---
name: hexagonal-architecture
description: Architecture hexagonale (ports & adapters) et clean architecture du backend Spring Boot — structure des packages, règle de dépendance, exemples de code par couche, tests par couche et règles ArchUnit. À charger avant de concevoir, écrire ou relire du code backend.
---

# Architecture hexagonale + clean architecture

## 1. L'idée en une phrase
Le métier est au centre et ne dépend de rien ; la technique (HTTP, JPA, Redis, SMS) est branchée autour
via des **ports** (interfaces définies par le cœur) et des **adapters** (implémentations techniques).

```
            adapter.in.web ──► application.port.in ◄── application.usecase ──► application.port.out ◄── adapter.out.persistence
                                                              │
                                                              ▼
                                                           domain
```
Sens des dépendances : **toujours vers l'intérieur**. `domain` ne connaît personne.

## 2. Packages (par bounded context)
```
com.example.library.loan/
  domain/
    model/        Loan, LoanId, MemberId, BookId           (agrégats, value objects)
    exception/    LoanLimitReachedException, ...
    service/      LoanPolicy                                (règle sur plusieurs agrégats)
  application/
    port/in/      BorrowBookUseCase, BorrowBookCommand
    port/out/     LoadActiveLoansPort, SaveLoanPort, LoadBookPort
    usecase/      BorrowBookService                         (implémente BorrowBookUseCase)
  adapter/
    in/web/       LoanController, BorrowBookRequest, LoanResponse, LoanWebMapper, LoanExceptionHandler
    out/persistence/ LoanJpaEntity, LoanJpaRepository, LoanPersistenceMapper, LoanPersistenceAdapter
  config/         (beans spécifiques au contexte si besoin)
com.example.shared/
  UseCase                                                   (annotation, voir §4)
```

| Couche | Peut dépendre de | Ne doit jamais dépendre de |
|---|---|---|
| `domain` | JDK uniquement | Spring, JPA, Jakarta Validation, Lombok, `application`, `adapter` |
| `application` | `domain`, `shared` | `adapter`, `config`, JPA, Spring Web, Spring Data |
| `adapter.in` | `application.port.in`, `domain` (types) | `adapter.out`, `application.usecase`, `application.port.out` |
| `adapter.out` | `application.port.out`, `domain` | `adapter.in`, `application.usecase`, `application.port.in` |
| `config` | tout | — |

## 3. Domaine : Java pur, riche en comportement
Pas d'entité « anémique » avec des setters : l'objet protège ses invariants.

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

    /** Création d'un nouvel emprunt (règle métier). */
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
    // getters sans setters
}
```

```java
package com.example.library.loan.domain.service;

/** Règle qui porte sur plusieurs emprunts : un service de domaine, sans état, sans framework. */
public final class LoanPolicy {
    public static final int MAX_ACTIVE_LOANS = 3;

    public void checkCanBorrow(List<Loan> activeLoans, LocalDate today) {
        if (activeLoans.stream().anyMatch(l -> l.isOverdue(today))) throw new MemberHasOverdueLoanException();
        if (activeLoans.size() >= MAX_ACTIVE_LOANS) throw new LoanLimitReachedException(MAX_ACTIVE_LOANS);
    }
}
```

Exceptions métier : héritent d'une `DomainException(String code)` du domaine, sans référence à HTTP.

## 4. Application : les cas d'utilisation
Un use case = une intention utilisateur = une interface en `port/in` + une implémentation en `usecase/`.

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

// port/out — nommés selon le besoin du use case, pas selon la technique
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

Le use case **orchestre** (charger → décider via le domaine → sauvegarder). La décision métier reste dans le domaine.
`Clock` est injecté pour rendre la date testable.

### Annotation `@UseCase` (compromis assumé)
```java
package com.example.shared;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Transactional
public @interface UseCase {}
```
Les classes `application` n'importent que `@UseCase` : aucune dépendance Spring directe dans leur code.
Variante 100 % pure : pas d'annotation, déclaration des beans dans `config/` avec `@Bean`, et transactions gérées par un décorateur.

## 5. Adapter entrant : web
```java
@RestController
@RequestMapping("/api/v1/loans")
class LoanController {
    private final BorrowBookUseCase borrowBookUseCase;   // un port d'entrée, jamais un service concret

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
- Validation de format (`@NotNull`, `@Size`) ici ; règles métier dans le domaine.
- L'identité de l'utilisateur vient du JWT, jamais du corps de la requête.
- `LoanExceptionHandler` (`@RestControllerAdvice`) traduit `DomainException` en `ProblemDetail` (409/422) et les « not found » en 404.

## 6. Adapter sortant : persistance
```java
@Entity @Table(name = "loan")
class LoanJpaEntity { @Id UUID id; UUID memberId; UUID bookId; LocalDate borrowedOn; LocalDate dueOn; LocalDate returnedOn; }

interface LoanJpaRepository extends JpaRepository<LoanJpaEntity, UUID> {
    List<LoanJpaEntity> findByMemberIdAndReturnedOnIsNull(UUID memberId);
}

@Component
class LoanPersistenceAdapter implements LoadActiveLoansPort, SaveLoanPort {
    private final LoanJpaRepository repository;
    private final LoanPersistenceMapper mapper;
    // constructeur

    @Override public List<Loan> loadActiveLoansOf(MemberId memberId) {
        return repository.findByMemberIdAndReturnedOnIsNull(memberId.value()).stream().map(mapper::toDomain).toList();
    }
    @Override public void save(Loan loan) { repository.save(mapper.toJpa(loan)); }
}
```
- Le mapper utilise `Loan.restore(...)` pour reconstruire l'agrégat.
- Les classes de l'adapter sont package-private : seul le port est visible de l'extérieur.

## 7. Tests par couche
| Couche | Type de test | Outils | Exemple |
|---|---|---|---|
| `domain` | unitaire pur, rapide | JUnit 5, AssertJ (pas de Spring, pas de Mockito) | `Loan.open` calcule l'échéance à J+14 |
| `application` | unitaire avec ports simulés | JUnit 5 + fakes en mémoire (ou Mockito) | refus du 4ᵉ emprunt actif |
| `adapter.in.web` | slice web | `@WebMvcTest`, MockMvc, use case mocké | 201 + Location ; 403 sans rôle ; 409 si limite |
| `adapter.out.persistence` | intégration | `@DataJpaTest` + Testcontainers PostgreSQL | l'adapter relit ce qu'il a sauvé |
| architecture | règles | ArchUnit | voir §8 |

Préfère les **fakes** pour les ports de sortie (`InMemoryLoanRepository implements LoadActiveLoansPort, SaveLoanPort`) :
les tests restent lisibles et ne cassent pas au moindre refactoring.

## 8. Garde-fou ArchUnit (obligatoire dans chaque projet)
Dépendance Maven : `com.tngtech.archunit:archunit-junit5` (scope `test`, dernière version sur Maven Central).
Tant que des packages sont vides (début de projet), ajoute `archRule.failOnEmptyShould=false`
dans `src/test/resources/archunit.properties`, sinon les règles échouent faute de classes à vérifier.

```java
package com.example;

@AnalyzeClasses(packages = "com.example", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_pure = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "jakarta.validation..", "lombok..",
            "..application..", "..adapter..", "..config..")
        .because("le domaine ne dépend d'aucun framework ni d'aucune couche externe");

    @ArchTest
    static final ArchRule application_does_not_know_adapters = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter..", "..config..", "jakarta.persistence..",
            "org.springframework.web..", "org.springframework.data..")
        .because("l'application parle à l'extérieur uniquement via ses ports");

    @ArchTest
    static final ArchRule inbound_adapters_use_input_ports_only = noClasses().that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.out..", "..application.usecase..", "..application.port.out..")
        .because("un contrôleur n'appelle qu'un use case via son interface");

    @ArchTest
    static final ArchRule outbound_adapters_implement_output_ports_only = noClasses().that().resideInAPackage("..adapter.out..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.in..", "..application.usecase..", "..application.port.in..")
        .because("un adapter sortant ne déclenche pas de use case");
}
```

## 9. Erreurs fréquentes à refuser en revue
- `@Entity` ou `@Column` dans `domain` → créer une `XxxJpaEntity` dans l'adapter.
- Contrôleur qui injecte un `JpaRepository` ou `BorrowBookService` (classe concrète).
- `if (activeLoans.size() >= 3)` dans le use case ou le contrôleur → la règle va dans le domaine.
- Port de sortie nommé d'après la technique (`LoanJpaPort`) → le nommer d'après le besoin (`LoadActiveLoansPort`).
- Un use case qui en appelle un autre → extraire la logique commune dans le domaine.
- Setters publics sur un agrégat.
