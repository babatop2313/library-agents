# Plan technique — Catalogue (liste paginée, recherche, enregistrement depuis l'interface)

> Spec : `docs/specs/catalogue.md` (décisions validées le 2026-11-23, prioritaires). Contrat : `docs/api/catalogue.md`.
> Plan validé le 2026-11-23.

## 0. Décisions de validation du plan (2026-11-23)
1. **Deux ports de sortie** : `LoadAvailableBooksPagePort` (décoré par le cache) et `CountAvailableBooksPort` (non décoré). Le use case lit le total d'abord et ne lit pas le contenu si `offset >= total`.
2. **Liaison des paramètres du GET** par le record `@ModelAttribute` `SearchBooksParams` (gestionnaire `MethodArgumentNotValidException` existant) ; `?page=` vide = valeur par défaut, pas de 400.
3. **Messages d'erreur** propres à `page`, `size`, `q` ; attribut `max` ajouté à `MaxTrimmedLength` ; messages du POST inchangés.
4. **RM-CAT-42 vérifiée automatiquement** : annotation `@AffectsAvailableBooks` sur les ports d'écriture + règle dans `CacheArchitectureTest` (jamais dans `ArchitectureTest`).
5. **GET protégé par `@PreAuthorize("hasAnyRole('LIBRARIAN','MEMBER')")`** (moindre privilège) : un JWT valide sans l'un de ces rôles reçoit 403 `ACCESS_DENIED`.
6. **Comportements d'interface** : `size` d'URL hors {10, 20, 50} → 10 ; `q` d'URL tronqué à 100 ; page d'URL au-delà de la dernière → redirection (`replaceUrl`) vers la dernière ; dialogue réinitialisé à chaque ouverture.
7. **Toast « Session expirée »** de l'`authInterceptor` sur 401 conservé (RM-CAT-39 ajustée dans la spec).
8. **Identifiants d'écran** `catalogue-list` et `catalogue-register-dialog` ; `Page<T>` dans `core/http/page.ts`.
9. **`lc_ctype` de la base locale = `French_France.1252`** (pas `C`) : `lower()` abaisse les majuscules accentuées, « ÉMILE » trouve « Émile ». Le test de persistance « ÉMILE » vérifie aussi l'image Testcontainers.

## 1. Architecture appliquée
hexagonal (`.claude/factory.json`). Référence : `<kit>/skills/architecture/references/hexagonal/REFERENCE.md`.
Intégrations : `postgresql`, `redis` (déjà dans `stack.json`). Options : `mapping=manuel`, `errors=problem-detail`, `ids=uuid`, `auth-token=cookie-httponly`, `front-ui=primeng-bootstrap`, `front-forms=reactive`.

## 2. Résumé
- Backend : nouveau `GET /api/v1/books` via un port d'entrée `SearchAvailableBooksUseCase` et deux ports de sortie (page `LoadAvailableBooksPagePort`, total `CountAvailableBooksPort`). Pagination et recherche sont des value objects du domaine ; aucun type Spring `Pageable` hors de l'adapter.
- Cache (option A) : `AvailableBooksCacheAdapter` décore aussi le port de page. Il sert la tête de liste depuis la clé `v1` existante quand `q` est absent et `page*size+size <= 20`. Le total vient toujours de la base. Ni clé ni format ne changent.
- Frontend : feature `catalogue` chargée en lazy sous `authGuard`. `p-table` paginée côté serveur, état dans l'URL, recherche avec anti-rebond et `switchMap`, `p-dialog` d'enregistrement (LIBRARIAN). Nouvelle option `HttpContext` `SKIP_ERROR_TOAST` dans `core/http`. Catalogue simulé en mode `mock`.

## 3. Migration
**Aucune migration.** Dernière migration : `V2__create_identity_tables.sql` ; `V3` n'est pas créée. La table `book` (V1) suffit. Recherche sans index (décision 4 de la spec). `pg_trgm` et `unaccent` : issue #20.

## 4. Découpage par couche (backend)
Racine : `backend/src/main/java/com/example/library/catalog/`

### 4.1 Domaine (`domain/`) — Java pur
| Classe | État | Responsabilité | Règles |
|---|---|---|---|
| `domain/model/CatalogPageRequest.java` | nouvelle | record `(int page, int size)`. Constantes `MIN_PAGE=0`, `MIN_SIZE=1`, `MAX_SIZE=50`, `DEFAULT_SIZE=10`. `long offset()` (calcul en `long`, sans débordement). `long endExclusive()` = offset + size. `static List<FieldViolation> violationsOf(Integer page, Integer size)` : absent = défaut, hors bornes = `OUT_OF_RANGE`. Le constructeur lève `InvalidCatalogSearchException` si les bornes sont violées. | RM-CAT-15 |
| `domain/model/SearchText.java` | nouvelle | record `(String value)`, `MAX_LENGTH=100`, `SearchText.NONE`. `of(String raw)` : `strip()` ; null, vide ou blanc → `NONE` ; plus de 100 caractères → exception. `violationsOf(String raw)`. `isEmpty()`. `boolean matches(Book)` : sous-chaîne littérale du titre OU de l'auteur après `toLowerCase(Locale.ROOT)` des deux côtés, sensible aux accents, sans l'ISBN. Sémantique de référence (fake, oracle des tests). | RM-CAT-17 |
| `domain/model/CatalogSearch.java` | nouvelle | record `(SearchText text, CatalogPageRequest pageRequest)`. `static of(Integer page, Integer size, String q)` réunit **toutes** les violations (page, size, q) en une passe, puis lève `InvalidCatalogSearchException`. | RM-CAT-15, 17 |
| `domain/model/AvailableBooksPage.java` | nouvelle | record `(List<Book> content, CatalogPageRequest pageRequest, long totalElements)` (copie défensive). `int totalPages()` = `ceil(total/size)`, 0 si total = 0. `static empty(request, total)`. | RM-CAT-18, 19, 20 |
| `domain/exception/InvalidCatalogSearchException.java` | nouvelle | `extends DomainException`, code `VALIDATION_FAILED`, porte `List<FieldViolation>` (champs `page`, `size`, `q`). | RM-CAT-15, 17 |
| `domain/model/Book.java` | inchangée | `isAvailable()` porte déjà RM-04 / RM-CAT-14. | RM-CAT-14 |
| `domain/service/AvailableBooksSelection.java` | inchangée | `CATALOG_ORDER` reste la définition de l'ordre ; utilisé par le fake et les tests d'équivalence. | RM-CAT-16 |
| `FieldViolation`, `ViolationCode` | réutilisés | Mêmes codes (`OUT_OF_RANGE`, `TOO_LONG`, `INVALID_FORMAT`). | — |

### 4.2 Application (`application/`)
| Classe | État | Responsabilité | Règles |
|---|---|---|---|
| `application/port/in/SearchAvailableBooksUseCase.java` | nouvelle | `AvailableBooksPage search(SearchAvailableBooksQuery query)` | RM-CAT-23 |
| `application/port/in/SearchAvailableBooksQuery.java` | nouvelle | record `(CatalogSearch search)`, `static of(Integer page, Integer size, String q)` qui délègue à `CatalogSearch.of`. | RM-CAT-15, 17 |
| `application/port/out/LoadAvailableBooksPagePort.java` | nouvelle | `List<Book> loadAvailableBooksPage(SearchText text, CatalogPageRequest pageRequest)`. Contrat (Javadoc) : livres avec `availableCopies >= 1`, filtrés selon `SearchText.matches`, ordre `CATALOG_ORDER`, à partir de `offset()`, au plus `size()`. Paramètres = types du **domaine** (un adapter sortant ne dépend jamais de `port.in`). | RM-CAT-14, 16, 17, 18 |
| `application/port/out/CountAvailableBooksPort.java` | nouvelle | `long countAvailableBooks(SearchText text)`, même filtre. | RM-CAT-18, 24 |
| `application/usecase/SearchAvailableBooksService.java` | nouvelle | `@UseCase(readOnly = true)`. `total = count(text)` ; si `total == 0` ou `offset >= total` → `AvailableBooksPage.empty(...)` sans lire le contenu ; sinon `content = loadPage(text, request)` → `new AvailableBooksPage(content, request, total)`. N'appelle aucun autre use case. | RM-CAT-18, 19, 22 |
| `ListAvailableBooksUseCase` / `ListAvailableBooksService` / `LoadAvailableBooksPort` | inchangés | Utilisés par MCP ; contrat MCP non modifié. | — |
| `application/port/out/AffectsAvailableBooks.java` | nouvelle | Annotation de marquage, JDK seul (`@Retention(RUNTIME) @Target(TYPE)`), posée sur `SaveBookPort` : tout port d'écriture qui modifie l'ensemble des livres disponibles (voir §6). | RM-CAT-42 |

### 4.3 Adapter sortant — persistance (`adapter/out/persistence/`, rôle `sortant-persistance`)
| Classe | État | Responsabilité | Règles |
|---|---|---|---|
| `BookJpaRepository.java` | modifiée | Constante `CATALOG_ORDER_BY = "ORDER BY lower(title) COLLATE \"C\", isbn COLLATE \"C\""` partagée par **toutes** les requêtes, y compris `findAvailableOrderedByTitle` (réécrite à l'identique) : garantie d'identité cache/base. Quatre requêtes natives : `findAvailablePage(limit, offset)` ; `searchAvailablePage(pattern, limit, offset)` avec `AND (lower(title) LIKE lower(:pattern) ESCAPE '\' OR lower(author) LIKE lower(:pattern) ESCAPE '\')` ; `countAvailable()` ; `countAvailableMatching(pattern)`. Méthodes séparées plutôt que `:pattern IS NULL` (typage d'un null en PostgreSQL). `OFFSET` en `long`. | RM-CAT-14, 16, 17, 18 |
| `LikePatterns.java` | nouvelle (package-private) | `containsPattern(String text)` : échappe `\` → `\\`, `%` → `\%`, `_` → `\_` (dans cet ordre), puis entoure de `%`. | RM-CAT-17 |
| `BookPersistenceAdapter.java` | modifiée | Implémente aussi `LoadAvailableBooksPagePort` et `CountAvailableBooksPort` ; branche sur `findAvailablePage` / `searchAvailablePage` selon `text.isEmpty()` ; mapping par `BookPersistenceMapper` (`Book.restore`). | RM-CAT-14 à 18 |

### 4.4 Adapter sortant — cache (`adapter/out/cache/`, rôle `sortant-cache`)
| Classe | État | Responsabilité | Règles |
|---|---|---|---|
| `AvailableBooksCacheAdapter.java` | modifiée | Implémente en plus `LoadAvailableBooksPagePort`, reste `@Primary`. Nouveau paramètre : `@Qualifier("bookPersistenceAdapter") LoadAvailableBooksPagePort persistentPageLoad` (5 dépendances, limite du socle). **Refactorisation sans changement de comportement** : extraction de `private List<Book> cachedHead()` (le `switch` Hit/Miss/Unavailable actuel sur les `CACHED_SIZE` premiers livres), réutilisée par `loadAvailableBooks`. `loadAvailableBooksPage(text, req)` : si `!text.isEmpty()` ou `req.endExclusive() > CACHED_SIZE` → `persistentPageLoad`, sans lire ni écrire le cache ; sinon `cachedHead()` puis `subList(min(offset, n), min(end, n))`. Extraction de `private void evictAfterCommit()` (corps actuel de `save()` après délégation) : **point d'extension RM-CAT-42**. `KEY`, `CachedBook`, `AvailableBooksCacheCodec`, TTL **inchangés**. `CountAvailableBooksPort` n'est **pas** décoré. | RM-CAT-22, 24, 25, 42 |

### 4.5 Adapter entrant — web (`adapter/in/web/`, rôle `entrant-http`)
| Classe | État | Responsabilité | Règles |
|---|---|---|---|
| `BookController.java` | modifiée | `@GetMapping ResponseEntity<BookPageResponse> search(@Valid @ModelAttribute SearchBooksParams params)`, injecte l'**interface** `SearchAvailableBooksUseCase`. **`@PreAuthorize("hasAnyRole('LIBRARIAN','MEMBER')")`** (décision 5). POST inchangé. | RM-CAT-13, 21, 23 |
| `SearchBooksParams.java` | nouvelle | record `(@Min(CatalogPageRequest.MIN_PAGE) Integer page, @Min(MIN_SIZE) @Max(MAX_SIZE) Integer size, @MaxTrimmedLength(max = SearchText.MAX_LENGTH) String q)`. Valeur non numérique → `FieldError` `typeMismatch` sur le bon champ → `MethodArgumentNotValidException` (déjà traitée). Paramètre inconnu ignoré. | RM-CAT-15, 17, 21 |
| `BookPageResponse.java` | nouvelle | record `(List<BookResponse> content, int page, int size, long totalElements, int totalPages)` | RM-CAT-20 |
| `BookWebMapper.java` | modifiée | `toQuery(SearchBooksParams)` → `SearchAvailableBooksQuery.of(...)` ; `toPageResponse(AvailableBooksPage)` réutilise `toResponse(Book)`. | RM-CAT-20 |
| `CatalogExceptionHandler.java` | modifiée | `@ExceptionHandler(InvalidCatalogSearchException)` → 400 `VALIDATION_FAILED` + `errors[]` (défense). Messages propres à `page`, `size`, `q` (le message `TOO_LONG` actuel indique « 255 » en dur). Messages du POST **identiques**. | RM-CAT-15, 17 |
| `MaxTrimmedLength.java` + `MaxTrimmedLengthValidator.java` | modifiées | Attribut `int max() default Book.TEXT_MAX_LENGTH`, lu dans `initialize()`. POST inchangé. | RM-CAT-17 |

### 4.6 Config
Rien à déclarer. `SecurityConfig` inchangé : GET authentifié (`anyRequest().authenticated()`), `CookieAuthenticatedRequestMatcher` exclut déjà les méthodes sûres du CSRF. Le 403 d'un `@PreAuthorize` refusé passe par l'`AccessDeniedHandler` existant (`ProblemDetail` `ACCESS_DENIED`). `RedisClientConfig` inchangé.

### 4.7 Règle de dépendance
- `domain` : JDK seul.
- `application` : `domain` + `shared.UseCase`.
- `adapter.out.persistence` / `adapter.out.cache` → `application.port.out` + `domain` ; jamais `port.in`.
- `adapter.in.web` → `application.port.in` + `domain` ; jamais `port.out` ni `usecase`.
- Redis confiné à `adapter.out.cache` et `config` (`CacheArchitectureTest`).
- `ArchitectureTest.java` non modifié.

## 5. Frontend (`library-web/src/app/`)
Écrans : pas de registre `docs/design/design.json`. Identifiants : `catalogue-list` (page) et `catalogue-register-dialog` (dialogue), à concevoir.

### 5.1 Socle `core/`
| Fichier | État | Contenu |
|---|---|---|
| `core/http/error-toast.context.ts` | nouveau | `export const SKIP_ERROR_TOAST = new HttpContextToken<ReadonlySet<number>>(() => new Set())` et `export function skipErrorToast(...statuses: number[]): HttpContext`. Option réutilisable (RM-CAT-40). |
| `core/http/error.interceptor.ts` | modifié | Pas de toast si `req.context.get(SKIP_ERROR_TOAST).has(error.status)` ; erreur **toujours propagée** ; 401 toujours sans toast ici. |
| `core/http/page.ts` | nouveau | `export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; }` |
| `core/layout/header/header.html` / `header.ts` / `header.scss` | modifiés | `<a routerLink="/catalogue" routerLinkActive="active" ariaCurrentWhenActive="page">Catalogue</a>` dans le bloc `@if (current)` ; import `RouterLinkActive` ; style d'état actif (RM-CAT-27). |
| `app.routes.ts` | modifié | Dans les enfants de `''` (déjà sous `canActivateChild: [authGuard]`) : `{ path: 'catalogue', title: 'Catalogue', loadChildren: () => import('./features/catalogue/catalogue.routes').then(m => m.CATALOGUE_ROUTES) }`. |

### 5.2 Feature `features/catalogue/`
| Fichier | Rôle |
|---|---|
| `catalogue.routes.ts` | `CATALOGUE_ROUTES = [{ path: '', loadComponent: () => import('./pages/catalogue/catalogue').then(m => m.Catalogue) }]`. Pas de `roleGuard` (dialogue). |
| `data/catalogue.model.ts` | `Book { id; isbn; title; author; totalCopies; availableCopies }`, `RegisterBookRequest { isbn; title; author; copies }`, `BookSearchParams { page: number; size: number; q?: string }` (page base 0, celle de l'API). |
| `data/catalogue.service.ts` | Seul accès HTTP. `search(p)` → `GET /api/v1/books` (`q` omis si vide). `register(body)` → `POST /api/v1/books` avec `{ context: skipErrorToast(400, 409) }`. URL relative, sans condition « mock ». |
| `pages/catalogue/catalogue-url-state.ts` | Fonctions pures : `parseCatalogueUrl(ParamMap)` (`page` base 1 ; absent, non numérique ou < 1 → 1 ; `size` ∈ {10, 20, 50} sinon 10 ; `q` rogné et tronqué à 100), `toApiParams(state)` (page − 1), `toQueryParams(state)` (omet les valeurs par défaut) (RM-CAT-31). |
| `pages/catalogue/catalogue.ts` / `.html` / `.scss` | Page routée qui orchestre. `state = toSignal(route.queryParamMap.pipe(map(parseCatalogueUrl)))` ; `combineLatest([state$, reload$.pipe(startWith(void 0))]).pipe(switchMap(...))` : annulation des appels obsolètes (RM-CAT-29), union `loading \| loaded \| error` (RM-CAT-30), « Réessayer » → `reload$.next()`. Page/taille → `router.navigate([], { queryParams })` ; nouvelle recherche → `q` et page 1 ; page au-delà de la dernière → dernière page (`replaceUrl`). `canRegister = computed(() => auth.hasRole('LIBRARIAN'))` (RM-CAT-33). Enregistrement : signal `submitting`, garde contre le double envoi ; succès : toast « Livre enregistré », dialogue fermé, `reload$.next()` avec page et recherche conservées (RM-CAT-36) ; 400/409 → `serverError` transmis au dialogue ; 403, 5xx, réseau : toast de l'intercepteur, dialogue ouvert ; 401 : `authInterceptor`. |
| `ui/book-search/book-search.ts` / `.html` | `input()` `value`, `output()` `searchChange`. `p-inputtext` libellé « Rechercher par titre ou auteur », `maxlength=100`, bouton d'effacement. Anti-rebond 300 ms + `distinctUntilChanged` sur le texte rogné ; Entrée émet immédiatement. Resynchronisation si `value` change (retour arrière). |
| `ui/book-table/book-table.ts` / `.html` | Entrées `books`, `totalElements`, `page`, `size`, `query`, `loading` ; sortie `pageChange({page, size})`. `p-table` sans tri, colonnes Titre / Auteur / ISBN / Exemplaires disponibles (« 2 / 3 », `totalCopies` toujours renvoyé, RM-CAT-32 ; libellé accessible « 2 disponibles sur 3 »). Libellé accessible du tableau (RM-CAT-41). `p-paginator` (`[first]`, `[rows]`, `[totalRecords]`, `[rowsPerPageOptions]="[10,20,50]"`), masqué si total = 0. Messages vides : « Aucun livre disponible pour le moment » / « Aucun livre ne correspond à "{{ query }}" » (interpolation échappée). |
| `ui/book-form-dialog/book-form-dialog.ts` / `.html` / `.scss` | `p-dialog` modal ; `model()` `visible`, `input()` `submitting` et `serverError: ProblemDetail \| null`, `output()` `save: RegisterBookRequest`. Formulaire réactif typé (`NonNullableFormBuilder`), réinitialisé à chaque ouverture. Erreurs serveur par `effect()` : `errors[]` → `setErrors({ server: message })` + `markAsTouched` sur le contrôle du même `field` ; `field` inconnu ou 400 sans `errors[]` / `errors: []` → message général `p-message` (RM-CAT-37) ; 409 `ISBN_ALREADY_EXISTS` → `detail` (sinon « Cet ISBN existe déjà au catalogue ») sous l'ISBN (RM-CAT-38). Erreur `server` retirée au premier `valueChanges` du champ ; focus sur le premier champ en erreur ; `aria-describedby`. Bouton « Enregistrer » `[loading]`/`[disabled]="submitting()"`. |
| `ui/book-form-dialog/book-form-validators.ts` | `notBlank`, `maxTrimmedLength(255)` (après trim, comme le backend), `isbnFormat` (retrait de `/[\s-]/g` puis `/^\d{13}$/`, client jamais plus strict que le backend, RM-CAT-34). `copies` : `required`, entier, 1 à 1000, `p-inputnumber` sans décimales ni séparateur de milliers. |

### 5.3 Mode `mock`
| Fichier | État | Contenu |
|---|---|---|
| `core/mock/mock-catalogue.ts` | nouveau | Classe `MockCatalogue` (types locaux : `core` n'importe pas `features`). État en mémoire depuis `books.json`. `search(params)` : valide `page`, `size`, `q` comme le backend (400 + `errors[]`) ; filtre `availableCopies >= 1` et `toLowerCase().includes` sur titre OU auteur ; tri `title.toLowerCase()` puis `isbn` par unités de code (`<`, pas `localeCompare`) pour imiter `COLLATE "C"` ; renvoie l'enveloppe. `register(body, roles)` : 403 sans LIBRARIAN, 400 par champ, ISBN normalisé, 409 si doublon, sinon ajout (`crypto.randomUUID()`) et 201 avec `Location`. GET : 403 si l'utilisateur n'a ni LIBRARIAN ni MEMBER. |
| `core/mock/mock-api.interceptor.ts` | modifié | `GET /api/v1/books` et `POST /api/v1/books` : 401 sans session (`unauthenticated.json`), sinon `MockCatalogue`. Délais 200 à 400 ms. |
| `src/mocks/catalogue/books.json` | nouveau | Au moins 30 livres, dont « L'Étranger », « La Peste » (Camus), « Germinal » (Zola), « 100% Python », « Le Python », 15 livres de « Hugo », un titre avec `_`, un avec `\`, deux livres à 0 exemplaire disponible. |
| `src/mocks/catalogue/validation-failed.json`, `isbn-already-exists.json`, `access-denied.json` | nouveaux | `ProblemDetail` conformes au contrat. |

## 6. RM-CAT-42 — règle générale d'invalidation (point d'extension pour `loan`)
**Règle.** Une opération qui modifie `availableCopies` d'un livre, ou l'ensemble des livres disponibles (création, suppression, ajout/retrait d'exemplaires, emprunt, retour), n'est terminée que si elle supprime `library:catalog:available-books:v1` **après le commit** ; sans transaction active, suppression immédiate ; rien en cas de rollback ; une erreur Redis donne un WARN et n'est jamais propagée (RC-13, RC-15).

**Mécanisme (seul autorisé).**
1. La modification est portée par un **port de sortie d'écriture du module `catalog`** : aujourd'hui `SaveBookPort` ; demain `ReserveCopyPort`, `ReleaseCopyPort` (noms indicatifs).
2. `AvailableBooksCacheAdapter` (décorateur `@Primary`) **implémente ce port**, délègue à `@Qualifier("bookPersistenceAdapter")`, puis appelle `evictAfterCommit()`.
3. `loan` **ne touche jamais** au cache ni à la clé : il appelle `catalog.api.CatalogApi` (implémenté par `catalog.adapter.in.module.CatalogApiFacade`) → port d'entrée `catalog` → use case `catalog` → port de sortie décoré (RM-CAT-12, « Communication entre modules » de la référence).
4. Pas d'`@CacheEvict` ni d'événement applicatif (`CacheArchitectureTest`, D2).
5. **Vérification (décision 4)** : `@AffectsAvailableBooks` (JDK seul, `application/port/out`) posée sur `SaveBookPort` ; règle ajoutée à `CacheArchitectureTest` : « toute interface annotée `@AffectsAvailableBooks` est implémentée par `AvailableBooksCacheAdapter` ». À la revue de `loan`, un port d'écriture non annoté est BLOQUANT.
6. Javadoc de `AvailableBooksCacheAdapter` mise à jour. Généralise RC-17 de `cache-available-books.md`.

## 7. Tests
### 7.1 Backend
| Couche | Classe de test | Cas (règles) |
|---|---|---|
| domain | `domain/model/CatalogPageRequestTest` | Défauts 0/10 ; `page=-1`, `size=0`, `size=51` → `OUT_OF_RANGE` ; `offset()` sans débordement (`page=Integer.MAX_VALUE`, `size=50`) ; `endExclusive()` (RM-CAT-15, 24) |
| domain | `domain/model/SearchTextTest` | Trim ; null/vide/blanc → `NONE` ; 100 accepté, 101 → `TOO_LONG` ; `matches` : casse, titre OU auteur, accents (« etranger » ne trouve pas « L'Étranger », « ÉTRANGER » le trouve), `%` `_` `\` littéraux, ISBN non cherché (RM-CAT-17) |
| domain | `domain/model/CatalogSearchTest` | Toutes les violations réunies (`page=-1&size=0&q=101` → 3 entrées) |
| domain | `domain/model/AvailableBooksPageTest` | `totalPages` : 25/10 = 3, 0 si total 0, page au-delà (RM-CAT-18, 19) |
| application | `application/usecase/SearchAvailableBooksServiceTest` (fake `InMemoryBookRepository` étendu aux 2 ports) | Scénarios Gherkin API : 25 livres, pages 0 et 2, page 9 (contenu **non lu** : compteur), livre épuisé exclu, recherche + pagination (Hugo) |
| adapter.out.persistence | `BookPersistenceAdapterTest` (`@DataJpaTest` + Testcontainers PG) | Ordre = `CATALOG_ORDER` ; pages sans chevauchement ni perte ; **`findAvailablePage` = préfixe de `findAvailableOrderedByTitle(20)`** pour toute fenêtre ≤ 20 ; recherche (exemples de la spec) ; `100%`, `%`, `_`, `\` ; count = filtre ; offset au-delà ; exclusion de `available_copies = 0` ; « ÉMILE » trouve « Émile Zola » |
| adapter.out.cache | `AvailableBooksCacheAdapterTest` (unitaire) | Tête servie par le cache sans `persistentPageLoad` ; découpe `page=1,size=10` ; limites `page*size+size = 20` (cache) / `= 21` (base) ; `q` non vide : base, cache ni lu ni écrit ; Miss : 20 lus puis écrits ; Unavailable : base, aucune écriture ; tests existants verts (RM-CAT-24) |
| adapter.out.cache | `AvailableBooksCacheIntegrationTest` / `...RedisDownIntegrationTest` (PG + Redis) | GET identique avec et sans cache ; Redis arrêté : même réponse + WARN (`LOG_READ_FAILED`) ; POST (201) puis GET `q=<titre>` : livre présent (RM-CAT-24, 25) |
| adapter.in.web | `BookControllerTest` (`@WebMvcTest` + `@Import(SecurityConfig)`, `@MockitoBean SearchAvailableBooksUseCase`) | 200 avec défauts ; forme JSON exacte ; 400 pour `page=-1`, `page=abc`, `size=0`, `size=51`, `size=abc`, `q` de 101 caractères ; plusieurs erreurs ensemble ; `sort=author,desc` ignoré ; 401 (`UNAUTHENTICATED`, `WWW-Authenticate`) ; LIBRARIAN, MEMBER, les deux : 200 ; **JWT sans rôle : 403 `ACCESS_DENIED`** ; cookie sans `X-XSRF-TOKEN` : 200 ; tests POST existants inchangés |
| bout en bout | `catalog/BookCatalogueApiIntegrationTest` (`@SpringBootTest` + PG + Redis) | Scénarios Gherkin « API : consultation », dont `q=100%25` |
| architecture | `ArchitectureTest` (non modifié), `CacheArchitectureTest` (+ règle RM-CAT-42) | Toujours vert |

### 7.2 Frontend (`npx ng test --watch=false`)
| Fichier | Cas |
|---|---|
| `core/http/error.interceptor.spec.ts` | Pas de toast pour 400/409 marqués ; toast maintenu pour 403, 500 et 0 même marqués ; erreur propagée |
| `core/layout/header/header.spec.ts` | Lien « Catalogue » seulement si connecté ; état actif |
| `features/catalogue/data/catalogue.service.spec.ts` | URL et paramètres (`q` omis si vide), corps du POST, `context` 400/409 |
| `features/catalogue/pages/catalogue/catalogue-url-state.spec.ts` | `page` base 1 (absent, `abc`, `0` → 1) ; `size` hors {10, 20, 50} → 10 ; `q` tronqué |
| `features/catalogue/pages/catalogue/catalogue.spec.ts` | `/catalogue?q=camus&size=20` → `q=camus&page=0&size=20` ; changement de page ; requête obsolète annulée ; états vide / sans résultat / erreur + « Réessayer » ; bouton selon le rôle (MEMBER, LIBRARIAN, les deux) ; après 201 : toast, fermeture, rechargement même page et même `q` ; 400/409 transmis au dialogue ; page au-delà → dernière page |
| `features/catalogue/ui/book-search/book-search.spec.ts` | « c », « ca », « cam » en < 300 ms → une émission (`vi.useFakeTimers`) ; Entrée immédiate ; effacement |
| `features/catalogue/ui/book-table/book-table.spec.ts` | Colonnes, « 2 / 3 », messages vides (`q` échappé), paginateur masqué si total 0, `pageChange` |
| `features/catalogue/ui/book-form-dialog/book-form-dialog.spec.ts` | `978-2-07-036822-8` accepté ; 12 chiffres refusés ; longueurs après trim ; formulaire vide : 4 erreurs sans émission ; `errors[]` sur les champs ; champ inconnu et `errors: []` → message général ; 409 sous ISBN ; erreur effacée à la modification ; focus ; une émission en double clic |
| `core/mock/mock-api.interceptor.spec.ts` | GET (pagination, recherche, 400), POST (201, 400, 409, 403 MEMBER, 401 sans session) |

## 8. Intégrations
| Techno | Classes et rôle | Résilience |
|---|---|---|
| PostgreSQL | `BookPersistenceAdapter`, `BookJpaRepository`, `LikePatterns` : `sortant-persistance` | Lecture seule (`@UseCase(readOnly = true)`) ; count + page dans la même transaction |
| Redis | `AvailableBooksCacheAdapter` : `sortant-cache` (décorateur `@Primary`) ; `RedisClientConfig` inchangé | Hit / Miss / Unavailable inchangés : Redis indisponible → base + WARN, aucune écriture ; recherche et pages profondes n'utilisent jamais Redis ; invalidation après commit (RM-CAT-25, 42) ; aucune nouvelle clé ; TTL inchangé ; pas de `KEYS` |

## 9. Risques
- **R1 Performance.** Sans index, chaque GET fait un `COUNT` et un tri de `book WHERE available_copies >= 1` (`LIKE '%…%'` non indexable). Acceptable pour quelques milliers de livres. Seuil de réexamen : issue #20. Le cache n'évite pas le `COUNT` (option A, gain faible assumé). Pas de N+1.
- **R2 Cohérence du total.** Tête (cache) et total (base) peuvent diverger au plus un TTL si l'invalidation échoue (RC-16, accepté). En READ COMMITTED, count et page sont deux instantanés : décalage d'une unité possible en cas d'écriture concurrente. Accepté.
- **R3 Pliage de casse.** `lower()` dépend de `LC_CTYPE`. Base locale : `French_France.1252` (décision 9), les majuscules accentuées sont abaissées. Image Testcontainers vérifiée par le test « ÉMILE ». Divergences marginales possibles entre `toLowerCase(Locale.ROOT)` (fake, mock) et PostgreSQL pour des caractères exotiques.
- **R4 Sécurité.** Requêtes paramétrées, `ESCAPE` explicite, `q` ≤ 100, `size` ≤ 50, offset en `long` (contenu au-delà du total non lu). 401 avant 403 avant 400. Aucun champ sensible. `q` affiché par interpolation.
- **R5 Impacts.** Refactorisation de `AvailableBooksCacheAdapter` (régressions MCP couvertes par les tests existants) ; `MaxTrimmedLength` gagne un attribut ; `errorInterceptor` gagne une option (défaut inchangé) ; `mock-api.interceptor` enrichi ; contrat MCP intact ; `loan` doit appliquer §6.

## 10. Écarts avec la spec et précisions
1. RM-CAT-23 : **deux** ports de sortie (page, total), car l'option A décore la page sans décorer le total.
2. `?page=` ou `?size=` vide = absent (défaut, pas de 400). `abc`, `1.5`, dépassement d'entier → 400.
3. Pas d'annotations OpenAPI (`springdoc` absent) : `docs/api/catalogue.md` fait foi.
4. RM-CAT-39 : le toast « Session expirée » de l'`authInterceptor` est conservé (décision 7, spec ajustée).
5. Pas de `roleGuard` (dialogue retenu).
6. GET : 403 pour un JWT valide sans rôle `LIBRARIAN` ni `MEMBER` (décision 5), précision de RM-CAT-13.
7. Pas de registre `docs/design/design.json` : écrans à concevoir.

## 11. Ordre d'implémentation
0. `docs/api/catalogue.md` (contrat).
1. **Domaine** : `CatalogPageRequest`, `SearchText`, `CatalogSearch`, `AvailableBooksPage`, `InvalidCatalogSearchException`. `./mvnw -q compile`.
2. **Application** : `SearchAvailableBooksQuery`, `SearchAvailableBooksUseCase`, `LoadAvailableBooksPagePort`, `CountAvailableBooksPort`, `@AffectsAvailableBooks`, `SearchAvailableBooksService`.
3. **Adapters sortants** : `BookJpaRepository`, `LikePatterns`, `BookPersistenceAdapter` ; puis `AvailableBooksCacheAdapter` (refactorisation + page).
4. **Adapter entrant** : `MaxTrimmedLength(max)`, `SearchBooksParams`, `BookPageResponse`, `BookWebMapper`, `BookController#search`, `CatalogExceptionHandler`.
5. **Config** : rien (vérifier `SecurityConfig`). Règle RM-CAT-42 dans `CacheArchitectureTest` (écrite par test-writer).
6. **Fin backend** : `./mvnw -q compile` ; les tests sont écrits et exécutés par test-writer.
7. **Frontend** : `core/http` ; `data/` ; `ui/` ; page `catalogue` ; routes et en-tête ; mode `mock` et jeux de données ; `npx ng build`, `npm run lint`.
