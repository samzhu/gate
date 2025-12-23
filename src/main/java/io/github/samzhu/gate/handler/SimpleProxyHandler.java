package io.github.samzhu.gate.handler;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
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

    private final RestClient restClient;

    /**
     * 建構子
     *
     * <p>重要：使用 Spring 自動配置的 {@code RestClient.Builder} 來建立 {@code RestClient}，
     * 這樣才能自動啟用 Tracing 功能（Trace Context 傳播、子 Span 建立）。
     *
     * @param anthropicProperties Anthropic API 配置
     * @param restClientBuilder Spring 自動配置的 RestClient.Builder（已包含 Tracing instrumentation）
     * @see <a href="https://docs.spring.io/spring-boot/reference/actuator/tracing.html">Spring Boot Tracing</a>
     */
    public SimpleProxyHandler(AnthropicProperties anthropicProperties, RestClient.Builder restClientBuilder) {
        // 使用 Spring 自動配置的 Builder，確保 Tracing 自動傳播
        this.restClient = restClientBuilder
            .baseUrl(anthropicProperties.baseUrl())
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
            // 建立 RestClient 請求
            RestClient.RequestBodySpec requestSpec = restClient.post()
                .uri(path)
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

            log.debug("Proxying request: path={}, keyAlias={}", path, keyAlias);

            // 使用 exchange() 方法來取得完整的回應資訊
            // exchange() 會自動傳播 Trace Context 並建立子 Span
            return requestSpec.body(requestBody)
                .exchange((request, response) -> {
                    String responseBody = new String(response.getBody().readAllBytes());
                    HttpStatusCode statusCode = response.getStatusCode();
                    long latencyMs = System.currentTimeMillis() - startTime;

                    String anthropicRequestId = response.getHeaders().getFirst("request-id");

                    if (!statusCode.is2xxSuccessful()) {
                        log.warn("Upstream error: path={}, status={}, anthropicRequestId={}, latencyMs={}",
                            path, statusCode.value(), anthropicRequestId, latencyMs);
                    } else {
                        log.debug("Proxy completed: path={}, keyAlias={}, anthropicRequestId={}, latencyMs={}",
                            path, keyAlias, anthropicRequestId, latencyMs);
                    }

                    return ServerResponse.status(HttpStatus.valueOf(statusCode.value()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(responseBody);
                });

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
