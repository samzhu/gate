package io.github.samzhu.gate.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic API 配置屬性
 */
@ConfigurationProperties(prefix = "anthropic.api")
public record AnthropicProperties(
    String baseUrl,
    List<ApiKeyConfig> keys
) {
    public AnthropicProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        if (keys == null) {
            keys = List.of();
        }
    }
}
