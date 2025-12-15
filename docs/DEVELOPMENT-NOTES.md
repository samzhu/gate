# 開發注意事項

> 本文件記錄開發過程中遇到的坑和解決方案，幫助團隊成員避免重複踩坑。

---

## 1. Spring Boot AOT 編譯相關

### 1.1 AOT 會實際啟動 ApplicationContext

**問題**：執行 `./gradlew bootBuildImage` 時，AOT 處理階段會**真正初始化 Spring ApplicationContext**，這意味著所有配置屬性都必須可解析。

**錯誤範例**：
```
Error: Could not resolve placeholder 'gate-jwt-jwk-set-uri' in value "${gate-jwt-jwk-set-uri}"
```

**原因**：AOT 使用預設 profiles (`local,dev`)，但 CI 環境沒有 `application-secrets.properties`。

**解決方案**：所有必要的配置屬性都要提供預設值：
```yaml
# application.yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # 預設值供 AOT 編譯使用，執行時由各環境覆蓋
          jwk-set-uri: ${gate-jwt-jwk-set-uri:https://placeholder.example.com/.well-known/jwks.json}
```

### 1.2 不要用 autoconfigure.exclude 排除 GCP 類別

**問題**：使用 `spring.autoconfigure.exclude` 排除 GCP 自動配置類別，會導致 GraalVM Native Image AOT 編譯時無法註冊 Pub/Sub Binder。

**錯誤做法**：
```yaml
# ❌ 不要這樣做
spring:
  autoconfigure:
    exclude:
      - com.google.cloud.spring.autoconfigure.pubsub.GcpPubSubAutoConfiguration
```

**正確做法**：使用 `enabled: false` 來禁用功能：
```yaml
# ✅ 正確做法
spring:
  cloud:
    gcp:
      core:
        enabled: false
      pubsub:
        enabled: false
      secretmanager:
        enabled: false
```

**原因**：AOT 需要在編譯時知道所有可能的 Binder 類型，排除自動配置會導致 Pub/Sub Binder 不被註冊。

### 1.3 GCP Profile 要明確啟用功能

**問題**：如果 `local` profile 禁用了 GCP 功能，`gcp` profile 需要明確重新啟用。

```yaml
# application-gcp.yaml
spring:
  cloud:
    # 明確啟用 GCP 功能 (覆蓋 local profile 的 enabled: false)
    gcp:
      core:
        enabled: true
      pubsub:
        enabled: true
      secretmanager:
        enabled: true
```

---

## 2. GraalVM Native Image 反射配置

### 2.1 新增 Model 類別必須註冊反射

**問題**：GraalVM Native Image **預設不支援反射**，當你新增需要 Jackson 序列化/反序列化的類別（如 Record、DTO），部署到 Cloud Run 後會出錯。

**錯誤範例**：
```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
Cannot construct instance of `io.github.samzhu.gate.model.YourNewModel`
```

**解決方案**：在 `NativeImageHints.java` 註冊需要反射的類別：

```java
// src/main/java/io/github/samzhu/gate/config/NativeImageHints.java
@Configuration
@RegisterReflectionForBinding({
    // 現有的類別...
    UsageEventData.class,
    StreamEvent.class,
    // 新增你的類別
    YourNewModel.class,
    YourNewModel.NestedClass.class  // 巢狀類別也要註冊
})
public class NativeImageHints {
    // ...
}
```

**重要**：
- 所有需要 JSON 序列化的 Model 都要註冊
- Java Record 的巢狀 Record 也要分別註冊
- Builder 類別也要註冊（如 `UsageEventData.Builder.class`）

### 2.2 Joda-Time 資源已配置（不需修改）

Spring Cloud Function 依賴 Joda-Time，時區資源已在 `NativeImageHints.java` 中註冊：

```java
static class JodaTimeResourcesHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("org/joda/time/tz/data/*");
    }
}
```

### 2.3 如何驗證反射配置

```bash
# 查看生成的反射配置
cat build/resources/aot/META-INF/native-image/io.github.samzhu/gate/reflect-config.json

# AOT 處理詳細日誌
./gradlew processAot --info
```

> 詳細說明請參考 [`docs/GRAALVM-NATIVE-IMAGE.md`](./GRAALVM-NATIVE-IMAGE.md)

---

## 3. 配置檔案架構

### 3.1 配置載入順序

```
Docker Image (classpath)
├── application.yaml          # 基礎共用配置 + 預設值 (AOT 用)
├── application-local.yaml    # 本地基礎設施 (RabbitMQ)
└── application-gcp.yaml      # GCP 基礎設施 (Pub/Sub, sm@ 啟用)

config/ 目錄 (外部配置)
├── application-dev.yaml      # 開發環境設定
├── application-secrets.properties  # 本地機敏值 (不進版控)
└── application-lab.yaml      # Lab 環境設定 (存到 Secret Manager)

Cloud Run 掛載 (Secret Manager)
└── /config/application-lab.yaml  # 從 gate-config secret 掛載
```

### 3.2 Profile 組合

| 環境 | Profiles | 說明 |
|------|----------|------|
| 本地開發 | `local,dev` | 使用 RabbitMQ + 本地 secrets |
| GCP Lab | `gcp,lab` | 使用 Pub/Sub + Secret Manager |
| GCP Prod | `gcp,prod` | 使用 Pub/Sub + Secret Manager |

### 3.3 機敏值處理策略

**本地開發**：
- 透過 `spring.config.import: optional:file:./config/application-secrets.properties`
- 屬性名稱：`gate-jwt-jwk-set-uri`, `gate-anthropic-api-key-primary`

**GCP 環境**：
- 透過 `spring.config.import: sm@` 啟用 Secret Manager
- 使用 `${sm@gate-jwt-jwk-set-uri}` 語法引用 secrets

---

## 4. List 類型配置的特殊處理

### 4.1 List 不會合併，只會覆蓋

**問題**：Spring Boot 對 List 類型的配置是**完全覆蓋**，不是合併。

**範例**：如果 `application.yaml` 定義了 `keys: [a, b]`，而 `application-lab.yaml` 定義了 `keys: [c]`，結果會是 `[c]`，不是 `[a, b, c]`。

**解決方案**：

- **固定結構** (如 `jwk-set-uri`)：放在 `application.yaml`，使用 `${property:default}` 語法
- **動態結構** (如 `keys` 數量不固定)：由各環境 profile 自行定義

```yaml
# application.yaml - 固定結構用預設值
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${gate-jwt-jwk-set-uri:placeholder}

# application.yaml - 動態 List 用空陣列
anthropic:
  api:
    keys: []  # 由各環境 profile 覆蓋

# application-lab.yaml - 各環境定義自己的 keys
anthropic:
  api:
    keys:
      - alias: "primary"
        value: ${gate-anthropic-api-key-primary}
```

---

## 5. Cloud Run 部署相關

### 5.1 Secret 掛載為配置檔

**配置**：
```yaml
# Cloud Run YAML
env:
  - name: spring.profiles.active
    value: "gcp,lab"
  - name: spring.config.additional-location
    value: "optional:file:/config/"
volumeMounts:
  - name: app-config
    mountPath: /config
volumes:
  - name: app-config
    secret:
      secretName: gate-config
      items:
        - key: latest
          path: application-lab.yaml
```

**重點**：
- `spring.config.additional-location` 讓 Spring Boot 搜尋 `/config/` 目錄
- 掛載的檔名要與 profile 匹配 (如 `application-lab.yaml`)

### 5.2 每個 GCP Project 是獨立環境

**設計原則**：
- vibe-lab 專案 → Lab 環境
- vibe-prod 專案 → Prod 環境

**好處**：
- Secret 名稱不需環境後綴 (都叫 `gate-config`, `gate-jwt-jwk-set-uri`)
- 部署腳本更簡單，只需改 `ENV_PROFILE` 變數

### 5.3 sm@ 語法的解析時機

**流程**：
1. `application-gcp.yaml` 的 `spring.config.import: sm@` 啟用 Secret Manager
2. Spring Cloud GCP 將 Secret Manager 作為 PropertySource 加入
3. 後續載入的配置檔可以使用 `${sm@secret-name}` 語法
4. Spring 在實際使用屬性時解析 placeholder

**重點**：`sm@` 語法需要在 `gcp` profile 載入後才能使用。

---

## 6. 常見錯誤排查

### 6.1 AOT 編譯失敗

**症狀**：`./gradlew bootBuildImage` 失敗，錯誤訊息包含 `PlaceholderResolutionException`

**檢查清單**：
- [ ] 所有 `${property}` 都有預設值 `${property:default}`
- [ ] 沒有使用 `spring.autoconfigure.exclude` 排除 GCP 類別
- [ ] `application-dev.yaml` 中的 keys 有預設值

### 6.2 本地開發連不上 RabbitMQ

**檢查清單**：
- [ ] Docker Compose 有啟動 RabbitMQ
- [ ] `application-local.yaml` 有正確的 RabbitMQ 配置
- [ ] Profile 設定為 `local,dev`

### 6.3 GCP 環境讀不到 Secret

**檢查清單**：
- [ ] `application-gcp.yaml` 有 `spring.config.import: sm@`
- [ ] Cloud Run Service Account 有 `roles/secretmanager.secretAccessor` 權限
- [ ] Secret 名稱正確 (不含環境後綴)
- [ ] Secret 已建立且有版本

### 6.4 Cloud Run 啟動失敗

**檢查清單**：
- [ ] `spring.profiles.active` 設為 `gcp,lab` (或 `gcp,prod`)
- [ ] `spring.config.additional-location` 設為 `optional:file:/config/`
- [ ] Secret 掛載路徑正確 (`/config/application-lab.yaml`)
- [ ] 所有必要的 secrets 都已建立

### 6.5 Native Image JSON 序列化失敗

**症狀**：Cloud Run 執行時出現 `InvalidDefinitionException: Cannot construct instance of...`

**原因**：新增的 Model 類別沒有在 `NativeImageHints.java` 註冊反射。

**解決方案**：
```java
// 在 NativeImageHints.java 加入類別
@RegisterReflectionForBinding({
    // ... 現有類別
    YourNewModel.class,
})
```

**檢查清單**：
- [ ] 所有需要 JSON 序列化的 Model 都有註冊
- [ ] Java Record 的巢狀 Record 也要分別註冊
- [ ] Builder 類別也要註冊

---

## 7. CloudEvents 整合

### 7.1 傳輸模式選擇：Binary Mode

**決策背景**：CloudEvents 有兩種傳輸模式，我們選擇 **Binary Mode**。

| 模式 | Body 內容 | Headers/Attributes | 說明 |
|------|----------|-------------------|------|
| Structured | 完整 CloudEvent JSON | 無 CE 屬性 | 整個事件在 body 中 |
| **Binary** | 只有 data | CE 屬性 (`ce-*` 前綴) | metadata 在 headers |

**選擇 Binary Mode 的原因**：

1. **Spring Cloud Function 官方推薦**：[官方範例](https://github.com/spring-cloud/spring-cloud-function/tree/main/spring-cloud-function-samples/function-sample-cloudevent-stream) 使用 Binary Mode
2. **程式碼簡潔**：使用內建的 `CloudEventMessageBuilder`，不需要額外依賴
3. **效率**：不需要額外的 JSON 序列化層，Spring 自動處理 POJO 轉換
4. **GCP Pub/Sub 支援**：Pub/Sub 完整支援 Binary Mode（CloudEvents 屬性存在 message attributes 中）

**注意**：Binary Mode 的 SDK 支援為 "SHOULD"（應該支援），而 Structured Mode 為 "MUST"（必須支援）。
如果消費端是較舊的 SDK 或不支援 Binary Mode，需要改用 Structured Mode。

### 7.2 Binary Mode 的輸出格式

**Pub/Sub Message 結構**：

```
Message Attributes (CloudEvents 屬性):
  ce-specversion: 1.0
  ce-id: trace-id-xxx
  ce-type: io.github.samzhu.gate.usage.v1
  ce-source: /gate/messages
  ce-subject: user@example.com
  ce-time: 2025-01-01T00:00:00Z
  ce-datacontenttype: application/json
  content-type: application/json

Message Body (只有 data):
{
  "trace_id": "xxx",
  "model": "claude-sonnet-4-20250514",
  "input_tokens": 100,
  "output_tokens": 50,
  ...
}
```

### 7.3 Event Data 結構 (`UsageEventData`)

CloudEvent 的 `data` 欄位使用 `UsageEventData` Record，完整定義如下：

**JSON 欄位對照表**：

| 分類 | JSON 欄位 | Java 欄位 | 類型 | 說明 |
|------|----------|-----------|------|------|
| **核心用量** | `model` | `model` | String | 模型名稱 (如 `claude-sonnet-4-20250514`) |
| | `input_tokens` | `inputTokens` | int | 輸入 Token 數量 |
| | `output_tokens` | `outputTokens` | int | 輸出 Token 數量 |
| | `cache_creation_tokens` | `cacheCreationTokens` | int | 快取建立消耗的 Token |
| | `cache_read_tokens` | `cacheReadTokens` | int | 從快取讀取的 Token |
| | `total_tokens` | `totalTokens` | int | 總 Token 數 (input + output) |
| **請求資訊** | `message_id` | `messageId` | String | Anthropic message ID (`msg_xxx`) |
| | `latency_ms` | `latencyMs` | long | 請求延遲 (毫秒) |
| | `stream` | `stream` | boolean | 是否為串流請求 |
| | `stop_reason` | `stopReason` | String | 結束原因 (`end_turn`, `max_tokens` 等) |
| **狀態追蹤** | `status` | `status` | String | 請求狀態 (`success` / `error`) |
| | `error_type` | `errorType` | String | 錯誤類型 (若發生錯誤) |
| **運維資訊** | `key_alias` | `keyAlias` | String | 使用的 API Key 別名 |
| | `trace_id` | `traceId` | String | OpenTelemetry Trace ID |
| | `anthropic_request_id` | `anthropicRequestId` | String | Anthropic request ID (`req_xxx`) |

**完整 JSON 範例**：

```json
{
  "model": "claude-sonnet-4-20250514",
  "input_tokens": 1250,
  "output_tokens": 350,
  "cache_creation_tokens": 0,
  "cache_read_tokens": 500,
  "total_tokens": 1600,
  "message_id": "msg_01XFDUDYJgAACzvnptvVoYEL",
  "latency_ms": 2340,
  "stream": true,
  "stop_reason": "end_turn",
  "status": "success",
  "error_type": null,
  "key_alias": "primary",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "anthropic_request_id": "req_01234567890abcdef"
}
```

**Java Record 定義** (`src/main/java/io/github/samzhu/gate/model/UsageEventData.java`)：

<details>
<summary>點擊展開完整程式碼</summary>

```java
package io.github.samzhu.gate.model;

import com.fasterxml.jackson.annotation.JsonProperty;

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

        public Builder model(String model) { this.model = model; return this; }
        public Builder inputTokens(int inputTokens) { this.inputTokens = inputTokens; return this; }
        public Builder outputTokens(int outputTokens) { this.outputTokens = outputTokens; return this; }
        public Builder cacheCreationTokens(int cacheCreationTokens) { this.cacheCreationTokens = cacheCreationTokens; return this; }
        public Builder cacheReadTokens(int cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; return this; }
        public Builder messageId(String messageId) { this.messageId = messageId; return this; }
        public Builder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        public Builder stopReason(String stopReason) { this.stopReason = stopReason; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder errorType(String errorType) { this.errorType = errorType; return this; }
        public Builder keyAlias(String keyAlias) { this.keyAlias = keyAlias; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder anthropicRequestId(String anthropicRequestId) { this.anthropicRequestId = anthropicRequestId; return this; }

        public UsageEventData build() {
            int totalTokens = inputTokens + outputTokens;
            return new UsageEventData(
                model, inputTokens, outputTokens,
                cacheCreationTokens, cacheReadTokens, totalTokens,
                messageId, latencyMs, stream, stopReason,
                status, errorType, keyAlias, traceId, anthropicRequestId
            );
        }
    }
}
```

</details>

### 7.4 必要的依賴和配置

**依賴**：**無需額外依賴！**

`spring-cloud-stream` 已經包含 `spring-cloud-function-context`，提供 `CloudEventMessageBuilder`。

```gradle
// build.gradle
// CloudEvents Binary Mode：使用 spring-cloud-function-context 內建的 CloudEventMessageBuilder
// - CloudEvents 屬性在 message headers（ce-* 前綴），data 在 body
// - 不需要額外依賴，spring-cloud-stream 已經包含 spring-cloud-function-context
```

**Spring Cloud Stream 配置 (`application.yaml`)**：
```yaml
spring:
  cloud:
    stream:
      bindings:
        usageEvent-out-0:
          destination: llm-gateway-usage
          content-type: application/json  # Binary Mode: body 是 JSON data
```

### 7.5 發送 CloudEvent 的程式碼

```java
// UsageEventPublisher.java
import org.springframework.cloud.function.cloudevent.CloudEventMessageBuilder;
import org.springframework.messaging.Message;

// 使用 CloudEventMessageBuilder 建立 Binary Mode 訊息
// Spring Cloud Stream 會自動序列化 POJO 為 JSON
Message<UsageEventData> message = CloudEventMessageBuilder
    .withData(eventData)  // 直接傳 POJO，不需要 ObjectMapper！
    .setId(eventId)
    .setType("io.github.samzhu.gate.usage.v1")
    .setSource(URI.create("/gate/messages"))
    .setTime(OffsetDateTime.now())
    .setSubject(subject)
    .setDataContentType("application/json")
    .build();

streamBridge.send("usageEvent-out-0", message);
```

**重點**：
- 使用 `CloudEventMessageBuilder`（Spring Cloud Function 內建）
- **直接傳入 POJO**，不需要 `ObjectMapper` 手動序列化
- Spring Cloud Stream 的 `JsonMessageConverter` 自動處理 JSON 轉換
- 產生 **Binary Mode**：CloudEvents 屬性在 message headers，data 在 body

### 7.6 兩種實作方式比較

| 方式 | 依賴 | Data 類型 | ObjectMapper | 額外 Bean | 結果 |
|------|------|----------|--------------|----------|------|
| `CloudEventBuilder` + `CloudEventMessageConverter` | `cloudevents-spring` | `byte[]` | ✅ 需要 | ✅ 需要 | Binary Mode |
| **`CloudEventMessageBuilder`** (Spring Cloud Function) | 無額外依賴 | **任意 POJO** | ❌ 不需要 | ❌ 不需要 | Binary Mode |

**我們選擇 `CloudEventMessageBuilder`** 的原因：
- **更簡潔**：不需要額外依賴、不需要 ObjectMapper、不需要額外 Bean
- **更直覺**：直接傳入 POJO，Spring 自動處理 JSON 序列化
- **官方推薦**：Spring Cloud Function 官方範例使用此方式

**如果需要切換到 Structured Mode**（保證跨語言互通）：
```java
// 需要 cloudevents-spring + cloudevents-json-jackson
byte[] json = EventFormatProvider.getInstance()
    .resolveFormat(JsonFormat.CONTENT_TYPE).serialize(cloudEvent);
streamBridge.send(BINDING_NAME, json);
```

### 7.7 消費端如何解析（Spring Cloud Stream）

消費端同樣使用 Spring Cloud Stream，可以輕鬆接收 Binary Mode 的 CloudEvents。

#### 7.7.1 依賴配置

```gradle
// build.gradle
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-stream'
    implementation 'com.google.cloud:spring-cloud-gcp-pubsub-stream-binder'
}
```

#### 7.7.2 定義 Event Data Record

建議直接複製生產端的 `UsageEventData`，或定義相容的 Record：

```java
package com.example.consumer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UsageEventData(
    String model,
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("cache_creation_tokens") int cacheCreationTokens,
    @JsonProperty("cache_read_tokens") int cacheReadTokens,
    @JsonProperty("total_tokens") int totalTokens,
    @JsonProperty("message_id") String messageId,
    @JsonProperty("latency_ms") long latencyMs,
    boolean stream,
    @JsonProperty("stop_reason") String stopReason,
    String status,
    @JsonProperty("error_type") String errorType,
    @JsonProperty("key_alias") String keyAlias,
    @JsonProperty("trace_id") String traceId,
    @JsonProperty("anthropic_request_id") String anthropicRequestId
) {}
```

#### 7.7.3 方式一：直接接收 POJO（推薦）

最簡單的方式，Spring 自動反序列化 JSON body 為 POJO：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import java.util.function.Consumer;

@Configuration
public class UsageEventConsumer {

    @Bean
    public Consumer<Message<UsageEventData>> usageEvent() {
        return message -> {
            UsageEventData data = message.getPayload();

            // CloudEvents 屬性從 headers 讀取（ce- 前綴）
            String ceType = (String) message.getHeaders().get("ce-type");
            String ceSubject = (String) message.getHeaders().get("ce-subject");
            String ceId = (String) message.getHeaders().get("ce-id");

            System.out.println("Event ID: " + ceId);
            System.out.println("Type: " + ceType);           // io.github.samzhu.gate.usage.v1
            System.out.println("Subject: " + ceSubject);     // user@example.com
            System.out.println("Model: " + data.model());    // claude-sonnet-4-20250514
            System.out.println("Input Tokens: " + data.inputTokens());
            System.out.println("Output Tokens: " + data.outputTokens());
        };
    }
}
```

**application.yaml 配置**：
```yaml
spring:
  cloud:
    stream:
      bindings:
        usageEvent-in-0:
          destination: llm-gateway-usage
          group: my-consumer-group
      gcp:
        pubsub:
          bindings:
            usageEvent-in-0:
              consumer:
                auto-create-resources: false
```

#### 7.7.4 方式二：使用 CloudEventMessageUtils 解析

如果需要完整的 CloudEvent 屬性存取，可使用 Spring Cloud Function 提供的工具類：

```java
import org.springframework.cloud.function.cloudevent.CloudEventMessageUtils;
import org.springframework.messaging.Message;
import java.util.function.Consumer;

@Bean
public Consumer<Message<UsageEventData>> usageEvent() {
    return message -> {
        // 使用 CloudEventMessageUtils 讀取 CloudEvents 屬性
        String id = CloudEventMessageUtils.getId(message);
        String type = CloudEventMessageUtils.getType(message);
        String source = CloudEventMessageUtils.getSource(message);
        String subject = CloudEventMessageUtils.getSubject(message);

        UsageEventData data = message.getPayload();

        System.out.println("CloudEvent ID: " + id);
        System.out.println("Type: " + type);
        System.out.println("Source: " + source);
        System.out.println("Subject: " + subject);
        System.out.println("Model: " + data.model());
    };
}
```

#### 7.7.5 方式三：不需要 CloudEvents 屬性

如果只需要 event data，可以直接接收 POJO（不包裝 Message）：

```java
@Bean
public Consumer<UsageEventData> usageEvent() {
    return data -> {
        System.out.println("Model: " + data.model());
        System.out.println("Total Tokens: " + data.totalTokens());
        // 處理用量資料...
    };
}
```

#### 7.7.6 Native Image 注意事項

如果消費端也使用 GraalVM Native Image，需要註冊反射：

```java
@Configuration
@RegisterReflectionForBinding({UsageEventData.class})
public class NativeImageHints {}
```

### 7.8 非 Spring 消費端參考

<details>
<summary>Python / Go 範例（點擊展開）</summary>

**Python 範例（Pub/Sub）**：
```python
import json

def callback(message):
    # CloudEvents 屬性從 attributes 讀取（ce- 前綴）
    ce_type = message.attributes.get("ce-type")
    ce_subject = message.attributes.get("ce-subject")

    # Data 從 message body 讀取
    data = json.loads(message.data.decode("utf-8"))

    print(f"Type: {ce_type}")           # io.github.samzhu.gate.usage.v1
    print(f"Subject: {ce_subject}")     # user@example.com
    print(f"Model: {data['model']}")    # claude-sonnet-4-20250514

    message.ack()
```

**Go 範例（Pub/Sub）**：
```go
func callback(ctx context.Context, msg *pubsub.Message) {
    ceType := msg.Attributes["ce-type"]
    ceSubject := msg.Attributes["ce-subject"]

    var data map[string]interface{}
    json.Unmarshal(msg.Data, &data)

    fmt.Printf("Type: %s, Subject: %s\n", ceType, ceSubject)
    msg.Ack()
}
```

</details>

---

## 8. 開發建議

### 8.1 本地開發 Checklist

1. 複製 `config/application-secrets.properties.example` 為 `config/application-secrets.properties`
2. 填入實際的 JWT JWKS URL 和 Anthropic API Key
3. 啟動 Docker Compose (RabbitMQ)
4. 執行應用程式，確認 profiles 為 `local,dev`

### 8.2 新增機敏配置的步驟

1. 在 `application-secrets.properties.example` 新增範例
2. 在對應的環境配置檔 (如 `application-lab.yaml`) 新增 `${sm@secret-name}` 引用
3. 如果需要預設值供 AOT 使用，在 `application.yaml` 或 `application-dev.yaml` 新增
4. 更新 `CLOUDRUN_DEPLOY_GUIDE.md` 的 secrets 建立步驟

### 8.3 測試 AOT 編譯

```bash
# 本地測試 AOT 編譯是否會成功
./gradlew processAot

# 完整建置 Docker Image (含 AOT)
./gradlew bootBuildImage --imageName=test:latest
```

---

## 9. 參考資料

**Spring Boot & Cloud**：
- [Spring Boot External Config](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Spring Boot AOT](https://docs.spring.io/spring-boot/reference/packaging/native-image/index.html)
- [Spring Cloud GCP Secret Manager](https://googlecloudplatform.github.io/spring-cloud-gcp/reference/html/index.html#secret-manager)
- [Spring Cloud Stream Reference](https://docs.spring.io/spring-cloud-stream/reference/)
- [Cloud Run Secrets](https://docs.cloud.google.com/run/docs/configuring/services/secrets)

**CloudEvents**：
- [CloudEvents Specification](https://cloudevents.io/)
- [CloudEvents SDK Requirements](https://github.com/cloudevents/spec/blob/main/cloudevents/SDK.md)
- [Spring Cloud Function - CloudEvents Support](https://docs.spring.io/spring-cloud-function/reference/spring-cloud-function/cloud-events.html)
- [Cloud Events and Spring (Official Blog)](https://spring.io/blog/2020/12/23/cloud-events-and-spring-part-2/)
- [Spring Cloud Function CloudEvents Sample](https://github.com/spring-cloud/spring-cloud-function/tree/main/spring-cloud-function-samples/function-sample-cloudevent-stream)
