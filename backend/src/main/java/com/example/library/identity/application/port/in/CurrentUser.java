package com.example.library.identity.application.port.in;

import java.time.Instant;
import java.util.Set;

import com.example.library.identity.domain.model.Role;

/** Identité de l'utilisateur courant. {@code email} est null pour un jeton /dev/token (profil local). */
public record CurrentUser(String userId, String email, Set<Role> roles, Instant expiresAt) {
}
