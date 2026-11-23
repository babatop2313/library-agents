package com.example.library.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.example.library.identity.domain.exception.InvalidUserException;
import org.junit.jupiter.api.Test;

/** RM-AUTH-01 et RM-AUTH-04 : invariants de l'utilisateur. */
class UserTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Email EMAIL = Email.of("alice@example.org");
    private static final PasswordHash HASH = new PasswordHash("$2a$10$abcdefghijklmnopqrstuv");

    @Test
    void register_quandRolesVides_alorsInvalidUserException() {
        // When / Then
        assertThatThrownBy(() -> User.register(EMAIL, HASH, Set.of(), NOW))
                .isInstanceOfSatisfying(InvalidUserException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EMPTY_ROLES"));
    }

    @Test
    void register_quandRolesCumules_alorsLesDeuxRolesConserves() {
        // When
        User user = User.register(EMAIL, HASH, Set.of(Role.MEMBER, Role.LIBRARIAN), NOW);

        // Then
        assertThat(user.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.LIBRARIAN);
        assertThat(user.hasRole(Role.LIBRARIAN)).isTrue();
        assertThat(user.hasRole(Role.MEMBER)).isTrue();
    }

    @Test
    void register_quandNouvelUtilisateur_alorsActifAvecIdentifiantEtDate() {
        // When
        User user = User.register(EMAIL, HASH, Set.of(Role.MEMBER), NOW);

        // Then
        assertThat(user.enabled()).isTrue();
        assertThat(user.canLogIn()).isTrue();
        assertThat(user.id()).isNotNull();
        assertThat(user.createdAt()).isEqualTo(NOW);
        assertThat(user.email()).isEqualTo(EMAIL);
        assertThat(user.hasRole(Role.LIBRARIAN)).isFalse();
    }

    @Test
    void roles_quandModification_alorsEnsembleImmuable() {
        // Given
        User user = User.register(EMAIL, HASH, Set.of(Role.MEMBER), NOW);

        // When / Then
        assertThatThrownBy(() -> user.roles().add(Role.LIBRARIAN)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void roles_quandEnsembleSourceModifieEnsuite_alorsUtilisateurInchange() {
        // Given
        Set<Role> source = EnumSet.of(Role.MEMBER);
        User user = User.register(EMAIL, HASH, source, NOW);

        // When
        source.add(Role.LIBRARIAN);

        // Then
        assertThat(user.roles()).containsExactly(Role.MEMBER);
    }

    @Test
    void canLogIn_quandCompteDesactive_alorsFaux() {
        // Given
        User user = User.restore(UserId.newId(), EMAIL, HASH, false, Set.of(Role.MEMBER), NOW);

        // When / Then
        assertThat(user.enabled()).isFalse();
        assertThat(user.canLogIn()).isFalse();
    }

    @Test
    void restore_quandDonneesPersistees_alorsEtatIdentique() {
        // Given
        UserId id = new UserId(UUID.randomUUID());

        // When
        User user = User.restore(id, EMAIL, HASH, true, Set.of(Role.LIBRARIAN), NOW);

        // Then
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.passwordHash()).isEqualTo(HASH);
        assertThat(user.roles()).containsExactly(Role.LIBRARIAN);
    }

    @Test
    void restore_quandRolesVides_alorsInvalidUserException() {
        // When / Then
        assertThatThrownBy(() -> User.restore(UserId.newId(), EMAIL, HASH, true, Set.of(), NOW))
                .isInstanceOf(InvalidUserException.class);
    }
}
