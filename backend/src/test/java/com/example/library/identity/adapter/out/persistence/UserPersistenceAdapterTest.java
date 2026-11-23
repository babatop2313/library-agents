package com.example.library.identity.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.example.library.PostgresTestcontainersConfiguration;
import com.example.library.identity.domain.model.Email;
import com.example.library.identity.domain.model.PasswordHash;
import com.example.library.identity.domain.model.Role;
import com.example.library.identity.domain.model.User;
import com.example.library.identity.domain.model.UserId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistance réelle (PostgreSQL via Testcontainers, schéma Flyway V2) : RM-AUTH-01, 02, 03, 08.
 * Chaque test est transactionnel et annulé : aucun nettoyage nécessaire.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, UserPersistenceAdapter.class, UserPersistenceMapper.class})
class UserPersistenceAdapterTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final PasswordHash HASH = new PasswordHash("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ0123456");

    @Autowired
    private UserPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    private static User user(String email, boolean enabled, Role... roles) {
        return User.restore(UserId.newId(), Email.of(email), HASH, enabled, Set.of(roles), CREATED_AT);
    }

    @Test
    void save_quandUtilisateurAvecRolesCumules_alorsLoadByEmailRelitToutLEtat() {
        // Given
        User saved = user("alice@example.org", true, Role.MEMBER, Role.LIBRARIAN);

        // When
        adapter.save(saved);
        User loaded = adapter.loadByEmail(Email.of("alice@example.org")).orElseThrow();

        // Then
        assertThat(loaded.id()).isEqualTo(saved.id());
        assertThat(loaded.email()).isEqualTo(saved.email());
        assertThat(loaded.passwordHash()).isEqualTo(HASH);
        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.LIBRARIAN);
        assertThat(loaded.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void save_quandCompteDesactive_alorsEtatEnabledFauxRelu() {
        // Given
        adapter.save(user("off@example.org", false, Role.MEMBER));

        // When
        User loaded = adapter.loadByEmail(Email.of("off@example.org")).orElseThrow();

        // Then
        assertThat(loaded.enabled()).isFalse();
        assertThat(loaded.canLogIn()).isFalse();
    }

    @Test
    void loadByEmail_quandEmailInconnu_alorsVide() {
        // When / Then
        assertThat(adapter.loadByEmail(Email.of("personne@example.org"))).isEmpty();
    }

    @Test
    void loadByEmail_quandEmailStockeAvecMajuscules_alorsTrouveParLaFormeNormalisee() {
        // Given : ligne insérée en SQL avec une casse différente (donnée héritée)
        jdbc.update("INSERT INTO app_user (id, email, password_hash, enabled, created_at) VALUES (?, ?, ?, TRUE, now())",
                UUID.randomUUID(), "Legacy@Example.ORG", HASH.value());
        UUID id = jdbc.queryForObject("SELECT id FROM app_user WHERE lower(email) = 'legacy@example.org'", UUID.class);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'MEMBER')", id);

        // When
        var loaded = adapter.loadByEmail(Email.of("legacy@example.org"));

        // Then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().id().value()).isEqualTo(id);
    }

    @Test
    void save_quandEmailDejaPresentAvecAutreCasse_alorsContrainteDUnicite() {
        // Given
        adapter.save(user("alice@example.org", true, Role.MEMBER));
        jdbc.execute("SELECT 1");

        // When / Then : insertion SQL directe d'un e-mail ne différant que par la casse
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (id, email, password_hash, enabled, created_at) VALUES (?, ?, ?, TRUE, now())",
                UUID.randomUUID(), "ALICE@example.org", HASH.value()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_quandMemeEmailDeuxFois_alorsDataIntegrityViolation() {
        // Given
        adapter.save(user("alice@example.org", true, Role.MEMBER));

        // When / Then
        assertThatThrownBy(() -> adapter.save(user("alice@example.org", true, Role.LIBRARIAN)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_quandRoleHorsListe_alorsContrainteCheckRefusee() {
        // Given
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, password_hash, enabled, created_at) VALUES (?, ?, ?, TRUE, now())",
                id, "bob@example.org", HASH.value());

        // When / Then
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void librarianExists_quandAucunUtilisateur_alorsFaux() {
        // When / Then
        assertThat(adapter.librarianExists()).isFalse();
    }

    @Test
    void librarianExists_quandSeulementDesMembres_alorsFaux() {
        // Given
        adapter.save(user("alice@example.org", true, Role.MEMBER));

        // When / Then
        assertThat(adapter.librarianExists()).isFalse();
    }

    @Test
    void librarianExists_quandUnBibliothecaire_alorsVrai() {
        // Given
        adapter.save(user("boss@example.org", true, Role.LIBRARIAN));

        // When / Then
        assertThat(adapter.librarianExists()).isTrue();
    }

    @Test
    void librarianExists_quandRolesCumules_alorsVrai() {
        // Given
        adapter.save(user("both@example.org", true, Role.MEMBER, Role.LIBRARIAN));

        // When / Then
        assertThat(adapter.librarianExists()).isTrue();
    }

    @Test
    void delete_quandUtilisateurSupprime_alorsSesRolesSontSupprimesEnCascade() {
        // Given
        User alice = user("alice@example.org", true, Role.MEMBER, Role.LIBRARIAN);
        adapter.save(alice);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class,
                alice.id().value());

        // When
        jdbc.update("DELETE FROM app_user WHERE id = ?", alice.id().value());

        // Then
        Integer after = jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class,
                alice.id().value());
        assertThat(before).isEqualTo(2);
        assertThat(after).isZero();
    }

    @Test
    void save_quandMotDePasseHache_alorsValeurStockeeCommenceParBcryptSansTexteEnClair() {
        // Given
        User alice = user("alice@example.org", true, Role.MEMBER);

        // When
        adapter.save(alice);

        // Then
        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class,
                alice.id().value());
        assertThat(stored).startsWith("$2").doesNotContain("S3cret!pass-long");
    }
}
