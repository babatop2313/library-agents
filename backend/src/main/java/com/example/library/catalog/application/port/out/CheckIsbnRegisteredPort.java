package com.example.library.catalog.application.port.out;

import com.example.library.catalog.domain.model.Isbn;

public interface CheckIsbnRegisteredPort {

    boolean isRegistered(Isbn isbn);
}
