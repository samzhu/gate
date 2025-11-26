package io.github.samzhu.gate.service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.data.PojoCloudEventData;
import io.github.samzhu.gate.model.UsageEventData;

/**
 * 用量事件發送服務
 * 使用 CloudEvents 格式發送到 GCP Pub/Sub
 */
@Service
public class UsageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UsageEventPublisher.class);

    private static final String BINDING_NAME = "usageEvent-out-0";
    private static final String EVENT_TYPE = "io.github.samzhu.gate.usage.v1";
    private static final URI EVENT_SOURCE = URI.create("/gate/messages");

    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;

    public UsageEventPublisher(StreamBridge streamBridge, ObjectMapper objectMapper) {
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
    }

    /**
     * 發送用量事件到 Pub/Sub
     *
     * @param eventData 用量事件資料
     * @param requestId 請求 ID
     * @param subject   用戶識別碼 (JWT sub claim)
     */
    public void publish(UsageEventData eventData, String requestId, String subject) {
        try {
            String eventId = requestId != null ? requestId : UUID.randomUUID().toString();

            CloudEvent cloudEvent = CloudEventBuilder.v1()
                .withId(eventId)
                .withType(EVENT_TYPE)
                .withSource(EVENT_SOURCE)
                .withTime(OffsetDateTime.now())
                .withSubject(subject)
                .withDataContentType("application/json")
                .withData(PojoCloudEventData.wrap(eventData, data -> {
                    try {
                        return objectMapper.writeValueAsBytes(data);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to serialize event data", e);
                    }
                }))
                .build();

            boolean sent = streamBridge.send(BINDING_NAME, cloudEvent);

            if (sent) {
                log.debug("Usage event published: requestId={}, subject={}, model={}, inputTokens={}, outputTokens={}",
                    eventId, subject, eventData.model(), eventData.inputTokens(), eventData.outputTokens());
            } else {
                log.warn("Failed to publish usage event: requestId={}", eventId);
            }
        } catch (Exception e) {
            // Pub/Sub 發送失敗不應影響主要代理功能
            log.error("Error publishing usage event: {}", e.getMessage(), e);
        }
    }
}
