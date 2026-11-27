# Plan technique — Emprunter, consulter et rendre (module loan)

> Spec : `docs/specs/loan.md` (validée le 2026-11-24, prioritaire). Contrat : `docs/api/loan.md`.
> Plan validé par l'utilisateur le 2026-11-24.

## 1. Architecture appliquée
hexagonal (`.claude/factory.json`). Référence : `<kit>/skills/architecture/references/hexagonal/REFERENCE.md`.
Intégrations : `postgresql` (loan, catalog) ; `redis` uniquement dans `catalog` (invalidation existante, `modules.redis = ["catalog"]`).
Options (profil `top`, aucune surcharge dans `stack.json`) : `mapping=manuel`, `lombok=hors-domaine`, `errors=problem-detail`,
`ids=uuid`, `auth-token=cookie-httponly`, `front-ui=primeng-bootstrap`, `front-forms=reactive`.
`ArchitectureTest` et `CacheArchitectureTest` restent inchangés.

## 2. Résumé
- Nouveau module `loan`. Les règles RM-01 à RM-03, RM-05 et RM-LOAN-28 vivent dans `Loan` et `LoanPolicy`. Trois use cases : emprunter, lister, rendre.
  `loan` appelle `catalog` uniquement par `catalog.api.CatalogApi`, depuis l'adapter sortant `loan.adapter.out.catalog`.
- `catalog` reçoit plusieurs ajouts :
  - deux opérations de domaine sur `Book` (prise et restitution d'un exemplaire, RM-04, bornes `0..total`) ;
  - trois use cases (réserver, libérer, lire par lot) et la façade `adapter.in.module` ;
  - un port d'écriture `@AffectsAvailableBooks`, décoré par le cache, avec une mise à jour SQL conditionnelle et atomique.
- La limite de 3 est sérialisée par membre avec un **verrou consultatif PostgreSQL de transaction** (`pg_advisory_xact_lock`) : aucune nouvelle technologie, aucune nouvelle table.
  Fuseau `app.timezone` dans `ClockConfig`. Membre de test amorcé en profil `local`. Frontend : écran `/emprunts`, bouton « Emprunter » dans le catalogue, lien d'en-tête, mode `mock`.

## 3. Décision Q7 — sérialisation de la limite de 3 (RM-LOAN-08)
**Retenu : verrou consultatif de transaction PostgreSQL, par membre.** C'est la première instruction de la transaction d'emprunt :
`SELECT pg_advisory_xact_lock(<namespace loan>, hashtext(<memberId>))`. Ensuite seulement, on lit les emprunts actifs, on applique la politique, on réserve l'exemplaire et on insère l'emprunt.

| Option | Verdict |
|---|---|
| `SELECT … FOR UPDATE` sur les emprunts actifs du membre | Rejetée : quand le membre n'a aucun emprunt, il n'y a aucune ligne à verrouiller (fantômes). Le comportement dépend aussi de la relecture après l'attente. Fragile. |
| Table compteur `loan_member(active_count CHECK <= 3)` | Rejetée : duplique un état dérivé, ajoute une table et une écriture de plus. Le retard (RM-02) doit être lu de toute façon. |
| Isolation `SERIALIZABLE` | Rejetée : impose de rejouer la transaction sur une erreur 40001, avec une logique de reprise dans un adapter. |
| **Verrou consultatif `pg_advisory_xact_lock`** | **Retenu.** Fonction native de PostgreSQL, déjà dans la stack. Libéré automatiquement au commit ou au rollback. Aucun schéma ajouté. En READ COMMITTED, chaque instruction après l'obtention du verrou voit les emprunts déjà validés du même membre. Une collision de hachage entre deux membres ne fait que sérialiser à tort deux requêtes, sans risque pour la correction. |

Effets :
- deux emprunts simultanés du même membre sont joués l'un après l'autre. « 2 actifs + 2 requêtes » donne un 201 et un 409 `LOAN_LIMIT_REACHED` ;
- « même livre × 2 » donne un 201 et un 409 `BOOK_ALREADY_BORROWED`, décidé par `LoanPolicy` ;
- l'index unique partiel `uk_loan_member_book_active` reste un **filet de sécurité** (RM-LOAN-28) ;
- `SET LOCAL lock_timeout = '5s'` est exécuté juste avant le verrou dans le même adapter, pour qu'aucun thread n'attende indéfiniment.

Le stock (RM-LOAN-08 et 31) ne dépend pas de ce verrou. `UPDATE … WHERE available_copies >= 1` est atomique : PostgreSQL réévalue la condition sur la version validée de la ligne après l'attente. La contrainte `ck_book_available_copies_range` (V1) complète la garantie.

Le retour double simultané (RM-LOAN-11) passe par une lecture `FOR UPDATE` de la ligne d'emprunt, puis un `UPDATE … WHERE returned_on IS NULL` conditionnel.

Ordre des verrous :
- emprunt : verrou du membre, puis ligne `book` ;
- retour : ligne `loan`, puis ligne `book`.

L'emprunt ne verrouille aucune ligne `loan` existante (il insère seulement), donc aucun cycle n'est possible : pas d'interblocage.

## 4. Migration
Dernière migration : `V2__create_identity_tables.sql`. Nouvelle migration : **`V3__create_table_loan.sql`** (SQL en §5.3). Pas de clé étrangère vers `book` ni vers `app_user` (modules distincts). Aucune migration pour `book` (RM-LOAN-31).

## 5. Découpage par couche (backend)

### 5.1 Module `loan` — Domaine (`backend/src/main/java/com/example/library/loan/domain/`), Java pur
| Classe | Responsabilité | Règles |
|---|---|---|
| `model/LoanId.java` | `record LoanId(UUID value)`, `newId()` | — |
| `model/MemberId.java` | `record MemberId(UUID value)` | RM-LOAN-18 |
| `model/BookId.java` | `record BookId(UUID value)`, propre à loan (distinct de celui de catalog) | RM-LOAN-17 |
| `model/Loan.java` | Agrégat sans setter.<br>- `LOAN_DURATION_DAYS = 14` ;<br>- `open(MemberId, BookId, LocalDate today)` : échéance J+14 ;<br>- `restore(...)` ;<br>- `giveBack(LocalDate today)` : lève `LoanAlreadyReturnedException` si déjà rendu ;<br>- `isActive()` ;<br>- `isOverdue(today)` : actif **et** `today.isAfter(dueOn)` ;<br>- `concerns(BookId)` ;<br>- `static Comparator<Loan> DISPLAY_ORDER` : `dueOn`, puis `id.value().toString()`. La comparaison de chaînes hexadécimales en minuscules reproduit l'ordre `uuid` de PostgreSQL ; `UUID.compareTo` est signé et ne le reproduit pas. | RM-03, RM-05, RM-LOAN-04, 05, 10, 11, 14 |
| `service/LoanPolicy.java` | `MAX_ACTIVE_LOANS = 3`. `checkCanBorrow(List<Loan> activeLoans, BookId bookId, LocalDate today)` vérifie dans cet ordre : retard → `MemberHasOverdueLoanException`, `size >= 3` → `LoanLimitReachedException`, livre déjà en cours → `BookAlreadyBorrowedException` (ordre RM-LOAN-06, points 2 à 4). | RM-01, RM-02, RM-LOAN-02, 03, 06, 28 |
| `exception/DomainException.java` | Abstraite, `code` stable, sans HTTP (copie du modèle de catalog : un module n'importe pas l'autre) | — |
| `exception/BookNotFoundException.java` | `BOOK_NOT_FOUND` | RM-LOAN-06.1 |
| `exception/MemberHasOverdueLoanException.java` | `MEMBER_HAS_OVERDUE_LOAN` | RM-LOAN-03 |
| `exception/LoanLimitReachedException.java` | `LOAN_LIMIT_REACHED`, porte `max` | RM-LOAN-02 |
| `exception/BookAlreadyBorrowedException.java` | `BOOK_ALREADY_BORROWED` | RM-LOAN-28 |
| `exception/BookNotAvailableException.java` | `BOOK_NOT_AVAILABLE` | RM-LOAN-01 |
| `exception/LoanNotFoundException.java` | `LOAN_NOT_FOUND` | RM-LOAN-13 |
| `exception/LoanAlreadyReturnedException.java` | `LOAN_ALREADY_RETURNED` | RM-LOAN-11 |

### 5.2 Module `loan` — Application (`loan/application/`)
| Classe | Responsabilité | Règles |
|---|---|---|
| `port/in/BorrowBookUseCase.java` + `BorrowBookCommand(MemberId, BookId)` | `Loan borrow(BorrowBookCommand)` | RM-LOAN-01 à 09, 28 |
| `port/in/ListCurrentLoansUseCase.java` + `ListCurrentLoansQuery(MemberId)` | `List<CurrentLoanView> listCurrentLoans(query)` | RM-LOAN-14, 32 |
| `port/in/CurrentLoanView.java` | `record (LoanId id, BookId bookId, String isbn, String title, String author, LocalDate borrowedOn, LocalDate dueOn, boolean overdue)`. Champs **aplatis** : un adapter entrant ne doit voir aucun type de `port.out`. | RM-LOAN-14 |
| `port/in/ReturnLoanUseCase.java` + `ReturnLoanCommand(MemberId, LoanId)` | `void giveBack(ReturnLoanCommand)` | RM-LOAN-10 à 13 |
| `port/out/LockMemberLoansPort.java` | `void lockLoansOf(MemberId)` : sérialise les emprunts d'un membre jusqu'à la fin de la transaction (§3) | RM-LOAN-08 |
| `port/out/LoadActiveLoansPort.java` | `List<Loan> loadActiveLoansOf(MemberId)` | RM-LOAN-02, 03, 14, 28 |
| `port/out/LoadMemberLoanForUpdatePort.java` | `Optional<Loan> loadForUpdate(LoanId, MemberId)` : actif ou rendu, verrouillé ; vide s'il est inconnu ou appartient à un autre membre | RM-LOAN-11, 13 |
| `port/out/SaveLoanPort.java` | `void saveNew(Loan)` lève `BookAlreadyBorrowedException` si l'index unique est violé ; `void saveReturned(Loan)` fait un UPDATE conditionnel et lève `LoanAlreadyReturnedException` si 0 ligne | RM-LOAN-07, 10, 11, 28 |
| `port/out/ReserveCopyPort.java` + `CopyReservation` (enum `RESERVED`, `BOOK_NOT_FOUND`, `NO_COPY_AVAILABLE`) | `CopyReservation reserve(BookId)` | RM-LOAN-01, 07, 31 |
| `port/out/ReleaseCopyPort.java` + `CopyRelease` (enum `RELEASED`, `BOOK_NOT_FOUND`) | `CopyRelease release(BookId)` | RM-LOAN-10 |
| `port/out/LoadBookSummariesPort.java` + `BookSummary(BookId, String isbn, String title, String author)` | `Map<BookId, BookSummary> loadSummaries(Set<BookId>)` : **un seul appel** | RM-LOAN-32 |
| `usecase/BorrowBookService.java` | `@UseCase`. Dépendances : 5 ports (`LockMemberLoansPort`, `LoadBookSummariesPort`, `LoadActiveLoansPort`, `ReserveCopyPort`, `SaveLoanPort`) + `Clock` (dépendance d'infrastructure, non comptée ; D11).<br>Étapes :<br>1. `today = LocalDate.now(clock)` ;<br>2. `lockLoansOf(member)` ;<br>3. `loadSummaries(Set.of(book)).containsKey(book)`, sinon `BookNotFoundException` ;<br>4. `policy.checkCanBorrow(loadActiveLoansOf(member), book, today)` ;<br>5. `reserve(book)` : `BOOK_NOT_FOUND` → 404, `NO_COPY_AVAILABLE` → `BookNotAvailableException` ;<br>6. `Loan.open(...)` ;<br>7. `saveNew` ;<br>8. log INFO `Emprunt créé : loanId, memberId, bookId`.<br>Réservation et insertion partagent la même transaction : tout échec annule l'autre. | RM-LOAN-01 à 09, 20, 28 |
| `usecase/ListCurrentLoansService.java` | `@UseCase(readOnly = true)`. Charge les emprunts actifs. Liste vide : renvoie `[]` **sans appeler** le port catalogue. Sinon un seul `loadSummaries(ids)`, puis tri `Loan.DISPLAY_ORDER` et `overdue = loan.isOverdue(today)`. Livre absent du lot : champs `null`. | RM-LOAN-14, 32 |
| `usecase/ReturnLoanService.java` | `@UseCase`. Étapes :<br>1. `loadForUpdate(loanId, member)`, sinon `LoanNotFoundException` ;<br>2. `loan.giveBack(today)` ;<br>3. `saveReturned(loan)` ;<br>4. `release(bookId)` : si `BOOK_NOT_FOUND`, WARN et le retour est conservé (point C.6) ;<br>5. log INFO. | RM-LOAN-10 à 13, 20 |

### 5.3 Module `loan` — Adapters sortants
**Persistance** (`loan/adapter/out/persistence/`, rôle `sortant-persistance`) :
| Classe | Responsabilité |
|---|---|
| `LoanJpaEntity.java` | `@Entity @Table(name="loan")`, implémente `Persistable<UUID>`. Le drapeau transitoire `isNew` vaut vrai à la construction et passe à faux par `@PostLoad` et `@PostPersist`, ce qui évite le `SELECT` d'un `merge` sur un id assigné. Pas de colonne `version`. |
| `LoanJpaRepository.java` | Méthodes :<br>- `findByMemberIdAndReturnedOnIsNullOrderByDueOnAscIdAsc(UUID)` ;<br>- `@Lock(PESSIMISTIC_WRITE) @QueryHints(jakarta.persistence.lock.timeout=5000) Optional<LoanJpaEntity> findByIdAndMemberId(UUID, UUID)` ;<br>- `@Modifying @Query(native) int markReturned(id, memberId, returnedOn)` : `UPDATE loan SET returned_on = :d WHERE id = :id AND member_id = :m AND returned_on IS NULL` ;<br>- requête native de verrou (voir `LoanPersistenceAdapter`). |
| `LoanPersistenceMapper.java` | `toJpa(Loan)` et `toDomain(entity)` (par `Loan.restore`) |
| `LoanPersistenceAdapter.java` (package-private) | Implémente `LockMemberLoansPort`, `LoadActiveLoansPort`, `LoadMemberLoanForUpdatePort`, `SaveLoanPort`.<br>- `lockLoansOf` : `SET LOCAL lock_timeout = '5s'`, puis `SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:ns, hashtext(CAST(:member AS text)))) l`, avec `ns` constante `int` documentée (ex. `0x4C4F414E`, « LOAN »). Une requête `SELECT` qui renvoie `void` ne se mappe pas avec Spring Data, d'où l'enveloppe `count(*)`.<br>- `saveNew` : `saveAndFlush`. Si `DataIntegrityViolationException` porte la contrainte `uk_loan_member_book_active`, lève `BookAlreadyBorrowedException` (même technique que `BookPersistenceAdapter`). |

```sql
-- V3__create_table_loan.sql
-- Module loan : emprunts (RM-01 à RM-05, RM-LOAN-28). member_id et book_id sans clé étrangère :
-- app_user (identity) et book (catalog) appartiennent à d'autres modules.
CREATE TABLE loan (
    id          UUID NOT NULL,
    member_id   UUID NOT NULL,
    book_id     UUID NOT NULL,
    borrowed_on DATE NOT NULL,
    due_on      DATE NOT NULL,
    returned_on DATE,
    CONSTRAINT pk_loan PRIMARY KEY (id),
    CONSTRAINT ck_loan_due_on_not_before_borrowed_on CHECK (due_on >= borrowed_on),
    CONSTRAINT ck_loan_returned_on_not_before_borrowed_on CHECK (returned_on IS NULL OR returned_on >= borrowed_on)
);

-- RM-LOAN-28 : un seul emprunt actif par membre et par livre (filet de sécurité de LoanPolicy).
-- Sert aussi aux lectures des emprunts actifs d'un membre (member_id en tête, même prédicat) :
-- pas d'index ix_loan_member_active séparé (décision D7).
CREATE UNIQUE INDEX uk_loan_member_book_active ON loan (member_id, book_id) WHERE returned_on IS NULL;
```

**Catalogue** (`loan/adapter/out/catalog/`, adapter sortant vers un autre module) :
| Classe | Responsabilité |
|---|---|
| `CatalogBooksAdapter.java` (package-private) | Implémente `ReserveCopyPort`, `ReleaseCopyPort`, `LoadBookSummariesPort` en injectant `catalog.api.CatalogApi` seulement.<br>- Traduit `ReservationResult` et `ReleaseResult` (raisons en chaînes) vers les enums de loan ;<br>- `loadSummaries` : **un** appel `findBooksByIds(ids)`, ensuite indexé par id. |

### 5.4 Module `loan` — Adapter entrant web (`loan/adapter/in/web/`, rôle `entrant-http`)
| Classe | Responsabilité | Règles |
|---|---|---|
| `LoanController.java` | `@RequestMapping("/api/v1/loans")`. Injecte les trois **interfaces** de use case.<br>- `POST` : `borrow(@CurrentMember MemberId member, @Valid @RequestBody BorrowBookRequest body)` → 201 + `Location: /api/v1/loans/{id}` + `LoanCreatedResponse` ;<br>- `GET` → 200 `List<CurrentLoanResponse>` ;<br>- `POST /{id}/return` : `giveBack(@CurrentMember MemberId member, @PathVariable UUID id)` → 204.<br>`@PreAuthorize("hasRole('MEMBER')")` sur chaque méthode. **Le paramètre `@CurrentMember` vient en premier** : il est résolu avant le corps et le chemin, donc un `sub` non UUID donne 401 avant tout 400 ou 404. | RM-LOAN-18, 19 |
| `CurrentMember.java` (annotation) + `CurrentMemberArgumentResolver.java` | Lit le `Jwt` du `SecurityContext`, puis `UUID.fromString(sub)` → `MemberId`. Échec : `InvalidMemberIdentityException`, exception locale au package web (pas de domaine). | RM-LOAN-18 |
| `BorrowBookRequest.java` | `record(@NotNull UUID bookId)`. Les champs inconnus (`memberId`) sont ignorés. | RM-LOAN-19 |
| `LoanCreatedResponse.java` | `record(UUID id, UUID bookId, LocalDate borrowedOn, LocalDate dueOn)` | — |
| `CurrentLoanResponse.java` | `record(UUID id, UUID bookId, String isbn, String title, String author, LocalDate borrowedOn, LocalDate dueOn, boolean overdue)` | RM-LOAN-14 |
| `LoanWebMapper.java` | Mapper manuel statique | — |
| `LoanExceptionHandler.java` | `@RestControllerAdvice(basePackageClasses = LoanController.class) @Order(HIGHEST_PRECEDENCE)` : traductions détaillées ci-dessous. | RM-LOAN-19, 20 |

Traductions de `LoanExceptionHandler` :
- `MethodArgumentNotValidException` et `HttpMessageNotReadableException` → 400 `VALIDATION_FAILED` + `errors[]`, même code que `CatalogExceptionHandler` : `REQUIRED` / `INVALID_FORMAT` ;
- `MethodArgumentTypeMismatchException` sur `id` → 404 `LOAN_NOT_FOUND` ;
- `BookNotFoundException` et `LoanNotFoundException` → 404 ;
- les cinq refus métier et `LoanAlreadyReturnedException` → 409 ;
- `InvalidMemberIdentityException` → 401 `UNAUTHENTICATED` + `WWW-Authenticate: Bearer`, même corps que l'entry point ;
- WARN `Demande d'emprunt refusée : code={}` pour les 404 et 409, sans aucune donnée personnelle.

### 5.5 Module `loan` — Config (`loan/config/`)
| Classe | Responsabilité |
|---|---|
| `LoanWebConfig.java` | `WebMvcConfigurer` qui enregistre `CurrentMemberArgumentResolver` |

### 5.6 Module `catalog` — modifications (`backend/src/main/java/com/example/library/catalog/`)
| Classe | État | Responsabilité | Règles |
|---|---|---|---|
| `domain/model/Book.java` | modifiée | `Book takeCopy()` : renvoie une copie avec `available - 1` ; lève `NoCopyAvailableException` si `!isAvailable()`. `Book returnCopy()` : renvoie `available + 1` ; lève `AvailableCopiesOverflowException` si `available == total`. L'objet reste immuable. | RM-04, RM-LOAN-01, 10, 31 |
| `domain/exception/NoCopyAvailableException.java` | nouvelle | code `BOOK_NOT_AVAILABLE` | RM-04 |
| `domain/exception/AvailableCopiesOverflowException.java` | nouvelle | code `AVAILABLE_COPIES_OVERFLOW` : invariant violé, incohérence de données | RM-LOAN-10 |
| `application/port/in/ReserveCopyUseCase.java` + `ReserveCopyCommand(BookId)` + `CopyReservationOutcome` (enum `RESERVED`, `BOOK_NOT_FOUND`, `NO_COPY_AVAILABLE`) | nouvelles | Renvoie une **issue** et ne lève pas d'exception (voir encadré). | RM-LOAN-01, 31 |
| `application/port/in/ReleaseCopyUseCase.java` + `ReleaseCopyCommand(BookId)` + `CopyReleaseOutcome` (`RELEASED`, `BOOK_NOT_FOUND`) | nouvelles | idem | RM-LOAN-10 |
| `application/port/in/FindBooksByIdsUseCase.java` + `FindBooksByIdsQuery(Set<BookId>)` | nouvelles | `List<Book> findByIds(query)`. Ensemble borné à 50 ids (`MAX_IDS`) : il protège la liste `IN`, et loan envoie au plus 3 ids. | RM-LOAN-32 |
| `application/port/out/LoadBookByIdPort.java` | nouvelle | `Optional<Book> loadById(BookId)`, non décoré par le cache | — |
| `application/port/out/LoadBooksByIdsPort.java` | nouvelle | `List<Book> loadByIds(Set<BookId>)` : **une** requête SQL, non décoré | RM-LOAN-32 |
| `application/port/out/ChangeAvailableCopiesPort.java` | nouvelle, **`@AffectsAvailableBooks`** | `boolean decrementAvailableCopies(BookId)` : faux si 0 ligne modifiée. `boolean incrementAvailableCopies(BookId)` : faux si 0 ligne. | RM-LOAN-16, 31, RM-CAT-42 |
| `application/usecase/ReserveCopyService.java` | nouvelle, `@UseCase` | 1. `loadById` : vide → `BOOK_NOT_FOUND` ;<br>2. `book.takeCopy()` : `NoCopyAvailableException` **capturée dans le use case** → `NO_COPY_AVAILABLE` ;<br>3. `decrementAvailableCopies` : `false` (course perdue) → `NO_COPY_AVAILABLE`, sinon `RESERVED`. | RM-04, RM-LOAN-08, 31 |
| `application/usecase/ReleaseCopyService.java` | nouvelle, `@UseCase` | `loadById` (vide → `BOOK_NOT_FOUND`), `book.returnCopy()` (le dépassement remonte comme erreur technique), `incrementAvailableCopies` (`false` → `AvailableCopiesOverflowException`) → `RELEASED` | RM-LOAN-10 |
| `application/usecase/FindBooksByIdsService.java` | nouvelle, `@UseCase(readOnly = true)` | Délègue à `LoadBooksByIdsPort`. Ensemble vide → `[]` sans requête. | RM-LOAN-32 |
| `adapter/out/persistence/BookJpaRepository.java` | modifiée | Deux requêtes natives `@Modifying(flushAutomatically = true)` :<br>- `decrementAvailableCopies(id)` : `UPDATE book SET available_copies = available_copies - 1, version = version + 1 WHERE id = :id AND available_copies >= 1` ;<br>- `incrementAvailableCopies(id)` : `... + 1 ... WHERE id = :id AND available_copies < total_copies`.<br>`findAllById` (Spring Data, un seul `WHERE id IN (...)`) réutilisé. | RM-LOAN-31, 32 |
| `adapter/out/persistence/BookPersistenceAdapter.java` | modifiée | Implémente en plus `LoadBookByIdPort`, `LoadBooksByIdsPort`, `ChangeAvailableCopiesPort` (`rows == 1`) | — |
| `adapter/out/cache/AvailableBooksCacheAdapter.java` | modifiée | Implémente `ChangeAvailableCopiesPort` : délègue à `@Qualifier("bookPersistenceAdapter")`, puis `evictAfterCommit()` si le résultat est `true`. `KEY`, codec et TTL inchangés. **Passe à 6 dépendances** (point C.11). Javadoc mise à jour. | RM-LOAN-16, RM-CAT-42 |
| `api/CatalogApi.java` | nouvelle (JDK seul) | Méthodes :<br>- `ReservationResult reserveCopy(UUID bookId)` ;<br>- `ReleaseResult releaseCopy(UUID bookId)` ;<br>- `List<BookSummary> findBooksByIds(Collection<UUID> bookIds)`.<br>Constantes de raison : `String BOOK_NOT_FOUND = "BOOK_NOT_FOUND"`, `String NO_COPY_AVAILABLE = "NO_COPY_AVAILABLE"`. | RM-LOAN-17 |
| `api/ReservationResult.java`, `api/ReleaseResult.java`, `api/BookSummary.java` | nouvelles | `record ReservationResult(boolean reserved, String reason)`, `record ReleaseResult(boolean released, String reason)`, `record BookSummary(UUID id, String isbn, String title, String author)` | RM-LOAN-17 |
| `adapter/in/module/CatalogApiFacade.java` | nouvelle (package-private, `@Component`) | Implémente `CatalogApi` en appelant **uniquement** les trois ports d'entrée ; traduit UUID ↔ `BookId` et issue ↔ résultat. | RM-LOAN-17 |

> **Piège transactionnel, et raison de la décision.** Les use cases de catalog s'exécutent dans la transaction de loan (`@Transactional` REQUIRED, jointure). Une exception qui traverse le proxy d'un use case de catalog marque cette transaction **rollback-only**, même si la façade l'intercepte. Une issue attendue (« introuvable » au retour, par exemple) ferait alors échouer le commit du retour avec `UnexpectedRollbackException`. Les cas attendus remontent donc sous forme d'**issues**, jamais d'exceptions. Seuls les cas techniques (dépassement de stock, erreur SQL) lèvent une exception et annulent tout, ce qui est voulu.

### 5.7 Module `identity` — membre de test, profil `local` (RM-LOAN-30)
| Classe | Responsabilité |
|---|---|
| `domain/model/DevMemberBootstrapOutcome.java` | `CREATED`, `MISSING_CONFIGURATION`, `INVALID_EMAIL`, `WEAK_PASSWORD`, `ALREADY_PRESENT`, `EMAIL_TAKEN_WITHOUT_MEMBER_ROLE` |
| `domain/service/DevMemberBootstrapPolicy.java` | `decide(rawEmail, rawPassword, findByEmail)` : vide → `MISSING_CONFIGURATION`, puis `Email.of`, puis `PasswordPolicy` (réutilisé : 12 caractères minimum, 72 octets maximum), puis existant avec ou sans `MEMBER`. `createMember(...)` : rôle `MEMBER` seul, actif. |
| `application/port/in/BootstrapDevMemberUseCase.java` + `BootstrapDevMemberCommand` + `DevMemberBootstrapResult` | port d'entrée |
| `application/usecase/BootstrapDevMemberService.java` | `@UseCase`. Réutilise `LoadUserByEmailPort`, `SaveUserPort`, `PasswordHasher`, `Clock`. Le hachage n'a lieu que si la création est décidée. |
| `adapter/in/startup/DevMemberBootstrapRunner.java` | `@Component @Profile("local") ApplicationRunner`, sur le modèle de `LibrarianBootstrapRunner`. Journalise : WARN figés (`DEV_MEMBER_EMAIL ou DEV_MEMBER_PASSWORD absent : aucun membre de test créé`, etc.), INFO `Membre de test créé : userId=…`, e-mail masqué seulement pour « pris sans rôle MEMBER ». Ne fait jamais échouer le démarrage. |
| `config/DevMemberProperties.java` | `@ConfigurationProperties("app.dev.member") record(String email, String password)`, `toString` masqué ; enregistré dans `IdentityConfig` |
| `application-local.yml` | `app.dev.member.email: ${DEV_MEMBER_EMAIL:}` et `password: ${DEV_MEMBER_PASSWORD:}`. Un défaut **vide** vaut absence (point C.7). |

### 5.8 Configuration globale (`backend/src/main/java/com/example/library/config/`)
| Classe / fichier | État | Responsabilité |
|---|---|---|
| `LibraryTimeZone.java` | nouvelle | `static ZoneId resolve(String raw)` :<br>- `null` ou blanc → `ZoneId.of("Africa/Dakar")` (constante `DEFAULT`) ;<br>- sinon `ZoneId.of(raw.strip())` ;<br>- `DateTimeException` → `InvalidTimeZoneException("app.timezone", raw)`. |
| `InvalidTimeZoneException.java` + `InvalidTimeZoneFailureAnalyzer.java` | nouvelles | Démarrage refusé, avec ce message : `app.timezone (APP_TIMEZONE) invalide : "Mars/Olympus"`. La valeur n'est pas secrète. Analyseur enregistré dans `META-INF/spring.factories`. |
| `ClockConfig.java` | modifiée | `@Bean Clock clock(@Value("${app.timezone:}") String raw)` → `Clock.system(LibraryTimeZone.resolve(raw))` ; INFO `Fuseau de la bibliothèque : {}`. |
| `application.yml` | modifié | `app.timezone: ${APP_TIMEZONE:Africa/Dakar}` (seule valeur par défaut ajoutée) |
| `security/SecurityConfig.java` | modifiée | `.requestMatchers("/api/v1/loans", "/api/v1/loans/**").hasRole("MEMBER")` avant `anyRequest()`. Elle garantit qu'un `LIBRARIAN` seul reçoit **403 avant** la validation du corps et la résolution du `sub`, car `@PreAuthorize` n'est évalué qu'après la résolution des arguments. CSRF inchangé (`CookieAuthenticatedRequestMatcher`). |
| `security/DevTokenController.java` | inchangé | — |

### 5.9 Règle de dépendance (vérifiée)
- `loan.domain` : JDK seul. `loan.application` : `loan.domain` + `shared.UseCase`.
- `loan.adapter.in.web` : `loan.application.port.in` + `loan.domain` (types), Spring Web et Security. Jamais `port.out` : c'est pourquoi `CurrentLoanView` est aplati.
- `loan.adapter.out.persistence` et `loan.adapter.out.catalog` : `loan.application.port.out` + `loan.domain` ; `catalog.api` pour le second. Jamais `port.in`.
- `catalog.adapter.in.module` : `catalog.api` + `catalog.application.port.in` + `catalog.domain`.
- `catalog.api` : JDK seul (`UUID`, `String`, `Collection`, `List`, records et constantes du package).
- Aucun accès de `loan` à `catalog.domain`, `catalog.application` ou au cache. Redis reste dans `catalog.adapter.out.cache` et dans `config`.
- `ChangeAvailableCopiesPort` est annoté `@AffectsAvailableBooks`, se trouve dans `application.port.out` et est implémenté par `AvailableBooksCacheAdapter` (`CacheArchitectureTest`).

## 6. Frontend (`library-web/src/app/`)
Écrans : `catalogue-list` (existant, intégré, **à mettre à jour** : colonne d'action) ; **`loans-list`** (nouveau, `/emprunts`, **sans design** : à concevoir ou à valider sur la description ci-dessous, puis à inscrire dans `docs/design/design.json`).

### 6.1 `core/` et `shared/`
| Fichier | État | Contenu |
|---|---|---|
| `core/auth/role.guard.ts` | nouveau | `roleGuard(role: Role, fallback = '/catalogue'): CanActivateFn` → `auth.hasRole(role) ? true : router.createUrlTree([fallback])` (RM-LOAN-23) |
| `app.routes.ts` | modifié | Enfant de `''` (déjà sous `authGuard`) : `{ path: 'emprunts', title: 'Mes emprunts', canActivate: [roleGuard('MEMBER')], loadChildren: () => import('./features/loans/loans.routes').then(m => m.LOANS_ROUTES) }` |
| `core/layout/header/header.html` / `.ts` / `.scss` | modifiés | Lien `<a routerLink="/emprunts" routerLinkActive="active" ariaCurrentWhenActive="page" class="app-nav-link">Mes emprunts</a>` dans le `<nav>`, si `isMember = computed(() => user()?.roles.includes('MEMBER') ?? false)`. Mobile : voir C.3. |
| `shared/format/iso-date.ts` | nouveau | `formatIsoDate('2026-12-08') → '08/12/2026'` : découpage de la chaîne, **sans `new Date()`**, qui décalerait d'un jour selon le fuseau du navigateur. Utilisé par les deux features, donc placé dans `shared/`. |

### 6.2 Feature `features/loans/` (nouvelle)
| Fichier | Rôle |
|---|---|
| `loans.routes.ts` | `LOANS_ROUTES = [{ path: '', loadComponent: () => import('./pages/my-loans/my-loans').then(m => m.MyLoans) }]` |
| `data/loans.model.ts` | `CurrentLoan { id: string; bookId: string; isbn: string \| null; title: string \| null; author: string \| null; borrowedOn: string; dueOn: string; overdue: boolean }` ; `MAX_ACTIVE_LOANS = 3` (affichage du compteur seulement, point C.9) |
| `data/loans.service.ts` | Seul accès HTTP de la feature : `listMine()` → `GET /api/v1/loans` ; `giveBack(id)` → `POST /api/v1/loans/{id}/return` avec `{ context: skipErrorToast(404, 409, 503) }` |
| `data/return-errors.ts` | `LOAN_ALREADY_RETURNED` → « Cet emprunt est déjà rendu » ; `LOAN_NOT_FOUND` → « Emprunt introuvable » |
| `pages/my-loans/my-loans.ts` / `.html` / `.scss` | Page routée, `providers: [ConfirmationService]`, `<p-confirmdialog />` dans le gabarit. Détail ci-dessous. (RM-LOAN-24 à 26) |
| `ui/loan-table/loan-table.ts` / `.html` / `.scss` | Entrées `loans`, `status`, `returningId`, `retrying` ; sorties `giveBack: CurrentLoan` et `retry`. Détail ci-dessous. |

Comportement de la page `my-loans` :
- État `loading | loaded | error` et `reload$`, sur le modèle de `Catalogue` ; `h1` « Mes emprunts ».
- Compteur « {n} / 3 emprunts en cours » (`aria-live="polite"`).
- Bandeau `p-message severity="warn"` si `loans().some(l => l.overdue)` : « Vous ne pouvez plus emprunter tant que vous avez un livre en retard ».
- « Rendre » : `confirmationService.confirm({ header: 'Rendre ce livre ?', message: 'Confirmez-vous le retour de « {titre} » ?', acceptLabel: 'Rendre', rejectLabel: 'Annuler' })`.
- Signal `returningId` : un seul envoi à la fois.
- Succès : toast `success` « Livre rendu », puis `reload$` ; focus sur le `h1`, car la ligne a disparu.
- Rejet : focus rendu au bouton « Rendre » de la ligne.
- 409 et 404 : toast `warn` (message de `return-errors.ts`), puis rechargement.
- 403, 5xx, réseau : toast de l'`errorInterceptor`.

Composant `loan-table` :
- `p-table` (caption masquée « Mes emprunts en cours »). Colonnes : Titre, Auteur, ISBN, Emprunté le, À rendre au plus tard le, Statut, action. Sous `md` : auteur, ISBN et dates passent en sous-lignes, comme dans le catalogue.
- Statut : `p-tag` « En cours » (`info`) ou « En retard » (`danger`, icône `pi pi-exclamation-circle`). Le texte porte l'information, pas seulement la couleur.
- Titre nul : « Livre indisponible ».
- Bouton `p-button` secondaire contour « Rendre » (`ariaLabel` « Rendre {titre} »), `[loading]` sur la ligne en cours, désactivé si un retour est en cours.
- État vide (icône `pi pi-book`) : « Vous n'avez aucun emprunt en cours », lien-bouton « Parcourir le catalogue » vers `/catalogue`.
- État d'erreur : comme le catalogue (`pi pi-exclamation-triangle`, « Vos emprunts n'ont pas pu être chargés », « Réessayer »).

### 6.3 Feature `features/catalogue/` (modifiée)
Une feature n'importe pas une autre. L'appel d'emprunt passe donc par le service de la feature catalogue, qui appelle `/api/v1/loans` sans importer `features/loans`.

| Fichier | Modification |
|---|---|
| `data/catalogue.model.ts` | `BorrowBookRequest { bookId: string }`, `LoanCreated { id: string; bookId: string; borrowedOn: string; dueOn: string }` |
| `data/catalogue.service.ts` | `borrow(bookId)` → `POST /api/v1/loans` avec `{ context: skipErrorToast(404, 409, 503) }` |
| `data/borrow-errors.ts` | Codes → messages de RM-LOAN-22 |
| `pages/catalogue/catalogue.ts` / `.html` | `canBorrow = computed(() => auth.hasRole('MEMBER'))`, `borrowingId = signal<string \| null>(null)`. `borrow(book)` : rien si un emprunt est en cours (double clic = une requête). Succès : toast `success` « Livre emprunté », détail « À rendre au plus tard le {formatIsoDate(dueOn)}. », puis `reload$.next()` (page et `q` conservés ; la redirection existante vers la dernière page s'applique si la page se vide). 409 : toast `warn` avec le message du code ; `BOOK_NOT_AVAILABLE` recharge aussi la liste (point C.8). 404 `BOOK_NOT_FOUND` : toast « Ce livre n'existe plus », puis rechargement. |
| `ui/book-table/book-table.ts` / `.html` / `.scss` | Entrées `canBorrow` et `borrowingId` ; sortie `borrow: Book`. Si `canBorrow` : colonne d'action (en-tête masqué « Action »), `p-button` « Emprunter » secondaire contour, petit, `ariaLabel` « Emprunter {titre} », `[loading]` sur la ligne, `[disabled]` pendant tout emprunt. Le `colspan` des états vides devient `4 + (canBorrow ? 1 : 0)`. |

### 6.4 Mode `mock` (RM-LOAN-27)
| Fichier | État | Contenu |
|---|---|---|
| `core/mock/mock-catalogue.ts` | modifié | `findById(id)`, `takeCopy(id): boolean`, `returnCopy(id)`, `summaries(ids)` : état partagé, la décrémentation se voit dans le catalogue simulé |
| `core/mock/mock-loans.ts` | nouveau | Classe `MockLoans(catalogue)`. Types locaux (`core` n'importe pas `features`). Graine `loans.json` : échéances **relatives** (`dueInDays: -2`) calculées au chargement. Détail ci-dessous. |
| `core/mock/mock-api.interceptor.ts` | modifié | Correspondance par motif (`RegExp`) en plus du chemin exact. `POST /api/v1/loans`, `GET /api/v1/loans`, `POST /api/v1/loans/{id}/return` passent par `withSession`. Délais de 200 à 400 ms. |
| `src/mocks/loans/` | nouveau | `loans.json`, `validation-failed.json`, `book-not-found.json`, `member-has-overdue-loan.json`, `loan-limit-reached.json`, `book-already-borrowed.json`, `book-not-available.json`, `loan-not-found.json`, `loan-already-returned.json`, `access-denied.json` |
| `src/mocks/auth/users.json` | modifié | + `membre-retard@exemple.test` (MEMBER, un emprunt en retard dans la graine) ; + `membre-bibliothecaire@exemple.test` (LIBRARIAN + MEMBER) ; mot de passe fictif |

Règles appliquées par `MockLoans`, dans l'ordre du contrat :
- `borrow` : 403 sans `MEMBER` ; 400 par champ ; puis 404, retard, limite, déjà emprunté, indisponible ; 201 + `Location`.
- `list` : emprunts actifs de l'utilisateur, triés, `overdue` à la date locale.
- `giveBack` : 404 si inconnu ou si l'emprunt appartient à un autre membre ; 409 si déjà rendu ; sinon 204 et `returnCopy`.

## 7. Tests
### 7.1 Backend
| Couche | Classe de test | Cas (règles) |
|---|---|---|
| domain | `loan/domain/model/LoanTest` | `open` le 2026-11-24 → échéance 2026-12-08 (RM-LOAN-04) ; `isOverdue` faux le jour de l'échéance, vrai le lendemain, faux si rendu (RM-LOAN-05) ; `giveBack` renseigne `returnedOn`, deuxième appel → `LoanAlreadyReturnedException` (RM-LOAN-10, 11) ; `DISPLAY_ORDER` (échéance puis id, ordre `uuid` de PostgreSQL) |
| domain | `loan/domain/service/LoanPolicyTest` | 3 actifs → limite ; 2 actifs dont un rendu le jour même → OK (RM-LOAN-02) ; retard → refus (RM-LOAN-03) ; même livre → refus, livre rendu → OK (RM-LOAN-28) ; **plan de l'ordre d'évaluation** (lignes 2 à 4 du tableau RM-LOAN-06) |
| domain | `catalog/domain/model/BookTest` (complété) | `takeCopy` 2 → 1, 0 → `NoCopyAvailableException`, `totalCopies` inchangé (RM-LOAN-09) ; `returnCopy` 0 → 1, `total` → exception |
| application | `loan/application/usecase/BorrowBookServiceTest` (fakes `InMemoryLoanRepository`, `FakeCatalog` dans `loan/application/fake/`, `Clock.fixed(2026-11-24, Africa/Dakar)`) | Nominal ; ordre complet RM-LOAN-06 (5 lignes : réservation **non appelée** pour les lignes 1 à 4) ; `NO_COPY_AVAILABLE` → 409 et aucun emprunt ; verrou pris **avant** la lecture des emprunts actifs (journal d'appels du fake) ; fuseau `Asia/Tokyo` + `2026-11-24T20:00Z` → `borrowedOn` 2026-11-25 |
| application | `ListCurrentLoansServiceTest` | 0 emprunt → `[]` et **aucun** appel catalogue ; 3 emprunts → **un** appel avec 3 ids (RM-LOAN-32) ; livre absent → champs nuls ; tri ; `overdue` |
| application | `ReturnLoanServiceTest` | Nominal (date du jour, `release` appelé) ; inconnu ou autre membre → `LoanNotFoundException` ; déjà rendu → 409, `release` non appelé ; en retard → OK (RM-LOAN-12) ; `BOOK_NOT_FOUND` à la libération → retour conservé + WARN |
| application | `catalog/.../ReserveCopyServiceTest`, `ReleaseCopyServiceTest`, `FindBooksByIdsServiceTest` | Issues `RESERVED`, `BOOK_NOT_FOUND`, `NO_COPY_AVAILABLE` (domaine, puis course perdue : décrément `false`) ; aucune exception pour les cas attendus ; lot vide → aucune lecture ; plus de 50 ids refusés |
| application | `identity/.../BootstrapDevMemberServiceTest` + `DevMemberBootstrapPolicyTest` | Création `MEMBER` seul ; absent ou vide (4 cas) ; e-mail invalide ; mot de passe faible ; existant (idempotent), avec ou sans `MEMBER` ; pas de hachage si refus (RM-LOAN-30) |
| adapter.out.persistence | `loan/adapter/out/persistence/LoanPersistenceAdapterTest` (`@DataJpaTest` + Testcontainers PG) | Relit ce qu'il sauve, dates exactes **avec un fuseau JVM `Asia/Tokyo`** (`hibernate.jdbc.time_zone=UTC`, risque R5) ; seuls les actifs du membre ; index unique → `BookAlreadyBorrowedException`, puis réemprunt après retour OK ; `saveReturned` sur un rendu → 0 ligne → exception ; CHECK `due_on >= borrowed_on` ; `findByIdAndMemberId` d'un autre membre → vide |
| adapter.out.persistence | `catalog/.../BookPersistenceAdapterTest` (complété) | Décrément 1 → 0 puis `false` ; incrément borné par `total` ; **écriture directe `available_copies = -1` et `= total + 1` rejetées par `ck_book_available_copies_range`** (RM-LOAN-31) ; `loadByIds` de 3 ids = **1 requête** (statistiques Hibernate, `generate_statistics` dans le test) (RM-LOAN-32) |
| adapter.out.cache | `AvailableBooksCacheAdapterTest` (complété) | `decrement` et `increment` délèguent, puis éviction **après commit** si `true` ; rien si `false` ; rien en cas de rollback |
| adapter.out.catalog | `loan/adapter/out/catalog/CatalogBooksAdapterTest` (`CatalogApi` mocké) | Traduction des raisons ; `loadSummaries` = un appel |
| adapter.in.module | `catalog/adapter/in/module/CatalogApiFacadeTest` (use cases mockés) | `reserveCopy` → `reserved=false, reason=NO_COPY_AVAILABLE` ; `BookSummary` |
| adapter.in.web | `loan/adapter/in/web/LoanControllerTest` (`@WebMvcTest` + `@Import(SecurityConfig)`, use cases en `@MockitoBean`) | 201 + `Location` + JSON exact (`"2026-11-24"`) ; 400 `{}`, `null`, `"abc"`, JSON illisible ; `memberId` du corps ignoré (commande capturée = `sub`) ; 401 sans jeton ; **`sub` non UUID → 401 `UNAUTHENTICATED` pour les 3 endpoints, use case non appelé** ; `LIBRARIAN` seul → 403 (3 endpoints) ; `LIBRARIAN` + `MEMBER` → 201 ; cookie sans `X-XSRF-TOKEN` → 403 ; Bearer sans CSRF → 201 ; `{id}` = `abc` → 404 ; chaque exception → statut et code |
| config | `config/LibraryTimeZoneTest` + `ClockConfigTest` (`ApplicationContextRunner`) | Absent, vide, blanc → Dakar ; `" Europe/Paris "` → Paris ; `Mars/Olympus` et `+25:00` → échec qui nomme `app.timezone` et la valeur (RM-LOAN-29) |
| identity | `DevMemberBootstrapRunnerTest` (`OutputCaptureExtension`) + contexte hors `local` | WARN sans valeur, mot de passe jamais journalisé ; runner absent hors profil `local` |
| bout en bout | `loan/LoanApiIntegrationTest` (`@SpringBootTest` + PG + Redis, `@TestConfiguration` avec `@Primary Clock.fixed`) | Scénarios Gherkin API : nominal, dernier exemplaire **absent de `GET /books` après emprunt (cache invalidé)**, retour → réapparaît, limite libérée par un retour, retard rendu, livre introuvable dans le lot |
| bout en bout | `loan/LoanConcurrencyIntegrationTest` (deux threads, `CyclicBarrier`, appels du **port d'entrée**) | Dernier exemplaire × 2 membres → un succès, un `BOOK_NOT_AVAILABLE`, stock 0 ; même membre, même livre → un succès, un `BOOK_ALREADY_BORROWED`, stock -1 seulement ; 2 actifs + 2 livres → 3 actifs exactement ; double retour → un succès, stock +1 ; **atomicité** (`@MockitoSpyBean` sur l'adapter de persistance de loan qui lève une exception à `saveNew`) → stock inchangé |
| architecture | `ArchitectureTest`, `CacheArchitectureTest` (non modifiés) | Verts (`ChangeAvailableCopiesPort` décoré) |

### 7.2 Frontend (`npx ng test --watch=false`)
| Fichier | Cas |
|---|---|
| `core/auth/role.guard.spec.ts` | MEMBER → `true` ; LIBRARIAN seul → `/catalogue` |
| `core/layout/header/header.spec.ts` | « Mes emprunts » visible pour MEMBER et pour LIBRARIAN + MEMBER, absent pour LIBRARIAN seul |
| `shared/format/iso-date.spec.ts` | `2026-12-08` → `08/12/2026`, indépendant du fuseau |
| `features/catalogue/data/catalogue.service.spec.ts` | `borrow` : URL, corps, contexte 404 et 409 |
| `features/catalogue/ui/book-table/book-table.spec.ts` | Bouton seulement si `canBorrow`, libellé accessible, `[loading]` sur la ligne, `colspan` |
| `features/catalogue/pages/catalogue/catalogue.spec.ts` | Double clic → une requête ; 201 → toast avec date + rechargement à la même page et au même `q` ; chaque 409 → **un seul** toast avec le bon texte ; 404 → toast + rechargement |
| `features/loans/data/loans.service.spec.ts` | `GET`, `POST …/return`, contexte |
| `features/loans/ui/loan-table/loan-table.spec.ts` | Colonnes, tag « En retard », « Livre indisponible », libellés « Rendre {titre} » |
| `features/loans/pages/my-loans/my-loans.spec.ts` | 2 lignes dont 1 en retard → bandeau + « 2 / 3 » ; vide → message + lien ; 500 → « Réessayer » ; confirmer → `POST` + toast + rechargement ; annuler → aucune requête ; 409 et 404 → toast + rechargement |
| `core/mock/mock-loans.spec.ts`, `mock-api.interceptor.spec.ts` | Limite, retard, déjà emprunté (scénario « Mode mock »), décrément du catalogue simulé, retour, 403 LIBRARIAN |

## 8. Intégrations
| Techno | Classes et rôle (`roles.conf`) | Résilience |
|---|---|---|
| PostgreSQL | `LoanPersistenceAdapter`, `LoanJpaRepository` : `sortant-persistance` (loan) ; `BookPersistenceAdapter`, `BookJpaRepository` : `sortant-persistance` (catalog) | - Une seule transaction par emprunt ou retour (`@UseCase`), qui englobe la réservation catalog : rien n'est partiel (RM-LOAN-07) ;<br>- verrou consultatif `lock_timeout` 5 s, verrou de ligne 5 s ;<br>- mises à jour conditionnelles + CHECK en base ;<br>- l'index unique garantit l'idempotence de « même livre » ;<br>- aucun appel réseau lent dans la transaction. |
| Redis (catalog seulement) | `AvailableBooksCacheAdapter` : `sortant-cache` (décore `ChangeAvailableCopiesPort`) | Éviction **après commit**, rien en cas de rollback ; Redis indisponible → WARN, jamais propagé (RC-15) ; retard d'au plus un TTL accepté (RC-16). `loan` ignore Redis. |
| MCP | — | Non utilisé (spec) |

## 9. Risques
- **R1 Performance.**
  - `GET /loans` : une requête sur les emprunts actifs (index partiel), puis **une** requête `book WHERE id IN (≤3)` ; aucun appel si la liste est vide. Pas de pagination (au plus 3).
  - L'emprunt fait environ 6 instructions SQL, verrou compris : acceptable.
- **R2 Sérialisation.** Le verrou consultatif ne sérialise que les requêtes d'**un même** membre ; la contention reste négligeable. Il doit être pris dans la transaction du use case : les tests vérifient qu'il échoue hors transaction.
- **R3 Sécurité.**
  - Le membre vient du `sub`, jamais du corps (pas d'IDOR) ; l'emprunt d'autrui donne 404.
  - Ordre garanti : 401, puis 403 (règle d'URL), puis 400, puis 404/409.
  - Le CSRF s'applique aux deux `POST` par cookie.
  - Les logs contiennent les identifiants, jamais d'e-mail.
- **R4 Impacts sur catalog.**
  - `AvailableBooksCacheAdapter` gagne un port et une dépendance (6, point C.11) ;
  - `BookJpaRepository` gagne deux `UPDATE` ;
  - `Book` gagne deux méthodes ;
  - le contrat REST et MCP du catalogue est inchangé ;
  - le cache est invalidé à chaque emprunt et retour, ce qui fait baisser le taux de succès du cache (accepté par la spec).
- **R5 Dates.** `hibernate.jdbc.time_zone: UTC` est configuré : un test vérifie qu'une `LocalDate` relue est identique quel que soit le fuseau de la JVM. Côté frontend, `formatIsoDate` évite le décalage de `new Date('yyyy-MM-dd')`.
- **R6 Données incohérentes.**
  - Livre supprimé : liste avec champs nuls ; au retour, voir C.6.
  - Restitution au-delà de `total` : erreur technique, transaction annulée (500), log ERROR.
- **R7 Hors périmètre :** secrets par défaut dans `application.yml` (réintroduits par #22), corrigés par une branche `fix/…` séparée depuis `develop`, fusionnée avant la PR loan. Le code et les tests de loan ne s'appuient sur aucune de ces valeurs par défaut.

## 10. Ordre d'implémentation
Il suit l'ordre de la référence. Dans chaque couche, catalog passe avant loan.

0. Contrat `docs/api/loan.md`.
1. **Domaine** : `Book.takeCopy` / `returnCopy` et leurs exceptions ; modèle, politique et exceptions de loan ; politique et issue du membre de test (identity). `./mvnw -q compile`.
2. **Application** : ports et use cases de catalog (réserver, libérer, lot) et `catalog.api` (records + interface, JDK seul) ; ports et use cases de loan ; use case du membre de test. Compiler.
3. **Adapters sortants** : `V3__create_table_loan.sql` ; persistance de loan ; `BookJpaRepository` et `BookPersistenceAdapter` ; `AvailableBooksCacheAdapter` (`ChangeAvailableCopiesPort`) ; `loan.adapter.out.catalog.CatalogBooksAdapter`. Compiler.
4. **Adapters entrants** : `catalog.adapter.in.module.CatalogApiFacade` ; web de loan (contrôleur, DTO, résolveur `@CurrentMember`, gestionnaire d'erreurs) ; `DevMemberBootstrapRunner`. Compiler.
5. **Config** : `LibraryTimeZone`, `ClockConfig`, analyseur de démarrage, `spring.factories`, `application.yml`, `application-local.yml`, `SecurityConfig`, `LoanWebConfig`, `IdentityConfig` (`DevMemberProperties`).
6. **Fin backend** : `./mvnw test`, `ArchitectureTest` et `CacheArchitectureTest` compris (tests écrits par test-writer).
7. **Frontend** : `shared/format`, `core/auth/role.guard`, routes et en-tête, `features/loans` (data, ui, page), modifications de `features/catalogue`, mock et jeux de données ; `npx ng build`, `npm run lint`, `npx ng test --watch=false`.
8. **Documentation** : `CLAUDE.md` (`APP_TIMEZONE`, `DEV_MEMBER_*`, RM-LOAN essentielles) et `.env.example`, **à créer** (absent du dépôt), sans valeur secrète.

**Travail séparé possible.**
- Le backend (étapes 1 à 6) est testable sans interface : MockMvc, et `/dev/token` `{"roles":["MEMBER"]}` ou le membre de test.
- Le frontend (étape 7) peut être développé **avant** le backend sur `ng serve --configuration mock`, à partir du seul contrat. `/integrate loan` retire ensuite les endpoints simulés.

## 11. Décisions (validées le 2026-11-24)
| # | Point | Décision |
|---|---|---|
| D1 | Refus métier : 409 ou 422 | **409** pour les cinq refus métier et `LOAN_ALREADY_RETURNED` (spec prioritaire sur l'option `errors`). |
| D2 | JSON illisible | 400 `VALIDATION_FAILED` avec `errors: []`, comme le catalogue. |
| D3 | Retour d'un emprunt dont le livre n'existe plus | 204, stock non modifié, WARN. Restitution au-delà de `totalCopies` : erreur technique (500, rollback). |
| D4 | Membre de test | `${DEV_MEMBER_EMAIL:}` / `${DEV_MEMBER_PASSWORD:}` (défaut vide = absent) dans `application-local.yml` **seulement**. |
| D5 | `BOOK_NOT_AVAILABLE` dans le catalogue | Toast `warn` **et** rechargement de la liste. |
| D6 | `AvailableBooksCacheAdapter` à 6 dépendances | Accepté. **Dette technique** : issue à créer pour refondre le décorateur (revenir à 5 dépendances au plus). |
| D7 | Index `ix_loan_member_active` | Supprimé (redondant avec `uk_loan_member_book_active`). |
| D8 | Design (catalogue, en-tête mobile, écran « Mes emprunts », éléments du design system) | Traité à l'étape 4, avant la partie frontend. |
| D9 | Secrets par défaut dans `application.yml` | Hors périmètre : correctif séparé depuis `develop`, fusionné avant la PR loan. Aucun code ni test de loan n'en dépend. |
| D10 | Ordre du code | **Backend d'abord**, puis frontend directement sur la vraie API. |
| D11 | Revue backend (2026-11-25) : dépendances de `BorrowBookService` | Fusion de `CheckBookExistsPort` dans `LoadBookSummariesPort` : un port de moins, existence vérifiée par la lecture groupée. `BorrowBookService` a **5 ports + `Clock`** ; `Clock`, dépendance d'infrastructure, n'est pas compté dans le seuil de 5 (corrigé le 2026-11-25 : la première rédaction annonçait à tort un « retour à 5 dépendances »). |
| D12 | Revue backend : délai de verrou dépassé (55P03) | **503 `LOAN_BUSY`** (ProblemDetail), ajouté au contrat. `lockLoansOf` exige une transaction (`MANDATORY`). `sub` accepté seulement en forme canonique d'UUID. |
| D13 | Design de l'étape 4 (2026-11-26) | Écran `loan-my-loans` (designer de la factory) validé ; décisions A–K reportées dans la spec (décisions 13 à 17) et dans `docs/design/`. Sous `md`, rupture intermédiaire 768–991 px ajoutée. 503 `LOAN_BUSY` : toast `warn` géré par l'écran (emprunt et retour). |

## Dette technique
- `AvailableBooksCacheAdapter` : 6 dépendances (seuil du socle : 5) après l'ajout de `ChangeAvailableCopiesPort`. Issue à créer : « Refondre le décorateur de cache du catalogue (dépendances > 5) ».

## Avancement
Ordre : backend d'abord
| Partie | État | Branche |
|---|---|---|
| Backend | terminé — 2026-11-25 | feature/loan |
| Frontend | terminé — 2026-11-27 | feature/loan |
Design : « Mes emprunts » (`loan-my-loans`) par le designer de la factory, validé le 2026-11-26 ; bouton « Emprunter » du catalogue, confirmation du retour et lien d'en-tête sans design dédié (design system + fiche `catalogue-list`, section « Évolution loan »).
