# Contrat d'API — Catalogue (`/api/v1/books`)

> Source : `docs/specs/catalogue.md` (consultation) et `docs/specs/catalog-enregistrement-livre.md` (enregistrement).
> Plan : `docs/specs/catalogue-plan.md`. Contrat validé le 2026-11-23.
> Ce document se suffit à lui-même : le frontend et le mode `mock` s'appuient dessus.

## Vue d'ensemble
| Méthode | URL | Rôle | Request | Response | Codes HTTP |
|---|---|---|---|---|---|
| GET | `/api/v1/books` | `LIBRARIAN` ou `MEMBER` | paramètres `page`, `size`, `q` | `BookPage` | 200, 400, 401, 403 |
| POST | `/api/v1/books` | `LIBRARIAN` | `RegisterBookRequest` (JSON) | `Book` + en-tête `Location` | 201, 400, 401, 403, 409 |

**Authentification** (les deux endpoints) : cookie `ACCESS_TOKEN` (navigateur, HttpOnly) **ou** en-tête `Authorization: Bearer <jwt>`. L'en-tête est prioritaire : s'il est présent, le cookie n'est pas lu.
**CSRF** : non exigé pour `GET`. Exigé pour `POST` authentifié par cookie : en-tête `X-XSRF-TOKEN` égal au cookie `XSRF-TOKEN`, géré automatiquement par `HttpClient` d'Angular. Les requêtes `Bearer` en sont dispensées.
**Ordre d'évaluation** : authentification (401), puis rôle ou CSRF (403), puis validation (400), puis métier (409).
**Format des erreurs** : `application/problem+json` (RFC 9457) avec la propriété `code`, plus `errors[]` pour la validation.

## Types communs
```ts
Book {
  id: string              // UUID
  isbn: string            // 13 chiffres, forme normalisée (sans tiret ni espace)
  title: string           // 1 à 255 caractères
  author: string          // 1 à 255 caractères
  totalCopies: number     // entier, 1 à 1000
  availableCopies: number // entier, 0 à totalCopies (>= 1 dans les réponses de GET)
}

FieldError {
  field: string           // page | size | q | isbn | title | author | copies
  code: string            // REQUIRED | BLANK | TOO_LONG | INVALID_FORMAT | OUT_OF_RANGE
  message: string         // message en français, affichable tel quel
}

ProblemDetail {
  type: string            // "about:blank"
  title: string
  status: number
  detail?: string
  instance?: string       // chemin de la requête
  code: string            // VALIDATION_FAILED | UNAUTHENTICATED | ACCESS_DENIED | ISBN_ALREADY_EXISTS
  errors?: FieldError[]   // seulement pour VALIDATION_FAILED (peut être vide : corps JSON illisible)
}
```

---

## GET `/api/v1/books` — liste paginée des livres disponibles

### Paramètres de requête
| Paramètre | Type | Défaut | Bornes / règles | Erreur |
|---|---|---|---|---|
| `page` | entier | `0` | `>= 0` (base 0). Une page au-delà de la dernière renvoie 200 avec `content: []`. | `OUT_OF_RANGE` si < 0 ; `INVALID_FORMAT` si non entier (`abc`, `1.5`, dépassement d'entier) |
| `size` | entier | `10` | `1` à `50` | `OUT_OF_RANGE` si hors bornes ; `INVALID_FORMAT` si non entier |
| `q` | texte | absent | Rogné (trim). Absent, vide ou blanc : pas de filtre. 100 caractères maximum après trim. Sous-chaîne **insensible à la casse** et **sensible aux accents** du **titre OU de l'auteur** (pas de l'ISBN). `%`, `_` et `\` sont des caractères littéraux. Encoder en URL (`%` s'écrit `%25`). | `TOO_LONG` si plus de 100 caractères |
| autre (`sort`, …) | — | — | Ignoré : le tri n'est pas pilotable. | — |

Un paramètre présent mais vide (`?page=`) vaut sa valeur par défaut.

### Sémantique
- Seuls les livres avec `availableCopies >= 1` sont renvoyés et comptés.
- Tri fixe et total : titre sans tenir compte de la casse (`lower(title)`, ordre binaire `COLLATE "C"` : les titres accentués comme « Émile » viennent après « z »), puis ISBN. Deux pages successives ne se chevauchent pas en l'absence d'écriture entre les deux appels.
- Le filtre `q` s'applique avant la pagination ; `totalElements` et `totalPages` portent sur le résultat filtré.
- Après un `POST` réussi, le livre apparaît à la lecture suivante (cache invalidé après commit ; retard d'au plus 10 min si l'invalidation échoue).

### Réponse 200 — `BookPage`
| Champ | Type | Description |
|---|---|---|
| `content` | `Book[]` | Livres de la page (0 à `size` éléments) |
| `page` | entier | Page demandée (base 0) |
| `size` | entier | Taille demandée |
| `totalElements` | entier (long) | Nombre total de livres correspondants |
| `totalPages` | entier | `ceil(totalElements / size)` ; `0` si aucun résultat |

Exemple : `GET /api/v1/books?q=camus&page=0&size=10`
```json
{
  "content": [
    {
      "id": "3f6c2a9e-1b7d-4c1e-9a52-0d8f6b2e4a11",
      "isbn": "9782070360024",
      "title": "L'Étranger",
      "author": "Albert Camus",
      "totalCopies": 3,
      "availableCopies": 2
    },
    {
      "id": "8b1e4d70-52a3-4f0b-b6c9-7e2d1a9f3c55",
      "isbn": "9782070360420",
      "title": "La Peste",
      "author": "Albert Camus",
      "totalCopies": 2,
      "availableCopies": 2
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 2,
  "totalPages": 1
}
```

Sans résultat ou au-delà de la dernière page (200, jamais 404) :
```json
{ "content": [], "page": 0, "size": 10, "totalElements": 0, "totalPages": 0 }
```

### Erreur 400 — `VALIDATION_FAILED`
Toutes les erreurs sont renvoyées ensemble. Exemple : `GET /api/v1/books?page=-1&size=abc`
```json
{
  "type": "about:blank",
  "title": "Validation échouée",
  "status": 400,
  "detail": "La requête est invalide",
  "instance": "/api/v1/books",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "page", "code": "OUT_OF_RANGE", "message": "Le paramètre 'page' doit être un entier supérieur ou égal à 0" },
    { "field": "size", "code": "INVALID_FORMAT", "message": "Le paramètre 'size' doit être un entier compris entre 1 et 50" }
  ]
}
```
`q` trop long : `{ "field": "q", "code": "TOO_LONG", "message": "Le paramètre 'q' est trop long (100 caractères maximum)" }`.
Les messages sont indicatifs ; `field` et `code` sont contractuels.

### Erreur 401 — `UNAUTHENTICATED`
Jeton absent, invalide, mal signé ou expiré. En-tête `WWW-Authenticate: Bearer`.
```json
{ "type": "about:blank", "title": "Authentification requise", "status": 401, "code": "UNAUTHENTICATED" }
```

### Erreur 403 — `ACCESS_DENIED`
Jeton valide mais sans rôle `LIBRARIAN` ni `MEMBER`.
```json
{ "type": "about:blank", "title": "Accès refusé", "status": 403, "code": "ACCESS_DENIED" }
```

---

## POST `/api/v1/books` — enregistrer un livre (existant, inchangé)

### Requête — `RegisterBookRequest` (`application/json`)
| Champ | Type | Contraintes | Codes d'erreur possibles |
|---|---|---|---|
| `isbn` | string | Obligatoire. Espaces et tirets retirés, puis exactement 13 chiffres (pas de calcul de clé de contrôle). `978-2-07-036822-8` est accepté. | `REQUIRED`, `INVALID_FORMAT` |
| `title` | string | Obligatoire, non blanc, 255 caractères maximum après trim | `REQUIRED`, `BLANK`, `TOO_LONG` |
| `author` | string | Obligatoire, non blanc, 255 caractères maximum après trim | `REQUIRED`, `BLANK`, `TOO_LONG` |
| `copies` | entier | Obligatoire, de 1 à 1000 | `REQUIRED`, `OUT_OF_RANGE`, `INVALID_FORMAT` (non entier) |

```json
{ "isbn": "978-2-07-036822-8", "title": "L'Étranger", "author": "Albert Camus", "copies": 3 }
```

### Réponse 201 — `Book`
En-tête `Location: /api/v1/books/{id}` (pas encore d'endpoint `GET` correspondant, hors périmètre). Tous les exemplaires sont disponibles ; ISBN normalisé ; titre et auteur rognés.
```json
{
  "id": "c0a8012e-7f3b-4d2a-9e61-5b4c3d2e1f00",
  "isbn": "9782070368228",
  "title": "L'Étranger",
  "author": "Albert Camus",
  "totalCopies": 3,
  "availableCopies": 3
}
```

### Erreur 400 — `VALIDATION_FAILED`
```json
{
  "type": "about:blank",
  "title": "Validation échouée",
  "status": 400,
  "detail": "La requête est invalide",
  "instance": "/api/v1/books",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "isbn", "code": "INVALID_FORMAT", "message": "Le champ 'isbn' a un format invalide" },
    { "field": "copies", "code": "OUT_OF_RANGE", "message": "Le champ 'copies' est hors des bornes autorisées" }
  ]
}
```
Corps JSON illisible : `errors: []`, à afficher en message général. Champ de type incorrect : entrée `INVALID_FORMAT` sur ce champ.

### Erreur 409 — `ISBN_ALREADY_EXISTS` (sans `errors[]`)
À afficher sous le champ `isbn` à partir de `code`.
```json
{
  "type": "about:blank",
  "title": "ISBN déjà enregistré",
  "status": 409,
  "detail": "Un livre avec cet ISBN existe déjà",
  "instance": "/api/v1/books",
  "code": "ISBN_ALREADY_EXISTS"
}
```

### Erreur 401 — `UNAUTHENTICATED`
Même corps que pour le GET, avec `WWW-Authenticate: Bearer`.

### Erreur 403 — `ACCESS_DENIED`
Utilisateur sans rôle `LIBRARIAN` (ex. `MEMBER`), **ou** requête par cookie sans jeton CSRF valide.
```json
{ "type": "about:blank", "title": "Accès refusé", "status": 403, "code": "ACCESS_DENIED" }
```

---

## Rôles
| Acteur | GET `/api/v1/books` | POST `/api/v1/books` |
|---|---|---|
| `LIBRARIAN` | 200 | 201 |
| `MEMBER` | 200 | 403 |
| `LIBRARIAN` + `MEMBER` | 200 | 201 |
| Jeton valide sans ces rôles | 403 | 403 |
| Non authentifié | 401 | 401 |

## Données simulées (`ng serve --configuration mock`)
`src/mocks/catalogue/` : `books.json`, `validation-failed.json`, `isbn-already-exists.json`, `access-denied.json`. Mêmes règles que ci-dessus. État en mémoire : un livre créé apparaît dans la liste jusqu'au rechargement de la page.
