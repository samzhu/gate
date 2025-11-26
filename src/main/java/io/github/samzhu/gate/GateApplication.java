package io.github.samzhu.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * LLM Gateway 應用程式入口
 *
 * <p>企業級 LLM API 閘道服務，為 Claude Code CLI 和其他 Anthropic Claude API 客戶端提供：
 * <ul>
 *   <li>OAuth2 JWT 認證（透過 JWKS 驗證）</li>
 *   <li>API Key 輪換（Round Robin 策略）</li>
 *   <li>Token 用量追蹤（CloudEvents 格式發送到 Pub/Sub）</li>
 *   <li>串流 SSE 代理支援</li>
 *   <li>OpenTelemetry 可觀測性</li>
 * </ul>
 *
 * @see <a href="https://platform.claude.com/docs/en/api/messages/create">Claude Messages API</a>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GateApplication {

	public static void main(String[] args) {
		SpringApplication.run(GateApplication.class, args);
	}

}
