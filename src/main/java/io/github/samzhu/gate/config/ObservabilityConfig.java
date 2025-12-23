package io.github.samzhu.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * 可觀測性配置
 *
 * <p>配置 Tracing Context 傳播，確保追蹤資訊在非同步任務間的完整性。
 *
 * <p>Spring Boot 3.2+ 行為：
 * <ul>
 *   <li>任何 {@link TaskDecorator} bean 會自動套用到 auto-configured executor</li>
 *   <li>Virtual Threads 啟用時，executor 是 {@code SimpleAsyncTaskExecutor}</li>
 *   <li>{@code @Observed} 註解支援由 {@code management.observations.annotations.enabled=true} 啟用</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/actuator/tracing.html">Spring Boot Tracing</a>
 * @see <a href="https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.2-Release-Notes">Spring Boot 3.2 Release Notes</a>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Context 傳播 TaskDecorator
     *
     * <p>Spring Boot 3.2+ 會自動將此 bean 套用到 auto-configured executor，
     * 確保 TraceContext 在 Virtual Threads 間正確傳播。
     *
     * @return ContextPropagatingTaskDecorator 實例
     */
    @Bean
    public TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
