package io.github.samzhu.gate.config;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.handler.NonStreamingProxyHandler;
import io.github.samzhu.gate.handler.SimpleProxyHandler;
import io.github.samzhu.gate.handler.StreamingProxyHandler;
import io.github.samzhu.gate.service.ApiKeyRotationService;
import io.github.samzhu.gate.service.ApiKeySelection;

/**
 * Spring Cloud Gateway Server MVC 路由配置
 *
 * <p>定義 Claude API 代理路由：
 * <ul>
 *   <li>{@code POST /v1/messages} - 訊息 API（支援串流/非串流）</li>
 *   <li>{@code POST /v1/messages/count_tokens} - Token 計算 API</li>
 * </ul>
 *
 * <p>{@code /v1/messages} 處理流程：
 * <ol>
 *   <li>從 JWT 取得用戶識別（subject）</li>
 *   <li>透過 Round Robin 策略選擇 API Key</li>
 *   <li>根據請求中的 {@code stream} 參數分流：
 *       <ul>
 *         <li>{@code stream: true} → 串流處理（SSE）</li>
 *         <li>{@code stream: false} → 非串流處理（JSON）</li>
 *       </ul>
 *   </li>
 *   <li>代理請求到 Anthropic API 並追蹤 Token 用量</li>
 * </ol>
 *
 * @see StreamingProxyHandler
 * @see NonStreamingProxyHandler
 * @see SimpleProxyHandler
 * @see <a href="https://platform.claude.com/docs/en/api/messages/create">Claude Messages API</a>
 * @see <a href="https://platform.claude.com/docs/en/api/messages/count_tokens">Claude Count Tokens API</a>
 */
@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);
    private static final String ANTHROPIC_HEADER_PREFIX = "anthropic-";

    private final ApiKeyRotationService apiKeyRotationService;
    private final StreamingProxyHandler streamingProxyHandler;
    private final NonStreamingProxyHandler nonStreamingProxyHandler;
    private final SimpleProxyHandler simpleProxyHandler;
    private final ObjectMapper objectMapper;

    public GatewayConfig(
            ApiKeyRotationService apiKeyRotationService,
            StreamingProxyHandler streamingProxyHandler,
            NonStreamingProxyHandler nonStreamingProxyHandler,
            SimpleProxyHandler simpleProxyHandler,
            ObjectMapper objectMapper) {
        this.apiKeyRotationService = apiKeyRotationService;
        this.streamingProxyHandler = streamingProxyHandler;
        this.nonStreamingProxyHandler = nonStreamingProxyHandler;
        this.simpleProxyHandler = simpleProxyHandler;
        this.objectMapper = objectMapper;
    }

    @Bean
    public RouterFunction<ServerResponse> messagesRoute() {
        return RouterFunctions.route()
            .POST("/v1/messages", this::handleMessages)
            .POST("/v1/messages/count_tokens", this::handleCountTokens)
            .build();
    }

    /**
     * 處理 /v1/messages 請求
     */
    private ServerResponse handleMessages(ServerRequest request) {
        try {
            // 讀取請求體
            String requestBody = request.body(String.class);

            // 取得 API Key
            ApiKeySelection selection = apiKeyRotationService.getNextApiKey();
            if (selection == null) {
                log.error("No API key available");
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"No Anthropic API key configured\"}}");
            }

            // 從 JWT 取得 subject
            String subject = getSubjectFromRequest(request);
            String keyAlias = selection.alias();
            String apiKey = selection.key();

            // 提取所有 anthropic-* headers（用於 Beta 功能、版本控制等）
            Map<String, String> anthropicHeaders = extractAnthropicHeaders(request);

            // 判斷是否為串流請求
            boolean isStreaming = isStreamingRequest(requestBody);

            log.debug("Routing request: subject={}, keyAlias={}, streaming={}, anthropicHeaders={}",
                subject, keyAlias, isStreaming, anthropicHeaders.keySet());

            if (isStreaming) {
                // 串流請求 - 使用 ServerResponse.sse()
                return streamingProxyHandler.handleStreaming(
                    requestBody, apiKey, subject, keyAlias, anthropicHeaders);
            } else {
                // 非串流請求 - 返回 JSON 回應
                return nonStreamingProxyHandler.handleNonStreaming(
                    requestBody, apiKey, subject, keyAlias, anthropicHeaders);
            }
        } catch (Exception e) {
            log.error("Error handling messages request: {}", e.getMessage(), e);
            return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" +
                    e.getMessage().replace("\"", "\\\"") + "\"}}");
        }
    }

    /**
     * 處理 /v1/messages/count_tokens 請求
     *
     * <p>Token 計算 API，用於計算 Message 的 Token 數量，不會產生實際的 API 呼叫費用。
     */
    private ServerResponse handleCountTokens(ServerRequest request) {
        try {
            String requestBody = request.body(String.class);

            ApiKeySelection selection = apiKeyRotationService.getNextApiKey();
            if (selection == null) {
                log.error("No API key available for count_tokens");
                return ServerResponse.status(500)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"No Anthropic API key configured\"}}");
            }

            // 提取所有 anthropic-* headers
            Map<String, String> anthropicHeaders = extractAnthropicHeaders(request);

            log.debug("Routing count_tokens request: keyAlias={}, anthropicHeaders={}",
                selection.alias(), anthropicHeaders.keySet());

            return simpleProxyHandler.proxyRequest(
                "/v1/messages/count_tokens",
                requestBody,
                selection.key(),
                selection.alias(),
                anthropicHeaders
            );
        } catch (Exception e) {
            log.error("Error handling count_tokens request: {}", e.getMessage(), e);
            return ServerResponse.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" +
                    e.getMessage().replace("\"", "\\\"") + "\"}}");
        }
    }

    /**
     * 從請求中取得 JWT subject
     */
    private String getSubjectFromRequest(ServerRequest request) {
        try {
            var principal = request.principal();
            if (principal.isPresent()) {
                Object p = principal.get();
                if (p instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth) {
                    Jwt jwt = jwtAuth.getToken();
                    return jwt.getSubject();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get subject from JWT: {}", e.getMessage());
        }
        return "anonymous";
    }

    /**
     * 判斷請求是否為串流模式
     */
    private boolean isStreamingRequest(String requestBody) {
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            if (root.has("stream")) {
                return root.get("stream").asBoolean(false);
            }
        } catch (Exception e) {
            log.warn("Failed to parse request body for stream detection: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 從請求中提取所有 anthropic-* headers
     *
     * <p>透明轉發所有 Anthropic 相關 headers，支援：
     * <ul>
     *   <li>{@code anthropic-version} - API 版本</li>
     *   <li>{@code anthropic-beta} - Beta 功能（如 context_management）</li>
     *   <li>其他未來可能新增的 anthropic-* headers</li>
     * </ul>
     *
     * @see <a href="https://platform.claude.com/docs/en/build-with-claude/context-editing">Context Editing</a>
     */
    private Map<String, String> extractAnthropicHeaders(ServerRequest request) {
        Map<String, String> anthropicHeaders = new HashMap<>();
        HttpHeaders headers = request.headers().asHttpHeaders();

        for (String headerName : headers.keySet()) {
            if (headerName.toLowerCase().startsWith(ANTHROPIC_HEADER_PREFIX)) {
                String value = headers.getFirst(headerName);
                if (value != null && !value.isBlank()) {
                    anthropicHeaders.put(headerName.toLowerCase(), value);
                }
            }
        }

        return anthropicHeaders;
    }
}
