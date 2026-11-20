package com.example.library.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void fromClaim_quandValeurConnue_alorsRole() {
        // When / Then
        assertThat(Role.fromClaim("LIBRARIAN")).contains(Role.LIBRARIAN);
        assertThat(Role.fromClaim("MEMBER")).contains(Role.MEMBER);
    }

    @Test
    void fromClaim_quandInconnuNullOuCasseDifferente_alorsVide() {
        // When / Then
        assertThat(Role.fromClaim("ADMIN")).isEmpty();
        assertThat(Role.fromClaim("member")).isEmpty();
        assertThat(Role.fromClaim(null)).isEmpty();
    }
}
