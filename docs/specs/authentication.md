# Authentification (e-mail / mot de passe -> JWT en cookie HttpOnly)

## Contexte et objectif
Aujourd'hui, la seule façon d'obtenir un JWT est `POST /dev/token` (profil `local`), qui fabrique un token pour n'importe quel sujet et n'importe quels rôles, sans identification. Il n'existe ni utilisateur, ni mot de passe, ni écran de connexion. Cette fonctionnalité permet à un bibliothécaire ou à un membre de se connecter avec son e-mail et son mot de passe, de recevoir un JWT (dans un cookie HttpOnly, option `auth-token` = `cookie-httponly` du profil `top`) utilisable sur l'API existante, puis d'utiliser l'application web authentifiée.

Constat sur l'existant :
- Backend (`backend/src/main/java/com/example/library/config/security/`) :
  - `SecurityConfig` : resource server JWT HS256 (clé `app.security.jwt.secret` = `JWT_SECRET`, 32 octets minimum), claim `roles` -> `ROLE_<rôle>`, session stateless, CSRF désactivé, `@EnableMethodSecurity`. Routes publiques actuelles : `/actuator/health`, `/actuator/info`, `/dev/token`. `POST /api/v1/books` exige `LIBRARIAN`, le reste exige un JWT valide. Un bean `JwtEncoder` existe déjà.
  - `DevTokenController` (`@Profile("local")`) : émet un JWT avec `iss=library-dev`, `sub` libre (UUID aléatoire par défaut), `roles` libres (`MEMBER` par défaut), durée 8 h.
  - `McpLocalSecurityConfig` (profil `mcp-local`) : hors périmètre, non modifié.
  - Une seule migration Flyway (`V1__create_table_book.sql`). Aucune table utilisateur ni membre.
  - Aucun code existant ne lit `jwt.getSubject()` : le module `loan` n'est pas encore implémenté, donc aucun emprunt existant ne référence un `sub`.
- Frontend (`library-web/src/app/`) : routes = `''` (Home) + `**` redirigeant vers `''` ; `core/layout/header/header.ts` (titre + lien accueil, sans zone utilisateur) ; `core/http/error.interceptor.ts` ignore volontairement le 401 (« laissé à l'intercepteur d'authentification ») ; `provideHttpClient(withInterceptors([errorInterceptor]))` ; `MessageService` fourni ; PrimeNG Aura ; aucune garde, aucun service d'authentification.
- Architecture hexagonale : nouveau module **`identity`** (utilisateurs + connexion), avec domaine sans dépendance framework, ports d'entrée/sortie, adapters web et persistance. Le hachage BCrypt et l'émission du JWT sont des ports sortants (adapters techniques). L'architecte confirme que les règles d'`ArchitectureTest` couvrent ce module supplémentaire **sans modifier `ArchitectureTest`**.

## Acteurs et rôles (qui a le droit de faire quoi)
| Acteur | Droit sur cette fonctionnalité |
|---|---|
| Non authentifié | Peut appeler `POST /api/v1/auth/login`, `POST /api/v1/auth/logout` et afficher l'écran de connexion. Rien d'autre. |
| `MEMBER` | Peut se connecter, consulter son identité (`GET /api/v1/auth/me`), se déconnecter. |
| `LIBRARIAN` | Idem `MEMBER`. |
| Système (démarrage) | Crée le premier bibliothécaire si aucun n'existe (RM-AUTH-08). |
| Mode développeur (profil `local`) | Peut utiliser `POST /dev/token`. |

## Règles métier (numérotées RM-01, RM-02…)
Les RM-01 à RM-07 du CLAUDE.md restent les règles de référence. Les règles de cette spec sont préfixées `RM-AUTH-`. RM-01 à RM-07 ne sont pas impactées.

Utilisateur et identifiants
- **RM-AUTH-01** : un utilisateur a un identifiant UUID, un e-mail unique, un mot de passe haché, un indicateur `enabled` et un ensemble non vide de rôles parmi `LIBRARIAN`, `MEMBER`. Les rôles sont cumulables (un utilisateur peut être `MEMBER` et `LIBRARIAN`).
- **RM-AUTH-02** : l'e-mail est normalisé avant toute comparaison et tout stockage : suppression des espaces de début/fin (trim), passage en minuscules. L'unicité est insensible à la casse (garantie par la normalisation et par un index unique sur `lower(email)`). Format valide : syntaxe d'adresse e-mail simple, longueur maximale 254 caractères.
- **RM-AUTH-03** : le mot de passe n'est jamais stocké, journalisé ni renvoyé en clair. Il est stocké uniquement haché en BCrypt (le hash inclut son sel). Politique à la création d'un mot de passe (aujourd'hui : amorçage uniquement) : **12 caractères minimum et 72 octets UTF-8 maximum** (limite BCrypt, jamais de troncature). Au login, aucune règle de longueur minimale n'est appliquée (on ne révèle pas la politique) ; un mot de passe vide est refusé en 400 ; un mot de passe de plus de 72 octets produit le 401 `INVALID_CREDENTIALS` de RM-AUTH-07.
- **RM-AUTH-04** : un compte peut être désactivé (`enabled = false`) ; un compte désactivé ne peut pas se connecter (même réponse qu'un identifiant invalide, RM-AUTH-07).

Connexion
- **RM-AUTH-05** : `POST /api/v1/auth/login` avec e-mail et mot de passe corrects pour un compte actif renvoie 200, pose le JWT dans un cookie (RM-AUTH-23) et renvoie dans le corps uniquement ce que l'interface affiche : identifiant, e-mail, rôles, date d'expiration. **Le jeton n'apparaît jamais dans le corps de la réponse.** Le JWT est signé HS256 avec la clé `JWT_SECRET` existante, de sorte que le décodeur actuel l'accepte.
- **RM-AUTH-06** : le JWT contient : `sub` = UUID de l'utilisateur, `email` = e-mail normalisé, `roles` = liste des rôles (même claim et mêmes valeurs que `/dev/token`, compatible avec `@PreAuthorize` et `SecurityConfig`), `iss` = `library`, `iat`, `exp`. Il ne contient ni mot de passe, ni hash. Durée de validité : **1 heure** (`app.security.jwt.ttl`, défaut `PT1H`). **Pas de refresh token** : à l'expiration, l'utilisateur se reconnecte.
- **RM-AUTH-07** : tout échec d'identification (e-mail inconnu, mot de passe erroné, mot de passe > 72 octets, compte désactivé) produit exactement la même réponse : 401, code `INVALID_CREDENTIALS`, message « E-mail ou mot de passe incorrect ». La comparaison BCrypt est **toujours exécutée**, y compris quand l'e-mail est inconnu (hash factice), pour que le temps de réponse ne distingue pas les cas.
- **RM-AUTH-09** : un corps de requête invalide (e-mail ou mot de passe absent/vide, e-mail mal formé ou trop long, JSON illisible) est refusé en 400 `VALIDATION_FAILED` avec `errors[]` (même convention que la spec `catalog-enregistrement-livre`), sans tentative d'authentification.
- **RM-AUTH-10** : routes publiques : `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `/actuator/health`, `/actuator/info`, `/dev/token` (profil `local` uniquement). **Tout le reste est authentifié par défaut.** L'accès anonyme éventuel à `GET /api/v1/books` sera décidé dans la feature Catalogue (aujourd'hui : authentifié). Aucune session serveur (stateless).
- **RM-AUTH-11** : un succès est journalisé (INFO) avec l'identifiant utilisateur seul, sans e-mail. Un échec de connexion est journalisé (WARN) avec l'e-mail **masqué** (`a***@example.org`) et sans identifiant ; l'e-mail n'apparaît masqué que dans les WARN sans identifiant. Jamais de mot de passe ni de token dans les logs.

Transport du jeton (option `auth-token` = `cookie-httponly`)
- **RM-AUTH-23** : le jeton est remis dans un cookie `ACCESS_TOKEN` (nom configurable) : `HttpOnly; Secure; SameSite=Strict; Path=/api; Max-Age=<durée du jeton>`, construit avec `ResponseCookie`.
- **RM-AUTH-24** : le backend lit le jeton dans l'en-tête `Authorization: Bearer` s'il est présent, sinon dans le cookie. Les clients non navigateurs (tests, serveur MCP, scripts) utilisent l'en-tête.
- **RM-AUTH-25** : CSRF exigé pour une requête modifiante qui porte le cookie `ACCESS_TOKEN` et aucun en-tête `Authorization` : `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`, en-tête `X-XSRF-TOKEN`), `CsrfTokenRequestAttributeHandler`. Les requêtes portant `Authorization: Bearer` ou sans cookie en sont dispensées. `POST /api/v1/auth/login` est dispensé de CSRF (risque de login CSRF accepté, atténué par `SameSite=Strict`) et initialise le jeton CSRF. Une requête refusée faute de jeton CSRF valide reçoit 403.
- **RM-AUTH-28** : les 401 et 403 produits par la couche de sécurité (non authentifié, jeton invalide/expiré, rôle insuffisant, CSRF) sont renvoyés en `ProblemDetail` avec un `code` (`UNAUTHENTICATED`, `ACCESS_DENIED`), comme le reste de l'API. La répartition `/error` est accessible sans authentification.
- **RM-AUTH-26** : `GET /api/v1/auth/me` renvoie l'identité de l'utilisateur courant (identifiant, e-mail, rôles, expiration, lus dans le JWT, sans accès à la base) ou 401 si non authentifié. Avec un jeton `/dev/token` (profil `local` uniquement, sans claim `email`) : 200, `email` null, `userId` = `sub` brut.
- **RM-AUTH-27** : `POST /api/v1/auth/logout` renvoie 204 et un cookie `ACCESS_TOKEN` vidé (`Max-Age=0`, mêmes attributs), que l'appelant soit authentifié ou non (soumis au CSRF). Le JWT n'est pas révoqué côté serveur (pas de liste noire) : il reste techniquement valide jusqu'à `exp`, mais le navigateur ne le détient plus.

Premier bibliothécaire
- **RM-AUTH-08** : au démarrage, s'il n'existe aucun utilisateur ayant le rôle `LIBRARIAN`, un bibliothécaire est créé à partir de `BOOTSTRAP_LIBRARIAN_EMAIL` et `BOOTSTRAP_LIBRARIAN_PASSWORD` (propriétés `app.bootstrap.librarian.email` / `.password`, sans valeur par défaut), avec **le seul rôle `LIBRARIAN`** et `enabled = true`. L'e-mail est normalisé (RM-AUTH-02), le mot de passe doit respecter RM-AUTH-03 (12 caractères minimum, 72 octets maximum) et est haché en BCrypt.
  - S'il existe déjà un bibliothécaire : rien n'est créé ni modifié (idempotent, y compris si les variables changent).
  - Si l'une des variables est absente ou vide : WARN et démarrage sans création.
  - Si le mot de passe ne respecte pas la politique ou si l'e-mail est mal formé : WARN et démarrage sans création.
  - Si un utilisateur de même e-mail existe sans le rôle `LIBRARIAN` : il est ignoré (ni modifié, ni promu), WARN, démarrage.
  - Aucun e-mail ni mot de passe par défaut dans le code, les `application*.yml` commités ni les migrations. **Le mot de passe d'amorçage n'est jamais journalisé**, y compris dans les messages d'avertissement.

Endpoint de développement
- **RM-AUTH-12** : `/dev/token` reste disponible uniquement avec le profil `local` (comportement et `@Profile("local")` inchangés, jeton dans le corps pour les clients non navigateurs) ; sa règle `permitAll` ne s'applique qu'avec le profil `local`. Avec tout autre profil, `POST /dev/token` ne renvoie pas de jeton (401 ou 404).

Frontend
- **RM-AUTH-13** : l'écran de connexion (`/login`) contient un champ e-mail, un champ mot de passe PrimeNG `p-password` avec bouton afficher/masquer (`toggleMask`, sans jauge de force) et un bouton « Se connecter ». Le bouton est inactif tant que l'e-mail est vide/mal formé ou le mot de passe vide, et pendant l'appel en cours (anti double soumission).
- **RM-AUTH-14** : en cas de 401 `INVALID_CREDENTIALS`, l'écran affiche le message générique de RM-AUTH-07 dans le formulaire (pas de toast en doublon), conserve l'e-mail saisi et vide le mot de passe. Les autres erreurs (400, 5xx, réseau) passent par `errorInterceptor` (toast).
- **RM-AUTH-15** : après connexion réussie, l'utilisateur est redirigé vers la page demandée initialement (paramètre `returnUrl`, chemin interne validé uniquement, sinon `/`).
- **RM-AUTH-16** : un service d'authentification (`core/auth/`) **ne manipule aucun jeton** : il garde dans un `signal` l'utilisateur courant (identifiant, e-mail, rôles, expiration) renvoyé par le login ou par `/me`, et expose `login()` et `logout()`.
- **RM-AUTH-17** : au démarrage de l'application (`provideAppInitializer`), `GET /api/v1/auth/me` restaure la session ; un 401 signifie « non connecté » (sans notification). Aucun intercepteur n'ajoute d'en-tête `Authorization`. Le CSRF est géré par `HttpClient` (`withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })`).
- **RM-AUTH-18** : sur toute réponse 401 d'une requête autre que le login et `/me`, l'intercepteur d'authentification vide la session et redirige vers `/login?returnUrl=<url courante>` avec une notification « Session expirée, veuillez vous reconnecter » (une seule même si plusieurs requêtes échouent simultanément). Pas d'anticipation de l'expiration : on réagit au 401. Un 403 reste géré par `errorInterceptor` (« Accès refusé ») sans déconnexion.
- **RM-AUTH-19** : une garde de route protège toutes les routes sauf `/login`. Un visiteur non connecté est redirigé vers `/login` avec `returnUrl`. Un utilisateur déjà connecté qui va sur `/login` est redirigé vers `/`.
- **RM-AUTH-20** : l'en-tête affiche, quand l'utilisateur est connecté, son e-mail, un badge par rôle (`p-tag` : « Bibliothécaire » / « Membre ») et un bouton « Se déconnecter » ; rien de tout cela sur l'écran de connexion. La déconnexion appelle `POST /api/v1/auth/logout`, vide la session (même si l'appel échoue) puis redirige vers `/login`.
- **RM-AUTH-21** : rien concernant l'authentification n'est stocké dans `localStorage` ni `sessionStorage` ; la session survit au rechargement grâce au cookie et à `/me`.
- **RM-AUTH-22** : les masquages d'interface liés aux rôles sont un confort ; la sécurité repose uniquement sur le backend.

## Critères d'acceptation (Gherkin : Étant donné / Quand / Alors)

### Contrat
`POST /api/v1/auth/login` (public, sans CSRF), corps JSON :
```
{ "email": "alice@example.org", "password": "..." }
```
Succès 200 : en-têtes `Set-Cookie: ACCESS_TOKEN=<jwt>; HttpOnly; Secure; SameSite=Strict; Path=/api; Max-Age=3600` (+ cookie `XSRF-TOKEN`), corps :
`{ "userId": "<uuid>", "email": "alice@example.org", "roles": ["MEMBER"], "expiresAt": "<ISO-8601 UTC>" }` — **sans jeton**.

`GET /api/v1/auth/me` (authentifié) : 200 avec le même corps que le login ; 401 sinon.

`POST /api/v1/auth/logout` (public, CSRF) : 204, `Set-Cookie: ACCESS_TOKEN=; HttpOnly; Secure; SameSite=Strict; Path=/api; Max-Age=0`.

Erreurs : `ProblemDetail` (RFC 9457) avec `code` : `INVALID_CREDENTIALS` (401), `VALIDATION_FAILED` (400, `errors[]` avec `field` et `code` parmi `REQUIRED`, `BLANK`, `INVALID_FORMAT`, `TOO_LONG`). Le corps d'un échec ne contient aucune information sur l'existence du compte.

### Backend
```gherkin
Scénario: Connexion nominale d'un membre
  Étant donné un utilisateur actif "alice@example.org" avec le rôle MEMBER et le mot de passe "S3cret!pass-long"
  Quand il envoie POST /api/v1/auth/login avec ces identifiants
  Alors la réponse a le statut 200
  Et un cookie ACCESS_TOKEN HttpOnly, Secure, SameSite=Strict, Path=/api, Max-Age=3600 est posé
  Et le corps contient userId, email, roles ["MEMBER"] et expiresAt, mais aucun jeton
  Et le jeton du cookie est un JWT HS256 dont sub = l'UUID de l'utilisateur, email = "alice@example.org", roles = ["MEMBER"], exp = iat + 1 h

Scénario: Le cookie authentifie les requêtes suivantes
  Étant donné un membre connecté (cookie ACCESS_TOKEN)
  Quand il appelle GET /api/v1/auth/me avec ce cookie
  Alors la réponse a le statut 200 avec son e-mail et ses rôles

Scénario: Connexion d'un bibliothécaire donnant accès à la création de livre
  Étant donné un utilisateur actif avec le rôle LIBRARIAN connecté par cookie
  Quand il envoie POST /api/v1/books avec le cookie, l'en-tête X-XSRF-TOKEN valide et un livre valide
  Alors la création est acceptée (201)

Scénario: CSRF exigé pour une requête modifiante authentifiée par cookie
  Étant donné un bibliothécaire connecté par cookie
  Quand il envoie POST /api/v1/books avec le cookie mais sans en-tête X-XSRF-TOKEN
  Alors la réponse a le statut 403

Scénario: Requête Bearer dispensée de CSRF
  Quand un client envoie POST /api/v1/books avec Authorization: Bearer <jwt LIBRARIAN> et sans X-XSRF-TOKEN
  Alors la création est acceptée (201)

Scénario: Le token d'un membre ne donne pas les droits de bibliothécaire
  Étant donné un membre authentifié
  Quand il envoie POST /api/v1/books
  Alors la réponse a le statut 403

Scénario: Rôles cumulés
  Étant donné un utilisateur avec les rôles MEMBER et LIBRARIAN
  Quand il se connecte
  Alors roles contient MEMBER et LIBRARIAN dans le corps et dans le JWT

Scénario: Insensibilité à la casse et aux espaces de l'e-mail
  Étant donné un utilisateur "alice@example.org"
  Quand il se connecte avec "  Alice@Example.ORG  " et le bon mot de passe
  Alors la réponse a le statut 200

Scénario: Unicité de l'e-mail insensible à la casse
  Étant donné un utilisateur "alice@example.org" en base
  Quand on tente d'enregistrer un utilisateur "ALICE@example.org"
  Alors l'enregistrement est refusé (contrainte d'unicité)

Scénario: Mot de passe erroné
  Étant donné un utilisateur actif "alice@example.org"
  Quand il se connecte avec un mauvais mot de passe
  Alors la réponse a le statut 401 et le code INVALID_CREDENTIALS
  Et aucun cookie ACCESS_TOKEN n'est posé

Scénario: E-mail inconnu, réponse identique et BCrypt exécuté
  Quand quelqu'un se connecte avec un e-mail qui n'existe pas
  Alors la réponse a le statut 401, le code INVALID_CREDENTIALS et le même message que pour un mauvais mot de passe
  Et une vérification BCrypt a bien été exécutée

Scénario: Compte désactivé
  Étant donné un utilisateur désactivé avec le bon mot de passe
  Quand il se connecte
  Alors la réponse a le statut 401 et le code INVALID_CREDENTIALS

Scénario: Mot de passe de plus de 72 octets au login
  Quand on se connecte avec un mot de passe de 73 octets
  Alors la réponse a le statut 401 et le code INVALID_CREDENTIALS

Scénario Outline: Requête invalide
  Quand on envoie POST /api/v1/auth/login avec <corps>
  Alors la réponse a le statut 400, le code VALIDATION_FAILED et errors[] désigne <champ>
  Exemples: e-mail absent ; e-mail vide ; e-mail "pas-un-email" ; mot de passe absent ; mot de passe vide ; e-mail de 300 caractères

Scénario: Mot de passe stocké haché
  Étant donné un utilisateur créé avec le mot de passe "S3cret!pass-long"
  Alors la valeur stockée en base commence par "$2" (BCrypt) et ne contient pas "S3cret!pass-long"

Scénario: /me sans authentification
  Quand on appelle GET /api/v1/auth/me sans cookie ni en-tête
  Alors la réponse a le statut 401

Scénario: Déconnexion
  Étant donné un utilisateur connecté par cookie
  Quand il envoie POST /api/v1/auth/logout avec un X-XSRF-TOKEN valide
  Alors la réponse a le statut 204 et le cookie ACCESS_TOKEN est renvoyé vide avec Max-Age=0 et les mêmes attributs

Scénario: Token expiré
  Étant donné un JWT dont exp est dans le passé
  Quand il est présenté à GET /api/v1/auth/me (en-tête ou cookie)
  Alors la réponse a le statut 401

Scénario: Routes publiques et routes protégées
  Quand on appelle POST /api/v1/auth/login sans authentification
  Alors la route n'est pas rejetée par la sécurité (200 ou 401 INVALID_CREDENTIALS, jamais le 401 du resource server)
  Et GET /api/v1/books sans authentification reste 401

Scénario: Création du premier bibliothécaire au démarrage
  Étant donné aucun utilisateur LIBRARIAN en base
  Et BOOTSTRAP_LIBRARIAN_EMAIL = " Admin@Example.org " et BOOTSTRAP_LIBRARIAN_PASSWORD d'au moins 12 caractères
  Quand l'application démarre
  Alors un utilisateur "admin@example.org", actif, avec le seul rôle LIBRARIAN et un mot de passe haché BCrypt existe
  Et il peut se connecter via POST /api/v1/auth/login

Scénario: Amorçage idempotent
  Étant donné un bibliothécaire déjà présent
  Quand l'application redémarre avec des variables d'amorçage différentes
  Alors aucun utilisateur n'est créé ni modifié

Scénario: Amorçage sans variables
  Étant donné aucun bibliothécaire et variables d'amorçage absentes
  Quand l'application démarre
  Alors aucun utilisateur n'est créé, un WARN est journalisé et l'application démarre

Scénario: Amorçage avec mot de passe trop court
  Étant donné aucun bibliothécaire et un mot de passe d'amorçage de 11 caractères
  Quand l'application démarre
  Alors aucun utilisateur n'est créé, un WARN est journalisé, l'application démarre et le mot de passe n'apparaît dans aucun log

Scénario: Amorçage avec e-mail existant sans le rôle LIBRARIAN
  Étant donné aucun bibliothécaire et un utilisateur MEMBER "admin@example.org"
  Et BOOTSTRAP_LIBRARIAN_EMAIL = "admin@example.org"
  Quand l'application démarre
  Alors l'utilisateur existant n'est pas modifié, aucun utilisateur n'est créé et un WARN est journalisé

Scénario: /dev/token uniquement en profil local
  Étant donné le profil "local" actif
  Alors POST /dev/token renvoie un token comme aujourd'hui
  Étant donné un autre profil (par défaut)
  Alors POST /dev/token ne renvoie pas de token (404 ou 401)
```

### Frontend
```gherkin
Scénario: Restauration de session au démarrage
  Étant donné un cookie de session valide
  Quand l'application démarre
  Alors GET /api/v1/auth/me est appelé et l'utilisateur est connecté sans repasser par /login

Scénario: Démarrage sans session
  Étant donné GET /api/v1/auth/me répond 401
  Quand l'application démarre puis ouvre "/"
  Alors aucune notification n'est affichée et il est redirigé vers /login?returnUrl=%2F

Scénario: Connexion réussie
  Étant donné l'écran /login
  Quand l'utilisateur saisit un e-mail et un mot de passe valides et clique sur « Se connecter »
  Alors il est redirigé vers la page demandée (ou "/")
  Et l'en-tête affiche son e-mail, le badge de son rôle et le bouton « Se déconnecter »
  Et aucun jeton n'est écrit dans localStorage ni sessionStorage, aucun en-tête Authorization n'est ajouté

Scénario: Afficher le mot de passe
  Quand l'utilisateur clique sur l'icône d'affichage du champ mot de passe
  Alors le mot de passe saisi devient lisible, puis masqué au clic suivant

Scénario: Identifiants invalides
  Quand le backend répond 401 INVALID_CREDENTIALS
  Alors le message « E-mail ou mot de passe incorrect » s'affiche dans le formulaire, l'e-mail reste saisi, le mot de passe est vidé et l'utilisateur reste sur /login

Scénario: Formulaire incomplet
  Quand l'e-mail ou le mot de passe est vide
  Alors le bouton « Se connecter » est inactif et aucun appel n'est émis

Scénario: Double clic
  Quand l'utilisateur clique deux fois rapidement
  Alors un seul appel de login est émis

Scénario: Session expirée pendant la navigation
  Étant donné un utilisateur connecté
  Quand une requête /api (hors login et /me) reçoit 401
  Alors la session est vidée, une notification « Session expirée » est affichée une seule fois et l'utilisateur est redirigé vers /login?returnUrl=<page courante>

Scénario: Déconnexion
  Étant donné un utilisateur connecté
  Quand il clique sur « Se déconnecter »
  Alors POST /api/v1/auth/logout est appelé, l'en-tête n'affiche plus l'utilisateur et il est redirigé vers /login
  Et un retour navigateur ne donne pas accès à une page protégée

Scénario: Utilisateur connecté sur /login
  Quand un utilisateur connecté ouvre /login
  Alors il est redirigé vers "/"

Scénario: returnUrl malveillant
  Quand returnUrl vaut une URL absolue externe (ex. https://evil.example ou //evil.example)
  Alors, après connexion, la redirection va vers "/"
```

## Données (entités, champs, contraintes)
Module `identity`. Migration Flyway `V2__create_identity_tables.sql` (nom indicatif ; `ddl-auto: validate` impose la migration).

Table `app_user` :

| Champ | Type | Contraintes |
|---|---|---|
| `id` | UUID | clé primaire, généré par l'application |
| `email` | VARCHAR(254) | NOT NULL, forme normalisée ; index unique sur `lower(email)` |
| `password_hash` | VARCHAR(100) | NOT NULL, hash BCrypt (60 caractères) |
| `enabled` | BOOLEAN | NOT NULL, défaut true |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |

Table `user_roles` : `user_id` (UUID, FK -> `app_user.id`, ON DELETE CASCADE), `role` VARCHAR(20) (CHECK `role IN ('LIBRARIAN','MEMBER')`), clé primaire composite `(user_id, role)`. Au moins un rôle par utilisateur (contrôlé par le domaine).

Configuration (aucune valeur secrète par défaut) :
- `BOOTSTRAP_LIBRARIAN_EMAIL`, `BOOTSTRAP_LIBRARIAN_PASSWORD` : variables d'environnement, à documenter dans `.env.example` et CLAUDE.md (jamais commitées avec une vraie valeur).
- `app.security.jwt.ttl` : durée du token (défaut `PT1H`).
- `app.security.jwt.cookie-name` : nom du cookie (défaut `ACCESS_TOKEN`).
- Coût BCrypt : 10 (défaut Spring), configurable.

JWT : `iss`=`library`, `sub`=UUID utilisateur, `email`, `roles`=["…"], `iat`, `exp`.

Frontend : modèle `CurrentUser { userId, email, roles, expiresAt }` ; fichiers attendus : `core/auth/` (service, intercepteur 401, garde, initialiseur, modèle), `features/auth/pages/login`, adaptation de `core/layout/header`, `app.routes.ts` et `app.config.ts` (`withXsrfConfiguration`, `provideAppInitializer`).

## Hors périmètre
- Création, modification, désactivation, suppression de comptes (aucun écran/endpoint d'administration), inscription libre, changement/réinitialisation de mot de passe, « mot de passe oublié », vérification d'e-mail. Seul le bibliothécaire d'amorçage est créé par cette feature ; les tests créent leurs utilisateurs directement en base.
- Refresh token, révocation de token, liste noire de tokens.
- Limitation des tentatives / verrouillage de compte : **à tracer dans une issue dédiée**.
- Authentification multi-facteur, SSO / OAuth2 externe / Keycloak.
- Le module `loan` et le lien membre/emprunts, hors du fait que `sub` (UUID utilisateur) sera l'identifiant de membre.
- Accès anonyme éventuel à `GET /api/v1/books` : décidé dans la feature Catalogue.
- Profil `mcp-local` et endpoint `/mcp` (inchangés).
- CORS (même origine : proxy Angular en développement, reverse proxy en production).
- Pages métier protégées autres que l'accueil existant.

## Décisions (validées le 2026-11-20)
1. **Module** : nouveau module hexagonal `identity` (utilisateurs + connexion). L'architecte confirme que `ArchitectureTest` le couvre **sans le modifier**.
2. **JWT** : `sub` = UUID de l'utilisateur ; claim `email` ajoutée (affichage). `loan.member_id` sera cet UUID.
3. **Durée** : jeton valable 1 h, sans refresh token.
4. **Comptes** : création des membres hors périmètre ; le bibliothécaire d'amorçage a **le seul rôle `LIBRARIAN`**.
5. **Amorçage** : variables absentes -> WARN et démarrage ; e-mail existant sans le rôle `LIBRARIAN` -> ignoré avec WARN ; mot de passe d'amorçage d'au moins 12 caractères, jamais journalisé.
6. **Limitation des tentatives** : hors périmètre, à noter comme issue.
7. **Transport** : option `auth-token` = `cookie-httponly` (profil `top`) : cookie HttpOnly, Secure, SameSite=Strict, Path=/api ; CSRF ; jeton absent du corps de réponse.
8. **Session front** : restaurée au démarrage par `GET /api/v1/auth/me` ; réaction aux 401 (pas d'anticipation de l'expiration).
9. **Déconnexion** : `POST /api/v1/auth/logout`, qui vide le cookie.
10. **Routes publiques** : `GET /api/v1/books` sera décidé dans la feature Catalogue ; tout le reste est authentifié par défaut.
11. **Échec** : 401 `INVALID_CREDENTIALS` identique dans tous les cas (message « E-mail ou mot de passe incorrect », pas de distinction du compte désactivé) ; BCrypt exécuté même si l'e-mail est inconnu.
12. **Rôles** : cumulables (table `user_roles`).
13. **Mot de passe** : 12 caractères minimum et 72 octets maximum (à la création) ; au login, > 72 octets -> 401.
14. **Tests** : construisent leurs propres JWT (en-tête Bearer via `JwtEncoder`), sans dépendre de `/dev/token`.
15. **Interface** : e-mail + badge du rôle dans l'en-tête ; bouton afficher le mot de passe (`toggleMask`).
16. **Ajouts** : e-mail normalisé (trim + minuscules) avec unicité insensible à la casse ; indicateur `enabled` sur le compte.

Décisions de validation du plan (2026-11-20) :
17. **Logs** : identifiant seul en INFO ; e-mail masqué uniquement dans les WARN sans identifiant (RM-AUTH-11).
18. **CSRF** exigé seulement avec le cookie `ACCESS_TOKEN` et sans en-tête `Authorization` ; login CSRF = risque accepté, atténué par `SameSite=Strict` (RM-AUTH-25).
19. **`/me` avec un jeton `/dev/token`** : 200, `email` null (profil `local` uniquement) (RM-AUTH-26).
20. **401/403 de Spring Security en `ProblemDetail`** (`AuthenticationEntryPoint` + `AccessDeniedHandler`) ; `/error` accessible sans authentification (RM-AUTH-28).
21. **Limite connue** : un compte désactivé reste utilisable jusqu'à l'expiration de son jeton (1 h max), puisque `/me` ne lit pas la base.
22. **`JWT_SECRET`** (valeur par défaut dans `application.yml`) : corrigé dans une PR séparée, après cette feature. **Réglé** par `fix/secrets-sans-valeur-par-defaut` : plus aucune valeur par défaut pour `JWT_SECRET`, `DB_USER` ni `DB_PASSWORD` ; démarrage refusé, avec la liste des variables manquantes, si l'une est absente ou vide.
