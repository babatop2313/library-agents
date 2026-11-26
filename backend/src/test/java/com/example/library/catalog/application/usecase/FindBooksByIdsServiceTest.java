package com.example.library.catalog.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.library.catalog.application.fake.InMemoryBookRepository;
import com.example.library.catalog.application.port.in.FindBooksByIdsQuery;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/** RM-LOAN-32 : lecture par lot, une seule lecture, ensemble borné à 50 identifiants. */
class FindBooksByIdsServiceTest {

    private final InMemoryBookRepository repository = new InMemoryBookRepository();
    private final FindBooksByIdsService service = new FindBooksByIdsService(repository);

    private Book givenBook(int index) {
        Book book = Book.restore(BookId.newId(), Isbn.of("978000000%04d".formatted(index)), "T" + index, "A", 2, 1);
        repository.givenExisting(book);
        return book;
    }

    @Test
    void findByIds_quandTroisIdentifiants_alorsTroisLivresEnUneSeuleLecture() {
        // Given
        Book a = givenBook(1);
        Book b = givenBook(2);
        Book c = givenBook(3);
        givenBook(4);
        // When
        List<Book> result = service.findByIds(new FindBooksByIdsQuery(Set.of(a.getId(), b.getId(), c.getId())));
        // Then
        assertThat(result).containsExactlyInAnyOrder(a, b, c);
        assertThat(repository.loadByIdsCalls()).isEqualTo(1);
    }

    @Test
    void findByIds_quandEnsembleVide_alorsListeVideSansLecture() {
        // Given / When
        List<Book> result = service.findByIds(new FindBooksByIdsQuery(Set.of()));
        // Then
        assertThat(result).isEmpty();
        assertThat(repository.loadByIdsCalls()).isZero();
    }

    @Test
    void findByIds_quandIdentifiantInconnu_alorsAbsentDuResultat() {
        // Given
        Book a = givenBook(1);
        // When
        List<Book> result = service.findByIds(new FindBooksByIdsQuery(Set.of(a.getId(), BookId.newId())));
        // Then
        assertThat(result).containsExactly(a);
    }

    @Test
    void query_quandCinquanteIdentifiants_alorsAccepte() {
        // Given
        Set<BookId> ids = new HashSet<>();
        for (int i = 0; i < FindBooksByIdsQuery.MAX_IDS; i++) {
            ids.add(BookId.newId());
        }
        // When / Then
        assertThat(new FindBooksByIdsQuery(ids).ids()).hasSize(50);
    }

    @Test
    void query_quandPlusDeCinquanteIdentifiants_alorsRefuse() {
        // Given
        Set<BookId> ids = new HashSet<>();
        for (int i = 0; i < FindBooksByIdsQuery.MAX_IDS + 1; i++) {
            ids.add(BookId.newId());
        }
        // When / Then
        assertThatThrownBy(() -> new FindBooksByIdsQuery(ids)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void query_quandEnsembleNul_alorsNullPointerException() {
        // When / Then
        assertThatThrownBy(() -> new FindBooksByIdsQuery(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void usecase_quandAnnote_alorsTransactionEnLectureSeule() {
        // When
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(FindBooksByIdsService.class,
                Transactional.class);
        // Then
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
