CREATE TABLE book (
    id               UUID         NOT NULL,
    isbn             VARCHAR(13)  NOT NULL,
    title            VARCHAR(255) NOT NULL,
    author           VARCHAR(255) NOT NULL,
    total_copies     INTEGER      NOT NULL,
    available_copies INTEGER      NOT NULL,
    version          BIGINT       NOT NULL,
    CONSTRAINT pk_book PRIMARY KEY (id),
    CONSTRAINT uk_book_isbn UNIQUE (isbn),
    CONSTRAINT ck_book_isbn_format CHECK (isbn ~ '^[0-9]{13}$'),
    CONSTRAINT ck_book_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_book_author_not_blank CHECK (length(btrim(author)) > 0),
    CONSTRAINT ck_book_total_copies_min CHECK (total_copies >= 1),
    CONSTRAINT ck_book_available_copies_range CHECK (available_copies BETWEEN 0 AND total_copies)
);
