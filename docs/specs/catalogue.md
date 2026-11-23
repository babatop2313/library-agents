# Catalogue : liste paginée des livres et enregistrement depuis l'interface

## Contexte et objectif
Les membres et les bibliothécaires doivent pouvoir consulter le catalogue depuis l'application web : liste des livres disponibles (titre, auteur, ISBN, exemplaires disponibles), paginée, avec recherche par titre ou auteur. Le bibliothécaire (`LIBRARIAN`) peut en plus enregistrer un nouveau livre depuis un formulaire. Un lien « Catalogue » est ajouté à l'en-tête.

Périmètre : backend (nouveau `GET /api/v1/books`) + frontend Angular 21 / PrimeNG 21 branché sur la vraie API. L'enregistrement (`POST /api/v1/books`) existe déjà et est réutilisé tel quel.

### Constat sur l'existant
**Backend (module `catalog`)**
- `BookController` (`@RequestMapping("/api/v1/books")`) ne porte que `POST` (`@PreAuthorize("hasRole('LIBRARIAN')")`, doublé dans `SecurityConfig` par `requestMatchers(POST, "/api/v1/books").hasRole("LIBRARIAN")`). Corps `RegisterBookRequest { isbn, title, author, copies }`, réponse 201 + `Location` + `BookResponse { id, isbn, title, author, totalCopies, availableCopies }`. Spec de référence : `docs/specs/catalog-enregistrement-livre.md` (RM-CAT-01 à RM-CAT-12, non répétées ici).
- Erreurs de `POST` (`CatalogExceptionHandler`, `ProblemDetail` avec `code`) : 400 `VALIDATION_FAILED` + `errors[] { field, code, message }` (codes de champ `REQUIRED`, `BLANK`, `TOO_LONG`, `INVALID_FORMAT`, `OUT_OF_RANGE` ; champs `isbn`, `title`, `author`, `copies`) ; 409 `ISBN_ALREADY_EXISTS` **sans `errors[]`** (seulement `code`, `title`, `detail`) ; 401/403 en `ProblemDetail` via `ProblemDetailAuthenticationEntryPoint` / `ProblemDetailAccessDeniedHandler` (codes `UNAUTHENTICATED`, `ACCESS_DENIED` côté frontend).
- **Aucun `GET` REST sur les livres.** La lecture n'existe que via MCP (`CatalogMcpTools`) : `get_book(isbn)` et `list_available_books(limit)`. Écart : pas de pagination, pas de recherche, pas de total, limite 1 à 20 (défaut 10) via `AvailableBooksLimit`, uniquement les livres avec `availableCopies >= 1`, tri titre (insensible à la casse, `COLLATE "C"`) puis ISBN. Le `Location` renvoyé par `POST` (`/api/v1/books/{id}`) ne pointe vers aucun endpoint.
- Table `book` (V1) : `UNIQUE(isbn)` ; aucun index sur `title` ni `author`.
- **Cache Redis** (`AvailableBooksCacheAdapter`, décorateur `@Primary` de `LoadAvailableBooksPort` et `SaveBookPort`) : **une seule clé** `library:catalog:available-books:v1`, TTL `app.cache.ttl.default` (10 min), qui contient **les 20 premiers livres disponibles** dans l'ordre du catalogue (JSON, liste de `CachedBook`). Lecture : `loadAvailableBooks(maxResults)` renvoie un préfixe de cette liste. Invalidation après commit de `save`. Repli sur la base si Redis est indisponible ou illisible (WARN).
  **Incompatibilité avec la pagination et la recherche** : le cache ne contient ni le nombre total de livres disponibles (indispensable à un paginateur), ni les livres au-delà du 20e, ni aucune notion de filtre ; la clé ne varie pas selon les paramètres. Il ne peut servir que la tête de liste (page 0, taille <= 20, sans recherche) et seulement pour la liste, pas pour le total. Voir RM-CAT-24 et questions Q1, Q2.

**Frontend (`library-web/src/app`)**
- Routes : `login` (`guestGuard`) et `''` protégée par `authGuard` (`canActivateChild`), seul enfant `Home`. Pas de route catalogue, pas de guard de rôle. L'en-tête (`core/layout/header`) affiche le titre (lien `/`), l'e-mail, les rôles (`p-tag`) et « Se déconnecter » ; aucune navigation. `AuthService.user` expose `CurrentUser { userId, email, roles: Role[], expiresAt }`.
- `errorInterceptor` : toute erreur HTTP hors 401 affiche un toast (`p-toast`) ; 403 = message fixe ; sinon `title`/`detail` du `ProblemDetail` ; l'erreur est **toujours propagée** pour que le formulaire reporte les `errors[]` sur les champs. `core/http/problem-detail.ts` fournit `ProblemDetail` et `FieldError`.
- Le mode `mock` (`core/mock/mock-api.interceptor`) ne simule que l'authentification : la spec exige le branchement sur la vraie API ; le catalogue sera aussi simulé en mode mock (décision Q12).

## Acteurs et rôles (qui a le droit de faire quoi)
| Acteur | Consulter le catalogue (`GET /api/v1/books`) | Enregistrer un livre (`POST /api/v1/books`) |
|---|---|---|
| `LIBRARIAN` | Oui | Oui |
| `MEMBER` | Oui | Non (403) |
| Non authentifié (JWT/cookie absent, invalide ou expiré) | Non (401) | Non (401) |

Côté interface : le lien « Catalogue » et la page sont visibles de tout utilisateur connecté ; le bouton/formulaire d'enregistrement n'est affiché qu'avec le rôle `LIBRARIAN` (confort d'affichage ; le backend reste la référence, comme `authGuard`).

## Règles métier (numérotées RM-CAT-xx)
Les RM-01 à RM-07 (CLAUDE.md) et RM-CAT-01 à RM-CAT-12 (`catalog-enregistrement-livre.md`) restent en vigueur. Numérotation de cette spec à partir de RM-CAT-13.

**Consultation (backend)**
- **RM-CAT-13** : `GET /api/v1/books` est accessible à tout utilisateur authentifié (`LIBRARIAN` ou `MEMBER`), par cookie `ACCESS_TOKEN` ou `Authorization: Bearer`. Sans authentification valide : 401. Un jeton valide sans rôle `LIBRARIAN` ni `MEMBER` : 403 `ACCESS_DENIED` (`hasAnyRole`, décision de validation du plan). Pas de CSRF (requête non modifiante).
- **RM-CAT-14** : la liste ne contient que les livres avec `availableCopies >= 1` (RM-04, cohérent avec `list_available_books` et le cache). Un livre à 0 exemplaire disponible n'est jamais renvoyé ni compté dans le total (décision Q3).
- **RM-CAT-15** : pagination par numéro de page et taille. Paramètres : `page` (entier >= 0, défaut 0) et `size` (entier de 1 à 50, défaut 10). Valeur hors bornes ou non numérique : 400 `VALIDATION_FAILED` avec `errors[]` sur `page` ou `size` (pas de correction silencieuse). Décision Q4.
- **RM-CAT-16** : tri fixe, non paramétrable : titre sans tenir compte de la casse, puis ISBN (même ordre que `AvailableBooksSelection.CATALOG_ORDER` et le cache). Le tri est total et déterministe : deux pages successives ne se chevauchent pas et ne perdent aucun livre en l'absence d'écriture entre les deux appels.
- **RM-CAT-17** : recherche par le paramètre optionnel `q`. Le texte est rogné (trim) ; absent, vide ou blanc = pas de filtre. Un livre est retenu si `q` est une **sous-chaîne, insensible à la casse, du titre ou de l'auteur** (« contient », OU logique entre les deux champs). Les caractères `%`, `_` et `\` sont traités comme du texte littéral (jamais comme jokers). Longueur maximale de `q` : 100 caractères après trim, sinon 400 avec `errors[]` sur `q`. La recherche est **sensible aux accents** et ne porte pas sur l'ISBN (décision Q5, recherche sans accents reportée à l'issue #20).
- **RM-CAT-18** : le filtre `q` s'applique avant la pagination ; `totalElements` et `totalPages` portent sur le résultat filtré. Une recherche sans résultat renvoie 200 avec `content: []`, `totalElements: 0`, `totalPages: 0` (pas 404).
- **RM-CAT-19** : une page au-delà de la dernière renvoie 200 avec `content: []` et les totaux corrects (pas d'erreur).
- **RM-CAT-20** : contrat de réponse (200) : `{ "content": [ { "id", "isbn", "title", "author", "totalCopies", "availableCopies" } ], "page": <int>, "size": <int>, "totalElements": <long>, "totalPages": <int> }`. L'élément réutilise la forme de `BookResponse` (aucune entité JPA ni objet de domaine exposé). Enveloppe validée (décision Q6).
- **RM-CAT-21** : un paramètre de requête inconnu (ex. `sort`) est ignoré (aucune erreur) ; le tri n'est pas pilotable par le client.
- **RM-CAT-22** : la lecture est en lecture seule et n'écrit jamais en base ; elle ne modifie pas le cache hors remplissage normal (RM-CAT-24).
- **RM-CAT-23** : architecture hexagonale : nouveau port d'entrée (cas d'usage de consultation paginée avec requête `page`/`size`/`q`) et nouveau port de sortie de lecture ; la pagination et la recherche sont des paramètres du domaine/application (pas de type Spring `Pageable` dans le domaine ni l'application) ; le contrôleur et la validation des paramètres restent dans `adapter.in.web`. `ArchitectureTest` passe sans modification.
- **RM-CAT-24** : usage du cache Redis. Le cache existant (une clé, 20 premiers livres, sans total) n'est réutilisé **que** pour la tête de liste et seulement si toutes les conditions sont vraies : `q` absent et `page * size + size <= 20`. Dans tous les autres cas (recherche, pages profondes) la lecture va en base. Le total (`totalElements`) n'étant pas en cache, il est calculé en base (comptage) : le cache ne supprime donc pas toute lecture en base d'une requête sans recherche. Le résultat est strictement identique avec ou sans cache (contenu, ordre, totaux). Aucune nouvelle clé n'est créée et le format `v1` n'est pas modifié (décision Q1, option A). Redis indisponible : même résultat via la base, WARN existant (RC-11), la requête réussit.
- **RM-CAT-25** : cohérence avec l'enregistrement : après un `POST` réussi, le livre apparaît dans `GET /api/v1/books` à la lecture suivante (invalidation du cache après commit existante, RC-13), sous réserve du retard de cache déjà accepté (RC-16, jusqu'à un TTL en cas d'échec d'invalidation).
- **RM-CAT-42** : invalidation du cache des livres disponibles. **Toute opération qui modifie le nombre d'exemplaires disponibles d'un livre** (ou l'ensemble des livres disponibles) **invalide le cache** `library:catalog:available-books:v1`, après le commit de la transaction : aujourd'hui l'enregistrement d'un livre (déjà en place, RC-13) ; demain l'emprunt et le retour (module `loan`) et tout ajout ou retrait d'exemplaires. Une nouvelle opération de ce type n'est pas terminée tant qu'elle n'invalide pas le cache (décision Q1).
- **RM-CAT-26** : `GET /api/v1/books/{id}` n'est pas créé par cette spec : la `Location` du `POST` reste sans endpoint cible (H8 de la spec d'enregistrement).

**Interface : écran Catalogue**
- **RM-CAT-27** : route `/catalogue` (chargement paresseux), protégée par `authGuard`. Un utilisateur non connecté est redirigé vers `/login?returnUrl=/catalogue`. Le lien « Catalogue » (`routerLink="/catalogue"`, état actif visible) est ajouté dans l'en-tête, visible uniquement quand un utilisateur est connecté.
- **RM-CAT-28** : le tableau (`p-table`) affiche les colonnes Titre, Auteur, ISBN, Exemplaires disponibles. Pagination via `p-paginator`/pagination du tableau, pilotée côté serveur (`page`, `size`), avec choix de taille parmi 10, 20, 50 (défaut 10). Aucun tri côté client.
- **RM-CAT-29** : champ de recherche unique (libellé « Rechercher par titre ou auteur »). La recherche est déclenchée après une temporisation (anti-rebond, 300 ms proposés) ou à la validation ; chaque nouvelle recherche revient à la page 0. Les appels obsolètes sont annulés (seule la dernière réponse est affichée). Un bouton permet d'effacer la recherche.
- **RM-CAT-30** : états d'affichage : chargement (indicateur), liste vide sans recherche (« Aucun livre disponible pour le moment »), recherche sans résultat (« Aucun livre ne correspond à "<q>" »), erreur de chargement (toast via `errorInterceptor` + possibilité de réessayer). Le texte de recherche est affiché échappé (jamais en HTML brut).
- **RM-CAT-31** : l'état (page, taille, `q`) est reflété dans les paramètres d'URL (`?q=&page=&size=`), pour que le rechargement ou le retour arrière restitue la même vue. La valeur de `page` dans l'URL est **à base 1** (lisible) et convertie en base 0 pour l'API ; une valeur absente, non numérique ou < 1 vaut 1 (décision Q7).
- **RM-CAT-32** : le nombre d'exemplaires disponibles est affiché sous la forme « disponibles / total » (« 2 / 3 ») **seulement si le total est déjà renvoyé par l'API** (`totalCopies`, c'est le cas du contrat RM-CAT-20), sinon disponibles seuls (« 2 ») (décision Q8) ; les données affichées viennent uniquement de l'API.

**Interface : enregistrement d'un livre**
- **RM-CAT-33** : le bouton « Ajouter un livre » (ouvre une boîte de dialogue `p-dialog` sur l'écran Catalogue, décision Q9) n'est affiché que si `user().roles` contient `LIBRARIAN`. Pour un `MEMBER`, le bouton est simplement masqué, sans message (décision Q15) ; un utilisateur cumulant `LIBRARIAN` et `MEMBER` le voit. Les droits réels sont contrôlés par le backend (403).
- **RM-CAT-34** : champs : ISBN (texte), Titre, Auteur, Nombre d'exemplaires (nombre entier). Validation côté client **non bloquante pour le contrat** : obligatoire pour chaque champ, titre et auteur <= 255 caractères, exemplaires entier de 1 à 1000, ISBN 13 chiffres après retrait des espaces et tirets. Les règles du backend (RM-CAT-02 à 07) restent la référence ; le client ne les contredit jamais (il ne rejette pas un ISBN que le backend accepterait, ex. `978-2-07-036822-8`).
- **RM-CAT-35** : à la soumission, un seul envoi à la fois (bouton désactivé et indicateur pendant l'appel ; double clic = une requête). L'en-tête `X-XSRF-TOKEN` est géré par `HttpClient` (cookie `XSRF-TOKEN`).
- **RM-CAT-36** : succès (201) : toast de confirmation (« Livre enregistré »), fermeture du formulaire, le formulaire est réinitialisé, la liste est rechargée en **conservant la page courante** et la recherche (décision Q10).
- **RM-CAT-37** : erreur 400 `VALIDATION_FAILED` : chaque entrée `errors[]` est affichée **sous le champ correspondant** (`field` = `isbn` | `title` | `author` | `copies`, message du backend en français) et le champ est marqué invalide ; le formulaire reste ouvert avec les valeurs saisies. Une erreur dont le `field` ne correspond à aucun champ du formulaire (ou un 400 sans `errors[]`, ex. JSON malformé) est affichée en message général dans le formulaire. L'erreur de champ disparaît dès que l'utilisateur modifie le champ concerné.
- **RM-CAT-38** : erreur 409 `ISBN_ALREADY_EXISTS` : le backend ne renvoie pas `errors[]` pour ce cas ; le frontend l'affiche **sous le champ ISBN** à partir du `code` (message : le `detail` du backend ou un texte fixe « Cet ISBN existe déjà au catalogue »). Le formulaire reste ouvert, les valeurs conservées, aucun livre n'est ajouté à la liste.
- **RM-CAT-39** : erreur 403 : toast fixe de l'`errorInterceptor` (« Accès refusé ») ; le formulaire reste ouvert. Erreur 401 : gérée par `authInterceptor` (retour à la connexion) ; pas de toast de l'`errorInterceptor`, mais l'avertissement « Session expirée » existant de l'`authInterceptor` est conservé (décision de validation du plan). Erreurs 5xx / réseau : toast technique de l'`errorInterceptor`, formulaire conservé.
- **RM-CAT-40** : pas de doublon d'affichage. Les requêtes du formulaire désactivent le toast générique de l'`errorInterceptor` pour les 400 et 409 (erreurs affichées sur les champs ou en message général du formulaire), au moyen d'une **option réutilisable de `core/http`** (jeton `HttpContext`, par exemple `SKIP_ERROR_TOAST`, lu par l'`errorInterceptor`). Les 403, 5xx et erreurs réseau gardent leur toast (décision Q11).
- **RM-CAT-41** : accessibilité : champs avec libellés associés, erreurs reliées au champ (`aria-describedby`), focus placé sur le premier champ en erreur, tableau avec légende ou libellé accessible, navigation au clavier.

## Critères d'acceptation (Gherkin : Étant donné / Quand / Alors)

### API : consultation
```gherkin
Scénario: Liste nominale, première page
  Étant donné 25 livres avec au moins un exemplaire disponible
  Et un MEMBER authentifié
  Quand il envoie GET /api/v1/books
  Alors la réponse a le statut 200
  Et content contient 10 livres dans l'ordre titre (sans casse) puis ISBN
  Et page vaut 0, size vaut 10, totalElements vaut 25, totalPages vaut 3
  Et chaque élément contient id, isbn, title, author, totalCopies et availableCopies

Scénario: Page intermédiaire et dernière page
  Étant donné 25 livres disponibles
  Quand un utilisateur authentifié envoie GET /api/v1/books?page=2&size=10
  Alors content contient 5 livres
  Et ce sont les livres de rang 21 à 25 de l'ordre du catalogue

Scénario: Page au-delà de la dernière
  Étant donné 25 livres disponibles
  Quand un utilisateur envoie GET /api/v1/books?page=9&size=10
  Alors la réponse a le statut 200, content est vide et totalElements vaut 25

Scénario: Livre sans exemplaire disponible exclu
  Étant donné un livre dont availableCopies vaut 0 et 2 livres disponibles
  Quand un utilisateur envoie GET /api/v1/books
  Alors totalElements vaut 2 et le livre épuisé n'apparaît pas

Scénario: Aucun livre disponible
  Étant donné aucun livre disponible
  Quand un utilisateur envoie GET /api/v1/books
  Alors la réponse a le statut 200, content est vide, totalElements et totalPages valent 0

Plan du Scénario: Recherche
  Étant donné les livres "L'Étranger" (Albert Camus), "La Peste" (Albert Camus), "Germinal" (Émile Zola)
  Quand un utilisateur envoie GET /api/v1/books?q=<q>
  Alors content contient les titres <titres> et totalElements vaut <total>
  Exemples:
    | q         | titres                  | total |
    | camus     | L'Étranger, La Peste    | 2     |
    | CAMUS     | L'Étranger, La Peste    | 2     |
    | peste     | La Peste                | 1     |
    | germ      | Germinal                | 1     |
    | ZOLA      | Germinal                | 1     |
    | introuvab | (aucun)                 | 0     |
    | (vide)    | les 3 livres            | 3     |
    | "   "     | les 3 livres            | 3     |

Scénario: Caractères spéciaux littéraux dans la recherche
  Étant donné les livres "100% Python" et "Le Python"
  Quand un utilisateur envoie GET /api/v1/books?q=100%25
  Alors seul "100% Python" est renvoyé
  Et GET /api/v1/books?q=%25 ne renvoie que les livres dont le titre ou l'auteur contient "%"

Scénario: Recherche et pagination combinées
  Étant donné 15 livres de l'auteur "Hugo" parmi 30 livres
  Quand un utilisateur envoie GET /api/v1/books?q=hugo&size=10&page=1
  Alors content contient 5 livres et totalElements vaut 15

Plan du Scénario: Paramètres de pagination invalides
  Quand un utilisateur authentifié envoie GET /api/v1/books?<param>
  Alors la réponse a le statut 400 et le code "VALIDATION_FAILED"
  Et errors[] contient une entrée pour le champ "<champ>"
  Exemples:
    | param         | champ |
    | page=-1       | page  |
    | page=abc      | page  |
    | size=0        | size  |
    | size=51       | size  |
    | size=abc      | size  |

Scénario: Recherche trop longue
  Quand un utilisateur envoie GET /api/v1/books avec q de 101 caractères
  Alors la réponse a le statut 400 avec errors[].field = "q"

Scénario: Tri paramétré ignoré
  Quand un utilisateur envoie GET /api/v1/books?sort=author,desc
  Alors la réponse a le statut 200 et l'ordre reste titre puis ISBN

Scénario: Non authentifié
  Quand une requête sans cookie ni Authorization envoie GET /api/v1/books
  Alors la réponse a le statut 401 au format ProblemDetail (code "UNAUTHENTICATED")

Scénario: Jeton invalide ou expiré
  Quand un JWT expiré ou mal signé est utilisé sur GET /api/v1/books
  Alors la réponse a le statut 401

Scénario: Authentification par cookie sans CSRF
  Étant donné un utilisateur connecté par cookie ACCESS_TOKEN
  Quand il envoie GET /api/v1/books sans en-tête X-XSRF-TOKEN
  Alors la réponse a le statut 200

Scénario: Les deux rôles peuvent lire
  Quand un LIBRARIAN puis un MEMBER envoient GET /api/v1/books
  Alors les deux réponses ont le statut 200 et le même contenu
```

### API : cache
```gherkin
Scénario: Tête de liste servie par le cache
  Étant donné 30 livres disponibles et la liste des 20 premiers déjà en cache
  Quand un utilisateur envoie GET /api/v1/books?page=0&size=10
  Alors content est identique à celui obtenu sans cache
  Et la liste n'est pas relue en base (seul le total l'est)

Scénario: La recherche ne passe pas par le cache
  Étant donné la liste en cache
  Quand un utilisateur envoie GET /api/v1/books?q=camus
  Alors le résultat est lu en base et le cache n'est pas modifié

Scénario: Page profonde hors cache
  Étant donné 30 livres disponibles
  Quand un utilisateur envoie GET /api/v1/books?page=2&size=10
  Alors le résultat est lu en base et est correct

Scénario: Redis indisponible
  Étant donné Redis arrêté
  Quand un utilisateur envoie GET /api/v1/books
  Alors la réponse est 200 avec le même contenu qu'avec Redis et un WARN est journalisé

Scénario: Un livre enregistré apparaît dans la liste
  Étant donné la liste en cache sans le livre d'ISBN "9782070368228"
  Quand un LIBRARIAN enregistre ce livre (201) puis un utilisateur envoie GET /api/v1/books?q=<titre du livre>
  Alors le livre est présent dans la réponse (la recherche ne porte pas sur l'ISBN, décision Q5)
```

### API : enregistrement (non-régression, déjà livré)
```gherkin
Scénario: Les contrats de POST /api/v1/books sont inchangés
  Quand les tests de catalog-enregistrement-livre.md sont exécutés
  Alors ils passent sans modification (201, 400 avec errors[], 409 ISBN_ALREADY_EXISTS, 401, 403)
```

### Interface : liste
```gherkin
Scénario: Accès au catalogue depuis l'en-tête
  Étant donné un utilisateur connecté
  Quand il clique sur « Catalogue » dans l'en-tête
  Alors l'URL est /catalogue et le tableau affiche Titre, Auteur, ISBN, Exemplaires disponibles

Scénario: Lien absent hors connexion
  Étant donné un visiteur non connecté
  Alors l'en-tête n'affiche pas le lien « Catalogue »
  Et l'ouverture de /catalogue redirige vers /login?returnUrl=/catalogue

Scénario: Première page
  Étant donné 25 livres disponibles
  Quand la page Catalogue s'ouvre
  Alors 10 lignes sont affichées et le paginateur indique 3 pages
  Et l'appel est GET /api/v1/books?page=0&size=10 (via le proxy /api, sans URL en dur)

Scénario: Changement de page
  Quand l'utilisateur va à la page 2
  Alors l'appel envoie page=1 et les lignes affichées sont celles de la page 2

Scénario: Recherche
  Quand l'utilisateur saisit « camus »
  Alors, après la temporisation, un seul appel envoie q=camus&page=0
  Et seuls les livres correspondants sont affichés
  Et le paginateur revient à la première page

Scénario: Saisie rapide, réponses obsolètes ignorées
  Quand l'utilisateur tape « c », « ca », « cam » en moins de la temporisation
  Alors un seul appel est émis avec q=cam

Scénario: Recherche sans résultat
  Quand la recherche ne retourne aucun livre
  Alors le message « Aucun livre ne correspond à "<q>" » s'affiche

Scénario: Catalogue vide
  Étant donné aucun livre disponible
  Alors le message « Aucun livre disponible pour le moment » s'affiche, sans paginateur

Scénario: Erreur serveur au chargement
  Étant donné l'API qui répond 500
  Alors un toast d'erreur technique s'affiche et un bouton « Réessayer » recharge la liste

Scénario: URL partageable
  Étant donné l'URL /catalogue?q=camus&size=20
  Quand la page s'ouvre
  Alors le champ de recherche contient « camus » et la requête envoie q=camus&size=20

Scénario: Bouton d'ajout selon le rôle
  Étant donné un MEMBER connecté
  Alors le bouton « Ajouter un livre » n'est pas affiché
  Et pour un LIBRARIAN connecté il est affiché
```

### Interface : formulaire d'enregistrement
```gherkin
Scénario: Enregistrement réussi
  Étant donné un LIBRARIAN sur le formulaire
  Quand il saisit ISBN "978-2-07-036822-8", titre "L'Étranger", auteur "Albert Camus", exemplaires 3 et valide
  Alors POST /api/v1/books est envoyé avec isbn, title, author et copies
  Et un toast « Livre enregistré » s'affiche, le formulaire se ferme
  Et la liste est rechargée et contient le livre

Scénario: Champs obligatoires vides
  Quand le LIBRARIAN valide un formulaire vide
  Alors les quatre champs sont marqués en erreur côté client sans appel réseau (ou, à défaut, les errors[] du 400 sont affichés sur les quatre champs)

Scénario: Erreurs de validation du backend sur les champs
  Étant donné l'API qui répond 400 avec errors[] pour "isbn" (INVALID_FORMAT) et "copies" (OUT_OF_RANGE)
  Quand le LIBRARIAN valide
  Alors le message de l'erreur "isbn" est affiché sous le champ ISBN
  Et le message de l'erreur "copies" est affiché sous le champ Exemplaires
  Et le formulaire reste ouvert avec les valeurs saisies

Scénario: Erreur de champ effacée à la modification
  Étant donné une erreur affichée sous le champ ISBN
  Quand le LIBRARIAN modifie ce champ
  Alors l'erreur de ce champ disparaît

Scénario: ISBN déjà existant
  Étant donné l'API qui répond 409 avec le code "ISBN_ALREADY_EXISTS" et aucun errors[]
  Quand le LIBRARIAN valide
  Alors l'erreur s'affiche sous le champ ISBN et le formulaire reste ouvert

Scénario: Accès refusé
  Étant donné l'API qui répond 403
  Quand l'utilisateur valide
  Alors le toast « Accès refusé » s'affiche et le formulaire reste ouvert

Scénario: Session expirée pendant la saisie
  Étant donné l'API qui répond 401
  Alors l'utilisateur est renvoyé vers la connexion (authInterceptor), sans toast d'erreur

Scénario: Double soumission
  Quand le LIBRARIAN double-clique sur « Enregistrer »
  Alors une seule requête POST est émise et le bouton est désactivé pendant l'appel

Scénario: MEMBER sur l'URL du formulaire
  Étant donné un MEMBER connecté
  Quand il ouvre /catalogue/nouveau (si la variante page est retenue)
  Alors il est redirigé vers /catalogue

Scénario: Erreur d'un champ inconnu
  Étant donné l'API qui répond 400 avec une erreur sur un champ inconnu du formulaire
  Alors un message général est affiché dans le formulaire
```

## Données (entités, champs, contraintes)

### Entité existante `Book` (inchangée, table `book`)
`id` (UUID), `isbn` (13 chiffres, unique), `title` (<= 255), `author` (<= 255), `totalCopies` (1 à 1000), `availableCopies` (0 à `totalCopies`). Détail dans `catalog-enregistrement-livre.md`. Aucune nouvelle colonne.

### Migration éventuelle
Aucune obligatoire. Si la recherche « contient » sur `lower(title)` / `lower(author)` devient lente avec le volume, un index `pg_trgm` (GIN) peut être ajouté : décision de l'architecte selon la volumétrie (Q13). Un index partiel sur `available_copies >= 1` est également possible.

### Requête `GET /api/v1/books`
| Paramètre | Type | Défaut | Contraintes |
|---|---|---|---|
| `page` | entier | 0 | >= 0 |
| `size` | entier | 10 | 1 à 50 |
| `q` | texte | absent | rogné, <= 100 caractères ; vide = pas de filtre |

### Réponse 200 (DTO de sortie, aucune entité exposée)
`content[]` : `{ id, isbn, title, author, totalCopies, availableCopies }` ; `page`, `size`, `totalElements`, `totalPages`.

### Erreurs (`ProblemDetail`, RFC 9457)
| Statut | Code | Cas |
|---|---|---|
| 400 | `VALIDATION_FAILED` + `errors[] { field, code, message }` | `page`, `size`, `q` invalides (GET) ; `isbn`, `title`, `author`, `copies` invalides (POST) |
| 401 | `UNAUTHENTICATED` | pas de jeton, jeton invalide ou expiré (GET et POST) |
| 403 | `ACCESS_DENIED` | `MEMBER` sur POST |
| 409 | `ISBN_ALREADY_EXISTS` (sans `errors[]`) | POST d'un ISBN existant |

### Frontend (indicatif)
Modèle `Book { id, isbn, title, author, totalCopies, availableCopies }`, `Page<T>`, service de catalogue (`core` ou `features/catalog`) utilisant `HttpClient` avec chemins relatifs `/api/v1/books`, `ProblemDetail` et `FieldError` existants. Aucun état d'authentification ni jeton dans le code du catalogue.

## Hors périmètre
- Modification, suppression d'un livre ; ajout ou retrait d'exemplaires d'un livre existant ; `GET /api/v1/books/{id}` et page de détail.
- Emprunt, retour, prolongation (module `loan` inexistant) ; bouton « Emprunter ».
- Affichage des livres sans exemplaire disponible et filtre « disponibles / tous » ; **vue du catalogue complet pour le bibliothécaire** (plus tard, décision Q3).
- Tri paramétrable par colonne, filtres autres que `q` (auteur seul, ISBN seul, éditeur, catégorie).
- Recherche floue ou insensible aux fautes de frappe, classement par pertinence, moteur de recherche externe.
- Nouveau cache dédié par page ou par recherche, pré-chauffage, métriques de cache.
- Modification du contrat MCP (`list_available_books`, `get_book`).
- Import en masse, export, couvertures, métadonnées externes.
- Internationalisation (interface en français uniquement).
- Recherche insensible aux accents et index `pg_trgm` (issue #20) ; tri linguistique français (issue #21).

## Décisions (validées le 2026-11-23)

1. **Cache (Q1, Q2) : option A.** Le cache `v1` sert uniquement la tête de liste sans recherche (`q` absent et `page * size + size <= 20`) ; le total est calculé en base ; aucun changement de clé ni de format (RM-CAT-24). Q2 sans objet. **Règle ajoutée RM-CAT-42** : toute opération qui modifie les exemplaires disponibles (enregistrement aujourd'hui, emprunt et retour demain dans `loan`) invalide le cache après commit.
2. **Livres à 0 exemplaire disponible (Q3) : masqués** (RM-CAT-14). Hors périmètre : vue du catalogue complet pour le bibliothécaire (plus tard).
3. **Taille de page (Q4)** : défaut 10, maximum 50, choix 10 / 20 / 50.
4. **Recherche (Q5, Q13)** : insensible à la casse, **sensible aux accents**, sur titre et auteur, **sans l'ISBN**, **sans index** ni migration. Recherche sans accents et index `pg_trgm` (extension `unaccent`, migration, droits) : issue #20.
5. **Enveloppe paginée (Q6)** : `{ content, page, size, totalElements, totalPages }` validée.
6. **`?page=` de l'écran (Q7)** : base 1, converti en base 0 pour l'API.
7. **Colonne exemplaires (Q8)** : « 2 / 3 » seulement si le total est déjà renvoyé par l'API, sinon « 2 ».
8. **Formulaire (Q9)** : boîte de dialogue `p-dialog` sur l'écran Catalogue.
9. **Après enregistrement (Q10)** : page courante et recherche conservées, liste rechargée, toast de confirmation.
10. **Doublon de toast (Q11)** : le toast générique est désactivé pour les 400/409 du formulaire via une option `HttpContext` réutilisable dans `core/http` (RM-CAT-40).
11. **Mode `mock` (Q12)** : le catalogue y est simulé (liste, recherche, pagination, enregistrement et ses erreurs), conformément au contrat.
12. **Tri (Q14)** : `COLLATE "C"` conservé pour l'instant (cohérence avec MCP et le cache). Tri français (cache, MCP et requête ensemble) : issue #21.
13. **Bouton d'ajout pour `MEMBER` (Q15)** : simplement masqué ; visible pour un utilisateur cumulant `LIBRARIAN` et `MEMBER`.

## Questions ouvertes (toutes tranchées, voir « Décisions »)

1. **Compatibilité du cache Redis avec la pagination et la recherche (bloquante pour l'architecte).** Le cache actuel est une clé unique `library:catalog:available-books:v1` : les 20 premiers livres, triés, sans total, sans filtre. Il ne peut pas servir une page quelconque, une recherche, ni le `totalElements`. Options : (A, recommandée) l'utiliser uniquement pour la tête de liste sans recherche (`page * size + size <= 20`) et lire le total en base, comme décrit en RM-CAT-24 ; gain réel faible (le total reste en base) ; (B) étendre le cache : valeur `{ total, books[20] }` sous un nouveau suffixe `v2` (RC-10 : changement de format = nouveau suffixe), ce qui permet de servir aussi le total de la page 0 sans recherche ; modifie le codec et les tests du cache ; (C) ne pas utiliser le cache pour `GET /api/v1/books` (base seule) et le laisser à MCP. Quelle option retenir ? Le besoin brut dit « réutilise le cache » : confirmer que A ou B est bien voulu malgré le faible gain.
2. **Invalidation et fraîcheur du total.** Si l'option B est retenue, le total en cache est périmé tant que la clé n'est pas invalidée (retard jusqu'à 10 min en cas d'échec d'invalidation, RC-16). Acceptable pour un paginateur ? Sinon, pas de total en cache (option A).
3. **Livres à 0 exemplaire disponible.** La spec propose de ne pas les afficher (« livres disponibles », RM-04, cohérence avec MCP et le cache). Alternative : tout le catalogue avec une pastille « indisponible » (plus utile au membre qui veut savoir si un livre existe, mais incompatible avec le cache tel quel, qui n'a que des livres disponibles). À trancher. Si tout le catalogue est affiché, l'écran « Catalogue » doit-il proposer un filtre « disponibles seulement » ?
4. **Taille de page.** Proposé : défaut 10, maximum 50, choix UI 10 / 20 / 50. Valeurs à confirmer. (Le cache ne couvre que 20 livres : un défaut de 20 maximiserait l'usage du cache.)
5. **Recherche : accents et ISBN.** La recherche est insensible à la casse. Doit-elle aussi ignorer les accents (« etranger » trouve « L'Étranger ») ? Cela demande l'extension PostgreSQL `unaccent` (migration) ; sinon recherche sensible aux accents. La recherche doit-elle aussi porter sur l'ISBN (le besoin dit « titre ou auteur » seulement) ? La spec exclut l'ISBN.
6. **Forme de la réponse paginée.** Enveloppe `{ content, page, size, totalElements, totalPages }` proposée (indépendante de Spring `Page`/`Pageable`, dont la sérialisation directe est déconseillée). Valider ces noms ou imposer une autre forme (ex. `items`, `total`).
7. **Numérotation de page.** L'API utilise `page` à partir de 0 (convention Spring). L'URL de l'écran (`?page=`) doit-elle être à base 0 (identique à l'API) ou à base 1 (lisible) ?
8. **Colonne « Exemplaires disponibles ».** Afficher « 2 » ou « 2 / 3 » (disponibles sur total) ? Le besoin dit « exemplaires disponibles » seulement.
9. **Formulaire : dialogue ou page ?** `p-dialog` ouvert depuis le catalogue (proposé, conserve le contexte de la liste) ou page dédiée `/catalogue/nouveau` ?
10. **Après enregistrement.** Recharger la page courante (le nouveau livre peut ne pas y apparaître vu le tri par titre) ou revenir à la page 0 avec la recherche vidée, ou rechercher l'ISBN créé pour montrer le livre ? Proposé : page courante conservée + toast de confirmation.
11. **Doublon d'affichage des erreurs.** Pour un 400/409 sur le formulaire, l'`errorInterceptor` affiche déjà un toast générique (titre + détail). Le garder en plus des messages sous les champs, ou le supprimer pour les requêtes du formulaire (ex. contexte de requête `HttpContext` qui désactive le toast, à ajouter à l'intercepteur) ? Cela modifie un composant du socle (`core/http`).
12. **Mode `mock` du frontend.** Le mode `ng serve --configuration mock` ne simule que l'authentification. Faut-il y ajouter des livres simulés (liste, recherche, pagination, enregistrement) ? Hors périmètre par défaut.
13. **Volumétrie attendue.** Nombre de livres visé (centaines, dizaines de milliers) ? Détermine l'index de recherche (`pg_trgm`) et l'intérêt du cache. Sans information, aucune migration d'index n'est prévue.
14. **Cohérence du `COLLATE "C"` pour le tri.** L'ordre actuel (minuscules via `lower()` puis `COLLATE "C"`) place les titres accentués (« Émile ») après « z ». Conserver cet ordre par cohérence avec MCP et le cache, ou adopter un tri linguistique français pour l'écran (et alors renoncer au cache de tête de liste pour garder l'identité de résultat) ?
15. **Visibilité du formulaire côté `MEMBER`.** Masquer simplement le bouton (proposé), ou aussi afficher un message d'information ? Et le rôle `LIBRARIAN` + `MEMBER` cumulés : le bouton est affiché (cohérent avec RM-CAT-01).
