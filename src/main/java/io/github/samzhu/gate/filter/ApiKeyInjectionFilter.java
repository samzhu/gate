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
 *
 * <p>實作 Spring Cloud Gateway Server MVC 的請求過濾，執行：
 * <ul>
 *   <li>移除原始 {@code Authorization} Header（JWT Token）</li>
 *   <li>透過 Round Robin 取得 Anthropic API Key 並注入 {@code x-api-key} Header</li>
 *   <li>設定 {@code anthropic-version} Header</li>
 *   <li>從 JWT 提取 subject 並存入請求屬性供用量追蹤使用</li>
 *   <li>產生 requestId 供日誌追蹤</li>
 * </ul>
 *
 * <p>請求屬性：
 * <ul>
 *   <li>{@code gateway.subject} - 用戶識別碼（JWT sub claim）</li>
 *   <li>{@code gateway.requestId} - 請求唯一識別碼（UUID）</li>
 *   <li>{@code gateway.keyAlias} - 使用的 API Key 別名</li>
 * </ul>
 *
 * @see ApiKeyRotationService
 * @see io.github.samzhu.gate.config.GatewayConfig
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
