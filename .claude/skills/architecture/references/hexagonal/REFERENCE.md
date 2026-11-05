# Référence d'architecture — Hexagonale (ports & adapters) + clean architecture

## Résumé
- **Architecture active : hexagonale (ports & adapters).** Le métier est au centre et ne dépend de rien.
- Packages par bounded context : `domain/` (Java pur), `application/` (`port/in`, `port/out`, `usecase`),
  `adapter/` (`in/web`, `out/persistence`, `out/<autre>`), `config/`.
- **Règle de dépendance : `adapter → application → domain`, jamais l'inverse.**
- `domain` n'importe ni Spring, ni JPA, ni Jakarta Validation, ni Lombok.
- `application` n'importe ni `adapter`, ni `config`, ni JPA, ni Spring Web/Data.
- Un contrôleur n'appelle qu'un port d'entrée (interface de use case) ; un adapter sortant implémente un port de sortie.
- Entité JPA et objet du domaine sont deux classes distinctes, reliées par un mapper dans l'adapter.
- Les règles métier vivent dans le domaine (agrégats, value objects, services de domaine).
- Ordre d'implémentation : domaine → application → adapter sortant → adapter entrant → config.
- `ArchitectureTest` (ArchUnit) doit toujours passer.

## Structure des packages
```
com.example.<app>.<boundedContext>/
  domain/
    model/            agrégats, entités du domaine, value objects (LoanId, Isbn…)
    exception/        exceptions métier (héritent de DomainException)
    service/          règles métier portant sur plusieurs agrégats (LoanPolicy)
  application/
    port/in/          interfaces des use cases + Command / Query (BorrowBookUseCase, BorrowBookCommand)
    port/out/         besoins vers l'extérieur, nommés d'après le besoin (LoadActiveLoansPort, SaveLoanPort)
    usecase/          implémentations des use cases, annotées @UseCase (BorrowBookService)
  adapter/
    in/web/           contrôleurs REST, DTO request/response, mapper web, @RestControllerAdvice
    out/persistence/  XxxJpaEntity, repository Spring Data, mapper, XxxPersistenceAdapter
    out/<autre>/      Redis, SMS, API externes…
  config/             beans spécifiques au contexte si besoin
com.example.<app>.shared/       @UseCase, types transverses (aucune logique métier)
com.example.<app>.config/       configuration globale (sécurité, Clock…)
```

## Règle de dépendance
Les dépendances pointent **toujours vers l'intérieur**.

```
adapter.in.web ──► application.port.in ◄── application.usecase ──► application.port.out ◄── adapter.out.persistence
                                                   │
                                                   ▼
                                                domain
```

| Couche | Peut dépendre de | Ne doit jamais dépendre de |
|---|---|---|
| `domain` | JDK uniquement | Spring, JPA, Jakarta Validation, Lombok, `application`, `adapter`, `config` |
| `application` | `domain`, `shared` | `adapter`, `config`, JPA, Spring Web, Spring Data |
| `adapter.in` | `application.port.in`, `domain` (types) | `adapter.out`, `application.usecase`, `application.port.out` |
| `adapter.out` | `application.port.out`, `domain` | `adapter.in`, `application.usecase`, `application.port.in` |
| `config` | tout | — |

Les adapters ne se parlent jamais directement : ils passent par un port.

## Ordre d'implémentation
De l'intérieur vers l'extérieur. Compiler (`./mvnw -q compile`) après chaque étape.
1. **Domaine** : value objects, agrégats, services de domaine, exceptions → tests unitaires purs, lancés tout de suite.
2. **Application** : Command/Query, interfaces `port/in` et `port/out`, use cases `@UseCase` → tests avec des fakes des ports.
3. **Adapter sortant** (`adapter/out/persistence`) : migration Flyway, `XxxJpaEntity`, repository Spring Data,
   mapper, adapter qui implémente les ports de sortie → test `@DataJpaTest` + Testcontainers.
4. **Adapter entrant** (`adapter/in/web`) : DTO, contrôleur branché sur le port d'entrée, gestionnaire d'exceptions → `@WebMvcTest`.
5. **Config** : uniquement si un bean doit être déclaré à la main.
6. **Fin** : `./mvnw test`, `ArchitectureTest` compris.

## Règles du développeur
- Aucun import `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `lombok` dans `domain/`.
- Aucun import d'`adapter` ou de `config` dans `application/`.
- Agrégats sans setters publics : création par une méthode de fabrique métier (`Loan.open(...)`),
  reconstitution depuis la base par `restore(...)` (aucune règle rejouée).
- Identifiants métier = value objects (`record BookId(UUID value)`), jamais un `UUID` nu dans le domaine.
- Une règle métier n'est jamais dans un use case, un contrôleur, un adapter ou un mapper : le use case **orchestre**
  (charger → décider via le domaine → sauvegarder), le domaine **décide**.
- Un use case n'en appelle jamais un autre : extraire la logique commune dans le domaine.
- Ports de sortie nommés d'après le besoin (`LoadActiveLoansPort`), jamais d'après la technique (`LoanJpaPort`).
- Le contrôleur injecte l'**interface** du use case, jamais l'implémentation ni un repository.
- La date courante vient d'un `Clock` injecté, jamais de `LocalDate.now()` sans argument.
- Les classes des adapters sont package-private quand c'est possible : seul le port est visible.
- Transactions portées par `@UseCase` (méta-annoté `@Transactional`) ; jamais dans le domaine ni sur un contrôleur.
- Si `ArchitectureTest` échoue : corriger le code, ne jamais modifier les règles ArchUnit.

## Exemples de code

### Domaine
```java
package com.example.library.loan.domain.model;

public record LoanId(UUID value) {
    public LoanId { Objects.requireNonNull(value); }
    public static LoanId newId() { return new LoanId(UUID.randomUUID()); }
}

public final class Loan {
    public static final int LOAN_DURATION_DAYS = 14;

    private final LoanId id;
    private final MemberId memberId;
    private final BookId bookId;
    private final LocalDate borrowedOn;
    private final LocalDate dueOn;
    private LocalDate returnedOn;

    private Loan(LoanId id, MemberId memberId, BookId bookId,
                 LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        this.id = Objects.requireNonNull(id);
        this.memberId = Objects.requireNonNull(memberId);
        this.bookId = Objects.requireNonNull(bookId);
        this.borrowedOn = Objects.requireNonNull(borrowedOn);
        this.dueOn = Objects.requireNonNull(dueOn);
        this.returnedOn = returnedOn;
    }

    /** Création d'un nouvel emprunt (règle métier RM-03 : 14 jours). */
    public static Loan open(MemberId memberId, BookId bookId, LocalDate today) {
        return new Loan(LoanId.newId(), memberId, bookId, today, today.plusDays(LOAN_DURATION_DAYS), null);
    }

    /** Reconstitution depuis la persistance : aucune règle rejouée. */
    public static Loan restore(LoanId id, MemberId memberId, BookId bookId,
                               LocalDate borrowedOn, LocalDate dueOn, LocalDate returnedOn) {
        return new Loan(id, memberId, bookId, borrowedOn, dueOn, returnedOn);
    }

    public void giveBack(LocalDate today) {
        if (returnedOn != null) throw new LoanAlreadyReturnedException(id);
        this.returnedOn = today;
    }

    public boolean isOverdue(LocalDate today) { return returnedOn == null && today.isAfter(dueOn); }
    public boolean isActive() { return returnedOn == null; }
    // getters, aucun setter
}
```

```java
package com.example.library.loan.domain.service;

/** Règle portant sur plusieurs emprunts : service de domaine, sans état, sans framework. */
public final class LoanPolicy {
    public static final int MAX_ACTIVE_LOANS = 3;

    public void checkCanBorrow(List<Loan> activeLoans, LocalDate today) {
        if (activeLoans.stream().anyMatch(l -> l.isOverdue(today))) throw new MemberHasOverdueLoanException();
        if (activeLoans.size() >= MAX_ACTIVE_LOANS) throw new LoanLimitReachedException(MAX_ACTIVE_LOANS);
    }
}
```
Les exceptions métier héritent d'une `DomainException(String code)` du domaine, sans aucune notion de HTTP.

### Application
```java
// port/in
public interface BorrowBookUseCase {
    LoanId borrow(BorrowBookCommand command);
}

public record BorrowBookCommand(MemberId memberId, BookId bookId) {
    public BorrowBookCommand {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(bookId, "bookId");
    }
}

// port/out — nommés d'après le besoin du use case
public interface LoadActiveLoansPort { List<Loan> loadActiveLoansOf(MemberId memberId); }
public interface SaveLoanPort        { void save(Loan loan); }
public interface LoadBookPort        { boolean isAvailable(BookId bookId); }
```

```java
// usecase
@UseCase
public class BorrowBookService implements BorrowBookUseCase {

    private final LoadActiveLoansPort loadActiveLoansPort;
    private final LoadBookPort loadBookPort;
    private final SaveLoanPort saveLoanPort;
    private final Clock clock;
    private final LoanPolicy loanPolicy = new LoanPolicy();

    public BorrowBookService(LoadActiveLoansPort loadActiveLoansPort, LoadBookPort loadBookPort,
                             SaveLoanPort saveLoanPort, Clock clock) {
        this.loadActiveLoansPort = loadActiveLoansPort;
        this.loadBookPort = loadBookPort;
        this.saveLoanPort = saveLoanPort;
        this.clock = clock;
    }

    @Override
    public LoanId borrow(BorrowBookCommand command) {
        LocalDate today = LocalDate.now(clock);
        if (!loadBookPort.isAvailable(command.bookId())) throw new BookNotAvailableException(command.bookId());
        loanPolicy.checkCanBorrow(loadActiveLoansPort.loadActiveLoansOf(command.memberId()), today);
        Loan loan = Loan.open(command.memberId(), command.bookId(), today);
        saveLoanPort.save(loan);
        return loan.getId();
    }
}
```

Annotation `@UseCase` (compromis assumé : les classes `application` n'importent qu'elle, aucune dépendance Spring directe) :
```java
package com.example.library.shared;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Transactional
public @interface UseCase {}
```

### Adapter entrant (web)
```java
@RestController
@RequestMapping("/api/v1/loans")
class LoanController {
    private final BorrowBookUseCase borrowBookUseCase;   // l'interface, jamais BorrowBookService

    LoanController(BorrowBookUseCase borrowBookUseCase) { this.borrowBookUseCase = borrowBookUseCase; }

    @PostMapping
    @PreAuthorize("hasRole('MEMBER')")
    ResponseEntity<LoanResponse> borrow(@Valid @RequestBody BorrowBookRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        var command = new BorrowBookCommand(new MemberId(UUID.fromString(jwt.getSubject())),
                                            new BookId(request.bookId()));
        LoanId id = borrowBookUseCase.borrow(command);
        return ResponseEntity.created(URI.create("/api/v1/loans/" + id.value()))
                             .body(new LoanResponse(id.value()));
    }
}

record BorrowBookRequest(@NotNull UUID bookId) {}
record LoanResponse(UUID id) {}
```
Un `@RestControllerAdvice` du même package traduit `DomainException` en `ProblemDetail` (409/422) et les « not found » en 404.

### Adapter sortant (persistance)
```java
@Entity @Table(name = "loan")
class LoanJpaEntity { @Id UUID id; UUID memberId; UUID bookId; LocalDate borrowedOn; LocalDate dueOn; LocalDate returnedOn; }

interface LoanJpaRepository extends JpaRepository<LoanJpaEntity, UUID> {
    List<LoanJpaEntity> findByMemberIdAndReturnedOnIsNull(UUID memberId);
}

@Component
class LoanPersistenceAdapter implements LoadActiveLoansPort, SaveLoanPort {
    private final LoanJpaRepository repository;
    private final LoanPersistenceMapper mapper;   // utilise Loan.restore(...)
    // constructeur

    @Override public List<Loan> loadActiveLoansOf(MemberId memberId) {
        return repository.findByMemberIdAndReturnedOnIsNull(memberId.value()).stream().map(mapper::toDomain).toList();
    }
    @Override public void save(Loan loan) { repository.save(mapper.toJpa(loan)); }
}
```

## Tests par couche
| Couche | Type de test | Outils | Exemple |
|---|---|---|---|
| `domain` | unitaire pur, rapide | JUnit 5, AssertJ — ni Spring, ni Mockito | `Loan.open` fixe l'échéance à J+14 |
| `application` | unitaire, ports simulés | JUnit 5 + **fakes en mémoire** des ports, `Clock.fixed(...)` | refus du 4ᵉ emprunt actif |
| `adapter.in.web` | slice web | `@WebMvcTest`, MockMvc, use case en `@MockitoBean` | 201 + Location ; 403 sans rôle ; 409 si limite |
| `adapter.out.persistence` | intégration | `@DataJpaTest` + Testcontainers PostgreSQL | l'adapter relit ce qu'il a sauvé |
| architecture | règles | ArchUnit (`ArchitectureTest`) | toujours vert |

- Une règle métier se teste **d'abord dans le domaine** ; le test de use case vérifie l'orchestration.
- Fakes réutilisables dans `src/test/java/.../<context>/application/fake/`
  (`InMemoryLoanRepository implements LoadActiveLoansPort, SaveLoanPort`).

## Grille de revue
Toute violation de la règle de dépendance est **BLOQUANT**.
- `@Entity`, `@Column` ou tout import Spring/JPA/Lombok dans `domain` → créer une `XxxJpaEntity` dans l'adapter.
- Contrôleur qui injecte un `JpaRepository`, une classe `usecase` concrète ou un port de sortie.
- Règle métier (`if (activeLoans.size() >= 3)`) dans un use case, un contrôleur, un adapter ou un mapper.
- Port de sortie nommé d'après la technique (`LoanJpaPort`).
- Use case qui en appelle un autre.
- Setters publics sur un agrégat ; identifiant métier en `UUID` nu dans le domaine.
- `LocalDate.now()` sans `Clock`.
- Use case qui renvoie une `XxxJpaEntity` ou un DTO web.
- `ArchitectureTest` absent, modifié ou rouge.

## Format de réponse finale
```
Architecture appliquée : hexagonal

Fichiers créés / modifiés
- domain        : …
- application   : …
- adapter.out   : …
- adapter.in    : …
- config        : …
- migrations    : …

Vérifications
- Compilation        : OK / KO
- Tests              : <n> passés, <n> échoués
- ArchitectureTest   : OK / KO

Règles métier couvertes : RM-xx → classe(s) du domaine
Points d'attention : …
```
