package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Claude API SSE 串流事件資料結構
 *
 * <p>解析 Claude Streaming API 回應中的各種事件類型，主要事件包含：
 * <ul>
 *   <li>{@code message_start} - 訊息開始，包含 input_tokens、model、message id</li>
 *   <li>{@code content_block_start} - 內容區塊開始</li>
 *   <li>{@code content_block_delta} - 內容區塊增量（串流文字）</li>
 *   <li>{@code content_block_stop} - 內容區塊結束</li>
 *   <li>{@code message_delta} - 訊息增量，包含 output_tokens、stop_reason</li>
 *   <li>{@code message_stop} - 訊息結束</li>
 *   <li>{@code error} - 錯誤事件</li>
 * </ul>
 *
 * <p>Token 用量提取位置：
 * <ul>
 *   <li>input_tokens - 從 {@code message_start.message.usage.input_tokens}</li>
 *   <li>output_tokens - 從 {@code message_delta.usage.output_tokens}</li>
 *   <li>cache_creation_input_tokens - 從 {@code message_start.message.usage}</li>
 *   <li>cache_read_input_tokens - 從 {@code message_start.message.usage}</li>
 * </ul>
 *
 * @see io.github.samzhu.gate.util.SseParser
 * @see io.github.samzhu.gate.util.TokenExtractor
 * @see <a href="https://platform.claude.com/docs/en/build-with-claude/streaming">Claude Streaming</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StreamEvent(
    String type,
    Message message,
    Delta delta,
    Usage usage,
    Integer index,
    @JsonProperty("content_block")
    ContentBlock contentBlock
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
        String id,
        String type,
        String role,
        String model,
        @JsonProperty("stop_reason")
        String stopReason,
        Usage usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
        String type,
        String text,
        @JsonProperty("stop_reason")
        String stopReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
        @JsonProperty("input_tokens")
        Integer inputTokens,
        @JsonProperty("output_tokens")
        Integer outputTokens,
        @JsonProperty("cache_creation_input_tokens")
        Integer cacheCreationInputTokens,
        @JsonProperty("cache_read_input_tokens")
        Integer cacheReadInputTokens
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(
        String type,
        String text
    ) {}

    /**
     * 是否為 message_start 事件
     */
    public boolean isMessageStart() {
        return "message_start".equals(type);
    }

    /**
     * 是否為 message_delta 事件
     */
    public boolean isMessageDelta() {
        return "message_delta".equals(type);
    }

    /**
     * 是否為 message_stop 事件
     */
    public boolean isMessageStop() {
        return "message_stop".equals(type);
    }

    /**
     * 從 message_start 事件取得 input_tokens
     */
    public int getInputTokens() {
        if (isMessageStart() && message != null && message.usage() != null) {
            return message.usage().inputTokens() != null ? message.usage().inputTokens() : 0;
        }
        return 0;
    }

    /**
     * 從 message_delta 事件取得 output_tokens
     */
    public int getOutputTokens() {
        if (isMessageDelta() && usage != null) {
            return usage.outputTokens() != null ? usage.outputTokens() : 0;
        }
        return 0;
    }

    /**
     * 從 message_start 事件取得 model
     */
    public String getModel() {
        if (isMessageStart() && message != null) {
            return message.model();
        }
        return null;
    }

    /**
     * 從 message_start 事件取得 cache_creation_input_tokens
     */
    public int getCacheCreationTokens() {
        if (isMessageStart() && message != null && message.usage() != null) {
            return message.usage().cacheCreationInputTokens() != null
                ? message.usage().cacheCreationInputTokens() : 0;
        }
        return 0;
    }

    /**
     * 從 message_start 事件取得 cache_read_input_tokens
     */
    public int getCacheReadTokens() {
        if (isMessageStart() && message != null && message.usage() != null) {
            return message.usage().cacheReadInputTokens() != null
                ? message.usage().cacheReadInputTokens() : 0;
        }
        return 0;
    }

    /**
     * 從 message_start 事件取得 message id
     */
    public String getMessageId() {
        if (isMessageStart() && message != null) {
            return message.id();
        }
        return null;
    }

    /**
     * 從 message_delta 事件取得 stop_reason
     */
    public String getStopReason() {
        if (isMessageDelta() && delta != null) {
            return delta.stopReason();
        }
        return null;
    }

    /**
     * 是否為 error 事件
     */
    public boolean isError() {
        return "error".equals(type);
    }
}
