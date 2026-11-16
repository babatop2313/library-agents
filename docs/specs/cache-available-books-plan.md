# Plan technique — Cache Redis de la liste des livres disponibles (module `catalog`)

> Spec : `docs/specs/cache-available-books.md` (RC-01 à RC-20, décisions D1 à D13).
> Plan produit par l'agent `architect`, **validé par l'humain le 2026-11-16** avec les décisions ci-dessous,
> qui sont intégrées dans le corps du plan et priment sur toute autre formulation.

## Décisions de l'humain à la validation du plan
| # | Décision | Effet sur le plan |
|---|---|---|
| D10 | **Pas d'écriture du cache quand la lecture a échoué parce que Redis est injoignable** (connexion, timeout, commande rejetée). Écriture seulement si la clé est **absente** ou la valeur **illisible/incohérente**. RC-12 amendée dans la spec. | Lecture à trois issues (§2.3.2, §3.6) ; tests adaptés (§7.3, §7.4) |
| D11 | Timeouts Redis conservés à **2 s** (commande et connexion). | `application.yml` inchangé |
| D12 | **Aucun contrôle de cohérence dans le domaine.** Le codec traite toute valeur incohérente (champ manquant ou `null`, `availableCopies > totalCopies`, valeurs négatives, livre non disponible…) comme **illisible** → repli sur la base puis remplacement. | Liste des cas illisibles du codec (§3.3) et tests (§7.3) |
| D13 | Sortir le health check `redis` du groupe de disponibilité d'Actuator : **tâche séparée**, hors périmètre (noté dans la spec). | Rien à faire ici |
| — | **Si la signature de `LettuceClientOptionsBuilderCustomizer` diffère de `void customize(ClientOptions.Builder)`, s'arrêter et le signaler à l'humain** (pas de contournement). | §2.5, §3.4, étape 5 de §10 |

## 0. En-tête
- **Architecture appliquée** : `hexagonal` (`.claude/factory.json`). Référence : `.claude/skills/architecture/references/hexagonal/REFERENCE.md` ; `roles.conf` : rôle `sortant-cache` → `adapter/out/cache`.
- **Conventions** : socle + profil `top` (aucune surcharge dans `stack.json`) → `mapping=manuel`, `lombok=hors-domaine`, `errors=problem-detail`, `ids=uuid`.
- **Intégrations** : `postgresql` et `redis` déjà déclarées dans `stack.json` ; **pas de `/add-integration`**. Référence `redis` en statut **« non testée »** : noms de classes vérifiés dans les jars de `~/.m2` (§8.1) ; une signature reste à confirmer à la compilation.

## 1. Résumé
Un adapter sortant `catalog.adapter.out.cache.AvailableBooksCacheAdapter` (`@Component @Primary`) **décore** l'adapter de persistance pour `LoadAvailableBooksPort` et `SaveBookPort`. Clé unique `library:catalog:available-books:v1`, valeur = tableau JSON (Jackson 3) des 20 premiers livres, tronqué à `maxResults` ; suppression de la clé **après commit** (immédiate sans transaction). Erreur Redis → repli base + WARN sans écriture (D10) ; valeur absente ou illisible → lecture base puis écriture. Domaine, application, adapter MCP et `ArchitectureTest` intacts. Seule nouveauté de configuration (globale) : `LettuceClientOptionsBuilderCustomizer` → `REJECT_COMMANDS`. Configuration de test scindée PostgreSQL / Redis.

## 2. Découpage par couche (ordre de la référence)

### 2.1 Domaine — aucune modification
Réutilisés : `Book.restore(...)` (chemin de reconstitution, D9 — **jamais `Book.register`**), `Isbn` (valide 13 chiffres, RM-07), `BookId`, `Book.isAvailable()` (RM-04), `AvailableBooksLimit.MAX` (= 20, D1).

### 2.2 Application — aucune modification
`LoadAvailableBooksPort`, `SaveBookPort`, `ListAvailableBooksService`, `RegisterBookService` inchangés (RC-03). Aucune annotation de cache dans `application` ni `domain`. `ListAvailableBooksService` continue d'appliquer `AvailableBooksSelection` sur ce que renvoie le port ; idempotent sur un préfixe trié (RC-01, RC-02).

### 2.3 Adapter sortant

#### 2.3.1 `adapter/out/persistence` — modification mineure
| Fichier | Modification | Raison |
|---|---|---|
| `backend/src/main/java/com/example/library/catalog/adapter/out/persistence/BookPersistenceAdapter.java` | `@Component` → `@Component("bookPersistenceAdapter")` ; rien d'autre | Nom de bean explicite, qualificatif du décorateur (§3.1) ; un renommage casse le démarrage de façon claire |

`BookPersistenceMapper` inchangé et **non importé** par le décorateur (package-private ; un adapter n'en appelle pas un autre). D9 respectée en empruntant le même chemin du domaine `Book.restore(new BookId(..), new Isbn(..), ...)`.

#### 2.3.2 `adapter/out/cache` — nouveau package (rôle `sortant-cache`), classes **package-private**
| Fichier | Responsabilité | Règles |
|---|---|---|
| `.../catalog/adapter/out/cache/package-info.java` | Javadoc ; rappel RC-17 : tout futur port modifiant `availableCopies` ou ajoutant un livre doit être décoré ici avec invalidation après commit | RC-17 |
| `.../adapter/out/cache/AvailableBooksCacheAdapter.java` | Décorateur `@Component @Primary` implémentant `LoadAvailableBooksPort` et `SaveBookPort` | RC-02, 03, 05, 06, 07, 09, 11–16, 18, 20 |
| `.../adapter/out/cache/CachedBook.java` | `record CachedBook(String id, String isbn, String title, String author, int totalCopies, int availableCopies)` — ni `Book`, ni `Isbn`, ni entité | RC-08 |
| `.../adapter/out/cache/CachedBookMapper.java` | Mapper manuel `Book` ↔ `CachedBook` ; reconstruit via `Book.restore` | RC-08, D9 |
| `.../adapter/out/cache/AvailableBooksCacheCodec.java` | `List<Book>` ↔ JSON avec un `JsonMapper` Jackson 3 **dédié** et strict ; contrôle de cohérence (D12) | RC-08–11, D12 |
| `.../adapter/out/cache/UnreadableCacheValueException.java` | `RuntimeException` package-private, message **sans la valeur** | RC-11, RC-18 |

**Signatures principales**
```java
@Component
@Primary
class AvailableBooksCacheAdapter implements LoadAvailableBooksPort, SaveBookPort {
    static final String KEY = "library:catalog:available-books:v1";
    static final int CACHED_SIZE = AvailableBooksLimit.MAX;            // 20 (D1)

    AvailableBooksCacheAdapter(
            @Qualifier("bookPersistenceAdapter") LoadAvailableBooksPort persistentLoad,
            @Qualifier("bookPersistenceAdapter") SaveBookPort persistentSave,
            StringRedisTemplate redis,
            @Value("${app.cache.ttl.default}") Duration ttl)           // null / zéro / négatif → IllegalStateException au démarrage (RC-07)

    @Override public List<Book> loadAvailableBooks(int maxResults)
    @Override public void save(Book book)
}

final class AvailableBooksCacheCodec {
    String encode(List<Book> books);      // tableau JSON de CachedBook ; [] si vide
    List<Book> decode(String json);       // UnreadableCacheValueException si illisible ou incohérente
}

final class CachedBookMapper {
    CachedBook toCache(Book book);
    Book toDomain(CachedBook cached);     // Book.restore(new BookId(UUID.fromString(id)), new Isbn(isbn), ...)
}
```
Codec et mapper instanciés **dans le constructeur** (pas injectés) : 4 dépendances, format indépendant de `spring.jackson.*`.

**Algorithme de lecture (D1, D10, RC-02, RC-09, RC-11, RC-12)** — la lecture du cache a **trois issues** :
```
loadAvailableBooks(maxResults):
  si maxResults < 1 ou maxResults > CACHED_SIZE :
      renvoyer persistentLoad.loadAvailableBooks(maxResults)      // hors contrat du port : sémantique de la base, cache non touché
  lecture = readCache()           // ne lève JAMAIS
      HIT(books)        : GET renvoie une valeur décodée ("[]" → HIT(liste vide), RC-09, D3)
      MISS              : GET renvoie null (absence normale, sans WARN)
                          OU valeur illisible/incohérente (WARN « lecture impossible », D12)
      UNAVAILABLE       : exception Redis (connexion, timeout, commande rejetée, erreur Lettuce) → WARN
  selon lecture :
      HIT(books)  → books
      MISS        → fromDb = persistentLoad.loadAvailableBooks(CACHED_SIZE)   // HORS try : erreur base propagée
                    writeCache(fromDb)   // SET key json PX ttl ; une seule tentative ; erreur → WARN (RC-12)
                                         // écrase une valeur illisible (RC-11)
                    → fromDb
      UNAVAILABLE → persistentLoad.loadAvailableBooks(CACHED_SIZE)            // AUCUNE écriture tentée (D10)
  renvoyer List.copyOf(books.subList(0, min(maxResults, books.size())))      // troncature sans re-tri (D1)
```
- Écriture : `redis.opsForValue().set(KEY, json, ttl)` → `SET ... PX` atomique, jamais de clé sans expiration (RC-07).
- Le décorateur ne retrie, ne filtre, n'ajoute rien (RC-02).
- Implémentation suggérée : un petit type interne (enum + liste, ou `sealed interface` package-private) pour les trois issues.

### 2.4 Adapter entrant — aucune modification de production
`CatalogMcpTools`, `BookController` inchangés. Seuls des tests changent (imports, nettoyage Redis — §7).

### 2.5 Config
| Fichier | Responsabilité | Règles |
|---|---|---|
| `backend/src/main/java/com/example/library/config/RedisClientConfig.java` (nouveau, **global**) | `@Configuration(proxyBeanMethods = false)` ; bean `LettuceClientOptionsBuilderCustomizer` → `disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)` | D7, RC-12 |
| `catalog/config/` | **Inchangé** (beans déclarés par annotation ; une déclaration manuelle forcerait des classes publiques) | — |
| `application.yml` | **Inchangé** (`spring.data.redis.*` à 2 s, D11 ; `app.cache.ttl.default: 10m`, D4) | RC-07, RC-12 |

## 3. Décisions techniques

### 3.1 Câblage du décorateur (RC-05)
`@Component @Primary` sur `AvailableBooksCacheAdapter` ; `@Qualifier("bookPersistenceAdapter")` sur ses deux dépendances de port.

| Type injecté | Candidats | Bean retenu | Raison |
|---|---|---|---|
| `LoadAvailableBooksPort` (ListAvailableBooksService) | décorateur, persistance | décorateur | `@Primary` |
| `SaveBookPort` (RegisterBookService) | décorateur, persistance | décorateur | `@Primary` |
| `CheckIsbnRegisteredPort` (RegisterBookService) | persistance | persistance | candidat unique |
| `LoadBookByIsbnPort` (GetBookByIsbnService) | persistance | persistance | candidat unique (`get_book` hors cache) |
| ports injectés dans le décorateur | décorateur, persistance | persistance | `@Qualifier` par nom de bean |

- Pas de cycle. Seuls les trois use cases consomment ces ports ; `CatalogMcpTools`/`BookController` n'injectent que des ports d'entrée ; tests de use case sans Spring (fake `InMemoryBookRepository` ou mocks).
- Slices : `@DataJpaTest` importe explicitement ses classes ; `@WebMvcTest` ne scanne pas les adapters sortants.
- Règle de dépendance : le décorateur dépend des ports et du domaine, pas de `adapter.out.persistence` (qualificatif en chaîne).
- Écartés : `@Fallback` sur la persistance (inverse la connaissance), auto-exclusion implicite (fragile), déclaration dans `catalog/config` (classes publiques).

### 3.2 Invalidation après commit (D2, RC-13, RC-15)
```java
@Override
public void save(Book book) {
    persistentSave.save(book);                        // exception propagée → aucun callback enregistré
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { evict(); }
        });
    } else {
        evict();                                      // aucune transaction : suppression immédiate (D2)
    }
}

private void evict() {
    try { redis.delete(KEY); }                        // DEL d'une clé connue ; jamais KEYS ni SCAN (RC-14)
    catch (RuntimeException e) { log.warn(...); }     // ne doit JAMAIS sortir d'afterCommit (RC-15)
}
```
- La transaction est ouverte par le proxy de `RegisterBookService` (`@UseCase` méta-annoté `@Transactional`) ; le décorateur s'exécute **dans** cette transaction, sans être transactionnel lui-même.
- Rollback : `AbstractPlatformTransactionManager` n'appelle `afterCommit()` qu'après un `doCommit()` réussi ; sur rollback ou échec de commit, seul `afterCompletion` est appelé (non implémenté) → clé conservée. Le chemin 409 normal lève avant même `save`.
- Le `try/catch` dans `afterCommit` est obligatoire : sinon le POST renverrait 500 alors que le livre est validé (RC-15).
- `isSynchronizationActive()` (condition exigée par `registerSynchronization`), couvre aussi `SUPPORTS`.
- `afterCommit` s'exécute avant `cleanupAfterCompletion` : un `DEL` lent retient la connexion JDBC (risque §9).

### 3.3 Sérialisation JSON Jackson 3 (D3, RC-08, D12)
`StringRedisTemplate` auto-configuré + `JsonMapper` Jackson 3 **dédié** dans le codec :
```java
private static final TypeReference<List<CachedBook>> TYPE = new TypeReference<>() {};
private final JsonMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();                                     // aucun activateDefaultTyping : jamais de "@class"
```
- Classes : `tools.jackson.databind.json.JsonMapper`, `tools.jackson.core.type.TypeReference`, `tools.jackson.core.JacksonException`. Features activées explicitement.
- `GenericJacksonJsonRedisSerializer` **écarté** (typage polymorphe : noms de classes dans la valeur, risque de gadgets). `JacksonJsonRedisSerializer<List<CachedBook>>` écarté (bean `RedisTemplate` dans `config` → `CachedBook` public, `JavaType` générique, erreurs enveloppées). `StringRedisTemplate` + codec : aucun nouveau bean, tout package-private, illisible ≠ absent explicite, valeur lisible par `redis-cli`, format lié à `v1`.

**Contrat clé / valeur**
| Élément | Valeur |
|---|---|
| Clé | `library:catalog:available-books:v1` (constante, `String`, UTF-8) — RC-06, RC-10, D1 |
| TTL | `app.cache.ttl.default` (10 m), posé par `SET ... PX` — RC-07, D4 |
| Valeur | tableau JSON d'au plus 20 objets, ordre du catalogue (titre insensible à la casse, puis ISBN) — D1 |
| Élément | `{"id":"<uuid>","isbn":"<13 chiffres>","title":"…","author":"…","totalCopies":n,"availableCopies":n}` |
| Liste vide | `[]` — D3 |
| Clé absente | `GET` → `null` → lecture base + écriture, sans WARN |

**Valeur illisible ou incohérente** (le codec lève `UnreadableCacheValueException` ; D12 : traité comme illisible → repli base puis remplacement) :
non-JSON ; littéral `null` ; objet au lieu de tableau ; propriété inconnue ; **propriété manquante** ; `null` dans un champ ; `id` non UUID ; ISBN invalide ; `title`/`author` `null` ou vide ; **`availableCopies > totalCopies`** ; **`totalCopies` ou `availableCopies` négatif**, `totalCopies < 1` ; élément `!book.isAvailable()` (RM-04) ; **plus de 20 éléments** ; doublon d'ISBN dans la liste. Le contrôle est fait **dans le codec** (adapter), pas dans le domaine. L'exception enveloppe la cause sans recopier son message.

### 3.4 Lettuce `REJECT_COMMANDS` (D7) — retenu
`org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer` (présent dans `spring-boot-data-redis-4.1.1.jar`) :
```java
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {
    /** D7 : connexion perdue → commandes rejetées immédiatement au lieu d'attendre le timeout de 2 s. */
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhenDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
```
- Signature attendue `void customize(ClientOptions.Builder)` — **non lue dans le bytecode : à confirmer à la compilation. Si elle diffère : s'arrêter et le signaler à l'humain.**
- **Ne pas** utiliser `LettuceClientConfigurationBuilderCustomizer` + `clientOptions(...)` : remplacerait les `ClientOptions` de Spring Boot (perte du `connect-timeout` 2 s et des `TimeoutOptions`).

| Situation | Sans (`ACCEPT_COMMANDS`) | Avec `REJECT_COMMANDS` |
|---|---|---|
| Redis perdu après connexion | file d'attente jusqu'au timeout (2 s), file non bornée, rejeu possible d'un `SET` | échec immédiat (`DataAccessException`) → repli instantané |
| Reconnexion automatique | active | **inchangée** (watchdog) |
| Premier appel, Redis jamais joignable | `RedisConnectionFailureException` (refus immédiat ou ≤ 2 s) | **identique** ; démarrage non bloqué |
| Coupure silencieuse (half-open) | timeout 2 s | identique |
| Health `redis` | DOWN après 2 s | DOWN immédiatement |

### 3.5 TTL, clé, taille, troncature, RC-20
- TTL : `@Value("${app.cache.ttl.default}") Duration ttl` ; `null`/≤ 0 → `IllegalStateException` au démarrage (RC-07). Pas de record `@ConfigurationProperties` (D4 : une seule valeur ; éviterait une dépendance adapter → `config`). Si plusieurs TTL apparaissent un jour : `com.example.library.config.CacheTtlProperties(@Name("default") Duration defaultTtl, …)`.
- Clé constante sans partie variable (D1) ; changement de `CachedBook` → `v2` (RC-10).
- La base est toujours lue avec `CACHED_SIZE` = 20 (D1).
- Troncature `subList(0, min(maxResults, size))` + `List.copyOf`, sans re-tri.
- RC-20 : le décorateur n'implémente que `LoadAvailableBooksPort` et `SaveBookPort` (délégation + invalidation) ; aucune écriture ne charge un `Book` depuis le cache.

### 3.6 Résilience (RC-11, RC-12 amendée par D10, RC-15, RC-18)
Les `try` ne couvrent **que** les opérations de cache ; `persistentLoad`/`persistentSave` sont hors `try` (erreur base propagée).

| Opération | `catch` | Issue |
|---|---|---|
| `GET` | `RuntimeException` (bloc limité à `opsForValue().get(KEY)`) | **UNAVAILABLE** → base, WARN lecture, **pas d'écriture** (D10). Couvre `RedisConnectionFailureException`, `QueryTimeoutException`, `RedisSystemException` (dont « Commands are rejected »), toute exception Lettuce non traduite |
| `decode` | `UnreadableCacheValueException` (enveloppe `JacksonException`, `InvalidIsbnException`, `IllegalArgumentException`, `NullPointerException`, incohérences D12) | **MISS** → base, WARN lecture, **écriture** pour remplacer (RC-11) |
| `encode` + `SET` | `RuntimeException` | WARN écriture ; une seule tentative (RC-12) |
| `DEL` | `RuntimeException` | WARN invalidation (RC-15) |

`RuntimeException` plutôt que `DataAccessException` seule : RC-11 exige qu'aucune erreur de cache ne fasse échouer la requête ; le bloc protégé ne contient que du code de cache.

**WARN** (SLF4J, français, un par échec, sans limitation — D8) :
- `"Lecture du cache impossible, repli sur la base : key={}, cause={}"`
- `"Valeur du cache illisible, repli sur la base et remplacement : key={}, cause={}"`
- `"Écriture du cache impossible : key={}, cause={}"`
- `"Invalidation du cache impossible, liste potentiellement périmée jusqu'à expiration : key={}, cause={}"`

`cause = e.getClass().getSimpleName()` (+ nom simple de la cause d'origine pour `UnreadableCacheValueException`). **Jamais `e.getMessage()` ni l'exception en dernier argument** (les messages Jackson citent un extrait du contenu).

Surcoût maximal par appel, Redis injoignable : connexion perdue ≈ 0 ms (`REJECT_COMMANDS`) ; Redis jamais joint ≤ 2 s (connexion) — plus d'écriture supplémentaire grâce à D10.

## 4. Contrat d'API — inchangé (RC-01)
| Méthode | URL / outil | Rôle | Effet de la feature |
|---|---|---|---|
| POST | `/api/v1/books` | `LIBRARIAN` | supprime la clé après commit ; reste 201 si Redis indisponible (WARN) |
| POST | `/mcp`, `list_available_books` | `LIBRARIAN`/`MEMBER` (ou profil `mcp-local`) | lecture servie par le cache, repli base |
| POST | `/mcp`, `get_book` | idem | aucun (hors cache) |

## 5. Migration — aucune
Schéma inchangé ; dernière migration `V1__create_table_book.sql`.

## 6. Frontend — aucun

## 7. Tests (section « Tests par couche » de la référence)

### 7.1 Séparation de la configuration de test (RC-19, D6)
| Fichier | Contenu |
|---|---|
| `backend/src/test/java/com/example/library/PostgresTestcontainersConfiguration.java` (nouveau) | `@TestConfiguration(proxyBeanMethods = false)`, `@ServiceConnection PostgreSQLContainer` (`postgres:16-alpine`) |
| `backend/src/test/java/com/example/library/RedisTestcontainersConfiguration.java` (nouveau) | `@TestConfiguration(proxyBeanMethods = false)`, `@ServiceConnection(name = "redis") GenericContainer<?>` (`redis:7-alpine`, 6379), constante publique `REDIS_IMAGE`, bean d'aide `RedisTestCache.flushAll()` via `execInContainer("redis-cli", "FLUSHALL")` |
| `backend/src/test/java/com/example/library/TestcontainersConfiguration.java` | **supprimé** → `./mvnw clean test` obligatoire |

`RedisTestCache` passe par `execInContainer` (pas d'import `org.springframework.data.redis` hors `adapter/out/cache` et `config`, contrôlé par `guard-architecture.sh`).

| Test | `@Import` |
|---|---|
| `LibraryApplicationTests`, `config/RedisIntegrationTest`, `config/security/McpSecurityTest`, `config/security/McpLocalSecurityTest` | `{PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class}` (même ordre partout → cache de contextes partagé) |
| `catalog/adapter/in/mcp/CatalogMcpServerIntegrationTest` | les deux + `RedisTestCache.flushAll()` en `@BeforeEach`/`@AfterEach` (insère en SQL direct, sans invalidation) — **aucune assertion modifiée** |
| `catalog/adapter/out/persistence/BookPersistenceAdapterTest` | `{PostgresTestcontainersConfiguration.class, BookPersistenceAdapter.class, BookPersistenceMapper.class}` → **plus de Redis** (RC-19) |
| nouveaux `@SpringBootTest` du cache | les deux |

### 7.2 Domaine et application
Aucun nouveau test ; non-régression sans modification de `ListAvailableBooksServiceTest`, `RegisterBookServiceTest`, `GetBookByIsbnServiceTest`, `BookTest`, `IsbnTest`, `AvailableBooksSelectionTest`, `AvailableBooksLimitTest`.

### 7.3 Adapter cache — unitaires et Redis réel, sans contexte Spring
Pas de `@DataRedisTest` disponible → JUnit 5 + `@Testcontainers`, `@Container static GenericContainer<?>` (`RedisTestcontainersConfiguration.REDIS_IMAGE`), `LettuceConnectionFactory` + `StringRedisTemplate` construits à la main ; « base » = `InMemoryBookRepository` (`application/fake`, compteurs `loadAvailableCalls()`, `lastMaxResults()`) ; WARN capturés par `OutputCaptureExtension`. Package `com.example.library.catalog.adapter.out.cache`.

**`AvailableBooksCacheCodecTest`** (pur)
- `encode_quandListeVide_alorsTableauJsonVide` ; `decode_quandTableauVide_alorsListeVideEtNonAbsence` (RC-09, D3)
- `encodeDecode_quandTroisLivres_alorsMemesChampsMemeOrdreMemeId` (RC-02, RC-08, D9 : `id` et `availableCopies ≠ totalCopies` conservés)
- `encode_quandLivres_alorsChampsExactsSansClasseNiTypage` (RC-08)
- `decode_quand{TexteNonJson|LitteralNull|ObjetAuLieuDeTableau|ProprieteInconnue|ProprieteManquante|ChampNull|UuidInvalide|IsbnInvalide|TitreNull|DisponiblesSuperieursAuTotal|ExemplairesNegatifs|LivreSansExemplaireDisponible|PlusDeVingtElements|IsbnEnDouble}_alorsUnreadableCacheValueException` (RC-08, RC-11, D12)
- `decode_quandIllisible_alorsMessageDExceptionSansLaValeur` (RC-18)

**`AvailableBooksCacheAdapterTest`** (Redis réel)
| Test | Règle |
|---|---|
| `loadAvailableBooks_quandCacheVide_alorsBaseLueUneFoisAvecVingtEtCleEcrite` | RC-02, D1 |
| `loadAvailableBooks_quandDeuxiemeLecture_alorsBaseNonRelueEtResultatIdentique` | demande « 2e lecture sans appel à la base » |
| `loadAvailableBooks_quandTrenteLivresEtLimites5Puis10Puis20_alorsPrefixesIdentiquesSansCache` | D1, RC-01 |
| `loadAvailableBooks_quandAucunLivre_alorsValeurTableauVideEtBaseLueUneFois` (2e lecture servie par `[]`) | RC-09, D3 |
| `loadAvailableBooks_quandCleEcrite_alorsTtlPositifEtInferieurOuEgalAuTtlConfigure` | RC-07 (TTL > 0) |
| `loadAvailableBooks_quandCleEcrite_alorsCleExacteEtValeurJsonLisible` | RC-06, RC-08, RC-10 |
| `loadAvailableBooks_quandValeurNonJson_alorsRepliBaseWarnSansValeurEtValeurRemplacee` | RC-11, RC-18 |
| `loadAvailableBooks_quandValeurIncoherente_alorsRepliBaseEtValeurRemplacee` (`{"foo":1}`, ISBN invalide, `availableCopies > totalCopies`) | RC-08, RC-11, D12 |
| `loadAvailableBooks_quandCleExpiree_alorsRelueEnBaseEtNouveauTtl` (Awaitility) | RC-07 |
| `loadAvailableBooks_quandLimiteHorsContrat_alorsDelegationDirecteSansCache` | contrat du port |
| `save_quandAucuneTransaction_alorsCleSupprimeeImmediatement` | D2 |
| `save_quandSynchronisationActive_alorsCleConserveeJusquAuCommitPuisSupprimee` (`initSynchronization` / `triggerAfterCommit` / `clearSynchronization` en `finally`) | RC-13 |
| `save_quandSynchronisationPuisRollback_alorsCleConservee` (`triggerAfterCompletion(STATUS_ROLLED_BACK)`) | RC-13 |
| `save_quandPersistanceEchoue_alorsExceptionPropageeEtCleConservee` | RC-13 |
| `save_quandInvalidation_alorsAucuneCommandeKeys` (`CONFIG RESETSTAT` puis `INFO commandstats` sans `cmdstat_keys`/`cmdstat_scan`) | RC-14 |

**`AvailableBooksCacheAdapterResilienceTest`**
- Redis injoignable : `LettuceConnectionFactory` vers un **port fermé** (`ServerSocket(0)` ouvert puis fermé), `connectTimeout` 500 ms dans le test (Windows).
  - `loadAvailableBooks_quandRedisInjoignable_alorsResultatDeLaBaseUnWarnLectureEtAucuneEcriture` — résultat identique au fake, **un seul WARN (lecture), aucun WARN d'écriture** (D10, RC-11, RC-18)
  - `save_quandRedisInjoignableSansTransaction_alorsSuccesEtWarnInvalidation` (RC-15)
  - `save_quandRedisInjoignableDansAfterCommit_alorsAucuneExceptionPropagee` (RC-15)
- Client en erreur (Mockito sur `StringRedisTemplate`/`ValueOperations`) : `QueryTimeoutException`, `RedisSystemException`, `IllegalStateException` sur `get` → repli base + WARN **et `set` jamais appelé** (D10) ; valeur illisible renvoyée par `get` → `set` appelé une fois (remplacement) ; exception sur `set` → WARN, résultat de la base.
- Optionnel si stable : `loadAvailableBooks_quandRedisArreteApresConnexion_alorsRepliImmediat` (conteneur dédié, `REJECT_COMMANDS`, `container.stop()`).

### 7.4 Adapter cache — intégration Spring
**`.../catalog/adapter/out/cache/AvailableBooksCacheIntegrationTest.java`** (`@SpringBootTest`, deux imports, nettoyage `book` + `flushAll()` avant/après) :
- `contexte_quandCacheActif_alorsPortsLectureEtSauvegardeSontLeDecorateur` (et `CheckIsbnRegisteredPort`/`LoadBookByIsbnPort` ne le sont pas) — RC-05
- `save_quandTransactionCommitee_alorsCleSupprimeeApresCommitEtPasAvant` (`TransactionTemplate`, vrai `JpaTransactionManager`) — RC-13
- `save_quandTransactionAnnuleeApresSauvegarde_alorsCleConserveeEtLivreAbsent` (`setRollbackOnly()`) — RC-13
- `register_quandLivreValide_alorsCleSupprimeeEtLectureSuivanteContientLeLivre` (via `RegisterBookUseCase` puis `ListAvailableBooksUseCase`) — RC-13, RC-16
- `register_quandIsbnDejaEnregistre_alorsIsbnAlreadyExistsEtCleConservee`

**`.../catalog/adapter/out/cache/AvailableBooksCacheRedisDownIntegrationTest.java`** (`@SpringBootTest` + MockMvc, `@Import(PostgresTestcontainersConfiguration.class)` seul, `@DynamicPropertySource` → `127.0.0.1` + port fermé, **jamais 6379**) :
- `postBooks_quandRedisIndisponible_alors201LivreEnBaseEtWarnInvalidation` (JWT `LIBRARIAN` via `jwt()`) — RC-15
- `listAvailable_quandRedisIndisponible_alorsMemeResultatQueLaBaseEtWarnSansEcriture` — RC-11, D10

### 7.5 Adapter entrant
`CatalogMcpToolsTest`, `CatalogMcpToolsDescriptionTest`, `BookControllerTest` inchangés ; `CatalogMcpServerIntegrationTest`, `McpSecurityTest`, `McpLocalSecurityTest` : imports (+ vidage Redis pour le premier) uniquement.

### 7.6 Config
- `backend/src/test/java/com/example/library/config/RedisClientConfigTest.java` : `customize_quandApplique_alorsDisconnectedBehaviorRejectCommands`.
- `RedisIntegrationTest` (+1 méthode, les deux existantes inchangées) : `clientOptions_quandContexteDemarre_alorsRejectCommandsEtConnectTimeoutConserve` (`LettuceConnectionFactory.getClientConfiguration().getClientOptions()` → `REJECT_COMMANDS` et `getSocketOptions().getConnectTimeout()` = 2 s).

### 7.7 Architecture
- `ArchitectureTest` **non modifié**, doit rester vert.
- Nouveau `backend/src/test/java/com/example/library/CacheArchitectureTest.java` (ArchUnit, `DoNotIncludeTests`), règles **ajoutées** :
  - `..domain..` et `..application..` ne dépendent pas de `org.springframework.cache..`, `org.springframework.data.redis..`, `io.lettuce..` ;
  - hors `..adapter.out.cache..` et `..config..`, aucune dépendance à `org.springframework.data.redis..` ni `io.lettuce..` (RC-04) ;
  - aucune classe annotée `@Cacheable`, `@CacheEvict`, `@CachePut` (RC-03).

## 8. Intégrations

### 8.1 Vérifications (référence redis « non testée »)
| Élément | Vérifié dans `~/.m2` | Usage |
|---|---|---|
| `org.springframework.data.redis.core.StringRedisTemplate` (auto-configuré par `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`) | oui | GET / SET / DEL |
| `JacksonJsonRedisSerializer`, `GenericJacksonJsonRedisSerializer` | oui | **non retenus** |
| `RedisConnectionFailureException`, `RedisSystemException`, `SerializationException` | oui | `catch` |
| `org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer` | oui | D7 — **signature à confirmer ; si différente, arrêt** |
| `org.springframework.boot.data.redis.health.DataRedisHealthIndicator` | oui | health existant |
| `io.lettuce.core.ClientOptions$DisconnectedBehavior` | oui (lettuce-core 7.5.2) | `REJECT_COMMANDS` |
| `tools.jackson.core.JacksonException`, `tools.jackson.core.type.TypeReference` | oui (jackson-core 3.1.7) | codec |
| `org.springframework.boot.test.system.OutputCaptureExtension` | oui | WARN |
| `@DataRedisTest` | **absent** | JUnit + Testcontainers |

Aucune nouvelle dépendance Maven.

### 8.2 Rôles
| Classe | Rôle | Package |
|---|---|---|
| `AvailableBooksCacheAdapter`, `AvailableBooksCacheCodec`, `CachedBook`, `CachedBookMapper`, `UnreadableCacheValueException` | `sortant-cache` | `catalog/adapter/out/cache` |
| `RedisClientConfig` | configuration technique | `config` (global) |
| `BookPersistenceAdapter` (nom de bean) | `sortant-persistance` | `catalog/adapter/out/persistence` |

### 8.3 Résilience
Redis indisponible → repli base + WARN, **sans écriture** (D10) ; valeur absente/illisible → base puis une écriture (RC-12) ; invalidation en échec → WARN, retard ≤ 1 TTL (RC-15, RC-16). `SET ... PX` et `DEL` idempotents. Invalidation seulement dans `afterCommit` ; jamais d'écriture du cache depuis une écriture métier (RC-20).

## 9. Risques
| Risque | Gravité | Mesure |
|---|---|---|
| Appel Redis dans la transaction lecture seule de `ListAvailableBooksService` : panne → connexion JDBC retenue jusqu'à 2 s | Moyenne | `REJECT_COMMANDS`, timeouts 2 s (D11), plus d'écriture après échec de connexion (D10). Déplacer hors transaction exigerait de toucher le use case (interdit par RC-03) |
| `DEL` dans `afterCommit` avant libération de la connexion JDBC | Faible | idem |
| Un succès de cache n'économise pas l'ouverture de transaction | Faible | accepté (RC-03) |
| Pollution entre tests partageant contexte et conteneur Redis | Élevée si oubliée | `RedisTestCache.flushAll()` (§7.1) |
| Qualificatif par chaîne | Faible | nom de bean explicite + test de câblage |
| Health `redis` DOWN → `/actuator/health` DOWN | Moyenne (préexistant) | tâche séparée (D13) |
| Course lecture/écriture repeuplant l'ancien état | Faible | accepté (RC-16, D5) |
| Futur `loan` modifiant `availableCopies` sans décorateur | Moyenne (futur) | RC-17 (`package-info`, spec `loan`, revue) |
| Lenteur des tests de résilience sous Windows | Faible | `connectTimeout` 500 ms dans les fabriques de test |

## 10. Ordre d'implémentation
1. **Domaine** : rien.
2. **Application** : rien.
3. **Adapter sortant** :
   1. `BookPersistenceAdapter` → `@Component("bookPersistenceAdapter")` ;
   2. `adapter/out/cache` : `package-info`, `CachedBook`, `CachedBookMapper`, `UnreadableCacheValueException`, `AvailableBooksCacheCodec`, `AvailableBooksCacheAdapter` ;
   3. `./mvnw -q compile`.
4. **Adapter entrant** : rien en production.
5. **Config** : `config/RedisClientConfig` ; `./mvnw -q compile` → confirme la signature du customizer. **Si elle diffère : arrêt et signalement à l'humain.**
6. **Tests** (agent `test-writer`) : §7.1 (scission, suppression de `TestcontainersConfiguration`, 6 imports, vidage Redis) → §7.3 → §7.4 → §7.6 → §7.7.
7. **Fin** : `cd backend && ./mvnw clean test` (clean obligatoire après suppression d'un fichier Java), `ArchitectureTest` compris.

Règle de dépendance : `adapter.out.cache` → `application.port.out`, `domain`, Spring Data Redis, Spring TX, Jackson 3 ; pas de `adapter.in`, `application.usecase`, `application.port.in`, `adapter.out.persistence`. Aucune violation.
