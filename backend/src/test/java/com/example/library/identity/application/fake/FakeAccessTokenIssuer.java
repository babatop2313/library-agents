package com.example.library.identity.application.fake;

import java.time.Duration;
import java.time.Instant;

import com.example.library.identity.application.port.out.AccessTokenIssuer;
import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.User;

/** Faux émetteur de jeton : mémorise l'utilisateur et la date d'émission reçus. */
public class FakeAccessTokenIssuer implements AccessTokenIssuer {

    private User lastUser;
    private Instant lastIssuedAt;
    private int calls;

    public User lastUser() {
        return lastUser;
    }

    public Instant lastIssuedAt() {
        return lastIssuedAt;
    }

    public int calls() {
        return calls;
    }

    @Override
    public AccessToken issue(User user, Instant issuedAt) {
        calls++;
        lastUser = user;
        lastIssuedAt = issuedAt;
        return new AccessToken("fake-jwt-for-" + user.id().value(), issuedAt.plus(Duration.ofHours(1)));
    }
}
