package io.github.samzhu.gate.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.samzhu.gate.model.GatewayError;

/**
 * 全域異常處理器
 *
 * <p>統一處理閘道異常並返回 Anthropic API 相容的錯誤格式，
 * 確保 Claude Code CLI 和其他客戶端能正確解析錯誤。
 *
 * <p>處理的異常類型：
 * <ul>
 *   <li>{@code AuthenticationException} - 401 Unauthorized（JWT 驗證失敗）</li>
 *   <li>{@code AccessDeniedException} - 403 Forbidden（權限不足）</li>
 *   <li>{@code CallNotPermittedException} - 503 Service Unavailable（熔斷器開路）</li>
 *   <li>{@code IllegalStateException} - 503/500（配置錯誤）</li>
 *   <li>{@code Exception} - 500 Internal Server Error（未預期錯誤）</li>
 * </ul>
 *
 * @see GatewayError
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 處理認證異常
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<GatewayError> handleAuthenticationException(AuthenticationException e) {
        log.warn("Authentication failed: {}", e.getMessage());
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(GatewayError.authenticationError("Invalid or expired access token"));
    }

    /**
     * 處理授權異常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<GatewayError> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(GatewayError.permissionError("Access denied"));
    }

    /**
     * 處理 Circuit Breaker 開路異常
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<GatewayError> handleCircuitBreakerOpen(CallNotPermittedException e) {
        log.warn("Circuit breaker open: {}", e.getMessage());
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(GatewayError.overloadedError("LLM service is temporarily unavailable. Please retry later."));
    }

    /**
     * 處理無 API Key 異常
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<GatewayError> handleIllegalStateException(IllegalStateException e) {
        log.error("Illegal state: {}", e.getMessage());
        if (e.getMessage() != null && e.getMessage().contains("API key")) {
            return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(GatewayError.apiError("Service configuration error"));
        }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(GatewayError.apiError("Internal server error"));
    }

    /**
     * 處理其他未預期異常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayError> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(GatewayError.apiError("Internal server error"));
    }
}
