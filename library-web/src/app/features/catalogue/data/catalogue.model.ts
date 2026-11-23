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

/** Paramètres de `GET /api/v1/books` ; `page` en base 0, comme l'API. */
export interface BookSearchParams {
  page: number;
  size: number;
  q?: string;
}
