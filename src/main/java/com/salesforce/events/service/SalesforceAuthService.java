package com.salesforce.events.service;

import com.salesforce.events.config.SalesforceConfig;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.annotation.PostConstruct;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class SalesforceAuthService {

    private static final String SERVICES_SOAP_PARTNER_ENDPOINT = "/services/Soap/u/64.0/";

    @Autowired
    private SalesforceConfig config;

    private HttpClient httpClient;
    private SessionInfo sessionInfo;

    @PostConstruct
    public void init() throws Exception {
        httpClient = new HttpClient();
        httpClient.start();
        authenticate();
    }

    public void authenticate() throws Exception {
        if (config.getAuth().getAccessToken() != null && !config.getAuth().getAccessToken().isEmpty()) {
            sessionInfo = new SessionInfo();
            sessionInfo.setSessionId(config.getAuth().getAccessToken());
            sessionInfo.setOrganizationId(config.getAuth().getTenantId());
            sessionInfo.setInstanceUrl(config.getAuth().getLoginUrl());
            log.info("Authenticated using access token");
        } else if (config.getAuth().getUsername() != null && config.getAuth().getPassword() != null) {
            login();
        } else {
            throw new IllegalStateException("No authentication credentials provided");
        }
    }

    private void login() throws Exception {
        String endpoint = config.getAuth().getLoginUrl() + SERVICES_SOAP_PARTNER_ENDPOINT;
        Request post = httpClient.POST(endpoint);

        String soapBody = buildLoginSoapRequest(config.getAuth().getUsername(), config.getAuth().getPassword());
        post.content(new StringContentProvider("text/xml", soapBody, StandardCharsets.UTF_8));
        post.header("SOAPAction", "''");
        post.header("PrettyPrint", "Yes");

        ContentResponse response = post.send();
        LoginResponseParser parser = parseLoginResponse(response);

        if (parser.sessionId == null || parser.serverUrl == null) {
            throw new Exception("Unable to login: " + parser.faultstring);
        }

        sessionInfo = new SessionInfo();
        sessionInfo.setSessionId(parser.sessionId);
        sessionInfo.setOrganizationId(parser.organizationId);
        sessionInfo.setInstanceUrl(extractInstanceUrl(parser.serverUrl));

        log.info("Successfully authenticated with Salesforce. Org ID: {}", sessionInfo.getOrganizationId());
    }

    private String buildLoginSoapRequest(String username, String password) {
        return "<soapenv:Envelope xmlns:soapenv='http://schemas.xmlsoap.org/soap/envelope/' " +
                "xmlns:urn='urn:partner.soap.sforce.com'>" +
                "<soapenv:Body>" +
                "<urn:login>" +
                "<urn:username>" + username + "</urn:username>" +
                "<urn:password>" + password + "</urn:password>" +
                "</urn:login>" +
                "</soapenv:Body>" +
                "</soapenv:Envelope>";
    }

    private String extractInstanceUrl(String serverUrl) throws Exception {
        java.net.URL url = new java.net.URL(serverUrl);
        String result = url.getProtocol() + "://" + url.getHost();
        if (url.getPort() > -1) {
            result += ":" + url.getPort();
        }
        return result;
    }

    private LoginResponseParser parseLoginResponse(ContentResponse response) throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        spf.setNamespaceAware(true);

        SAXParser saxParser = spf.newSAXParser();
        LoginResponseParser parser = new LoginResponseParser();
        saxParser.parse(new ByteArrayInputStream(response.getContent()), parser);

        return parser;
    }

    public CallCredentials getCallCredentials() {
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
    }

    private static class LoginResponseParser extends DefaultHandler {
        String buffer;
        String faultstring;
        boolean reading = false;
        String serverUrl;
        String sessionId;
        String organizationId;

        @Override
        public void characters(char[] ch, int start, int length) {
            if (reading) {
                buffer = new String(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            reading = false;
            switch (localName) {
                case "organizationId":
                    organizationId = buffer;
                    break;
                case "sessionId":
                    sessionId = buffer;
                    break;
                case "serverUrl":
                    serverUrl = buffer;
                    break;
                case "faultstring":
                    faultstring = buffer;
                    break;
            }
            buffer = null;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "sessionId":
                case "serverUrl":
                case "faultstring":
                case "organizationId":
                    reading = true;
                    break;
            }
        }
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