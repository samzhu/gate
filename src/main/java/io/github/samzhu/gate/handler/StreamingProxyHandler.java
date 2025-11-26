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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.config.AnthropicProperties;
import io.github.samzhu.gate.model.UsageEventData;
import io.github.samzhu.gate.service.ApiKeyRotationService;
import io.github.samzhu.gate.service.UsageEventPublisher;
import io.github.samzhu.gate.util.SseParser;
import io.github.samzhu.gate.util.TokenExtractor;

/**
 * 串流代理處理器
 * 使用 Web MVC SseEmitter 處理 Claude API 串流回應
 */
@Component
public class StreamingProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingProxyHandler.class);

    private final AnthropicProperties anthropicProperties;
    private final ApiKeyRotationService apiKeyRotationService;
    private final UsageEventPublisher usageEventPublisher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    public StreamingProxyHandler(
            AnthropicProperties anthropicProperties,
            ApiKeyRotationService apiKeyRotationService,
            UsageEventPublisher usageEventPublisher,
            ObjectMapper objectMapper) {
        this.anthropicProperties = anthropicProperties;
        this.apiKeyRotationService = apiKeyRotationService;
        this.usageEventPublisher = usageEventPublisher;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 處理串流請求
     *
     * @param requestBody 請求體
     * @param subject     用戶識別碼
     * @param requestId   請求 ID
     * @return SseEmitter
     */
    public SseEmitter handleStreaming(String requestBody, String subject, String requestId) {
        // 10 分鐘超時
        SseEmitter emitter = new SseEmitter(600_000L);

        String apiKey = apiKeyRotationService.getNextApiKey();
        if (apiKey == null) {
            emitter.completeWithError(new IllegalStateException("No API key available"));
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            processStream(emitter, requestBody, apiKey, subject, requestId);
        }, executor);

        return emitter;
    }

    private void processStream(SseEmitter emitter, String requestBody, String apiKey,
                               String subject, String requestId) {
        TokenExtractor tokenExtractor = new TokenExtractor();
        SseParser sseParser = new SseParser(objectMapper);
        String status = "success";

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

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("Upstream error: status={}, body={}", response.statusCode(), errorBody);
                emitter.send(SseEmitter.event().data(errorBody));
                emitter.complete();
                status = "error";
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
                            if (currentEventType != null) {
                                emitter.send(SseEmitter.event()
                                    .name(currentEventType)
                                    .data(dataContent != null ? dataContent : eventData));
                            } else if (dataContent != null) {
                                emitter.send(SseEmitter.event().data(dataContent));
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

                emitter.complete();
            }
        } catch (IOException e) {
            log.error("IO error during streaming: {}", e.getMessage(), e);
            status = "error";
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {}
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Streaming interrupted: {}", e.getMessage());
            status = "error";
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.error("Unexpected error during streaming: {}", e.getMessage(), e);
            status = "error";
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {}
        } finally {
            // 發送用量事件
            UsageEventData eventData = tokenExtractor.buildUsageEventData(status);
            usageEventPublisher.publish(eventData, requestId, subject);

            log.debug("Stream completed: subject={}, requestId={}, inputTokens={}, outputTokens={}, latencyMs={}",
                subject, requestId, tokenExtractor.getInputTokens(),
                tokenExtractor.getOutputTokens(), tokenExtractor.getLatencyMs());
        }
    }
}
