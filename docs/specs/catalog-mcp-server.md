# Serveur MCP du catalogue (module catalog)

## Contexte et objectif
Un assistant IA (client MCP) doit pouvoir interroger le catalogue de la bibliothèque en lecture seule : retrouver un livre par ISBN et lister les livres encore empruntables. Le besoin est couvert par un serveur MCP (Model Context Protocol) embarqué dans l'application, en Spring AI 2.0, transport Streamable HTTP, exposé sur `/mcp`.

Constat sur le code existant (`backend/src/main/java/com/example/library/`) :
- Ports d'entrée de `catalog` : uniquement `RegisterBookUseCase`. **Il n'existe ni port de consultation par ISBN, ni port de listing** (les specs `catalog-enregistrement-livre` et `loan-emprunter-livre` le confirment : « aucun port de lecture »). Ces deux ports d'entrée sont donc **à créer dans le périmètre de cette feature**, ainsi que les ports de sortie et requêtes de persistance qu'ils nécessitent (l'architecte tranchera leur découpage).
- Ports de sortie existants : `SaveBookPort`, `CheckIsbnRegisteredPort`. Aucun ne permet de relire un livre.
- Modèle : `Book` (immuable) porte `id`, `isbn` (`Isbn`, 13 chiffres, normalisé par `Isbn.of`), `title`, `author`, `totalCopies`, `availableCopies` (RM-CAT-12 : compteurs, pas d'entité exemplaire). Un livre est « disponible » si `availableCopies >= 1` (RM-04).
- Exceptions du domaine : `InvalidIsbnException` (code `ISBN_INVALID`), dérivées de `DomainException(code, message)`. Il n'existe pas d'exception « livre absent » (à introduire, utile aussi au futur `GET` REST et à `loan`).
- Adapter entrant existant : `catalog.adapter.in.web` (`BookController`, `CatalogExceptionHandler` limité par `basePackageClasses` au package web). Règle ArchUnit : un `adapter.in` ne dépend que des ports d'entrée (`..application.port.in..`), jamais de `adapter.out`, `usecase` ou `port.out`.
- Sécurité : `SecurityConfig` définit une seule `SecurityFilterChain` (JWT HS256, rôles dans `roles`), `anyRequest().authenticated()` ; `/mcp` exige donc déjà un JWT valide (401 sinon) tant qu'on n'y touche pas. Profil `local` existant pour `/dev/token`.
- `pom.xml` : Spring Boot parent 4.1.1, aucune dépendance Spring AI.

## Acteurs et rôles (qui a le droit de faire quoi)
| Acteur | Droit sur cette fonctionnalité |
|---|---|
| Assistant IA / client MCP avec JWT valide | Peut appeler `get_book` et `list_available_books` (rôle exact : voir Q3). |
| Client MCP sans JWT, profil `mcp-local` **inactif** | Interdit : 401 sur `/mcp`. |
| Client MCP sans JWT, profil `mcp-local` **actif** | Autorisé (usage poste de développeur uniquement). |
| JWT invalide ou expiré (profil `mcp-local` inactif) | 401. |
| Développeur / mainteneur | Active `mcp-local` explicitement en local ; ne doit jamais l'activer hors poste local. |

Aucun acteur ne peut écrire dans le catalogue via MCP (aucun outil d'écriture).

## Règles métier (numérotées RM-01, RM-02…)
Les RM-01 à RM-07 du CLAUDE.md sont les règles de référence ; les règles propres à cette spec sont préfixées `RM-MCP-`.

- **RM-04 (référence)** : un livre est disponible s'il reste au moins un exemplaire disponible (`availableCopies >= 1`). Appliquée par `list_available_books`.
- **RM-07 (référence)** : l'ISBN est de 13 chiffres. Appliquée à l'entrée de `get_book`.
- **RM-MCP-01** : le serveur expose exactement deux outils, `get_book` et `list_available_books`, tous deux en lecture seule. Aucun outil, ressource ou prompt MCP ne crée, modifie ou supprime une donnée. Un test vérifie la liste exacte des outils exposés.
- **RM-MCP-02** : l'adapter `catalog.adapter.in.mcp` n'appelle que des ports d'entrée (`..application.port.in..`) ; `ArchitectureTest` reste vert sans modification.
- **RM-MCP-03** : les outils renvoient exclusivement des records DTO de l'adapter (jamais `Book`, `Isbn`, `BookId`, ni entité JPA). Les DTO ne contiennent pas l'identifiant technique `id` (hypothèse, voir Q6).
- **RM-MCP-04** : `get_book(isbn)` normalise l'ISBN comme le reste du système (suppression des espaces et tirets, `Isbn.of`) puis exige 13 chiffres (RM-07). `978-2-07-036822-8` et `9782070368228` donnent le même résultat. La normalisation et la validation sont portées par le domaine, pas dupliquées dans l'adapter MCP.
- **RM-MCP-05** : `get_book` renvoie `title`, `author`, `isbn` (forme normalisée à 13 chiffres), `availableCopies`, `totalCopies`.
- **RM-MCP-06** : `list_available_books` ne renvoie que des livres avec `availableCopies >= 1` (RM-04). Un livre à 0 exemplaire disponible n'apparaît jamais.
- **RM-MCP-07** : paramètre `limit` de `list_available_books` : entier facultatif ; **maximum 20** ; valeur par défaut si absent : voir Q1 (recommandation : 10). Une valeur `<= 0` ou `> 20` est refusée avec un message clair (pas de troncature silencieuse, voir Q2). La borne maximale 20 est une constante unique, portée par le port d'entrée / use case (pas seulement par l'adapter) pour être réutilisable.
- **RM-MCP-08** : le résultat de `list_available_books` est trié de façon déterministe (titre croissant sans tenir compte de la casse, puis ISBN croissant) et ne contient jamais plus de `limit` éléments. Une liste vide est un résultat valide (pas une erreur).
- **RM-MCP-09** : toute erreur métier ou de saisie est restituée à l'assistant comme **résultat d'outil en erreur** (`isError = true`) avec un message textuel en français, court, actionnable pour le modèle (ex. « Aucun livre trouvé pour l'ISBN 9782070368228. »). Jamais de stack trace, de nom de classe, de SQL ni de message technique. Une erreur imprévue donne un message générique (« Erreur interne, réessayez plus tard. ») et est journalisée côté serveur sans donnée personnelle ni token.
- **RM-MCP-10** : `/mcp` est accessible sans authentification **si et seulement si** le profil Spring `mcp-local` est actif. Sans ce profil, `/mcp` exige un JWT valide, comme le reste de l'API (401 sinon). Le profil `mcp-local` n'ouvre que `/mcp` : toutes les autres routes (`/api/**`, etc.) restent protégées même avec `mcp-local`.
- **RM-MCP-11** : au démarrage avec le profil `mcp-local`, un avertissement est journalisé indiquant que `/mcp` est ouvert sans authentification. Le profil n'est activé par défaut dans aucun fichier de configuration versionné commun (`application.yml` de base).
- **RM-MCP-12** : les descriptions `@McpTool` et `@McpToolParam` sont rédigées pour le modèle : elles disent ce que fait l'outil, quand l'utiliser et quand ne pas l'utiliser, le format attendu (ISBN 13 chiffres, tirets et espaces tolérés), les bornes (`limit` 1 à 20, défaut), le contenu renvoyé et le comportement si le livre est absent. Un test vérifie que chaque outil et chaque paramètre a une description non vide (contrôle automatique minimal ; la qualité relève de la revue).
- **RM-MCP-13** : les outils sont en lecture seule, y compris côté transaction et persistance (aucune écriture déclenchée par la consultation).

## Critères d'acceptation (Gherkin : Étant donné / Quand / Alors)

### Contrat des outils
`get_book`
- Paramètre : `isbn` (string, obligatoire).
- Succès : `{ "isbn": "9782070368228", "title": "L'Étranger", "author": "Albert Camus", "availableCopies": 2, "totalCopies": 3 }`.

`list_available_books`
- Paramètre : `limit` (entier, facultatif, 1 à 20).
- Succès : liste de `{ "isbn", "title", "author", "availableCopies", "totalCopies" }` (même record que `get_book`).

Erreurs : résultat d'outil `isError = true` + message français. Codes internes indicatifs : `ISBN_INVALID`, `BOOK_NOT_FOUND`, `LIMIT_OUT_OF_RANGE`.

```gherkin
Scénario: get_book nominal
  Étant donné un livre d'ISBN "9782070368228", titre "L'Étranger", auteur "Albert Camus", 3 exemplaires dont 2 disponibles
  Quand l'outil get_book est appelé avec isbn "9782070368228"
  Alors le résultat contient isbn "9782070368228", title "L'Étranger", author "Albert Camus", availableCopies 2 et totalCopies 3
  Et le résultat est un record DTO (pas un objet du domaine)

Scénario: get_book avec ISBN formaté
  Étant donné le même livre
  Quand get_book est appelé avec isbn "978-2-07-036822-8"
  Alors le résultat est identique et l'isbn renvoyé est "9782070368228"

Scénario: get_book sur un livre sans exemplaire disponible
  Étant donné un livre avec 2 exemplaires dont 0 disponible
  Quand get_book est appelé avec son ISBN
  Alors le résultat est un succès avec availableCopies 0 et totalCopies 2

Scénario: get_book livre absent
  Étant donné aucun livre d'ISBN "9780000000002" au catalogue
  Quand get_book est appelé avec isbn "9780000000002"
  Alors le résultat est en erreur (isError = true)
  Et le message dit clairement qu'aucun livre n'existe pour cet ISBN
  Et le message ne contient ni stack trace ni nom de classe

Plan du Scénario: get_book avec ISBN invalide
  Quand get_book est appelé avec isbn "<isbn>"
  Alors le résultat est en erreur avec un message indiquant que l'ISBN doit comporter 13 chiffres
  Et le catalogue n'est pas interrogé
  Exemples:
    | isbn              |
    | 978207036822      |
    | 97820703682280    |
    | 978207036822A     |
    | (chaîne vide)     |
    | "   "             |
    | (paramètre absent)|

Scénario: list_available_books nominal
  Étant donné 5 livres dont 3 ont au moins un exemplaire disponible
  Quand list_available_books est appelé avec limit 10
  Alors le résultat contient exactement les 3 livres disponibles
  Et chaque élément a availableCopies >= 1
  Et la liste est triée par titre puis ISBN

Scénario: list_available_books exclut les livres épuisés
  Étant donné un livre avec 0 exemplaire disponible
  Quand list_available_books est appelé
  Alors ce livre n'apparaît pas dans le résultat

Scénario: list_available_books respecte la limite
  Étant donné 30 livres disponibles
  Quand list_available_books est appelé avec limit 5
  Alors le résultat contient exactement 5 livres

Scénario: limite à la borne maximale
  Étant donné 30 livres disponibles
  Quand list_available_books est appelé avec limit 20
  Alors le résultat contient exactement 20 livres

Scénario: limite absente
  Étant donné 30 livres disponibles
  Quand list_available_books est appelé sans limit
  Alors le résultat contient exactement la valeur par défaut retenue (Q1) de livres

Plan du Scénario: limite hors bornes
  Étant donné des livres disponibles
  Quand list_available_books est appelé avec limit <valeur>
  Alors le résultat est en erreur avec un message indiquant que limit doit être compris entre 1 et 20
  Et aucune lecture du catalogue n'est effectuée
  Exemples:
    | valeur |
    | 0      |
    | -1     |
    | 21     |
    | 1000   |

Scénario: limite non entière
  Quand list_available_books est appelé avec limit "abc" ou 2.5
  Alors le résultat est en erreur avec un message clair (pas de stack trace)

Scénario: aucun livre disponible
  Étant donné un catalogue vide ou sans exemplaire disponible
  Quand list_available_books est appelé
  Alors le résultat est un succès avec une liste vide

Scénario: outils exposés
  Quand un client MCP demande la liste des outils
  Alors il obtient exactement get_book et list_available_books
  Et chaque outil et chaque paramètre a une description non vide
  Et aucun outil d'écriture n'est exposé

Scénario: sans profil mcp-local, pas de JWT
  Étant donné l'application démarrée sans le profil "mcp-local"
  Quand une requête est envoyée sur /mcp sans en-tête Authorization
  Alors la réponse a le statut 401

Scénario: sans profil mcp-local, JWT invalide
  Étant donné l'application démarrée sans le profil "mcp-local"
  Quand une requête est envoyée sur /mcp avec un JWT expiré ou mal signé
  Alors la réponse a le statut 401

Scénario: sans profil mcp-local, JWT valide
  Étant donné l'application démarrée sans le profil "mcp-local"
  Quand une requête MCP est envoyée sur /mcp avec un JWT valide
  Alors elle n'est pas rejetée par la sécurité (pas de 401)

Scénario: avec profil mcp-local, sans JWT
  Étant donné l'application démarrée avec le profil "mcp-local"
  Quand une requête MCP est envoyée sur /mcp sans en-tête Authorization
  Alors elle n'est pas rejetée par la sécurité

Scénario: le profil mcp-local n'ouvre que /mcp
  Étant donné l'application démarrée avec le profil "mcp-local"
  Quand une requête est envoyée sans JWT sur POST /api/v1/books
  Alors la réponse a le statut 401

Scénario: le REST existant est inchangé
  Quand les tests de BookController existants sont rejoués
  Alors ils passent sans modification

Scénario: architecture
  Quand ArchitectureTest est exécuté
  Alors il passe sans modification, et aucune classe de adapter.in.mcp ne dépend de adapter.out, usecase ou port.out
```

### Tests exigés (rappel du besoin)
- **Unitaires des outils** : classes d'outils testées avec le port d'entrée **mocké** (Mockito) ; couvrent nominal, livre absent, ISBN invalide, limite absente / hors bornes, liste vide, correspondance DTO <-> champs du livre.
- **Unitaires des use cases** (couche application, logique métier selon la référence hexagonale) : normalisation/validation d'ISBN, filtre `availableCopies >= 1`, bornes de `limit`, tri, avec adapters de sortie factices (`InMemory...`).
- **Persistance** : test du nouvel accès en lecture (Testcontainers) : filtre disponibles, tri, limite.
- **Sécurité** : test qui prouve `/mcp` -> 401 sans le profil `mcp-local` (sans JWT et avec JWT invalide), et l'accès sans JWT avec le profil (contexte Spring avec profils actifs distincts), et que `/api/v1/books` reste en 401 avec le profil.

## Données (entités, champs, contraintes)

Aucune nouvelle table ni migration Flyway : les données lues sont celles de la table `book` existante. Un index sur `available_copies` n'est pas nécessaire à ce volume (à réévaluer si le catalogue grossit).

### Éléments à créer (niveau fonctionnel, le découpage fin revient à l'architecte)
| Élément | Rôle |
|---|---|
| Port d'entrée « consulter un livre par ISBN » | Reçoit un ISBN brut, normalise/valide via `Isbn`, renvoie le livre ou signale l'absence (nouvelle exception domaine `BookNotFound`, code `BOOK_NOT_FOUND`). |
| Port d'entrée « lister les livres disponibles » | Reçoit une limite (0 < limite <= 20), renvoie au plus `limite` livres disponibles triés. |
| Ports de sortie correspondants | Lecture par ISBN ; lecture des livres disponibles avec limite et tri. |

### DTO de sortie (adapter `mcp`)
Record `BookView` (nom indicatif) : `isbn` (String, 13 chiffres), `title` (String), `author` (String), `availableCopies` (int, >= 0), `totalCopies` (int, >= 1, >= availableCopies). Le même record sert aux deux outils. `list_available_books` renvoie une liste de ces records.

### Dépendances et configuration
- Dépendance `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, version gérée par le BOM `spring-ai-bom` (import dans `dependencyManagement`), dernière 2.0.x compatible Spring Boot 4.1 (à confirmer, voir Q5). Aucune version codée en dur sur le starter lui-même.
- Transport Streamable HTTP, endpoint `/mcp`, serveur de type synchrone, nom et version du serveur déclarés dans la configuration.
- Profil `mcp-local` : configuration dédiée qui ouvre uniquement `/mcp` ; aucun secret en clair.

## Hors périmètre
- Tout outil d'écriture (enregistrement, modification, suppression, emprunt, retour, prolongation).
- Outils liés au module `loan` (emprunts d'un membre, retards, disponibilité par membre).
- Recherche par titre/auteur/mot-clé, filtres, pagination par curseur, tri paramétrable.
- Ressources et prompts MCP (seulement des outils).
- Création du `GET /api/v1/books/...` REST (seuls les ports d'entrée sont partagés ; l'ajout éventuel d'un contrôleur REST de lecture est une autre feature).
- Authentification propre à MCP (OAuth 2 MCP, découverte d'autorisation), gestion de clés d'API, quotas / rate limiting.
- Transports SSE / STDIO.
- Interface utilisateur Angular.
- Internationalisation des messages (français uniquement).

## Questions ouvertes

1. **Valeur par défaut de `limit` quand elle est absente.** Le besoin demande de la proposer. Recommandation : **10** (confortable pour le contexte du modèle, bien sous le maximum de 20). Alternative : 20 (le maximum), qui maximise l'information mais consomme plus de contexte.

2. **Limite hors bornes (`<= 0` ou `> 20`) : erreur ou ajustement ?** Recommandation : **erreur explicite** (`isError`, message « limit doit être compris entre 1 et 20 ») pour `<= 0` **et** pour `> 20`, afin que le modèle sache que sa demande n'a pas été satisfaite telle quelle. Alternative : ramener silencieusement `> 20` à 20 (plus tolérant, mais le modèle croit avoir obtenu davantage de résultats qu'il n'en existe).

3. **Rôle exigé pour `/mcp` avec JWT.** Le besoin dit « exige un JWT comme le reste » sans préciser de rôle. Recommandation : **tout utilisateur authentifié** (`LIBRARIAN` ou `MEMBER`), car les données sont de la lecture de catalogue non sensible, cohérent avec `anyRequest().authenticated()`. Alternative : restreindre à `LIBRARIAN`.

4. **Activation du profil `mcp-local` et risque en production.** Recommandation : le profil n'est jamais activé par défaut, un avertissement est journalisé au démarrage (RM-MCP-11), et on envisage un garde-fou : refuser le démarrage si `mcp-local` est combiné à un profil de production (ex. `prod`). À confirmer : existe-t-il un nom de profil de production à interdire, et le garde-fou est-il souhaité maintenant ?

5. **Version exacte de Spring AI 2.0.x.** La dernière 2.0.x compatible avec Spring Boot 4.1.1 doit être vérifiée au moment du développement (notes de version / Maven Central), je ne la fige pas ici. Recommandation : importer `spring-ai-bom` en 2.0.x la plus récente validée, vérifier que `mvn dependency:tree` n'introduit pas de conflit avec Boot 4.1.1 (Jackson 3, Spring Security 7), et que la compilation, `ArchitectureTest` et les tests existants passent. Ce point est un prérequis, pas un choix métier.

6. **Exposer l'`id` technique du livre ?** Recommandation : **non**, l'ISBN est l'identifiant fonctionnel et l'assistant n'a pas besoin de l'UUID (moins de bruit, aucune fuite de détail interne). À valider.

7. **Ordre de `list_available_books`.** Recommandation : titre croissant (insensible à la casse), puis ISBN, pour un résultat stable et prévisible. Alternative : ordre d'enregistrement (nécessite une date de création absente du modèle actuel).

8. **Langue et format des messages d'erreur.** Recommandation : français (cohérent avec le projet), texte court sans code technique, avec le code interne (`BOOK_NOT_FOUND`, `ISBN_INVALID`, `LIMIT_OUT_OF_RANGE`) en préfixe facultatif. Alternative : anglais, plus naturel pour certains modèles. À confirmer.

9. **Livre absent : erreur d'outil ou résultat vide « non trouvé » ?** Le besoin dit « message clair, pas de stack trace ». Recommandation : résultat `isError = true` avec message explicite, ce qui distingue clairement l'absence d'une panne ; alternative : succès avec un champ `found=false` (mais un DTO d'un second type serait nécessaire).

10. **Nouvelle exception `BookNotFound` et son traitement REST.** Recommandation : l'exception de domaine est créée avec cette feature (code `BOOK_NOT_FOUND`) et l'adapter MCP la traduit ; aucun handler REST n'est ajouté tant qu'aucun contrôleur ne l'utilise. À confirmer avec l'architecte.

## Décisions

Spec validée par l'humain le 2026-11-12, avec les décisions suivantes (elles prévalent sur les recommandations des questions ouvertes) :

1. **`limit` par défaut = 10** quand le paramètre est absent.
2. **`limit` ≤ 0 ou > 20 → erreur explicite** (`isError = true`), pas de correction silencieuse.
3. **`/mcp` avec JWT : tout utilisateur authentifié** (`LIBRARIAN` ou `MEMBER`).
4. **Profil `mcp-local`** :
   - jamais actif par défaut ;
   - avertissement journalisé au démarrage quand il est actif ;
   - refus de démarrer si un profil actif s'appelle `prod` ;
   - `server.address=127.0.0.1` dans le profil `mcp-local`, pour que l'API n'écoute qu'en local.
5. **Version de Spring AI 2.0.x** vérifiée pendant le développement : dernière 2.0.x compatible avec Spring Boot 4.1, importée via `spring-ai-bom`.
6. **Pas d'`id` technique exposé** : l'ISBN suffit.
7. **Tri de `list_available_books`** : titre croissant sans tenir compte de la casse, puis ISBN.
8. **Messages d'erreur en français.**
9. **Livre absent → erreur d'outil** (`isError = true`) avec un message clair.
10. **`BookNotFoundException` créée dans le domaine**, sans gestion REST pour l'instant.
11. **Persistance** : les nouveaux ports de sortie sont implémentés par l'adapter de persistance existant. Réutiliser un port de sortie existant s'il charge déjà un livre par ISBN. Pas de nouvel adapter de persistance.
12. **Messages d'erreur (précision de la décision 8, après implémentation)** : messages métier en français ; erreurs de validation du protocole émises par le SDK. Le schéma typé des paramètres est conservé (`limit` entier, `isbn` obligatoire) : pour `limit` non entier ou `isbn` absent, le SDK MCP rejette l'appel avant le code de l'adapter, avec `isError = true`, sans stack trace, et un message du SDK (en anglais).
13. **Durcissement du profil `mcp-local` (après revue)** :
    - toute requête `/mcp` dont l'en-tête `Origin` est présent et n'est pas `http://localhost` ou `http://127.0.0.1` (n'importe quel port) est rejetée en 403 ; sans en-tête `Origin`, la requête est acceptée ;
    - le démarrage est refusé si l'adresse effective du serveur (`server.address`, y compris écrasée par `SERVER_ADDRESS`) n'est pas une adresse de bouclage.
14. **Durcissement final du profil `mcp-local` (troisième commit)** :
    - l'en-tête `Host` des requêtes `/mcp` est aussi validé : seuls `localhost` et `127.0.0.1` (port optionnel) sont acceptés, sinon 403 ;
    - le garde-fou d'adresse n'accepte que `127.0.0.1` et `localhost` (`::1` refusé), par cohérence avec les filtres `Origin` et `Host` ;
    - le message WARN de démarrage cite ces deux adresses.
    Fin du durcissement pour cette feature.

