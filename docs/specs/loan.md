# Emprunter et rendre un livre (module loan)

> **Statut : spécification validée par l'utilisateur le 2026-11-24.**

## Contexte et objectif
Un membre (`MEMBER`) doit pouvoir emprunter un livre disponible du catalogue, consulter ses emprunts en cours et rendre un livre. Les règles de référence sont RM-01 à RM-05 du CLAUDE.md. La prolongation (RM-06) est hors périmètre.

Périmètre : backend (nouveau module `loan` + extension de `catalog` + outillage local) et frontend Angular 21 / PrimeNG 21 branché sur la vraie API.

### Constat sur l'existant
**Backend**
- Le module `loan` n'existe pas. Aucune table d'emprunt (migrations V1 `book`, V2 `app_user` / `user_roles`). Aucun code ne lit encore `jwt.getSubject()`.
- `catalog` : `Book` (domaine) porte `totalCopies` et `availableCopies` mais **aucune opération pour diminuer ou augmenter `availableCopies`** ; `isAvailable()` implémente RM-04. Le port de sortie d'écriture `SaveBookPort` et le marqueur `@AffectsAvailableBooks` existent : toute écriture qui change la disponibilité doit passer par un port ainsi annoté, implémenté par le décorateur de cache (invalidation après commit de `library:catalog:available-books:v1`, RM-CAT-42, vérifié par `CacheArchitectureTest`).
- La table `book` (V1) porte **déjà** `CONSTRAINT ck_book_available_copies_range CHECK (available_copies BETWEEN 0 AND total_copies)` et une colonne `version` (verrouillage optimiste). Aucune migration n'est donc nécessaire pour garantir le stock en base.
- `identity` : utilisateur = UUID, rôles `LIBRARIAN` / `MEMBER` cumulables ; amorçage du premier bibliothécaire au démarrage (`LibrarianBootstrapRunner`, variables `BOOTSTRAP_LIBRARIAN_EMAIL` / `_PASSWORD`, sans valeur par défaut, idempotent, WARN si absentes). Le membre connecté est le `sub` du JWT (UUID pour les jetons de `/api/v1/auth/login`).
- `DevTokenController` (`@Profile("local")`, `POST /dev/token`) : `subject` et `roles` libres, `subject` = UUID aléatoire et `roles` = `["MEMBER"]` par défaut, jeton dans le corps (pas de cookie). Aucun membre (`MEMBER`) n'existe en base en local : seul le bibliothécaire d'amorçage est créé.
- `ClockConfig` : `Clock.systemDefaultZone()` (fuseau du serveur). Aucune propriété de fuseau n'existe.
- Architecture hexagonale : un module n'appelle un autre que par `<module>.api` (interfaces et types simples, JDK uniquement), via un adapter sortant (`loan.adapter.out.catalog`) et une façade `catalog.adapter.in.module`. Pas de clé étrangère SQL ni jointure entre modules : `loan` stocke `book_id` et `member_id` sans FK.
- Le contrat du catalogue (`docs/api/catalogue.md`) et le cache n'exposent que les livres avec `availableCopies >= 1` ; `GET /api/v1/books` renvoie `id` (UUID du livre) utilisable comme `bookId` d'emprunt.

**Frontend**
- Routes `login` et `''` protégées par `authGuard`, écran Catalogue (`/catalogue`, spec `catalogue.md`) ; la spec Catalogue excluait explicitement le bouton « Emprunter » (hors périmètre), ajouté ici. `AuthService.user` expose `userId`, `email`, `roles`. `errorInterceptor` : toast pour toute erreur hors 401, erreur toujours propagée, option `SKIP_ERROR_TOAST` (HttpContext) disponible dans `core/http`.

## Acteurs et rôles (qui a le droit de faire quoi)
| Acteur | Emprunter | Voir ses emprunts en cours | Rendre un emprunt |
|---|---|---|---|
| `MEMBER` | Oui (pour lui-même uniquement) | Oui (les siens uniquement) | Oui (les siens uniquement) |
| `LIBRARIAN` seul | Non (403) | Non (403) | Non (403) |
| `LIBRARIAN` + `MEMBER` | Oui, comme membre, pour lui-même | Oui, les siens | Oui, les siens |
| Non authentifié | 401 | 401 | 401 |

Le membre est toujours déduit du `sub` du JWT : aucune requête ne transmet un identifiant de membre. Un membre ne peut ni voir, ni rendre l'emprunt d'un autre. Un retour saisi par un bibliothécaire pour un membre est hors périmètre.
Côté interface, l'affichage conditionnel par rôle est un confort ; le backend fait foi.

## Règles métier (numérotées RM-LOAN-xx)
Les RM-01 à RM-05 du CLAUDE.md sont les règles de référence ; elles sont déclinées ci-dessous en règles testables. RM-06 (prolongation) et RM-07 ne sont pas impactées.

**Emprunter**
- **RM-LOAN-01** (RM-04) : on ne peut emprunter un livre que si `availableCopies >= 1`. Sinon 409 `BOOK_NOT_AVAILABLE`, aucun emprunt créé, aucun compteur modifié.
- **RM-LOAN-02** (RM-01) : un membre a au maximum 3 emprunts actifs (non rendus). Tenter un 4e : 409 `LOAN_LIMIT_REACHED`. Les emprunts rendus ne comptent pas, y compris ceux rendus le jour même.
- **RM-LOAN-03** (RM-02) : un membre qui a au moins un emprunt actif en retard ne peut pas emprunter : 409 `MEMBER_HAS_OVERDUE_LOAN`.
- **RM-LOAN-04** (RM-03) : un emprunt créé le jour J a pour échéance `dueOn = J + 14 jours`, où J est la date du jour dans le fuseau de la bibliothèque (RM-LOAN-29). La date vient d'un `Clock` injecté (jamais `LocalDate.now()` sans argument).
- **RM-LOAN-05** : un emprunt est **en retard** quand il est actif et que la date du jour (fuseau de la bibliothèque, RM-LOAN-29) est strictement postérieure à `dueOn` (le jour de l'échéance inclus, l'emprunt n'est pas en retard).
- **RM-LOAN-28** : un membre ne peut pas avoir deux emprunts actifs du même livre : tenter d'emprunter un livre qu'il a déjà en cours renvoie 409 `BOOK_ALREADY_BORROWED`, sans emprunt créé ni compteur modifié. Après le retour de l'emprunt, il peut emprunter de nouveau ce livre. Deux membres différents peuvent emprunter le même livre (s'il reste des exemplaires). La règle est garantie en base par un index unique partiel (voir Données), de sorte que deux requêtes simultanées du même membre pour le même livre ne créent qu'un seul emprunt.
- **RM-LOAN-06** (ordre d'évaluation) : quand plusieurs règles sont violées, **la première de la liste suivante détermine le code renvoyé** :
  1. livre inconnu : 404 `BOOK_NOT_FOUND` ;
  2. emprunt en retard : 409 `MEMBER_HAS_OVERDUE_LOAN` (RM-02) ;
  3. limite atteinte : 409 `LOAN_LIMIT_REACHED` (RM-01) ;
  4. livre déjà emprunté par ce membre : 409 `BOOK_ALREADY_BORROWED` ;
  5. plus d'exemplaire disponible : 409 `BOOK_NOT_AVAILABLE` (RM-04).
  Les règles qui portent sur l'état du membre (2, 3) passent avant celles qui portent sur le livre (4, 5), et la règle sur le membre et ce livre (4) avant la disponibilité du stock (5), afin de ne rien réserver inutilement. La réservation de l'exemplaire (qui modifie le stock) n'a lieu qu'après les règles 2 à 4.
- **RM-LOAN-07** : un emprunt réussi diminue de 1 `availableCopies` du livre et crée l'emprunt **dans la même transaction** : jamais l'un sans l'autre. Si l'un échoue, l'autre est annulé.
- **RM-LOAN-08** (concurrence) : deux emprunts simultanés du dernier exemplaire ne peuvent pas réussir tous les deux (un seul 201, l'autre 409 `BOOK_NOT_AVAILABLE`) ; `availableCopies` ne devient jamais négatif. Deux emprunts simultanés du même membre ne peuvent pas dépasser 3 emprunts actifs. Le contrôle de limite par membre est sérialisé (verrou par membre ou contrainte équivalente) : **le mécanisme est laissé à l'architecte**, sans nouvelle technologie.
- **RM-LOAN-31** (stock, double garantie) : le stock ne passe jamais sous zéro. Il est garanti **à la fois** (a) par une décrémentation conditionnelle et atomique côté catalogue (« décrémenter si `availableCopies >= 1` », compatible avec la colonne `version`) et (b) par la contrainte de base existante `ck_book_available_copies_range` (`available_copies BETWEEN 0 AND total_copies`), **conservée** et vérifiée par un test d'intégration : une écriture directe de `available_copies = -1` ou `> total_copies` est rejetée par PostgreSQL. Aucune migration pour ce point.
- **RM-LOAN-09** : la création d'un emprunt ne modifie ni `totalCopies`, ni les autres livres.

**Rendre**
- **RM-LOAN-10** (RM-05) : rendre un emprunt actif renseigne `returnedOn` = date du jour (fuseau de la bibliothèque, via `Clock`), augmente de 1 `availableCopies` du livre (jamais au-delà de `totalCopies`) dans la même transaction, et le livre redevient empruntable.
- **RM-LOAN-11** (RM-05) : on ne rend pas deux fois le même emprunt : un emprunt déjà rendu renvoie 409 `LOAN_ALREADY_RETURNED`, sans second incrément du compteur (y compris en cas de deux requêtes simultanées : un seul succès).
- **RM-LOAN-12** : un emprunt en retard peut être rendu normalement (aucune pénalité dans cette spec). Rendre le dernier emprunt en retard lève le blocage de RM-LOAN-03 immédiatement.
- **RM-LOAN-13** : rendre l'emprunt d'un autre membre, ou un identifiant inconnu : 404 `LOAN_NOT_FOUND` (même réponse, on ne révèle pas l'existence d'un emprunt d'autrui).

**Consulter**
- **RM-LOAN-14** : la liste « mes emprunts en cours » ne renvoie que les emprunts actifs du membre connecté (au plus 3), triés par échéance croissante puis identifiant. Chaque élément contient les informations du livre (titre, auteur, ISBN) et un indicateur `overdue` calculé à la date du jour (RM-LOAN-05). Pas de pagination (3 éléments maximum, RM-01). Liste vide : 200 avec `[]`.
- **RM-LOAN-32** (lecture groupée, sans N+1) : les informations des livres affichés ne sont **pas copiées** dans `loan` (la table ne stocke que `book_id`) ; elles sont lues dans `catalog` **en une seule requête pour tous les emprunts affichés**, via une recherche par lot d'identifiants exposée par `catalog.api`. `GET /api/v1/loans` provoque donc exactement un appel à `catalog.api` et, côté catalogue, une seule requête SQL (`WHERE id IN (...)`), quel que soit le nombre d'emprunts (0 à 3) ; aucun appel n'est fait quand le membre n'a aucun emprunt. Un livre introuvable dans le lot (supprimé) : l'emprunt est quand même listé, avec `isbn`, `title`, `author` nuls, sans erreur.
- **RM-LOAN-15** : l'historique (emprunts rendus) n'est pas exposé (hors périmètre), mais les emprunts rendus restent conservés en base (aucune suppression).

**Transverse**
- **RM-LOAN-16** (cache, RM-CAT-42) : l'emprunt et le retour modifient `availableCopies` ; le port de sortie de `catalog` qui les réalise porte `@AffectsAvailableBooks` et est décoré par le cache : la clé `library:catalog:available-books:v1` est invalidée après le commit. **L'invalidation relève de `catalog`** ; `loan` n'appelle que `catalog.api` et ne connaît ni Redis ni la clé. Un livre emprunté jusqu'au dernier exemplaire disparaît de `GET /api/v1/books` à la lecture suivante (sous réserve du retard de cache déjà accepté, RC-16) ; un livre rendu y réapparaît. **Aucun cache et aucun outil MCP pour `loan`** : les emprunts sont lus en base (au plus 3 lignes par membre).
- **RM-LOAN-17** (architecture) : `loan` appelle `catalog` uniquement par `catalog.api` via un adapter sortant `loan.adapter.out.catalog`. `catalog.api` expose, en types simples (`UUID`, `String`, `int`, records) : réserver un exemplaire, libérer un exemplaire, et **rechercher des livres par lot d'identifiants** (`Collection<UUID>` → liste de résumés `id`, `isbn`, `title`, `author`). La façade `catalog.adapter.in.module` implémente ce contrat en appelant les ports d'entrée de `catalog`, qui s'appuient sur un port de sortie de lecture par lot. Les règles (RM-01 à RM-03, RM-05, RM-LOAN-28) vivent dans le domaine de `loan` (`Loan`, `LoanPolicy`), la règle RM-04 et les bornes `0 <= availableCopies <= totalCopies` dans le domaine de `catalog` (`Book`). `ArchitectureTest` et `CacheArchitectureTest` passent sans modification.
- **RM-LOAN-18** (sécurité) : `POST /api/v1/loans`, `GET /api/v1/loans`, `POST /api/v1/loans/{id}/return` exigent le rôle `MEMBER` (`@PreAuthorize` et règle dans `SecurityConfig`). Cookie `ACCESS_TOKEN` ou `Authorization: Bearer` ; les requêtes modifiantes par cookie exigent le CSRF (`X-XSRF-TOKEN`). **Un `sub` qui n'est pas un UUID valide : 401 `UNAUTHENTICATED`** (jeton inexploitable), pour les trois endpoints, sans effet en base.
- **RM-LOAN-19** : validation de `POST /api/v1/loans` : `bookId` obligatoire et UUID valide, sinon 400 `VALIDATION_FAILED` avec `errors[] { field: "bookId", code: REQUIRED | INVALID_FORMAT, message }` (même convention que le catalogue). JSON illisible : 400 sans `errors[]`. `{id}` non UUID dans l'URL : 404 `LOAN_NOT_FOUND`.
- **RM-LOAN-20** : journalisation INFO de l'emprunt et du retour avec identifiants d'emprunt, de membre et de livre (jamais d'e-mail) ; WARN pour les refus métier sans donnée personnelle.
- **RM-LOAN-29** (fuseau de la bibliothèque) : « la date du jour » (échéance, retard, date de retour) se calcule dans le fuseau explicite de la bibliothèque, **et non dans celui du serveur**. Le fuseau est lu dans la propriété `app.timezone`, identifiant IANA accepté par `ZoneId.of`, après trim. Le `Clock` de l'application est construit sur ce fuseau (`ClockConfig`, `Clock.system(zone)`) ; les tests utilisent un `Clock.fixed` avec le même fuseau. Le fuseau **n'est pas un secret** : il a une valeur par défaut commitée dans `application.yml`, `app.timezone: ${APP_TIMEZONE:Africa/Dakar}`, modifiable par la variable d'environnement `APP_TIMEZONE`. Comportement :
  - variable `APP_TIMEZONE` absente : `Africa/Dakar` ;
  - valeur vide ou blanche (ex. `APP_TIMEZONE=` dans `.env`) : **traitée comme absente**, donc `Africa/Dakar` (choix le plus simple : une variable laissée vide n'est pas une demande de fuseau, et l'application ne bloque pas pour si peu) ; la valeur effective est journalisée en INFO ;
  - valeur valide : utilisée telle quelle (après trim), journalisée en INFO ;
  - **valeur invalide** (ex. `Mars/Olympus`, `+25:00`) : l'application **refuse de démarrer** avec un message qui nomme la propriété `app.timezone` et la valeur fautive. Aucun repli silencieux sur le défaut dans ce cas.
  Les instants techniques (`createdAt`, expiration du JWT) restent des `Instant` et ne dépendent pas du fuseau.
- **RM-LOAN-30** (test local avec un compte `MEMBER`, profil `local` uniquement) : **choix retenu : amorçage d'un membre de test au démarrage, profil `local` uniquement**, sur le modèle exact de l'amorçage du bibliothécaire.
  - Au démarrage avec le profil `local`, s'il n'existe aucun utilisateur de cet e-mail, un utilisateur avec **le seul rôle `MEMBER`** et `enabled = true` est créé à partir de `DEV_MEMBER_EMAIL` et `DEV_MEMBER_PASSWORD` (propriétés `app.dev.member.email` / `.password`, dans `.env`, jamais commité). Aucune valeur par défaut, ni dans le code, ni dans `application.yml`, ni dans une migration. Le mot de passe respecte la politique existante (12 caractères minimum, 72 octets maximum, RM-AUTH-03) et est haché en BCrypt ; il n'est jamais journalisé.
  - **Variables absentes ou vides (l'une des deux) : aucun membre de test n'est créé, un WARN est journalisé (sans valeur), et le démarrage se poursuit normalement** (même comportement que `BOOTSTRAP_LIBRARIAN_*`). Idem pour un e-mail mal formé ou un mot de passe non conforme : WARN, pas de création, jamais d'échec de démarrage.
  - Idempotent : si un utilisateur de cet e-mail existe déjà, rien n'est créé ni modifié (WARN s'il n'a pas le rôle `MEMBER`).
  - Hors profil `local`, ce composant n'existe pas (aucun membre de test créé, variables ignorées).
  - Usage : `POST /api/v1/auth/login` avec ce compte (cookie, parcours frontend complet avec CSRF) ou `Authorization: Bearer` avec un jeton de login, et `sub` = vrai UUID du membre, stable entre redémarrages.
  - `/dev/token` reste inchangé et reste utilisable pour tester l'API : son `sub` par défaut est déjà un UUID aléatoire et son rôle par défaut `MEMBER`, donc un jeton `{"roles":["MEMBER"]}` suffit à appeler les trois endpoints sans compte. Un `subject` non UUID donne 401 (RM-LOAN-18).
  - Justification : `/dev/token` seul ne permet pas de tester le frontend (jeton dans le corps, pas de cookie `ACCESS_TOKEN` ni de CSRF) et son `sub` ne désigne aucun membre réel ; le membre de test réutilise le module `identity`, le schéma existant et le modèle d'amorçage déjà accepté (variables d'environnement sans défaut, WARN si absentes), sans secret dans le code.

**Interface**
- **RM-LOAN-21** : sur l'écran Catalogue, chaque ligne affiche un bouton « Emprunter » pour un utilisateur ayant le rôle `MEMBER` (masqué pour un `LIBRARIAN` seul). Pendant l'appel, le bouton de la ligne est désactivé avec indicateur (une seule requête par clic, double clic = une requête).
- **RM-LOAN-22** : succès (201) : toast « Livre emprunté, à rendre avant le <date d'échéance> » ; la liste du catalogue est rechargée en conservant page et recherche (le livre peut disparaître s'il n'y a plus d'exemplaire). Erreurs 409 : message métier en français affiché par toast (un seul toast, pas de doublon avec l'`errorInterceptor` : option `SKIP_ERROR_TOAST` sur cet appel, toast émis par l'écran) :
  - `BOOK_NOT_AVAILABLE` : « Ce livre n'est plus disponible » ;
  - `LOAN_LIMIT_REACHED` : « Vous avez atteint la limite de 3 emprunts en cours » ;
  - `MEMBER_HAS_OVERDUE_LOAN` : « Rendez d'abord vos livres en retard » ;
  - `BOOK_ALREADY_BORROWED` : « Vous avez déjà emprunté ce livre ».
  404 `BOOK_NOT_FOUND` : « Ce livre n'existe plus » et rechargement. 403, 5xx, réseau : toast générique de l'`errorInterceptor`. 401 : comportement existant de l'`authInterceptor`.
- **RM-LOAN-23** : route `/emprunts` (chargement paresseux, `authGuard`), lien « Mes emprunts » dans l'en-tête pour un utilisateur connecté ayant le rôle `MEMBER`. Un `LIBRARIAN` seul qui ouvre `/emprunts` est redirigé vers `/catalogue`.
- **RM-LOAN-24** : l'écran « Mes emprunts » affiche un tableau (`p-table`) : Titre, Auteur, ISBN, Emprunté le, À rendre avant, Statut (`p-tag` « En cours » / « En retard » en danger) et un bouton « Rendre ». Un compteur « n / 3 emprunts en cours » est visible. Un bandeau d'avertissement s'affiche si au moins un emprunt est en retard (« Vous ne pouvez plus emprunter tant que vous avez un livre en retard »). États : chargement, vide (« Vous n'avez aucun emprunt en cours », avec lien vers le catalogue), erreur (toast + « Réessayer »). Dates au format français. Si `title` est nul (livre introuvable, RM-LOAN-32), la cellule affiche « Livre indisponible ».
- **RM-LOAN-25** : « Rendre » demande une confirmation (`p-confirmdialog`), puis envoie `POST /api/v1/loans/{id}/return`, un seul envoi à la fois. Succès (204) : toast « Livre rendu », la ligne disparaît, le tableau est rechargé. 409 `LOAN_ALREADY_RETURNED` : toast « Cet emprunt est déjà rendu » et rechargement. 404 `LOAN_NOT_FOUND` : toast « Emprunt introuvable » et rechargement.
- **RM-LOAN-26** : accessibilité : libellés de boutons explicites (« Emprunter <titre> », « Rendre <titre> »), statut non porté par la seule couleur, focus géré à la fermeture de la confirmation, navigation clavier.
- **RM-LOAN-27** : le mode `ng serve --configuration mock` simule les emprunts (création, limite à 3, retard, déjà emprunté, rendu, erreurs 409) et la décrémentation du catalogue simulé. Aucun code simulé dans le build normal.

## Critères d'acceptation (Gherkin : Étant donné / Quand / Alors)

### API : emprunter
```gherkin
Scénario: Emprunt nominal
  Étant donné un MEMBER sans emprunt et un livre "L'Étranger" avec 2 exemplaires disponibles
  Et la date du jour 2026-11-24 dans le fuseau de la bibliothèque
  Quand il envoie POST /api/v1/loans avec {"bookId": "<id du livre>"}
  Alors la réponse a le statut 201 avec un en-tête Location
  Et le corps contient id, bookId, borrowedOn "2026-11-24", dueOn "2026-12-08"
  Et le livre a 1 exemplaire disponible

Scénario: La date du jour suit le fuseau de la bibliothèque
  Étant donné app.timezone = "Asia/Tokyo" et l'instant 2026-11-24T20:00:00Z
  Quand un membre emprunte un livre disponible
  Alors borrowedOn vaut "2026-11-25" et dueOn vaut "2026-12-09"

Scénario: Dernier exemplaire
  Étant donné un livre avec 1 exemplaire disponible
  Quand un membre l'emprunte
  Alors availableCopies vaut 0 et le livre n'apparaît plus dans GET /api/v1/books

Scénario: Livre indisponible
  Étant donné un livre dont availableCopies vaut 0
  Quand un membre envoie POST /api/v1/loans pour ce livre
  Alors la réponse a le statut 409 et le code "BOOK_NOT_AVAILABLE"
  Et aucun emprunt n'est créé

Scénario: Livre déjà emprunté par ce membre
  Étant donné un membre qui a un emprunt actif du livre "La Peste" (2 exemplaires disponibles)
  Quand il envoie POST /api/v1/loans pour "La Peste"
  Alors la réponse a le statut 409 et le code "BOOK_ALREADY_BORROWED"
  Et aucun emprunt n'est créé et availableCopies est inchangé

Scénario: Même livre après retour
  Étant donné un membre qui a emprunté puis rendu "La Peste"
  Quand il emprunte de nouveau "La Peste"
  Alors la réponse a le statut 201

Scénario: Même livre par un autre membre
  Étant donné un membre A qui a emprunté "La Peste" (2 exemplaires, 1 disponible)
  Quand un membre B emprunte "La Peste"
  Alors la réponse a le statut 201

Scénario: Même livre, deux requêtes simultanées du même membre
  Étant donné un livre avec 5 exemplaires disponibles
  Quand le même membre envoie deux POST /api/v1/loans simultanés pour ce livre
  Alors exactement une réponse est 201 et l'autre 409 "BOOK_ALREADY_BORROWED"
  Et availableCopies a diminué de 1 seulement

Scénario: Quatrième emprunt refusé
  Étant donné un membre avec 3 emprunts actifs non en retard
  Quand il emprunte un 4e livre disponible
  Alors la réponse a le statut 409 et le code "LOAN_LIMIT_REACHED"

Scénario: Limite libérée par un retour
  Étant donné un membre avec 3 emprunts actifs
  Quand il rend l'un d'eux puis emprunte un autre livre disponible
  Alors le second appel renvoie 201

Scénario: Retard bloque l'emprunt
  Étant donné un membre avec un emprunt actif dont l'échéance est hier
  Quand il emprunte un livre disponible
  Alors la réponse a le statut 409 et le code "MEMBER_HAS_OVERDUE_LOAN"

Scénario: Le jour de l'échéance n'est pas un retard
  Étant donné un emprunt dont l'échéance est aujourd'hui
  Alors l'emprunt n'est pas en retard et le membre peut emprunter (si moins de 3 emprunts)

Scénario: Retard rendu, emprunt de nouveau possible
  Étant donné un membre dont le seul emprunt est en retard
  Quand il le rend puis emprunte un livre disponible
  Alors le retour renvoie 204 et l'emprunt renvoie 201

Plan du Scénario: Ordre d'évaluation quand plusieurs règles s'appliquent
  Étant donné <situation>
  Quand le membre envoie POST /api/v1/loans pour le livre visé
  Alors la réponse a le statut <statut> et le code "<code>"
  Exemples:
    | situation                                                                                  | statut | code                    |
    | livre inconnu, membre avec un emprunt en retard                                            | 404    | BOOK_NOT_FOUND          |
    | emprunt en retard, 3 emprunts actifs, livre déjà emprunté, livre à 0 exemplaire            | 409    | MEMBER_HAS_OVERDUE_LOAN |
    | aucun retard, 3 emprunts actifs dont ce livre, livre à 0 exemplaire                        | 409    | LOAN_LIMIT_REACHED      |
    | aucun retard, 2 emprunts actifs dont ce livre, livre à 0 exemplaire                        | 409    | BOOK_ALREADY_BORROWED   |
    | aucun retard, moins de 3 emprunts, livre non emprunté par lui, livre à 0 exemplaire        | 409    | BOOK_NOT_AVAILABLE      |

Scénario: Livre inconnu
  Quand un membre emprunte un bookId qui n'existe pas
  Alors la réponse a le statut 404 et le code "BOOK_NOT_FOUND"

Plan du Scénario: Corps invalide
  Quand un membre envoie POST /api/v1/loans avec <corps>
  Alors la réponse a le statut 400 et le code "VALIDATION_FAILED"
  Exemples:
    | corps                  | champ / code            |
    | {}                     | bookId / REQUIRED       |
    | {"bookId": "abc"}      | bookId / INVALID_FORMAT |
    | {"bookId": null}       | bookId / REQUIRED       |

Scénario: Dernier exemplaire en concurrence
  Étant donné un livre avec 1 exemplaire disponible et deux membres
  Quand ils envoient POST /api/v1/loans simultanément
  Alors exactement une réponse est 201 et l'autre 409 "BOOK_NOT_AVAILABLE"
  Et availableCopies vaut 0 et n'est jamais négatif

Scénario: Le stock ne passe jamais sous zéro (contrainte en base)
  Étant donné un livre dont availableCopies vaut 0
  Quand une écriture directe tente de mettre available_copies à -1 (test d'intégration PostgreSQL)
  Alors la base rejette l'écriture (contrainte ck_book_available_copies_range)
  Et une écriture à total_copies + 1 est aussi rejetée

Scénario: Limite en concurrence
  Étant donné un membre avec 2 emprunts actifs
  Quand il envoie deux emprunts simultanés de livres différents
  Alors exactement un réussit et il a 3 emprunts actifs

Scénario: Atomicité
  Étant donné une erreur technique lors de l'enregistrement de l'emprunt
  Alors availableCopies du livre est inchangé et aucun emprunt n'existe

Scénario: Cache invalidé
  Étant donné la liste des livres disponibles en cache contenant le livre
  Quand le dernier exemplaire est emprunté
  Alors la clé du cache est invalidée après le commit et GET /api/v1/books ne renvoie plus le livre
```

### API : consulter et rendre
```gherkin
Scénario: Mes emprunts en cours
  Étant donné un membre avec 2 emprunts actifs et 1 emprunt rendu, et un autre membre avec 1 emprunt actif
  Quand le membre envoie GET /api/v1/loans
  Alors la réponse a le statut 200 avec 2 éléments triés par dueOn croissant
  Et chaque élément contient id, bookId, isbn, title, author, borrowedOn, dueOn, overdue
  Et aucun emprunt d'un autre membre ni rendu n'apparaît

Scénario: Lecture groupée des livres, sans N+1
  Étant donné un membre avec 3 emprunts actifs de 3 livres différents
  Quand il envoie GET /api/v1/loans
  Alors catalog.api est appelé une seule fois avec les 3 identifiants de livre
  Et le catalogue exécute une seule requête de lecture pour ces 3 livres

Scénario: Aucun emprunt, aucun appel au catalogue
  Quand un membre sans emprunt envoie GET /api/v1/loans
  Alors la réponse a le statut 200 et le corps est []
  Et catalog.api n'est pas appelé

Scénario: Livre introuvable dans le lot
  Étant donné un emprunt actif dont le livre n'existe plus dans le catalogue
  Quand le membre envoie GET /api/v1/loans
  Alors l'emprunt est listé avec isbn, title et author nuls et la réponse a le statut 200

Scénario: Indicateur de retard
  Étant donné un emprunt actif dont l'échéance est hier
  Alors son élément a overdue = true

Scénario: Retour nominal
  Étant donné un emprunt actif du membre sur un livre à 0 exemplaire disponible
  Quand il envoie POST /api/v1/loans/<id>/return
  Alors la réponse a le statut 204
  Et l'emprunt a returnedOn = date du jour et le livre a 1 exemplaire disponible
  Et le livre réapparaît dans GET /api/v1/books

Scénario: Double retour
  Étant donné un emprunt déjà rendu
  Quand le membre envoie de nouveau POST /api/v1/loans/<id>/return
  Alors la réponse a le statut 409 et le code "LOAN_ALREADY_RETURNED"
  Et availableCopies n'a pas été incrémenté une seconde fois

Scénario: Double retour simultané
  Quand deux requêtes de retour du même emprunt arrivent en même temps
  Alors exactement une répond 204 et l'autre 409, et availableCopies n'augmente que de 1

Scénario: Emprunt d'un autre membre
  Quand un membre rend l'emprunt d'un autre membre
  Alors la réponse a le statut 404 et le code "LOAN_NOT_FOUND" et rien n'est modifié

Scénario: Emprunt inconnu ou identifiant mal formé
  Quand un membre rend un identifiant inconnu ou "abc"
  Alors la réponse a le statut 404 et le code "LOAN_NOT_FOUND"

Scénario: Rendre un emprunt en retard
  Étant donné un emprunt actif en retard
  Quand le membre le rend
  Alors la réponse a le statut 204
```

### API : sécurité
```gherkin
Plan du Scénario: Accès
  Quand <acteur> appelle <endpoint>
  Alors la réponse a le statut <statut>
  Exemples:
    | acteur                    | endpoint                       | statut            |
    | non authentifié           | POST /api/v1/loans             | 401               |
    | non authentifié           | GET /api/v1/loans              | 401               |
    | LIBRARIAN seul            | POST /api/v1/loans             | 403               |
    | LIBRARIAN seul            | GET /api/v1/loans              | 403               |
    | LIBRARIAN seul            | POST /api/v1/loans/{id}/return | 403               |
    | MEMBER                    | POST /api/v1/loans             | 201 ou 4xx métier |
    | LIBRARIAN + MEMBER        | POST /api/v1/loans             | 201 ou 4xx métier |
    | MEMBER (cookie sans CSRF) | POST /api/v1/loans             | 403               |
    | MEMBER (Bearer sans CSRF) | POST /api/v1/loans             | 201               |

Plan du Scénario: Sujet du jeton non UUID
  Étant donné un jeton valide, rôle MEMBER, dont le sub vaut "not-a-uuid"
  Quand il appelle <endpoint>
  Alors la réponse a le statut 401 et le code "UNAUTHENTICATED" et aucune donnée n'est modifiée
  Exemples:
    | endpoint                       |
    | POST /api/v1/loans             |
    | GET /api/v1/loans              |
    | POST /api/v1/loans/{id}/return |

Scénario: Le membre vient du jeton
  Quand un membre envoie un corps contenant un champ memberId d'un autre membre
  Alors le champ est ignoré et l'emprunt est créé pour le sub du JWT
```

### Configuration et outillage local
```gherkin
Scénario: Fuseau absent, valeur par défaut
  Étant donné la variable APP_TIMEZONE non définie
  Quand l'application démarre
  Alors app.timezone vaut "Africa/Dakar" (valeur de application.yml) et le démarrage réussit
  Et le fuseau effectif est journalisé en INFO

Scénario: Fuseau vide traité comme absent
  Étant donné APP_TIMEZONE défini mais vide ou blanc
  Quand l'application démarre
  Alors le fuseau effectif est "Africa/Dakar" et le démarrage réussit

Scénario: Fuseau modifié par la variable
  Étant donné APP_TIMEZONE = "Europe/Paris"
  Quand l'application démarre
  Alors le Clock de l'application est dans le fuseau "Europe/Paris"

Scénario: Fuseau invalide
  Étant donné APP_TIMEZONE = "Mars/Olympus"
  Quand l'application démarre
  Alors le démarrage échoue avec un message qui nomme app.timezone et la valeur "Mars/Olympus"
  Et l'application ne se rabat pas sur "Africa/Dakar"

Scénario: Membre de test créé en local
  Étant donné le profil local, DEV_MEMBER_EMAIL et DEV_MEMBER_PASSWORD (12 caractères) définis, aucun utilisateur de cet e-mail
  Quand l'application démarre
  Alors un utilisateur actif avec le seul rôle MEMBER est créé
  Et POST /api/v1/auth/login avec ces identifiants renvoie 200 avec le rôle MEMBER
  Et le mot de passe n'apparaît dans aucun journal

Scénario: Membre de test déjà présent
  Étant donné un utilisateur de cet e-mail déjà en base
  Quand l'application redémarre
  Alors rien n'est créé ni modifié

Plan du Scénario: Variables du membre de test absentes ou vides
  Étant donné le profil local avec <situation>
  Quand l'application démarre
  Alors un WARN est journalisé sans aucune valeur secrète
  Et aucun membre de test n'est créé
  Et le démarrage réussit normalement
  Exemples:
    | situation                                         |
    | DEV_MEMBER_EMAIL absent                           |
    | DEV_MEMBER_PASSWORD absent                        |
    | DEV_MEMBER_EMAIL et DEV_MEMBER_PASSWORD absents   |
    | DEV_MEMBER_PASSWORD vide                          |

Scénario: Aucun membre de test hors profil local
  Étant donné un profil autre que local avec DEV_MEMBER_EMAIL et DEV_MEMBER_PASSWORD définis
  Quand l'application démarre
  Alors aucun membre de test n'est créé

Scénario: Jeton de développement pour tester l'API
  Étant donné le profil local
  Quand POST /dev/token est appelé avec {"roles":["MEMBER"]}
  Alors le jeton obtenu (sub = UUID) permet d'appeler POST /api/v1/loans avec Authorization: Bearer
```

### Interface
```gherkin
Scénario: Emprunter depuis le catalogue
  Étant donné un MEMBER sur /catalogue
  Quand il clique sur « Emprunter » pour un livre
  Alors POST /api/v1/loans est envoyé avec bookId
  Et un toast indique la date de retour et la liste est rechargée à la même page

Scénario: Bouton absent pour un bibliothécaire seul
  Étant donné un LIBRARIAN seul connecté
  Alors le bouton « Emprunter » et le lien « Mes emprunts » ne sont pas affichés
  Et l'ouverture de /emprunts redirige vers /catalogue

Scénario: Limite atteinte
  Étant donné l'API qui répond 409 "LOAN_LIMIT_REACHED"
  Quand le membre clique sur « Emprunter »
  Alors un seul toast « Vous avez atteint la limite de 3 emprunts en cours » s'affiche

Scénario: Livre déjà emprunté
  Étant donné l'API qui répond 409 "BOOK_ALREADY_BORROWED"
  Quand le membre clique sur « Emprunter »
  Alors un seul toast « Vous avez déjà emprunté ce livre » s'affiche

Scénario: Double clic
  Quand le membre double-clique sur « Emprunter »
  Alors une seule requête est émise

Scénario: Mes emprunts
  Étant donné un membre avec un emprunt en cours et un en retard
  Quand il ouvre /emprunts
  Alors le tableau affiche 2 lignes, la seconde avec le statut « En retard »
  Et le bandeau d'avertissement et le compteur « 2 / 3 » sont visibles

Scénario: Aucun emprunt
  Étant donné un membre sans emprunt
  Alors « Vous n'avez aucun emprunt en cours » et un lien vers le catalogue s'affichent

Scénario: Rendre un livre
  Quand le membre clique sur « Rendre », puis confirme
  Alors POST /api/v1/loans/<id>/return est envoyé, un toast « Livre rendu » s'affiche et la ligne disparaît

Scénario: Rendre annulé
  Quand le membre clique sur « Rendre » puis annule la confirmation
  Alors aucune requête n'est envoyée

Scénario: Emprunt déjà rendu (autre onglet)
  Étant donné l'API qui répond 409 "LOAN_ALREADY_RETURNED"
  Alors un toast « Cet emprunt est déjà rendu » s'affiche et le tableau est rechargé

Scénario: Erreur serveur au chargement
  Étant donné l'API qui répond 500 sur GET /api/v1/loans
  Alors un toast technique et un bouton « Réessayer » s'affichent

Scénario: Mode mock
  Étant donné ng serve --configuration mock et un membre simulé
  Quand il emprunte deux fois le même livre simulé
  Alors le second essai affiche le toast « Vous avez déjà emprunté ce livre »
```

## Données (entités, champs, contraintes)

### Nouvelle entité `Loan` (module `loan`, table `loan`, migration Flyway `V3__create_table_loan.sql`)
| Champ | Type | Contraintes |
|---|---|---|
| `id` | UUID | clé primaire (`LoanId`) |
| `member_id` | UUID | non nul, `sub` du JWT (`MemberId`) ; pas de FK vers `app_user` |
| `book_id` | UUID | non nul (`BookId` propre à `loan`) ; pas de FK vers `book` |
| `borrowed_on` | DATE | non nul |
| `due_on` | DATE | non nul, `>= borrowed_on` (CHECK) ; = `borrowed_on + 14` à la création |
| `returned_on` | DATE | nul tant que non rendu ; `>= borrowed_on` (CHECK) |

Index : `(member_id)` partiel `WHERE returned_on IS NULL` (lecture des emprunts actifs d'un membre) ; **index unique partiel `(member_id, book_id) WHERE returned_on IS NULL`** (un seul emprunt actif par membre et par livre, RM-LOAN-28 ; une violation est traduite en 409 `BOOK_ALREADY_BORROWED`). Aucune suppression physique. Agrégat sans setter public : `Loan.open(...)`, `Loan.restore(...)`, `giveBack(today)`, `isActive()`, `isOverdue(today)`. Service de domaine `LoanPolicy` (`MAX_ACTIVE_LOANS = 3`, ordre de RM-LOAN-06 : retard, limite, déjà emprunté). Exceptions métier héritant de `DomainException` : `BookNotAvailableException`, `LoanLimitReachedException`, `MemberHasOverdueLoanException`, `BookAlreadyBorrowedException`, `LoanAlreadyReturnedException`, `LoanNotFoundException`, `BookNotFoundException`.

### Évolution de `catalog`
Aucune nouvelle colonne ni migration ; la contrainte `ck_book_available_copies_range` existante est conservée (RM-LOAN-31). Ajout dans le domaine `Book` de deux opérations métier (prise et restitution d'un exemplaire, bornes `0..totalCopies`), de cas d'usage (réserver / libérer un exemplaire, lire des livres par lot d'identifiants), d'un contrat `catalog.api.CatalogApi` (réserver, libérer, rechercher par lot : `Collection<UUID>` → résumés `id`, `isbn`, `title`, `author`) et de sa façade `adapter.in.module`. L'écriture de disponibilité passe par un port annoté `@AffectsAvailableBooks`.

### Configuration
| Propriété | Variable | Rôle | Valeur par défaut |
|---|---|---|---|
| `app.timezone` | `APP_TIMEZONE` | fuseau IANA de la bibliothèque | `Africa/Dakar`, commitée dans `application.yml` (`${APP_TIMEZONE:Africa/Dakar}`) ; vide = absente ; invalide : refus de démarrer |
| `app.dev.member.email` | `DEV_MEMBER_EMAIL` | e-mail du membre de test (profil `local` seulement) | aucune ; absente : WARN, pas de compte, démarrage normal |
| `app.dev.member.password` | `DEV_MEMBER_PASSWORD` | mot de passe du membre de test (12 caractères minimum), jamais journalisé | aucune ; absente : WARN, pas de compte, démarrage normal |

### Requêtes et réponses
| Endpoint | Rôle | Corps / paramètres | Succès |
|---|---|---|---|
| `POST /api/v1/loans` | `MEMBER` | `{ "bookId": UUID }` | 201, `Location: /api/v1/loans/{id}`, `{ id, bookId, borrowedOn, dueOn }` |
| `GET /api/v1/loans` | `MEMBER` | aucun (emprunts actifs du membre connecté) | 200, `[ { id, bookId, isbn, title, author, borrowedOn, dueOn, overdue } ]` (`isbn`, `title`, `author` nuls si le livre est introuvable) |
| `POST /api/v1/loans/{id}/return` | `MEMBER` | aucun | 204 sans corps |

Dates au format ISO `yyyy-MM-dd` (type `LocalDate`, calculées dans le fuseau `app.timezone`, sans fuseau dans le contrat).

### Erreurs (`ProblemDetail`, RFC 9457, avec `code`)
| Statut | Code | Cas |
|---|---|---|
| 400 | `VALIDATION_FAILED` (+ `errors[]`) | `bookId` absent ou mal formé, JSON illisible |
| 401 | `UNAUTHENTICATED` | jeton absent, invalide, expiré, `sub` non UUID |
| 403 | `ACCESS_DENIED` | rôle `MEMBER` absent, CSRF manquant |
| 404 | `BOOK_NOT_FOUND` | livre inconnu à l'emprunt |
| 404 | `LOAN_NOT_FOUND` | emprunt inconnu ou d'un autre membre |
| 409 | `MEMBER_HAS_OVERDUE_LOAN` | emprunt en retard (RM-02) |
| 409 | `LOAN_LIMIT_REACHED` | 3 emprunts actifs (RM-01) |
| 409 | `BOOK_ALREADY_BORROWED` | le membre a déjà ce livre en cours (RM-LOAN-28) |
| 409 | `BOOK_NOT_AVAILABLE` | aucun exemplaire disponible (RM-04) |
| 409 | `LOAN_ALREADY_RETURNED` | double retour (RM-05) |

Les `@RestControllerAdvice` de `loan` traduisent ses exceptions ; le contrat sera documenté dans `docs/api/loan.md` par l'étape de contrat de la factory.

### Frontend (indicatif)
Modèles `Loan { id, bookId, isbn, title, author, borrowedOn, dueOn, overdue }` ; service `LoanService` (`HttpClient`, chemins relatifs `/api/v1/loans`) ; fonctionnalité `features/loans` (écran `/emprunts`) ; bouton « Emprunter » ajouté à l'écran Catalogue ; lien d'en-tête conditionné au rôle. Aucun jeton manipulé.

## À prévoir (documentation et configuration)
- **CLAUDE.md** : documenter `APP_TIMEZONE` (facultative, défaut `Africa/Dakar`, invalide = refus de démarrer), `DEV_MEMBER_EMAIL` et `DEV_MEMBER_PASSWORD` (profil `local`, facultatives, absentes = WARN et aucun membre de test), ainsi que les règles RM-LOAN essentielles.
- **`.env.example`** : ajouter `APP_TIMEZONE`, `DEV_MEMBER_EMAIL` et `DEV_MEMBER_PASSWORD` avec un commentaire, **sans valeur secrète** (valeurs vides ou fictives manifestes).
- **`.env`** : jamais modifié par l'implémentation ni par les agents ; c'est l'utilisateur qui le remplit lui-même (le mot de passe du membre de test en particulier).
- **`application.yml`** : seule la ligne `app.timezone: ${APP_TIMEZONE:Africa/Dakar}` porte une valeur par défaut ; aucune valeur par défaut pour `app.dev.member.*`.
- **Tests** : `src/test/resources/application.properties` n'a pas besoin de fournir `app.timezone` (défaut applicable) ; les tests d'emprunt utilisent un `Clock.fixed`.

## Hors périmètre
- Prolongation d'un emprunt (RM-06), pénalités, amendes, notifications ou rappels d'échéance.
- Historique des emprunts rendus côté interface, vue du bibliothécaire sur les emprunts de tous les membres, retour enregistré par un bibliothécaire pour un membre, liste des retards.
- Réservation d'un livre indisponible, file d'attente.
- Création ou gestion des membres en production (module `identity`), désactivation de compte avec emprunts en cours. Le membre de test de RM-LOAN-30 est un outil du profil `local` uniquement.
- Ajout ou retrait d'exemplaires d'un livre existant.
- Cache, outil MCP ou toute autre technologie pour `loan`.
- Modification des contrats existants du catalogue (`GET`/`POST /api/v1/books`) ou du contrat MCP.
- Désactivation du bouton « Emprunter » pour un livre que le membre a déjà en cours (le refus 409 `BOOK_ALREADY_BORROWED` et son toast suffisent).

## Décisions (validées le 2026-11-24)
1. **Q1 : rôles.** Seul `MEMBER` emprunte ; un compte qui cumule `LIBRARIAN` et `MEMBER` emprunte pour lui-même. `LIBRARIAN` seul : 403.
2. **Q2 : retour.** Seul le membre emprunteur rend ; le retour saisi par un bibliothécaire est hors périmètre.
3. **Q3 : même livre.** Un membre ne peut pas avoir deux emprunts en cours du même livre : 409 `BOOK_ALREADY_BORROWED` (RM-LOAN-28), garanti par un index unique partiel, toast frontend « Vous avez déjà emprunté ce livre ». Ordre d'évaluation fixé en RM-LOAN-06 : livre inconnu, retard, limite, déjà emprunté, indisponible.
4. **Q4 : données du livre.** Lecture via `catalog.api`, sans copie dans `loan`, **groupée** : une seule requête vers `catalog` pour tous les emprunts affichés, sans N+1 ; `catalog.api` expose une recherche par lot d'identifiants (RM-LOAN-17, RM-LOAN-32).
5. **Q5 : date du jour.** Calculée dans le fuseau explicite `app.timezone`, pas celui du serveur. **Le fuseau n'est pas un secret** : valeur par défaut `Africa/Dakar` dans `application.yml` (`${APP_TIMEZONE:Africa/Dakar}`), modifiable par `APP_TIMEZONE`. Absente ou vide : `Africa/Dakar`. Seule une valeur invalide empêche le démarrage, avec un message qui nomme la propriété et la valeur fautive (RM-LOAN-29).
6. **Q6 : `sub` et test local.** `sub` non UUID : 401 `UNAUTHENTICATED` confirmé. Test local avec un compte `MEMBER` : **membre de test créé au démarrage, profil `local` uniquement**, à partir de `DEV_MEMBER_EMAIL` / `DEV_MEMBER_PASSWORD` sans valeur par défaut, idempotent ; **variables absentes : aucun membre créé, WARN, démarrage normal**, comme pour le bibliothécaire (RM-LOAN-30). `/dev/token` reste inchangé (son `sub` par défaut est déjà un UUID). Justification : `/dev/token` seul ne permet pas de tester le frontend (pas de cookie ni de CSRF) ni de désigner un membre réel ; l'amorçage reprend le modèle déjà en place pour le bibliothécaire.
7. **Q7 : concurrence.** Sérialisation de la limite de 3 laissée à l'architecte. Le stock ne passe jamais sous zéro : décrémentation conditionnelle **et** contrainte de base `available_copies >= 0` (déjà présente dans V1 sous la forme `BETWEEN 0 AND total_copies`, conservée et testée, aucune migration) (RM-LOAN-31).
8. **Q8 : limite.** Seuls les emprunts en cours comptent dans la limite de 3.
9. **Q9 : cache et MCP.** Ni cache ni MCP pour `loan`. L'invalidation du cache des livres disponibles se fait dans `catalog` ; `loan` n'appelle que `catalog.api` (RM-LOAN-16).
10. **Q10 : navigation.** Lien « Mes emprunts » dans l'en-tête, route `/emprunts`.
11. **Q11 : confirmation.** `p-confirmdialog` avant le retour.
12. **Documentation.** `APP_TIMEZONE`, `DEV_MEMBER_EMAIL` et `DEV_MEMBER_PASSWORD` sont documentées dans CLAUDE.md et `.env.example`, jamais dans `.env` (voir « À prévoir »).

## Questions ouvertes
Aucune.
