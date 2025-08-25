package com.salesforce.events.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlatformEvent {

    private String eventId;
    private String schemaId;
    private String replayId;
    private Instant timestamp;
    private String topic;
    private Map<String, Object> payload;
    private String rawPayload;
    private EventMetadata metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventMetadata {
        private String createdById;
        private Instant createdDate;
        private String recordId;
        private String changeType;
    }
}