package io.github.samzhu.gate.config;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.gate.model.UsageEventData;

/**
 * GraalVM Native Image 反射提示配置
 *
 * <p>註冊需要在 Native Image 中使用反射的類別。
 * 這些類別通常是需要被 Jackson 序列化/反序列化的 POJO 或 Record。
 *
 * @see <a href="https://docs.spring.io/spring-boot/docs/current/reference/html/native-image.html">Spring Boot Native Image Support</a>
 */
@Configuration
@RegisterReflectionForBinding({
    UsageEventData.class,
    UsageEventData.Builder.class
})
public class NativeImageHints {
    // 此類別僅用於註冊反射提示，不需要任何方法
}
