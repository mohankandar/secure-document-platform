package com.securedoc.platform.api.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonLoggingConfigurationContractTest {

    @Test
    void encoderProducesParseableJsonWithOperationalMdc() throws Exception {
        LoggerContext context = new LoggerContext();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.addIncludeMdcKeyName("correlationId");
        encoder.addIncludeMdcKeyName("incidentId");
        encoder.addIncludeMdcKeyName("eventName");
        encoder.addIncludeMdcKeyName("reasonCode");
        encoder.start();
        try {
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName("com.securedoc.platform.Test");
            event.setLevel(Level.WARN);
            event.setMessage("Technical processing failure");
            event.setTimeStamp(System.currentTimeMillis());
            event.setMDCPropertyMap(Map.of(
                    "correlationId", "00000000-0000-0000-0000-000000000123",
                    "incidentId", "00000000-0000-0000-0000-000000000456",
                    "eventName", "PROCESSING_INCIDENT_RECORDED",
                    "reasonCode", "AI_INVALID_RESPONSE",
                    "rawPrompt", "must-not-be-emitted"));

            String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
            var node = new ObjectMapper().readTree(json);

            assertEquals("PROCESSING_INCIDENT_RECORDED", node.get("eventName").asText());
            assertEquals("AI_INVALID_RESPONSE", node.get("reasonCode").asText());
            assertTrue(node.has("correlationId"));
            assertTrue(node.has("incidentId"));
            assertFalse(node.has("rawPrompt"));
        } finally {
            encoder.stop();
            context.stop();
        }
    }

    @Test
    void productionConfigurationUsesJsonEncoderAndExplicitMdcAllowList() throws Exception {
        String xml;
        try (var in = getClass().getResourceAsStream("/logback-json.xml")) {
            assertNotNull(in);
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("net.logstash.logback.encoder.LogstashEncoder"));
        assertTrue(xml.contains("<includeMdcKeyName>incidentId</includeMdcKeyName>"));
        assertTrue(xml.contains("<includeMdcKeyName>correlationId</includeMdcKeyName>"));
        assertTrue(xml.contains("<includeMdcKeyName>reasonCode</includeMdcKeyName>"));
        assertFalse(xml.contains("<includeMdcKeyName>rawPrompt</includeMdcKeyName>"));
        assertFalse(xml.contains("<includeMdcKeyName>documentText</includeMdcKeyName>"));
    }

    @Test
    void rollingConfigurationKeepsJsonPrivacyContractAndBoundedRetention() throws Exception {
        String xml;
        try (var in = getClass().getResourceAsStream("/logback-json-rolling.xml")) {
            assertNotNull(in);
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("SizeAndTimeBasedRollingPolicy"));
        assertTrue(xml.contains("SECUREDOC_LOG_MAX_FILE_SIZE"));
        assertTrue(xml.contains("SECUREDOC_LOG_MAX_HISTORY"));
        assertTrue(xml.contains("SECUREDOC_LOG_TOTAL_SIZE_CAP"));
        assertTrue(xml.contains("<includeMdcKeyName>incidentId</includeMdcKeyName>"));
        assertTrue(xml.contains("<includeMdcKeyName>correlationId</includeMdcKeyName>"));
        assertFalse(xml.contains("<includeMdcKeyName>rawPrompt</includeMdcKeyName>"));
        assertFalse(xml.contains("<includeMdcKeyName>documentText</includeMdcKeyName>"));
    }


}
