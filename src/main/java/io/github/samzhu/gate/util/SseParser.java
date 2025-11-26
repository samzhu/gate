package io.github.samzhu.gate.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.gate.model.StreamEvent;

/**
 * SSE 事件解析工具
 */
public class SseParser {

    private static final Logger log = LoggerFactory.getLogger(SseParser.class);

    private final ObjectMapper objectMapper;

    public SseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 SSE data 行內容為 StreamEvent
     *
     * @param dataLine SSE data 行（不含 "data: " 前綴）
     * @return StreamEvent，解析失敗返回 null
     */
    public StreamEvent parse(String dataLine) {
        if (dataLine == null || dataLine.isBlank() || "[DONE]".equals(dataLine.trim())) {
            return null;
        }

        try {
            return objectMapper.readValue(dataLine, StreamEvent.class);
        } catch (Exception e) {
            log.debug("Failed to parse SSE data: {}", dataLine, e);
            return null;
        }
    }

    /**
     * 從 SSE 行提取 data 內容
     *
     * @param line SSE 行
     * @return data 內容，非 data 行返回 null
     */
    public String extractData(String line) {
        if (line != null && line.startsWith("data: ")) {
            return line.substring(6);
        }
        return null;
    }

    /**
     * 從 SSE 行提取 event 類型
     *
     * @param line SSE 行
     * @return event 類型，非 event 行返回 null
     */
    public String extractEventType(String line) {
        if (line != null && line.startsWith("event: ")) {
            return line.substring(7);
        }
        return null;
    }
}
