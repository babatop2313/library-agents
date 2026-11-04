---
name: spring-boot-conventions
description: Conventions techniques Spring Boot appliquées dans les adapters et la configuration (transactions, gestion d'erreurs, logs, Flyway, sécurité JWT, mapping). Complète le skill hexagonal-architecture ; à charger avec lui pour toute tâche backend.
---

# Conventions techniques Spring Boot

> L'architecture (packages, règle de dépendance) est dans le skill `hexagonal-architecture`.
> Ici : les conventions techniques, qui ne s'appliquent **que** dans `adapter/`, `config/` et `shared/`.

## Injection
Par constructeur uniquement. Pas de `@Autowired` sur un champ. Lombok autorisé dans `adapter` et `config`, interdit dans `domain`.

## Transactions
- Portées par les use cases via `@UseCase` (méta-annoté `@Transactional`).
- Jamais de `@Transactional` sur un contrôleur ni dans le domaine.
- Une lecture seule : use case séparé (`GetLoanQuery`), annoté `@Transactional(readOnly = true)` si besoin de performance.

## Mapping
- Web ↔ application : mapper manuel ou MapStruct dans `adapter.in.web` (DTO ↔ Command / résultat).
- Persistance ↔ domaine : `XxxPersistenceMapper` dans `adapter.out.persistence`, qui appelle `Xxx.restore(...)`.
- MapStruct : `@Mapper(componentModel = "spring")`, jamais référencé depuis `domain` ou `application`.

## Gestion d'erreurs
- Le domaine lève des `DomainException(code)` sans notion de statut HTTP.
- Un `@RestControllerAdvice` par bounded context dans `adapter.in.web` :
  - `DomainException` → 409 ou 422 avec `ProblemDetail` (RFC 9457) et propriété `code` ;
  - `XxxNotFoundException` → 404 ;
  - `MethodArgumentNotValidException` → 400 avec la liste des champs.

## Logs
SLF4J dans les adapters et les use cases. `info` pour les événements métier, `debug` pour le détail.
Jamais de données personnelles, de token ou de mot de passe dans un log. Le domaine ne logge pas.

## Flyway
- Fichier : `V<n>__<verbe>_<objet>.sql` (ex. `V3__create_table_loan.sql`).
- snake_case, contraintes nommées (`fk_loan_member`, `uk_book_isbn`), index sur chaque clé étrangère et colonne filtrée.
- Les tables reflètent les `XxxJpaEntity`, pas le modèle du domaine : les deux peuvent diverger.

## Sécurité
- `@PreAuthorize` sur chaque méthode de contrôleur qui modifie des données.
- L'identité de l'utilisateur vient du JWT (`@AuthenticationPrincipal`), jamais d'un champ de la requête (pas d'IDOR).
- Configuration de sécurité dans `config/` ou `shared/security/`.

## Configuration
- `application.yml` sans secret : `${DB_PASSWORD}`.
- Un `@Bean Clock` (`Clock.systemDefaultZone()`) dans la config globale, remplacé par `Clock.fixed(...)` dans les tests.
