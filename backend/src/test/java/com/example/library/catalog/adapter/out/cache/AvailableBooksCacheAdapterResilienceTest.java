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
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.example.library.catalog.domain.model.SearchText;
import com.example.library.catalog.domain.model.CatalogPageRequest;
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
    // Préfixes dérivés des gabarits de production (partie avant le premier « {} ») : si le message change, les
    // assertions positives et négatives suivent. Les chaînes restent des String Java en mémoire (sortie capturée
    // par OutputCaptureExtension) : l'encodage de la console Windows n'intervient pas pour « É » de « Écriture ».
    private static final String READ_WARN = prefix(AvailableBooksCacheAdapter.LOG_READ_FAILED);
    private static final String UNREADABLE_WARN = prefix(AvailableBooksCacheAdapter.LOG_UNREADABLE_VALUE);
    private static final String WRITE_WARN = prefix(AvailableBooksCacheAdapter.LOG_WRITE_FAILED);
    private static final String EVICT_WARN = prefix(AvailableBooksCacheAdapter.LOG_EVICT_FAILED);

    /** Délai de commande de la fabrique dédiée : bien supérieur au seuil de repli pour les distinguer sans ambiguïté. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration FALLBACK_THRESHOLD = Duration.ofSeconds(1);

    private static String prefix(String template) {
        return template.substring(0, template.indexOf("{}"));
    }

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
                .commandTimeout(COMMAND_TIMEOUT)
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
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database,
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
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database,
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
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database,
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
                AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database,
                        new StringRedisTemplate(factory), TTL);
                adapter.loadAvailableBooks(10); // établit la connexion et remplit le cache
                container.stop();
                // When : on attend que Lettuce ait détecté la coupure, puis on mesure un appel
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                        .until(() -> count(output.getAll(), READ_WARN) > 0 || probe(adapter, output));
                String logsBefore = output.getAll();
                long start = System.nanoTime();
                List<Book> result = adapter.loadAvailableBooks(10);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                String newLogs = output.getAll().substring(logsBefore.length());
                // Then : résultat de la base ; repli par rejet immédiat (cause autre qu'un délai de commande) et
                // durée très inférieure au délai de commande de 3 s
                assertThat(result).hasSize(3);
                assertThat(newLogs).contains(READ_WARN).doesNotContain(QueryTimeoutException.class.getSimpleName());
                assertThat(COMMAND_TIMEOUT).isGreaterThan(FALLBACK_THRESHOLD.multipliedBy(2));
                assertThat(elapsed).isLessThan(FALLBACK_THRESHOLD);
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
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
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
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooks(10);
        // Then : RC-11, remplacement une seule fois, valeur absente des logs
        assertThat(result).hasSize(3);
        verify(operations, times(1)).set(eq(KEY), anyString(), eq(TTL));
        assertThat(count(output.getAll(), UNREADABLE_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-VALEUR");
    }

    static Stream<Arguments> unreadableValues() {
        return Stream.of(
                Arguments.of("pas du json MARQUEUR-VALEUR", AvailableBooksCacheCodec.REASON_UNREADABLE_JSON),
                Arguments.of("[{\"id\":\"6f1b2c3d-0000-4000-8000-000000000001\",\"isbn\":\"123\","
                        + "\"title\":\"MARQUEUR-VALEUR\",\"author\":\"A\",\"totalCopies\":3,\"availableCopies\":2}]",
                        AvailableBooksCacheCodec.REASON_INVALID_ID_OR_ISBN));
    }

    @ParameterizedTest
    @MethodSource("unreadableValues")
    void loadAvailableBooks_quandValeurIllisible_alorsWarnAvecMotifFixeEtJamaisLaValeur(String value,
                                                                                         String expectedReason,
                                                                                         CapturedOutput output) {
        // Given : une valeur illisible contenant un marqueur unique
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn(value);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        adapter.loadAvailableBooks(10);
        // Then : RC-18, le WARN porte le motif du codec, jamais la valeur lue
        assertThat(count(output.getAll(), UNREADABLE_WARN)).isEqualTo(1);
        assertThat(output.getAll()).contains("reason=" + expectedReason).doesNotContain("MARQUEUR-VALEUR");
    }

    @Test
    void loadAvailableBooks_quandCleAbsente_alorsSetAppeleUneFoisSansWarn(CapturedOutput output) {
        // Given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn(null);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
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
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
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
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
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
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        adapter.save(book(99));
        // Then : RC-15, RC-18
        assertThat(database.all()).hasSize(4);
        assertThat(count(output.getAll(), EVICT_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-MESSAGE");
    }

    @Test
    void decrementAvailableCopies_quandDeleteEnErreur_alorsResultatConserveEtWarnInvalidation(CapturedOutput output) {
        // Given : Redis refuse la suppression de la clé après la mise à jour de la base
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.delete(KEY)).thenThrow(new QueryTimeoutException("MARQUEUR-MESSAGE"));
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        Book target = database.all().get(0);
        // When
        boolean changed = adapter.decrementAvailableCopies(target.getId());
        // Then : RC-15, l'erreur de cache n'est jamais propagée, l'emprunt n'est pas perdu
        assertThat(changed).isTrue();
        assertThat(database.find(target.getIsbn()).getAvailableCopies()).isEqualTo(1);
        assertThat(count(output.getAll(), EVICT_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-MESSAGE");
    }

    @Test
    void incrementAvailableCopies_quandDeleteEnErreur_alorsResultatConserveEtWarnInvalidation(CapturedOutput output) {
        // Given : un livre dont un exemplaire est emprunté
        Book borrowed = Book.restore(com.example.library.catalog.domain.model.BookId.newId(),
                Isbn.of("9780000009999"), "Emprunté", "Auteur", 2, 1);
        database.givenExisting(borrowed);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.delete(KEY)).thenThrow(new QueryTimeoutException("MARQUEUR-MESSAGE"));
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        boolean changed = adapter.incrementAvailableCopies(borrowed.getId());
        // Then
        assertThat(changed).isTrue();
        assertThat(database.find(borrowed.getIsbn()).getAvailableCopies()).isEqualTo(2);
        assertThat(count(output.getAll(), EVICT_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-MESSAGE");
    }

    @Test
    void decrementAvailableCopies_quandAucuneLigneModifiee_alorsAucuneInvalidationTentee() {
        // Given : un livre sans exemplaire disponible ; un template dont aucun appel n'est attendu
        Book soldOut = Book.restore(com.example.library.catalog.domain.model.BookId.newId(),
                Isbn.of("9780000009998"), "Épuisé", "Auteur", 2, 0);
        database.givenExisting(soldOut);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        boolean changed = adapter.decrementAvailableCopies(soldOut.getId());
        // Then
        assertThat(changed).isFalse();
        verifyNoInteractions(template);
    }

    // ------------------------------------------------- pages du catalogue (RM-CAT-24)

    @ParameterizedTest
    @MethodSource("redisErrors")
    void loadAvailableBooksPage_quandRedisEnErreurSurLaTeteDeListe_alorsMemeContenuQueLaBaseEtWarn(
            RuntimeException error, CapturedOutput output) {
        // Given : le client Redis lève une erreur sur get
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenThrow(error);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        CatalogPageRequest request = new CatalogPageRequest(0, 10);
        // When
        List<Book> result = adapter.loadAvailableBooksPage(SearchText.NONE, request);
        // Then : même résultat qu'avec la base, WARN sans le message, aucune écriture
        assertThat(ids(result)).isEqualTo(ids(database.loadAvailableBooksPage(SearchText.NONE, request)));
        assertThat(count(output.getAll(), READ_WARN)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("MARQUEUR-MESSAGE");
        verify(operations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void loadAvailableBooksPage_quandRecherche_alorsRedisJamaisSollicite() {
        // Given : un template dont aucun appel n'est attendu
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        List<Book> result = adapter.loadAvailableBooksPage(SearchText.of("livre"), new CatalogPageRequest(0, 10));
        // Then
        assertThat(result).hasSize(3);
        verifyNoInteractions(template);
    }

    @Test
    void loadAvailableBooksPage_quandPageProfonde_alorsRedisJamaisSollicite() {
        // Given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        // When
        adapter.loadAvailableBooksPage(SearchText.NONE, new CatalogPageRequest(2, 10));
        // Then
        verifyNoInteractions(template);
    }

    @Test
    void loadAvailableBooksPage_quandRedisInjoignable_alorsReponseDeLaBaseEtUnWarn(CapturedOutput output)
            throws IOException {
        // Given : Redis ne répond pas du tout
        LettuceConnectionFactory factory = unreachableRedis();
        try {
            AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database,
                    new StringRedisTemplate(factory), TTL);
            CatalogPageRequest request = new CatalogPageRequest(0, 10);
            // When
            List<Book> result = adapter.loadAvailableBooksPage(SearchText.NONE, request);
            // Then : même contenu que la base, un WARN de lecture
            assertThat(ids(result)).isEqualTo(ids(database.loadAvailableBooksPage(SearchText.NONE, request)));
            assertThat(count(output.getAll(), READ_WARN)).isEqualTo(1);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void loadAvailableBooksPage_quandValeurIllisible_alorsRepliBaseEtContenuIdentique() {
        // Given
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = operationsOf(template);
        when(operations.get(KEY)).thenReturn("pas du json");
        AvailableBooksCacheAdapter adapter = new AvailableBooksCacheAdapter(database, database, database, database, template, TTL);
        CatalogPageRequest request = new CatalogPageRequest(0, 2);
        // When
        List<Book> result = adapter.loadAvailableBooksPage(SearchText.NONE, request);
        // Then
        assertThat(ids(result)).isEqualTo(ids(database.loadAvailableBooksPage(SearchText.NONE, request)));
        verify(operations, times(1)).set(eq(KEY), anyString(), eq(TTL));
    }
}
