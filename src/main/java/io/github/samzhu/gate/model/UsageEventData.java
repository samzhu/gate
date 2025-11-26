package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用量事件資料 (CloudEvents data payload)
 */
public record UsageEventData(
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

    @JsonProperty("latency_ms")
    long latencyMs,

    boolean stream,

    String status
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
        private long latencyMs;
        private boolean stream;
        private String status = "success";

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

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public UsageEventData build() {
            int totalTokens = inputTokens + outputTokens;
            return new UsageEventData(
                model, inputTokens, outputTokens,
                cacheCreationTokens, cacheReadTokens, totalTokens,
                latencyMs, stream, status
            );
        }
    }
}
