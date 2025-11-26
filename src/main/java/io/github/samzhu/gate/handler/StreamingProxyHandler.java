package io.github.samzhu.gate.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

import io.micrometer.tracing.Tracer;
import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.config.AnthropicProperties;
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

    private final AnthropicProperties anthropicProperties;
    private final UsageEventPublisher usageEventPublisher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Tracer tracer;

    public StreamingProxyHandler(
            AnthropicProperties anthropicProperties,
            UsageEventPublisher usageEventPublisher,
            ObjectMapper objectMapper,
            Tracer tracer) {
        this.anthropicProperties = anthropicProperties;
        this.usageEventPublisher = usageEventPublisher;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 處理串流請求，返回 ServerResponse
     *
     * @param requestBody 請求體
     * @param apiKey      Anthropic API Key
     * @param subject     用戶識別碼
     * @param keyAlias    API Key 別名
     * @return ServerResponse with SSE
     */
    public ServerResponse handleStreaming(String requestBody, String apiKey, String subject,
                                           String keyAlias) {
        if (apiKey == null) {
            return ServerResponse.status(500)
                .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"No API key available\"}}");
        }

        String traceId = getCurrentTraceId();

        return ServerResponse.sse(sseBuilder -> {
            processStream(sseBuilder, requestBody, apiKey, subject, keyAlias, traceId);
        });
    }

    private void processStream(ServerResponse.SseBuilder sseBuilder, String requestBody, String apiKey,
                               String subject, String keyAlias, String traceId) {
        TokenExtractor tokenExtractor = new TokenExtractor();
        SseParser sseParser = new SseParser(objectMapper);
        String status = "success";
        String anthropicRequestId = null;

        try {
            URI uri = URI.create(anthropicProperties.baseUrl() + "/v1/messages");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
            );

            // 從回應 header 提取 Anthropic request-id
            anthropicRequestId = response.headers()
                .firstValue("request-id")
                .orElse(null);

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("Upstream error: status={}, body={}, anthropicRequestId={}",
                    response.statusCode(), errorBody, anthropicRequestId);
                sseBuilder.data(errorBody);
                sseBuilder.complete();
                status = "error";
                publishUsageEvent(tokenExtractor, status, keyAlias, traceId, anthropicRequestId, subject);
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

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
        } catch (IOException e) {
            log.error("IO error during streaming: {}", e.getMessage(), e);
            status = "error";
            try {
                sseBuilder.error(e);
            } catch (Exception ignored) {}
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Streaming interrupted: {}", e.getMessage());
            status = "error";
            try {
                sseBuilder.error(e);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.error("Unexpected error during streaming: {}", e.getMessage(), e);
            status = "error";
            try {
                sseBuilder.error(e);
            } catch (Exception ignored) {}
        } finally {
            publishUsageEvent(tokenExtractor, status, keyAlias, traceId, anthropicRequestId, subject);
        }
    }

    private void publishUsageEvent(TokenExtractor tokenExtractor, String status,
                                    String keyAlias, String traceId,
                                    String anthropicRequestId, String subject) {
        UsageEventData eventData = tokenExtractor.buildUsageEventData(status, keyAlias, traceId, anthropicRequestId);
        usageEventPublisher.publish(eventData, subject);

        log.debug("Stream completed: subject={}, keyAlias={}, traceId={}, anthropicRequestId={}, " +
            "inputTokens={}, outputTokens={}, latencyMs={}",
            subject, keyAlias, traceId, anthropicRequestId,
            tokenExtractor.getInputTokens(),
            tokenExtractor.getOutputTokens(), tokenExtractor.getLatencyMs());
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
}
