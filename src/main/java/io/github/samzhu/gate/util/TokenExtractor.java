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
            // 從 message_start 提取 input_tokens 和 model
            inputTokens.set(event.getInputTokens());
            cacheCreationTokens.set(event.getCacheCreationTokens());
            cacheReadTokens.set(event.getCacheReadTokens());
            String eventModel = event.getModel();
            if (eventModel != null) {
                model.set(eventModel);
            }
        } else if (event.isMessageDelta()) {
            // 從 message_delta 提取最終 output_tokens
            int tokens = event.getOutputTokens();
            if (tokens > 0) {
                outputTokens.set(tokens);
            }
        }
    }

    /**
     * 建立用量事件資料
     *
     * @param status 請求狀態 (success/error)
     * @return 用量事件資料
     */
    public UsageEventData buildUsageEventData(String status) {
        return UsageEventData.builder()
            .model(model.get())
            .inputTokens(inputTokens.get())
            .outputTokens(outputTokens.get())
            .cacheCreationTokens(cacheCreationTokens.get())
            .cacheReadTokens(cacheReadTokens.get())
            .latencyMs(System.currentTimeMillis() - startTime)
            .stream(true)
            .status(status)
            .build();
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

    public long getLatencyMs() {
        return System.currentTimeMillis() - startTime;
    }
}
