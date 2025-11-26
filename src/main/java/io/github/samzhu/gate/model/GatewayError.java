package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 閘道錯誤回應格式
 *
 * <p>採用與 Anthropic API 相容的錯誤格式，確保 Claude Code CLI 和其他客戶端能正確解析錯誤。
 *
 * <p>錯誤結構：
 * <pre>{@code
 * {
 *   "type": "error",
 *   "error": {
 *     "type": "authentication_error|permission_error|api_error|...",
 *     "message": "錯誤描述"
 *   }
 * }
 * }</pre>
 *
 * <p>支援的錯誤類型：
 * <ul>
 *   <li>{@code authentication_error} - JWT 驗證失敗或 Token 過期</li>
 *   <li>{@code permission_error} - 權限不足或存取被拒絕</li>
 *   <li>{@code invalid_request_error} - 請求格式錯誤</li>
 *   <li>{@code overloaded_error} - 服務過載</li>
 *   <li>{@code api_error} - 一般 API 錯誤</li>
 * </ul>
 *
 * @see io.github.samzhu.gate.exception.GlobalExceptionHandler
 * @see <a href="https://platform.claude.com/docs/en/api/errors">Claude API Errors</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayError(
    String type,
    Error error
) {
    public record Error(
        String type,
        String message
    ) {}

    public static GatewayError authenticationError(String message) {
        return new GatewayError("error", new Error("authentication_error", message));
    }

    public static GatewayError permissionError(String message) {
        return new GatewayError("error", new Error("permission_error", message));
    }

    public static GatewayError overloadedError(String message) {
        return new GatewayError("error", new Error("overloaded_error", message));
    }

    public static GatewayError apiError(String message) {
        return new GatewayError("error", new Error("api_error", message));
    }

    public static GatewayError invalidRequestError(String message) {
        return new GatewayError("error", new Error("invalid_request_error", message));
    }
}
