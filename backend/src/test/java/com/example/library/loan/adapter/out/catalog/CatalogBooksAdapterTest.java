package com.example.library.loan.adapter.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.library.catalog.api.CatalogApi;
import com.example.library.catalog.api.ReleaseResult;
import com.example.library.catalog.api.ReservationResult;
import com.example.library.loan.application.port.out.BookSummary;
import com.example.library.loan.application.port.out.CopyRelease;
import com.example.library.loan.application.port.out.CopyReservation;
import com.example.library.loan.domain.model.BookId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Adapter sortant de loan vers catalog : traduction des types et raisons de {@code catalog.api}. RM-LOAN-17, 32. */
class CatalogBooksAdapterTest {

    private final CatalogApi catalogApi = mock(CatalogApi.class);
    private final CatalogBooksAdapter adapter = new CatalogBooksAdapter(catalogApi);
    private final UUID uuid = UUID.randomUUID();
    private final BookId book = new BookId(uuid);


    // ---- reserve

    @Test
    void reserve_quandReserved_alorsReserved() {
        // Given
        when(catalogApi.reserveCopy(uuid)).thenReturn(new ReservationResult(true, null));
        // When / Then
        assertThat(adapter.reserve(book)).isEqualTo(CopyReservation.RESERVED);
    }

    @Test
    void reserve_quandRaisonLivreIntrouvable_alorsBookNotFound() {
        // Given
        when(catalogApi.reserveCopy(uuid)).thenReturn(new ReservationResult(false, CatalogApi.BOOK_NOT_FOUND));
        // When / Then
        assertThat(adapter.reserve(book)).isEqualTo(CopyReservation.BOOK_NOT_FOUND);
    }

    @Test
    void reserve_quandRaisonAucunExemplaire_alorsNoCopyAvailable() {
        // Given
        when(catalogApi.reserveCopy(uuid)).thenReturn(new ReservationResult(false, CatalogApi.NO_COPY_AVAILABLE));
        // When / Then
        assertThat(adapter.reserve(book)).isEqualTo(CopyReservation.NO_COPY_AVAILABLE);
    }

    @Test
    void reserve_quandRaisonInconnue_alorsIllegalStateException() {
        // Given : on ne devine pas une raison que loan ne connaît pas
        when(catalogApi.reserveCopy(uuid)).thenReturn(new ReservationResult(false, "AUTRE"));
        // When / Then
        assertThatThrownBy(() -> adapter.reserve(book)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTRE");
    }

    @Test
    void reserve_quandRaisonNulle_alorsIllegalStateException() {
        // Given
        when(catalogApi.reserveCopy(uuid)).thenReturn(new ReservationResult(false, null));
        // When / Then
        assertThatThrownBy(() -> adapter.reserve(book)).isInstanceOf(IllegalStateException.class);
    }

    // ---- release

    @Test
    void release_quandReleased_alorsReleased() {
        // Given
        when(catalogApi.releaseCopy(uuid)).thenReturn(new ReleaseResult(true, null));
        // When / Then
        assertThat(adapter.release(book)).isEqualTo(CopyRelease.RELEASED);
    }

    @Test
    void release_quandRaisonLivreIntrouvable_alorsBookNotFound() {
        // Given
        when(catalogApi.releaseCopy(uuid)).thenReturn(new ReleaseResult(false, CatalogApi.BOOK_NOT_FOUND));
        // When / Then
        assertThat(adapter.release(book)).isEqualTo(CopyRelease.BOOK_NOT_FOUND);
    }

    @Test
    void release_quandRaisonInconnue_alorsIllegalStateException() {
        // Given
        when(catalogApi.releaseCopy(uuid)).thenReturn(new ReleaseResult(false, "AUTRE"));
        // When / Then
        assertThatThrownBy(() -> adapter.release(book)).isInstanceOf(IllegalStateException.class);
    }

    // ---- loadSummaries

    @Test
    void loadSummaries_quandTroisLivres_alorsUnSeulAppelAuCatalogueEtResultatIndexeParIdentifiant() {
        // Given
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(catalogApi.findBooksByIds(any())).thenReturn(List.of(
                new com.example.library.catalog.api.BookSummary(a, "9782070368228", "A", "Auteur A"),
                new com.example.library.catalog.api.BookSummary(b, "9782070368235", "B", "Auteur B")));
        // When
        Map<BookId, BookSummary> result = adapter.loadSummaries(Set.of(new BookId(a), new BookId(b), new BookId(c)));
        // Then : RM-LOAN-32, un seul appel, le livre manquant est simplement absent
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(catalogApi, times(1)).findBooksByIds(captor.capture());
        verifyNoMoreInteractions(catalogApi);
        assertThat(captor.getValue()).containsExactlyInAnyOrder(a, b, c);
        assertThat(result).containsOnlyKeys(new BookId(a), new BookId(b));
        assertThat(result.get(new BookId(a))).isEqualTo(new BookSummary(new BookId(a), "9782070368228", "A", "Auteur A"));
        assertThat(result.get(new BookId(b)).title()).isEqualTo("B");
    }

    @Test
    void loadSummaries_quandLeCatalogueNeRenvoieRien_alorsMapVide() {
        // Given
        when(catalogApi.findBooksByIds(any())).thenReturn(List.of());
        // When / Then
        assertThat(adapter.loadSummaries(Set.of(book))).isEmpty();
    }

    @Test
    void adapter_quandReservationOuRetour_alorsAucunAppelAuxAutresOperationsDuCatalogue() {
        // Given
        when(catalogApi.reserveCopy(uuid)).thenReturn(new ReservationResult(true, null));
        // When
        adapter.reserve(book);
        // Then
        verify(catalogApi, never()).releaseCopy(any());
        verify(catalogApi, never()).findBooksByIds(any());
    }
}
