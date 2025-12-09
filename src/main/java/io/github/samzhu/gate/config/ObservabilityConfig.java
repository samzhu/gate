package io.github.samzhu.gate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * 可觀測性配置
 *
 * <p>配置 Tracing Context 在非同步任務間的傳播，確保追蹤資訊的完整性：
 * <ul>
 *   <li>{@link ContextPropagatingTaskDecorator} - 非同步任務的 Context 傳播</li>
 *   <li>{@code spring.observations.annotations.enabled} - 啟用 {@code @Observed} 註解支援</li>
 * </ul>
 *
 * <p>使用場景：
 * <ul>
 *   <li>{@code @Async} 方法 - 需要 TaskDecorator 傳播 TraceContext</li>
 *   <li>{@code @Observed} 方法 - 自動建立 Span 追蹤方法執行</li>
 * </ul>
 *
 * <p>此配置預先啟用，確保專案一致性，避免日後需要時遺漏配置。
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/actuator/tracing.html">Spring Boot Tracing</a>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Context 傳播 TaskDecorator
     *
     * <p>用於 {@code @Async} 方法，確保 TraceContext 從呼叫端傳播到非同步執行緒。
     * Spring Boot 的 {@code TaskExecutorBuilder} 會自動套用此 decorator。
     *
     * @return TaskDecorator 實例
     */
    @Bean
    public TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    /**
     * 自訂 Context 傳播 TaskDecorator
     *
     * <p>使用 Micrometer Context Propagation 機制，在任務執行前後
     * 正確設定與清理 ThreadLocal 中的 TraceContext。
     */
    static class ContextPropagatingTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            // 捕獲當前執行緒的 Context（包含 TraceContext）
            var contextSnapshot = ContextSnapshotFactory.builder().build().captureAll();
            return () -> {
                // 在新執行緒中設定 Context
                try (var scope = contextSnapshot.setThreadLocals()) {
                    runnable.run();
                }
            };
        }
    }
}
