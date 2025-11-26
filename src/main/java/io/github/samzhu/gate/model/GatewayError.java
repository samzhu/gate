package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 閘道錯誤回應格式 (相容 Anthropic API 錯誤格式)
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
