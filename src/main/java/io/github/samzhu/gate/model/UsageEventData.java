package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用量事件資料（CloudEvents data payload）
 *
 * <p>記錄每次 Claude API 呼叫的 Token 用量和請求資訊，作為 CloudEvents 的 data 欄位發送到訊息佇列。
 * <b>Gate 只發布 Anthropic API 回傳的原始數據，不進行任何計算。計算與結算邏輯應由 Ledger 端處理。</b>
 *
 * <h3>Token 欄位定義（依據 Anthropic API 官方文檔）</h3>
 * <p>根據 <a href="https://platform.claude.com/docs/en/build-with-claude/prompt-caching#tracking-cache-performance">
 * Anthropic Prompt Caching 文檔</a>：
 * <ul>
 *   <li>{@code input_tokens} - <b>僅包含快取斷點之後的 tokens</b>（即未被快取的輸入部分，不是總輸入！）</li>
 *   <li>{@code cache_read_input_tokens} - 從快取讀取的 tokens（快取命中）</li>
 *   <li>{@code cache_creation_input_tokens} - 寫入快取的 tokens（新建快取）</li>
 *   <li>{@code output_tokens} - 輸出 tokens</li>
 * </ul>
 *
 * <h3>Ledger 端計算公式參考</h3>
 * <p><b>總輸入 tokens：</b>
 * <pre>
 * total_input_tokens = input_tokens + cache_creation_input_tokens + cache_read_input_tokens
 * </pre>
 *
 * <p><b>成本計算：</b>
 * <pre>
 * input_cost = (input_tokens × base_rate)
 *            + (cache_creation_tokens × base_rate × 1.25)
 *            + (cache_read_tokens × base_rate × 0.1)
 * </pre>
 *
 * <p>欄位說明：
 * <ul>
 *   <li><b>核心用量（原始數據）</b>
 *       <ul>
 *         <li>{@code model} - 使用的模型名稱（如 claude-sonnet-4-5-20250929）</li>
 *         <li>{@code inputTokens} - 快取斷點之後的輸入 Token（對應 Anthropic API 的 input_tokens）</li>
 *         <li>{@code outputTokens} - 輸出 Token 數量（對應 output_tokens）</li>
 *         <li>{@code cacheCreationTokens} - 快取建立的 Token（對應 cache_creation_input_tokens）</li>
 *         <li>{@code cacheReadTokens} - 從快取讀取的 Token（對應 cache_read_input_tokens）</li>
 *       </ul>
 *   </li>
 *   <li><b>請求資訊</b>
 *       <ul>
 *         <li>{@code messageId} - Anthropic 回應的 message ID（msg_xxx）</li>
 *         <li>{@code latencyMs} - 請求延遲（毫秒）</li>
 *         <li>{@code stream} - 是否為串流請求</li>
 *         <li>{@code stopReason} - 結束原因（end_turn、max_tokens 等）</li>
 *       </ul>
 *   </li>
 *   <li><b>狀態追蹤</b>
 *       <ul>
 *         <li>{@code status} - 請求狀態（success/error）</li>
 *         <li>{@code errorType} - 錯誤類型（若發生錯誤）</li>
 *       </ul>
 *   </li>
 *   <li><b>運維資訊</b>
 *       <ul>
 *         <li>{@code keyAlias} - 使用的 API Key 別名</li>
 *         <li>{@code traceId} - OpenTelemetry Trace ID（用於端到端追蹤）</li>
 *         <li>{@code anthropicRequestId} - Anthropic 回應的 request-id（req_xxx，用於向 Anthropic 客服報告問題）</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * @see io.github.samzhu.gate.service.UsageEventPublisher
 * @see <a href="https://platform.claude.com/docs/en/api/messages/create">Claude Messages API</a>
 * @see <a href="https://platform.claude.com/docs/en/build-with-claude/prompt-caching">Prompt Caching</a>
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
    String traceId,

    @JsonProperty("anthropic_request_id")
    String anthropicRequestId
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
        private String anthropicRequestId;

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

        public Builder anthropicRequestId(String anthropicRequestId) {
            this.anthropicRequestId = anthropicRequestId;
            return this;
        }

        public UsageEventData build() {
            return new UsageEventData(
                model, inputTokens, outputTokens,
                cacheCreationTokens, cacheReadTokens,
                messageId, latencyMs, stream, stopReason,
                status, errorType, keyAlias, traceId, anthropicRequestId
            );
        }
    }
}
