package com.example.library.catalog.adapter.out.cache;

import static com.example.library.catalog.adapter.out.cache.AvailableBooksCacheAdapter.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.example.library.RedisTestcontainersConfiguration;
import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Résilience du décorateur : Redis injoignable ou client en erreur, la requête réussit depuis la base (RC-11, RC-12
 * amendée par D10, RC-15, RC-18). Les WARN sont capturés et ne contiennent jamais la valeur du cache.
 */
@ExtendWith(OutputCaptureExtension.class)
class AvailableBooksCacheAdapterResilienceTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String READ_WARN = "Lecture du cache impossible";
    private static final String UNREADABLE_WARN = "Valeur du cache illisible";
    private static final String WRITE_WARN = "criture du cache impossible";
    private static final String EVICT_WARN = "Invalidation du cache impossible";

    private InMemoryBookRepository database;

    @BeforeEach
    void setUp() {
        database = new InMemoryBookRepository();
        for (int i = 0; i < 3; i++) {
            database.givenExisting(book(i));
        }
    }

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Book book(int index) {
        return Book.register(Isbn.of("978000000%04d".formatted(index)), "Livre %02d".formatted(index), "Auteur", 2);
    }

    private static List<UUID> ids(List<Book> books) {
        return books.stream().map(book -> book.getId().value()).toList();
    }

    private static int count(String text, String fragment) {
        int occurrences = 0;
        for (int from = text.indexOf(fragment); from >= 0; from = text.indexOf(fragment, from + 1)) {
            occurrences++;
        }
        return occurrences;
    }

    /** Fabrique de connexion vers un port sur lequel personne n'écoute (jamais 6379). */
    private static LettuceConnectionFactory unreachableRedis() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        return factory("127.0.0.1", closedPort, ClientOptions.DisconnectedBehavior.DEFAULT);
    }

    private static LettuceConnectionFactory factory(String host, int port, ClientOptions.DisconnectedBehavior behavior) {
        ClientOptions options = ClientOptions.builder()
                .disconnectedBehavior(behavior)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                .build();
        LettuceClientConfiguration configuration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .clientOptions(options)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port), configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    // ------------------------------------------------------- Redis injoignable

    @Test
    void loadAvailableBooks_quandRedisInjoignable_alorsResultatDeLaBaseUnWarnLectureEtAucuneEcriture(
            CapturedOutput output) throws IOException {
        // Given : Redis ne répond pas du tout
        LettuceConnectionFactory factory = unreachableRedis();
        try {
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database,
                    new StringRedisTemplate(factory), TTL);
            // When
            List<Book> result = adapter.loadAvailableBooks(10);
            // Then : même résultat que la base, un seul WARN de lecture, aucun WARN d'écriture (D10)
            assertThat(ids(result)).isEqualTo(ids(database.loadAvailableBooks(10)));
            assertThat(database.loadAvailableCalls()).isEqualTo(2); // 1 repli + 1 appel de comparaison ci-dessus
            assertThat(count(output.getAll(), READ_WARN)).isEqualTo(1);
            assertThat(output.getAll()).doesNotContain(WRITE_WARN).doesNotContain(UNREADABLE_WARN).contains(KEY);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void save_quandRedisInjoignableSansTransaction_alorsSuccesEtWarnInvalidation(CapturedOutput output)
            throws IOException {
        // Given
        LettuceConnectionFactory factory = unreachableRedis();
        try {
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database,
                    new StringRedisTemplate(factory), TTL);
            // When : aucune exception ne sort, le livre est en base
            adapter.save(book(99));
            // Then : RC-15
            assertThat(database.all()).hasSize(4);
            assertThat(count(output.getAll(), EVICT_WARN)).isEqualTo(1);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void save_quandRedisInjoignableDansAfterCommit_alorsAucuneExceptionPropagee(CapturedOutput output)
            throws IOException {
        // Given : une transaction simulée
        LettuceConnectionFactory factory = unreachableRedis();
        try {
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database,
                    new StringRedisTemplate(factory), TTL);
            TransactionSynchronizationManager.initSynchronization();
            adapter.save(book(99));
            // When : le commit déclenche l'invalidation, Redis est injoignable
            TransactionSynchronizationUtils.triggerAfterCommit();
            // Then : RC-15, le POST ne doit pas échouer
            assertThat(database.all()).hasSize(4);
            assertThat(count(output.getAll(), EVICT_WARN)).isEqualTo(1);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void loadAvailableBooks_quandRedisArreteApresConnexion_alorsRepliImmediat(CapturedOutput output) {
        // Given : un Redis dédié, connecté puis arrêté ; REJECT_COMMANDS (D7)
        try (GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(RedisTestcontainersConfiguration.REDIS_IMAGE)).withExposedPorts(6379)) {
            container.start();
            LettuceConnectionFactory factory = factory(container.getHost(), container.getMappedPort(6379),
                    ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            try {
                AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database,
                        new StringRedisTemplate(factory), TTL);
                adapter.loadAvailableBooks(10); // établit la connexion et remplit le cache
                container.stop();
                // When : on attend que Lettuce ait détecté la coupure, puis on mesure un appel
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                        .until(() -> count(output.getAll(), READ_WARN) > 0 || probe(adapter, output));
                long start = System.nanoTime();
                List<Book> result = adapter.loadAvailableBooks(10);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                // Then : repli quasi instantané, bien en deçà du timeout de commande, résultat de la base
                assertThat(result).hasSize(3);
                assertThat(elapsed).isLessThan(Duration.ofMillis(400));
                assertThat(output.getAll()).doesNotContain(WRITE_WARN);
            } finally {
                factory.destroy();
            }
        }
    }

    private boolean probe(AvailableBooksCacheAdapter adapter, CapturedOutput output) {
        adapter.loadAvailableBooks(10);
        return count(output.getAll(), READ_WARN) > 0;
    }

    // --------------------------------------------------- client simulé en erreur

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> operationsOf(StringRedisTemplate template) {
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(operations);
        return operations;
    }

    static Stream<Arguments> redisErrors() {
        return Stream.of(
                Arguments.of(new QueryTimeoutException("MARQUEUR-MESSAGE")),
                Arguments.of(new RedisSystemException("MARQUEUR-MESSAGE", new RuntimeException("Commands are rejected"))),
                Arguments.of(new IllegalStateException("MARQUEUR-MESSAGE")));
    }

    @ParameterizedTest
    @MethodSource("redisErrors")
    void loadAvailableBooks_quandGetEnErreur_alorsRepliBaseWarnEtSetJamaisAppele(RuntimeException error,
                                                                                  CapturedOutput output) {
        // Given : le client Redis lève une erreur sur get
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenThrow(error);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then : D10, repli sur la base, aucune écriture, WARN sans le message de l'erreur
        assertThat(result).hasSize(3);
        assertThat(database.loadAvailableCalls()).isEqualTo(1);
        verify(operations, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(count(output.getAll(), READ_WARN)).isEqualTo(1);
        assertThat(output.getAll()).contains(error.getClass().getSimpleName()).doesNotContain("MARQUEUR-MESSAGE");
    }

    @Test
    void loadAvailableBooks_quandGetRenvoieUneValeurIllisible_alorsSetAppeleUneFoisPourRemplacer(
            CapturedOutput output) {
        // Given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn("pas du json MARQUEUR-VALEUR");
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then : RC-11, remplacement une seule fois, valeur absente des logs
        assertThat(result).hasSize(3);
        verify(operations, times(1)).set(eq(KEY), anyString(), eq(TTL));
        assertThat(count(output.getAll(), UNREADABLE_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-VALEUR");
    }

    @Test
    void loadAvailableBooks_quandCleAbsente_alorsSetAppeleUneFoisSansWarn(CapturedOutput output) {
        // Given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn(null);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then : absence normale, pas de WARN
        assertThat(result).hasSize(3);
        verify(operations, times(1)).set(eq(KEY), anyString(), eq(TTL));
        assertThat(output.getAll()).doesNotContain(READ_WARN).doesNotContain(UNREADABLE_WARN);
    }

    @Test
    void loadAvailableBooks_quandSetEnErreur_alorsWarnEcritureEtResultatDeLaBase(CapturedOutput output) {
        // Given : lecture normale (clé absente) puis écriture en erreur
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn(null);
        doThrow(new QueryTimeoutException("MARQUEUR-MESSAGE")).when(operations)
                .set(eq(KEY), anyString(), any(Duration.class));
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then : RC-12, une seule tentative, WARN d'écriture, la requête réussit
        assertThat(result).hasSize(3);
        verify(operations, times(1)).set(eq(KEY), anyString(), any(Duration.class));
        assertThat(count(output.getAll(), WRITE_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-MESSAGE");
    }

    @Test
    void loadAvailableBooks_quandGetRenvoieUnTableauVide_alorsBaseNonLueEtAucuneEcriture() {
        // Given : D3, « [] » est un résultat valide
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn("[]");
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then
        assertThat(result).isEmpty();
        assertThat(database.loadAvailableCalls()).isZero();
        verify(operations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void save_quandDeleteEnErreur_alorsAucuneExceptionEtWarnInvalidation(CapturedOutput output) {
        // Given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.delete(KEY)).thenThrow(new QueryTimeoutException("MARQUEUR-MESSAGE"));
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, template, TTL);
        // When
        adapter.save(book(99));
        // Then : RC-15, RC-18
        assertThat(database.all()).hasSize(4);
        assertThat(count(output.getAll(), EVICT_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-MESSAGE");
    }
}
