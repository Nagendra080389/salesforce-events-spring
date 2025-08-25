package com.salesforce.events;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SalesforceEventsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalesforceEventsApplication.class, args);
    }
}