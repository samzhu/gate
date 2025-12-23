package io.github.samzhu.gate.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.function.ServerResponse;

import io.micrometer.tracing.Tracer;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.config.AnthropicProperties; // 用於建構子中取得 baseUrl
import io.github.samzhu.gate.model.UsageEventData;
import io.github.samzhu.gate.service.UsageEventPublisher;
import io.github.samzhu.gate.util.SseParser;
import io.github.samzhu.gate.util.TokenExtractor;

/**
 * 串流代理處理器
 *
 * <p>處理 Claude API 的 SSE（Server-Sent Events）串流回應，執行以下功能：
 * <ul>
 *   <li>代理請求到 Anthropic API（{@code stream: true}）</li>
 *   <li>透傳 SSE 事件給客戶端（即時回應）</li>
 *   <li>解析 SSE 事件提取 Token 用量：
 *       <ul>
 *         <li>{@code message_start} - 提取 input_tokens、model、message_id</li>
 *         <li>{@code message_delta} - 提取 output_tokens、stop_reason</li>
 *       </ul>
 *   </li>
 *   <li>串流結束後發送 CloudEvents 格式的用量事件</li>
 * </ul>
 *
 * <p>使用 {@link ServerResponse#sse} 建立 SSE 回應，支援長達 10 分鐘的串流。
 *
 * @see NonStreamingProxyHandler
 * @see TokenExtractor
 * @see <a href="https://platform.claude.com/docs/en/build-with-claude/streaming">Claude Streaming</a>
 */
@Component
public class StreamingProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingProxyHandler.class);

    private final UsageEventPublisher usageEventPublisher;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Tracer tracer;

    /**
     * 建構子
     *
     * <p>重要：使用 Spring 自動配置的 {@code RestClient.Builder} 來建立 {@code RestClient}，
     * 這樣才能自動啟用 Tracing 功能（Trace Context 傳播、子 Span 建立）。
     *
     * @param anthropicProperties Anthropic API 配置
     * @param usageEventPublisher 用量事件發布器
     * @param objectMapper JSON 物件映射器
     * @param restClientBuilder Spring 自動配置的 RestClient.Builder（已包含 Tracing instrumentation）
     * @param tracer Micrometer Tracer
     * @see <a href="https://docs.spring.io/spring-boot/reference/actuator/tracing.html">Spring Boot Tracing</a>
     */
    public StreamingProxyHandler(
            AnthropicProperties anthropicProperties,
            UsageEventPublisher usageEventPublisher,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            Tracer tracer) {
        this.usageEventPublisher = usageEventPublisher;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        // 使用 Spring 自動配置的 Builder，確保 Tracing 自動傳播
        this.restClient = restClientBuilder
            .baseUrl(anthropicProperties.baseUrl())
            .build();
    }

    /**
     * 處理串流請求，返回 ServerResponse
     *
     * @param requestBody      請求體
     * @param apiKey           Anthropic API Key
     * @param subject          用戶識別碼
     * @param keyAlias         API Key 別名
     * @param anthropicHeaders 所有 anthropic-* headers（透明轉發）
     * @return ServerResponse with SSE
     */
    public ServerResponse handleStreaming(String requestBody, String apiKey, String subject,
                                           String keyAlias, Map<String, String> anthropicHeaders) {
        if (apiKey == null) {
            return ServerResponse.status(500)
                .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"No API key available\"}}");
        }

        String traceId = getCurrentTraceId();

        // 捕獲當前 span，SSE callback 會在不同 thread 執行，需要手動傳播 trace context
        // 參考: https://github.com/micrometer-metrics/context-propagation
        var currentSpan = tracer.currentSpan();

        return ServerResponse.sse(sseBuilder -> {
            // 在 SSE callback thread 中恢復 trace context
            // 這樣 RestClient 呼叫才能建立正確的子 span
            if (currentSpan != null) {
                try (var ignored = tracer.withSpan(currentSpan)) {
                    processStream(sseBuilder, requestBody, apiKey, subject, keyAlias, traceId, anthropicHeaders);
                }
            } else {
                processStream(sseBuilder, requestBody, apiKey, subject, keyAlias, traceId, anthropicHeaders);
            }
        });
    }

    private void processStream(ServerResponse.SseBuilder sseBuilder, String requestBody, String apiKey,
                               String subject, String keyAlias, String traceId,
                               Map<String, String> anthropicHeaders) {
        TokenExtractor tokenExtractor = new TokenExtractor();
        SseParser sseParser = new SseParser(objectMapper);
        final String[] status = {"success"};
        final String[] anthropicRequestId = {null};

        try {
            // 建立 RestClient 請求
            RestClient.RequestBodySpec requestSpec = restClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", apiKey);

            // 設定預設 anthropic-version（如果客戶端沒有提供）
            if (!anthropicHeaders.containsKey("anthropic-version")) {
                requestSpec.header("anthropic-version", "2023-06-01");
            }

            // 透明轉發所有 anthropic-* headers
            for (Map.Entry<String, String> entry : anthropicHeaders.entrySet()) {
                requestSpec.header(entry.getKey(), entry.getValue());
            }

            // 使用 exchange() 方法來取得 InputStream，這樣可以處理串流回應
            // exchange() 會自動傳播 Trace Context 並建立子 Span
            requestSpec.body(requestBody)
                .exchange((request, response) -> {
                    try {
                        // 從回應 header 提取 Anthropic request-id
                        anthropicRequestId[0] = response.getHeaders().getFirst("request-id");
                        HttpStatusCode statusCode = response.getStatusCode();

                        if (!statusCode.is2xxSuccessful()) {
                            try (InputStream errorStream = response.getBody()) {
                                String errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                                log.error("Upstream error: status={}, body={}, anthropicRequestId={}",
                                    statusCode.value(), errorBody, anthropicRequestId[0]);
                                sseBuilder.data(errorBody);
                                sseBuilder.complete();
                                status[0] = "error";
                            }
                            return null;
                        }

                        // 處理成功的串流回應
                        processStreamResponse(response.getBody(), sseBuilder, sseParser, tokenExtractor);
                        return null;
                    } catch (AsyncRequestNotUsableException e) {
                        // 客戶端提前斷開連接
                        log.warn("Client disconnected during streaming: {} (this is common for long LLM responses)",
                            e.getMessage());
                        status[0] = "client_disconnected";
                        return null;
                    } catch (IOException e) {
                        if (isClientDisconnectedException(e)) {
                            log.warn("Client disconnected (Broken pipe) during streaming: {}", e.getMessage());
                            status[0] = "client_disconnected";
                        } else {
                            log.error("IO error during streaming: {}", e.getMessage(), e);
                            status[0] = "error";
                            try {
                                sseBuilder.error(e);
                            } catch (Exception ignored) {}
                        }
                        return null;
                    }
                });
        } catch (Exception e) {
            // 檢查根本原因是否為客戶端斷開
            if (isClientDisconnectedException(e)) {
                log.warn("Client disconnected during streaming: {}", e.getMessage());
                status[0] = "client_disconnected";
            } else {
                log.error("Unexpected error during streaming: {}", e.getMessage(), e);
                status[0] = "error";
                try {
                    sseBuilder.error(e);
                } catch (Exception ignored) {}
            }
        } finally {
            publishUsageEvent(tokenExtractor, status[0], keyAlias, traceId, anthropicRequestId[0], subject);
        }
    }

    /**
     * 處理串流回應的內部方法
     */
    private void processStreamResponse(InputStream inputStream, ServerResponse.SseBuilder sseBuilder,
                                        SseParser sseParser, TokenExtractor tokenExtractor) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            StringBuilder eventBuilder = new StringBuilder();
            String currentEventType = null;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 空行表示事件結束，發送累積的事件
                    if (eventBuilder.length() > 0) {
                        String eventData = eventBuilder.toString();

                        // 解析並提取 Token 用量
                        String dataContent = sseParser.extractData(eventData);
                        if (dataContent != null) {
                            var streamEvent = sseParser.parse(dataContent);
                            if (streamEvent != null) {
                                tokenExtractor.processEvent(streamEvent);
                            }
                        }

                        // 轉發原始事件給客戶端
                        if (currentEventType != null && dataContent != null) {
                            sseBuilder.event(currentEventType);
                            sseBuilder.data(dataContent);
                        } else if (dataContent != null) {
                            sseBuilder.data(dataContent);
                        }

                        eventBuilder.setLength(0);
                        currentEventType = null;
                    }
                    continue;
                }

                if (line.startsWith("event:")) {
                    currentEventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (eventBuilder.length() > 0) {
                        eventBuilder.append("\n");
                    }
                    eventBuilder.append(line);
                }
            }

            sseBuilder.complete();
        }
    }

    private void publishUsageEvent(TokenExtractor tokenExtractor, String status,
                                    String keyAlias, String traceId,
                                    String anthropicRequestId, String subject) {
        UsageEventData eventData = tokenExtractor.buildUsageEventData(status, keyAlias, traceId, anthropicRequestId, subject);
        usageEventPublisher.publish(eventData);

        // 記錄 Token 用量 - 用於監控和計費追蹤
        log.info("Token usage: subject={}, inputTokens={}, outputTokens={}, model={}, latencyMs={}",
            subject,
            tokenExtractor.getInputTokens(),
            tokenExtractor.getOutputTokens(),
            tokenExtractor.getModel(),
            tokenExtractor.getLatencyMs());

        log.debug("Stream completed: keyAlias={}, traceId={}, anthropicRequestId={}, messageId={}",
            keyAlias, traceId, anthropicRequestId, tokenExtractor.getMessageId());
    }

    /**
     * 取得當前 OpenTelemetry Trace ID
     */
    private String getCurrentTraceId() {
        try {
            var currentSpan = tracer.currentSpan();
            if (currentSpan != null && currentSpan.context() != null) {
                return currentSpan.context().traceId();
            }
        } catch (Exception e) {
            log.debug("Failed to get trace ID: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 檢查異常是否為客戶端斷開連接導致
     * <p>常見情況：
     * <ul>
     *   <li>Broken pipe - 客戶端關閉連接後伺服器嘗試寫入</li>
     *   <li>Connection reset - 客戶端強制重置連接</li>
     *   <li>ClientAbortException - Tomcat 檢測到客戶端中斷</li>
     * </ul>
     *
     * @param e 要檢查的異常
     * @return 如果是客戶端斷開導致的異常則返回 true
     */
    private boolean isClientDisconnectedException(Throwable e) {
        if (e == null) {
            return false;
        }

        // 檢查異常類型
        String className = e.getClass().getName();
        if (className.contains("ClientAbortException") ||
            className.contains("AsyncRequestNotUsableException")) {
            return true;
        }

        // 檢查異常訊息
        String message = e.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("broken pipe") ||
                lowerMessage.contains("connection reset") ||
                lowerMessage.contains("client disconnected")) {
                return true;
            }
        }

        // 遞迴檢查根本原因
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            return isClientDisconnectedException(cause);
        }

        return false;
    }
}
