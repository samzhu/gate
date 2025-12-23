package io.github.samzhu.gate.handler;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.ServerResponse;

import io.micrometer.tracing.Tracer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.config.AnthropicProperties;
import io.github.samzhu.gate.model.UsageEventData;
import io.github.samzhu.gate.service.UsageEventPublisher;

/**
 * 非串流代理處理器
 *
 * <p>處理 Claude API 的非串流 JSON 回應（{@code stream: false}），執行以下功能：
 * <ul>
 *   <li>代理請求到 Anthropic API</li>
 *   <li>解析完整 JSON 回應提取 Token 用量：
 *       <ul>
 *         <li>{@code usage.input_tokens} - 輸入 Token 數</li>
 *         <li>{@code usage.output_tokens} - 輸出 Token 數</li>
 *         <li>{@code usage.cache_creation_input_tokens} - 快取建立 Token</li>
 *         <li>{@code usage.cache_read_input_tokens} - 快取讀取 Token</li>
 *       </ul>
 *   </li>
 *   <li>發送 CloudEvents 格式的用量事件到 Pub/Sub</li>
 * </ul>
 *
 * <p>注意：Claude Code CLI 主要使用串流模式，非串流模式較少使用。
 *
 * @see StreamingProxyHandler
 * @see <a href="https://platform.claude.com/docs/en/api/messages/create">Claude Messages API</a>
 */
@Component
public class NonStreamingProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(NonStreamingProxyHandler.class);

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
    public NonStreamingProxyHandler(
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
     * 處理非串流請求
     *
     * @param requestBody      請求體
     * @param apiKey           Anthropic API Key
     * @param subject          用戶識別碼
     * @param keyAlias         API Key 別名
     * @param anthropicHeaders 所有 anthropic-* headers（透明轉發）
     * @return ServerResponse
     */
    public ServerResponse handleNonStreaming(String requestBody, String apiKey, String subject,
                                              String keyAlias, Map<String, String> anthropicHeaders) {
        long startTime = System.currentTimeMillis();
        String traceId = getCurrentTraceId();

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

            // 使用 exchange() 方法來取得完整的回應資訊
            // exchange() 會自動傳播 Trace Context 並建立子 Span
            return requestSpec.body(requestBody)
                .exchange((request, response) -> {
                    String responseBody = new String(response.getBody().readAllBytes());
                    HttpStatusCode statusCode = response.getStatusCode();

                    // 從回應 header 提取 Anthropic request-id
                    String anthropicRequestId = response.getHeaders().getFirst("request-id");

                    String status = "success";
                    if (!statusCode.is2xxSuccessful()) {
                        log.error("Upstream error: status={}, body={}, anthropicRequestId={}",
                            statusCode.value(), responseBody, anthropicRequestId);
                        status = "error";
                    }

                    // 解析回應並提取用量資訊
                    UsageEventData eventData = parseUsageFromResponse(
                        responseBody, startTime, status, keyAlias, traceId, anthropicRequestId, subject);

                    // 發送用量事件
                    usageEventPublisher.publish(eventData);

                    // 記錄 Token 用量 - 用於監控和計費追蹤
                    log.info("Token usage: subject={}, inputTokens={}, outputTokens={}, model={}, latencyMs={}",
                        subject,
                        eventData.inputTokens(),
                        eventData.outputTokens(),
                        eventData.model(),
                        eventData.latencyMs());

                    log.debug("Non-streaming completed: keyAlias={}, traceId={}, anthropicRequestId={}, messageId={}",
                        keyAlias, traceId, anthropicRequestId, eventData.messageId());

                    return ServerResponse.status(HttpStatus.valueOf(statusCode.value()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody);
                });

        } catch (Exception e) {
            log.error("Unexpected error during non-streaming request: {}", e.getMessage(), e);
            return buildErrorResponse(e.getMessage(), startTime, keyAlias, traceId, subject);
        }
    }

    /**
     * 從回應中解析用量資訊
     */
    private UsageEventData parseUsageFromResponse(String responseBody, long startTime,
                                                   String status, String keyAlias, String traceId,
                                                   String anthropicRequestId, String userId) {
        UsageEventData.Builder builder = UsageEventData.builder()
            .userId(userId)
            .eventTime(Instant.now())
            .latencyMs(System.currentTimeMillis() - startTime)
            .stream(false)
            .status(status)
            .keyAlias(keyAlias)
            .traceId(traceId)
            .anthropicRequestId(anthropicRequestId);

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 提取 model
            if (root.has("model")) {
                builder.model(root.get("model").asText());
            }

            // 提取 message id
            if (root.has("id")) {
                builder.messageId(root.get("id").asText());
            }

            // 提取 stop_reason
            if (root.has("stop_reason") && !root.get("stop_reason").isNull()) {
                builder.stopReason(root.get("stop_reason").asText());
            }

            // 提取 usage
            if (root.has("usage")) {
                JsonNode usage = root.get("usage");

                if (usage.has("input_tokens")) {
                    builder.inputTokens(usage.get("input_tokens").asInt());
                }
                if (usage.has("output_tokens")) {
                    builder.outputTokens(usage.get("output_tokens").asInt());
                }
                if (usage.has("cache_creation_input_tokens")) {
                    builder.cacheCreationTokens(usage.get("cache_creation_input_tokens").asInt());
                }
                if (usage.has("cache_read_input_tokens")) {
                    builder.cacheReadTokens(usage.get("cache_read_input_tokens").asInt());
                }
            }

            // 提取錯誤類型 (如果是錯誤回應)
            if (root.has("error")) {
                JsonNode error = root.get("error");
                if (error.has("type")) {
                    builder.errorType(error.get("type").asText());
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse usage from response: {}", e.getMessage());
        }

        return builder.build();
    }

    /**
     * 建立錯誤回應
     */
    private ServerResponse buildErrorResponse(String message, long startTime,
                                               String keyAlias, String traceId,
                                               String subject) {
        // 發送錯誤用量事件
        UsageEventData eventData = UsageEventData.builder()
            .userId(subject)
            .eventTime(Instant.now())
            .latencyMs(System.currentTimeMillis() - startTime)
            .stream(false)
            .status("error")
            .errorType("gateway_error")
            .keyAlias(keyAlias)
            .traceId(traceId)
            .build();

        usageEventPublisher.publish(eventData);

        String errorBody = String.format(
            "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"%s\"}}",
            message.replace("\"", "\\\"")
        );

        return ServerResponse.status(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(errorBody);
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
