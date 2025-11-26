package io.github.samzhu.gate.filter;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import io.github.samzhu.gate.service.ApiKeyRotationService;
import io.github.samzhu.gate.service.ApiKeySelection;

/**
 * API Key 注入過濾器
 * 移除原始 Authorization Header，注入 Anthropic API Key
 */
@Component
public class ApiKeyInjectionFilter implements Function<ServerRequest, ServerRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyInjectionFilter.class);

    private static final String ANTHROPIC_API_KEY_HEADER = "x-api-key";
    private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final String SUBJECT_ATTRIBUTE = "gateway.subject";
    public static final String REQUEST_ID_ATTRIBUTE = "gateway.requestId";
    public static final String KEY_ALIAS_ATTRIBUTE = "gateway.keyAlias";

    private final ApiKeyRotationService apiKeyRotationService;

    public ApiKeyInjectionFilter(ApiKeyRotationService apiKeyRotationService) {
        this.apiKeyRotationService = apiKeyRotationService;
    }

    @Override
    public ServerRequest apply(ServerRequest request) {
        // 取得下一個 API Key 及其 alias
        ApiKeySelection selection = apiKeyRotationService.getNextApiKey();

        if (selection == null) {
            log.error("No API key available");
            throw new IllegalStateException("No Anthropic API key configured");
        }

        // 從 SecurityContext 取得 JWT 並提取 subject
        String subject = extractSubject();
        String requestId = java.util.UUID.randomUUID().toString();

        log.debug("Processing request: subject={}, requestId={}, keyAlias={}",
            subject, requestId, selection.alias());

        // 建立新的請求，移除 Authorization 並加入 x-api-key
        return ServerRequest.from(request)
            .headers(headers -> {
                headers.remove("Authorization");
                headers.set(ANTHROPIC_API_KEY_HEADER, selection.key());
                headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION);
            })
            .attribute(SUBJECT_ATTRIBUTE, subject)
            .attribute(REQUEST_ID_ATTRIBUTE, requestId)
            .attribute(KEY_ALIAS_ATTRIBUTE, selection.alias())
            .build();
    }

    /**
     * 從 SecurityContext 取得 JWT subject
     */
    private String extractSubject() {
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                return jwt.getSubject();
            }
        } catch (Exception e) {
            log.warn("Failed to extract subject from JWT: {}", e.getMessage());
        }
        return "anonymous";
    }
}
