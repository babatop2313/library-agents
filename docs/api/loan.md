# Contrat d'API — Emprunts (`/api/v1/loans`)

> Source : `docs/specs/loan.md` (validée le 2026-11-24). Plan : `docs/specs/loan-plan.md`.
> Ce document se suffit à lui-même : le frontend et le mode `mock` s'appuient dessus.

## Vue d'ensemble
| Méthode | URL | Rôle | Request | Response | Codes HTTP |
|---|---|---|---|---|---|
| POST | `/api/v1/loans` | `MEMBER` | `BorrowBookRequest` (JSON) | `LoanCreated` + en-tête `Location` | 201, 400, 401, 403, 404, 409, 503 |
| GET | `/api/v1/loans` | `MEMBER` | — | `CurrentLoan[]` | 200, 401, 403 |
| POST | `/api/v1/loans/{id}/return` | `MEMBER` | — (aucun corps) | — | 204, 401, 403, 404, 409, 503 |

**Membre concerné.** C'est toujours l'utilisateur authentifié (`sub` du JWT, UUID). Aucune requête ne transmet d'identifiant de membre : un champ `memberId` dans le corps est ignoré.
**Authentification.** Cookie `ACCESS_TOKEN` (navigateur, HttpOnly) **ou** `Authorization: Bearer <jwt>`. L'en-tête est prioritaire. Un jeton dont le `sub` n'est pas un UUID est inexploitable : 401.
**CSRF.** Non exigé pour `GET`. Exigé pour les deux `POST` authentifiés par cookie : en-tête `X-XSRF-TOKEN` égal au cookie `XSRF-TOKEN`, géré par `HttpClient`. Les requêtes `Bearer` en sont dispensées.
**Ordre d'évaluation.** Authentification (401), puis rôle ou CSRF (403), puis `sub` non UUID (401), puis validation (400), puis métier (404 puis 409, ordre ci-dessous).
**Dates.** `yyyy-MM-dd`, sans fuseau. Elles sont calculées dans le fuseau de la bibliothèque (`app.timezone`, `Africa/Dakar` par défaut).
**Format des erreurs.** `application/problem+json` (RFC 9457) avec la propriété `code`, plus `errors[]` pour la validation. `title` et `detail` sont indicatifs ; `status` et `code` sont contractuels.

## Types communs
```ts
BorrowBookRequest {
  bookId: string          // UUID d'un livre (champ `id` de GET /api/v1/books). Obligatoire.
}

LoanCreated {
  id: string              // UUID de l'emprunt
  bookId: string          // UUID du livre
  borrowedOn: string      // yyyy-MM-dd, date du jour
  dueOn: string           // yyyy-MM-dd, borrowedOn + 14 jours
}

CurrentLoan {
  id: string              // UUID de l'emprunt
  bookId: string          // UUID du livre
  isbn: string | null     // 13 chiffres ; null si le livre n'existe plus au catalogue
  title: string | null    // null si le livre n'existe plus (afficher « Livre indisponible »)
  author: string | null   // null si le livre n'existe plus
  borrowedOn: string      // yyyy-MM-dd
  dueOn: string           // yyyy-MM-dd
  overdue: boolean        // true si la date du jour est strictement postérieure à dueOn
}

FieldError {
  field: string           // bookId
  code: string            // REQUIRED | INVALID_FORMAT
  message: string         // en français, affichable tel quel
}

ProblemDetail {
  type: string            // "about:blank"
  title: string
  status: number
  detail?: string
  instance?: string       // chemin de la requête
  code: string            // voir chaque endpoint
  errors?: FieldError[]   // seulement pour VALIDATION_FAILED (vide si le JSON est illisible)
}
```
Les champs nuls sont **présents** avec la valeur `null` (jamais omis).

---

## POST `/api/v1/loans` — emprunter un livre

### Requête — `BorrowBookRequest`
| Champ | Type | Contraintes | Codes d'erreur |
|---|---|---|---|
| `bookId` | string (UUID) | Obligatoire, UUID valide | `REQUIRED` (absent ou `null`), `INVALID_FORMAT` (pas un UUID, ou pas une chaîne) |

```json
{ "bookId": "3f6c2a9e-1b7d-4c1e-9a52-0d8f6b2e4a11" }
```

### Règles et ordre d'évaluation (le premier cas rencontré détermine la réponse)
1. Livre inconnu : 404 `BOOK_NOT_FOUND`.
2. Le membre a au moins un emprunt actif en retard : 409 `MEMBER_HAS_OVERDUE_LOAN`.
3. Le membre a déjà 3 emprunts actifs : 409 `LOAN_LIMIT_REACHED` (les emprunts rendus ne comptent pas).
4. Le membre a déjà un emprunt actif de ce livre : 409 `BOOK_ALREADY_BORROWED`.
5. Aucun exemplaire disponible : 409 `BOOK_NOT_AVAILABLE`.

En cas de succès, `availableCopies` du livre baisse de 1 dans la même transaction. Le livre disparaît de `GET /api/v1/books` s'il n'en reste plus (cache invalidé après commit ; retard d'au plus 10 min si l'invalidation échoue). En cas de refus, rien n'est modifié.
Concurrence : deux demandes simultanées pour le dernier exemplaire donnent exactement un 201 et un 409 `BOOK_NOT_AVAILABLE`. Deux demandes simultanées du même membre ne dépassent jamais 3 emprunts actifs ni deux emprunts du même livre.

### Réponse 201 — `LoanCreated`
En-tête `Location: /api/v1/loans/{id}`. Il n'existe pas d'endpoint `GET` correspondant (hors périmètre).
```json
{
  "id": "6d1f0c4b-8a2e-4f7a-b3d9-2c5e7a9b1f30",
  "bookId": "3f6c2a9e-1b7d-4c1e-9a52-0d8f6b2e4a11",
  "borrowedOn": "2026-11-24",
  "dueOn": "2026-12-08"
}
```

### Erreur 400 — `VALIDATION_FAILED`
```json
{
  "type": "about:blank",
  "title": "Validation échouée",
  "status": 400,
  "detail": "La requête est invalide",
  "instance": "/api/v1/loans",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "bookId", "code": "REQUIRED", "message": "Le champ 'bookId' est obligatoire" }
  ]
}
```
`{"bookId": "abc"}` : `{ "field": "bookId", "code": "INVALID_FORMAT", "message": "Le champ 'bookId' a un format invalide" }`.
Corps JSON illisible : `errors: []`.

### Erreur 404 — `BOOK_NOT_FOUND`
```json
{
  "type": "about:blank",
  "title": "Livre introuvable",
  "status": 404,
  "detail": "Aucun livre du catalogue ne correspond à cet identifiant",
  "instance": "/api/v1/loans",
  "code": "BOOK_NOT_FOUND"
}
```

### Erreurs 409
| `code` | `title` | `detail` (indicatif) | Message à afficher (frontend) |
|---|---|---|---|
| `MEMBER_HAS_OVERDUE_LOAN` | Emprunt en retard | Vous avez un emprunt en retard : rendez-le avant d'emprunter un autre livre | Rendez d'abord vos livres en retard |
| `LOAN_LIMIT_REACHED` | Limite d'emprunts atteinte | Vous avez déjà 3 emprunts en cours | Vous avez atteint la limite de 3 emprunts en cours |
| `BOOK_ALREADY_BORROWED` | Livre déjà emprunté | Vous avez déjà un emprunt en cours pour ce livre | Vous avez déjà emprunté ce livre |
| `BOOK_NOT_AVAILABLE` | Livre indisponible | Aucun exemplaire de ce livre n'est disponible | Ce livre n'est plus disponible |

Exemple :
```json
{
  "type": "about:blank",
  "title": "Limite d'emprunts atteinte",
  "status": 409,
  "detail": "Vous avez déjà 3 emprunts en cours",
  "instance": "/api/v1/loans",
  "code": "LOAN_LIMIT_REACHED"
}
```

### Erreurs 401 et 403
Voir « Erreurs communes ».

---

## GET `/api/v1/loans` — mes emprunts en cours
Emprunts **actifs** (non rendus) du membre connecté uniquement, au plus 3. Tri : `dueOn` croissant, puis `id`. Pas de pagination. Les emprunts rendus ne sont pas exposés (ils restent conservés).

### Réponse 200 — `CurrentLoan[]`
```json
[
  {
    "id": "a1b2c3d4-0000-4000-8000-000000000101",
    "bookId": "8b1e4d70-52a3-4f0b-b6c9-7e2d1a9f3c55",
    "isbn": "9782070360420",
    "title": "La Peste",
    "author": "Albert Camus",
    "borrowedOn": "2026-11-05",
    "dueOn": "2026-11-19",
    "overdue": true
  },
  {
    "id": "6d1f0c4b-8a2e-4f7a-b3d9-2c5e7a9b1f30",
    "bookId": "3f6c2a9e-1b7d-4c1e-9a52-0d8f6b2e4a11",
    "isbn": "9782070360024",
    "title": "L'Étranger",
    "author": "Albert Camus",
    "borrowedOn": "2026-11-24",
    "dueOn": "2026-12-08",
    "overdue": false
  },
  {
    "id": "f0e1d2c3-0000-4000-8000-000000000303",
    "bookId": "11111111-2222-4333-8444-555555555555",
    "isbn": null,
    "title": null,
    "author": null,
    "borrowedOn": "2026-11-24",
    "dueOn": "2026-12-08",
    "overdue": false
  }
]
```
Aucun emprunt en cours : `200` avec `[]`.

### Erreurs
401 et 403 (voir « Erreurs communes »).

---

## POST `/api/v1/loans/{id}/return` — rendre un emprunt
`{id}` : UUID de l'emprunt. Pas de corps.

### Règles
- L'emprunt doit appartenir au membre connecté. Un emprunt d'un autre membre, un identifiant inconnu et un identifiant mal formé (`abc`) donnent la **même** réponse : 404 `LOAN_NOT_FOUND`.
- Un emprunt déjà rendu donne 409 `LOAN_ALREADY_RETURNED`, sans second effet. Deux retours simultanés donnent exactement un 204 et un 409.
- Un emprunt en retard se rend normalement. Le blocage `MEMBER_HAS_OVERDUE_LOAN` est levé immédiatement si c'était le dernier emprunt en retard.
- Effet : `returnedOn` = date du jour. `availableCopies` du livre augmente de 1 (sans dépasser `totalCopies`) dans la même transaction. Le livre réapparaît dans `GET /api/v1/books`.

### Réponse 204
Sans corps.

### Erreur 404 — `LOAN_NOT_FOUND`
```json
{
  "type": "about:blank",
  "title": "Emprunt introuvable",
  "status": 404,
  "detail": "Aucun emprunt ne correspond à cet identifiant",
  "instance": "/api/v1/loans/abc/return",
  "code": "LOAN_NOT_FOUND"
}
```
Message à afficher : « Emprunt introuvable ».

### Erreur 409 — `LOAN_ALREADY_RETURNED`
```json
{
  "type": "about:blank",
  "title": "Emprunt déjà rendu",
  "status": 409,
  "detail": "Cet emprunt a déjà été rendu",
  "instance": "/api/v1/loans/6d1f0c4b-8a2e-4f7a-b3d9-2c5e7a9b1f30/return",
  "code": "LOAN_ALREADY_RETURNED"
}
```
Message à afficher : « Cet emprunt est déjà rendu ».

---

## Erreurs communes
### 401 — `UNAUTHENTICATED`
Jeton absent, invalide, mal signé, expiré, ou `sub` qui n'est pas un UUID. En-tête `WWW-Authenticate: Bearer`. Aucune donnée n'est modifiée.
```json
{ "type": "about:blank", "title": "Authentification requise", "status": 401, "code": "UNAUTHENTICATED" }
```

### 403 — `ACCESS_DENIED`
Jeton valide sans rôle `MEMBER` (ex. `LIBRARIAN` seul), **ou** `POST` par cookie sans jeton CSRF valide.
```json
{ "type": "about:blank", "title": "Accès refusé", "status": 403, "code": "ACCESS_DENIED" }
```

### 503 — `LOAN_BUSY` (POST `/loans` et POST `/loans/{id}/return`)
Délai d'attente d'un verrou dépassé (5 s) : une autre opération sur le même membre, le même emprunt ou le même livre est en cours. Rien n'a été modifié ; la requête peut être rejouée. En-tête `Retry-After: 1` (secondes).
```json
{
  "type": "about:blank",
  "title": "Service momentanément occupé",
  "status": 503,
  "detail": "Réessayez dans quelques instants",
  "instance": "/api/v1/loans",
  "code": "LOAN_BUSY"
}
```
Message à afficher : « Le service est momentanément occupé, réessayez ».

## Rôles
| Acteur | POST `/loans` | GET `/loans` | POST `/loans/{id}/return` |
|---|---|---|---|
| `MEMBER` | 201 ou 4xx métier | 200 (les siens) | 204 ou 4xx métier (les siens) |
| `LIBRARIAN` + `MEMBER` | 201 ou 4xx métier (pour lui-même) | 200 (les siens) | 204 ou 4xx métier (les siens) |
| `LIBRARIAN` seul | 403 | 403 | 403 |
| Non authentifié | 401 | 401 | 401 |
| Cookie sans `X-XSRF-TOKEN` | 403 | 200 | 403 |
| `Bearer` sans CSRF | 201 ou 4xx | 200 | 204 ou 4xx |

## Effets sur le catalogue (`docs/api/catalogue.md`, contrat inchangé)
- Un emprunt réussi baisse `availableCopies` de 1 ; un retour l'augmente de 1.
- `GET /api/v1/books` ne liste que `availableCopies >= 1` : un livre emprunté jusqu'au dernier exemplaire disparaît à la lecture suivante, un livre rendu réapparaît.

## Données simulées (`ng serve --configuration mock`)
`src/mocks/loans/` : `loans.json` (graine, échéances relatives à la date du jour), `validation-failed.json`, `book-not-found.json`, `member-has-overdue-loan.json`, `loan-limit-reached.json`, `book-already-borrowed.json`, `book-not-available.json`, `loan-not-found.json`, `loan-already-returned.json`, `access-denied.json`.
Comptes fictifs (`src/mocks/auth/users.json`) :
- `membre@exemple.test` (MEMBER, sans emprunt) ;
- `membre-retard@exemple.test` (MEMBER, un emprunt en retard) ;
- `membre-bibliothecaire@exemple.test` (LIBRARIAN + MEMBER) ;
- `bibliothecaire@exemple.test` (LIBRARIAN seul).

Les règles et l'ordre d'évaluation sont les mêmes que ci-dessus. L'état est en mémoire et partagé avec le catalogue simulé (les compteurs diminuent et augmentent) jusqu'au rechargement de la page.
