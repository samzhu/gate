package io.github.samzhu.gate.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.gate.config.AnthropicProperties;
import io.github.samzhu.gate.config.ApiKeyConfig;

/**
 * API Key 輪換服務
 *
 * <p>使用 Round Robin（循環輪換）策略分配 Anthropic API Key，實現：
 * <ul>
 *   <li>負載分散：將請求平均分配到多組 API Key</li>
 *   <li>配額管理：避免單一 Key 過快達到 Rate Limit</li>
 *   <li>容錯能力：支援多組 Key 備援</li>
 * </ul>
 *
 * <p>執行緒安全：使用 {@link AtomicInteger} 確保並發存取時的正確性。
 *
 * @see ApiKeyConfig
 * @see ApiKeySelection
 */
@Service
public class ApiKeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRotationService.class);

    private final List<ApiKeyConfig> apiKeys;
    private final AtomicInteger counter = new AtomicInteger(0);

    public ApiKeyRotationService(AnthropicProperties properties) {
        this.apiKeys = properties.keys();
        if (apiKeys == null || apiKeys.isEmpty()) {
            log.warn("No Anthropic API keys configured. Please configure anthropic.api.keys in application.yaml");
        } else {
            log.info("Loaded {} Anthropic API key(s): {}",
                apiKeys.size(),
                apiKeys.stream().map(ApiKeyConfig::alias).toList());
        }
    }

    /**
     * Round Robin 策略取得下一個 API Key 及其 alias
     *
     * @return ApiKeySelection 包含 key 和 alias，若無配置則返回 null
     */
    public ApiKeySelection getNextApiKey() {
        if (apiKeys == null || apiKeys.isEmpty()) {
            return null;
        }
        int index = Math.abs(counter.getAndIncrement() % apiKeys.size());
        ApiKeyConfig config = apiKeys.get(index);
        return new ApiKeySelection(config.value(), config.alias());
    }

    /**
     * 取得可用的 API Key 數量
     */
    public int getKeyCount() {
        return apiKeys != null ? apiKeys.size() : 0;
    }

    /**
     * 檢查是否有可用的 API Key
     */
    public boolean hasAvailableKeys() {
        return apiKeys != null && !apiKeys.isEmpty();
    }
}
