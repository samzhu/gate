# GraalVM Native Image 配置指南

## 概述

LLM Gateway 使用 Spring Boot 3.5 + GraalVM Native Image 編譯為原生執行檔，部署於 GCP Cloud Run。
本文件記錄 Native Image 編譯過程中遇到的問題及解決方案。

---

## 已解決的問題

### 1. Joda-Time 時區資源缺失

**錯誤訊息**：
```
java.io.IOException: Resource not found: "org/joda/time/tz/data/ZoneInfoMap"
    at org.joda.time.tz.ZoneInfoProvider.openResource(ZoneInfoProvider.java:225)
    at com.fasterxml.jackson.datatype.joda.cfg.FormatConfig.<clinit>(FormatConfig.java:22)
    at com.fasterxml.jackson.datatype.joda.JodaModule.<init>(JodaModule.java:20)
    at org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration$JsonMapperConfiguration.jackson(...)
```

**根本原因**：

```
依賴鏈：
spring-cloud-stream
  └── spring-cloud-function-context
        └── jackson-datatype-joda
              └── joda-time
```

- Spring Cloud Function 的 `JsonMapperConfiguration` 硬編碼引用 `JodaModule`
- `JodaModule` 初始化時需要載入 Joda-Time 時區資料
- GraalVM Native Image **預設不包含** classpath 上的資源檔案

**為什麼不能排除 joda-time？**

嘗試排除會導致編譯失敗：
```
Caused by: java.lang.NoClassDefFoundError: com/fasterxml/jackson/datatype/joda/JodaModule
```

Spring Cloud Function 在編譯時期就引用了 `JodaModule`，無法動態排除。

**解決方案**：

使用 Spring 的 `RuntimeHintsRegistrar` API 註冊資源：

```java
// NativeImageHints.java
@Configuration
@ImportRuntimeHints(NativeImageHints.JodaTimeResourcesHints.class)
public class NativeImageHints {

    static class JodaTimeResourcesHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // 包含 Joda-Time 時區資料
            hints.resources().registerPattern("org/joda/time/tz/data/*");
        }
    }
}
```

**參考資料**：
- [GraalVM Accessing Resources in Native Image](https://www.graalvm.org/jdk21/reference-manual/native-image/dynamic-features/Resources/)
- [JodaOrg/joda-time#478 - Ensure compatibility with Graal](https://github.com/JodaOrg/joda-time/issues/478)

---

### 2. StreamEvent 反射缺失

**錯誤訊息**：
```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException:
Cannot construct instance of `io.github.samzhu.gate.model.StreamEvent`
```

**根本原因**：

- `StreamEvent` 是 Java Record，用於解析 Claude API 的 SSE 事件
- Jackson 需要透過反射來建構 Record 實例
- GraalVM Native Image **預設不支援反射**

**解決方案**：

使用 `@RegisterReflectionForBinding` 註冊需要反射的類別：

```java
@Configuration
@RegisterReflectionForBinding({
    StreamEvent.class,
    StreamEvent.Message.class,
    StreamEvent.Delta.class,
    StreamEvent.Usage.class,
    StreamEvent.ContentBlock.class
})
public class NativeImageHints {
    // ...
}
```

---

### 3. UsageEventData CloudEvents 序列化

**錯誤訊息**：
```
io.cloudevents.core.data.CloudEventRWException:
The CloudEvent data is an object containing the reference, and there's no way to read it directly as bytes.
```

**根本原因**：

- `UsageEventData` 是 CloudEvents 的 payload
- 需要序列化為 JSON 發送到 Pub/Sub
- GraalVM 需要反射資訊才能序列化

**解決方案**：

同樣使用 `@RegisterReflectionForBinding`：

```java
@RegisterReflectionForBinding({
    UsageEventData.class,
    UsageEventData.Builder.class,
    // ...
})
```

---

## 完整配置

`NativeImageHints.java` 完整程式碼：

```java
package io.github.samzhu.gate.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import io.github.samzhu.gate.model.StreamEvent;
import io.github.samzhu.gate.model.UsageEventData;

@Configuration
@ImportRuntimeHints(NativeImageHints.JodaTimeResourcesHints.class)
@RegisterReflectionForBinding({
    // CloudEvents payload
    UsageEventData.class,
    UsageEventData.Builder.class,
    // SSE Stream parsing
    StreamEvent.class,
    StreamEvent.Message.class,
    StreamEvent.Delta.class,
    StreamEvent.Usage.class,
    StreamEvent.ContentBlock.class
})
public class NativeImageHints {

    static class JodaTimeResourcesHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.resources().registerPattern("org/joda/time/tz/data/*");
        }
    }
}
```

---

## 除錯技巧

### 1. AOT 處理日誌

查看 AOT 處理過程：
```bash
./gradlew processAot --info
```

### 2. 資源包含確認

確認資源是否包含在 Native Image 中：
```bash
native-image -H:Log=registerResource:3 ...
```

### 3. 反射配置檢查

查看生成的反射配置：
```bash
cat build/resources/aot/META-INF/native-image/io.github.samzhu/gate/reflect-config.json
```

---

## 相關文件

- [Spring Boot Native Image Support](https://docs.spring.io/spring-boot/docs/current/reference/html/native-image.html)
- [GraalVM Native Image Reference](https://www.graalvm.org/latest/reference-manual/native-image/)
- [Spring AOT Processing](https://docs.spring.io/spring-framework/reference/core/aot.html)
