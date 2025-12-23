package io.github.samzhu.gate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import io.github.samzhu.gate.handler.OtlpHandler;

/**
 * Anthropic 1P 遙測 Stub 路由配置
 *
 * <p>定義端點路由：
 * <ul>
 *   <li>{@code POST /api/event_logging/batch} - Anthropic 1P 遙測 stub（返回 200 OK）</li>
 * </ul>
 *
 * <p>Claude Code 會自動向 ANTHROPIC_BASE_URL 發送內部遙測資料，
 * 此 stub 端點避免產生 401 錯誤。
 *
 * @see OtlpHandler
 * @see SecurityConfig
 */
@Configuration
public class OtlpReceiverConfig {

    private static final Logger log = LoggerFactory.getLogger(OtlpReceiverConfig.class);

    private final OtlpHandler otlpHandler;

    public OtlpReceiverConfig(OtlpHandler otlpHandler) {
        this.otlpHandler = otlpHandler;
    }

    /**
     * 遙測 stub 路由配置
     *
     * @return RouterFunction
     */
    @Bean
    public RouterFunction<ServerResponse> telemetryStubRoutes() {
        log.info("Configuring telemetry stub route: /api/event_logging/batch");
        return RouterFunctions.route()
            .POST("/api/event_logging/batch", otlpHandler::handleEventLoggingBatch)
            .build();
    }
}
