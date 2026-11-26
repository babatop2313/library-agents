package com.example.library.config;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Fuseau de la bibliothèque (propriété {@code app.timezone}, variable APP_TIMEZONE) : c'est lui qui définit « la date
 * du jour » des emprunts, indépendamment du fuseau du serveur.
 */
public final class LibraryTimeZone {

    /** Fuseau par défaut, si la propriété est absente ou blanche. */
    public static final ZoneId DEFAULT = ZoneId.of("Africa/Dakar");

    static final String PROPERTY = "app.timezone";

    private LibraryTimeZone() {
    }

    /** @throws InvalidTimeZoneException si la valeur n'est pas un fuseau reconnu */
    public static ZoneId resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return ZoneId.of(raw.strip());
        } catch (DateTimeException e) {
            throw new InvalidTimeZoneException(PROPERTY, raw, e);
        }
    }
}
