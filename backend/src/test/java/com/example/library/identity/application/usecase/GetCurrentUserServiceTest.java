package com.example.library.identity.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.example.library.identity.application.port.in.CurrentUser;
import com.example.library.identity.application.port.in.GetCurrentUserQuery;
import com.example.library.identity.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** RM-AUTH-26 : l'identité courante est lue dans les claims, sans base. */
class GetCurrentUserServiceTest {

    private static final Instant EXP = Instant.parse("2026-11-20T11:00:00Z");
    private final GetCurrentUserService service = new GetCurrentUserService();

    @Test
    void current_quandClaimsComplets_alorsIdentiteRestituee() {
        // Given
        GetCurrentUserQuery query = new GetCurrentUserQuery("11111111-1111-1111-1111-111111111111",
                "alice@example.org", List.of("MEMBER", "LIBRARIAN"), EXP);

        // When
        CurrentUser current = service.current(query);

        // Then
        assertThat(current.userId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(current.email()).isEqualTo("alice@example.org");
        assertThat(current.roles()).containsExactlyInAnyOrder(Role.MEMBER, Role.LIBRARIAN);
        assertThat(current.expiresAt()).isEqualTo(EXP);
    }

    @Test
    void current_quandRoleInconnu_alorsIgnore() {
        // Given
        GetCurrentUserQuery query = new GetCurrentUserQuery("sub", "a@example.org", List.of("MEMBER", "ADMIN"), EXP);

        // When
        CurrentUser current = service.current(query);

        // Then
        assertThat(current.roles()).containsExactly(Role.MEMBER);
    }

    @Test
    void current_quandEmailAbsent_alorsEmailNullEtSubjectBrut() {
        // Given : jeton /dev/token (profil local) sans claim email
        GetCurrentUserQuery query = new GetCurrentUserQuery("sujet-brut", null, List.of("MEMBER"), EXP);

        // When
        CurrentUser current = service.current(query);

        // Then
        assertThat(current.email()).isNull();
        assertThat(current.userId()).isEqualTo("sujet-brut");
    }

    @Test
    void current_quandRolesNull_alorsEnsembleVide() {
        // Given
        GetCurrentUserQuery query = new GetCurrentUserQuery("sub", null, null, EXP);

        // When / Then
        assertThat(service.current(query).roles()).isEmpty();
    }

    @Test
    void usecase_quandAnnotation_alorsPasDeTransactionPourLaLectureDesClaims() {
        // When
        Transactional transactional =
                AnnotatedElementUtils.findMergedAnnotation(GetCurrentUserService.class, Transactional.class);

        // Then
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
