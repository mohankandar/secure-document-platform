package com.securedoc.platform.ai.analysis;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Task 0023 C7 (#131): dropped model findings are counted by reason; no text is logged. */
class SensitiveContentResponseParserDropCountTest {

    @Test
    void droppedFindingsAreCountedByReasonWithoutAnyText() throws Exception {
        String pageText = "Applicant SSN 123-45-6789 on file";
        String answer = """
                {"findings":[
                  {"pageNumber":1,"entityType":"SSN","matchedText":"123-45-6789","confidence":0.97},
                  {"pageNumber":1,"entityType":"SSN","matchedText":"123 45 6789","confidence":0.97},
                  {"pageNumber":1,"entityType":"MOTHERS_MAIDEN_NAME_Smith","matchedText":"Smith","confidence":0.9},
                  {"pageNumber":1,"entityType":"SSN","matchedText":"","confidence":0.9},
                  {"pageNumber":1,"entityType":"SSN","matchedText":"123-45-6789","confidence":1.7},
                  {"pageNumber":9,"entityType":"SSN","matchedText":"987-65-4321","confidence":0.9}
                ]}
                """;
        SensitiveContentResponseParser parser = new SensitiveContentResponseParser(JsonMapper.builderWithJackson2Defaults().build());

        Logger logger = (Logger) LoggerFactory.getLogger(SensitiveContentResponseParser.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        List<?> accepted;
        try {
            accepted = parser.parse(answer, List.of(new PageContent(1, pageText)), "ollama", "m", "a", "1");
        } finally {
            logger.detachAppender(logs);
        }

        assertEquals(1, accepted.size());
        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(List.of("AI_FINDINGS_DROPPED provider=ollama model=m pages=1 received=6 accepted=1 "
                + "pageNotInBatch=1 unknownType=1 blankText=1 confidenceOutOfRange=1 textNotOnPage=1"), lines);
        String all = String.join("\n", lines);
        for (String content : List.of("123", "6789", "Smith", "MAIDEN", "Applicant")) {
            assertFalse(all.contains(content), "log must not carry " + content);
        }
    }
}
