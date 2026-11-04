---
name: rest-api-design
description: Règles de conception des API REST (nommage des ressources, verbes, codes HTTP, pagination, erreurs, versionnement). À charger pour concevoir ou relire un contrat d'API.
---

# Conception d'API REST

## Ressources
- Noms au pluriel, kebab-case : `/api/v1/scholarship-categories`.
- Sous-ressources pour la composition : `/api/v1/programs/{programId}/categories`.
- Actions métier hors CRUD : `POST /api/v1/applications/{id}/submit`.

## Verbes et codes
| Opération | Verbe | Succès | Erreurs courantes |
|---|---|---|---|
| Lister | GET | 200 | 400 (filtre invalide) |
| Lire | GET | 200 | 404 |
| Créer | POST | 201 + `Location` | 400, 409 |
| Remplacer | PUT | 200 | 400, 404, 409 |
| Modifier partiellement | PATCH | 200 | 400, 404 |
| Supprimer | DELETE | 204 | 404, 409 (référencé) |
| Non authentifié / non autorisé | — | — | 401 / 403 |

## Pagination et filtres
- `?page=0&size=20&sort=label,asc` (Spring `Pageable`), `size` plafonné à 100.
- Réponse : `{ content, page, size, totalElements, totalPages }`.

## Erreurs
`ProblemDetail` (RFC 9457) : `type`, `title`, `status`, `detail`, plus `code` métier et `errors[]` pour la validation.

## Versionnement
Préfixe `/api/v1`. Un changement cassant = nouvelle version, l'ancienne reste disponible pendant la transition.

## Documentation
Annotations OpenAPI (`@Operation`, `@ApiResponse`) sur chaque endpoint ; le Swagger est la source de vérité du contrat.
