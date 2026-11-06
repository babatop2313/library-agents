# Référence d'architecture — En couches (controller / service / repository)

## Résumé
- **Architecture active : en couches.** Organisation classique Spring, découpée par fonctionnalité (feature).
- Packages par fonctionnalité : `controller/`, `dto/`, `service/`, `mapper/`, `repository/`, `entity/`, `exception/`.
- **Règle de dépendance : `controller → service → repository → entity`, jamais l'inverse ni en sautant une couche.**
- Un contrôleur n'appelle que des services et ne manipule que des DTO : jamais de repository, jamais d'entité JPA.
- Un repository n'est appelé que par un service. Une entité ne dépend d'aucune autre couche.
- La logique métier est dans les services (et dans les méthodes des entités pour leurs propres invariants),
  jamais dans un contrôleur ni dans un repository.
- Les services ne connaissent pas HTTP (pas de `ResponseEntity`, pas de `HttpServletRequest`).
- Transactions sur les services.
- Entre features : on n'appelle que le service (et les DTO) d'une autre feature.
- Ordre d'implémentation : migration → entité → repository → DTO + mapper → service → contrôleur.
- `ArchitectureTest` (ArchUnit) doit toujours passer.

## Structure des packages
```
com.example.<app>.<feature>/            ex. com.example.library.catalog
  controller/     contrôleurs REST (@RestController) + @RestControllerAdvice de la feature
  dto/            records request / response (BookCreateRequest, BookResponse)
  service/        logique métier et transactions (BookService)
  mapper/         conversions entité ↔ DTO (BookMapper, MapStruct ou manuel)
  repository/     interfaces Spring Data (BookRepository)
  entity/         entités JPA (@Entity Book)
  exception/      exceptions métier de la feature (BookNotFoundException, IsbnAlreadyUsedException)
com.example.<app>.shared/       types transverses (BusinessException, pagination…)
com.example.<app>.config/       configuration globale (sécurité, Clock…)
```
Le découpage **par fonctionnalité** (puis par couche à l'intérieur) est obligatoire : pas de package global
`controllers/` contenant les contrôleurs de toute l'application.

## Règle de dépendance
```
controller ──► service ──► repository ──► entity
    │             │
    ▼             ▼
   dto ◄──────  mapper ──► entity
```

| Couche | Peut dépendre de | Ne doit jamais dépendre de |
|---|---|---|
| `controller` | `service`, `dto`, `exception` | `repository`, `entity`, `mapper` |
| `service` | `repository`, `entity`, `dto`, `mapper`, `exception` | `controller`, Spring Web (`org.springframework.web`, `ResponseEntity`) |
| `mapper` | `entity`, `dto` | `controller`, `service`, `repository` |
| `repository` | `entity` | `service`, `controller`, `dto`, `mapper` |
| `entity` | JDK, JPA | toutes les autres couches, Spring Web |
| `config` | tout | — |

### Communication entre fonctionnalités
Une feature peut appeler le **service** d'une autre feature (et utiliser ses **DTO** et ses **exceptions**), jamais son repository,
son entité, son mapper ni son contrôleur. Exemple : `LoanService` appelle `BookService.reserveCopy(bookId)`,
il n'injecte jamais `BookRepository`. Chaque feature garde ses propres tables : on stocke l'identifiant (`bookId`).

## Ordre d'implémentation
Du stockage vers l'exposition. Compiler (`./mvnw -q compile`) après chaque étape.
Les tests indiqués après chaque flèche (→) sont ceux que la couche doit avoir. Dans `/feature`, ils sont écrits par
l'agent `test-writer` après l'implémentation ; en travail direct (hors `/feature`), écris-les juste après l'étape.
1. **Migration Flyway** : `V<n+1>__<description>.sql`.
2. **Entité JPA** (`entity/`) : mapping, contraintes, méthodes métier qui protègent ses propres invariants.
3. **Repository** (`repository/`) : interface Spring Data, requêtes dérivées ou `@Query` → test `@DataJpaTest` + Testcontainers
   si une requête n'est pas triviale.
4. **DTO + mapper** (`dto/`, `mapper/`) : records request/response, conversions.
5. **Service** (`service/`) : règles métier, transactions, levée des exceptions métier → tests unitaires (Mockito ou fakes).
6. **Contrôleur** (`controller/`) : endpoints, validation `@Valid`, sécurité `@PreAuthorize`, gestionnaire d'exceptions → `@WebMvcTest`.
7. **Fin** : `./mvnw test`, `ArchitectureTest` compris.

## Règles du développeur
- Le contrôleur ne contient **aucune** logique métier : il valide le format, appelle un service, renvoie un DTO.
- Jamais d'entité JPA dans une signature de contrôleur (paramètre ou retour) : toujours un DTO.
- Le service reçoit et renvoie des DTO (ou des types simples) ; la conversion entité ↔ DTO se fait dans le service via le mapper.
- Les règles métier vont dans le service. Une règle qui ne concerne qu'une entité et ses champs peut être une méthode de l'entité
  (`book.borrowCopy()`), appelée par le service.
- Injection par constructeur uniquement ; `@Transactional(readOnly = true)` au niveau de la classe de service,
  `@Transactional` sur les méthodes d'écriture.
- Le service ne connaît pas HTTP : il lève des exceptions métier, traduites en `ProblemDetail` par le `@RestControllerAdvice`.
- Pas de repository appelé depuis un contrôleur, un mapper ou une entité.
- La date courante vient d'un `Clock` injecté dans le service.
- Pagination obligatoire sur les listes (`Pageable`), `fetch = LAZY` par défaut sur les relations, attention aux N+1.
- Si `ArchitectureTest` échoue : corriger le code, ne jamais modifier les règles ArchUnit.

## Exemples de code

### Entité
```java
package com.example.library.catalog.entity;

@Entity
@Table(name = "book")
public class Book {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 13)
    private String isbn;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String author;
    @Column(name = "available_copies", nullable = false)
    private int availableCopies;

    protected Book() {}   // pour JPA

    public Book(String isbn, String title, String author, int copies) {
        if (copies < 1) throw new InvalidCopiesException(copies);
        this.id = UUID.randomUUID();
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.availableCopies = copies;
    }

    /** Invariant propre à l'entité (RM-04). */
    public void borrowCopy() {
        if (availableCopies == 0) throw new NoCopyAvailableException(isbn);
        availableCopies--;
    }
    // getters
}
```

### Repository
```java
package com.example.library.catalog.repository;

public interface BookRepository extends JpaRepository<Book, UUID> {
    boolean existsByIsbn(String isbn);
}
```

### DTO et mapper
```java
package com.example.library.catalog.dto;

public record BookCreateRequest(
    @NotBlank @Pattern(regexp = "\\d{13}") String isbn,
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 200) String author,
    @Min(1) int copies) {}

public record BookResponse(UUID id, String isbn, String title, String author, int availableCopies) {}
```
```java
package com.example.library.catalog.mapper;

@Component
public class BookMapper {
    public Book toEntity(BookCreateRequest request) {
        return new Book(request.isbn(), request.title(), request.author(), request.copies());
    }
    public BookResponse toResponse(Book book) {
        return new BookResponse(book.getId(), book.getIsbn(), book.getTitle(), book.getAuthor(), book.getAvailableCopies());
    }
}
```

### Service
```java
package com.example.library.catalog.service;

@Service
@Transactional(readOnly = true)
public class BookService {
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;

    public BookService(BookRepository bookRepository, BookMapper bookMapper) {
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
    }

    @Transactional
    public BookResponse register(BookCreateRequest request) {
        if (bookRepository.existsByIsbn(request.isbn())) {          // RM-07 : ISBN unique
            throw new IsbnAlreadyUsedException(request.isbn());
        }
        Book saved = bookRepository.save(bookMapper.toEntity(request));
        return bookMapper.toResponse(saved);
    }

    public BookResponse getById(UUID id) {
        return bookRepository.findById(id).map(bookMapper::toResponse)
                             .orElseThrow(() -> new BookNotFoundException(id));
    }
}
```

### Contrôleur
```java
package com.example.library.catalog.controller;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {
    private final BookService bookService;

    public BookController(BookService bookService) { this.bookService = bookService; }

    @PostMapping
    @PreAuthorize("hasRole('LIBRARIAN')")
    public ResponseEntity<BookResponse> register(@Valid @RequestBody BookCreateRequest request) {
        BookResponse created = bookService.register(request);
        return ResponseEntity.created(URI.create("/api/v1/books/" + created.id())).body(created);
    }
}
```
Un `@RestControllerAdvice` dans `controller/` traduit les exceptions métier en `ProblemDetail` :
`XxxNotFoundException` → 404, conflit (`IsbnAlreadyUsedException`) → 409, règle métier violée → 422.

## Tests par couche
| Couche | Type de test | Outils | Exemple |
|---|---|---|---|
| `entity` | unitaire pur (si l'entité a des méthodes métier) | JUnit 5, AssertJ | `borrowCopy()` refuse quand il n'y a plus d'exemplaire |
| `service` | unitaire | JUnit 5 + Mockito (repository mocké) ou fake en mémoire, `Clock.fixed(...)` | ISBN déjà utilisé → `IsbnAlreadyUsedException` |
| `repository` | intégration | `@DataJpaTest` + Testcontainers PostgreSQL | requête `@Query` ou dérivée non triviale |
| `controller` | slice web | `@WebMvcTest`, MockMvc, service en `@MockitoBean` | 201 + Location ; 400 si ISBN invalide ; 403 sans rôle ; 409 si doublon |
| architecture | règles | ArchUnit (`ArchitectureTest`) | toujours vert |

- Une règle métier se teste **dans le service** (ou dans l'entité si elle y vit), pas dans le contrôleur.
- Le test de contrôleur vérifie le contrat HTTP (codes, validation, sécurité, format `ProblemDetail`), pas la règle métier.

## Grille de revue
Toute violation de la règle de dépendance est **BLOQUANT**.
- Contrôleur qui injecte un repository ou qui manipule une entité JPA (paramètre, retour, variable).
- Logique métier dans un contrôleur (`if (book.getAvailableCopies() == 0)`) ou dans un repository (`default` method métier).
- Service qui importe `org.springframework.web` ou renvoie une `ResponseEntity`.
- Repository appelé depuis un contrôleur, un mapper ou une entité, ou repository d'une autre feature appelé directement.
- Entité qui dépend d'un DTO, d'un service ou d'un repository.
- Package global par couche (`com.example.app.controllers`) au lieu d'un découpage par fonctionnalité.
- Liste non paginée, relation `EAGER`, N+1.
- `LocalDate.now()` sans `Clock` dans un service.
- Une feature qui utilise le repository, l'entité, le mapper ou le contrôleur d'une autre feature (au lieu de son service et de ses DTO).
- `ArchitectureTest` absent, modifié ou rouge.

## Format de réponse finale
```
Architecture appliquée : couche

Fichiers créés / modifiés
- migrations  : …
- entity      : …
- repository  : …
- dto / mapper: …
- service     : …
- controller  : …
- config      : …

Vérifications
- Compilation        : OK / KO
- Tests              : <n> passés, <n> échoués
- ArchitectureTest   : OK / KO

Règles métier couvertes : RM-xx → service / entité
Points d'attention : …
```
