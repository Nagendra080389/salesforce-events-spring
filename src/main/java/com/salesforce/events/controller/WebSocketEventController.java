package com.salesforce.events.controller;

import com.salesforce.events.model.PlatformEvent;
import com.salesforce.events.service.EventSubscriptionService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import java.time.Instant;

@Controller
@Slf4j
public class WebSocketEventController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private EventSubscriptionService subscriptionService;

    @EventListener
    public void handlePlatformEvent(PlatformEvent event) {
        log.debug("Broadcasting platform event to WebSocket clients: {}", event.getEventId());
        messagingTemplate.convertAndSend("/topic/events", event);
    }

    @MessageMapping("/status")
    @SendTo("/topic/status")
    public ConnectionStatus getStatus() {
        return buildConnectionStatus();
    }

    @Scheduled(fixedRate = 5000)
    public void sendStatusUpdate() {
        messagingTemplate.convertAndSend("/topic/status", buildConnectionStatus());
    }

    private ConnectionStatus buildConnectionStatus() {
        return ConnectionStatus.builder()
                .status(subscriptionService.getConnectionStatus().toString())
                .topic(subscriptionService.getCurrentTopic())
                .eventsReceived(subscriptionService.getTotalEventsReceived())
                .timestamp(Instant.now())
                .build();
    }

    @Data
    @lombok.Builder
    public static class ConnectionStatus {
        private String status;
        private String topic;
        private int eventsReceived;
        private Instant timestamp;
    }
}