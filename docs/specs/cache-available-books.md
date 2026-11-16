# Cache Redis de la liste des livres disponibles (module catalog)

## Contexte et objectif
La liste des livres empruntables (use case `ListAvailableBooks`, exposée aujourd'hui par l'outil MCP `list_available_books`) est lue en base à chaque appel. Elle change peu (seul l'enregistrement d'un livre la modifie pour l'instant) et peut être appelée souvent par des assistants IA. On la met en cache dans Redis pour éviter la lecture en base. Le cache accélère une lecture : il n'est jamais la source de vérité (référence `redis`, statut « non testée »).

Constat sur le code existant (`backend/src/main/java/com/example/library/`) :
- Port de sortie `LoadAvailableBooksPort.loadAvailableBooks(int maxResults)` : renvoie au plus `maxResults` livres avec `availableCopies >= 1`, triés par titre (casse ignorée) puis ISBN. Implémenté par `BookPersistenceAdapter` (qui implémente aussi `SaveBookPort`, `CheckIsbnRegisteredPort`, `LoadBookByIsbnPort`).
- `ListAvailableBooksService` (`@UseCase(readOnly = true)`) appelle ce port avec `query.limit().value()` puis applique `AvailableBooksSelection` (filtre, tri, troncature). `AvailableBooksLimit` : 1 à 20, défaut 10. Le seul paramètre de la requête est donc la **limite** ; il n'y a aucun filtre, aucune pagination, aucune notion d'utilisateur : le résultat est identique pour tous les appelants.
- Seul adapter entrant qui consomme cette liste : `catalog.adapter.in.mcp` (`CatalogMcpTools`). Aucun contrôleur REST de liste n'existe.
- Écritures qui modifient la liste : `RegisterBookService` -> `SaveBookPort.save` (`@UseCase` transactionnel ; `saveAndFlush`). Le module `loan` n'existe pas encore : aucun emprunt ni retour ne modifie `availableCopies` aujourd'hui. Le futur `loan` passera par un port d'entrée de `catalog` (RM-CAT-12) : ce chemin d'écriture devra invalider le cache (voir RC-17).
- Configuration : `spring.data.redis.*` (timeouts 2 s) et `app.cache.ttl.default: 10m` existent. Aucun bean Redis ni `CacheManager`. `TestcontainersConfiguration` déclare PostgreSQL **et** Redis ; elle est importée par des tests qui n'ont pas besoin de Redis (ex. `BookPersistenceAdapterTest`, `@DataJpaTest`).

## Acteurs et rôles (qui a le droit de faire quoi)
Aucun nouveau droit : la fonctionnalité est transparente pour les acteurs.

| Acteur | Effet |
|---|---|
| Client MCP authentifié (`LIBRARIAN` ou `MEMBER`, ou sans JWT avec le profil `mcp-local`) | Lit la liste ; sa requête réussit que Redis soit disponible ou non. Les règles d'accès de `catalog-mcp-server` sont inchangées. |
| `LIBRARIAN` qui enregistre un livre (`POST /api/v1/books`) | Déclenche l'invalidation du cache après le commit. Sa requête ne dépend pas de Redis. |
| Exploitant | Configure Redis et `app.cache.ttl` ; surveille les WARN de repli. |

## Règles métier (numérotées RC-01, RC-02…)
Les RM-01 à RM-07 (CLAUDE.md) et `RM-CAT-*`, `RM-MCP-*` restent en vigueur ; les règles propres à cette spec sont préfixées `RC-`.

**Comportement fonctionnel inchangé**
- **RC-01** : le contenu, l'ordre (titre sans casse, puis ISBN), la limite (1 à 20, défaut 10) et les erreurs de `list_available_books` sont identiques avec ou sans cache. Un livre à 0 exemplaire disponible n'apparaît jamais (RM-04, RM-MCP-06).
- **RC-02** : la liste mise en cache est celle que renvoie le port `LoadAvailableBooksPort` ; le cache n'ajoute, ne retire ni ne réordonne aucun livre.

**Emplacement et architecture**
- **RC-03** : le cache est un adapter sortant `catalog.adapter.out.cache` qui **décore** l'adapter de persistance : il implémente `LoadAvailableBooksPort`, lit le cache, sinon délègue à l'adapter de persistance puis écrit le cache. Aucune annotation de cache (`@Cacheable`, `@CacheEvict`, `@CachePut`) sur un use case, le domaine ou un port. Le use case `ListAvailableBooksService` et l'adapter MCP ne sont pas modifiés.
- **RC-04** : toute classe Redis (`RedisTemplate`, sérialiseur, etc.) est située dans `catalog.adapter.out.cache` ou dans `config`. `ArchitectureTest` passe sans modification.
- **RC-05** : lorsque le cache est actif, l'injection de `LoadAvailableBooksPort` dans le use case fournit le décorateur, et le décorateur reçoit l'adapter de persistance (pas de dépendance circulaire ni d'ambiguïté de bean). Le mécanisme est laissé à l'architecte.

**Clé, valeur, TTL**
- **RC-06** : clé conforme à la référence `redis` : `library:catalog:available-books:...:v1` (le détail de la partie variable dépend de la décision Q1). Le nom d'application et le module sont ceux de la référence (`library`, `catalog`). Clés en `String`.
- **RC-07** : chaque clé écrite porte un TTL strictement positif lu dans `app.cache.ttl` (par défaut `app.cache.ttl.default` = 10 m, voir Q4). Aucune clé sans expiration.
- **RC-08** : la valeur est du JSON écrit et lu avec un sérialiseur compatible Jackson 3 (`tools.jackson`) ; jamais la sérialisation Java native. La valeur est un `record` DTO de cache propre à l'adapter (ex. liste de `CachedBook`), jamais `Book`, `Isbn`, `BookId` ni entité JPA. Le DTO contient tous les champs nécessaires pour reconstruire un `Book` (dont l'identifiant, voir Q9). Les règles du domaine (validation de l'ISBN, bornes) s'appliquent à la reconstruction : une valeur incohérente est traitée comme illisible (RC-11).
- **RC-09** : un résultat vide est mis en cache comme un résultat valide, et se distingue d'une clé absente (Q3).
- **RC-10** : la clé comprend le suffixe de version `v1`. Tout changement de format de la valeur change ce suffixe ; on ne vide pas le cache pour migrer.

**Résilience**
- **RC-11** : aucune erreur de cache ne fait échouer la requête. Redis indisponible (connexion refusée, timeout), erreur de commande, JSON illisible ou valeur incohérente : la liste est lue via l'adapter de persistance, la requête réussit avec le même résultat, et un WARN est journalisé. Le WARN contient le type d'erreur et la clé, jamais la valeur, jamais de token ni de donnée personnelle. Une valeur illisible est écrasée dès qu'une lecture en base réussit.
- **RC-12** : les timeouts existants (2 s commande, 2 s connexion) bornent le surcoût d'une panne Redis. Le cache n'est écrit (une seule tentative, erreur ignorée avec WARN) que si la clé est **absente** ou si sa valeur est **illisible ou incohérente** ; si la lecture a échoué parce que **Redis est injoignable** (connexion, timeout, commande rejetée), aucune écriture n'est tentée (amendement D10).

**Invalidation et cohérence**
- **RC-13** : après l'enregistrement **réussi** d'un livre (après le commit de la transaction), l'entrée du cache de la liste est supprimée. Si la transaction est annulée (ISBN déjà existant, erreur de validation, exception), le cache n'est pas touché. L'invalidation n'a pas lieu avant le commit (sinon une lecture concurrente repeuplerait le cache avec l'ancien état).
- **RC-14** : l'invalidation supprime des clés connues (calculables) ; la commande `KEYS` n'est jamais utilisée. `SCAN` n'est pas nécessaire non plus si Q1 est suivie.
- **RC-15** : une erreur Redis pendant l'invalidation ne fait pas échouer l'enregistrement (le livre est déjà validé) : WARN journalisé, la liste restera potentiellement périmée jusqu'à la fin du TTL.
- **RC-16** : cohérence à terme assumée : une entrée en cache peut être en retard sur la base d'au plus un TTL (10 min par défaut), dans deux cas : invalidation en échec (RC-15) ; lecture concurrente d'une écriture qui repeuple le cache avec l'état d'avant le commit. Hors de ces cas, un livre enregistré apparaît dans la liste à la lecture suivante. Ce retard est jugé acceptable pour un catalogue (voir Q5 pour l'emprunt).
- **RC-17** : tout futur chemin qui modifie `availableCopies` ou ajoute un livre (emprunt, retour, ajout d'exemplaires du futur module `loan`) doit invalider ce cache ; **en pratique, tout futur port de sortie qui modifie la disponibilité est lui aussi décoré par l'adapter de cache, avec invalidation après commit** (D2). Cette obligation est consignée dans la spec du module `loan` et vérifiée à sa revue (non implémentable aujourd'hui).

**Observabilité et tests**
- **RC-18** : un WARN est journalisé à chaque repli (RC-11) et à chaque échec d'invalidation (RC-15). Aucun log de la valeur.
- **RC-19** : la configuration de test Redis est séparée de celle de PostgreSQL : un test qui n'utilise pas Redis (ex. `@DataJpaTest` de la persistance) ne démarre pas de conteneur Redis (Q6).
- **RC-20** : la liste en cache ne sert qu'à la lecture ; aucun cas d'utilisation d'écriture ne charge un `Book` depuis le cache (D9).

## Critères d'acceptation (Gherkin : Étant donné / Quand / Alors)

```gherkin
Scénario: Première lecture, cache vide
  Étant donné 3 livres disponibles en base et aucune entrée en cache
  Quand la liste des livres disponibles est demandée avec limit 10
  Alors la base est lue une fois
  Et le résultat contient les 3 livres dans l'ordre du catalogue
  Et une entrée existe en cache pour cette liste

Scénario: Deuxième lecture servie par le cache
  Étant donné la liste déjà lue une fois et mise en cache
  Quand la liste est demandée à nouveau
  Alors la base n'est pas interrogée une seconde fois
  Et le résultat est identique au premier

Scénario: La limite est respectée depuis le cache
  Étant donné 30 livres disponibles et la liste déjà en cache
  Quand la liste est demandée avec limit 5, puis avec limit 20
  Alors le premier résultat contient 5 livres, le second 20
  Et ce sont les premiers livres de l'ordre du catalogue
  Et le résultat est identique à celui obtenu sans cache

Scénario: Résultat vide mis en cache
  Étant donné aucun livre disponible
  Quand la liste est demandée deux fois
  Alors la base n'est lue qu'une fois
  Et les deux résultats sont des listes vides

Scénario: TTL positif
  Étant donné une lecture qui a rempli le cache
  Quand on consulte l'expiration de la clé dans Redis
  Alors le TTL est strictement positif et inférieur ou égal à la valeur de app.cache.ttl

Scénario: Format de la clé et de la valeur
  Étant donné une lecture qui a rempli le cache
  Alors la clé commence par "library:catalog:available-books:" et se termine par ":v1"
  Et la valeur est du JSON (pas de sérialisation Java native)
  Et la valeur ne contient aucune classe du domaine ni entité

Scénario: Invalidation après enregistrement d'un livre
  Étant donné la liste en cache sans le livre d'ISBN "9782070368228"
  Quand un LIBRARIAN enregistre ce livre avec 3 exemplaires (201)
  Alors l'entrée de cache de la liste est supprimée après le commit
  Et la lecture suivante interroge la base et contient le nouveau livre
  Et aucune commande KEYS n'a été émise

Scénario: Enregistrement refusé, cache conservé
  Étant donné la liste en cache et un livre d'ISBN "9782070368228" déjà enregistré
  Quand un LIBRARIAN enregistre de nouveau cet ISBN (409)
  Alors l'entrée de cache est conservée

Scénario: Pas d'invalidation avant le commit
  Étant donné une transaction d'enregistrement qui échoue après la sauvegarde
  Alors le cache n'est pas invalidé et la liste en cache reste valide

Scénario: Redis arrêté à la lecture
  Étant donné Redis indisponible
  Quand la liste est demandée
  Alors la liste est lue en base
  Et la requête réussit avec le même résultat qu'avec Redis
  Et un WARN est journalisé sans la valeur du cache
  Et aucune écriture du cache n'est tentée (D10)

Scénario: Redis arrêté à l'enregistrement
  Étant donné Redis indisponible
  Quand un LIBRARIAN enregistre un livre valide
  Alors la réponse est 201 et le livre est en base
  Et un WARN d'échec d'invalidation est journalisé

Scénario: Valeur illisible en cache
  Étant donné une valeur non JSON ou d'un format inattendu sous la clé de la liste
  Quand la liste est demandée
  Alors la liste est lue en base, la requête réussit, un WARN est journalisé
  Et l'entrée est remplacée par une valeur valide

Scénario: Expiration
  Étant donné une entrée expirée
  Quand la liste est demandée
  Alors elle est relue en base et remise en cache avec un nouveau TTL

Scénario: Architecture et non-régression
  Quand ArchitectureTest et les tests existants de catalog (use cases, MCP, web, persistance) sont exécutés
  Alors ils passent sans modification de leurs règles ni de leurs assertions
  Et aucun use case ni classe du domaine ne porte d'annotation de cache

Scénario: Slice de persistance sans Redis
  Étant donné un test @DataJpaTest de l'adapter de persistance
  Quand son contexte démarre
  Alors aucun conteneur Redis n'est lancé
```

### Tests exigés
- **Adapter de cache** (couche adapter sortant, base simulée par un double du port, Redis réel via Testcontainers) : 2e lecture sans appel à la base ; limites 5/10/20 depuis un même état ; résultat vide ; TTL > 0 et <= config ; format de clé ; JSON lisible ; valeur illisible ; invalidation après enregistrement ; pas d'invalidation si échec.
- **Résilience** : Redis arrêté ou client en erreur -> lecture réussie depuis la base ; WARN capturé (sans valeur) ; enregistrement réussi malgré l'échec d'invalidation.
- **Non-régression** : tests existants de `ListAvailableBooksService` et de l'adapter MCP inchangés.

## Données (entités, champs, contraintes)
Aucune table ni migration Flyway.

| Élément | Description |
|---|---|
| Clé Redis | `library:catalog:available-books:<partie variable selon Q1>:v1`, `String`, TTL obligatoire |
| DTO de cache `CachedBook` (record, nom indicatif) | `id` (UUID en texte), `isbn` (13 chiffres), `title`, `author`, `totalCopies`, `availableCopies` |
| Valeur | JSON : liste de `CachedBook` (ou enveloppe qui distingue « vide » de « absent », voir RC-09) |
| Configuration | `app.cache.ttl.default` (existant) ; éventuelle propriété dédiée selon Q4 ; `spring.data.redis.*` inchangé |

Données non sensibles (catalogue accessible à tout utilisateur authentifié) : aucune donnée personnelle ni token dans le cache.

## Hors périmètre
- Cache de `get_book` / `LoadBookByIsbnPort` ou de toute autre lecture.
- Invalidation par les emprunts et retours (module `loan` inexistant) : seulement consignée (RC-17).
- Module Spring Cache (`@Cacheable`, `CacheManager`) et `spring-boot-starter-cache`.
- Disjoncteur (circuit breaker), cache de secours en mémoire, préchauffage du cache, protection contre la ruée (cache stampede) au moment de l'expiration.
- Métriques de cache et tableau de bord ; le health check `redis` existant suffit.
- Mise à jour de la référence `redis` du kit.
- Retrait du health check `redis` du groupe de disponibilité (readiness) d'Actuator, pour qu'une panne du cache ne rende pas l'instance indisponible : **tâche séparée** (D13).
- Contrôleur REST de liste, modification des DTO MCP, de leurs descriptions ou du contrat des outils.

## Décisions
Spec validée par l'humain le 2026-11-16 ; recommandations retenues, avec les précisions suivantes.

- **D1 (Q1) — Une seule clé** `library:catalog:available-books:v1`, contenant les 20 premiers livres disponibles ; le décorateur renvoie les `maxResults` premiers. La liste en cache est **déjà triée selon l'ordre du domaine** (titre insensible à la casse, puis ISBN) : le décorateur ne retrie pas, il tronque.
- **D2 (Q2) — Décorateur sur `SaveBookPort`** : le décorateur de cache implémente aussi `SaveBookPort`, délègue à la persistance, puis supprime la clé **après commit** ; **immédiatement si aucune transaction n'est active**. Pas d'événement applicatif. RC-17 est étendue : tout futur port qui modifie la disponibilité devra aussi invalider ce cache.
- **D3 (Q3) — Liste vide en cache** sous la forme d'un **tableau JSON vide**, sans enveloppe : la distinction « clé absente » (`null` renvoyé par Redis → lecture en base) / « liste vide » (`[]` → résultat vide valide) suffit et doit être testée.
- **D4 (Q4) — TTL** : `app.cache.ttl.default` (10 m), sans nouvelle propriété.
- **D5 (Q5) — Retard jusqu'au TTL accepté** (RC-16), avec invalidation explicite exigée des futurs chemins d'écriture (RC-17).
- **D6 (Q6) — Configurations de test séparées** PostgreSQL / Redis ; les slices JPA n'importent que PostgreSQL. (`REDIS_PASSWORD=` est déjà défini dans `src/test/resources/application.properties` : les contextes de test démarrent sans la variable.)
- **D7 (Q7) — Pas de disjoncteur** ; étudier `ClientOptions.disconnectedBehavior(REJECT_COMMANDS)` de Lettuce pour **échouer immédiatement** quand Redis est déconnecté, au lieu d'attendre le timeout de commande (configuration dans `config`, à valider par l'architecte : effet sur la reconnexion automatique et sur le health check).
- **D8 (Q8) — Un WARN par échec**, sans limitation de débit.
- **D9 (Q9) — Reconstruction** : le DTO de cache contient l'`id` ; la reconstruction d'un `Book` réutilise **le chemin de reconstruction du mapper de persistance** (reconstitution d'un état existant), **jamais `Book.register`**.
- **RC-20 (nouvelle règle)** : la liste en cache ne sert **qu'à la lecture** (`LoadAvailableBooksPort`). Aucun cas d'utilisation d'écriture ne charge un `Book` depuis le cache ; les écritures lisent toujours la base.

Décisions prises à la validation du plan (2026-11-16) :
- **D10** — RC-12 amendée : pas d'écriture du cache quand la lecture a échoué parce que Redis est injoignable ; écriture seulement si la clé est absente ou la valeur illisible.
- **D11** — Timeouts Redis conservés à 2 s (commande et connexion).
- **D12** — Aucun contrôle de cohérence ajouté au domaine ; le codec de l'adapter de cache traite toute valeur incohérente (champ manquant, `availableCopies > totalCopies`, valeurs négatives…) comme illisible : repli sur la base puis remplacement.
- **D13** — Sortir le health check `redis` du groupe de disponibilité d'Actuator : tâche séparée, hors périmètre.

## Questions ouvertes (résolues, voir « Décisions »)
1. **Granularité de la clé selon la limite.** Le seul paramètre est `limit` (1 à 20). Recommandation : **une seule clé** `library:catalog:available-books:v1` qui contient toujours les 20 premiers livres de l'ordre du catalogue, le décorateur renvoyant les `maxResults` premiers (l'ordre déterministe fait de toute liste plus courte un préfixe de la plus longue). Une écriture, une suppression, pas de `KEYS` ni de `SCAN`, un taux de succès maximal. Alternative : une clé par limite (`...:limit-5:v1`), invalidation en supprimant les 20 clés possibles ; plus de mémoire et d'écritures. Conséquence : le premier appel lit 20 livres en base même si 5 sont demandés (négligeable).
2. **Qui décore l'écriture ?** L'invalidation doit se déclencher à l'enregistrement. Recommandation : le décorateur de cache implémente aussi `SaveBookPort` (délègue à la persistance, puis enregistre un callback `afterCommit` qui supprime la clé, et supprime immédiatement si aucune transaction n'est active). Le use case ne connaît pas le cache. Alternative : écouter un événement applicatif `BookRegistered` publié par le use case (plus découplé, mais ajoute un mécanisme d'événement). À trancher avec l'architecte.
3. **Mettre en cache une liste vide ?** Recommandation : **oui**, avec le même TTL (un catalogue vide ou épuisé est un état stable, et l'invalidation couvre l'ajout d'un livre). Alternative : ne pas la mettre en cache, plus simple mais chaque lecture retourne en base tant qu'il n'y a rien.
4. **TTL dédié ou commun ?** Recommandation : utiliser `app.cache.ttl.default` (10 m) pour démarrer, sans nouvelle propriété ; prévoir `app.cache.ttl.available-books` seulement si le besoin apparaît. Alternative : créer la propriété dédiée dès maintenant, avec repli sur `default`.
5. **Retard acceptable jusqu'au TTL.** La spec accepte jusqu'à 10 minutes de retard dans les cas d'échec d'invalidation et de course (RC-16). Quand `loan` existera, une liste périmée pourrait montrer un livre épuisé comme disponible (l'emprunt sera de toute façon refusé par RM-04 côté base). Recommandation : **accepté**, et `loan` invalide explicitement à chaque emprunt/retour (RC-17). À confirmer ; sinon, TTL plus court (ex. 1 à 2 minutes) pour cette clé.
6. **Redis hors des slices JPA.** Recommandation : scinder `TestcontainersConfiguration` en deux classes de configuration de test, l'une pour PostgreSQL et l'autre pour Redis (importée seulement par `@SpringBootTest` et les tests de cache). `BookPersistenceAdapterTest` n'importe plus que PostgreSQL. Vérifier que les `@SpringBootTest` existants (MCP, sécurité, `RedisIntegrationTest`) importent bien les deux. Point d'attention : `spring.data.redis.password: ${REDIS_PASSWORD}` n'a pas de valeur par défaut ; vérifier que les contextes de test démarrent sans cette variable (sinon, valeur vide par défaut en test).
7. **Redis en panne et latence.** Chaque requête paie jusqu'à 2 s de timeout tant que Redis est indisponible (pas de disjoncteur). Recommandation : accepter pour l'instant (hors périmètre) et ne traiter le disjoncteur qu'en cas de besoin constaté. Alternative : abaisser les timeouts à 500 ms.
8. **Politique de journalisation du repli.** Un WARN par requête en cas de panne prolongée peut inonder les logs. Recommandation : un WARN par échec, sans limitation de débit pour l'instant (cohérent avec la référence). Alternative : limiter à un WARN par minute.
9. **Reconstruction depuis le cache et identifiant.** Le DTO MCP n'expose pas l'`id`, mais le `Book` du domaine en a un. Recommandation : le DTO de cache contient l'`id` (`ListAvailableBooksService` manipule des `Book`). Vérifier avec l'architecte qu'il existe un moyen de reconstituer un `Book` sans repasser par `Book.register` (qui génère un nouvel identifiant et fixe `availableCopies = totalCopies`).
