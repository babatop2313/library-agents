package com.example.library.catalog.application.fake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.library.catalog.application.port.out.ChangeAvailableCopiesPort;
import com.example.library.catalog.application.port.out.CheckIsbnRegisteredPort;
import com.example.library.catalog.application.port.out.CountAvailableBooksPort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPagePort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.application.port.out.LoadBookByIdPort;
import com.example.library.catalog.application.port.out.LoadBookByIsbnPort;
import com.example.library.catalog.application.port.out.LoadBooksByIdsPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.BookId;
import com.example.library.catalog.domain.model.CatalogPageRequest;
import com.example.library.catalog.domain.model.Isbn;
import com.example.library.catalog.domain.model.SearchText;
import com.example.library.catalog.domain.service.AvailableBooksSelection;

/** Fake en memoire des ports de sortie du catalogue. */
public class InMemoryBookRepository
        implements CheckIsbnRegisteredPort, SaveBookPort, LoadBookByIsbnPort, LoadAvailableBooksPort,
        LoadAvailableBooksPagePort, CountAvailableBooksPort, LoadBookByIdPort, LoadBooksByIdsPort,
        ChangeAvailableCopiesPort {

    private final Map<Isbn, Book> books = new LinkedHashMap<>();
    private final AvailableBooksSelection selection = new AvailableBooksSelection();
    private boolean simulateConcurrentInsert;
    private int checkCalls;
    private int loadByIsbnCalls;
    private int loadAvailableCalls;
    private Integer lastMaxResults;
    private int loadPageCalls;
    private int countCalls;
    private CatalogPageRequest lastPageRequest;
    private SearchText lastText;
    private int loadByIdCalls;
    private int loadByIdsCalls;
    private int decrementCalls;
    private int incrementCalls;
    private boolean simulateLostRace;

    @Override
    public boolean isRegistered(Isbn isbn) {
        checkCalls++;
        // simule une course : le controle d'unicite ne voit rien, mais l'insertion echouera
        return !simulateConcurrentInsert && books.containsKey(isbn);
    }

    @Override
    public void save(Book book) {
        if (simulateConcurrentInsert || books.containsKey(book.getIsbn())) {
            throw new IsbnAlreadyExistsException(book.getIsbn());
        }
        books.put(book.getIsbn(), book);
    }

    @Override
    public Optional<Book> loadByIsbn(Isbn isbn) {
        loadByIsbnCalls++;
        return Optional.ofNullable(books.get(isbn));
    }

    /** Comme le SQL : livres disponibles, ordre du catalogue, au plus maxResults. */
    @Override
    public List<Book> loadAvailableBooks(int maxResults) {
        loadAvailableCalls++;
        lastMaxResults = maxResults;
        return selection.select(new ArrayList<>(books.values()), new AvailableBooksLimit(maxResults));
    }

    /** Sémantique de référence : disponibles, filtre {@link SearchText#matches(Book)}, ordre du catalogue, fenêtre. */
    @Override
    public List<Book> loadAvailableBooksPage(SearchText text, CatalogPageRequest pageRequest) {
        loadPageCalls++;
        lastText = text;
        lastPageRequest = pageRequest;
        return books.values().stream()
                .filter(Book::isAvailable)
                .filter(text::matches)
                .sorted(AvailableBooksSelection.CATALOG_ORDER)
                .skip(pageRequest.offset())
                .limit(pageRequest.size())
                .toList();
    }

    @Override
    public long countAvailableBooks(SearchText text) {
        countCalls++;
        return books.values().stream().filter(Book::isAvailable).filter(text::matches).count();
    }

    /** Pre-remplit le catalogue sans passer par les ports. */
    public void givenExisting(Book book) {
        books.put(book.getIsbn(), book);
    }

    public void simulateConcurrentInsert() {
        this.simulateConcurrentInsert = true;
    }

    public List<Book> all() {
        return new ArrayList<>(books.values());
    }

    public Book find(Isbn isbn) {
        return books.get(isbn);
    }

    public int checkCalls() {
        return checkCalls;
    }

    public int loadByIsbnCalls() {
        return loadByIsbnCalls;
    }

    public int loadAvailableCalls() {
        return loadAvailableCalls;
    }

    public Integer lastMaxResults() {
        return lastMaxResults;
    }

    public int loadPageCalls() {
        return loadPageCalls;
    }

    public int countCalls() {
        return countCalls;
    }

    public CatalogPageRequest lastPageRequest() {
        return lastPageRequest;
    }

    public SearchText lastText() {
        return lastText;
    }

    @Override
    public Optional<Book> loadById(BookId id) {
        loadByIdCalls++;
        return books.values().stream().filter(book -> book.getId().equals(id)).findFirst();
    }

    @Override
    public List<Book> loadByIds(Set<BookId> ids) {
        loadByIdsCalls++;
        return books.values().stream().filter(book -> ids.contains(book.getId())).toList();
    }

    /** Comme le SQL conditionnel : false si plus d'exemplaire disponible ou si le livre est absent. */
    @Override
    public boolean decrementAvailableCopies(BookId id) {
        decrementCalls++;
        Optional<Book> book = books.values().stream().filter(b -> b.getId().equals(id)).findFirst();
        if (simulateLostRace || book.isEmpty() || !book.get().isAvailable()) {
            return false;
        }
        books.put(book.get().getIsbn(), book.get().takeCopy());
        return true;
    }

    /** Comme le SQL conditionnel : false si tous les exemplaires sont deja disponibles ou si le livre est absent. */
    @Override
    public boolean incrementAvailableCopies(BookId id) {
        incrementCalls++;
        Optional<Book> book = books.values().stream().filter(b -> b.getId().equals(id)).findFirst();
        if (book.isEmpty() || book.get().getAvailableCopies() >= book.get().getTotalCopies()) {
            return false;
        }
        books.put(book.get().getIsbn(), book.get().returnCopy());
        return true;
    }

    /** Simule la course perdue : le livre semble disponible a la lecture, mais la mise a jour conditionnelle echoue. */
    public void simulateLostRace() {
        this.simulateLostRace = true;
    }

    public int loadByIdCalls() {
        return loadByIdCalls;
    }

    public int loadByIdsCalls() {
        return loadByIdsCalls;
    }

    public int decrementCalls() {
        return decrementCalls;
    }

    public int incrementCalls() {
        return incrementCalls;
    }
}
