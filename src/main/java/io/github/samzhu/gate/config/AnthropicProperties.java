package io.github.samzhu.gate.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic API 配置屬性
 *
 * <p>從 application.yaml 中的 {@code anthropic.api} 前綴載入配置：
 * <ul>
 *   <li>{@code baseUrl} - Anthropic API 基礎 URL（預設: https://api.anthropic.com）</li>
 *   <li>{@code keys} - API Key 配置列表，支援多組 Key 輪換</li>
 * </ul>
 *
 * <p>配置範例：
 * <pre>
 * anthropic:
 *   api:
 *     base-url: https://api.anthropic.com
 *     keys:
 *       - alias: "primary"
 *         value: sk-ant-api03-xxx...
 *       - alias: "secondary"
 *         value: sk-ant-api03-yyy...
 * </pre>
 *
 * @param baseUrl Anthropic API 基礎 URL
 * @param keys API Key 配置列表
 * @see ApiKeyConfig
 * @see io.github.samzhu.gate.service.ApiKeyRotationService
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
