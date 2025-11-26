# 追蹤機制重構計劃

## 背景分析

### 目前架構

```
Claude Code CLI ──────────────────────────→ GCP Cloud Run (Gateway) ─────→ Anthropic API
                                                    │
                                           生成 requestId (UUID)
                                           取得 traceId (OTel)
                                                    │
                                                    ▼
                                              UsageEvent
                                           (traceId, messageId)
```

### GCP Cloud Run 追蹤行為

根據 [Cloud Run Distributed Tracing](https://cloud.google.com/run/docs/trace)：

1. **Cloud Run 自動注入 `traceparent` header**
   - 對每個 incoming request 自動產生 trace
   - 遵循 W3C Trace Context 標準

2. **Header 優先順序** ([Google Trace Context](https://cloud.google.com/trace/docs/trace-context))
   - 若 Client 發送 `traceparent`，Cloud Run 會繼承該 trace
   - 若無，Cloud Run 產生新的 trace
   - `traceparent` 優先於 `X-Cloud-Trace-Context`

3. **Spring Boot Micrometer 整合**
   - 自動讀取傳入的 trace context
   - `tracer.currentSpan().context().traceId()` 會取得正確的 trace ID

### Anthropic API 回應

根據 [Anthropic API Errors](https://platform.claude.com/docs/en/api/errors)：

| Header | 格式 | 用途 |
|--------|------|------|
| `request-id` | `req_018EeWyXxfu5pfWkrYcMdjWG` | Anthropic 產生，用於客服支援 |
| `x-cloud-trace-context` | GCP Trace | Anthropic 內部 GCP 追蹤 |

## 追蹤 ID 流向分析

### 情境 1：Client 啟用 OTel（如 Claude Code CLI）

```
Claude Code CLI                    Cloud Run                      Anthropic API
      │                                │                               │
      │  traceparent: 00-{traceA}-... │                               │
      │ ─────────────────────────────→│                               │
      │                                │  繼承 traceA                  │
      │                                │  記錄到 Cloud Trace           │
      │                                │                               │
      │                                │  POST /v1/messages            │
      │                                │ ─────────────────────────────→│
      │                                │                               │
      │                                │  request-id: req_xxx          │
      │                                │←─────────────────────────────│
      │                                │                               │
      │                         UsageEvent:                            │
      │                         - trace_id: traceA (可關聯 Client)    │
      │                         - anthropic_request_id: req_xxx        │
```

### 情境 2：Client 未啟用 OTel

```
Client                             Cloud Run                      Anthropic API
      │                                │                               │
      │  (無 traceparent)             │                               │
      │ ─────────────────────────────→│                               │
      │                                │  產生新 traceB                │
      │                                │  記錄到 Cloud Trace           │
      │                                │                               │
      │                                │  POST /v1/messages            │
      │                                │ ─────────────────────────────→│
      │                                │                               │
      │                                │  request-id: req_xxx          │
      │                                │←─────────────────────────────│
      │                                │                               │
      │                         UsageEvent:                            │
      │                         - trace_id: traceB (Cloud Run 產生)   │
      │                         - anthropic_request_id: req_xxx        │
```

## 設計決策

### 移除 `requestId`，統一使用 `traceId`

**理由**：
1. GCP Cloud Run 已自動處理 trace propagation
2. 若 Client 啟用 OTel，traceId 可端到端關聯
3. 減少冗餘 ID，簡化架構
4. 符合業界標準（W3C Trace Context）

### 新增 `anthropicRequestId` 欄位

**理由**：
1. Anthropic 的 `request-id` 是向 Anthropic 報告問題的唯一識別碼
2. 無法用我方的 traceId 向 Anthropic 查詢
3. 對排查 Anthropic 端問題至關重要

## 實作計劃

### Phase 1: 資料模型調整

**檔案**: `UsageEventData.java`

```java
// 運維資訊區塊新增
@JsonProperty("anthropic_request_id")
String anthropicRequestId,  // Anthropic 回應的 request-id header
```

### Phase 2: 捕獲 Anthropic request-id

**檔案**: `NonStreamingProxyHandler.java`

```java
// 從回應 header 提取
String anthropicRequestId = response.headers()
    .firstValue("request-id")
    .orElse(null);
```

**檔案**: `StreamingProxyHandler.java`

```java
// 串流模式也需從 HttpResponse 提取
String anthropicRequestId = response.headers()
    .firstValue("request-id")
    .orElse(null);
```

### Phase 3: 移除 requestId 相關程式碼

**檔案**: `GatewayConfig.java`

```diff
- String requestId = UUID.randomUUID().toString();
- log.debug("Routing request: subject={}, requestId={}, ...", subject, requestId, ...);
+ log.debug("Routing request: subject={}, keyAlias={}, streaming={}", subject, keyAlias, isStreaming);
```

**方法簽名調整**:

```diff
- handleStreaming(requestBody, apiKey, subject, requestId, keyAlias)
+ handleStreaming(requestBody, apiKey, subject, keyAlias)
```

### Phase 4: CloudEvent ID 使用 traceId

**檔案**: `UsageEventPublisher.java`

```diff
- String eventId = requestId != null ? requestId : UUID.randomUUID().toString();
+ String eventId = eventData.traceId() != null ? eventData.traceId() : UUID.randomUUID().toString();
```

### Phase 5: 清理 ApiKeyInjectionFilter（若有使用）

移除 `REQUEST_ID_ATTRIBUTE` 相關程式碼。

## 變更摘要

### 新增欄位

| 欄位 | JSON Key | 來源 | 用途 |
|------|----------|------|------|
| `anthropicRequestId` | `anthropic_request_id` | Anthropic response header | 向 Anthropic 報告問題 |

### 移除概念

| 移除項目 | 理由 |
|----------|------|
| Gateway 自產 `requestId` | 被 `traceId` 取代 |
| `REQUEST_ID_ATTRIBUTE` | 不再需要 |

### 保留欄位

| 欄位 | 來源 | 用途 |
|------|------|------|
| `traceId` | Micrometer/OTel (含 Cloud Run 注入) | 端到端追蹤、CloudEvent ID |
| `messageId` | Anthropic response body (`id`) | 訊息識別 (msg_xxx) |

## 最終資料結構

```json
{
  "model": "claude-sonnet-4-5-20250929",
  "input_tokens": 100,
  "output_tokens": 50,
  "cache_creation_tokens": 0,
  "cache_read_tokens": 0,
  "total_tokens": 150,
  "message_id": "msg_01XFDUDYJgAACzvnptvVoYEL",
  "anthropic_request_id": "req_018EeWyXxfu5pfWkrYcMdjWG",
  "latency_ms": 1234,
  "stream": true,
  "stop_reason": "end_turn",
  "status": "success",
  "error_type": null,
  "key_alias": "key-1",
  "trace_id": "80e1afed08e019fc1110464cfa66635c"
}
```

## 排查指南

| 問題類型 | 使用欄位 | 查詢位置 |
|----------|----------|----------|
| Gateway 內部問題 | `trace_id` | GCP Cloud Trace、應用日誌 |
| Anthropic API 問題 | `anthropic_request_id` | 聯繫 Anthropic 客服 |
| 用戶特定問題 | `trace_id` + CloudEvent `subject` | BigQuery 用量資料 |
| 端到端追蹤 | `trace_id` | Client OTel backend + Cloud Trace |

## 參考資料

- [Cloud Run Distributed Tracing](https://cloud.google.com/run/docs/trace)
- [Google Cloud Trace Context](https://cloud.google.com/trace/docs/trace-context)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Anthropic API Errors](https://platform.claude.com/docs/en/api/errors)
- [Claude Code Monitoring](https://docs.claude.com/en/docs/claude-code/monitoring-usage)
