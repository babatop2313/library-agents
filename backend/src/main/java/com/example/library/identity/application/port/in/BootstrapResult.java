package com.example.library.identity.application.port.in;

import java.util.Objects;

import com.example.library.identity.domain.model.BootstrapOutcome;
import com.example.library.identity.domain.model.UserId;

/** Issue de l'amorçage ; {@code createdUserId} n'est renseigné que pour {@link BootstrapOutcome#CREATED}. */
public record BootstrapResult(BootstrapOutcome outcome, UserId createdUserId) {

    public BootstrapResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static BootstrapResult of(BootstrapOutcome outcome) {
        return new BootstrapResult(outcome, null);
    }

    public static BootstrapResult created(UserId userId) {
        return new BootstrapResult(BootstrapOutcome.CREATED, Objects.requireNonNull(userId, "userId"));
    }
}
