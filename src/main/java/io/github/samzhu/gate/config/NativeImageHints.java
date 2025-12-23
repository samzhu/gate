package io.github.samzhu.gate.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import io.github.samzhu.gate.model.StreamEvent;
import io.github.samzhu.gate.model.UsageEventData;

/**
 * GraalVM Native Image 反射與資源提示配置
 *
 * <p>註冊需要在 Native Image 中使用反射的類別，以及需要包含的資源。
 *
 * <p>反射類別（Jackson 序列化/反序列化）：
 * <ul>
 *   <li>{@link UsageEventData} - API 用量 CloudEvents payload</li>
 *   <li>{@link StreamEvent} - SSE 事件解析</li>
 * </ul>
 *
 * <p>資源配置：
 * <ul>
 *   <li>{@code org/joda/time/tz/data/.*} - Joda-Time 時區資料（Spring Cloud Function 依賴）</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-boot/docs/current/reference/html/native-image.html">Spring Boot Native Image Support</a>
 * @see <a href="https://github.com/JodaOrg/joda-time/issues/478">Joda-Time GraalVM Compatibility</a>
 */
@Configuration
@ImportRuntimeHints(NativeImageHints.JodaTimeResourcesHints.class)
@RegisterReflectionForBinding({
    // CloudEvents payload - API usage
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

    /**
     * Joda-Time 資源提示註冊器
     *
     * <p>Spring Cloud Function 依賴 jackson-datatype-joda，
     * 該模組初始化時需要 Joda-Time 的時區資料。
     * GraalVM Native Image 預設不包含這些資源，需要明確註冊。
     */
    static class JodaTimeResourcesHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // 包含 Joda-Time 時區資料
            hints.resources().registerPattern("org/joda/time/tz/data/*");
        }
    }
}
