# Enregistrement d'un livre (module catalog)

## Contexte et objectif
La bibliothèque prête des livres à ses membres. Avant tout emprunt, le catalogue doit contenir les livres.
Cette fonctionnalité permet à un bibliothécaire d'enregistrer un nouveau livre avec son ISBN, son titre,
son auteur et le nombre d'exemplaires physiques dont la bibliothèque dispose.

Le livre enregistré est ensuite utilisé par le module `loan` (RM-04 : emprunt possible seulement s'il reste un exemplaire disponible).

Constat sur le code existant (`backend/src/main/java/com/example/library/`) :
- Le module `catalog` ne contient que le squelette hexagonal (packages vides, `package-info.java`) et `DomainException(code, message)`. Aucune entité, aucun contrôleur, aucune migration.
- `SecurityConfig` : JWT HS256, rôles lus dans la claim `roles` et préfixés `ROLE_`, `@EnableMethodSecurity` actif, toute requête hors `/actuator/health`, `/actuator/info` et `/dev/token` exige une authentification (donc 401 sans token valide). Le contrôle du rôle se fait par `@PreAuthorize("hasRole('LIBRARIAN')")` (refus = 403).
- Skill `rest-api-design` : préfixe de version obligatoire `/api/v1`, ressources au pluriel en kebab-case, création = `POST` -> 201 + `Location`, erreurs 400 / 409, corps d'erreur `ProblemDetail` (RFC 9457) avec `code` métier et `errors[]` pour la validation.
- Chemin retenu : `POST /api/v1/books`.

## Acteurs et rôles (qui a le droit de faire quoi)
| Acteur | Droit sur cette fonctionnalité |
|---|---|
| `LIBRARIAN` | Peut enregistrer un livre. |
| `MEMBER` | Interdit (403). |
| Non authentifié (pas de JWT, JWT invalide ou expiré) | Interdit (401). |

Le rôle est lu dans la claim `roles` du JWT. Un token portant à la fois `MEMBER` et `LIBRARIAN` est autorisé (présence de `LIBRARIAN` suffisante).

## Règles métier (numérotées RM-01, RM-02…)
Les numéros RM-01 à RM-07 du CLAUDE.md sont les règles de référence du projet ; les règles propres à cette spec sont préfixées `RM-CAT-`.

- **RM-07 (référence)** : l'ISBN d'un livre est unique et valide (13 chiffres).
- **RM-CAT-01** : seul un utilisateur authentifié ayant le rôle `LIBRARIAN` peut enregistrer un livre.
- **RM-CAT-02** : l'ISBN est obligatoire et, après normalisation (RM-CAT-03), doit contenir exactement 13 chiffres (caractères `0-9` uniquement). Toute autre valeur est refusée. Le calcul de la clé de contrôle ISBN-13 n'est pas exigé (hypothèse H1, validée).
- **RM-CAT-03** : avant validation et stockage, l'ISBN est normalisé : suppression des espaces et des tirets (`-`) en début, fin et à l'intérieur. `978-2-07-036822-8` et `9782070368228` désignent le même ISBN. L'ISBN stocké et renvoyé est la forme à 13 chiffres sans séparateur (hypothèse H2, validée).
- **RM-CAT-04** : l'unicité de l'ISBN s'apprécie sur la forme normalisée. Enregistrer un ISBN déjà présent au catalogue est refusé, et aucune donnée n'est modifiée (pas de mise à jour du stock d'exemplaires du livre existant).
- **RM-CAT-05** : le titre est obligatoire. Il est rogné (trim) des espaces de début et de fin ; une valeur vide ou composée uniquement d'espaces est refusée. Longueur maximale : 255 caractères après trim (hypothèse H3, validée).
- **RM-CAT-06** : l'auteur est obligatoire, avec les mêmes règles que le titre (trim, non vide). Longueur maximale : 255 caractères (hypothèse H3, validée). Un seul champ texte libre `author` (pas de liste d'auteurs).
- **RM-CAT-07** : le nombre d'exemplaires est obligatoire, entier, supérieur ou égal à 1. Borne maximale : 1000 (hypothèse H4, validée). Une valeur absente, non entière, nulle, négative ou supérieure à la borne est refusée.
- **RM-CAT-08** : un livre est enregistré de façon atomique : soit toutes les données sont valides et le livre est créé avec ses exemplaires, soit rien n'est créé.
- **RM-CAT-09** : à l'enregistrement, tous les exemplaires sont disponibles (aucun emprunt n'existe pour un livre neuf). Le nombre d'exemplaires disponibles est donc égal au nombre d'exemplaires saisi.
- **RM-CAT-10** : les validations sont toutes évaluées et toutes les erreurs de validation sont renvoyées ensemble (pas d'arrêt à la première). Les erreurs de format (RM-CAT-02, 05, 06, 07) sont signalées avant le contrôle d'unicité (RM-CAT-04) : si le format est invalide, l'unicité n'est pas vérifiée.
- **RM-CAT-11** : la règle de validité de l'ISBN (RM-07 / RM-CAT-02, 03) et les bornes sont portées par le domaine (value object `Isbn`, agrégat `Book`) ; la validation de forme de la requête dans l'adapter web ne remplace pas ces règles.
- **RM-CAT-12** : les exemplaires sont représentés par deux compteurs portés par le livre (`totalCopies`, `availableCopies`) ; il n'existe pas d'entité exemplaire. `availableCopies` appartient au module `catalog` : le futur module `loan` ne le modifie jamais directement, il passe par un port d'entrée de `catalog` (décision Q1).

## Critères d'acceptation (Gherkin : Étant donné / Quand / Alors)

### Contrat
`POST /api/v1/books`, corps JSON :
```
{ "isbn": "9782070368228", "title": "L'Étranger", "author": "Albert Camus", "copies": 3 }
```
Succès : 201, en-tête `Location: /api/v1/books/{id}`, corps `{ "id", "isbn", "title", "author", "totalCopies", "availableCopies" }`. La `Location` est acceptée même si le `GET /api/v1/books/{id}` n'existe pas encore.
Erreurs : `ProblemDetail` (`type`, `title`, `status`, `detail`, `code`, `errors[]` avec `field` et `code` pour la validation).

Codes métier : `ISBN_INVALID`, `ISBN_ALREADY_EXISTS`, `VALIDATION_FAILED` (erreur multiple ou erreur de champ). Détail des codes de champ dans `errors[]` : `REQUIRED`, `BLANK`, `TOO_LONG`, `INVALID_FORMAT`, `OUT_OF_RANGE`.

```gherkin
Scénario: Enregistrement nominal
  Étant donné un utilisateur authentifié avec le rôle LIBRARIAN
  Et aucun livre d'ISBN "9782070368228" au catalogue
  Quand il envoie POST /api/v1/books avec isbn "9782070368228", title "L'Étranger", author "Albert Camus", copies 3
  Alors la réponse a le statut 201
  Et l'en-tête Location pointe vers /api/v1/books/{id} du livre créé
  Et le corps contient isbn "9782070368228", totalCopies 3 et availableCopies 3
  Et le livre est retrouvable au catalogue avec ces valeurs

Scénario: ISBN avec tirets normalisé
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un livre avec isbn "978-2-07-036822-8"
  Alors la réponse a le statut 201
  Et l'ISBN renvoyé et stocké est "9782070368228"

Scénario: ISBN avec espaces autour normalisé
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un livre avec isbn " 9782070368228 "
  Alors la réponse a le statut 201
  Et l'ISBN stocké est "9782070368228"

Plan du Scénario: ISBN invalide
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un livre avec isbn "<isbn>"
  Alors la réponse a le statut 400
  Et le code est "ISBN_INVALID" (ou "VALIDATION_FAILED" avec errors[].field = "isbn")
  Et aucun livre n'est créé
  Exemples:
    | isbn              | motif                    |
    | 978207036822      | 12 chiffres              |
    | 97820703682280    | 14 chiffres              |
    | 978207036822A     | caractère non numérique  |
    | 978.2070368228    | séparateur non autorisé  |
    | (chaîne vide)     | vide                     |
    | (champ absent)    | absent                   |

Scénario: ISBN déjà existant
  Étant donné un livre d'ISBN "9782070368228" déjà enregistré avec 3 exemplaires
  Et un LIBRARIAN authentifié
  Quand il envoie un livre avec isbn "9782070368228" et copies 5
  Alors la réponse a le statut 409
  Et le code est "ISBN_ALREADY_EXISTS"
  Et le livre existant garde 3 exemplaires
  Et aucun second livre n'est créé

Scénario: ISBN existant saisi sous une autre forme
  Étant donné un livre d'ISBN "9782070368228" déjà enregistré
  Quand un LIBRARIAN envoie isbn "978-2-07-036822-8"
  Alors la réponse a le statut 409 avec le code "ISBN_ALREADY_EXISTS"

Plan du Scénario: Titre ou auteur vide
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un livre avec <champ> égal à "<valeur>"
  Alors la réponse a le statut 400
  Et errors[] contient une entrée pour le champ "<champ>"
  Et aucun livre n'est créé
  Exemples:
    | champ  | valeur         |
    | title  | (chaîne vide)  |
    | title  | "   "          |
    | title  | (champ absent) |
    | author | (chaîne vide)  |
    | author | "   "          |
    | author | (champ absent) |

Scénario: Titre trop long
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un titre de 256 caractères
  Alors la réponse a le statut 400 avec errors[].field = "title" et code "TOO_LONG"

Scénario: Titre à la longueur maximale
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un titre de 255 caractères valides
  Alors la réponse a le statut 201

Plan du Scénario: Nombre d'exemplaires invalide
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un livre avec copies = <valeur>
  Alors la réponse a le statut 400
  Et errors[] contient une entrée pour le champ "copies"
  Et aucun livre n'est créé
  Exemples:
    | valeur         |
    | 0              |
    | -1             |
    | 1001           |
    | 2.5            |
    | "trois"        |
    | (champ absent) |

Plan du Scénario: Nombre d'exemplaires aux bornes valides
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un livre avec copies = <valeur>
  Alors la réponse a le statut 201 et availableCopies = <valeur>
  Exemples:
    | valeur |
    | 1      |
    | 1000   |

Scénario: Non authentifié
  Étant donné une requête sans en-tête Authorization
  Quand elle envoie POST /api/v1/books avec un corps valide
  Alors la réponse a le statut 401
  Et aucun livre n'est créé

Scénario: Token invalide ou expiré
  Étant donné un JWT expiré ou mal signé
  Quand il est utilisé sur POST /api/v1/books avec un corps valide
  Alors la réponse a le statut 401

Scénario: Rôle MEMBER
  Étant donné un utilisateur authentifié avec le seul rôle MEMBER
  Quand il envoie POST /api/v1/books avec un corps valide
  Alors la réponse a le statut 403
  Et aucun livre n'est créé

Scénario: Autorisation évaluée avant la validation
  Étant donné un utilisateur MEMBER
  Quand il envoie POST /api/v1/books avec un corps invalide
  Alors la réponse a le statut 403 (et non 400)

Scénario: Erreurs multiples
  Étant donné un LIBRARIAN authentifié
  Quand il envoie isbn "123", title "", author "   ", copies 0
  Alors la réponse a le statut 400 et le code "VALIDATION_FAILED"
  Et errors[] contient quatre entrées, pour isbn, title, author et copies
  Et aucun livre n'est créé

Scénario: Erreur de format et ISBN existant
  Étant donné un livre d'ISBN "9782070368228" déjà enregistré
  Quand un LIBRARIAN envoie isbn "9782070368228", title "" et copies 3
  Alors la réponse a le statut 400 (erreur de titre), pas 409

Scénario: Corps JSON malformé
  Étant donné un LIBRARIAN authentifié
  Quand il envoie un corps qui n'est pas du JSON valide
  Alors la réponse a le statut 400 au format ProblemDetail
```

## Données (entités, champs, contraintes)

### Entité `Book` (agrégat du domaine, module `catalog`)
| Champ | Type | Contraintes |
|---|---|---|
| `id` | identifiant technique (value object `BookId`, UUID) | généré par le système, immuable |
| `isbn` | value object `Isbn` | 13 chiffres exactement, unique, stocké sans séparateur (`CHAR(13)` ou `VARCHAR(13)`), index unique en base |
| `title` | texte | obligatoire, non vide après trim, <= 255 |
| `author` | texte | obligatoire, non vide après trim, <= 255 |
| `totalCopies` | entier | >= 1 et <= 1000 |
| `availableCopies` | entier | à la création = `totalCopies` ; toujours entre 0 et `totalCopies` ; propriété du module `catalog` (modifié uniquement via un port d'entrée de `catalog`) |

Contraintes de base à prévoir dans la migration Flyway : `UNIQUE(isbn)`, `NOT NULL` sur tous les champs, `CHECK(total_copies >= 1)`. Une violation de l'index unique, en cas de concurrence entre deux requêtes simultanées, doit aussi produire 409 `ISBN_ALREADY_EXISTS` (jamais 500).

### Requête (DTO d'entrée)
`isbn` (string), `title` (string), `author` (string), `copies` (integer).

### Réponse (DTO de sortie)
`id`, `isbn`, `title`, `author`, `totalCopies`, `availableCopies`. Aucune entité JPA n'est exposée.

## Hors périmètre
- Modification, suppression, consultation, recherche ou liste des livres.
- Ajout ou retrait d'exemplaires sur un livre existant.
- Gestion individualisée des exemplaires (cote, état, localisation) : il n'y a pas d'entité exemplaire (décision Q1).
- Port d'entrée de `catalog` permettant à `loan` de décrémenter ou incrémenter `availableCopies` (à spécifier avec le module `loan`).
- Récupération de métadonnées externes (API ISBN, couverture, résumé).
- Éditeur, année, langue, catégories, plusieurs auteurs.
- Conversion ISBN-10 vers ISBN-13.
- Interface utilisateur (Angular).
- Audit (qui a enregistré le livre) et journalisation métier.
- Idempotence de la requête (réessai avec une clé d'idempotence).

## Questions ouvertes

Plus aucune question bloquante : toutes les questions ont été tranchées par l'humain.

### TRANCHÉE
- **Q1 - Modèle des exemplaires.** Décision : compteur sur le livre (`totalCopies` / `availableCopies`), pas d'entité exemplaire. `availableCopies` appartient au module `catalog` ; le futur module `loan` ne le modifie jamais directement, il passe par un port d'entrée de `catalog` (voir RM-CAT-12).

### Hypothèses H1 à H10 : validées telles quelles
- **H1 - Clé de contrôle ISBN-13.** Seule la forme « 13 chiffres » est vérifiée (RM-07). Pas de calcul de checksum : `9782070368229` (checksum faux) est accepté.
- **H2 - Normalisation.** Espaces et tirets acceptés en entrée et supprimés ; l'ISBN est stocké et renvoyé sans séparateur.
- **H3 - Longueurs maximales.** Titre et auteur <= 255 caractères après trim ; pas de contrainte sur les caractères.
- **H4 - Borne maximale d'exemplaires.** 1000.
- **H5 - Chemin d'API.** `POST /api/v1/books`, avec `copies` en entrée et `totalCopies` en sortie.
- **H6 - Codes HTTP.** 400 pour toute erreur de validation (y compris ISBN invalide), 409 pour l'ISBN déjà existant, 401 sans authentification valide, 403 pour le rôle MEMBER.
- **H7 - Format d'erreur.** `ProblemDetail` (RFC 9457) avec `code` métier et `errors[]` (`field`, `code`, `message`). Messages en français. Les 401 et 403 gérés par Spring Security gardent le format par défaut du framework.
- **H8 - Réponse de création.** 201 avec `Location` et le livre créé dans le corps. La `Location` vers `/api/v1/books/{id}` est acceptée même si le GET n'existe pas encore.
- **H9 - Doublons titre/auteur.** Seul l'ISBN est unique.
- **H10 - Ordre des contrôles.** Authentification (401), autorisation (403), validation (400), unicité (409).
