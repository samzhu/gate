# Spring Boot 4.0.0 升級指南

本文件記錄從 Spring Boot 3.5.8 升級至 4.0.0 所需的變更。

## 版本對照

| 項目 | Commit | Spring Boot 版本 |
|------|--------|------------------|
| 4.0.0 基準版本 | `2bc759e67560b56bc62124a8d18ab6a5d860ec4a` | 4.0.0 |
| 3.5.8 相容版本 | 目前工作目錄（未提交） | 3.5.8 |

> 本文件基於上述兩個版本的 `git diff` 產生，供未來升級回 4.0.0 時參考。

---

## 1. build.gradle 依賴變更

### 1.1 Spring Boot 版本

```diff
- id 'org.springframework.boot' version '3.5.8'
+ id 'org.springframework.boot' version '4.0.0'
```

### 1.2 Tracing 依賴

```diff
  // 3.5.8 使用
- implementation 'io.micrometer:micrometer-tracing-bridge-otel'

  // 4.0.0 使用
+ implementation 'org.springframework.boot:spring-boot-micrometer-tracing'
```

### 1.3 Validation Starter

Spring Boot 4.0.0 已內建 validation，無需額外引入：

```diff
  // 3.5.8 需要
- implementation 'org.springframework.boot:spring-boot-starter-validation'

  // 4.0.0 不需要（已內建）
```

### 1.4 測試依賴

```diff
  // 3.5.8 使用
- testImplementation 'org.springframework.boot:spring-boot-starter-test'

  // 4.0.0 使用（更細粒度的測試模組）
+ testImplementation 'org.springframework.boot:spring-boot-micrometer-tracing-test'
+ testImplementation 'org.springframework.boot:spring-boot-starter-actuator-test'
+ testImplementation 'org.springframework.boot:spring-boot-starter-opentelemetry-test'
+ testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
```

### 1.5 Testcontainers 依賴

```diff
  // 3.5.8 使用
- testImplementation 'org.testcontainers:gcloud'
- testImplementation 'org.testcontainers:junit-jupiter'

  // 4.0.0 使用（新命名）
+ testImplementation 'org.testcontainers:testcontainers-grafana'
+ testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
```

---

## 2. Jackson 套件升級 (2.x → 3.x)

Spring Boot 4.0.0 使用 Jackson 3.x，套件名稱從 `com.fasterxml` 改為 `tools.jackson`。

### 2.1 Import 變更

對所有使用 Jackson 的檔案執行以下變更：

```diff
  // 3.5.8 使用 (Jackson 2.x)
- import com.fasterxml.jackson.databind.JsonNode;
- import com.fasterxml.jackson.databind.ObjectMapper;

  // 4.0.0 使用 (Jackson 3.x)
+ import tools.jackson.databind.JsonNode;
+ import tools.jackson.databind.ObjectMapper;
```

### 2.2 受影響檔案清單

| 檔案路徑 | 變更內容 |
|----------|----------|
| `src/main/java/io/github/samzhu/gate/config/GatewayConfig.java` | JsonNode, ObjectMapper import |
| `src/main/java/io/github/samzhu/gate/handler/NonStreamingProxyHandler.java` | JsonNode, ObjectMapper import |
| `src/main/java/io/github/samzhu/gate/handler/StreamingProxyHandler.java` | ObjectMapper import |
| `src/main/java/io/github/samzhu/gate/service/UsageEventPublisher.java` | ObjectMapper import |
| `src/main/java/io/github/samzhu/gate/util/SseParser.java` | ObjectMapper import |

### 2.3 API 方法變更

Jackson 3.x 的 `JsonNode` API 有所變更：

```diff
  // 3.5.8 使用 (Jackson 2.x)
- root.get("model").asText()

  // 4.0.0 使用 (Jackson 3.x)
+ root.get("model").stringValue()
```

**受影響檔案**: `src/main/java/io/github/samzhu/gate/handler/NonStreamingProxyHandler.java`

| 目前行號 | 3.5.8 程式碼 | 4.0.0 程式碼 |
|----------|--------------|--------------|
| 166 | `root.get("model").asText()` | `root.get("model").stringValue()` |
| 171 | `root.get("id").asText()` | `root.get("id").stringValue()` |
| 176 | `root.get("stop_reason").asText()` | `root.get("stop_reason").stringValue()` |
| 201 | `error.get("type").asText()` | `error.get("type").stringValue()` |

---

## 3. Spring Boot Actuator Health API 變更

Spring Boot 4.0.0 將 Health Indicator 套件路徑變更：

```diff
  // 3.5.8 使用
- import org.springframework.boot.actuate.health.Health;
- import org.springframework.boot.actuate.health.HealthIndicator;

  // 4.0.0 使用
+ import org.springframework.boot.health.contributor.Health;
+ import org.springframework.boot.health.contributor.HealthIndicator;
```

**受影響檔案**: `src/main/java/io/github/samzhu/gate/health/ApiKeyHealthIndicator.java`

---

## 4. Testcontainers 設定

啟用 Grafana LGTM Stack 整合（4.0.0 支援）：

**檔案**: `src/test/java/io/github/samzhu/gate/TestcontainersConfiguration.java`

```diff
  // 3.5.8（已註解，不支援）
- // import org.testcontainers.grafana.LgtmStackContainer;

  // 4.0.0（啟用）
+ import org.testcontainers.grafana.LgtmStackContainer;

  // 3.5.8（已註解）
- // @Bean
- // @ServiceConnection
- // LgtmStackContainer grafanaLgtmContainer() {
- //     return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
- // }

  // 4.0.0（啟用）
+ @Bean
+ @ServiceConnection
+ LgtmStackContainer grafanaLgtmContainer() {
+     return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
+ }
```

---

## 5. 完整 Diff 記錄

以下是從 3.5.8（目前）升級到 4.0.0（commit `2bc759e`）的完整差異：

### 5.1 build.gradle

```diff
 dependencies {
+    implementation 'org.springframework.boot:spring-boot-micrometer-tracing'
     implementation 'org.springframework.boot:spring-boot-starter-actuator'
+    // implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'
     implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
     implementation 'org.springframework.boot:spring-boot-starter-json'
-    implementation 'org.springframework.boot:spring-boot-starter-validation'
-    implementation 'io.micrometer:micrometer-tracing-bridge-otel'
     // ... 其他依賴 ...

-    testImplementation 'org.springframework.boot:spring-boot-starter-test'
+    testImplementation 'org.springframework.boot:spring-boot-micrometer-tracing-test'
+    testImplementation 'org.springframework.boot:spring-boot-starter-actuator-test'
+    testImplementation 'org.springframework.boot:spring-boot-starter-opentelemetry-test'
+    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
     testImplementation 'org.springframework.boot:spring-boot-testcontainers'
     testImplementation 'org.springframework.cloud:spring-cloud-stream-test-binder'
-    testImplementation 'org.testcontainers:gcloud'
-    testImplementation 'org.testcontainers:junit-jupiter'
+    testImplementation 'org.testcontainers:testcontainers-grafana'
+    testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
     testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
 }
```

---

## 6. 升級檢查清單

### 6.1 build.gradle 變更
- [ ] 更新 Spring Boot 版本：`3.5.8` → `4.0.0`
- [ ] 更新 Spring Cloud 版本（檢查 4.0.0 相容版本）
- [ ] 更新 Spring Cloud GCP 版本（檢查 4.0.0 相容版本）
- [ ] 替換 Tracing 依賴：`micrometer-tracing-bridge-otel` → `spring-boot-micrometer-tracing`
- [ ] 移除 `spring-boot-starter-validation`
- [ ] 更新測試依賴為 4.0.0 細粒度模組
- [ ] 更新 Testcontainers 依賴命名

### 6.2 Java 程式碼變更
- [ ] 全域替換 Jackson import：`com.fasterxml.jackson.databind` → `tools.jackson.databind`
- [ ] 更新 Jackson API：`asText()` → `stringValue()`
- [ ] 更新 Health Indicator import：`actuate.health` → `health.contributor`
- [ ] 啟用 `TestcontainersConfiguration.java` 中的 Grafana LGTM

### 6.3 驗證
- [ ] 執行 `./gradlew clean compileJava` 驗證編譯
- [ ] 執行 `./gradlew test` 驗證測試
- [ ] 執行 `./gradlew bootRun` 驗證本地運行
- [ ] 執行 `./gradlew bootBuildImage` 驗證 Docker 映像建置

---

## 7. 快速升級指令參考

使用 `sed` 批次替換（macOS）：

```bash
# 1. Jackson import 替換
find src -name "*.java" -exec sed -i '' 's/com\.fasterxml\.jackson\.databind/tools.jackson.databind/g' {} \;

# 2. Jackson API 替換
find src -name "*.java" -exec sed -i '' 's/\.asText()/.stringValue()/g' {} \;

# 3. Health Indicator import 替換
find src -name "*.java" -exec sed -i '' 's/org\.springframework\.boot\.actuate\.health/org.springframework.boot.health.contributor/g' {} \;
```

Linux 使用者請移除 `sed -i` 後的 `''`：

```bash
find src -name "*.java" -exec sed -i 's/com\.fasterxml\.jackson\.databind/tools.jackson.databind/g' {} \;
```

---

## 8. 參考資源

- [Spring Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
- [Jackson 3.x Migration Guide](https://github.com/FasterXML/jackson/wiki/Jackson-3.x-Migration-Guide)
- [Spring Cloud 2025.x Release Notes](https://github.com/spring-cloud/spring-cloud-release/wiki)

---

## 9. 版本歷程

| 日期 | 說明 |
|------|------|
| 2025-12-04 | 建立文件，記錄 4.0.0 → 3.5.8 降版差異 |
