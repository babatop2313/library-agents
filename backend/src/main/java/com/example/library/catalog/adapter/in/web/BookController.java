package com.example.library.catalog.adapter.in.web;

import java.net.URI;

import com.example.library.catalog.application.port.in.RegisterBookUseCase;
import com.example.library.catalog.application.port.in.SearchAvailableBooksUseCase;
import com.example.library.catalog.domain.model.Book;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/books")
class BookController {

    private final RegisterBookUseCase registerBookUseCase;
    private final SearchAvailableBooksUseCase searchAvailableBooksUseCase;

    BookController(RegisterBookUseCase registerBookUseCase, SearchAvailableBooksUseCase searchAvailableBooksUseCase) {
        this.registerBookUseCase = registerBookUseCase;
        this.searchAvailableBooksUseCase = searchAvailableBooksUseCase;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LIBRARIAN','MEMBER')")
    ResponseEntity<BookPageResponse> search(@Valid @ModelAttribute SearchBooksParams params) {
        return ResponseEntity.ok(BookWebMapper.toPageResponse(
                searchAvailableBooksUseCase.search(BookWebMapper.toQuery(params))));
    }

    @PostMapping
    @PreAuthorize("hasRole('LIBRARIAN')")
    ResponseEntity<BookResponse> register(@Valid @RequestBody RegisterBookRequest request) {
        Book book = registerBookUseCase.register(BookWebMapper.toCommand(request));
        return ResponseEntity.created(URI.create("/api/v1/books/" + book.getId().value()))
                .body(BookWebMapper.toResponse(book));
    }
}
