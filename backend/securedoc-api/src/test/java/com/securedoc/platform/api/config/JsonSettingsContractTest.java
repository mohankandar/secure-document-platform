package com.securedoc.platform.api.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 0056: the app's JSON settings ({@code spring.jackson} in application.yml) write and read exactly what Spring Boot 3
 * with Jackson 2 did. Every expected value here was printed by the app's mapper before the move (Spring Boot 3.3.2,
 * Jackson 2.18.9), not worked out.
 */
class JsonSettingsContractTest {

    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";
    private static ConfigurableApplicationContext context;
    private static ObjectMapper mapper;

    public enum Level {
        LOW, HIGH;

        @Override
        public String toString() {
            return "level " + name().toLowerCase();
        }
    }

    public record Sample(String zeta, int alpha, Instant at, Instant whole, LocalDate day, Duration waited,
                         OffsetDateTime offset, Level level, Map<String, Level> byName, Map<Level, Integer> byLevel,
                         Optional<String> some, Optional<String> none, String absent, List<String> list, BigDecimal money,
                         BigDecimal big, double number, float small, long count, byte[] bytes, UUID id, JsonNode raw,
                         String text, Object[] array, boolean isOpen, Boolean flag) {
        public String extra() {
            return "not a property";
        }

        public String getDerived() {
            return "derived";
        }
    }

    public record In(String name, int count, boolean flag, Level level, List<String> tags, Map<String, String> values,
                     LocalDate day, Instant at, JsonNode raw, Double ratio) {
    }

    @BeforeAll
    static void start() {
        String before = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        try {
            context = new SpringApplicationBuilder(JacksonAutoConfiguration.class).web(WebApplicationType.NONE).run();
        } finally {
            if (before == null) System.clearProperty(LOGGING_SYSTEM); else System.setProperty(LOGGING_SYSTEM, before);
        }
        mapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @Test
    void whatIsWrittenIsByteForByteAsBefore() {
        Map<String, Level> byName = new LinkedHashMap<>();
        byName.put("b", Level.HIGH);
        byName.put("a", null);
        Map<Level, Integer> byLevel = new LinkedHashMap<>();
        byLevel.put(Level.HIGH, 2);
        byLevel.put(Level.LOW, 1);
        Sample sample = new Sample("z", 1, Instant.parse("2026-11-24T10:00:00.123456789Z"),
                Instant.parse("2026-11-24T10:00:00Z"), LocalDate.of(2026, 1, 2), Duration.ofSeconds(90),
                OffsetDateTime.parse("2026-11-24T10:00:00+02:00"), Level.HIGH, byName, byLevel, Optional.of("x"),
                Optional.empty(), null, List.of("b", "a"), new BigDecimal("1.50"), new BigDecimal("1E+3"), 0.1, 1.0f,
                12345678901L, new byte[]{1, 2, 3}, UUID.fromString("11111111-2222-4333-8444-555555555555"),
                mapper.readTree("{\"z\":1.50,\"a\":[1e5,null,true],\"s\":\"a/b\"}"),
                "a/b \u00e9 \ud83d\ude42 \u2028 \"q\" <x> \u0001", new Object[]{1, "x"}, true, null);
        assertEquals("{\"zeta\":\"z\",\"alpha\":1,\"at\":\"2026-11-24T10:00:00.123456789Z\","
                + "\"whole\":\"2026-11-24T10:00:00Z\",\"day\":\"2026-01-02\",\"waited\":\"PT1M30S\","
                + "\"offset\":\"2026-11-24T10:00:00+02:00\",\"level\":\"HIGH\",\"byName\":{\"b\":\"HIGH\",\"a\":null},"
                + "\"byLevel\":{\"HIGH\":2,\"LOW\":1},\"some\":\"x\",\"none\":null,\"absent\":null,\"list\":[\"b\",\"a\"],"
                + "\"money\":1.50,\"big\":1E+3,\"number\":0.1,\"small\":1.0,\"count\":12345678901,\"bytes\":\"AQID\","
                + "\"id\":\"11111111-2222-4333-8444-555555555555\",\"raw\":{\"z\":1.5,\"a\":[100000.0,null,true],\"s\":\"a/b\"},"
                + "\"text\":\"a/b \u00e9 \\uD83D\\uDE42 \u2028 \\\"q\\\" <x> \\u0001\",\"array\":[1,\"x\"],\"isOpen\":true,"
                + "\"flag\":null,\"derived\":\"derived\"}", new String(mapper.writeValueAsBytes(sample), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "{}                                          | name=null, count=0, flag=false, level=null",
            "{\"name\":5,\"count\":null,\"flag\":null}   | name=5, count=0, flag=false, level=null",
            "{\"unknown\":1,\"count\":\"7\",\"flag\":\"true\"} | name=null, count=7, flag=true, level=null",
            "{\"count\":1.9}                             | name=null, count=1, flag=false, level=null",
            "{\"count\":\"\"}                            | name=null, count=0, flag=false, level=null",
            "{\"level\":\"HIGH\"}                        | name=null, count=0, flag=false, level=level high",
            "{\"level\":1}                               | name=null, count=0, flag=false, level=level high",
            "{\"name\":\"a\",\"name\":\"b\"}             | name=b, count=0, flag=false, level=null",
            "{} x                                        | name=null, count=0, flag=false, level=null",
            "{\"flag\":1}                                | name=null, count=0, flag=true, level=null"
    })
    void whatIsAcceptedIsReadAsBefore(String json, String expected) {
        String read = mapper.readValue(json, In.class).toString();
        assertEquals(expected, read.substring("In[".length(), read.indexOf(", tags=")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "{\"level\":\"level high\"}", "{\"level\":\"\"}", "{\"level\":\"high\"}", "{\"tags\":\"x\"}",
            "{\"count\":99999999999}", "{\"name\":{}}", "{'name':'x'}", "{\"name\":", "[]"
    })
    void whatWasRefusedIsRefused(String json) {
        assertThrows(JacksonException.class, () -> mapper.readValue(json, In.class));
    }

    @Test
    void datesAreReadInEveryFormThatWasAccepted() {
        assertEquals(LocalDate.of(2026, 1, 2), mapper.readValue("{\"day\":[2026,1,2]}", In.class).day());
        assertEquals(Instant.ofEpochSecond(1700000000), mapper.readValue("{\"at\":1700000000}", In.class).at());
        assertEquals(null, mapper.readValue("{\"day\":\"\",\"at\":\"\"}", In.class).day());
    }
}
