package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE 串流事件 DTO
 * 用於解析 Claude API 串流回應中的事件
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
