package cn.dental.organization.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Date-only certificate facts; this is not a complete clinical authorization decision. */
public final class CredentialValidity {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private CredentialValidity() {}

    public static boolean onDate(String state, LocalDate from, LocalDate to, boolean noExpiry, LocalDate date) {
        return coversDates(state, from, to, noExpiry, date, date);
    }

    public static boolean coversInterval(String state, LocalDate from, LocalDate to, boolean noExpiry,
                                         Instant startsAt, Instant endsAt) {
        Objects.requireNonNull(startsAt); Objects.requireNonNull(endsAt);
        if (!startsAt.isBefore(endsAt)) throw new IllegalArgumentException("Interval must be nonempty");
        // Half-open interval: midnight at the end does not require the following day's qualification.
        return coversDates(state, from, to, noExpiry,
            startsAt.atZone(ZONE).toLocalDate(), endsAt.minusNanos(1).atZone(ZONE).toLocalDate());
    }

    private static boolean coversDates(String state, LocalDate from, LocalDate to, boolean noExpiry,
                                       LocalDate first, LocalDate last) {
        Objects.requireNonNull(first); Objects.requireNonNull(last);
        if (!"verified".equals(state) || first.isAfter(last)) return false;
        if (from != null && to != null && from.isAfter(to)) return false;
        if (noExpiry && to != null) return false;
        if (from != null && from.isAfter(first)) return false;
        return to != null ? !to.isBefore(last) : noExpiry;
    }
}
