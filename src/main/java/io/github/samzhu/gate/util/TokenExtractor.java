package io.github.samzhu.gate.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.samzhu.gate.model.StreamEvent;
import io.github.samzhu.gate.model.UsageEventData;

/**
 * SSE 串流 Token 用量提取工具
 *
 * <p>從 Claude API 串流事件中累計提取 Token 用量資訊，用於建立用量事件。
 *
 * <p>提取邏輯：
 * <ul>
 *   <li>{@code message_start} 事件 - 提取 input_tokens、cache tokens、model、message_id</li>
 *   <li>{@code message_delta} 事件 - 提取最終 output_tokens、stop_reason</li>
 * </ul>
 *
 * <p>執行緒安全：使用 {@link java.util.concurrent.atomic.AtomicInteger} 和
 * {@link java.util.concurrent.atomic.AtomicReference} 確保並發存取安全，
 * 但通常每個串流請求會獨立使用一個實例。
 *
 * <p>使用方式：
 * <pre>{@code
 * TokenExtractor extractor = new TokenExtractor();
 * // 處理每個 SSE 事件
 * streamEvents.forEach(extractor::processEvent);
 * // 串流結束後建立用量資料
 * UsageEventData data = extractor.buildUsageEventData("success", keyAlias, traceId);
 * }</pre>
 *
 * @see io.github.samzhu.gate.model.StreamEvent
 * @see io.github.samzhu.gate.model.UsageEventData
 * @see io.github.samzhu.gate.handler.StreamingProxyHandler
 */
public class TokenExtractor {

    private final AtomicInteger inputTokens = new AtomicInteger(0);
    private final AtomicInteger outputTokens = new AtomicInteger(0);
    private final AtomicInteger cacheCreationTokens = new AtomicInteger(0);
    private final AtomicInteger cacheReadTokens = new AtomicInteger(0);
    private final AtomicReference<String> model = new AtomicReference<>();
    private final AtomicReference<String> messageId = new AtomicReference<>();
    private final AtomicReference<String> stopReason = new AtomicReference<>();
    private final long startTime;

    public TokenExtractor() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 處理串流事件，提取 Token 用量
     *
     * @param event 串流事件
     */
    public void processEvent(StreamEvent event) {
        if (event == null) {
            return;
        }

        if (event.isMessageStart()) {
            // 從 message_start 提取 input_tokens, model, message_id
            inputTokens.set(event.getInputTokens());
            cacheCreationTokens.set(event.getCacheCreationTokens());
            cacheReadTokens.set(event.getCacheReadTokens());
            String eventModel = event.getModel();
            if (eventModel != null) {
                model.set(eventModel);
            }
            String eventMessageId = event.getMessageId();
            if (eventMessageId != null) {
                messageId.set(eventMessageId);
            }
        } else if (event.isMessageDelta()) {
            // 從 message_delta 提取最終 output_tokens 和 stop_reason
            int tokens = event.getOutputTokens();
            if (tokens > 0) {
                outputTokens.set(tokens);
            }
            String eventStopReason = event.getStopReason();
            if (eventStopReason != null) {
                stopReason.set(eventStopReason);
            }
        }
    }

    /**
     * 建立用量事件資料
     *
     * @param status 請求狀態 (success/error)
     * @param keyAlias API Key 別名
     * @param traceId OpenTelemetry Trace ID
     * @return 用量事件資料
     */
    public UsageEventData buildUsageEventData(String status, String keyAlias, String traceId) {
        return UsageEventData.builder()
            .model(model.get())
            .inputTokens(inputTokens.get())
            .outputTokens(outputTokens.get())
            .cacheCreationTokens(cacheCreationTokens.get())
            .cacheReadTokens(cacheReadTokens.get())
            .messageId(messageId.get())
            .latencyMs(System.currentTimeMillis() - startTime)
            .stream(true)
            .stopReason(stopReason.get())
            .status(status)
            .keyAlias(keyAlias)
            .traceId(traceId)
            .build();
    }

    /**
     * 建立用量事件資料 (簡化版，向下相容)
     *
     * @param status 請求狀態 (success/error)
     * @return 用量事件資料
     */
    public UsageEventData buildUsageEventData(String status) {
        return buildUsageEventData(status, null, null);
    }

    public int getInputTokens() {
        return inputTokens.get();
    }

    public int getOutputTokens() {
        return outputTokens.get();
    }

    public String getModel() {
        return model.get();
    }

    public String getMessageId() {
        return messageId.get();
    }

    public String getStopReason() {
        return stopReason.get();
    }

    public long getLatencyMs() {
        return System.currentTimeMillis() - startTime;
    }
}
