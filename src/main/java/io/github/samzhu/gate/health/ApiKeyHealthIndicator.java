package io.github.samzhu.gate.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.samzhu.gate.service.ApiKeyRotationService;

/**
 * API Key 健康指標
 * 檢查是否有可用的 Anthropic API Key
 */
@Component
public class ApiKeyHealthIndicator implements HealthIndicator {

    private final ApiKeyRotationService apiKeyRotationService;

    public ApiKeyHealthIndicator(ApiKeyRotationService apiKeyRotationService) {
        this.apiKeyRotationService = apiKeyRotationService;
    }

    @Override
    public Health health() {
        int keyCount = apiKeyRotationService.getKeyCount();

        if (keyCount == 0) {
            return Health.down()
                .withDetail("count", 0)
                .withDetail("message", "No API keys configured")
                .build();
        }

        return Health.up()
            .withDetail("count", keyCount)
            .build();
    }
}
