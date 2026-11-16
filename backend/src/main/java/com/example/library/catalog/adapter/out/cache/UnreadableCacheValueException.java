package com.example.library.catalog.adapter.out.cache;

/** Valeur du cache illisible ou incohérente. Le message ne contient jamais la valeur (RC-18). */
final class UnreadableCacheValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnreadableCacheValueException(String reason) {
        super(reason);
    }

    UnreadableCacheValueException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
