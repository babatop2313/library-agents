package com.example.library.identity.application.port.out;

import java.time.Instant;

import com.example.library.identity.domain.model.AccessToken;
import com.example.library.identity.domain.model.User;

public interface AccessTokenIssuer {

    AccessToken issue(User user, Instant issuedAt);
}
