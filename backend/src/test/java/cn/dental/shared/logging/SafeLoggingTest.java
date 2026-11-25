package cn.dental.shared.logging;

import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeLoggingTest {
    private String render(String message, Object[] args, Throwable error, Map<String,String> mdc) throws Exception {
        var context = new LoggerContext();
        try {
            var config = new JoranConfigurator(); config.setContext(context);
            config.doConfigure(getClass().getResource("/logback-spring.xml"));
            var logger = context.getLogger("ROOT");
            @SuppressWarnings("unchecked")
            var appender = (OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>) logger.getAppender("CONSOLE");
            var output = new ByteArrayOutputStream(); appender.setOutputStream(output);
            var event = new LoggingEvent(getClass().getName(), logger, Level.ERROR, message, error, args);
            event.setMDCPropertyMap(mdc);
            appender.doAppend(event);
            return output.toString(StandardCharsets.UTF_8);
        } finally { context.stop(); }
    }
    @Test void keepsApprovedMetadata() throws Exception {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        String text = render("REQUEST_COMPLETED", null, null, Map.of("request_id", id, "http_status", "200"));
        assertTrue(text.contains("event=REQUEST_COMPLETED"));
        assertTrue(text.contains(id)); assertTrue(text.contains("http_status=200"));
    }
    @Test void blocksJsonBearerAndPatientText() throws Exception {
        for (String message : new String[]{"{\"password\":\"SECRET_CANARY\"}",
                "Authorization: Bearer SECRET_CANARY", "Patient phone SECRET_CANARY"}) {
            String text = render(message, null, null, Map.of());
            assertTrue(text.contains("event=UNCLASSIFIED")); assertFalse(text.contains("SECRET_CANARY"));
        }
    }
    @Test void blocksArgumentsAndExceptionsEvenForApprovedEvents() throws Exception {
        String text = render("REQUEST_FAILED", new Object[]{"SECRET_CANARY"},
                new IllegalStateException("SECRET_CANARY", new RuntimeException("INNER_CANARY")), Map.of());
        assertTrue(text.contains("event=REQUEST_FAILED"));
        assertFalse(text.contains("SECRET_CANARY")); assertFalse(text.contains("INNER_CANARY"));
        assertFalse(text.contains("IllegalStateException"));
    }
    @Test void rejectsInvalidAndUnapprovedMdc() throws Exception {
        String text = render("ACCESS_DENIED", null, null,
                Map.of("request_id", "SECRET_CANARY\nFORGED", "http_status", "200 SECRET_CANARY", "token", "SECRET_CANARY"));
        assertTrue(text.contains("request_id=- http_status=-"));
        assertFalse(text.contains("SECRET_CANARY")); assertFalse(text.contains("FORGED"));
    }
}
