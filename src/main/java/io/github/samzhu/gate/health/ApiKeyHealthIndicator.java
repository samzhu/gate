package io.github.samzhu.gate.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.samzhu.gate.service.ApiKeyRotationService;

/**
 * Anthropic API Key 健康指標
 *
 * <p>Spring Boot Actuator 健康檢查元件，檢查閘道是否有可用的 API Key。
 *
 * <p>健康狀態：
 * <ul>
 *   <li>UP - 至少有一個 API Key 已配置，包含 key 數量</li>
 *   <li>DOWN - 沒有配置任何 API Key</li>
 * </ul>
 *
 * <p>存取方式：{@code GET /actuator/health}
 *
 * <p>回應範例（健康）：
 * <pre>{@code
 * {
 *   "components": {
 *     "apiKey": {
 *       "status": "UP",
 *       "details": { "count": 2 }
 *     }
 *   }
 * }
 * }</pre>
 *
 * @see ApiKeyRotationService
 */
@Component
public class ApiKeyHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHealthIndicator.class);

    private final ApiKeyRotationService apiKeyRotationService;

    public ApiKeyHealthIndicator(ApiKeyRotationService apiKeyRotationService) {
        this.apiKeyRotationService = apiKeyRotationService;
    }

    @Override
    public Health health() {
        int keyCount = apiKeyRotationService.getKeyCount();

        if (keyCount == 0) {
            log.warn("API Key health check failed: no keys configured");
            return Health.down()
                .withDetail("count", 0)
                .withDetail("message", "No API keys configured")
                .build();
        }

        log.debug("API Key health check passed: {} key(s) available", keyCount);
        return Health.up()
            .withDetail("count", keyCount)
            .build();
    }
}
