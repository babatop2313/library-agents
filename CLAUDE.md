# CLAUDE.md — Bibliothèque

> Mémoire du projet, lue par Claude à chaque session. Garde-la courte (< 150 lignes) :
> les conventions détaillées sont dans `.claude/skills/`.

## Contexte
- Application : gestion d'une bibliothèque qui prête des livres à ses membres (projet bac à sable de la Claude Factory).
- Modules : `catalog` (livres, exemplaires), `loan` (emprunts) et `identity` (comptes, connexion).
  `loan` appelle `catalog` uniquement par `catalog.api.CatalogApi` ; les dates des emprunts sont calculées dans le
  fuseau `APP_TIMEZONE`. Contrat : `docs/api/loan.md` ; plan : `docs/specs/loan-plan.md`.
- Langue du code : anglais. Langue des commentaires métier, specs et messages de commit : français.
- Langue des échanges avec Claude : français.

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

Emprunts : ordre des refus 404 livre inconnu, 409 retard, limite de 3, livre déjà emprunté, plus d'exemplaire.
La limite est sérialisée par membre (verrou consultatif PostgreSQL de transaction) ; le stock par mise à jour SQL
conditionnelle. Les cas attendus de `catalog.api` sont des résultats, jamais des exceptions (transaction partagée).

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
library-web/                               ← frontend Angular (src/app/core, shared, features)
docs/specs/                                ← une spec par fonctionnalité
```

## Frontend
- Dossier `library-web/` : **Angular 21**, composants standalone, tests Vitest, lint angular-eslint.
- Interface : PrimeNG 21 (thème Aura, PrimeIcons) ; Bootstrap 5 pour la grille et les utilitaires uniquement.
- `proxy.conf.json` route `/api` vers `http://localhost:8080` en développement : aucune URL d'API en dur.
- Erreurs HTTP : `core/http/error.interceptor.ts` (notifications `p-toast`, lecture du `ProblemDetail`).
- Node.js ≥ 24.0 requis (Angular 22 exigerait Node ≥ 24.15).

## Sécurité
- JWT signé HS256 (`JWT_SECRET`), rôles dans la claim `roles` → `ROLE_<rôle>`.
- Rôles : `LIBRARIAN` (gère le catalogue), `MEMBER` (emprunte).
- L'identifiant du membre connecté = `sub` du JWT (`@AuthenticationPrincipal Jwt jwt` → `jwt.getSubject()`).
  Emprunts (`/api/v1/loans/**`) : rôle `MEMBER` exigé (403 pour un `LIBRARIAN` seul) ; membre lu via `@CurrentMember`,
  jamais dans le corps ; `sub` non UUID = 401. Refus métier = 409, livre ou emprunt introuvable = 404.
- Connexion : `POST /api/v1/auth/login` (e-mail + mot de passe) ; `GET /api/v1/auth/me` ; `POST /api/v1/auth/logout`.
- Transport du jeton (navigateur) : cookie `ACCESS_TOKEN` `HttpOnly; Secure; SameSite=Strict; Path=/api`,
  jamais dans le corps ni lisible par JavaScript. Les clients non navigateurs envoient `Authorization: Bearer`
  (prioritaire : si l'en-tête est présent, le cookie n'est pas lu).
- CSRF exigé pour toute requête modifiante authentifiée par cookie : cookie `XSRF-TOKEN` (lisible par JS,
  `Secure`, `SameSite=Strict`) recopié dans l'en-tête `X-XSRF-TOKEN` (géré par `HttpClient` côté Angular).
  Dispensés : requêtes avec `Authorization: Bearer`, et `POST /api/v1/auth/login`.
- En local (profil `local`), `POST /dev/token` avec `{"roles":["LIBRARIAN"]}` fournit un token de test.

## Environnement local (Windows)
- Base de données : **PostgreSQL installé sur Windows** (port 5432, base et utilisateur `library`).
  Ne pas utiliser `docker compose` pour la base.
- Docker Desktop sert uniquement aux tests (Testcontainers lance un PostgreSQL jetable).
- Variables obligatoires au lancement : `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (32 octets minimum), dans `.env`
  (lu au démarrage par `spring.config.import`, facultatif), jamais commité (ignoré par `.env*`).
  Modèle : `.env.example`. Emplacement recommandé : la racine du dépôt. Priorité : variables système
  > `.env` racine > `backend/.env`. Lancer depuis `backend/`.
- Aucune valeur par défaut pour les secrets : si l'une manque ou est vide, l'application
  refuse de démarrer et liste les variables absentes. En test, `src/test/resources/application.properties`
  les fournit (Testcontainers remplace la vraie connexion).
- Amorçage du premier bibliothécaire (au démarrage, seulement si aucun `LIBRARIAN` n'existe) :
  `BOOTSTRAP_LIBRARIAN_EMAIL`, `BOOTSTRAP_LIBRARIAN_PASSWORD` (12 caractères minimum), dans `.env`.
  Aucune valeur par défaut, ni dans `application.yml` ni dans le code ; absentes = aucun compte créé (WARN).
- Fuseau de la bibliothèque : `APP_TIMEZONE` (défaut `Africa/Dakar`, non secret) ; il définit « la date du jour »
  des emprunts. Une valeur inconnue empêche le démarrage.
- Membre de test (profil `local` uniquement, rôle `MEMBER`) : `DEV_MEMBER_EMAIL`, `DEV_MEMBER_PASSWORD`
  (12 caractères minimum), dans `.env`. Défaut **vide** dans `application-local.yml` seulement ; absents = aucun
  compte créé (WARN).

## Commandes
| Action | Commande |
|---|---|
| Compiler le backend | `cd backend && ./mvnw -q compile` |
| Tests backend (Docker Desktop démarré) | `cd backend && ./mvnw test` |
| Un seul test | `cd backend && ./mvnw test -Dtest=NomDuTest` |
| Vérifier l'architecture | `cd backend && ./mvnw test -Dtest=ArchitectureTest` |
| Lancer l'API | `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (variables fournies par le `.env` ou l'environnement) |
| Installer les dépendances du frontend | `cd library-web && npm ci` |
| Lancer le frontend (backend démarré) | `cd library-web && npx ng serve` → http://localhost:4200 (connexion avec le bibliothécaire d'amorçage) |
| Lancer le frontend sans backend (données simulées) | `cd library-web && npx ng serve --configuration mock` → comptes fictifs dans `src/mocks/auth/users.json` |
| Tests frontend | `cd library-web && npx ng test --watch=false` |
| Lint frontend | `cd library-web && npm run lint` |
| Build frontend | `cd library-web && npx ng build` |


