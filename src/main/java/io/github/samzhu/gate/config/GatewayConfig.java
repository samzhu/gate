package io.github.samzhu.gate.config;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.handler.NonStreamingProxyHandler;
import io.github.samzhu.gate.handler.StreamingProxyHandler;
import io.github.samzhu.gate.service.ApiKeyRotationService;
import io.github.samzhu.gate.service.ApiKeySelection;

/**
 * Spring Cloud Gateway Server MVC 配置
 * 使用 RouterFunction 定義 /v1/messages 路由
 */
@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    private final ApiKeyRotationService apiKeyRotationService;
    private final StreamingProxyHandler streamingProxyHandler;
    private final NonStreamingProxyHandler nonStreamingProxyHandler;
    private final ObjectMapper objectMapper;

    public GatewayConfig(
            ApiKeyRotationService apiKeyRotationService,
            StreamingProxyHandler streamingProxyHandler,
            NonStreamingProxyHandler nonStreamingProxyHandler,
            ObjectMapper objectMapper) {
        this.apiKeyRotationService = apiKeyRotationService;
        this.streamingProxyHandler = streamingProxyHandler;
        this.nonStreamingProxyHandler = nonStreamingProxyHandler;
        this.objectMapper = objectMapper;
    }

    @Bean
    public RouterFunction<ServerResponse> messagesRoute() {
        return RouterFunctions.route()
            .POST("/v1/messages", this::handleMessages)
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
            String requestId = UUID.randomUUID().toString();
            String keyAlias = selection.alias();
            String apiKey = selection.key();

            // 判斷是否為串流請求
            boolean isStreaming = isStreamingRequest(requestBody);

            log.debug("Routing request: subject={}, requestId={}, keyAlias={}, streaming={}",
                subject, requestId, keyAlias, isStreaming);

            if (isStreaming) {
                // 串流請求 - 使用 ServerResponse.sse()
                return streamingProxyHandler.handleStreaming(
                    requestBody, apiKey, subject, requestId, keyAlias);
            } else {
                // 非串流請求 - 返回 JSON 回應
                return nonStreamingProxyHandler.handleNonStreaming(
                    requestBody, apiKey, subject, requestId, keyAlias);
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
}
