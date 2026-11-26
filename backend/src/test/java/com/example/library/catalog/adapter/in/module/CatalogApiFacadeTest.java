package com.example.library.catalog.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.library.catalog.api.BookSummary;
import com.example.library.catalog.api.CatalogApi;
import com.example.library.catalog.api.ReleaseResult;
import com.example.library.catalog.api.ReservationResult;
import com.example.library.catalog.application.port.in.CopyReleaseOutcome;
import com.example.library.catalog.application.port.in.CopyReservationOutcome;
import com.example.library.catalog.application.port.in.FindBooksByIdsQuery;
import com.example.library.catalog.application.port.in.FindBooksByIdsUseCase;
import com.example.library.catalog.application.port.in.ReleaseCopyCommand;
import com.example.library.catalog.application.port.in.ReleaseCopyUseCase;
import com.example.library.catalog.application.port.in.ReserveCopyCommand;
import com.example.library.catalog.application.port.in.ReserveCopyUseCase;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.Isbn;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Façade du contrat public {@code catalog.api} : ne parle qu'aux ports d'entrée, traduit les types. RM-LOAN-17. */
class CatalogApiFacadeTest {

    private final ReserveCopyUseCase reserve = mock(ReserveCopyUseCase.class);
    private final ReleaseCopyUseCase release = mock(ReleaseCopyUseCase.class);
    private final FindBooksByIdsUseCase find = mock(FindBooksByIdsUseCase.class);
    private final CatalogApiFacade facade = new CatalogApiFacade(reserve, release, find);
    private final UUID uuid = UUID.randomUUID();

    @Test
    void reserveCopy_quandReserved_alorsReservedVraiSansRaison() {
        // Given
        when(reserve.reserve(any())).thenReturn(CopyReservationOutcome.RESERVED);
        // When
        ReservationResult result = facade.reserveCopy(uuid);
        // Then
        assertThat(result).isEqualTo(new ReservationResult(true, null));
        ArgumentCaptor<ReserveCopyCommand> captor = ArgumentCaptor.forClass(ReserveCopyCommand.class);
        verify(reserve).reserve(captor.capture());
        assertThat(captor.getValue().bookId()).isEqualTo(new BookId(uuid));
    }

    @Test
    void reserveCopy_quandPlusAucunExemplaire_alorsReservedFauxEtRaisonNoCopyAvailable() {
        // Given
        when(reserve.reserve(any())).thenReturn(CopyReservationOutcome.NO_COPY_AVAILABLE);
        // When
        ReservationResult result = facade.reserveCopy(uuid);
        // Then
        assertThat(result.reserved()).isFalse();
        assertThat(result.reason()).isEqualTo(CatalogApi.NO_COPY_AVAILABLE).isEqualTo("NO_COPY_AVAILABLE");
    }

    @Test
    void reserveCopy_quandLivreInconnu_alorsReservedFauxEtRaisonBookNotFound() {
        // Given
        when(reserve.reserve(any())).thenReturn(CopyReservationOutcome.BOOK_NOT_FOUND);
        // When
        ReservationResult result = facade.reserveCopy(uuid);
        // Then
        assertThat(result).isEqualTo(new ReservationResult(false, "BOOK_NOT_FOUND"));
    }

    @Test
    void releaseCopy_quandReleased_alorsReleasedVrai() {
        // Given
        when(release.release(any())).thenReturn(CopyReleaseOutcome.RELEASED);
        // When
        ReleaseResult result = facade.releaseCopy(uuid);
        // Then
        assertThat(result).isEqualTo(new ReleaseResult(true, null));
        ArgumentCaptor<ReleaseCopyCommand> captor = ArgumentCaptor.forClass(ReleaseCopyCommand.class);
        verify(release).release(captor.capture());
        assertThat(captor.getValue().bookId()).isEqualTo(new BookId(uuid));
    }

    @Test
    void releaseCopy_quandLivreInconnu_alorsReleasedFauxEtRaisonBookNotFound() {
        // Given
        when(release.release(any())).thenReturn(CopyReleaseOutcome.BOOK_NOT_FOUND);
        // When / Then
        assertThat(facade.releaseCopy(uuid)).isEqualTo(new ReleaseResult(false, "BOOK_NOT_FOUND"));
    }

    @Test
    void findBooksByIds_quandLivresTrouves_alorsResumesSansCompteursEtUnSeulAppel() {
        // Given
        Book a = Book.restore(BookId.newId(), Isbn.of("9782070368228"), "L'Étranger", "Albert Camus", 3, 1);
        Book b = Book.restore(BookId.newId(), Isbn.of("9782070368235"), "La Peste", "Albert Camus", 2, 0);
        when(find.findByIds(any())).thenReturn(List.of(a, b));
        // When
        List<BookSummary> result = facade.findBooksByIds(List.of(a.getId().value(), b.getId().value()));
        // Then
        assertThat(result).containsExactly(
                new BookSummary(a.getId().value(), "9782070368228", "L'Étranger", "Albert Camus"),
                new BookSummary(b.getId().value(), "9782070368235", "La Peste", "Albert Camus"));
        ArgumentCaptor<FindBooksByIdsQuery> captor = ArgumentCaptor.forClass(FindBooksByIdsQuery.class);
        verify(find).findByIds(captor.capture());
        assertThat(captor.getValue().ids()).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void findBooksByIds_quandIdentifiantsEnDouble_alorsDedoublonnesDansLaRequete() {
        // Given
        when(find.findByIds(any())).thenReturn(List.of());
        // When
        facade.findBooksByIds(List.of(uuid, uuid));
        // Then
        ArgumentCaptor<FindBooksByIdsQuery> captor = ArgumentCaptor.forClass(FindBooksByIdsQuery.class);
        verify(find).findByIds(captor.capture());
        assertThat(captor.getValue().ids()).isEqualTo(Set.of(new BookId(uuid)));
    }

    @Test
    void findBooksByIds_quandAucunLivreTrouve_alorsListeVide() {
        // Given
        when(find.findByIds(any())).thenReturn(List.of());
        // When / Then
        assertThat(facade.findBooksByIds(Set.of(uuid))).isEmpty();
    }

    @Test
    void reserveCopy_quandAppel_alorsLesAutresPortsNeSontPasSollicites() {
        // Given
        when(reserve.reserve(any())).thenReturn(CopyReservationOutcome.RESERVED);
        // When
        facade.reserveCopy(uuid);
        // Then
        verifyNoInteractions(release, find);
    }
}
