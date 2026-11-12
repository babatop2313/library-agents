# Plan technique : serveur MCP du catalogue (`catalog-mcp-server`)

> Plan produit par l'agent `architect`, validé par l'humain le 2026-11-12.
> Spec : `docs/specs/catalog-mcp-server.md` (section « Décisions » prioritaire).

## 0. Décisions de l'humain (rappel, elles prévalent)

1. `limit` par défaut = 10.
2. `limit` ≤ 0 ou > 20 → erreur explicite (`isError = true`), pas de correction silencieuse.
3. `/mcp` avec JWT : tout utilisateur authentifié (`LIBRARIAN` ou `MEMBER`).
4. Profil `mcp-local` : jamais actif par défaut ; avertissement au démarrage ; refus de démarrer si un profil actif s'appelle `prod` ; `server.address=127.0.0.1` dans le profil `mcp-local`.
5. Version Spring AI 2.0.x vérifiée pendant le développement (dernière 2.0.x compatible Spring Boot 4.1, via `spring-ai-bom`).
6. Pas d'`id` technique exposé, l'ISBN suffit.
7. Tri : titre insensible à la casse, puis ISBN.
8. Messages en français.
9. Livre absent → erreur d'outil (`isError = true`).
10. `BookNotFoundException` créée dans le domaine, sans gestion REST pour l'instant.
11. Les nouveaux ports de sortie sont implémentés par l'adapter de persistance existant ; réutiliser un port existant s'il charge déjà un livre par ISBN (aucun ne le fait). Pas de nouvel adapter de persistance.
12. Plan validé tel quel.

## 1. Architecture appliquée

**hexagonal** (`.claude/factory.json`). Seule référence : `.claude/skills/architecture/references/hexagonal/REFERENCE.md`.

## 2. Résumé

- Deux ports d'entrée en lecture seule (`GetBookByIsbnUseCase`, `ListAvailableBooksUseCase`) et leurs use cases, appuyés sur deux nouveaux ports de sortie implémentés par le `BookPersistenceAdapter` existant. Les règles (ISBN, bornes de `limit`, disponibilité, tri) sont dans le domaine.
- Nouvel adapter entrant `catalog.adapter.in.mcp` : `get_book` et `list_available_books` avec `@McpTool`, Spring AI 2.0, Streamable HTTP sur `/mcp`. Il n'appelle que les ports d'entrée. Toute exception devient un `CallToolResult` `isError=true` avec un message en français.
- Sécurité : `/mcp` reste couvert par la chaîne JWT existante. Avec `mcp-local` seulement, une `SecurityFilterChain` dédiée, prioritaire et limitée à `/mcp`, l'ouvre sans authentification ; avertissement au démarrage ; refus de démarrer avec `prod` ; `server.address=127.0.0.1`.

### Constats sur l'existant

| Constat | Conséquence |
|---|---|
| Ports de sortie existants : `SaveBookPort`, `CheckIsbnRegisteredPort` (`boolean isRegistered(Isbn)`). Aucun ne charge un livre par ISBN. | On crée `LoadBookByIsbnPort`, implémenté par `BookPersistenceAdapter`. |
| `Isbn.of(raw)` retire espaces et tirets, exige 13 chiffres ; `Isbn.of(null)` lève `InvalidIsbnException` (`ISBN_INVALID`). | Réutilisé tel quel, aucune duplication dans l'adapter. |
| `@UseCase` porte un `@Transactional` lecture-écriture. | Extension de `@UseCase` avec `readOnly` (3.2). |
| `SecurityConfig.apiSecurity` sans `securityMatcher` ni `@Order` (chaîne attrape-tout), `csrf` désactivé. | Chaîne plus prioritaire limitée à `/mcp`, sans toucher `apiSecurity`. |
| `BookControllerTest` fait `@Import(SecurityConfig.class)`. | Chaîne `mcp-local` dans une classe séparée. |
| Dernière migration `V1__create_table_book.sql` (contrainte `available_copies BETWEEN 0 AND total_copies`). | Aucune migration. |
| `ArchitectureTest` : `adapter.in` ne dépend ni de `adapter.out`, ni de `usecase`, ni de `port.out`. | L'adapter MCP n'importe que `port.in`, des types `domain` et Spring AI / MCP. |

## 3. Découpage par couche

Racine : `backend/src/main/java/com/example/library/`

### 3.1 Domaine (`catalog.domain`)

| Fichier | Action | Responsabilité | Règles |
|---|---|---|---|
| `catalog/domain/model/Book.java` | Modifier | Ajouter `public boolean isAvailable()` → `availableCopies >= 1`. | RM-04, RM-MCP-06 |
| `catalog/domain/model/AvailableBooksLimit.java` | Créer | `record AvailableBooksLimit(int value)`, constantes `MIN = 1`, `MAX = 20`, `DEFAULT = 10`. Constructeur compact : `LimitOutOfRangeException` hors 1..20. `static of(Integer requested)` : `DEFAULT` si `null`, sinon valide. Constante unique de la borne 20. | RM-MCP-07, décisions 1-2 |
| `catalog/domain/service/AvailableBooksSelection.java` | Créer | Service sans état. `List<Book> select(List<Book> candidates, AvailableBooksLimit limit)` : filtre `Book::isAvailable`, trie `CATALOG_ORDER`, tronque. `public static final Comparator<Book> CATALOG_ORDER` : `title.toLowerCase(Locale.ROOT)` puis `isbn.value()`. | RM-MCP-06/08, décision 7 |
| `catalog/domain/exception/BookNotFoundException.java` | Créer | `extends DomainException`, code `BOOK_NOT_FOUND`, message « Aucun livre trouvé pour cet ISBN », porte l'`Isbn`. Pas de handler REST. | décisions 9-10 |
| `catalog/domain/exception/LimitOutOfRangeException.java` | Créer | `extends DomainException`, code `LIMIT_OUT_OF_RANGE`, porte `requested`, `min`, `max`. | RM-MCP-07 |

Le SQL filtre/trie/limite pour la performance ; le domaine décide et réapplique la sélection (idempotent), testable sans base.

### 3.2 Application (`catalog.application`)

| Fichier | Action | Responsabilité |
|---|---|---|
| `port/in/GetBookByIsbnUseCase.java` | Créer | `Book getByIsbn(GetBookByIsbnQuery query)`, lève `BookNotFoundException`. |
| `port/in/GetBookByIsbnQuery.java` | Créer | `record GetBookByIsbnQuery(Isbn isbn)` (`requireNonNull`), `static fromRaw(String rawIsbn)` → `Isbn.of(rawIsbn)`. |
| `port/in/ListAvailableBooksUseCase.java` | Créer | `List<Book> listAvailable(ListAvailableBooksQuery query)`. |
| `port/in/ListAvailableBooksQuery.java` | Créer | `record ListAvailableBooksQuery(AvailableBooksLimit limit)`, `static of(Integer requestedLimit)` → `AvailableBooksLimit.of`. |
| `port/out/LoadBookByIsbnPort.java` | Créer | `Optional<Book> loadByIsbn(Isbn isbn)`. |
| `port/out/LoadAvailableBooksPort.java` | Créer | `List<Book> loadAvailableBooks(int maxResults)` ; Javadoc : livres disponibles, ordre `CATALOG_ORDER`, au plus `maxResults`. |
| `usecase/GetBookByIsbnService.java` | Créer | `@UseCase(readOnly = true)` ; `loadByIsbn(...).orElseThrow(() -> new BookNotFoundException(isbn))`. |
| `usecase/ListAvailableBooksService.java` | Créer | `@UseCase(readOnly = true)` ; `selection.select(port.loadAvailableBooks(limit.value()), limit)`. |
| `shared/UseCase.java` | Modifier | Ajouter `boolean readOnly() default false` avec `@AliasFor(annotation = Transactional.class, attribute = "readOnly")`. |

Repli si l'alias ne s'applique pas (vérifier via `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`) : `shared/QueryUseCase.java` méta-annoté `@Component @Transactional(readOnly = true)`.

### 3.3 Adapter sortant (`catalog.adapter.out.persistence`) — pas de nouvel adapter

| Fichier | Action | Responsabilité |
|---|---|---|
| `BookJpaRepository.java` | Modifier | `Optional<BookJpaEntity> findByIsbn(String isbn);` + requête native `SELECT * FROM book WHERE available_copies >= 1 ORDER BY lower(title) COLLATE "C", isbn LIMIT :max` → `findAvailableOrderedByTitle(@Param("max") int max)`. |
| `BookPersistenceAdapter.java` | Modifier | Implémente aussi `LoadBookByIsbnPort` et `LoadAvailableBooksPort` (mapping via `mapper::toDomain`). Reste package-private `@Component`. |
| `BookPersistenceMapper.java`, `BookJpaEntity.java` | Inchangés | |

`COLLATE "C"` aligne l'ordre SQL sur `toLowerCase(Locale.ROOT)` en Java, indépendamment de la collation de la base. Aucune migration.

### 3.4 Adapter entrant MCP (`catalog.adapter.in.mcp`)

| Fichier | Responsabilité |
|---|---|
| `package-info.java` | Adapter entrant MCP, lecture seule. |
| `BookView.java` | `record BookView(String isbn, String title, String author, int availableCopies, int totalCopies)`, sans `id`. |
| `BookViewMapper.java` | `static BookView from(Book book)`. |
| `McpToolErrors.java` | Traduit une exception en message français par code (jamais `getMessage()`, ni classe, ni stack trace) : `ISBN_INVALID` → « ISBN invalide : il doit comporter 13 chiffres (tirets et espaces tolérés), par exemple 9782070368228. » ; `BOOK_NOT_FOUND` → « Aucun livre trouvé pour l'ISBN {isbn normalisé}. » ; `LIMIT_OUT_OF_RANGE` (et type invalide) → « limit doit être un entier compris entre 1 et 20 (10 par défaut si absent). » ; autre → « Erreur interne, réessayez plus tard. » + `log.error` (nom de l'outil, cause ; ni token ni donnée personnelle). |
| `CatalogMcpTools.java` | `@Component`, injecte uniquement `GetBookByIsbnUseCase` et `ListAvailableBooksUseCase`. Deux méthodes `@McpTool` renvoyant `McpSchema.CallToolResult` : succès = JSON du `BookView` / `List<BookView>` (Jackson 3 `JsonMapper`), erreur = `isError(true)` + message `McpToolErrors`. |

Signatures (attributs à vérifier sur Spring AI 2.0) :

```java
@McpTool(name = "get_book", description = GET_BOOK_DESCRIPTION,
         annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
                                               idempotentHint = true, openWorldHint = false))
public CallToolResult getBook(@McpToolParam(description = ISBN_DESCRIPTION, required = true) String isbn)

@McpTool(name = "list_available_books", description = LIST_DESCRIPTION, annotations = /* idem */)
public CallToolResult listAvailableBooks(@McpToolParam(description = LIMIT_DESCRIPTION, required = false) Integer limit)
```

Déroulé : `try { ... GetBookByIsbnQuery.fromRaw(isbn) / ListAvailableBooksQuery.of(limit) ... } catch (DomainException e) { error(e) } catch (RuntimeException e) { internalError(tool, e) }`. ISBN ou limite invalide → exception avant l'appel du use case. Liste vide = succès `[]`.

Descriptions (constantes `static final String`) :

- `get_book` : « Renvoie la fiche d'un livre du catalogue de la bibliothèque à partir de son ISBN : isbn (13 chiffres, forme normalisée), title, author, availableCopies (exemplaires empruntables maintenant) et totalCopies. À utiliser quand l'utilisateur donne un ISBN précis ou veut savoir si ce livre est disponible. Ne pas utiliser pour chercher par titre, auteur ou mot-clé (non supporté), ni pour parcourir le catalogue : utiliser list_available_books. Si aucun livre ne porte cet ISBN, l'outil renvoie une erreur « Aucun livre trouvé ». Outil en lecture seule. »
- `isbn` : « ISBN-13 du livre : exactement 13 chiffres ; tirets et espaces tolérés (ex. 978-2-07-036822-8 ou 9782070368228). Les ISBN-10 ne sont pas acceptés. Obligatoire. »
- `list_available_books` : « Liste les livres du catalogue qui ont au moins un exemplaire disponible à l'emprunt, triés par titre (sans tenir compte de la casse) puis par ISBN. Chaque élément contient isbn, title, author, availableCopies et totalCopies. À utiliser pour proposer des livres empruntables ou donner un aperçu du catalogue disponible. Ne pas utiliser pour un livre dont l'ISBN est connu (utiliser get_book), ni pour chercher par titre ou auteur (non supporté). Les livres sans exemplaire disponible n'apparaissent jamais. Une liste vide signifie qu'aucun livre n'est disponible. Outil en lecture seule. »
- `limit` : « Nombre maximal de livres à renvoyer : entier de 1 à 20. Facultatif, 10 si absent. Une valeur hors de 1 à 20 provoque une erreur (aucun ajustement automatique). »

Visibilité : package-private si le scanner Spring AI l'accepte, sinon `public` (justifié dans la PR). Renvoyer `CallToolResult` plutôt que lever une exception garantit l'absence de préfixe technique (RM-MCP-09).

Dépendances : `catalog.application.port.in.*`, `catalog.domain.model.*`, `catalog.domain.exception.*`, `org.springframework.ai.mcp.annotation.*`, `io.modelcontextprotocol.spec.McpSchema`, `tools.jackson.*`, `org.slf4j`.

### 3.5 Config

| Fichier | Action | Responsabilité |
|---|---|---|
| `backend/pom.xml` | Modifier | Voir 3.6. |
| `src/main/resources/application.yml` | Modifier | Bloc `spring.ai.mcp.server`. Ne pas définir `spring.profiles.active`. |
| `src/main/resources/application-mcp-local.yml` | Créer | `server.address: 127.0.0.1` + commentaire « poste de développeur uniquement ». |
| `config/security/McpLocalSecurityConfig.java` | Créer | `@Configuration @Profile("mcp-local")`. Constructeur : `McpLocalProfileGuard.check(environment.getActiveProfiles())` puis `log.warn("Profil mcp-local actif : /mcp est accessible SANS authentification et l'API n'écoute que sur 127.0.0.1. Ne jamais activer hors poste de développeur.")`. Bean `@Order(Ordered.HIGHEST_PRECEDENCE) SecurityFilterChain mcpLocalSecurity` : `securityMatcher("/mcp", "/mcp/**")`, csrf désactivé, `STATELESS`, `anyRequest().permitAll()`, sans `oauth2ResourceServer`. |
| `config/security/McpLocalProfileGuard.java` | Créer | Classe finale package-private sans Spring : `static void check(String[] activeProfiles)` → `IllegalStateException("Le profil mcp-local ne peut pas être combiné avec le profil prod")` si un profil vaut `prod` (`trim` + `equalsIgnoreCase`). |
| `config/security/SecurityConfig.java` | Inchangé | `anyRequest().authenticated()` couvre `/mcp` hors `mcp-local`. |

### 3.6 Maven et propriétés (à vérifier par backend-dev)

```xml
<properties>
    <spring-ai.version>2.0.x</spring-ai.version> <!-- dernière 2.0.x compatible Boot 4.1 -->
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    mcp:
      server:
        name: library-catalog
        version: 0.0.1
        type: SYNC
        protocol: STREAMABLE
        streamable-http:
          mcp-endpoint: /mcp
        capabilities:
          tool: true
          resource: false
          prompt: false
          completion: false
        annotation-scanner:
          enabled: true
```

Vérifier `./mvnw dependency:tree` : pas de Jackson 2 imposé, pas de conflit Spring Framework / Security ; module JSON Jackson 3 du SDK MCP.

### 3.7 Règle de dépendance

Toutes les nouvelles dépendances sont autorisées (adapter.in → port.in + domaine ; usecase → ports + domaine + shared ; adapter.out → port.out + domaine ; domain pur ; config libre ; `shared.UseCase` → `AliasFor`). `ArchitectureTest` passe sans modification.

## 4. Contrat MCP sur HTTP

| Méthode | URL | Accès | Codes |
|---|---|---|---|
| POST | `/mcp` | Tout utilisateur authentifié ; aucun avec `mcp-local` | 200 (erreur d'outil = 200 + `isError=true`), 401 sans JWT / JWT invalide (hors `mcp-local`), 400/404 selon SDK |
| GET | `/mcp` | idem | 200, 401, 405 |
| DELETE | `/mcp` | idem | 200/204, 401 |

| Outil | Arguments | Succès | Erreurs |
|---|---|---|---|
| `get_book` | `isbn` string obligatoire | `BookView` | `ISBN_INVALID`, `BOOK_NOT_FOUND`, interne |
| `list_available_books` | `limit` entier facultatif 1..20, défaut 10 | `[BookView…]` (peut être vide) | `LIMIT_OUT_OF_RANGE` (y compris type invalide), interne |

## 5. Migration

Aucune.

## 6. Frontend

Hors périmètre.

## 7. Tests par couche

Racine : `backend/src/test/java/com/example/library/`

### 7.1 Domaine (JUnit 5 + AssertJ)
- `AvailableBooksLimitTest` : `of(null)` = 10 ; 1 et 20 acceptés ; 0, -1, 21, 1000 → `LIMIT_OUT_OF_RANGE`.
- `BookTest` : `isAvailable` faux à 0, vrai à 1.
- `IsbnTest` (compléter) : `""`, `"   "`, `null`, `978207036822A`, 12 et 14 chiffres → `ISBN_INVALID` ; forme avec tirets égale à la forme brute.
- `AvailableBooksSelectionTest` : exclut 0 exemplaire ; tri insensible à la casse ; départage par ISBN ; troncature ; entrée vide.
- `BookNotFoundExceptionTest` : code, ISBN conservé.

### 7.2 Application (fakes en mémoire)
- `InMemoryBookRepository` : implémente les deux nouveaux ports (avec `AvailableBooksSelection`), compteurs d'appels.
- `GetBookByIsbnServiceTest` : nominal ; livre à 0 disponible renvoyé ; inconnu → `BookNotFoundException` ; ISBN formaté ; `fromRaw` invalide sans appel.
- `ListAvailableBooksServiceTest` : filtre + tri ; limites 5/20/défaut 10 ; vide ; `of(0)` sans lecture.
- Transaction lecture seule : vérification facultative (test-writer juge).

### 7.3 Adapter sortant (`@DataJpaTest` + Testcontainers)
- `BookPersistenceAdapterTest` : `loadByIsbn` présent/absent ; `loadAvailableBooks` exclut 0 disponible, ordre casse mixte et accents comparé à `CATALOG_ORDER`, limite ; aucune écriture (`version` inchangée).

### 7.4 Adapter MCP (unitaire, ports d'entrée mockés)
- `CatalogMcpToolsTest` : nominal sans `id` ; ISBN formaté (captor) ; livre absent → message exact, sans `Exception`/`com.example`/`at ` ; ISBN invalides paramétrés + `verifyNoInteractions` ; `limit` null → 10, 5 et 20 transmis, 0/-1/21/1000 → erreur sans interaction ; `[]` succès ; erreur imprévue → message générique + log (`OutputCaptureExtension`).
- `CatalogMcpToolsDescriptionTest` : exactement deux `@McpTool` nommés ; descriptions non vides ; `@McpToolParam` décrits ; `readOnlyHint=true`.

### 7.5 Sécurité et intégration (`@SpringBootTest` + Testcontainers)
- `McpSecurityTest` (sans profil) : `POST /mcp` sans JWT → 401 ; JWT mal signé → 401 ; `MEMBER` et `LIBRARIAN` → pas 401.
- `McpLocalSecurityTest` (`mcp-local`) : `/mcp` sans JWT → pas 401 ; `/api/v1/books` sans JWT → 401 ; autre route → 401 ; WARN présent.
- `McpLocalProfileGuardTest` : `{mcp-local, prod}` et `{PROD, mcp-local}` → exception ; `{mcp-local}`, `{mcp-local, local}` passent.
- Refus de démarrer avec `prod` par `SpringApplicationBuilder` : facultatif.
- `CatalogMcpServerIT` (recommandé) : client MCP Streamable HTTP, `listTools` = 2 outils, `get_book`, `list_available_books` avec `limit` `"abc"` et `2.5` → `isError=true` sans stack trace.
- Non-régression : `BookControllerTest`, `LibraryApplicationTests`, `ArchitectureTest` inchangés et verts.

## 8. Risques et points à vérifier

| # | Risque | Mesure |
|---|---|---|
| 1 | Compatibilité Spring AI 2.0.x / Boot 4.1.1 (Jackson 3, Security 7, Framework 7). | Maven Central, notes de version, `dependency:tree`, `LibraryApplicationTests` dès l'ajout. |
| 2 | Noms des propriétés `spring.ai.mcp.server.*` et attributs `@McpTool`/`@McpToolParam`/`McpAnnotations` en 2.0. | Vérifier la doc ; `CatalogMcpServerIT`. |
| 3 | `limit` non entier converti par le framework (message technique, `2.5` tronqué). | IT ; sinon paramètre `Object` parsé dans l'adapter (bornes restent dans le domaine). |
| 4 | `isbn` absent rejeté par validation de schéma du SDK. | Acceptable si `isError=true` clair. |
| 5 | `@McpTool` dans une classe package-private. | Repli `public`. |
| 6 | Divergence ordre SQL / Java. | `COLLATE "C"` + test de persistance ; le domaine réapplique la sélection. |
| 7 | Performance (scan pour `lower(title)`). | Index fonctionnel dans une future V2 si besoin. |
| 8 | `mcp-local` activé hors poste local ; garde-fou limité au nom `prod`. | Jamais dans `application.yml`, `127.0.0.1`, refus avec `prod`, WARN ; documenter dans la PR. |
| 9 | Dispatch ASYNC/SSE en `STATELESS`. | Vérifier en IT avec JWT ; `dispatcherTypeMatchers(ASYNC).permitAll()` seulement si nécessaire et justifié. |
| 10 | DNS rebinding (en-tête `Origin`). | `127.0.0.1` ; activer la validation d'`Origin` si Spring AI 2.0 la propose. |
| 11 | Journaux. | Jamais d'en-têtes ni de corps JSON-RPC ; `log.error` pour l'imprévu uniquement. |
| 12 | Impact autres modules. | Aucun. |
| 13 | `LibraryApplicationTests` charge l'auto-config MCP. | Doit démarrer sans propriété de test supplémentaire. |
| 14 | `@AliasFor` sur `@UseCase`. | Test dédié ou repli `@QueryUseCase`. |

## 9. Ordre d'implémentation

Compiler (`cd backend && ./mvnw -q compile`) après chaque étape.

1. Domaine : `Book.isAvailable`, `AvailableBooksLimit`, `LimitOutOfRangeException`, `BookNotFoundException`, `AvailableBooksSelection`.
2. Application : `UseCase.readOnly`, queries, ports d'entrée, ports de sortie, services ; `InMemoryBookRepository`.
3. Adapter sortant : `BookJpaRepository`, `BookPersistenceAdapter`.
4. Adapter entrant MCP : dépendance `pom.xml` d'abord, puis `BookView`, `BookViewMapper`, `McpToolErrors`, `CatalogMcpTools`.
5. Config : `application.yml`, `application-mcp-local.yml`, `McpLocalProfileGuard`, `McpLocalSecurityConfig`.
6. Fin : `cd backend && ./mvnw clean test`.
