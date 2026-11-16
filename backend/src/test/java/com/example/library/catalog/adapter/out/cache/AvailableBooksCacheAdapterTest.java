package com.example.library.catalog.adapter.out.cache;

import static com.example.library.catalog.adapter.out.cache.AvailableBooksCacheAdapter.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;

/**
 * Décorateur de cache contre un Redis réel (Testcontainers), sans contexte Spring ; la « base » est le fake
 * {@link InMemoryBookRepository}. Couvre RC-02, RC-06 à RC-10, RC-11, RC-13, RC-14, RC-18, D1, D2, D3, D12.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AvailableBooksCacheAdapterTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(RedisTestcontainersConfiguration.REDIS_IMAGE)).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final JsonMapper json = JsonMapper.builder().build();
    private InMemoryBookRepository database;
    private AvailableBooksCacheAdapter adapter;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        run(connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        database = new InMemoryBookRepository();
        adapter = new AvailableBooksCacheAdapter(database, database, redis, TTL);
    }

    @AfterEach
    void cleanUp() {
        // aucune synchronisation de transaction ne doit survivre à un test
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static <T> T run(RedisCallback<T> callback) {
        return redis.execute(callback);
    }

    private static Book book(int index) {
        return Book.register(Isbn.of("978000000%04d".formatted(index)), "Livre %02d".formatted(index), "Auteur", 2);
    }

    private void givenBooksInDatabase(int count) {
        for (int i = 0; i < count; i++) {
            database.givenExisting(book(i));
        }
    }

    private static List<UUID> ids(List<Book> books) {
        return books.stream().map(book -> book.getId().value()).toList();
    }

    // ---------------------------------------------------------------- lecture

    @Test
    void loadAvailableBooks_quandCacheVide_alorsBaseLueUneFoisAvecVingtEtCleEcrite() {
        // Given : 3 livres en base, aucune entrée en cache
        givenBooksInDatabase(3);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then
        assertThat(result).hasSize(3);
        assertThat(result).extracting(Book::getTitle).containsExactly("Livre 00", "Livre 01", "Livre 02");
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
        assertThat(database.lastMaxResults()).isEqualTo(20);
        assertThat(redis.hasKey(KEY)).isTrue();
    }

    @Test
    void loadAvailableBooks_quandDeuxiemeLecture_alorsBaseNonRelueEtResultatIdentique() {
        // Given : une première lecture a rempli le cache
        givenBooksInDatabase(3);
        List<Book> first = adapter.loadAvailableBooks(10);
        // When
        List<Book> second = adapter.loadAvailableBooks(10);
        // Then
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
        assertThat(ids(second)).isEqualTo(ids(first));
        assertThat(second).extracting(Book::getTitle).isEqualTo(first.stream().map(Book::getTitle).toList());
    }

    @Test
    void loadAvailableBooks_quandTrenteLivresEtLimites5Puis10Puis20_alorsPrefixesIdentiquesSansCache() {
        // Given : 30 livres disponibles, un seul état en base
        givenBooksInDatabase(30);
        // When : trois limites lues depuis le même cache
        List<Book> five = adapter.loadAvailableBooks(5);
        List<Book> ten = adapter.loadAvailableBooks(10);
        List<Book> twenty = adapter.loadAvailableBooks(20);
        // Then : un seul appel à la base, tailles respectées, résultat identique à la lecture directe
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
        assertThat(five).hasSize(5);
        assertThat(ten).hasSize(10);
        assertThat(twenty).hasSize(20);
        assertThat(ids(five)).isEqualTo(ids(database.loadAvailableBooks(5)));
        assertThat(ids(ten)).isEqualTo(ids(database.loadAvailableBooks(10)));
        assertThat(ids(twenty)).isEqualTo(ids(database.loadAvailableBooks(20)));
        assertThat(ids(twenty).subList(0, 5)).isEqualTo(ids(five));
    }

    @Test
    void loadAvailableBooks_quandAucunLivre_alorsValeurTableauVideEtBaseLueUneFois() {
        // Given : catalogue vide
        // When
        List<Book> first = adapter.loadAvailableBooks(10);
        List<Book> second = adapter.loadAvailableBooks(10);
        // Then : D3, « [] » est un résultat valide, distinct d'une clé absente
        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("[]");
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
    }

    @Test
    void loadAvailableBooks_quandCleEcrite_alorsTtlPositifEtInferieurOuEgalAuTtlConfigure() {
        // Given
        givenBooksInDatabase(2);
        // When
        adapter.loadAvailableBooks(10);
        Long ttlMillis = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        // Then : RC-07, jamais de clé sans expiration (-1) ni absente (-2)
        assertThat(ttlMillis).isNotNull().isPositive().isLessThanOrEqualTo(TTL.toMillis());
    }

    @Test
    void loadAvailableBooks_quandCleEcrite_alorsCleExacteEtValeurJsonLisible() {
        // Given
        givenBooksInDatabase(2);
        // When
        adapter.loadAvailableBooks(10);
        String value = redis.opsForValue().get(KEY);
        // Then : RC-06, RC-08, RC-10
        assertThat(KEY).isEqualTo("library:catalog:available-books:v1");
        assertThat(redis.keys("*")).containsExactly(KEY);
        assertThat(value).isNotNull().startsWith("[").doesNotContain("@class", "com.example");
        JsonNode root = json.readTree(value);
        assertThat(root).hasSize(2);
        assertThat(root.get(0).get("title").asString()).isEqualTo("Livre 00");
        assertThat(root.get(0).get("isbn").asString()).isEqualTo("9780000000000");
        assertThat(root.get(0).has("id")).isTrue();
    }

    @Test
    void loadAvailableBooks_quandValeurNonJson_alorsRepliBaseWarnSansValeurEtValeurRemplacee(
            CapturedOutput output) {
        // Given : une valeur illisible contenant un marqueur
        givenBooksInDatabase(3);
        redis.opsForValue().set(KEY, "pas du json MARQUEUR-SECRET", TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then : repli sur la base, WARN sans la valeur, entrée remplacée par une valeur valide (RC-11, RC-18)
        assertThat(result).hasSize(3);
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
        assertThat(output.getAll()).contains("Valeur du cache illisible").contains(KEY)
                .doesNotContain("MARQUEUR-SECRET");
        assertThat(new AvailableBooksCacheCodec().decode(redis.opsForValue().get(KEY))).hasSize(3);
        assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"foo\":1}",
            "[{\"id\":\"6f1b2c3d-0000-4000-8000-000000000001\",\"isbn\":\"123\",\"title\":\"T\",\"author\":\"A\","
                    + "\"totalCopies\":3,\"availableCopies\":2}]",
            "[{\"id\":\"6f1b2c3d-0000-4000-8000-000000000001\",\"isbn\":\"9782070368228\",\"title\":\"T\","
                    + "\"author\":\"A\",\"totalCopies\":2,\"availableCopies\":3}]"})
    void loadAvailableBooks_quandValeurIncoherente_alorsRepliBaseEtValeurRemplacee(String incoherent) {
        // Given : D12, une valeur incohérente est traitée comme illisible
        givenBooksInDatabase(2);
        redis.opsForValue().set(KEY, incoherent, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then
        assertThat(result).hasSize(2);
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
        assertThat(redis.opsForValue().get(KEY)).isNotEqualTo(incoherent);
        assertThat(new AvailableBooksCacheCodec().decode(redis.opsForValue().get(KEY))).hasSize(2);
    }

    @Test
    void loadAvailableBooks_quandCleExpiree_alorsRelueEnBaseEtNouveauTtl() {
        // Given : un TTL très court pour ce test
        adapter = new AvailableBooksCacheAdapter(database, database, redis, Duration.ofSeconds(1));
        givenBooksInDatabase(2);
        adapter.loadAvailableBooks(10);
        await().atMost(Duration.ofSeconds(10)).until(() -> !Boolean.TRUE.equals(redis.hasKey(KEY)));
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then
        assertThat(result).hasSize(2);
        assertThat(database.loadAvailableCalls()).isEqualTo(2);
        assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void loadAvailableBooks_quandLimiteHorsContrat_alorsDelegationDirecteSansCache() {
        // Given : un port de base simulé (le fake refuserait une limite hors 1 à 20)
        LoadAvailableBooksPort persistence = mock(LoadAvailableBooksPort.class);
        when(persistence.loadAvailableBooks(anyInt())).thenReturn(List.of(book(1)));
        AvailableBooksCacheAdapter outOfContract = new AvailableBooksCacheAdapter(persistence, database, redis, TTL);
        // When
        List<Book> tooBig = outOfContract.loadAvailableBooks(25);
        List<Book> zero = outOfContract.loadAvailableBooks(0);
        // Then : la base répond avec la limite telle quelle, le cache n'est ni lu ni écrit
        assertThat(tooBig).hasSize(1);
        assertThat(zero).hasSize(1);
        verify(persistence).loadAvailableBooks(25);
        verify(persistence).loadAvailableBooks(0);
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void constructeur_quandTtlNulOuNegatif_alorsIllegalStateException() {
        // Given / When / Then : RC-07
        assertThatThrownBy(() -> new AvailableBooksCacheAdapter(database, database, redis, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AvailableBooksCacheAdapter(database, database, redis, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AvailableBooksCacheAdapter(database, database, redis, null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------ invalidation

    @Test
    void save_quandAucuneTransaction_alorsCleSupprimeeImmediatement() {
        // Given : une liste en cache
        givenBooksInDatabase(2);
        adapter.loadAvailableBooks(10);
        assertThat(redis.hasKey(KEY)).isTrue();
        // When
        adapter.save(book(99));
        // Then : D2, suppression immédiate et livre enregistré en base
        assertThat(redis.hasKey(KEY)).isFalse();
        assertThat(database.all()).hasSize(3);
    }

    @Test
    void save_quandSynchronisationActive_alorsCleConserveeJusquAuCommitPuisSupprimee() {
        // Given
        givenBooksInDatabase(2);
        adapter.loadAvailableBooks(10);
        TransactionSynchronizationManager.initSynchronization();
        // When : sauvegarde dans une « transaction » non encore validée
        adapter.save(book(99));
        // Then : RC-13, la clé reste tant que le commit n'a pas eu lieu
        assertThat(redis.hasKey(KEY)).isTrue();
        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void save_quandSynchronisationPuisRollback_alorsCleConservee() {
        // Given
        givenBooksInDatabase(2);
        adapter.loadAvailableBooks(10);
        TransactionSynchronizationManager.initSynchronization();
        // When : la transaction est annulée (afterCompletion seul, sans afterCommit)
        adapter.save(book(99));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        // Then
        assertThat(redis.hasKey(KEY)).isTrue();
    }

    @Test
    void save_quandPersistanceEchoue_alorsExceptionPropageeEtCleConservee() {
        // Given : la base refuse l'écriture (ISBN déjà pris), dans une transaction
        givenBooksInDatabase(2);
        adapter.loadAvailableBooks(10);
        database.simulateConcurrentInsert();
        TransactionSynchronizationManager.initSynchronization();
        // When / Then
        assertThatThrownBy(() -> adapter.save(book(99))).isInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(redis.hasKey(KEY)).isTrue();
    }

    @Test
    void save_quandPersistanceEchoueSansTransaction_alorsExceptionPropageeEtCleConservee() {
        // Given
        givenBooksInDatabase(2);
        adapter.loadAvailableBooks(10);
        database.simulateConcurrentInsert();
        // When / Then
        assertThatThrownBy(() -> adapter.save(book(99))).isInstanceOf(IsbnAlreadyExistsException.class);
        assertThat(redis.hasKey(KEY)).isTrue();
    }

    @Test
    void save_quandInvalidation_alorsAucuneCommandeKeys() {
        // Given : compteurs de commandes remis à zéro
        givenBooksInDatabase(2);
        run(connection -> {
            connection.serverCommands().resetConfigStats();
            return null;
        });
        // When : lecture (GET + SET) puis invalidation (DEL)
        adapter.loadAvailableBooks(10);
        adapter.save(book(99));
        Properties stats = run(connection -> connection.serverCommands().info("commandstats"));
        // Then : RC-14, DEL utilisé ; ni KEYS ni SCAN
        assertThat(stats).isNotNull();
        assertThat(stats.stringPropertyNames()).contains("cmdstat_get", "cmdstat_set", "cmdstat_del")
                .doesNotContain("cmdstat_keys", "cmdstat_scan");
    }
}
