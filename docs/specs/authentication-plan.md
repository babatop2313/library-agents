# Plan technique : authentification (e-mail / mot de passe, JWT en cookie HttpOnly)

Spec : `docs/specs/authentication.md`. Architecture : **hexagonal** (référence `<kit>/skills/architecture/references/hexagonal/REFERENCE.md`).
Conventions : profil `top` — `mapping`=manuel, `lombok`=hors-domaine, `errors`=problem-detail, `ids`=uuid, `auth-token`=cookie-httponly, `front-ui`=primeng-bootstrap, `front-forms`=reactive.
Plan validé le 2026-11-20.

## 0. Décisions de validation du plan (priment sur le reste du document)
1. **Logs** : succès et événements avec utilisateur connu → **identifiant (userId) seul en INFO**, jamais d'e-mail. L'e-mail n'apparaît que **masqué** (`a***@example.org`) et **uniquement dans les WARN qui n'ont pas d'identifiant** (échec de login, amorçage `EMAIL_TAKEN_BY_NON_LIBRARIAN`). Jamais de mot de passe ni de jeton. (Ajuste RM-AUTH-11.)
2. **CSRF** exigé seulement si : méthode non sûre **ET** cookie `ACCESS_TOKEN` présent **ET** pas d'en-tête `Authorization`. Le login est dispensé de CSRF : **risque accepté (login CSRF), atténué par `SameSite=Strict`**.
3. **`/me` avec un jeton `/dev/token`** (profil `local` uniquement, seul cas où la claim `email` manque) : 200, `email: null`, `userId` = `sub` brut.
4. **Retenu** : les 401 et 403 produits par Spring Security sont renvoyés en `ProblemDetail` (`AuthenticationEntryPoint` + `AccessDeniedHandler` dans `config/security`), avec `code` (`UNAUTHENTICATED` / `ACCESS_DENIED`), comme le reste de l'API. Le 401 `INVALID_CREDENTIALS` du login reste produit par `IdentityExceptionHandler`.
5. **`/error`** accessible sans authentification (`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`).
6. Laissés de côté : régénération du jeton CSRF après login, sortie de BCrypt de la transaction du login.
7. **Limite connue** : un compte désactivé reste utilisable jusqu'à l'expiration de son jeton (1 h max), puisque `/me` et les autres routes ne lisent pas la base.
8. **`JWT_SECRET`** a une valeur par défaut dans `application.yml` (contraire au socle) : corrigé dans une PR séparée, après cette feature. Ne pas y toucher ici. **Réglé** par `fix/secrets-sans-valeur-par-defaut` (`JWT_SECRET`, `DB_USER`, `DB_PASSWORD` sans valeur par défaut, démarrage refusé si absents).

## 1. Architecture et ArchitectureTest
**Confirmé : `ArchitectureTest` couvre le module `identity` sans aucun changement.**
- Règles de couche génériques (`..domain..`, `..application..`, `..adapter.in..`, `..adapter.out..`) : s'appliquent à `com.example.library.identity.*`.
- `modules_talk_only_through_their_api` : `identity` est une tranche ; les dépendances vers `config..`, `shared..`, `*.api..` sont ignorées, ainsi que celles qui partent de `config..`. `identity` peut utiliser `config.security.JwtProperties`, `JwtClaims`, `shared.UseCase`.
- `identity` n'importe rien de `catalog` : copie locale de `MaxTrimmedLength` et de la construction de `VALIDATION_FAILED` (voulu).
- Pas de package `identity.api`.
- Contraintes : `identity.domain` n'importe jamais `config.security.JwtClaims` (seuls les adapters) ; aucune classe de `config.security` n'importe `identity`.
- `CacheArchitectureTest` non concerné.
- Aucune nouvelle intégration : BCrypt (`spring-security-crypto`) et Nimbus (`JwtEncoder`/`JwtDecoder`) sont déjà présents.

## 2. Découpage par couche
Racine : `backend/src/main/java/com/example/library/`.

### 2.1 `identity/domain` (Java pur)
**`model/`**
- `UserId` : `record UserId(UUID value)`, `newId()`.
- `Email` : `Email.of(String raw)` → trim + minuscules, format `^[^\s@]+@[^\s@]+\.[^\s@]+$`, max 254 après trim, sinon `InvalidEmailException`. `static hasValidFormat(String)`, `static isTooLong(String)` réutilisés par les validateurs web.
- `Role` : `enum { LIBRARIAN, MEMBER }`, `fromClaim(String)`.
- `PasswordHash` : `record(String value)` non vide, `toString()` masqué.
- `User` : agrégat sans setter — `UserId id`, `Email email`, `PasswordHash passwordHash`, `boolean enabled`, `Set<Role> roles` (copie immuable d'`EnumSet`), `Instant createdAt`. `register(Email, PasswordHash, Set<Role>, Instant)` (enabled=true, rôles non vides sinon `InvalidUserException`) ; `restore(...)` ; `canLogIn()` ; `hasRole(Role)`.
- `AccessToken` : `record(String value, Instant expiresAt)`, `toString()` masqué (partagé par `port.in` et `port.out`).
- `BootstrapOutcome` : `enum { CREATED, LIBRARIAN_ALREADY_PRESENT, MISSING_CONFIGURATION, INVALID_EMAIL, WEAK_PASSWORD, EMAIL_TAKEN_BY_NON_LIBRARIAN }`.

**`exception/`**
- `DomainException` (propre au module, `code` stable, sans HTTP).
- `InvalidCredentialsException` : `INVALID_CREDENTIALS`, « E-mail ou mot de passe incorrect », même message dans tous les cas.
- `InvalidEmailException` : `INVALID_EMAIL`, sans la valeur saisie.
- `PasswordPolicyViolationException` : `WEAK_PASSWORD`, motif `TOO_SHORT`/`TOO_LONG`, jamais la valeur ni sa longueur.
- `InvalidUserException` : `EMPTY_ROLES`.

**`service/`**
- `PasswordPolicy` : `checkNewPassword(raw)` ≥ 12 caractères (`codePointCount`) et ≤ 72 octets UTF-8, jamais de troncature ; `isVerifiable(raw)` non vide et ≤ 72 octets, pas de minimum.
- `LoginPolicy` : `User authenticate(Optional<User>, String rawPassword, boolean hashMatches)` → `InvalidCredentialsException` si absent, non vérifiable, `!hashMatches` ou `!canLogIn()`. Pure ; BCrypt déjà exécuté par le use case.
- `LibrarianBootstrapPolicy`, dans l'ordre : bibliothécaire existant → `LIBRARIAN_ALREADY_PRESENT` ; variable absente/vide → `MISSING_CONFIGURATION` ; e-mail invalide → `INVALID_EMAIL` ; mot de passe hors politique → `WEAK_PASSWORD` ; e-mail pris par un non-bibliothécaire → `EMAIL_TAKEN_BY_NON_LIBRARIAN` ; sinon `CREATED` avec `User.register(email, hash, Set.of(LIBRARIAN), now)`.

### 2.2 `identity/application`
**`port/in/`**
- `LoginUseCase` : `LoginResult login(LoginCommand)` ; `LoginCommand(String email, String password)` (`toString()` masqué) ; `LoginResult(UserId, Email, Set<Role>, AccessToken)`.
- `GetCurrentUserUseCase` : `CurrentUser current(GetCurrentUserQuery)` ; `GetCurrentUserQuery(String subject, String email, List<String> roles, Instant expiresAt)` ; `CurrentUser(String userId, String email /* nullable */, Set<Role> roles, Instant expiresAt)`.
- `BootstrapLibrarianUseCase` : `BootstrapOutcome bootstrap(BootstrapLibrarianCommand)` ; `BootstrapLibrarianCommand(String email, String password)` (`toString()` masqué).

**`port/out/`**
- `LoadUserByEmailPort` : `Optional<User> loadByEmail(Email)`.
- `CheckLibrarianExistsPort` : `boolean librarianExists()`.
- `SaveUserPort` : `void save(User)`.
- `PasswordHasher` : `hash(raw)`, `matches(raw, hash)` (ne lève jamais, `false` après vraie comparaison si entrée non comparable), `dummyHash()`.
- `AccessTokenIssuer` : `AccessToken issue(User, Instant issuedAt)`.

**`usecase/`** (`@UseCase`, injection par constructeur, `Clock` injecté)
- `LoginService` :
  ```java
  Email email = Email.of(cmd.email());
  Optional<User> user = loadUserByEmailPort.loadByEmail(email);
  PasswordHash reference = user.map(User::passwordHash).orElseGet(passwordHasher::dummyHash);
  boolean matches = passwordHasher.matches(cmd.password(), reference);   // TOUJOURS exécuté
  User authenticated = loginPolicy.authenticate(user, cmd.password(), matches);
  AccessToken token = accessTokenIssuer.issue(authenticated, Instant.now(clock));
  ```
  INFO `userId` en succès (sans e-mail) ; sur `InvalidCredentialsException` WARN avec e-mail masqué (sans identifiant), puis relance. Utilitaire de masquage local au module.
- `GetCurrentUserService` : claims → `CurrentUser` (rôles inconnus ignorés, e-mail facultatif), sans base.
- `BootstrapLibrarianService` : `librarianExists`, pré-vérification politique, `Email.of`, `loadByEmail`, décision, puis seulement si `CREATED` : `hash` + `save`. Renvoie l'outcome, ne journalise rien.

### 2.3 `identity/adapter/out`
**`persistence/`**
- `UserJpaEntity` : `@Table(name="app_user")` — `id UUID`, `email` (254), `passwordHash` (`password_hash`, 100), `enabled`, `createdAt Instant` ; `roles` : `@ElementCollection(fetch=EAGER)`, `@CollectionTable(name="user_roles", joinColumns=@JoinColumn(name="user_id"))`, `@Column(name="role", length=20)`, `@Enumerated(STRING)`.
- `UserJpaRepository` : `findByNormalizedEmail` (`where lower(u.email) = :email`), `existsWithRole(Role)` (`count(u) > 0 ... join u.roles r where r = :role`).
- `UserPersistenceMapper` (manuel, `User.restore`), `UserPersistenceAdapter` (package-private, implémente les 3 ports).

**`crypto/BCryptPasswordHasher`** : `new BCryptPasswordEncoder(strength)`, `@Value("${app.security.bcrypt.strength:10}")` ; `dummyHash` calculé une fois au démarrage (même coût) ; si > 72 octets : `encoder.matches("<constante>", dummyHash)` puis `false` (évite l'`IllegalArgumentException`, même temps).

**`token/JwtAccessTokenIssuer`** : bean `JwtEncoder` existant, `JwtProperties.ttl` ; HS256 ; claims `iss=library`, `sub=userId`, `email`, `roles` (`JwtClaims.ROLES`), `iat`, `exp = iat + ttl`.

### 2.4 `identity/adapter/in`
**`web/`** (package-private)
- `AuthController` `@RequestMapping("/api/v1/auth")` :
  - `POST /login` : `@Valid LoginRequest` → `200` + `Set-Cookie` (`AccessTokenCookies.issue`) + `AuthenticatedUserResponse`.
  - `GET /me` : `@AuthenticationPrincipal Jwt` → `GetCurrentUserUseCase` → `AuthenticatedUserResponse`.
  - `POST /logout` : `204` + `AccessTokenCookies.clear()`, sans use case (pur transport HTTP, choix assumé).
- `LoginRequest(email, password)` : email `@NotNull @NotBlank @MaxTrimmedLength(254) @ValidEmailFormat` (renvoie `true` si trop long → une seule erreur) ; password `@NotNull @NotEmpty` (BLANK), pas de `@NotBlank`, pas de `@Size`.
- `AuthenticatedUserResponse(String userId, String email, List<String> roles, Instant expiresAt)` — aucun champ jeton.
- `AuthWebMapper` (manuel, rôles triés).
- `AccessTokenCookies` (`@Component`) : `ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Strict").path("/api").maxAge(ttl)` ; `clear()` = valeur vide, `maxAge(0)`.
- `ValidEmailFormat`/`EmailFormatValidator`, `MaxTrimmedLength`/`MaxTrimmedLengthValidator`, `ApiErrorCode { REQUIRED, BLANK, INVALID_FORMAT, TOO_LONG }`.
- `IdentityExceptionHandler` `@RestControllerAdvice(basePackageClasses = AuthController.class)`, `@Order(HIGHEST_PRECEDENCE)` : `MethodArgumentNotValidException`/`HttpMessageNotReadableException` → 400 `VALIDATION_FAILED` + `errors[{field, code, message}]` ; `InvalidCredentialsException` → 401, title « Identifiants invalides », detail « E-mail ou mot de passe incorrect », code `INVALID_CREDENTIALS`, pas de cookie ; `InvalidEmailException` → 400 `VALIDATION_FAILED` sur `email`.

**`startup/LibrarianBootstrapRunner`** (`ApplicationRunner`, n'appelle que `BootstrapLibrarianUseCase`, dans un `try`, ne fait jamais échouer le démarrage). Messages figés, sans mot de passe, jamais `command.toString()` :
- `CREATED` → INFO « Bibliothécaire d'amorçage créé : userId=… » ;
- `LIBRARIAN_ALREADY_PRESENT` → DEBUG ;
- `MISSING_CONFIGURATION` → WARN « BOOTSTRAP_LIBRARIAN_EMAIL ou BOOTSTRAP_LIBRARIAN_PASSWORD absent : aucun bibliothécaire créé » ;
- `INVALID_EMAIL`, `WEAK_PASSWORD` → WARN avec motif, sans valeur ni longueur ;
- `EMAIL_TAKEN_BY_NON_LIBRARIAN` → WARN avec e-mail masqué ;
- `DataIntegrityViolationException` → WARN, démarrage continue.

### 2.5 `identity/config`
- `LibrarianBootstrapProperties` : `@ConfigurationProperties("app.bootstrap.librarian") record(String email, String password)`, `toString()` masqué.
- `IdentityConfig` : `@Configuration @EnableConfigurationProperties(LibrarianBootstrapProperties.class)`.

### 2.6 `config/security`
| Fichier | Statut | Contenu |
|---|---|---|
| `SecurityConfig` | modifié | voir esquisse |
| `JwtProperties` | nouveau | `@ConfigurationProperties("app.security.jwt") record(String secret, Duration ttl /*PT1H*/, String cookieName /*ACCESS_TOKEN*/)`, activé par `@EnableConfigurationProperties` sur `SecurityConfig` (les `@Import(SecurityConfig.class)` des `@WebMvcTest` existants suffisent) |
| `JwtClaims` | nouveau | `ROLES="roles"`, `EMAIL="email"` ; `SecurityConfig.ROLES_CLAIM` garde la même valeur |
| `CookieOrHeaderBearerTokenResolver` | nouveau | `null` pour `POST /api/v1/auth/login` et `/logout` ; sinon en-tête (`DefaultBearerTokenResolver`) puis cookie |
| `CookieAuthenticatedRequestMatcher` | nouveau | CSRF si méthode non sûre ET pas d'`Authorization` ET cookie `ACCESS_TOKEN` présent |
| `CsrfCookieFilter` | nouveau | `OncePerRequestFilter` (instancié par `new`), appelle `CsrfToken.getToken()` pour poser `XSRF-TOKEN` |
| `ProblemDetailAuthenticationEntryPoint` | nouveau (décision 4) | 401 `ProblemDetail`, `code` `UNAUTHENTICATED`, `Content-Type: application/problem+json` |
| `ProblemDetailAccessDeniedHandler` | nouveau (décision 4) | 403 `ProblemDetail`, `code` `ACCESS_DENIED` (rôle insuffisant ou CSRF) |
| `DevTokenController`, `McpLocalSecurityConfig` | inchangés | |

```java
http
  .csrf(csrf -> csrf
      .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
      .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
      .requireCsrfProtectionMatcher(new CookieAuthenticatedRequestMatcher(props.cookieName()))
      .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/login")))
  .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
  .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
  .exceptionHandling(e -> e
      .authenticationEntryPoint(problemDetailEntryPoint)
      .accessDeniedHandler(problemDetailAccessDeniedHandler))
  .authorizeHttpRequests(auth -> {
      auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()                 // décision 5
          .requestMatchers("/actuator/health", "/actuator/info").permitAll()
          .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout").permitAll();
      if (environment.acceptsProfiles(Profiles.of("local"))) {
          auth.requestMatchers(HttpMethod.POST, "/dev/token").permitAll();           // RM-AUTH-12
      }
      auth.requestMatchers(HttpMethod.POST, "/api/v1/books").hasRole("LIBRARIAN")
          .anyRequest().authenticated();
  })
  .oauth2ResourceServer(o -> o
      .bearerTokenResolver(new CookieOrHeaderBearerTokenResolver(props.cookieName()))
      .authenticationEntryPoint(problemDetailEntryPoint)
      .accessDeniedHandler(problemDetailAccessDeniedHandler)
      .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
```
Hors profil `local`, `POST /dev/token` → 401 (sans jeton) ou 404 (avec jeton).
Vérifier que les tests existants (`BookControllerTest`, `McpSecurityTest`, `McpLocalSecurityTest`) qui attendent 401/403 restent verts avec un corps `ProblemDetail` (ils vérifient le statut) ; s'assurer que `McpLocalSecurityConfig` n'est pas impacté.

### 2.7 `application.yml`
```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET:...}      # existant, NE PAS MODIFIER (PR séparée) — devenu ${JWT_SECRET} depuis fix/secrets-sans-valeur-par-defaut
      ttl: ${JWT_TTL:PT1H}
      cookie-name: ACCESS_TOKEN
    bcrypt:
      strength: 10
  bootstrap:
    librarian:
      email: ${BOOTSTRAP_LIBRARIAN_EMAIL:}
      password: ${BOOTSTRAP_LIBRARIAN_PASSWORD:}
```

## 3. Contrat d'API
| Méthode | URL | Accès | Request | Response | Codes |
|---|---|---|---|---|---|
| POST | `/api/v1/auth/login` | public, sans CSRF | `{ "email": string ≤254, "password": string non vide }` | 200 `{ userId, email, roles, expiresAt }` **sans jeton** ; `Set-Cookie: ACCESS_TOKEN=<jwt>; Path=/api; Max-Age=3600; Secure; HttpOnly; SameSite=Strict` + `XSRF-TOKEN` | 200, 400 `VALIDATION_FAILED`, 401 `INVALID_CREDENTIALS` |
| GET | `/api/v1/auth/me` | authentifié (en-tête ou cookie) | — | 200, même corps (lu dans le JWT ; `email` null pour un jeton `/dev/token`) | 200, 401 `UNAUTHENTICATED` |
| POST | `/api/v1/auth/logout` | public ; CSRF si cookie présent | — | 204 ; `Set-Cookie: ACCESS_TOKEN=; Path=/api; Max-Age=0; Secure; HttpOnly; SameSite=Strict` | 204, 403 `ACCESS_DENIED` (cookie sans XSRF) |
| POST | `/api/v1/books` | LIBRARIAN | inchangé | inchangé | 201, 401, 403 |
| POST | `/dev/token` | profil `local` | inchangé | inchangé | 200 local ; 401/404 sinon |

Erreurs `ProblemDetail` (RFC 9457) :
```json
{ "type":"about:blank", "title":"Identifiants invalides", "status":401,
  "detail":"E-mail ou mot de passe incorrect", "code":"INVALID_CREDENTIALS" }
{ "title":"Validation échouée", "status":400, "detail":"La requête est invalide", "code":"VALIDATION_FAILED",
  "errors":[{ "field":"email", "code":"INVALID_FORMAT", "message":"Le champ 'email' a un format invalide" }] }
{ "title":"Authentification requise", "status":401, "code":"UNAUTHENTICATED" }
{ "title":"Accès refusé", "status":403, "code":"ACCESS_DENIED" }
```
Pas de springdoc ajouté.

## 4. Migration `V2__create_identity_tables.sql`
```sql
CREATE TABLE app_user (
    id            UUID                     NOT NULL,
    email         VARCHAR(254)             NOT NULL,
    password_hash VARCHAR(100)             NOT NULL,
    enabled       BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT ck_app_user_email_not_blank CHECK (length(btrim(email)) > 0)
);
CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user (lower(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL,
    role    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_roles_role CHECK (role IN ('LIBRARIAN', 'MEMBER'))
);
CREATE INDEX ix_user_roles_role ON user_roles (role);
```
Aucune donnée insérée. Pas de `CHECK email = lower(btrim(email))` (masquerait le test d'unicité).

## 5. Frontend (`library-web/src/app/`)
Angular 21 standalone, OnPush, signaux, `inject()`, `@if`/`@for`, PrimeNG + grille Bootstrap, formulaires réactifs typés.

**`core/auth/`**
- `current-user.model.ts` : `type Role = 'LIBRARIAN' | 'MEMBER'` ; `CurrentUser { userId; email: string | null; roles: Role[]; expiresAt }` ; `LoginRequest { email; password }`.
- `auth.service.ts` (root) : `state = signal<CurrentUser | null>(null)`, `user`, `isAuthenticated`, `hasRole` ; `login()` POST + `tap(set)` ; `restoreSession(): Promise<void>` GET `/me`, résout toujours ; `logout()` POST, `catchError → of(undefined)`, `finalize(clear)` ; `expireSession(): boolean` (true seulement si état non nul → une seule notification). Aucun jeton, aucun stockage.
- `auth.initializer.ts` : `provideAuthInitializer()` = `provideAppInitializer(() => inject(AuthService).restoreSession())`.
- `auth.interceptor.ts` : 401 hors `/api/v1/auth/login` et `/api/v1/auth/me` → si `expireSession()` : toast « Session expirée, veuillez vous reconnecter » + `navigate(['/login'], { queryParams: { returnUrl: router.url } })` ; erreur propagée ; aucun en-tête `Authorization` ; 403 ignoré.
- `auth.guard.ts` : `authGuard` (`canActivateChild`) → `createUrlTree(['/login'], { queryParams: { returnUrl } })` ; `guestGuard` → `/`.
- `return-url.ts` : `safeReturnUrl(raw)` accepte seulement `/…`, ni `//` ni `/\`, sinon `/`.
- `role-label.ts` : `LIBRARIAN → « Bibliothécaire »`, `MEMBER → « Membre »` + sévérité `p-tag`.

**`features/auth/pages/login/`** (`login.ts/html/scss`, classe `Login`)
- `NonNullableFormBuilder` : email `required` + validateur local (trim, même regex, max 254) ; password `required`.
- `submitting`, `invalidCredentials`, `formValid` (`toSignal(statusChanges)`).
- Gabarit : `pInputText` (`autocomplete="username"`) ; `<p-password formControlName="password" [toggleMask]="true" [feedback]="false" inputId="password" autocomplete="current-password">` ; `<p-button type="submit" label="Se connecter" [disabled]="!formValid() || submitting()" [loading]="submitting()">` ; `<p-message severity="error">` sur 401 ; messages de validation en français après `touched`.
- `submit()` : garde double soumission ; succès → `navigateByUrl(safeReturnUrl(returnUrl), { replaceUrl: true })` ; 401 → message, `password.reset()`, e-mail conservé, pas de toast ; 400 `errors[]` → `setErrors({ server })`.

**Modifications**
- `app.routes.ts` : `login` (`guestGuard`, lazy) ; `''` avec `canActivateChild: [authGuard]` et enfants (home) ; `**` → `''`.
- `app.config.ts` : `provideHttpClient(withInterceptors([authInterceptor, errorInterceptor]), withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }))` + `provideAuthInitializer()`.
- `core/layout/header/` : `header.html` (nouveau) — e-mail (libellé de repli si `email` null), `p-tag` par rôle, bouton « Se déconnecter » → `logout()` puis `navigate(['/login'], { replaceUrl: true })`.
- `core/http/error.interceptor.ts` : comportement conforme (401 ignoré, 403 « Accès refusé ») ; mettre à jour le commentaire et ajouter le cas « 401 → aucune notification » au spec. Il lit le `ProblemDetail` : vérifier la compatibilité avec les nouveaux corps 401/403.
- `app.spec.ts` : `provideHttpClient()`/`provideHttpClientTesting()` si nécessaire.

## 6. Tests par couche
Les tests forgent leurs JWT avec `JwtEncoder` (HS256, claim `roles`) via `config/security/TestJwts` (`member()`, `librarian()`, `expired()`, `signedWithOtherKey()`), jamais `/dev/token`.

| Couche | Classe | Cas |
|---|---|---|
| domain | `EmailTest` | trim/minuscules ; formats invalides ; 254 ok, 255 refusé |
| domain | `UserTest` | rôles vides refusés ; cumul ; immuable ; `canLogIn` ; `restore` |
| domain | `AccessToken`/`PasswordHash` | `toString` masqué |
| domain | `PasswordPolicyTest` | 11/12 caractères ; 72/73 octets dont multioctet ; `isVerifiable` |
| domain | `LoginPolicyTest` | 4 cas d'échec : même exception/code/message ; nominal |
| domain | `LibrarianBootstrapPolicyTest` | table de décision complète |
| application | fakes `InMemoryUserRepository`, `FakePasswordHasher` (compte `matches`), `FakeAccessTokenIssuer` | — |
| application | `LoginServiceTest` | succès ; e-mail inconnu → `matches` 1 fois avec `dummyHash` ; mauvais mdp ; désactivé ; > 72 octets ; e-mail normalisé ; logs : INFO userId sans e-mail, WARN e-mail masqué sans mdp |
| application | `GetCurrentUserServiceTest` | claims ; rôle inconnu ; e-mail absent |
| application | `BootstrapLibrarianServiceTest` | chaque outcome ; idempotence ; `hash` seulement si création ; LIBRARIAN seul ; e-mail normalisé ; `createdAt` |
| persistence | `UserPersistenceAdapterTest` (`@DataJpaTest` + Testcontainers) | aller-retour avec rôles ; `loadByEmail` ; unicité insensible à la casse ; CHECK rôle ; `librarianExists` ; cascade |
| crypto | `BCryptPasswordHasherTest` | `$2` ; `matches` ; > 72 octets → false sans exception ; `dummyHash` valide |
| token | `JwtAccessTokenIssuerTest` | HS256 ; claims ; exp = iat + 1 h ; accepté par le décodeur |
| web | `AuthControllerTest` (`@WebMvcTest`, `@Import({SecurityConfig.class, AccessTokenCookies.class})`) | 200 cookie exact, pas de jeton dans le corps, XSRF posé ; 401 sans cookie ; 6 cas 400 ; JSON illisible ; login sans CSRF ; login avec cookie expiré atteint le contrôleur ; `/me` Bearer et cookie ; `/me` 401 en `ProblemDetail` ; logout 204 ; logout cookie sans XSRF → 403 `ProblemDetail` ; logout avec XSRF → 204 |
| startup | `LibrarianBootstrapRunnerTest` | niveau de log par outcome ; mdp absent ; `DataIntegrityViolationException` interceptée |
| config | `CookieOrHeaderBearerTokenResolverTest`, `CookieAuthenticatedRequestMatcherTest`, entry point / access denied handler | priorités ; matrice ; corps `ProblemDetail` |
| intégration | `AuthenticationFlowIntegrationTest` (`@SpringBootTest` + Testcontainers) | login → cookie → `/me` ; LIBRARIAN cookie + XSRF → 201 ; cookie sans XSRF → 403 ; Bearer sans XSRF → 201 ; MEMBER → 403 ; rôles cumulés ; e-mail en majuscules → 200 ; hash `$2` ; books anonyme → 401 ; `/error` accessible |
| intégration | `LibrarianBootstrapIntegrationTest` | création + login ; idempotence ; variables absentes ; mdp 11 caractères (absent des logs) ; e-mail d'un MEMBER inchangé |
| intégration | `DevTokenProfileTest` | défaut → 401 ; `local` → 200 |
| architecture | `ArchitectureTest`, `CacheArchitectureTest` | inchangés, verts |
| non-régression | `BookControllerTest`, `McpSecurityTest`, `McpLocalSecurityTest` | inchangés, verts |

Frontend (Vitest) : `auth.service.spec`, `auth.interceptor.spec` (deux 401 → un toast), `auth.guard.spec`, `return-url.spec`, `login.spec` (toggleMask, double clic, 401, returnUrl), `header.spec`, `error.interceptor.spec` (401 silencieux).

## 7. Risques et limites connues
1. **Login CSRF** : login dispensé de CSRF — risque accepté, atténué par `SameSite=Strict` (décision 2).
2. Cookie expiré sur les routes publiques : traité par le resolver (`null` sur login/logout).
3. `XSRF-TOKEN` (cookie de session) recréé par `CsrfCookieFilter` au `/me` du démarrage.
4. `/error` en `permitAll` (décision 5).
5. BCrypt (~100 ms) dans la transaction du login : acceptable à cette échelle.
6. Énumération de comptes : réponse et temps uniformes (hash factice au même coût).
7. Force brute : pas de limitation des tentatives → **issue à créer**.
8. JWT non révocable : valide jusqu'à 1 h après la déconnexion.
9. **Compte désactivé utilisable jusqu'à l'expiration de son jeton (1 h max)**, puisque `/me` et les autres routes ne lisent pas la base (décision 7).
10. `Secure` sur `http://localhost` : ok Chrome/Firefox, à vérifier sur Safari.
11. `JWT_SECRET` avec valeur par défaut : PR séparée après cette feature (décision 8). Réglé par `fix/secrets-sans-valeur-par-defaut`.
12. `catalog` non modifié ; futur `loan` utilisera `jwt.getSubject()` ; `/mcp` non impacté (cookie `Path=/api`).
13. Regrouper les scénarios `@SpringBootTest` d'amorçage (coût de contexte).

## 8. Ordre d'implémentation
1. Domaine `identity/domain`.
2. Application (ports, use cases).
3. Adapters sortants : migration V2, persistance, `BCryptPasswordHasher`, `JwtAccessTokenIssuer` (+ `JwtProperties`, `JwtClaims` dans `config/security`).
4. Adapters entrants : web, `LibrarianBootstrapRunner`.
5. Config : `identity/config`, `config/security` (SecurityConfig, resolver, matcher, filtre CSRF, entry point, access denied handler), `application.yml`.
6. `./mvnw -q compile` à chaque étape ; tests par `test-writer`.
7. Frontend.
8. Documentation : `.env.example` (`BOOTSTRAP_LIBRARIAN_EMAIL`, `BOOTSTRAP_LIBRARIAN_PASSWORD`, `JWT_TTL`) ; CLAUDE.md (avec accord) ; issue « limitation des tentatives ».

## 9. Fichiers
**Créés (backend main, `identity/`)** : `domain/model/{UserId, Email, Role, PasswordHash, User, AccessToken, BootstrapOutcome}` ; `domain/exception/{DomainException, InvalidCredentialsException, InvalidEmailException, PasswordPolicyViolationException, InvalidUserException}` ; `domain/service/{PasswordPolicy, LoginPolicy, LibrarianBootstrapPolicy}` ; `application/port/in/{LoginUseCase, LoginCommand, LoginResult, GetCurrentUserUseCase, GetCurrentUserQuery, CurrentUser, BootstrapLibrarianUseCase, BootstrapLibrarianCommand}` ; `application/port/out/{LoadUserByEmailPort, CheckLibrarianExistsPort, SaveUserPort, PasswordHasher, AccessTokenIssuer}` ; `application/usecase/{LoginService, GetCurrentUserService, BootstrapLibrarianService}` ; `adapter/out/persistence/{UserJpaEntity, UserJpaRepository, UserPersistenceMapper, UserPersistenceAdapter}` ; `adapter/out/crypto/BCryptPasswordHasher` ; `adapter/out/token/JwtAccessTokenIssuer` ; `adapter/in/web/{AuthController, LoginRequest, AuthenticatedUserResponse, AuthWebMapper, AccessTokenCookies, IdentityExceptionHandler, ApiErrorCode, ValidEmailFormat, EmailFormatValidator, MaxTrimmedLength, MaxTrimmedLengthValidator}` ; `adapter/in/startup/LibrarianBootstrapRunner` ; `config/{LibrarianBootstrapProperties, IdentityConfig}`.
**Créés (hors module)** : `config/security/{JwtProperties, JwtClaims, CookieOrHeaderBearerTokenResolver, CookieAuthenticatedRequestMatcher, CsrfCookieFilter, ProblemDetailAuthenticationEntryPoint, ProblemDetailAccessDeniedHandler}` ; `db/migration/V2__create_identity_tables.sql` ; `.env.example`.
**Créés (frontend)** : `core/auth/*` + specs ; `features/auth/pages/login/*` ; `core/layout/header/header.html` + spec.
**Modifiés** : `SecurityConfig.java`, `application.yml` (sans toucher `JWT_SECRET`), `app.config.ts`, `app.routes.ts`, `header.ts/scss`, `error.interceptor.ts` (+ spec), `app.spec.ts` (si besoin), `CLAUDE.md` (avec accord).
**Inchangés** : `ArchitectureTest`, `CacheArchitectureTest`, `DevTokenController`, `McpLocalSecurityConfig`, `V1__create_table_book.sql`, tests existants.
