package io.github.samzhu.gate.handler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

import io.micrometer.tracing.Tracer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.config.AnthropicProperties;
import io.github.samzhu.gate.model.UsageEventData;
import io.github.samzhu.gate.service.UsageEventPublisher;

/**
 * 非串流代理處理器
 * 處理 stream: false 的 Claude API 請求
 */
@Component
public class NonStreamingProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(NonStreamingProxyHandler.class);

    private final AnthropicProperties anthropicProperties;
    private final UsageEventPublisher usageEventPublisher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Tracer tracer;

    public NonStreamingProxyHandler(
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
     * 處理非串流請求
     *
     * @param requestBody 請求體
     * @param apiKey      Anthropic API Key
     * @param subject     用戶識別碼
     * @param requestId   請求 ID
     * @param keyAlias    API Key 別名
     * @return ServerResponse
     */
    public ServerResponse handleNonStreaming(String requestBody, String apiKey, String subject,
                                              String requestId, String keyAlias) {
        long startTime = System.currentTimeMillis();
        String status = "success";
        String traceId = getCurrentTraceId();

        try {
            URI uri = URI.create(anthropicProperties.baseUrl() + "/v1/messages");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            String responseBody = response.body();
            int statusCode = response.statusCode();

            if (statusCode != 200) {
                log.error("Upstream error: status={}, body={}", statusCode, responseBody);
                status = "error";
            }

            // 解析回應並提取用量資訊
            UsageEventData eventData = parseUsageFromResponse(
                responseBody, startTime, status, keyAlias, traceId);

            // 發送用量事件
            usageEventPublisher.publish(eventData, requestId, subject);

            log.debug("Non-streaming completed: subject={}, requestId={}, keyAlias={}, traceId={}, " +
                "inputTokens={}, outputTokens={}, latencyMs={}",
                subject, requestId, keyAlias, traceId,
                eventData.inputTokens(), eventData.outputTokens(), eventData.latencyMs());

            return ServerResponse.status(HttpStatus.valueOf(statusCode))
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseBody);

        } catch (IOException e) {
            log.error("IO error during non-streaming request: {}", e.getMessage(), e);
            status = "error";
            return buildErrorResponse(e.getMessage(), startTime, keyAlias, traceId, requestId, subject);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Non-streaming request interrupted: {}", e.getMessage());
            status = "error";
            return buildErrorResponse("Request interrupted", startTime, keyAlias, traceId, requestId, subject);
        } catch (Exception e) {
            log.error("Unexpected error during non-streaming request: {}", e.getMessage(), e);
            status = "error";
            return buildErrorResponse(e.getMessage(), startTime, keyAlias, traceId, requestId, subject);
        }
    }

    /**
     * 從回應中解析用量資訊
     */
    private UsageEventData parseUsageFromResponse(String responseBody, long startTime,
                                                   String status, String keyAlias, String traceId) {
        UsageEventData.Builder builder = UsageEventData.builder()
            .latencyMs(System.currentTimeMillis() - startTime)
            .stream(false)
            .status(status)
            .keyAlias(keyAlias)
            .traceId(traceId);

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 提取 model
            if (root.has("model")) {
                builder.model(root.get("model").stringValue());
            }

            // 提取 message id
            if (root.has("id")) {
                builder.messageId(root.get("id").stringValue());
            }

            // 提取 stop_reason
            if (root.has("stop_reason") && !root.get("stop_reason").isNull()) {
                builder.stopReason(root.get("stop_reason").stringValue());
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
                    builder.errorType(error.get("type").stringValue());
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
                                               String requestId, String subject) {
        // 發送錯誤用量事件
        UsageEventData eventData = UsageEventData.builder()
            .latencyMs(System.currentTimeMillis() - startTime)
            .stream(false)
            .status("error")
            .errorType("gateway_error")
            .keyAlias(keyAlias)
            .traceId(traceId)
            .build();

        usageEventPublisher.publish(eventData, requestId, subject);

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
