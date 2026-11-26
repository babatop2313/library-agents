package com.example.library.loan.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** Horloge de test modifiable (instant et fuseau), pour fixer « la date du jour » sans toucher à l'horloge système. */
public final class MutableClock extends Clock {

    /** 2026-11-24, midi à Dakar (UTC+0) : la date du jour des scénarios de la spec. */
    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-11-24T12:00:00Z");
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Dakar");

    private volatile Instant instant = DEFAULT_INSTANT;
    private volatile ZoneId zone = DEFAULT_ZONE;

    public void set(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void reset() {
        set(DEFAULT_INSTANT, DEFAULT_ZONE);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return Clock.fixed(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
