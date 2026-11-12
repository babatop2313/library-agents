package com.example.library.catalog.application.fake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.library.catalog.application.port.out.CheckIsbnRegisteredPort;
import com.example.library.catalog.application.port.out.LoadAvailableBooksPort;
import com.example.library.catalog.application.port.out.LoadBookByIsbnPort;
import com.example.library.catalog.application.port.out.SaveBookPort;
import com.example.library.catalog.domain.exception.IsbnAlreadyExistsException;
import com.example.library.catalog.domain.model.AvailableBooksLimit;
import com.example.library.catalog.domain.model.Book;
import com.example.library.catalog.domain.model.Isbn;
import com.example.library.catalog.domain.service.AvailableBooksSelection;

/** Fake en memoire des ports de sortie du catalogue. */
public class InMemoryBookRepository
        implements CheckIsbnRegisteredPort, SaveBookPort, LoadBookByIsbnPort, LoadAvailableBooksPort {

    private final Map<Isbn, Book> books = new LinkedHashMap<>();
    private final AvailableBooksSelection selection = new AvailableBooksSelection();
    private boolean simulateConcurrentInsert;
    private int checkCalls;
    private int loadByIsbnCalls;
    private int loadAvailableCalls;
    private Integer lastMaxResults;

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
}
