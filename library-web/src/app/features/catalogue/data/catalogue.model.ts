/** Livre tel que renvoyé par `/api/v1/books` (DTO `Book` du contrat). */
export interface Book {
  id: string;
  isbn: string;
  title: string;
  author: string;
  totalCopies: number;
  availableCopies: number;
}

/** Corps de `POST /api/v1/books` (DTO `RegisterBookRequest`). */
export interface RegisterBookRequest {
  isbn: string;
  title: string;
  author: string;
  copies: number;
}

/** Corps de `POST /api/v1/loans` (DTO `BorrowBookRequest`, contrat `docs/api/loan.md`). */
export interface BorrowBookRequest {
  bookId: string;
}

/** Réponse 201 de `POST /api/v1/loans` (DTO `LoanCreated`). Dates `yyyy-MM-dd`, sans fuseau. */
export interface LoanCreated {
  id: string;
  bookId: string;
  borrowedOn: string;
  dueOn: string;
}

/** Paramètres de `GET /api/v1/books` ; `page` en base 0, comme l'API. */
export interface BookSearchParams {
  page: number;
  size: number;
  q?: string;
}
