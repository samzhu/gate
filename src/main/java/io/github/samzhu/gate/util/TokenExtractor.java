package io.github.samzhu.gate.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.samzhu.gate.model.StreamEvent;
import io.github.samzhu.gate.model.UsageEventData;

/**
 * Token 用量提取工具
 * 用於從串流事件中累計提取 Token 用量
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
