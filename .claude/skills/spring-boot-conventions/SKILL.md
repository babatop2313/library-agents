---
name: spring-boot-conventions
description: Conventions techniques Spring Boot communes à toutes les architectures (injection, transactions, mapping, gestion d'erreurs, logs, Flyway, sécurité JWT, configuration). Complète le skill architecture ; à charger avec lui pour toute tâche backend.
---

# Conventions techniques Spring Boot

> **L'architecture (packages, couches, règle de dépendance) n'est pas ici** : elle est définie par le skill `architecture`
> et la référence active (`.claude/factory.json`). En cas de conflit, **la référence d'architecture l'emporte**.
> Ici : les conventions techniques valables quelle que soit l'architecture.

## Injection
Par constructeur uniquement. Pas de `@Autowired` sur un champ.
Lombok est autorisé sauf dans les couches où la référence d'architecture l'interdit.

## Transactions
- Portées par la couche qui exécute les cas d'utilisation (services ou use cases, selon la référence).
- Jamais de `@Transactional` sur un contrôleur.
- Lectures : `@Transactional(readOnly = true)`.

## Mapping
- Les conversions (DTO ↔ objets internes, entités JPA ↔ objets internes) se font dans des mappers,
  placés là où la référence d'architecture l'indique.
- MapStruct : `@Mapper(componentModel = "spring")`, ou mapper manuel si la conversion est simple.

## Gestion d'erreurs
- Les exceptions métier portent un `code` métier et aucune notion de statut HTTP.
- Un `@RestControllerAdvice` par module, dans la couche web, renvoie un `ProblemDetail` (RFC 9457) avec la propriété `code` :
  - ressource introuvable → 404 ;
  - conflit (doublon) → 409 ;
  - règle métier violée → 422 ;
  - `MethodArgumentNotValidException` → 400 avec la liste des champs en erreur.

## Logs
SLF4J (`@Slf4j` ou `LoggerFactory`). `info` pour les événements métier, `debug` pour le détail.
Jamais de données personnelles, de token ou de mot de passe dans un log.

## Flyway
- Fichier : `V<n>__<verbe>_<objet>.sql` (ex. `V3__create_table_loan.sql`). Ne jamais modifier une migration existante.
- snake_case, contraintes nommées (`fk_loan_member`, `uk_book_isbn`), index sur chaque clé étrangère et colonne filtrée.
- `spring.jpa.hibernate.ddl-auto=validate` : le schéma n'est géré que par Flyway.

## Sécurité
- `@PreAuthorize` sur chaque méthode de contrôleur qui modifie des données.
- L'identité de l'utilisateur vient du JWT (`@AuthenticationPrincipal`), jamais d'un champ de la requête (pas d'IDOR).
- Configuration de sécurité dans `config/`.

## Configuration
- `application.yml` sans secret : `${DB_PASSWORD}`.
- Un `@Bean Clock` (`Clock.systemDefaultZone()`) dans la config globale, remplacé par `Clock.fixed(...)` dans les tests.
