package com.example.library.identity.application.port.in;

import java.util.Objects;

import com.example.library.identity.domain.model.DevMemberBootstrapOutcome;
import com.example.library.identity.domain.model.UserId;

/** Issue de l'amorçage du membre de test ; {@code createdUserId} n'est renseigné que pour CREATED. */
public record DevMemberBootstrapResult(DevMemberBootstrapOutcome outcome, UserId createdUserId) {

    public DevMemberBootstrapResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public static DevMemberBootstrapResult of(DevMemberBootstrapOutcome outcome) {
        return new DevMemberBootstrapResult(outcome, null);
    }

    public static DevMemberBootstrapResult created(UserId userId) {
        return new DevMemberBootstrapResult(DevMemberBootstrapOutcome.CREATED,
                Objects.requireNonNull(userId, "userId"));
    }
}
