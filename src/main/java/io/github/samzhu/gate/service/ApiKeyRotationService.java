package io.github.samzhu.gate.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.samzhu.gate.config.AnthropicProperties;

/**
 * API Key 輪換服務
 * 使用 Round Robin 策略分配 Anthropic API Key
 */
@Service
public class ApiKeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRotationService.class);

    private final List<String> apiKeys;
    private final AtomicInteger counter = new AtomicInteger(0);

    public ApiKeyRotationService(AnthropicProperties properties) {
        this.apiKeys = properties.keys();
        if (apiKeys == null || apiKeys.isEmpty()) {
            log.warn("No Anthropic API keys configured. Please set ANTHROPIC_API_KEYS environment variable.");
        } else {
            log.info("Loaded {} Anthropic API key(s)", apiKeys.size());
        }
    }

    /**
     * Round Robin 策略取得下一個 API Key
     *
     * @return API Key，若無配置則返回 null
     */
    public String getNextApiKey() {
        if (apiKeys == null || apiKeys.isEmpty()) {
            return null;
        }
        int index = Math.abs(counter.getAndIncrement() % apiKeys.size());
        return apiKeys.get(index);
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
