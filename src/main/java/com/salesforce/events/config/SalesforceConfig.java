package com.salesforce.events.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "salesforce")
@Data
public class SalesforceConfig {

    private Pubsub pubsub = new Pubsub();
    private Auth auth = new Auth();
    private Event event = new Event();

    @Data
    public static class Pubsub {
        private String host;
        private int port;
        private boolean usePlaintext;
    }

    @Data
    public static class Auth {
        private String loginUrl;

        // OAuth 2.0 Client Credentials (Recommended)
        private String clientId;
        private String clientSecret;

        // Legacy authentication methods
        private String username;
        private String password;
        private String accessToken;
        private String tenantId;
    }

    @Data
    public static class Event {
        private String topic;
        private int batchSize;
        private String replayPreset;
        private boolean processChangeFields;
    }
}