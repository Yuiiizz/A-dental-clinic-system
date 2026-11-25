package cn.dental.shared.logging;

import java.util.Set;
import java.util.regex.Pattern;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Only explicitly approved event codes and validated correlation metadata reach the log. */
public final class SafeEventConverter extends ClassicConverter {
    private static final Set<String> EVENTS = Set.of(
            "APPLICATION_STARTED", "REQUEST_COMPLETED", "REQUEST_FAILED",
            "ACCESS_DENIED", "CONFIGURATION_REJECTED");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    @Override
    public String convert(ILoggingEvent event) {
        String code = event.getMessage();
        if (code == null || !EVENTS.contains(code)) code = "UNCLASSIFIED";
        var mdc = event.getMDCPropertyMap();
        String requestId = mdc == null ? null : mdc.get("request_id");
        if (requestId == null || !UUID.matcher(requestId).matches()) requestId = "-";
        String status = mdc == null ? null : mdc.get("http_status");
        if (status == null || !status.matches("[1-5][0-9]{2}")) status = "-";
        // Never format arguments, copy arbitrary MDC, or render exception messages/stack traces.
        return "event=" + code + " request_id=" + requestId + " http_status=" + status;
    }
}
