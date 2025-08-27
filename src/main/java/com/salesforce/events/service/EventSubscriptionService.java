package com.salesforce.events.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.salesforce.eventbus.protobuf.*;
import com.salesforce.events.config.SalesforceConfig;
import com.salesforce.events.model.PlatformEvent;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class EventSubscriptionService {

    @Autowired
    private SalesforceConfig config;

    @Autowired
    private SalesforceAuthService authService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    private ManagedChannel channel;
    private PubSubGrpc.PubSubStub asyncStub;
    private PubSubGrpc.PubSubBlockingStub blockingStub;
    private StreamObserver<FetchRequest> serverStream;

    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicInteger receivedEvents = new AtomicInteger(0);

    private TopicInfo topicInfo;
    @Getter
    private String currentTopic;
    @Getter
    private ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;

    @PostConstruct
    public void init() {
        try {
            setupChannel();
            setupTopic();
            startSubscription();
        } catch (Exception e) {
            log.error("Failed to initialize event subscription", e);
            connectionStatus = ConnectionStatus.ERROR;
        }
    }

    private void setupChannel() {
        String grpcHost = config.getPubsub().getHost();
        int grpcPort = config.getPubsub().getPort();

        log.info("Connecting to Salesforce Pub/Sub API at {}:{}", grpcHost, grpcPort);

        if (config.getPubsub().isUsePlaintext()) {
            channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort)
                    .usePlaintext()
                    .build();
        } else {
            channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort)
                    .build();
        }

        asyncStub = PubSubGrpc.newStub(channel)
                .withCallCredentials(authService.getCallCredentials());
        blockingStub = PubSubGrpc.newBlockingStub(channel)
                .withCallCredentials(authService.getCallCredentials());

        connectionStatus = ConnectionStatus.CONNECTED;
    }

    private void setupTopic() {
        currentTopic = config.getEvent().getTopic();
        log.info("Setting up topic: {}", currentTopic);

        TopicRequest topicRequest = TopicRequest.newBuilder()
                .setTopicName(currentTopic)
                .build();

        topicInfo = blockingStub.getTopic(topicRequest);

        if (!topicInfo.getCanSubscribe()) {
            throw new IllegalStateException("Topic " + currentTopic + " is not available for subscription");
        }

        log.info("Topic setup complete. Can publish: {}, Can subscribe: {}",
                topicInfo.getCanPublish(), topicInfo.getCanSubscribe());
    }

    @Async
    public void startSubscription() {
        if (isActive.compareAndSet(false, true)) {
            log.info("Starting subscription to topic: {}", currentTopic);
            connectionStatus = ConnectionStatus.SUBSCRIBING;

            serverStream = asyncStub.subscribe(new FetchResponseObserver());

            FetchRequest.Builder fetchRequestBuilder = FetchRequest.newBuilder()
                    .setNumRequested(config.getEvent().getBatchSize())
                    .setTopicName(currentTopic)
                    .setReplayPreset(ReplayPreset.valueOf(config.getEvent().getReplayPreset()));

            serverStream.onNext(fetchRequestBuilder.build());
            connectionStatus = ConnectionStatus.SUBSCRIBED;
        }
    }

    public void stopSubscription() {
        if (isActive.compareAndSet(true, false)) {
            log.info("Stopping subscription");
            if (serverStream != null) {
                serverStream.onCompleted();
            }
            connectionStatus = ConnectionStatus.DISCONNECTED;
        }
    }

    private class FetchResponseObserver implements StreamObserver<FetchResponse> {

        @Override
        public void onNext(FetchResponse fetchResponse) {
            log.debug("Received batch of {} events", fetchResponse.getEventsList().size());

            for (ConsumerEvent consumerEvent : fetchResponse.getEventsList()) {
                try {
                    PlatformEvent platformEvent = processEvent(consumerEvent);
                    receivedEvents.incrementAndGet();

                    // Publish event for WebSocket distribution
                    eventPublisher.publishEvent(platformEvent);

                } catch (Exception e) {
                    log.error("Error processing event", e);
                }
            }

            // Request more events if needed
            if (fetchResponse.getPendingNumRequested() == 0) {
                FetchRequest fetchRequest = FetchRequest.newBuilder()
                        .setTopicName(currentTopic)
                        .setNumRequested(config.getEvent().getBatchSize())
                        .build();
                serverStream.onNext(fetchRequest);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            log.error("Error in subscription stream", throwable);
            connectionStatus = ConnectionStatus.ERROR;
            isActive.set(false);

            // Attempt to reconnect after a delay
            try {
                Thread.sleep(5000);
                startSubscription();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onCompleted() {
            log.info("Subscription stream completed");
            connectionStatus = ConnectionStatus.DISCONNECTED;
            isActive.set(false);
        }
    }

    private PlatformEvent processEvent(ConsumerEvent consumerEvent) throws IOException {
        ProducerEvent event = consumerEvent.getEvent();
        String schemaId = event.getSchemaId();

        Schema schema = getSchema(schemaId);
        GenericRecord record = deserialize(schema, event.getPayload());

        Map<String, Object> payload = new HashMap<>();
        record.getSchema().getFields().forEach(field -> {
            Object value = record.get(field.name());
            if (value != null) {
                payload.put(field.name(), value.toString());
            }
        });

        return PlatformEvent.builder()
                .eventId(event.getId())
                .schemaId(schemaId)
                .replayId(consumerEvent.getReplayId().toStringUtf8())
                .timestamp(Instant.now())
                .topic(currentTopic)
                .payload(payload)
                .rawPayload(record.toString())
                .build();
    }

    private Schema getSchema(String schemaId) {
        return schemaCache.computeIfAbsent(schemaId, id -> {
            SchemaRequest request = SchemaRequest.newBuilder()
                    .setSchemaId(id)
                    .build();
            String schemaJson = blockingStub.getSchema(request).getSchemaJson();
            return new Schema.Parser().parse(schemaJson);
        });
    }

    private GenericRecord deserialize(Schema schema, ByteString payload) throws IOException {
        DatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        ByteArrayInputStream in = new ByteArrayInputStream(payload.toByteArray());
        BinaryDecoder decoder = DecoderFactory.get().directBinaryDecoder(in, null);
        return reader.read(null, decoder);
    }

    @PreDestroy
    public void cleanup() {
        stopSubscription();
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error("Error shutting down channel", e);
            }
        }
    }

    public int getTotalEventsReceived() {
        return receivedEvents.get();
    }

    public enum ConnectionStatus {
        DISCONNECTED,
        CONNECTED,
        SUBSCRIBING,
        SUBSCRIBED,
        ERROR
    }
}