# Intégration — PostgreSQL
> Statut : testée

## Résumé
Base de données relationnelle principale, accédée par JPA (Hibernate) et versionnée par Flyway.
Rôle : `sortant-persistance`. Validée sur le projet Bibliothèque (PostgreSQL 17 en local, 16 dans les tests).

## Cas d'usage
- Toute donnée métier qui a besoin de transactions, de contraintes d'intégrité et de requêtes relationnelles.
- **Ne pas** l'utiliser comme file de messages (préférer RabbitMQ ou Kafka) ni comme cache de données chaudes
  partagées entre instances (préférer Redis), ni pour stocker des fichiers volumineux (préférer le stockage objet).

## Dépendances
- `spring-boot-starter-data-jpa`
- `org.postgresql:postgresql` (scope `runtime`)
- `spring-boot-starter-flyway` et `org.flywaydb:flyway-database-postgresql` (support PostgreSQL de Flyway, séparé depuis Flyway 10)
- Tests : `spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, `org.testcontainers:testcontainers-junit-jupiter`

## Connexion et configuration
```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/<base>}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}          # jamais de valeur par défaut
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}
      connection-timeout: 5s
  jpa:
    open-in-view: false               # pas de requête SQL déclenchée depuis la couche web
    hibernate:
      ddl-auto: validate              # le schéma n'est géré que par Flyway
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    locations: classpath:db/migration
```
- **Utilisateur applicatif dédié**, propriétaire de ses tables, **jamais le super-utilisateur `postgres`**.
  Vérification : `SELECT installed_by FROM flyway_schema_history;` doit renvoyer cet utilisateur.

## Rôle dans l'architecture
- `sortant-persistance` : entités JPA, repositories Spring Data, adapter (ou repository) et mapper de persistance.
- Les entités JPA ne sortent jamais de ce rôle : le reste du code manipule des objets du domaine ou des DTO.
- En architecture en couches, les entités sont dans `entity` et les repositories dans `repository` (voir `roles.conf`).

## Conventions
- Migrations : voir le socle des conventions (`V<n>__<verbe>_<objet>.sql`, jamais de modification d'une migration existante).
- snake_case ; contraintes nommées `pk_`, `uk_`, `fk_`, `ck_` ; index sur chaque clé étrangère et colonne filtrée.
- Verrouillage optimiste (`@Version`) sur les entités modifiées de façon concurrente.
- Pas de clé étrangère entre les tables de modules différents (architecture modulaire) : référence par identifiant.
- Requêtes : méthodes dérivées ou JPQL ; SQL natif seulement si nécessaire, toujours paramétré (jamais de concaténation).

## Résilience
- `connection-timeout` court : une base indisponible doit échouer vite, pas bloquer les threads.
- Conflit de version (`OptimisticLockingFailureException`) traduit en erreur métier « conflit » (409), jamais en 500.
- Transactions courtes ; pas d'appel réseau lent à l'intérieur (voir le socle).

## Sécurité
- Mot de passe dans une variable d'environnement ; utilisateur applicatif sans droit `SUPERUSER` ni `CREATEROLE`.
- Accès en lecture pour un assistant IA : rôle séparé `claude_ro` (kit : `extras/mcp/postgres-readonly-role.sql`).
- Jamais de donnée personnelle dans les logs SQL ; `show-sql` désactivé hors poste de développement.

## Observabilité
- Health check Actuator `db` (inclus avec le starter).
- Métriques HikariCP (`hikaricp.connections.*`) via Micrometer.

## Développement local
- PostgreSQL installé sur le poste (installeur officiel sous Windows et macOS, paquet `postgresql` sous Linux),
  ou conteneur `postgres:16-alpine`. Créer la base et l'utilisateur applicatif propriétaire.
- Le jeu d'essai éventuel va dans `docs/dev/` et s'exécute à la main.

## Tests
```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {          // org.testcontainers.postgresql.PostgreSQLContainer (Testcontainers 2)
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
```
- Tests de persistance avec `@DataJpaTest` et `@Import(TestcontainersConfiguration.class)`, jamais H2.
- Un test par contrainte importante (unicité, `CHECK`) et par requête spécifique (tri, filtre).
- Docker doit tourner avant `./mvnw test`.

## Grille de revue
- Nouvelle migration plutôt que modification d'une existante ; contraintes et index nommés.
- Aucune entité JPA hors du rôle `sortant-persistance` ; `open-in-view: false` ; `ddl-auto: validate`.
- Requêtes paramétrées ; pas de N+1 évident (chargements dans une boucle).
- `@Version` sur les entités modifiées en concurrence ; conflit traduit en 409.
- Utilisateur applicatif dédié dans la configuration, mot de passe en variable d'environnement.
