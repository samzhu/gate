package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用量事件資料 (CloudEvents data payload)
 */
public record UsageEventData(
    // === 核心用量 ===
    String model,

    @JsonProperty("input_tokens")
    int inputTokens,

    @JsonProperty("output_tokens")
    int outputTokens,

    @JsonProperty("cache_creation_tokens")
    int cacheCreationTokens,

    @JsonProperty("cache_read_tokens")
    int cacheReadTokens,

    @JsonProperty("total_tokens")
    int totalTokens,

    // === 請求資訊 ===
    @JsonProperty("message_id")
    String messageId,

    @JsonProperty("latency_ms")
    long latencyMs,

    boolean stream,

    @JsonProperty("stop_reason")
    String stopReason,

    // === 狀態追蹤 ===
    String status,

    @JsonProperty("error_type")
    String errorType,

    // === 運維資訊 ===
    @JsonProperty("key_alias")
    String keyAlias,

    @JsonProperty("trace_id")
    String traceId
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private int inputTokens;
        private int outputTokens;
        private int cacheCreationTokens;
        private int cacheReadTokens;
        private String messageId;
        private long latencyMs;
        private boolean stream;
        private String stopReason;
        private String status = "success";
        private String errorType;
        private String keyAlias;
        private String traceId;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        public Builder outputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        public Builder cacheCreationTokens(int cacheCreationTokens) {
            this.cacheCreationTokens = cacheCreationTokens;
            return this;
        }

        public Builder cacheReadTokens(int cacheReadTokens) {
            this.cacheReadTokens = cacheReadTokens;
            return this;
        }

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder stopReason(String stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder keyAlias(String keyAlias) {
            this.keyAlias = keyAlias;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public UsageEventData build() {
            int totalTokens = inputTokens + outputTokens;
            return new UsageEventData(
                model, inputTokens, outputTokens,
                cacheCreationTokens, cacheReadTokens, totalTokens,
                messageId, latencyMs, stream, stopReason,
                status, errorType, keyAlias, traceId
            );
        }
    }
}
