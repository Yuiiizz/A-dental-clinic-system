package cn.dental.support;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;
    public MutableClock(Instant instant, ZoneId zone) { this(new AtomicReference<>(instant), zone); }
    private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now=now; this.zone=zone; }
    public void advance(Duration duration) { now.updateAndGet(t -> t.plus(duration)); }
    public void set(Instant instant) { now.set(instant); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
    @Override public Instant instant() { return now.get(); }
}
