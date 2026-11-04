package com.example.library;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Vérifie que l'application démarre : sécurité, Flyway et JPA configurés, base PostgreSQL réelle.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LibraryApplicationTests {

    @Test
    void contextLoads() {
    }
}
