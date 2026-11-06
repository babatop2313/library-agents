# CLAUDE.md — Bibliothèque

> Mémoire du projet, lue par Claude à chaque session. Garde-la courte (< 150 lignes) :
> les conventions détaillées sont dans `.claude/skills/`.

## Contexte
- Application : gestion d'une bibliothèque qui prête des livres à ses membres (projet bac à sable de la Claude Factory).
- Modules prévus : `catalog` (livres, exemplaires) et `loan` (emprunts).
- Langue du code : anglais. Langue des commentaires métier, specs et messages de commit : français.

## Règles métier de référence
| Code | Règle |
|---|---|
| RM-01 | Un membre a au maximum 3 emprunts actifs. |
| RM-02 | Un membre qui a un emprunt en retard ne peut pas emprunter. |
| RM-03 | Un emprunt dure 14 jours. |
| RM-04 | On ne peut emprunter un livre que s'il reste un exemplaire disponible. |
| RM-05 | Un livre rendu redevient disponible ; on ne rend pas deux fois le même emprunt. |
| RM-06 | Un emprunt peut être prolongé une seule fois de 7 jours, s'il n'est pas en retard. |
| RM-07 | L'ISBN d'un livre est unique et valide (13 chiffres). |

## Stack
- Backend : Java 21, **Spring Boot 4.1**, Spring Security (resource server JWT HS256), Spring Data JPA, Flyway, PostgreSQL, ArchUnit
- Tests : JUnit 5, AssertJ, Mockito, Testcontainers 2.x (Docker Desktop requis pour les tests)
- Spring Boot 4 : starters `spring-boot-starter-webmvc`, `-flyway`, `-security-oauth2-resource-server` ;
  starters de test `-webmvc-test`, `-data-jpa-test`, `-security-test` ; `@MockitoBean` (plus de `@MockBean`) ;
  Jackson 3 (`tools.jackson`).

## Structure du dépôt
```
backend/                                   ← code Spring Boot, organisé selon la référence d'architecture
  src/main/java/com/example/library/         package racine
  src/main/resources/db/migration/           migrations Flyway V<n>__<description>.sql
  src/test/java/com/example/library/ArchitectureTest.java   règles ArchUnit (ne jamais supprimer ni modifier)
docs/specs/                                ← une spec par fonctionnalité
```

## Sécurité
- JWT signé HS256 (`JWT_SECRET`), rôles dans la claim `roles` → `ROLE_<rôle>`.
- Rôles : `LIBRARIAN` (gère le catalogue), `MEMBER` (emprunte).
- L'identifiant du membre connecté = `sub` du JWT (`@AuthenticationPrincipal Jwt jwt` → `jwt.getSubject()`).
- En local (profil `local`), `POST /dev/token` avec `{"roles":["LIBRARIAN"]}` fournit un token de test.

## Environnement local (Windows)
- Base de données : **PostgreSQL installé sur Windows** (port 5432, base et utilisateur `library`).
  Ne pas utiliser `docker compose` pour la base.
- Docker Desktop sert uniquement aux tests (Testcontainers lance un PostgreSQL jetable).
- Variables nécessaires au lancement : `DB_PASSWORD`, `JWT_SECRET` (dans `.env`, jamais commité).

## Commandes
| Action | Commande |
|---|---|
| Compiler le backend | `cd backend && ./mvnw -q compile` |
| Tests backend (Docker Desktop démarré) | `cd backend && ./mvnw test` |
| Un seul test | `cd backend && ./mvnw test -Dtest=NomDuTest` |
| Vérifier l'architecture | `cd backend && ./mvnw test -Dtest=ArchitectureTest` |
| Lancer l'API | `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (avec `DB_PASSWORD` et `JWT_SECRET` définis) |


