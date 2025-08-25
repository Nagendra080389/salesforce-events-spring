package com.salesforce.events.controller;

import com.salesforce.events.service.EventSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
public class WebController {

    @Autowired
    private EventSubscriptionService subscriptionService;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("topic", subscriptionService.getCurrentTopic());
        model.addAttribute("status", subscriptionService.getConnectionStatus());
        model.addAttribute("eventsReceived", subscriptionService.getTotalEventsReceived());
        return "index";
    }

    @GetMapping("/api/status")
    @ResponseBody
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connectionStatus", subscriptionService.getConnectionStatus().toString());
        status.put("topic", subscriptionService.getCurrentTopic());
        status.put("totalEvents", subscriptionService.getTotalEventsReceived());
        return status;
    }

    @PostMapping("/api/reconnect")
    @ResponseBody
    public Map<String, String> reconnect() {
        subscriptionService.stopSubscription();
        subscriptionService.startSubscription();

        Map<String, String> response = new HashMap<>();
        response.put("status", "Reconnecting...");
        return response;
    }
}