package io.github.samzhu.gate.config;

/**
 * API Key 配置
 * 包含 alias (人類可讀別名) 和 value (實際 API Key)
 */
public record ApiKeyConfig(
    String alias,
    String value
) {
    public ApiKeyConfig {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("API Key alias cannot be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("API Key value cannot be blank");
        }
    }
}
