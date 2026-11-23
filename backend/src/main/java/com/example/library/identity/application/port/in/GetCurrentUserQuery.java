package com.example.library.identity.application.port.in;

import java.time.Instant;
import java.util.List;

/** Claims du JWT de l'utilisateur courant ; {@code email} et {@code roles} peuvent être null (jeton /dev/token). */
public record GetCurrentUserQuery(String subject, String email, List<String> roles, Instant expiresAt) {
}
