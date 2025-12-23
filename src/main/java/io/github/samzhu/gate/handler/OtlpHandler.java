package io.github.samzhu.gate.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Anthropic 1P 遙測 Stub 處理器
 *
 * <p>處理 Claude Code 自動發送的內部遙測資料：
 * <ul>
 *   <li>{@code POST /api/event_logging/batch} - Anthropic 1P 遙測 stub</li>
 * </ul>
 *
 * <p>Claude Code 會自動向 ANTHROPIC_BASE_URL 發送 1P（first-party）遙測資料。
 * 此 stub 端點返回 200 OK 以避免產生 401 錯誤日誌。
 *
 * @see io.github.samzhu.gate.config.OtlpReceiverConfig
 */
@Component
public class OtlpHandler {

    private static final Logger log = LoggerFactory.getLogger(OtlpHandler.class);

    public OtlpHandler() {
        log.info("OtlpHandler initialized");
    }

    /**
     * 處理 POST /api/event_logging/batch - Anthropic 1P 遙測 stub
     *
     * <p>Claude Code 會向 ANTHROPIC_BASE_URL 發送內部遙測資料。
     * 由於 Gate 代理不處理這些資料，返回 200 OK 以避免錯誤日誌。
     *
     * @param request HTTP 請求
     * @return HTTP 回應（200 OK）
     */
    public ServerResponse handleEventLoggingBatch(ServerRequest request) {
        log.debug("Received Anthropic 1P telemetry batch (stub, discarding)");
        return ServerResponse.ok().build();
    }
}
