package com.example.library.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;

/** Identité renvoyée au navigateur : jamais de jeton (RM-AUTH-05). {@code email} est null pour un jeton /dev/token. */
record AuthenticatedUserResponse(String userId, String email, List<String> roles, Instant expiresAt) {
}
