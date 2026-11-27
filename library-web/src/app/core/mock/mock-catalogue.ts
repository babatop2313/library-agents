import { HttpErrorResponse, HttpHeaders, HttpRequest, HttpResponse } from '@angular/common/http';

import accessDenied from '../../../mocks/catalogue/access-denied.json';
import initialBooks from '../../../mocks/catalogue/books.json';
import isbnAlreadyExists from '../../../mocks/catalogue/isbn-already-exists.json';
import validationFailed from '../../../mocks/catalogue/validation-failed.json';
import { Role } from '../auth/current-user.model';
import { FieldError, ProblemDetail } from '../http/problem-detail';
import { problem } from './mock-problem';

// Types locaux : `core` n'importe jamais `features` (voir docs/api/catalogue.md pour le contrat).
interface MockBook {
  id: string;
  isbn: string;
  title: string;
  author: string;
  totalCopies: number;
  availableCopies: number;
}

/** Champs d'un livre repris par les emprunts (`title`, `author`, `isbn` du DTO `CurrentLoan`). */
export interface MockBookSummary {
  id: string;
  isbn: string;
  title: string;
  author: string;
}

type MockResult = HttpResponse<unknown> | HttpErrorResponse;

const BOOKS_URL = '/api/v1/books';
const DEFAULT_PAGE = 0;
const DEFAULT_SIZE = 10;
const MAX_SIZE = 50;
const SEARCH_MAX_LENGTH = 100;
const TEXT_MAX_LENGTH = 255;
const COPIES_MIN = 1;
const COPIES_MAX = 1000;
const INT_MAX = 2147483647;
const INTEGER = /^[+-]?\d+$/;

/**
 * Catalogue simulé (configuration `mock`) : mêmes règles que `GET` et `POST /api/v1/books`
 * (validation par champ, tri, filtre, rôles, doublon d'ISBN). État en mémoire : un livre créé
 * apparaît dans la liste jusqu'au rechargement de la page.
 */
export class MockCatalogue {
  private readonly books: MockBook[] = (initialBooks as MockBook[]).map((book) => ({ ...book }));

  /** Liste paginée des livres disponibles (`LIBRARIAN` ou `MEMBER`). */
  search(req: HttpRequest<unknown>, roles: Role[]): MockResult {
    if (!roles.includes('LIBRARIAN') && !roles.includes('MEMBER')) {
      return problem(accessDenied as ProblemDetail);
    }

    const errors: FieldError[] = [];
    const page = this.readInt(req.params.get('page'), 'page', DEFAULT_PAGE, 0, INT_MAX, errors);
    const size = this.readInt(req.params.get('size'), 'size', DEFAULT_SIZE, 1, MAX_SIZE, errors);
    const query = (req.params.get('q') ?? '').trim();
    if (query.length > SEARCH_MAX_LENGTH) {
      errors.push({
        field: 'q',
        code: 'TOO_LONG',
        message: "Le paramètre 'q' est trop long (100 caractères maximum)",
      });
    }
    if (errors.length > 0) {
      return problem({ ...(validationFailed as ProblemDetail), errors });
    }

    const needle = query.toLowerCase();
    const matching = this.books
      .filter((book) => book.availableCopies >= 1)
      .filter(
        (book) =>
          needle === '' ||
          book.title.toLowerCase().includes(needle) ||
          book.author.toLowerCase().includes(needle),
      )
      .sort(compareCatalogOrder);

    const start = page * size;
    return new HttpResponse({
      status: 200,
      body: {
        content: matching.slice(start, start + size).map((book) => ({ ...book })),
        page,
        size,
        totalElements: matching.length,
        totalPages: Math.ceil(matching.length / size),
      },
    });
  }

  /** Enregistre un livre (`LIBRARIAN`) : 403, puis 400 par champ, puis 409. */
  register(req: HttpRequest<unknown>, roles: Role[]): MockResult {
    if (!roles.includes('LIBRARIAN')) {
      return problem(accessDenied as ProblemDetail);
    }

    const body = isRecord(req.body) ? req.body : {};
    const errors: FieldError[] = [];
    const isbn = this.readIsbn(body['isbn'], errors);
    const title = this.readText(body['title'], 'title', errors);
    const author = this.readText(body['author'], 'author', errors);
    const copies = this.readCopies(body['copies'], errors);
    if (
      errors.length > 0 ||
      isbn === null ||
      title === null ||
      author === null ||
      copies === null
    ) {
      return problem({ ...(validationFailed as ProblemDetail), errors });
    }

    if (this.books.some((book) => book.isbn === isbn)) {
      return problem(isbnAlreadyExists as ProblemDetail);
    }
    const book: MockBook = {
      id: crypto.randomUUID(),
      isbn,
      title,
      author,
      totalCopies: copies,
      availableCopies: copies,
    };
    this.books.push(book);
    return new HttpResponse({
      status: 201,
      body: { ...book },
      headers: new HttpHeaders({ Location: `${BOOKS_URL}/${book.id}` }),
    });
  }

  /** Résumé d'un livre (état partagé avec les emprunts simulés) ; `null` s'il n'existe pas. */
  findById(id: string): MockBookSummary | null {
    const book = this.books.find((candidate) => candidate.id === id);
    return book ? { id: book.id, isbn: book.isbn, title: book.title, author: book.author } : null;
  }

  /** Prend un exemplaire (emprunt) : faux s'il n'en reste aucun ou si le livre n'existe pas. */
  takeCopy(id: string): boolean {
    const book = this.books.find((candidate) => candidate.id === id);
    if (!book || book.availableCopies < 1) {
      return false;
    }
    book.availableCopies -= 1;
    return true;
  }

  /** Rend un exemplaire (retour), sans dépasser le total ; sans effet si le livre n'existe plus. */
  returnCopy(id: string): void {
    const book = this.books.find((candidate) => candidate.id === id);
    if (book && book.availableCopies < book.totalCopies) {
      book.availableCopies += 1;
    }
  }

  private readInt(
    raw: string | null,
    field: string,
    fallback: number,
    min: number,
    max: number,
    errors: FieldError[],
  ): number {
    if (raw === null || raw === '') {
      return fallback;
    }
    const value = Number(raw);
    if (!INTEGER.test(raw) || Math.abs(value) > INT_MAX) {
      errors.push({
        field,
        code: 'INVALID_FORMAT',
        message: `Le paramètre '${field}' doit être un entier`,
      });
      return fallback;
    }
    if (value < min || value > max) {
      errors.push({
        field,
        code: 'OUT_OF_RANGE',
        message:
          field === 'page'
            ? "Le paramètre 'page' doit être un entier supérieur ou égal à 0"
            : `Le paramètre '${field}' doit être un entier compris entre ${min} et ${max}`,
      });
      return fallback;
    }
    return value;
  }

  private readIsbn(raw: unknown, errors: FieldError[]): string | null {
    if (raw === undefined || raw === null) {
      errors.push({ field: 'isbn', code: 'REQUIRED', message: "Le champ 'isbn' est obligatoire" });
      return null;
    }
    const normalized = typeof raw === 'string' ? raw.replace(/[\s-]/g, '') : '';
    if (!/^\d{13}$/.test(normalized)) {
      errors.push({
        field: 'isbn',
        code: 'INVALID_FORMAT',
        message: "Le champ 'isbn' a un format invalide",
      });
      return null;
    }
    return normalized;
  }

  private readText(raw: unknown, field: string, errors: FieldError[]): string | null {
    if (raw === undefined || raw === null) {
      errors.push({ field, code: 'REQUIRED', message: `Le champ '${field}' est obligatoire` });
      return null;
    }
    if (typeof raw !== 'string') {
      errors.push({
        field,
        code: 'INVALID_FORMAT',
        message: `Le champ '${field}' a un format invalide`,
      });
      return null;
    }
    const value = raw.trim();
    if (value === '') {
      errors.push({ field, code: 'BLANK', message: `Le champ '${field}' ne doit pas être vide` });
      return null;
    }
    if (value.length > TEXT_MAX_LENGTH) {
      errors.push({
        field,
        code: 'TOO_LONG',
        message: `Le champ '${field}' est trop long (255 caractères maximum)`,
      });
      return null;
    }
    return value;
  }

  private readCopies(raw: unknown, errors: FieldError[]): number | null {
    if (raw === undefined || raw === null) {
      errors.push({
        field: 'copies',
        code: 'REQUIRED',
        message: "Le champ 'copies' est obligatoire",
      });
      return null;
    }
    if (typeof raw !== 'number' || !Number.isInteger(raw)) {
      errors.push({
        field: 'copies',
        code: 'INVALID_FORMAT',
        message: "Le champ 'copies' a un format invalide",
      });
      return null;
    }
    if (raw < COPIES_MIN || raw > COPIES_MAX) {
      errors.push({
        field: 'copies',
        code: 'OUT_OF_RANGE',
        message: "Le champ 'copies' est hors des bornes autorisées",
      });
      return null;
    }
    return raw;
  }
}

/** Ordre du catalogue : titre en minuscules, puis ISBN, par unités de code (comme `COLLATE "C"`). */
function compareCatalogOrder(a: MockBook, b: MockBook): number {
  const titleA = a.title.toLowerCase();
  const titleB = b.title.toLowerCase();
  if (titleA !== titleB) {
    return titleA < titleB ? -1 : 1;
  }
  if (a.isbn === b.isbn) {
    return 0;
  }
  return a.isbn < b.isbn ? -1 : 1;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
