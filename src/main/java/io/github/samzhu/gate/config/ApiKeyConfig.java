package io.github.samzhu.gate.config;

/**
 * Anthropic API Key 配置
 *
 * <p>每個 API Key 配置包含：
 * <ul>
 *   <li>{@code alias} - 人類可讀的別名，用於日誌追蹤和審計（不暴露實際 Key）</li>
 *   <li>{@code value} - 實際的 Anthropic API Key（以 sk-ant- 開頭）</li>
 * </ul>
 *
 * <p>配置範例：
 * <pre>
 * anthropic:
 *   api:
 *     keys:
 *       - alias: "primary"
 *         value: sk-ant-api03-xxx...
 * </pre>
 *
 * @param alias API Key 別名（必填，用於追蹤）
 * @param value 實際的 Anthropic API Key（必填）
 * @see AnthropicProperties
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
