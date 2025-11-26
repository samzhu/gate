# LLM Gateway 開發計劃

## 專案現況

- Spring Boot 4.0.0 + Java 25 + GraalVM Native 框架已建立
- 依賴已配置（Gateway, Security, Resilience4j, Pub/Sub, CloudEvents, OpenTelemetry）
- 需要實作所有業務邏輯

---

## 開發階段

### Phase 1: 基礎設施與配置

#### 1.1 應用程式配置 (`application.yaml`)
- [ ] 配置 Spring Cloud Gateway Server Web MVC
- [ ] 配置 OAuth2 Resource Server (jwk-set-uri)
- [ ] 配置 Anthropic API 設定（base-url, keys）
- [ ] 配置 GCP Pub/Sub Stream Binder
- [ ] 配置 Resilience4j Circuit Breaker
- [ ] 配置 OpenTelemetry（tracing, metrics）
- [ ] 配置結構化 JSON 日誌

#### 1.2 配置屬性類
- [ ] `AnthropicProperties.java` - Anthropic API 配置屬性

---

### Phase 2: 核心模型 (Model)

#### 2.1 請求/回應 DTO
- [ ] `ClaudeRequest.java` - Claude API 請求 DTO
- [ ] `ClaudeResponse.java` - Claude API 回應 DTO（非串流）
- [ ] `StreamEvent.java` - SSE 事件 DTO

#### 2.2 用量事件
- [ ] `UsageEventData.java` - 用量事件資料（CloudEvents data payload）

#### 2.3 錯誤回應
- [ ] `GatewayError.java` - 閘道錯誤回應格式

---

### Phase 3: 服務層 (Service)

#### 3.1 API Key 輪換服務
- [ ] `ApiKeyRotationService.java` - Round Robin API Key 輪換

#### 3.2 用量事件發送
- [ ] `UsageEventPublisher.java` - CloudEvents + Pub/Sub 發送

#### 3.3 SSE 解析工具
- [ ] `SseParser.java` - SSE 事件解析
- [ ] `TokenExtractor.java` - Token 用量提取

---

### Phase 4: 安全配置 (Security)

#### 4.1 OAuth2 Resource Server
- [ ] `SecurityConfig.java` - Spring Security 配置
  - 配置 JWT 驗證（僅 jwk-set-uri）
  - 配置端點權限（/v1/messages 需認證，/actuator/** 公開）
  - 停用 CSRF（API Gateway）

---

### Phase 5: 閘道路由與過濾器 (Gateway)

#### 5.1 閘道路由配置
- [ ] `GatewayConfig.java` - 路由配置
  - POST /v1/messages 路由到 Anthropic API
  - 區分串流/非串流請求

#### 5.2 過濾器
- [ ] `ApiKeyInjectionFilter.java` - 注入 Anthropic API Key（移除 Authorization，加入 x-api-key）
- [ ] `UsageTrackingFilter.java` - 非串流用量追蹤
- [ ] `RequestLoggingFilter.java` - 請求日誌

---

### Phase 6: 串流處理 (Streaming)

#### 6.1 串流代理處理
- [ ] `StreamingProxyHandler.java` - SSE 串流代理
  - 使用 Web MVC SseEmitter
  - 透傳 SSE 事件同時攔截 Token 用量
  - 串流結束後發送用量事件

---

### Phase 7: 韌性設計 (Resilience)

#### 7.1 Circuit Breaker
- [ ] `Resilience4jConfig.java` - Circuit Breaker 配置
- [ ] 整合 Circuit Breaker 到代理請求

#### 7.2 錯誤處理
- [ ] `GlobalExceptionHandler.java` - 全域異常處理
- [ ] 返回 Anthropic API 相容的錯誤格式

---

### Phase 8: 可觀測性 (Observability)

#### 8.1 健康檢查
- [ ] `ApiKeyHealthIndicator.java` - API Key 健康指標
- [ ] 配置 liveness/readiness probes

#### 8.2 指標
- [ ] 自定義指標（llm_gateway_requests_total, llm_gateway_input_tokens_total 等）

---

### Phase 9: 測試

#### 9.1 單元測試
- [ ] `SseParserTest.java`
- [ ] `TokenExtractorTest.java`
- [ ] `ApiKeyRotationServiceTest.java`
- [ ] `UsageEventPublisherTest.java`

#### 9.2 整合測試
- [ ] `GatewayIntegrationTest.java` - 使用 WireMock 模擬 Anthropic API
- [ ] `StreamingIntegrationTest.java` - 串流請求測試
- [ ] `SecurityIntegrationTest.java` - JWT 驗證測試

---

## 檔案結構

```
src/main/java/io/github/samzhu/gate/
├── GateApplication.java                    # 已存在
├── config/
│   ├── AnthropicProperties.java           # Phase 1
│   ├── GatewayConfig.java                 # Phase 5
│   ├── SecurityConfig.java                # Phase 4
│   ├── Resilience4jConfig.java            # Phase 7
│   └── ObservabilityConfig.java           # Phase 8
├── filter/
│   ├── ApiKeyInjectionFilter.java         # Phase 5
│   ├── UsageTrackingFilter.java           # Phase 5
│   └── RequestLoggingFilter.java          # Phase 5
├── handler/
│   └── StreamingProxyHandler.java         # Phase 6
├── service/
│   ├── ApiKeyRotationService.java         # Phase 3
│   └── UsageEventPublisher.java           # Phase 3
├── model/
│   ├── ClaudeRequest.java                 # Phase 2
│   ├── ClaudeResponse.java                # Phase 2
│   ├── StreamEvent.java                   # Phase 2
│   ├── UsageEventData.java                # Phase 2
│   └── GatewayError.java                  # Phase 2
├── util/
│   ├── SseParser.java                     # Phase 3
│   └── TokenExtractor.java                # Phase 3
├── exception/
│   └── GlobalExceptionHandler.java        # Phase 7
└── health/
    └── ApiKeyHealthIndicator.java         # Phase 8

src/main/resources/
├── application.yaml                       # Phase 1 (更新)
└── application-prod.yaml                  # Phase 1

src/test/java/io/github/samzhu/gate/
├── util/
│   ├── SseParserTest.java                # Phase 9
│   └── TokenExtractorTest.java           # Phase 9
├── service/
│   ├── ApiKeyRotationServiceTest.java    # Phase 9
│   └── UsageEventPublisherTest.java      # Phase 9
└── integration/
    ├── GatewayIntegrationTest.java       # Phase 9
    ├── StreamingIntegrationTest.java     # Phase 9
    └── SecurityIntegrationTest.java      # Phase 9
```

---

## 環境變數

### 命名規則

| 類型 | 命名風格 | 理由 |
|------|----------|------|
| **應用程式專屬** | Spring 屬性名稱 | 利用 Spring Boot Relaxed Binding |
| **業界標準** | 保持原樣 (大寫) | 遵循各領域官方規範 |

### 應用程式專屬配置

使用 Spring 屬性名稱格式：

| 變數名稱 | 說明 | 預設值 |
|---------|------|--------|
| `spring.profiles.active` | 啟用的 Profile | `local` |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | JWKS 端點 URL | `https://localhost/.well-known/jwks.json` |
| `anthropic.api.keys` | Anthropic API Keys（逗號分隔）| - |
| `anthropic.api.base-url` | Anthropic API Base URL | `https://api.anthropic.com` |

### 業界標準環境變數

遵循各領域官方規範：

| 變數名稱 | 說明 | 規範來源 |
|---------|------|----------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP Collector 端點 | [OpenTelemetry SDK 規範](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/) |

### 自動取得 (無需設定)

| 項目 | 說明 |
|------|------|
| GCP project-id | 在 GCP 環境自動從 metadata 取得 |
| Pub/Sub Topic | 程式固定使用 `llm-gateway-usage` |

---

## 開發優先順序建議

1. **Phase 1 + 2**: 配置與模型（基礎建設）
2. **Phase 4**: 安全配置（驗證 JWT）
3. **Phase 3**: 服務層（API Key 輪換、用量發送）
4. **Phase 5**: 閘道路由（非串流請求先做）
5. **Phase 6**: 串流處理（核心功能）
6. **Phase 7**: 韌性設計（Circuit Breaker）
7. **Phase 8**: 可觀測性（健康檢查、指標）
8. **Phase 9**: 測試

---

## 預估工作量

| Phase | 說明 | 檔案數 |
|-------|------|-------|
| Phase 1 | 基礎設施與配置 | 2-3 |
| Phase 2 | 核心模型 | 5 |
| Phase 3 | 服務層 | 4 |
| Phase 4 | 安全配置 | 1 |
| Phase 5 | 閘道路由與過濾器 | 4 |
| Phase 6 | 串流處理 | 1 |
| Phase 7 | 韌性設計 | 2 |
| Phase 8 | 可觀測性 | 2 |
| Phase 9 | 測試 | 7 |
| **Total** | | **~28 檔案** |

---

## 備註

- 專案名稱使用 `gate`（現有命名），不改為 `llm-gateway`
- Package 使用 `io.github.samzhu.gate`（現有命名）
- 優先確保核心代理功能運作，再增加可觀測性功能
