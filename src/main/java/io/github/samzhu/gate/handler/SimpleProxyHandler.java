package io.github.samzhu.gate.handler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

import io.github.samzhu.gate.config.AnthropicProperties;

/**
 * 簡單代理處理器
 *
 * <p>處理不需要追蹤 Token 用量的 Claude API 請求，例如：
 * <ul>
 *   <li>{@code POST /v1/messages/count_tokens} - Token 計算</li>
 * </ul>
 *
 * <p>此處理器直接轉發請求到 Anthropic API，不進行用量追蹤。
 *
 * @see NonStreamingProxyHandler
 * @see <a href="https://platform.claude.com/docs/en/api/messages/count_tokens">Claude Count Tokens API</a>
 */
@Component
public class SimpleProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(SimpleProxyHandler.class);

    private final AnthropicProperties anthropicProperties;
    private final HttpClient httpClient;

    public SimpleProxyHandler(AnthropicProperties anthropicProperties) {
        this.anthropicProperties = anthropicProperties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 代理請求到指定的 Anthropic API 端點
     *
     * @param path             API 路徑（例如 /v1/messages/count_tokens）
     * @param requestBody      請求體
     * @param apiKey           Anthropic API Key
     * @param keyAlias         API Key 別名（用於日誌）
     * @param anthropicHeaders 所有 anthropic-* headers（透明轉發）
     * @return ServerResponse
     */
    public ServerResponse proxyRequest(String path, String requestBody, String apiKey,
                                        String keyAlias, Map<String, String> anthropicHeaders) {
        long startTime = System.currentTimeMillis();

        try {
            URI uri = URI.create(anthropicProperties.baseUrl() + path);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey);

            // 設定預設 anthropic-version（如果客戶端沒有提供）
            if (!anthropicHeaders.containsKey("anthropic-version")) {
                requestBuilder.header("anthropic-version", "2023-06-01");
            }

            // 透明轉發所有 anthropic-* headers
            for (Map.Entry<String, String> entry : anthropicHeaders.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            log.debug("Proxying request: path={}, keyAlias={}", path, keyAlias);

            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            String responseBody = response.body();
            int statusCode = response.statusCode();
            long latencyMs = System.currentTimeMillis() - startTime;

            String anthropicRequestId = response.headers()
                .firstValue("request-id")
                .orElse(null);

            if (statusCode != 200) {
                log.warn("Upstream error: path={}, status={}, anthropicRequestId={}, latencyMs={}",
                    path, statusCode, anthropicRequestId, latencyMs);
            } else {
                log.debug("Proxy completed: path={}, keyAlias={}, anthropicRequestId={}, latencyMs={}",
                    path, keyAlias, anthropicRequestId, latencyMs);
            }

            return ServerResponse.status(HttpStatus.valueOf(statusCode))
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseBody);

        } catch (IOException e) {
            log.error("IO error during proxy request to {}: {}", path, e.getMessage(), e);
            return buildErrorResponse(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Proxy request to {} interrupted: {}", path, e.getMessage());
            return buildErrorResponse("Request interrupted");
        } catch (Exception e) {
            log.error("Unexpected error during proxy request to {}: {}", path, e.getMessage(), e);
            return buildErrorResponse(e.getMessage());
        }
    }

    private ServerResponse buildErrorResponse(String message) {
        String errorBody = String.format(
            "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"%s\"}}",
            message.replace("\"", "\\\"")
        );

        return ServerResponse.status(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_JSON)
            .body(errorBody);
    }
}
