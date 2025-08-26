package com.salesforce.events.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesforce.events.config.SalesforceConfig;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.util.Fields;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SalesforceAuthService {

    private static final String OAUTH_TOKEN_ENDPOINT = "/services/oauth2/token";
    private static final String USERINFO_ENDPOINT = "/services/oauth2/userinfo";

    @Autowired
    private SalesforceConfig config;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient httpClient;
    private SessionInfo sessionInfo;
    private ScheduledExecutorService tokenRefreshScheduler;

    @PostConstruct
    public void init() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();

        tokenRefreshScheduler = Executors.newSingleThreadScheduledExecutor();

        authenticate();

        // Schedule token refresh every 50 minutes (tokens typically last 1-2 hours)
        tokenRefreshScheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("Refreshing Salesforce access token...");
                authenticate();
            } catch (Exception e) {
                log.error("Failed to refresh token", e);
            }
        }, 50, 50, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void cleanup() {
        if (tokenRefreshScheduler != null) {
            tokenRefreshScheduler.shutdown();
        }
        if (httpClient != null) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                log.error("Error stopping HTTP client", e);
            }
        }
    }

    public void authenticate() throws Exception {
        // Check if we should use direct access token (for backward compatibility)
        if (config.getAuth().getAccessToken() != null && !config.getAuth().getAccessToken().isEmpty()) {
            sessionInfo = new SessionInfo();
            sessionInfo.setSessionId(config.getAuth().getAccessToken());
            sessionInfo.setOrganizationId(config.getAuth().getTenantId());
            sessionInfo.setInstanceUrl(config.getAuth().getLoginUrl());
            log.info("Authenticated using provided access token");
            return;
        }

        // Use OAuth 2.0 Client Credentials flow
        if (config.getAuth().getClientId() != null && config.getAuth().getClientSecret() != null) {
            authenticateWithClientCredentials();
        }
        // Fall back to username/password if configured (JWT or password flow)
        else if (config.getAuth().getUsername() != null && config.getAuth().getPassword() != null) {
            authenticateWithPassword();
        } else {
            throw new IllegalStateException("No authentication credentials provided. " +
                    "Please configure either client_id/client_secret or username/password");
        }
    }

    /**
     * OAuth 2.0 Client Credentials Flow
     * This is the most secure method for server-to-server integration
     */
    private void authenticateWithClientCredentials() throws Exception {
        log.info("Authenticating with OAuth 2.0 Client Credentials flow");

        String tokenUrl = config.getAuth().getLoginUrl() + OAUTH_TOKEN_ENDPOINT;

        Fields fields = new Fields();
        fields.put("grant_type", "client_credentials");
        fields.put("client_id", config.getAuth().getClientId());
        fields.put("client_secret", config.getAuth().getClientSecret());

        Request request = httpClient.POST(tokenUrl);
        request.content(new FormContentProvider(fields));

        ContentResponse response = request.send();

        if (response.getStatus() != 200) {
            throw new Exception("Authentication failed with status: " + response.getStatus() +
                    ", Response: " + response.getContentAsString());
        }

        JsonNode tokenResponse = objectMapper.readTree(response.getContent());

        String accessToken = tokenResponse.get("access_token").asText();
        String instanceUrl = tokenResponse.get("instance_url").asText();
        String id = tokenResponse.get("id").asText();

        // Extract organization ID from the identity URL
        String orgId = extractOrgIdFromIdentityUrl(id);

        sessionInfo = new SessionInfo();
        sessionInfo.setSessionId(accessToken);
        sessionInfo.setInstanceUrl(instanceUrl);
        sessionInfo.setOrganizationId(orgId);
        sessionInfo.setTokenType(tokenResponse.get("token_type").asText());

        log.info("Successfully authenticated with Client Credentials. Instance: {}, Org ID: {}",
                instanceUrl, orgId);
    }

    /**
     * OAuth 2.0 Username-Password Flow (less secure, use only if client credentials not available)
     */
    private void authenticateWithPassword() throws Exception {
        log.info("Authenticating with OAuth 2.0 Username-Password flow");

        String tokenUrl = config.getAuth().getLoginUrl() + OAUTH_TOKEN_ENDPOINT;

        Fields fields = new Fields();
        fields.put("grant_type", "password");
        fields.put("client_id", config.getAuth().getClientId());
        fields.put("client_secret", config.getAuth().getClientSecret());
        fields.put("username", config.getAuth().getUsername());
        fields.put("password", config.getAuth().getPassword());

        Request request = httpClient.POST(tokenUrl);
        request.content(new FormContentProvider(fields));
        request.header("Accept", "application/json");

        ContentResponse response = request.send();

        if (response.getStatus() != 200) {
            throw new Exception("Authentication failed with status: " + response.getStatus() +
                    ", Response: " + response.getContentAsString());
        }

        JsonNode tokenResponse = objectMapper.readTree(response.getContent());

        String accessToken = tokenResponse.get("access_token").asText();
        String instanceUrl = tokenResponse.get("instance_url").asText();
        String id = tokenResponse.get("id").asText();

        // Extract organization ID
        String orgId = extractOrgIdFromIdentityUrl(id);

        sessionInfo = new SessionInfo();
        sessionInfo.setSessionId(accessToken);
        sessionInfo.setInstanceUrl(instanceUrl);
        sessionInfo.setOrganizationId(orgId);
        sessionInfo.setTokenType(tokenResponse.get("token_type").asText());

        log.info("Successfully authenticated with Username-Password flow. Instance: {}, Org ID: {}",
                instanceUrl, orgId);
    }

    /**
     * Extract Organization ID from the identity URL
     * Identity URL format: https://login.salesforce.com/id/00Dxx0000000000/005xx000000000
     */
    private String extractOrgIdFromIdentityUrl(String identityUrl) {
        try {
            String[] parts = identityUrl.split("/");
            if (parts.length >= 2) {
                // The org ID is the second-to-last segment
                return parts[parts.length - 2];
            }
        } catch (Exception e) {
            log.warn("Could not extract org ID from identity URL: {}", identityUrl);
        }
        return null;
    }

    /**
     * Get user info from the OAuth userinfo endpoint (optional, for additional metadata)
     */
    public JsonNode getUserInfo() throws Exception {
        if (sessionInfo == null || sessionInfo.getSessionId() == null) {
            throw new IllegalStateException("Not authenticated");
        }

        String userInfoUrl = sessionInfo.getInstanceUrl() + USERINFO_ENDPOINT;

        Request request = httpClient.newRequest(userInfoUrl);
        request.header("Authorization", sessionInfo.getTokenType() + " " + sessionInfo.getSessionId());
        request.header("Accept", "application/json");

        ContentResponse response = request.send();

        if (response.getStatus() != 200) {
            throw new Exception("Failed to get user info: " + response.getContentAsString());
        }

        return objectMapper.readTree(response.getContent());
    }

    public CallCredentials getCallCredentials() {
        if (sessionInfo == null) {
            throw new IllegalStateException("Not authenticated. Call authenticate() first.");
        }
        return new ApiSessionCredentials(sessionInfo);
    }

    public SessionInfo getSessionInfo() {
        return sessionInfo;
    }

    @Data
    public static class SessionInfo {
        private String sessionId;
        private String organizationId;
        private String instanceUrl;
        private String tokenType = "Bearer";
    }

    public static class ApiSessionCredentials extends CallCredentials {
        private static final Metadata.Key<String> INSTANCE_URL_KEY =
                Metadata.Key.of("instanceUrl", Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> SESSION_TOKEN_KEY =
                Metadata.Key.of("accessToken", Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> TENANT_ID_KEY =
                Metadata.Key.of("tenantId", Metadata.ASCII_STRING_MARSHALLER);

        private final SessionInfo sessionInfo;

        public ApiSessionCredentials(SessionInfo sessionInfo) {
            this.sessionInfo = sessionInfo;
        }

        @Override
        public void applyRequestMetadata(RequestInfo requestInfo, Executor executor,
                                         MetadataApplier metadataApplier) {
            Metadata headers = new Metadata();
            headers.put(INSTANCE_URL_KEY, sessionInfo.getInstanceUrl());
            headers.put(TENANT_ID_KEY, sessionInfo.getOrganizationId());
            headers.put(SESSION_TOKEN_KEY, sessionInfo.getSessionId());
            metadataApplier.apply(headers);
        }

        @Override
        public void thisUsesUnstableApi() {
            // Required by gRPC
        }
    }
}