# LLM Gateway: 產品需求文件

## 文件資訊
- **版本**: 1.2.0
- **最後更新**: 2025-11-25
- **專案**: llm-gateway
- **技術堆疊**: Spring Boot 4.0.0, Spring Cloud Gateway Server Web MVC, Spring Cloud GCP Pub/Sub, Java 25, GraalVM Native
- **目標平台**: 企業級 LLM API 閘道服務
- **發布方式**: Docker 容器 / GraalVM 原生可執行檔
- **相關專案**: Gate-CLI（獨立專案，負責 OAuth2 Token 取得與 Claude Code 配置）

---

## 1. 執行摘要

**LLM Gateway** 是一個企業級的 LLM API 閘道服務，專門為 Claude Code CLI 和其他 Anthropic Claude API 客戶端提供代理、認證、用量追蹤和可觀測性功能。基於 Spring Cloud Gateway Server Web MVC 構建，它作為組織與 Anthropic Claude API 之間的中間層，提供統一的存取控制、Token 用量記錄和成本管理能力。

### 核心價值主張
- **統一存取控制**: OAuth2 認證保護（自建認證伺服器 + JWKS），集中管理 API 存取權限
- **Token 用量追蹤**: 擷取每次 API 呼叫的 input/output tokens，透過 GCP Pub/Sub 發送用量事件
- **API Key 輪換**: 支援多組 Anthropic API Key，採用 Round Robin 策略分散負載
- **可觀測性**: 整合 OpenTelemetry 實現分散式追蹤和指標收集
- **韌性設計**: Resilience4j Circuit Breaker 防止級聯故障
- **串流支援**: 完整支援 Claude API 的 SSE 串流回應（基於 Web MVC）

---

## 2. 背景與動機

### 問題陳述
企業在使用 Claude Code CLI 和 Claude API 時面臨幾個挑戰：

1. **成本不透明**: 難以追蹤和分配 LLM API 使用成本到各團隊/專案
2. **存取控制分散**: 每個開發者直接持有 API Key，安全風險高
3. **缺乏審計能力**: 無法追蹤誰在何時使用了多少 Token
4. **無法設定配額**: 難以對團隊或個人設定用量限制
5. **可觀測性不足**: 缺乏對 API 呼叫的監控和追蹤能力

### 解決方案概述
LLM Gateway 提供：
1. 作為 Claude API 的代理閘道，所有請求經由閘道轉發
2. OAuth2 認證機制（自建認證伺服器 + JWKS 驗證），開發者使用企業憑證存取
3. 攔截每次請求的 Token 用量 (input_tokens, output_tokens)，透過 GCP Pub/Sub 發送用量事件
4. 支援多組 Anthropic API Key，採用 Round Robin 策略輪換
5. 整合 OpenTelemetry 實現完整的可觀測性
6. Circuit Breaker 保護後端服務

### 與 Gate-CLI 的關係
- **Gate-CLI**: 獨立專案，客戶端 CLI 工具，負責 OAuth2 登入、Token 取得/刷新、配置 Claude Code
- **LLM Gateway**: 伺服端閘道服務，驗證 Token、代理請求、發送用量事件
- Gate-CLI 配置 Claude Code 使用 `ANTHROPIC_BASE_URL` 指向 LLM Gateway
- Token 刷新由 Gate-CLI 負責，Gateway 只負責驗證

---

## 3. 參考文件與技術規格

### 3.1 Claude API 技術規格

#### Messages API 端點
**POST** `/v1/messages`

**請求格式**:
```json
{
  "model": "claude-sonnet-4-5-20250929",
  "max_tokens": 1024,
  "messages": [
    {"role": "user", "content": "Hello, Claude"}
  ],
  "stream": false
}
```

**非串流回應格式** (較少使用):
```json
{
  "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
  "type": "message",
  "role": "assistant",
  "content": [
    {"type": "text", "text": "Hello! How can I help you today?"}
  ],
  "model": "claude-sonnet-4-5-20250929",
  "stop_reason": "end_turn",
  "usage": {
    "input_tokens": 25,
    "output_tokens": 15,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0
  }
}
```

> **重要**: Claude Code CLI **幾乎總是使用串流模式** (`stream: true`)。這是因為：
> 1. Claude Code 需要在終端機即時顯示 AI 回應（token-by-token）
> 2. Anthropic 官方建議對長時間請求使用串流模式
> 3. Claude Code 的請求通常包含大量 context，適合串流處理
>
> 非串流模式可能僅在特定 headless 場景下使用（如 `claude -p "prompt" --output-format json`）。
> **LLM Gateway 應優先處理串流請求作為主要使用場景。**

#### 串流回應 (SSE) 格式 - 主要模式
當 `stream: true` 時，使用 Server-Sent Events (SSE) 格式回應：

```
event: message_start
data: {"type": "message_start", "message": {"id": "msg_...", "usage": {"input_tokens": 25, "output_tokens": 1}}}

event: content_block_start
data: {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}

event: content_block_delta
data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Hello"}}

event: content_block_stop
data: {"type": "content_block_stop", "index": 0}

event: message_delta
data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 15}}

event: message_stop
data: {"type": "message_stop"}
```

#### Token 用量欄位說明
| 欄位 | 說明 |
|------|------|
| `input_tokens` | 輸入消耗的 Token 數量 |
| `output_tokens` | 輸出生成的 Token 數量 |
| `cache_creation_input_tokens` | 建立快取消耗的 Token |
| `cache_read_input_tokens` | 從快取讀取的 Token |

**重要**: 在串流模式下，`input_tokens` 在 `message_start` 事件中提供，最終的 `output_tokens` 在 `message_delta` 事件中提供（累計值）。

### 3.2 Spring Cloud Gateway Server Web MVC

#### 與 WebFlux 的主要差異
- **Web MVC**: 基於傳統 Servlet，使用同步阻塞模型
- **WebFlux**: 基於 Reactor，使用非阻塞反應式模型

選擇 Web MVC 的原因：
1. 團隊更熟悉同步程式設計模型
2. 與現有 Spring Security 整合更直接
3. 調試和錯誤處理更直觀
4. 對於代理場景，效能差異不顯著

#### 核心元件
- **RouterFunctions.Builder**: 定義路由規則
- **HandlerFunctions.http()**: HTTP 代理處理器
- **BeforeFilterFunctions**: 請求前置處理
- **AfterFilterFunctions**: 回應後置處理
- **GatewayRequestPredicates**: 請求匹配條件

### 3.3 Claude Code CLI 整合

Claude Code CLI 透過環境變數配置使用自訂 API 端點：

```json
{
  "env": {
    "ANTHROPIC_BASE_URL": "https://llm-gateway.company.com",
    "ANTHROPIC_AUTH_TOKEN": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

- `ANTHROPIC_BASE_URL`: 指向 LLM Gateway 的 URL
- `ANTHROPIC_AUTH_TOKEN`: OAuth2 Bearer Token（不含 "Bearer " 前綴）

---

## 4. 功能需求

### 4.1 核心功能

#### 4.1.1 API 代理
**目的**: 將 Claude API 請求從客戶端代理到 Anthropic API

**支援的端點**:
| 端點 | 方法 | 說明 | 使用頻率 |
|------|------|------|----------|
| `/v1/messages` (stream: true) | POST | Messages API 串流模式 | **主要** (~99%) |
| `/v1/messages` (stream: false) | POST | Messages API 非串流模式 | 次要 (~1%) |

> **注意**: Claude Code CLI 預設使用串流模式以實現即時回應顯示。Gateway 設計應以串流處理為核心。

**行為**:
1. 接收客戶端請求
2. 驗證 OAuth2 Bearer Token
3. 將 Bearer Token 替換為 Anthropic API Key
4. 轉發請求至 `api.anthropic.com`
5. 處理回應並提取 Token 用量
6. 記錄用量資訊
7. 將回應返回給客戶端

#### 4.1.2 OAuth2 認證
**目的**: 驗證客戶端的存取權限

**認證伺服器**: 自建 OAuth2 認證伺服器，提供 JWKS 端點

**流程**:
1. 客戶端在 `Authorization` Header 中提供 Bearer Token
2. Gateway 透過 JWKS 端點取得公鑰，驗證 JWT 簽章
3. 驗證 Token 的有效性（簽章、過期時間、audience）
4. 從 Token 的 `sub` claim 提取用戶識別資訊
5. 將 `sub` 用於用量事件記錄

**Token 格式**: JWT Access Token（RS256 簽章）

**必要 Claims**:
| Claim | 說明 |
|-------|------|
| `sub` | 用戶唯一識別碼，用於用量追蹤 |
| `exp` | Token 過期時間 |
| `aud` | 目標 audience（需包含 `llm-gateway`）|

> **注意**: Token 刷新由 Gate-CLI 負責，Gateway 只負責驗證。

#### 4.1.3 API Key 輪換
**目的**: 支援多組 Anthropic API Key，分散 API 配額壓力並提供容錯能力

**輪換策略**: Round Robin（依序輪換）

**配置方式**:
```yaml
anthropic:
  api:
    keys:
      - sk-ant-api-key-1
      - sk-ant-api-key-2
      - sk-ant-api-key-3
```

**行為**:
1. 啟動時載入所有配置的 API Key
2. 每次請求依序選擇下一個 API Key
3. 使用 AtomicInteger 確保執行緒安全
4. 當某個 Key 返回 429 (Rate Limit) 時，記錄警告但不自動移除

**健康檢查**:
- 定期驗證各 API Key 的有效性（可選功能）
- 在 `/actuator/health` 中顯示可用 Key 數量

#### 4.1.4 Token 用量追蹤
**目的**: 擷取每次 API 呼叫的 Token 使用量，透過 GCP Pub/Sub 發送用量事件

**非串流模式處理**:
1. 代理請求到 Anthropic API
2. 解析完整的 JSON 回應
3. 從 `usage` 物件提取 Token 資訊
4. 發送用量事件到 Pub/Sub

**串流模式處理**:
1. 代理請求到 Anthropic API
2. 透傳 SSE 事件給客戶端
3. 同時攔截並解析 SSE 事件：
   - 從 `message_start` 提取 `input_tokens`
   - 從 `message_delta` 提取最終 `output_tokens`
4. 在串流結束後發送完整用量事件到 Pub/Sub

**Pub/Sub 配置**:
- Topic 名稱: `llm-gateway-usage`
- 使用 Spring Cloud GCP Pub/Sub Stream Binder
- 事件格式: [CloudEvents](https://cloudevents.io/) 規範

**用量事件結構** (CloudEvents 格式，發送到 Pub/Sub):
```json
{
  "specversion": "1.0",
  "type": "io.github.samzhu.llmgateway.usage.v1",
  "source": "/llm-gateway/messages",
  "id": "req_abc123",
  "time": "2025-11-25T10:30:00.000Z",
  "datacontenttype": "application/json",
  "subject": "user-uuid-12345",
  "data": {
    "model": "claude-sonnet-4-5-20250929",
    "input_tokens": 1250,
    "output_tokens": 850,
    "cache_creation_tokens": 0,
    "cache_read_tokens": 500,
    "total_tokens": 2100,
    "latency_ms": 2500,
    "stream": true,
    "status": "success"
  }
}
```

**CloudEvents 屬性說明**:
| 屬性 | 說明 |
|------|------|
| `specversion` | CloudEvents 規範版本 |
| `type` | 事件類型（用於事件路由和過濾）|
| `source` | 事件來源（Gateway 端點）|
| `id` | 事件唯一識別碼（對應 request_id）|
| `time` | 事件產生時間（ISO 8601 格式）|
| `subject` | 事件主體（JWT sub claim，用於識別用戶）|
| `data` | 事件資料內容 |

> **注意**: Gateway 只負責發送用量事件，用量儲存與查詢由下游服務處理。使用 CloudEvents 標準格式有助於跨系統整合和事件處理。

#### 4.1.5 Circuit Breaker
**目的**: 防止 Anthropic API 故障時的級聯影響

**配置**:
- 失敗率閾值: 50%
- 慢呼叫閾值: 100% (超過 30 秒視為慢呼叫)
- 開路狀態持續時間: 60 秒
- 半開狀態允許呼叫數: 10

**回退行為**:
當 Circuit Breaker 開路時，返回 503 Service Unavailable：
```json
{
  "error": {
    "type": "service_unavailable",
    "message": "LLM service is temporarily unavailable. Please retry later."
  }
}
```

### 4.2 可觀測性

#### 4.2.1 分散式追蹤 (OpenTelemetry)
**Trace 傳播**:
- 支援 W3C Trace Context 標準
- 在轉發請求時傳遞 Trace ID 和 Span ID
- 記錄關鍵 Span 事件

**Span 命名**:
- `gateway.auth.validate`: Token 驗證
- `gateway.proxy.request`: 代理請求
- `gateway.usage.publish`: 用量事件發送

**Span 屬性**:
```
llm.model: claude-sonnet-4-5-20250929
llm.input_tokens: 1250
llm.output_tokens: 850
user.subject: user-uuid-12345
```

#### 4.2.2 指標 (Metrics)
**請求指標**:
- `llm_gateway_requests_total`: 總請求數（按 model, status 分組）
- `llm_gateway_request_duration_seconds`: 請求延遲直方圖
- `llm_gateway_errors_total`: 錯誤數（按 error_type 分組）

**Token 用量指標**:
- `llm_gateway_input_tokens_total`: 輸入 Token 總數（按 model, subject 分組）
- `llm_gateway_output_tokens_total`: 輸出 Token 總數（按 model, subject 分組）
- `llm_gateway_cache_tokens_total`: 快取 Token 總數

**Circuit Breaker 指標**:
- `resilience4j_circuitbreaker_state`: Circuit Breaker 狀態
- `resilience4j_circuitbreaker_calls_total`: 呼叫總數
- `resilience4j_circuitbreaker_failure_rate`: 失敗率

#### 4.2.3 日誌
**結構化 JSON 日誌**:
```json
{
  "timestamp": "2025-11-25T10:30:00.123Z",
  "level": "INFO",
  "logger": "llm-gateway",
  "message": "Request completed",
  "trace_id": "abc123",
  "span_id": "def456",
  "request_id": "req_xyz",
  "subject": "user-uuid-12345",
  "model": "claude-sonnet-4-5",
  "input_tokens": 1250,
  "output_tokens": 850,
  "latency_ms": 2500
}
```

### 4.3 管理功能

#### 4.3.1 健康檢查端點
**端點**: `GET /actuator/health`

**回應**:
```json
{
  "status": "UP",
  "components": {
    "circuitBreaker": {"status": "UP", "details": {"state": "CLOSED"}},
    "pubsub": {"status": "UP"},
    "apiKeys": {"status": "UP", "details": {"count": 3}},
    "diskSpace": {"status": "UP"}
  }
}
```

> **注意**: 用量查詢 API 不在 Gateway 範圍內，由下游服務（消費 Pub/Sub 事件）提供。

---

## 5. 非功能性需求

### 5.1 效能
- 代理延遲增加: < 50ms (不含 Anthropic API 回應時間)
- 吞吐量: 支援 1000 requests/second
- 串流首字節延遲: < 100ms (從收到 Anthropic 回應到轉發給客戶端)
- 記憶體佔用: < 512MB (處理並發串流時)

### 5.2 可靠性
- 服務可用性目標: 99.9%
- 串流連線保持: 支援長達 10 分鐘的串流回應
- 優雅降級: Pub/Sub 發送失敗不影響主要代理功能（記錄錯誤後繼續）
- 訊息可靠性: Pub/Sub 確保用量事件至少送達一次

### 5.3 安全性
- 所有通訊使用 TLS 1.3
- Anthropic API Key 透過環境變數或配置注入，不在日誌中出現
- OAuth2 Token 驗證使用非對稱加密 (RS256)，透過 JWKS 端點取得公鑰
- 審計日誌（透過 Pub/Sub 事件）保留由下游服務決定

### 5.4 可擴展性
- 水平擴展: 支援多實例部署，無狀態設計
- 用量事件: 非同步發送到 GCP Pub/Sub，使用 Spring Cloud GCP Stream Binder

---

## 6. 技術架構

### 6.1 專案結構
```
llm-gateway/
├── src/main/java/io/github/samzhu/llmgateway/
│   ├── LlmGatewayApplication.java           # Spring Boot 應用程式
│   ├── config/
│   │   ├── GatewayConfig.java               # 閘道路由配置
│   │   ├── SecurityConfig.java              # OAuth2 安全配置 (JWKS)
│   │   ├── Resilience4jConfig.java          # Circuit Breaker 配置
│   │   ├── PubSubConfig.java                # GCP Pub/Sub 配置
│   │   └── ObservabilityConfig.java         # 追蹤和指標配置
│   ├── filter/
│   │   ├── AuthenticationFilter.java        # OAuth2 Token 驗證
│   │   ├── ApiKeyInjectionFilter.java       # Anthropic API Key 注入 (Round Robin)
│   │   ├── UsageTrackingFilter.java         # Token 用量追蹤
│   │   ├── StreamingUsageFilter.java        # 串流用量追蹤
│   │   └── RequestLoggingFilter.java        # 請求日誌
│   ├── handler/
│   │   ├── ProxyHandler.java                # HTTP 代理處理
│   │   └── StreamingProxyHandler.java       # 串流代理處理 (Web MVC SseEmitter)
│   ├── service/
│   │   ├── UsageEventPublisher.java         # 用量事件發送 (CloudEvents + Pub/Sub)
│   │   ├── ApiKeyRotationService.java       # API Key 輪換服務
│   │   └── TokenValidationService.java      # Token 驗證服務
│   ├── model/
│   │   ├── UsageEventData.java              # 用量事件資料 (CloudEvents data)
│   │   ├── ClaudeRequest.java               # Claude API 請求 DTO
│   │   ├── ClaudeResponse.java              # Claude API 回應 DTO
│   │   └── StreamEvent.java                 # SSE 事件 DTO
│   ├── exception/
│   │   ├── AuthenticationException.java
│   │   └── UpstreamException.java
│   └── util/
│       ├── SseParser.java                   # SSE 事件解析
│       └── TokenExtractor.java              # Token 資訊提取
├── src/main/resources/
│   ├── application.yml                       # 主配置
│   └── application-prod.yml                  # 生產環境配置
└── src/test/java/                            # 測試
```

### 6.2 核心元件

#### 6.2.1 閘道路由配置
```java
@Configuration
public class GatewayConfig {

    @Bean
    public RouterFunction<ServerResponse> gatewayRoutes(
            ProxyHandler proxyHandler,
            StreamingProxyHandler streamingProxyHandler,
            AuthenticationFilter authFilter,
            ApiKeyInjectionFilter apiKeyFilter,
            UsageTrackingFilter usageFilter) {

        return route()
            // Messages API - 非串流
            .POST("/v1/messages",
                request -> !isStreamingRequest(request),
                proxyHandler::handle)
            .before(authFilter::authenticate)
            .before(apiKeyFilter::injectApiKey)
            .after(usageFilter::trackUsage)

            // Messages API - 串流
            .POST("/v1/messages",
                request -> isStreamingRequest(request),
                streamingProxyHandler::handle)
            .before(authFilter::authenticate)
            .before(apiKeyFilter::injectApiKey)
            .build();
    }

    private boolean isStreamingRequest(ServerRequest request) {
        // 檢查 stream 參數或請求體
        return request.param("stream")
            .map(Boolean::parseBoolean)
            .orElse(false);
    }
}
```

#### 6.2.2 API Key 輪換服務
```java
@Component
public class ApiKeyRotationService {

    private final List<String> apiKeys;
    private final AtomicInteger counter = new AtomicInteger(0);

    public ApiKeyRotationService(
            @Value("${anthropic.api.keys}") List<String> apiKeys) {
        this.apiKeys = apiKeys;
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("At least one API key is required");
        }
    }

    /**
     * Round Robin 策略取得下一個 API Key
     */
    public String getNextApiKey() {
        int index = counter.getAndIncrement() % apiKeys.size();
        return apiKeys.get(index);
    }

    public int getKeyCount() {
        return apiKeys.size();
    }
}
```

#### 6.2.3 串流用量追蹤 (Web MVC)
```java
@Component
public class StreamingUsageHandler {

    private final UsageEventPublisher usageEventPublisher;

    /**
     * 使用 Web MVC 的 SseEmitter 處理串流回應
     */
    public SseEmitter handleStreaming(
            InputStream upstreamResponse,
            RequestContext context) {

        SseEmitter emitter = new SseEmitter(600_000L); // 10 分鐘超時
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

        // 在背景執行緒處理 SSE 串流
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(upstreamResponse))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        // 提取 Token 用量
                        parseUsage(data, inputTokens, outputTokens);
                        // 轉發 SSE 事件給客戶端
                        emitter.send(SseEmitter.event().data(data));
                    } else if (!line.isEmpty()) {
                        emitter.send(SseEmitter.event().data(line));
                    }
                }
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            } finally {
                // 串流結束，發送 CloudEvents 格式的用量事件到 Pub/Sub
                UsageEventData eventData = UsageEventData.builder()
                    .model(context.getModel())
                    .inputTokens(inputTokens.get())
                    .outputTokens(outputTokens.get())
                    .totalTokens(inputTokens.get() + outputTokens.get())
                    .latencyMs(System.currentTimeMillis() - startTime.get())
                    .stream(true)
                    .status("success")
                    .build();

                usageEventPublisher.publish(
                    eventData,
                    context.getRequestId(),
                    context.getSubject()
                );
            }
        });

        return emitter;
    }
}
```

#### 6.2.4 用量事件發送 (CloudEvents + Pub/Sub)
```java
@Component
public class UsageEventPublisher {

    private final StreamBridge streamBridge;
    private static final String BINDING_NAME = "usageEvent-out-0";
    private static final String EVENT_TYPE = "io.github.samzhu.llmgateway.usage.v1";
    private static final URI EVENT_SOURCE = URI.create("/llm-gateway/messages");

    public void publish(UsageEventData eventData, String requestId, String subject) {
        // 建立 CloudEvent
        CloudEvent cloudEvent = CloudEventBuilder.v1()
            .withId(requestId)
            .withType(EVENT_TYPE)
            .withSource(EVENT_SOURCE)
            .withTime(OffsetDateTime.now())
            .withSubject(subject)
            .withDataContentType("application/json")
            .withData(PojoCloudEventData.wrap(eventData,
                data -> new ObjectMapper().writeValueAsBytes(data)))
            .build();

        // 發送到 Pub/Sub
        streamBridge.send(BINDING_NAME, cloudEvent);
    }
}

/**
 * 用量事件資料內容
 */
@Data
@Builder
public class UsageEventData {
    private String model;
    private int inputTokens;
    private int outputTokens;
    private int cacheCreationTokens;
    private int cacheReadTokens;
    private int totalTokens;
    private long latencyMs;
    private boolean stream;
    private String status;
}
```

> **參考文件**: [CloudEvents Java SDK - Spring Integration](https://cloudevents.github.io/sdk-java/spring.html)

#### 6.2.5 Circuit Breaker 配置
```java
@Configuration
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slowCallRateThreshold(100)
            .slowCallDurationThreshold(Duration.ofSeconds(30))
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(10)
            .slidingWindowSize(100)
            .slidingWindowType(SlidingWindowType.COUNT_BASED)
            .build();
    }

    @Bean
    public CircuitBreaker anthropicCircuitBreaker(CircuitBreakerConfig config) {
        return CircuitBreaker.of("anthropic-api", config);
    }
}
```

### 6.3 依賴管理

**build.gradle** (已提供):
```gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.0'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'org.graalvm.buildtools.native' version '0.11.3'
    id 'org.cyclonedx.bom' version '3.0.1'
}

group = 'io.github.samzhu'
version = '0.0.1-SNAPSHOT'
description = 'LLM Gateway'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

ext {
    set('springCloudVersion', "2025.1.0-RC1")
    set('springCloudGcpVersion', "7.4.1")
    set('cloudeventsVersion', "4.0.1")
}

dependencies {
    // 可觀測性
    implementation 'org.springframework.boot:spring-boot-micrometer-tracing'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'
    implementation 'io.micrometer:micrometer-tracing-bridge-brave'
    runtimeOnly 'io.micrometer:micrometer-registry-otlp'

    // 安全性 (OAuth2 Resource Server with JWKS)
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'

    // 閘道核心
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc'

    // 韌性
    implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j'

    // GCP Pub/Sub (用量事件發送)
    implementation 'com.google.cloud:spring-cloud-gcp-pubsub-stream-binder'

    // CloudEvents (標準化事件格式)
    implementation "io.cloudevents:cloudevents-spring:${cloudeventsVersion}"
    implementation "io.cloudevents:cloudevents-json-jackson:${cloudeventsVersion}"

    // 開發工具
    compileOnly 'org.projectlombok:lombok'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'
    annotationProcessor 'org.projectlombok:lombok'

    // 測試
    testImplementation 'org.springframework.boot:spring-boot-micrometer-tracing-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-actuator-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-opentelemetry-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.springframework.cloud:spring-cloud-stream-test-binder'
    testImplementation 'org.testcontainers:testcontainers-grafana'
    testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
        mavenBom "com.google.cloud:spring-cloud-gcp-dependencies:${springCloudGcpVersion}"
    }
}
```

### 6.4 配置

**application.yml**:
```yaml
spring:
  application:
    name: llm-gateway
  cloud:
    gateway:
      server:
        webmvc:
          enabled: true
    # GCP Pub/Sub Stream Binder 配置
    stream:
      bindings:
        usageEvent-out-0:
          destination: llm-gateway-usage
      gcp:
        pubsub:
          project-id: ${GCP_PROJECT_ID}
  # OAuth2 Resource Server 配置 (JWKS)
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.company.com
          jwk-set-uri: https://auth.company.com/.well-known/jwks.json
          audiences:
            - llm-gateway

# Anthropic API 配置
anthropic:
  api:
    base-url: https://api.anthropic.com
    # 多個 API Key (Round Robin 輪換)
    keys:
      - ${ANTHROPIC_API_KEY_1}
      - ${ANTHROPIC_API_KEY_2}
      - ${ANTHROPIC_API_KEY_3}

# Resilience4j 配置
resilience4j:
  circuitbreaker:
    instances:
      anthropic-api:
        failure-rate-threshold: 50
        slow-call-rate-threshold: 100
        slow-call-duration-threshold: 30s
        wait-duration-in-open-state: 60s
        permitted-number-of-calls-in-half-open-state: 10
        sliding-window-size: 100

# 可觀測性配置
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
    metrics:
      endpoint: http://otel-collector:4318/v1/metrics

# 日誌配置
logging:
  pattern:
    console: '{"timestamp":"%d","level":"%p","logger":"%logger","message":"%m","trace_id":"%X{traceId}","span_id":"%X{spanId}"}%n'
```

> **參考文件**: [Spring Cloud GCP Pub/Sub Stream Binder](https://googlecloudplatform.github.io/spring-cloud-gcp/7.4.1/reference/html/index.html#spring-cloud-stream)

---

## 7. 請求處理流程

### 7.1 非串流請求流程
```
Client                     LLM Gateway                    Anthropic API      Pub/Sub
  |                             |                              |                |
  |---(1) POST /v1/messages---->|                              |                |
  |      Authorization: Bearer  |                              |                |
  |                             |                              |                |
  |                     (2) 驗證 JWT (JWKS)                     |                |
  |                             |                              |                |
  |                     (3) Round Robin 選擇 API Key           |                |
  |                             |                              |                |
  |                             |---(4) POST /v1/messages----->|                |
  |                             |      x-api-key: sk-ant-...   |                |
  |                             |                              |                |
  |                             |<---(5) 200 OK + JSON---------|                |
  |                             |                              |                |
  |                     (6) 解析回應，提取 usage               |                |
  |                             |                              |                |
  |                             |---(7) 發送用量事件---------->|--------------->|
  |                             |                              |                |
  |<---(8) 200 OK + JSON--------|                              |                |
```

### 7.2 串流請求流程
```
Client                     LLM Gateway                    Anthropic API      Pub/Sub
  |                             |                              |                |
  |---(1) POST /v1/messages---->|                              |                |
  |      stream: true           |                              |                |
  |                             |                              |                |
  |                     (2) 驗證 JWT (JWKS)                     |                |
  |                             |                              |                |
  |                     (3) Round Robin 選擇 API Key           |                |
  |                             |                              |                |
  |                             |---(4) POST /v1/messages----->|                |
  |                             |      stream: true            |                |
  |                             |                              |                |
  |                             |<---(5) SSE: message_start----|                |
  |<---(5a) SSE: message_start--|      (提取 input_tokens)     |                |
  |                             |                              |                |
  |                             |<---(6) SSE: content_block_*--|                |
  |<---(6a) SSE: content_block_*|      (透傳)                  |                |
  |                             |                              |                |
  |                             |<---(7) SSE: message_delta----|                |
  |<---(7a) SSE: message_delta--|      (提取 output_tokens)    |                |
  |                             |                              |                |
  |                             |<---(8) SSE: message_stop-----|                |
  |<---(8a) SSE: message_stop---|                              |                |
  |                             |                              |                |
  |                             |---(9) 發送用量事件---------->|--------------->|
```

---

## 8. 錯誤處理

### 8.1 錯誤回應格式
所有錯誤遵循 Anthropic API 錯誤格式，確保客戶端相容性：

```json
{
  "type": "error",
  "error": {
    "type": "authentication_error",
    "message": "Invalid or expired access token"
  }
}
```

### 8.2 錯誤類型對應
| Gateway 錯誤 | HTTP Status | Error Type | 說明 |
|--------------|-------------|------------|------|
| Token 無效 | 401 | authentication_error | JWT Token 驗證失敗（簽章或格式錯誤）|
| Token 過期 | 401 | authentication_error | Token 已過期 |
| 權限不足 | 403 | permission_error | 無權存取此資源（audience 不符）|
| Circuit Open | 503 | overloaded_error | 服務暫時不可用 |
| 上游錯誤 | 502 | api_error | Anthropic API 回應錯誤 |

### 8.3 上游錯誤透傳
當 Anthropic API 回傳錯誤時，Gateway 應：
1. 記錄錯誤資訊（不記錄敏感內容）
2. 原樣轉發錯誤回應給客戶端
3. 更新 Circuit Breaker 狀態

---

## 9. 測試策略

### 9.1 單元測試
**覆蓋率目標**: 最低 80%

**關鍵測試領域**:
- `SseParser`: SSE 事件解析和 Token 提取
- `TokenExtractor`: 從 JSON 回應提取 usage
- `ApiKeyRotationService`: Round Robin 輪換邏輯
- `UsageEventPublisher`: 用量事件發送邏輯

### 9.2 整合測試
使用 Testcontainers：
1. 模擬 Anthropic API 的 WireMock 容器
2. GCP Pub/Sub 模擬器（或 Spring Cloud Stream Test Binder）
3. Grafana Stack 用於可觀測性驗證

**測試情境**:
- 非串流請求完整流程
- 串流請求完整流程
- Circuit Breaker 開路和恢復
- 用量事件正確發送到 Pub/Sub
- JWT Token 驗證失敗處理
- API Key 輪換正確性

### 9.3 負載測試
- 使用 k6 或 Gatling
- 目標: 1000 RPS，P99 延遲 < 100ms
- 驗證串流連線長時間穩定性

---

## 10. 部署

### 10.1 容器化部署
```dockerfile
FROM eclipse-temurin:25-jre-alpine
COPY build/libs/llm-gateway.jar /app/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/llm-gateway.jar"]
```

### 10.2 GraalVM Native 部署
```bash
./gradlew nativeCompile
# 產出: build/native/nativeCompile/llm-gateway
```

優勢:
- 啟動時間 < 100ms
- 記憶體佔用減少 70%
- 適合 Kubernetes 環境

> **⚠️ AOT/Native Image 重要須知**
>
> Spring Cloud 的 `RefreshScope` **不支援 AOT 和 Native Image 編譯**。必須在 `application.yaml` 中設置：
> ```yaml
> spring:
>   cloud:
>     refresh:
>       enabled: false
> ```
>
> **原因**：AOT 在建置時就固定了 classpath 和 bean 定義，而 `@RefreshScope` 設計用於運行時動態重新創建 beans，兩者概念互相衝突。
>
> **參考文件**：[Spring Cloud AOT Wiki](https://github.com/spring-cloud/spring-cloud-release/wiki/AOT-transformations-and-native-image-support)

### 10.3 Kubernetes 配置
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llm-gateway
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: llm-gateway
        image: llm-gateway:latest
        ports:
        - containerPort: 8080
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
        env:
        - name: GCP_PROJECT_ID
          value: "your-gcp-project"
        - name: ANTHROPIC_API_KEY_1
          valueFrom:
            secretKeyRef:
              name: anthropic-secrets
              key: api-key-1
        - name: ANTHROPIC_API_KEY_2
          valueFrom:
            secretKeyRef:
              name: anthropic-secrets
              key: api-key-2
        - name: ANTHROPIC_API_KEY_3
          valueFrom:
            secretKeyRef:
              name: anthropic-secrets
              key: api-key-3
```

---

## 11. 監控與告警

### 11.1 儀表板
推薦 Grafana 儀表板包含：
1. **請求概覽**: RPS、延遲分布、錯誤率
2. **Token 用量**: 按模型/subject 的 Token 消耗趨勢（來自 Pub/Sub 下游處理）
3. **Circuit Breaker**: 狀態變化和呼叫統計
4. **API Key 使用**: 各 API Key 的使用次數分布

### 11.2 告警規則
| 告警 | 條件 | 嚴重度 |
|------|------|--------|
| 高錯誤率 | error_rate > 5% 持續 5 分鐘 | Critical |
| Circuit Breaker Open | state = OPEN | Warning |
| 高延遲 | P99 > 10s 持續 5 分鐘 | Warning |
| 用量異常 | 日用量超過均值 300% | Warning |

---

## 12. 安全考量

### 12.1 API Key 保護
- Anthropic API Key 儲存於 Kubernetes Secret 或雲端 Secret Manager
- 運行時透過環境變數注入，不寫入配置檔
- 日誌中自動遮蔽 API Key

### 12.2 審計日誌
透過 Pub/Sub 用量事件記錄所有 API 存取：
- 誰 (subject - 來自 JWT sub claim)
- 何時 (timestamp)
- 什麼 (model, endpoint)
- 多少 (input_tokens, output_tokens)

> **注意**: 審計日誌的儲存與查詢由下游服務（消費 Pub/Sub 事件）處理。

### 12.3 資料隱私
- 不記錄請求/回應的實際內容
- 只記錄元數據 (用量、延遲、狀態)
- 符合 GDPR 資料最小化原則

---

## 13. 未來擴展

### 13.1 短期規劃
- 支援多個 LLM 提供者 (OpenAI, Google Gemini)
- 用戶/團隊配額管理（透過下游服務實現）
- IP 白名單與速率限制

### 13.2 長期規劃
- 語義快取 (Semantic Caching)
- 請求重試和負載均衡
- A/B 測試支援
- 成本分攤報表（透過下游服務實現）

---

## 附錄

### 附錄 A: Claude API 模型定價參考
| 模型 | Input (per 1M tokens) | Output (per 1M tokens) |
|------|----------------------|------------------------|
| Claude Opus 4 | $15.00 | $75.00 |
| Claude Sonnet 4 | $3.00 | $15.00 |
| Claude Haiku 3.5 | $0.80 | $4.00 |

### 附錄 B: 相關文件連結
- [Spring Cloud Gateway Server Web MVC](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/starter.html)
- [Spring Cloud GCP Pub/Sub Stream Binder](https://googlecloudplatform.github.io/spring-cloud-gcp/7.4.1/reference/html/index.html#spring-cloud-stream)
- [CloudEvents Specification](https://cloudevents.io/)
- [CloudEvents Java SDK - Spring Integration](https://cloudevents.github.io/sdk-java/spring.html)
- [Claude Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [Claude Streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [Claude Token Counting](https://platform.claude.com/docs/en/build-with-claude/token-counting)
- [Claude Code Network Configuration](https://code.claude.com/docs/en/network-config)

### 附錄 C: SSE 事件完整範例

**message_start**:
```json
{
  "type": "message_start",
  "message": {
    "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
    "type": "message",
    "role": "assistant",
    "content": [],
    "model": "claude-sonnet-4-5-20250929",
    "stop_reason": null,
    "usage": {
      "input_tokens": 25,
      "output_tokens": 1
    }
  }
}
```

**message_delta** (最後一個):
```json
{
  "type": "message_delta",
  "delta": {
    "stop_reason": "end_turn",
    "stop_sequence": null
  },
  "usage": {
    "output_tokens": 15
  }
}
```

---

## 文件核准

| 角色 | 姓名 | 日期 | 簽名 |
|------|------|------|------|
| 產品負責人 | | | |
| 技術主管 | | | |
| 安全審查者 | | | |

---

## 修訂歷史

| 版本 | 日期 | 作者 | 變更 |
|------|------|------|------|
| 1.0 | 2025-11-25 | AI 助理 | 初始 PRD 建立 |
| 1.1 | 2025-11-25 | AI 助理 | 需求釐清後更新：OAuth2 使用自建伺服器 + JWKS、JWT sub claim 作為識別、多 API Key Round Robin 輪換、GCP Pub/Sub 發送用量事件、Web MVC SSE 處理、移除用量查詢 API/IP 白名單/速率限制/動態配置更新 |
| 1.2 | 2025-11-25 | AI 助理 | 新增 CloudEvents 事件格式：用量事件採用 CloudEvents v1.0 規範、新增 cloudevents-spring 和 cloudevents-json-jackson 依賴 |
