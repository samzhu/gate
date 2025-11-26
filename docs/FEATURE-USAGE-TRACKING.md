# Token Usage Tracking 功能計劃書

## 文件資訊
- **版本**: 1.1.0
- **建立日期**: 2025-11-26
- **最後更新**: 2025-11-26
- **功能**: Token 用量追蹤與事件發佈

---

## 1. 功能概述

### 1.1 目標
從 LLM Gateway 代理的每個請求中擷取 Token 用量資訊，透過 CloudEvents 格式發佈到訊息佇列，供下游服務進行用量分析、成本計算和計費處理。

### 1.2 核心需求
- 從 OAuth2 JWT Token 的 `sub` claim 識別用戶
- 擷取串流 (SSE) 和非串流回應中的 Token 用量
- 使用 CloudEvents v1.0 規範格式化事件
- 透過 Spring Cloud Stream 發佈到 RabbitMQ (本地) / GCP Pub/Sub (生產)

### 1.3 現有實作狀態

| 元件 | 狀態 | 說明 |
|------|------|------|
| `UsageEventData.java` | ✅ 已實作 | 用量事件資料結構 |
| `UsageEventPublisher.java` | ✅ 已實作 | CloudEvents 發佈服務 |
| `StreamingProxyHandler.java` | ✅ 已實作 | 串流用量追蹤 |
| `TokenExtractor.java` | ✅ 已實作 | SSE Token 提取 |
| `SseParser.java` | ✅ 已實作 | SSE 事件解析 |
| 非串流用量追蹤 | ❌ 待實作 | 需新增 |
| Key Alias 配置 | ❌ 待實作 | 需重構配置結構 |

---

## 2. 資料結構設計

### 2.1 CloudEvents 格式

根據 [CloudEvents Specification v1.0.2](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md)，事件結構如下：

```json
{
  "specversion": "1.0",
  "id": "req_abc123def456",
  "type": "io.github.samzhu.gate.usage.v1",
  "source": "/gate/messages",
  "subject": "user-uuid-12345",
  "time": "2025-11-26T10:30:00.000Z",
  "datacontenttype": "application/json",
  "data": {
    "model": "claude-sonnet-4-5-20250929",
    "message_id": "msg_016pGU1jGmczbq7p4JTfAqmn",
    "input_tokens": 30,
    "output_tokens": 148,
    "cache_creation_tokens": 0,
    "cache_read_tokens": 0,
    "total_tokens": 178,
    "latency_ms": 7257,
    "stream": true,
    "stop_reason": "end_turn",
    "status": "success",
    "key_alias": "primary",
    "trace_id": "4c71578c899ae6249e5b70d07900fc93"
  }
}
```

### 2.2 CloudEvents 屬性說明

#### 必要屬性 (Required)

| 屬性 | 類型 | 說明 | 範例 |
|------|------|------|------|
| `specversion` | String | CloudEvents 規範版本 | `"1.0"` |
| `id` | String | 事件唯一識別碼 (request ID) | `"req_abc123"` |
| `type` | String | 事件類型 (reverse-DNS 格式) | `"io.github.samzhu.gate.usage.v1"` |
| `source` | URI-reference | 事件來源 | `"/gate/messages"` |

#### 選用屬性 (Optional)

| 屬性 | 類型 | 說明 | 範例 |
|------|------|------|------|
| `subject` | String | 事件主體 (JWT sub claim) | `"user-uuid-12345"` |
| `time` | Timestamp | 事件產生時間 (RFC 3339) | `"2025-11-26T10:30:00.000Z"` |
| `datacontenttype` | String | Data 內容類型 | `"application/json"` |

### 2.3 Data Payload 欄位設計

參考以下來源設計 data payload：

1. **[Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create)** - 官方 API 回應結構
2. **[Anthropic Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)** - SSE 事件格式
3. **市場 LLM Gateway 方案** ([LiteLLM](https://docs.litellm.ai/docs/proxy/virtual_keys), [Langfuse](https://langfuse.com/docs/observability/features/token-and-cost-tracking), Helicone)

#### 現有欄位 (UsageEventData.java)

| 欄位 | 類型 | 說明 | 來源 |
|------|------|------|------|
| `model` | String | 模型名稱 | SSE `message_start.message.model` |
| `input_tokens` | int | 輸入 Token 數 | SSE `message_start.message.usage.input_tokens` |
| `output_tokens` | int | 輸出 Token 數 | SSE `message_delta.usage.output_tokens` |
| `cache_creation_tokens` | int | 快取建立 Token | SSE `message_start.message.usage.cache_creation_input_tokens` |
| `cache_read_tokens` | int | 快取讀取 Token | SSE `message_start.message.usage.cache_read_input_tokens` |
| `total_tokens` | int | 總 Token 數 | 計算值 |
| `latency_ms` | long | 回應延遲 (毫秒) | Gateway 計算 |
| `stream` | boolean | 是否為串流請求 | Request body |
| `status` | String | 請求狀態 | `"success"` / `"error"` |

#### 新增欄位

| 欄位 | 類型 | 說明 | 來源 | 可行性 |
|------|------|------|------|--------|
| `message_id` | String | Anthropic 回應 ID | SSE `message_start.message.id` | ✅ 確定 |
| `stop_reason` | String | 結束原因 | SSE `message_delta.delta.stop_reason` | ✅ 確定 |
| `key_alias` | String | API Key 別名 | Gateway 配置 | ✅ 確定 |
| `error_type` | String | 錯誤類型 | SSE `error.error.type` | ✅ 確定 |
| `trace_id` | String | OpenTelemetry Trace ID | Micrometer Tracer | ✅ 確定 |

#### stop_reason 可能值

根據 [Anthropic API 文件](https://platform.claude.com/docs/en/api/messages/create)：

| 值 | 說明 |
|----|------|
| `end_turn` | 正常結束 |
| `max_tokens` | 達到 token 上限 |
| `stop_sequence` | 觸發停止序列 |
| `tool_use` | 需要呼叫工具 |
| `pause_turn` | 暫停 (extended thinking) |
| `refusal` | 拒絕回應 |

### 2.4 增強版 Data Payload 結構

```java
public record UsageEventData(
    // === 核心用量 ===
    String model,
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("cache_creation_tokens") int cacheCreationTokens,
    @JsonProperty("cache_read_tokens") int cacheReadTokens,
    @JsonProperty("total_tokens") int totalTokens,

    // === 請求資訊 ===
    @JsonProperty("message_id") String messageId,        // Anthropic msg_xxx
    @JsonProperty("latency_ms") long latencyMs,
    boolean stream,
    @JsonProperty("stop_reason") String stopReason,      // end_turn, max_tokens, etc.

    // === 狀態追蹤 ===
    String status,                                        // success, error
    @JsonProperty("error_type") String errorType,        // 錯誤類型 (可為 null)

    // === 運維資訊 ===
    @JsonProperty("key_alias") String keyAlias,          // API Key 別名 (primary, secondary)
    @JsonProperty("trace_id") String traceId             // OTel Trace ID
) {}
```

---

## 3. API Key Alias 配置設計

### 3.1 設計理念

參考 [LiteLLM Virtual Keys](https://docs.litellm.ai/docs/proxy/virtual_keys) 的 `key_name` / `key_alias` 設計：
- 使用人類可讀的別名追蹤 API Key 使用
- 不暴露實際 API Key 值
- 即使 Key 更換，alias 可保持不變

### 3.2 配置結構

**application.yaml**:
```yaml
anthropic:
  api:
    base-url: https://api.anthropic.com
    keys:
      - alias: "primary"
        value: ${ANTHROPIC_KEY_PRIMARY:}
      - alias: "secondary"
        value: ${ANTHROPIC_KEY_SECONDARY:}
      - alias: "backup"
        value: ${ANTHROPIC_KEY_BACKUP:}
```

**application-secrets.yaml** (本地開發):
```yaml
anthropic:
  api:
    keys:
      - alias: "primary"
        value: sk-ant-api03-xxx...
      - alias: "secondary"
        value: sk-ant-api03-yyy...
```

### 3.3 配置類別

**ApiKeyConfig.java** (新增):
```java
package io.github.samzhu.gate.config;

/**
 * API Key 配置
 */
public record ApiKeyConfig(
    String alias,
    String value
) {
    public ApiKeyConfig {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("API Key alias cannot be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("API Key value cannot be blank");
        }
    }
}
```

**AnthropicProperties.java** (修改):
```java
package io.github.samzhu.gate.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anthropic.api")
public record AnthropicProperties(
    String baseUrl,
    List<ApiKeyConfig> keys    // 從 List<String> 改為 List<ApiKeyConfig>
) {
    public AnthropicProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.anthropic.com";
        }
        if (keys == null) {
            keys = List.of();
        }
    }
}
```

### 3.4 ApiKeyRotationService 變更

**ApiKeySelection.java** (新增):
```java
package io.github.samzhu.gate.service;

/**
 * API Key 選擇結果
 */
public record ApiKeySelection(
    String key,
    String alias
) {}
```

**ApiKeyRotationService.java** (修改):
```java
@Service
public class ApiKeyRotationService {

    private final List<ApiKeyConfig> apiKeys;
    private final AtomicInteger counter = new AtomicInteger(0);

    public ApiKeyRotationService(AnthropicProperties properties) {
        this.apiKeys = properties.keys();
        // ...
    }

    /**
     * Round Robin 策略取得下一個 API Key 及其 alias
     */
    public ApiKeySelection getNextApiKey() {
        if (apiKeys == null || apiKeys.isEmpty()) {
            return null;
        }
        int index = Math.abs(counter.getAndIncrement() % apiKeys.size());
        ApiKeyConfig config = apiKeys.get(index);
        return new ApiKeySelection(config.value(), config.alias());
    }

    // ...
}
```

---

## 4. 與 Anthropic 資料匯出格式對照

| Anthropic 欄位 | Gate Event 欄位 | 對應說明 |
|----------------|-----------------|----------|
| `usage_date_utc` | CloudEvents `time` | 事件時間戳 |
| `model_version` | `data.model` | 模型名稱 |
| `api_key` | `data.key_alias` | 使用 alias 替代實際 key (安全) |
| `workspace` | CloudEvents `subject` | 用戶識別 (JWT sub) |
| `usage_type` | `data.stream` | 串流/非串流 |
| `context_window` | - | 不追蹤 (模型固定) |
| `usage_input_tokens_no_cache` | `data.input_tokens - cache_read_tokens` | 可計算 |
| `usage_input_tokens_cache_write_5m` | `data.cache_creation_tokens` | 快取建立 |
| `usage_input_tokens_cache_write_1h` | - | 目前 Anthropic 無區分 |
| `usage_input_tokens_cache_read` | `data.cache_read_tokens` | 快取讀取 |
| `usage_output_tokens` | `data.output_tokens` | 輸出 Token |
| `web_search_count` | - | 目前不支援 web search |

---

## 5. 實作計劃

### 5.1 Phase 1: API Key Alias 配置重構 (P0)

**目標**: 支援 Key Alias 配置結構

**新增檔案**:
- `src/main/java/io/github/samzhu/gate/config/ApiKeyConfig.java`
- `src/main/java/io/github/samzhu/gate/service/ApiKeySelection.java`

**修改檔案**:
- `src/main/java/io/github/samzhu/gate/config/AnthropicProperties.java`
- `src/main/java/io/github/samzhu/gate/service/ApiKeyRotationService.java`
- `src/main/java/io/github/samzhu/gate/filter/ApiKeyInjectionFilter.java`
- `src/main/resources/application.yaml`
- `config/application-secrets.yaml.example`

### 5.2 Phase 2: 增強 UsageEventData (P1)

**目標**: 新增追蹤欄位

**檔案變更**:
- `src/main/java/io/github/samzhu/gate/model/UsageEventData.java`

**新增欄位**:
```java
@JsonProperty("message_id") String messageId,
@JsonProperty("stop_reason") String stopReason,
@JsonProperty("key_alias") String keyAlias,
@JsonProperty("trace_id") String traceId,
@JsonProperty("error_type") String errorType
```

### 5.3 Phase 3: 擴充 StreamEvent 和 TokenExtractor (P1)

**目標**: 從 SSE 事件提取額外資訊

**修改檔案**:
- `src/main/java/io/github/samzhu/gate/model/StreamEvent.java` - 新增 getter
- `src/main/java/io/github/samzhu/gate/util/TokenExtractor.java` - 新增提取邏輯

**新增提取**:
- `message_start.message.id` → `messageId`
- `message_delta.delta.stop_reason` → `stopReason`

### 5.4 Phase 4: 傳遞 Key Alias 和 Trace ID (P1)

**目標**: 在 Filter 和 Handler 之間傳遞運維資訊

**檔案變更**:
- `src/main/java/io/github/samzhu/gate/filter/ApiKeyInjectionFilter.java`
- `src/main/java/io/github/samzhu/gate/handler/StreamingProxyHandler.java`

**實作方式**:
```java
// ApiKeyInjectionFilter 中
ApiKeySelection selection = apiKeyRotationService.getNextApiKey();
request.attributes().put("keyAlias", selection.alias());

// StreamingProxyHandler 中
String keyAlias = (String) request.attributes().get("keyAlias");
String traceId = tracer.currentSpan().context().traceId();
```

### 5.5 Phase 5: 實作非串流用量追蹤 (P2)

**目標**: 處理 `stream: false` 請求的用量追蹤

**新增檔案**:
- `src/main/java/io/github/samzhu/gate/handler/NonStreamingProxyHandler.java`

**實作要點**:
1. 解析完整 JSON 回應
2. 從 `usage` 物件提取 Token 資訊
3. 發佈用量事件

### 5.6 Phase 6: 路由分流配置 (P2)

**目標**: 區分串流/非串流請求到不同 Handler

**檔案變更**:
- `src/main/java/io/github/samzhu/gate/config/GatewayConfig.java`

---

## 6. SSE 事件處理流程

### 6.1 串流模式 Token 提取時機

```
┌─────────────────────┐
│   message_start     │ ──→ 提取: input_tokens, cache_*, model, message_id
└─────────────────────┘
          │
          ▼
┌─────────────────────┐
│ content_block_start │ ──→ 透傳 (不提取)
└─────────────────────┘
          │
          ▼
┌─────────────────────┐
│ content_block_delta │ ──→ 透傳 (不提取)
│      (多次)         │
└─────────────────────┘
          │
          ▼
┌─────────────────────┐
│  content_block_stop │ ──→ 透傳 (不提取)
└─────────────────────┘
          │
          ▼
┌─────────────────────┐
│   message_delta     │ ──→ 提取: output_tokens (累計), stop_reason
└─────────────────────┘
          │
          ▼
┌─────────────────────┐
│    message_stop     │ ──→ 觸發: 發佈用量事件
└─────────────────────┘
```

### 6.2 串流事件範例與提取欄位

**message_start** (提取 input_tokens, model, message_id):
```json
{
  "type": "message_start",
  "message": {
    "id": "msg_016pGU1jGmczbq7p4JTfAqmn",
    "model": "claude-sonnet-4-5-20250929",
    "usage": {
      "input_tokens": 30,
      "cache_creation_input_tokens": 0,
      "cache_read_input_tokens": 0,
      "output_tokens": 1,
      "service_tier": "standard"
    }
  }
}
```

**message_delta** (提取 output_tokens, stop_reason):
```json
{
  "type": "message_delta",
  "delta": {
    "stop_reason": "end_turn",
    "stop_sequence": null
  },
  "usage": {
    "output_tokens": 148
  }
}
```

**error** (提取 error_type):
```json
{
  "type": "error",
  "error": {
    "type": "overloaded_error",
    "message": "Overloaded"
  }
}
```

---

## 7. 下游服務分析支援

### 7.1 建議的分析維度

基於發佈的用量事件，下游服務可進行以下分析：

| 分析類型 | 使用欄位 | 說明 |
|----------|----------|------|
| **用戶用量** | `subject`, `input/output_tokens` | 依用戶彙總 Token 消耗 |
| **模型用量** | `model`, `input/output_tokens` | 依模型分析使用偏好 |
| **成本計算** | `model`, `input/output_tokens` | 結合定價計算成本 |
| **效能分析** | `latency_ms`, `stream` | 回應時間分析 |
| **快取效益** | `cache_read_tokens`, `input_tokens` | 快取命中率 |
| **錯誤分析** | `status`, `error_type` | 錯誤率和類型分布 |
| **API Key 負載** | `key_alias` | Key 使用分布 |
| **完成率** | `stop_reason` | 正常完成 vs 截斷 |
| **分散式追蹤** | `trace_id` | 關聯 OTel traces |

### 7.2 成本計算公式

下游服務可使用以下公式計算成本：

```
cost = (input_tokens - cache_read_tokens) * input_price_per_token
     + cache_read_tokens * cache_read_price_per_token
     + cache_creation_tokens * cache_write_price_per_token
     + output_tokens * output_price_per_token
```

**Claude 模型定價參考** (2025-11):

| 模型 | Input (per 1M) | Output (per 1M) | Cache Read | Cache Write |
|------|----------------|-----------------|------------|-------------|
| Claude Opus 4 | $15.00 | $75.00 | $1.50 | $18.75 |
| Claude Sonnet 4 | $3.00 | $15.00 | $0.30 | $3.75 |
| Claude Haiku 3.5 | $0.80 | $4.00 | $0.08 | $1.00 |

---

## 8. 測試計劃

### 8.1 單元測試

| 測試類別 | 測試重點 |
|----------|----------|
| `ApiKeyConfigTest` | 配置驗證、alias 必填檢查 |
| `ApiKeyRotationServiceTest` | Round Robin 正確性、alias 返回 |
| `UsageEventDataTest` | Builder 正確性、JSON 序列化 |
| `TokenExtractorTest` | SSE 事件解析、Token 累計、message_id/stop_reason 提取 |
| `UsageEventPublisherTest` | CloudEvents 格式、發佈邏輯 |

### 8.2 整合測試

| 測試情境 | 驗證項目 |
|----------|----------|
| 串流請求完整流程 | Token 提取正確、key_alias 正確、事件發佈成功 |
| 非串流請求流程 | JSON 解析正確、事件發佈成功 |
| 錯誤請求處理 | 錯誤狀態記錄、error_type 正確 |
| 快取命中場景 | cache_read_tokens 正確提取 |
| Key Alias 輪換 | Round Robin 正確、alias 對應正確 |

---

## 9. 配置需求

### 9.1 Anthropic API Key 配置

**application.yaml** (預設結構):
```yaml
anthropic:
  api:
    base-url: https://api.anthropic.com
    keys:
      - alias: "primary"
        value: ${ANTHROPIC_KEY_PRIMARY:}
      - alias: "secondary"
        value: ${ANTHROPIC_KEY_SECONDARY:}
```

**config/application-secrets.yaml.example**:
```yaml
# 複製此檔案為 application-secrets.yaml 並填入實際值
# 此檔案已加入 .gitignore，不會提交到版本控制
anthropic:
  api:
    keys:
      - alias: "primary"
        value: sk-ant-api03-your-key-here
      - alias: "secondary"
        value: sk-ant-api03-another-key-here
```

### 9.2 Spring Cloud Stream 配置

**application.yaml**:
```yaml
spring:
  cloud:
    stream:
      bindings:
        usageEvent-out-0:
          destination: llm-gateway-usage
          content-type: application/cloudevents+json
```

### 9.3 本地開發 (RabbitMQ)

**application-local.yaml**:
```yaml
spring:
  cloud:
    stream:
      binders:
        rabbit:
          type: rabbit
      bindings:
        usageEvent-out-0:
          binder: rabbit
  rabbitmq:
    host: localhost
    port: 5672
```

### 9.4 生產環境 (GCP Pub/Sub)

**application-gcp.yaml**:
```yaml
spring:
  cloud:
    stream:
      binders:
        pubsub:
          type: pubsub
      bindings:
        usageEvent-out-0:
          binder: pubsub
```

---

## 10. 風險與考量

### 10.1 效能考量

| 風險 | 緩解措施 |
|------|----------|
| SSE 解析增加延遲 | 最小化解析邏輯、避免額外記憶體分配 |
| 事件發佈阻塞 | 非同步發佈、發佈失敗不影響主流程 |
| 記憶體使用 | 串流處理，不緩存完整回應 |

### 10.2 可靠性考量

| 風險 | 緩解措施 |
|------|----------|
| 訊息遺失 | Pub/Sub at-least-once 語意、記錄 log |
| 解析失敗 | 容錯設計、記錄原始事件供 debug |
| 下游服務故障 | 解耦設計、訊息佇列緩衝 |

### 10.3 配置遷移

現有配置格式 (`List<String>`) 需遷移至新格式 (`List<ApiKeyConfig>`)：

**舊格式** (不再支援):
```yaml
anthropic:
  api:
    keys:
      - sk-ant-xxx
      - sk-ant-yyy
```

**新格式** (必須):
```yaml
anthropic:
  api:
    keys:
      - alias: "primary"
        value: sk-ant-xxx
      - alias: "secondary"
        value: sk-ant-yyy
```

---

## 11. 參考資料

- [CloudEvents Specification v1.0.2](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/spec.md)
- [CloudEvents Java SDK - Spring Integration](https://cloudevents.github.io/sdk-java/spring.html)
- [Spring Cloud Stream Reference](https://docs.spring.io/spring-cloud-stream/reference/spring-cloud-stream.html)
- [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [Anthropic Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [LiteLLM Virtual Keys](https://docs.litellm.ai/docs/proxy/virtual_keys)
- [LiteLLM Spend Tracking](https://docs.litellm.ai/docs/proxy/cost_tracking)
- [Langfuse Token & Cost Tracking](https://langfuse.com/docs/observability/features/token-and-cost-tracking)

---

## 文件修訂歷史

| 版本 | 日期 | 作者 | 變更 |
|------|------|------|------|
| 1.0.0 | 2025-11-26 | AI 助理 | 初始版本建立 |
| 1.1.0 | 2025-11-26 | AI 助理 | 新增 Key Alias 設計；根據 Anthropic API 文件更新欄位可行性分析；將 `api_key_index` 改為 `key_alias`；新增配置類別設計 |
