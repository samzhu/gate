package io.github.samzhu.gate.service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.function.cloudevent.CloudEventMessageBuilder;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import io.github.samzhu.gate.model.UsageEventData;

/**
 * 用量事件發送服務
 *
 * <p>使用 CloudEvents v1.0 規範格式，透過 Spring Cloud Stream 發送用量事件到訊息佇列：
 * <ul>
 *   <li>本地開發：RabbitMQ</li>
 *   <li>生產環境：GCP Pub/Sub</li>
 * </ul>
 *
 * <p>CloudEvents 屬性：
 * <ul>
 *   <li>{@code type}: io.github.samzhu.gate.usage.v1</li>
 *   <li>{@code source}: /gate/messages</li>
 *   <li>{@code subject}: JWT sub claim（用戶識別）</li>
 *   <li>{@code id}: OpenTelemetry Trace ID（用於端到端追蹤）</li>
 * </ul>
 *
 * @see UsageEventData
 * @see <a href="https://cloudevents.io/">CloudEvents Specification</a>
 */
@Service
public class UsageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UsageEventPublisher.class);

    private static final String BINDING_NAME = "usageEvent-out-0";
    private static final String EVENT_TYPE = "io.github.samzhu.gate.usage.v1";
    private static final URI EVENT_SOURCE = URI.create("/gate/messages");

    private final StreamBridge streamBridge;

    public UsageEventPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
        log.info("UsageEventPublisher initialized: bindingName={}, streamBridge={}",
            BINDING_NAME, streamBridge.getClass().getSimpleName());
    }

    /**
     * 發送用量事件到訊息佇列
     *
     * <p>使用 Spring Cloud Function 的 {@link CloudEventMessageBuilder} 建構
     * CloudEvents <b>Binary Mode</b> 訊息：
     * <ul>
     *   <li>CloudEvents 屬性 → message headers（{@code ce-} 前綴）</li>
     *   <li>Event data → message body（JSON 格式，由 Spring 自動序列化）</li>
     * </ul>
     *
     * <p>Pub/Sub Message 結構：
     * <pre>{@code
     * Attributes:
     *   ce-specversion: 1.0
     *   ce-id: trace-id-xxx
     *   ce-type: io.github.samzhu.gate.usage.v1
     *   ce-source: /gate/messages
     *   ce-subject: user@example.com
     *   ce-time: 2025-01-01T00:00:00Z
     *   ce-datacontenttype: application/json
     *
     * Body:
     *   { "user_id": "...", "event_time": "...", "trace_id": "...", "model": "...", ... }
     * }</pre>
     *
     * <p>注意：{@code user_id} 和 {@code event_time} 同時存在於 CloudEvents headers
     * （{@code ce-subject}, {@code ce-time}）和 data payload 中，這是有意義的冗餘：
     * <ul>
     *   <li>CloudEvents headers：用於 message broker 層級的 subscription filtering</li>
     *   <li>Data payload：讓消費端可直接反序列化使用，不需從 headers 組裝資料</li>
     * </ul>
     *
     * @param eventData 用量事件資料（包含 userId 和 eventTime）
     */
    public void publish(UsageEventData eventData) {
        try {
            // 使用 traceId 作為 CloudEvent ID，若無則產生 UUID
            String eventId = eventData.traceId() != null ? eventData.traceId() : UUID.randomUUID().toString();
            String subject = eventData.userId();
            OffsetDateTime eventTime = eventData.eventTime() != null
                ? OffsetDateTime.ofInstant(eventData.eventTime(), java.time.ZoneOffset.UTC)
                : OffsetDateTime.now();

            // 使用 CloudEventMessageBuilder 建立 Binary Mode 訊息
            // Spring Cloud Stream 會自動序列化 POJO 為 JSON
            Message<UsageEventData> message = CloudEventMessageBuilder
                .withData(eventData)
                .setId(eventId)
                .setType(EVENT_TYPE)
                .setSource(EVENT_SOURCE)
                .setTime(eventTime)
                .setSubject(subject)
                .setDataContentType("application/json")
                .build();

            boolean sent = streamBridge.send(BINDING_NAME, message);

            if (sent) {
                log.debug("Usage event published: traceId={}, subject={}, model={}, inputTokens={}, outputTokens={}",
                    eventId, subject, eventData.model(), eventData.inputTokens(), eventData.outputTokens());
            } else {
                log.warn("Failed to publish usage event: traceId={}", eventId);
            }
        } catch (Exception e) {
            // Pub/Sub 發送失敗不應影響主要代理功能
            // 記錄詳細錯誤資訊以便排查 Binder 問題
            String rootCause = e.getCause() != null ? e.getCause().getClass().getSimpleName() : "N/A";
            log.error("Error publishing usage event: type={}, message={}, rootCause={}, binding={}",
                e.getClass().getSimpleName(), e.getMessage(), rootCause, BINDING_NAME, e);
        }
    }
}
