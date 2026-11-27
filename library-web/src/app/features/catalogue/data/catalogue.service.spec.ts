import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SKIP_ERROR_TOAST } from '../../../core/http/error-toast.context';
import { Page } from '../../../core/http/page';
import { Book } from './catalogue.model';
import { CatalogueService } from './catalogue.service';

const BOOK: Book = {
  id: '00000000-0000-4000-8000-000000001001',
  isbn: '9782070360024',
  title: "L'Étranger",
  author: 'Albert Camus',
  totalCopies: 3,
  availableCopies: 2,
};

describe('CatalogueService', () => {
  let service: CatalogueService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CatalogueService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  describe('search', () => {
    it('appelle GET /api/v1/books avec page et size, sans q (chemin relatif)', () => {
      service.search({ page: 0, size: 10 }).subscribe();

      const req = httpTesting.expectOne((r) => r.url === '/api/v1/books');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('10');
      expect(req.request.params.has('q')).toBe(false);
      req.flush({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    });

    it('envoie q=camus&page=0 pour une recherche', () => {
      service.search({ page: 0, size: 20, q: 'camus' }).subscribe();

      const req = httpTesting.expectOne((r) => r.url === '/api/v1/books');
      expect(req.request.params.get('q')).toBe('camus');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    });

    it('n’envoie pas q quand il est vide', () => {
      service.search({ page: 2, size: 50, q: '' }).subscribe();

      const req = httpTesting.expectOne((r) => r.url === '/api/v1/books');
      expect(req.request.params.has('q')).toBe(false);
      expect(req.request.params.get('page')).toBe('2');
      req.flush({ content: [], page: 2, size: 50, totalElements: 0, totalPages: 0 });
    });

    it('encode les caractères spéciaux de q dans la requête', () => {
      service.search({ page: 0, size: 10, q: '100% & co' }).subscribe();

      const req = httpTesting.expectOne((r) => r.url === '/api/v1/books');
      expect(req.request.params.get('q')).toBe('100% & co');
      expect(req.request.urlWithParams).toContain('q=100%25%20%26%20co');
      req.flush({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 });
    });

    it('renvoie la page telle que le backend la fournit', () => {
      const body: Page<Book> = {
        content: [BOOK],
        page: 0,
        size: 10,
        totalElements: 25,
        totalPages: 3,
      };
      let result: Page<Book> | undefined;

      service.search({ page: 0, size: 10 }).subscribe((page) => (result = page));
      httpTesting.expectOne((r) => r.url === '/api/v1/books').flush(body);

      expect(result).toEqual(body);
    });

    it('ne marque pas la requête de liste pour la suppression du toast', () => {
      service.search({ page: 0, size: 10 }).subscribe({ error: () => undefined });

      const req = httpTesting.expectOne((r) => r.url === '/api/v1/books');
      expect(req.request.context.get(SKIP_ERROR_TOAST).size).toBe(0);
      req.flush(null);
    });

    it('propage une erreur serveur à l’appelant', () => {
      let status: number | undefined;

      service
        .search({ page: 0, size: 10 })
        .subscribe({ error: (e: { status: number }) => (status = e.status) });
      httpTesting
        .expectOne((r) => r.url === '/api/v1/books')
        .flush(null, { status: 500, statusText: 'Server Error' });

      expect(status).toBe(500);
    });
  });

  describe('register', () => {
    const body = {
      isbn: '978-2-07-036822-8',
      title: "L'Étranger",
      author: 'Albert Camus',
      copies: 3,
    };

    it('envoie POST /api/v1/books avec isbn, title, author et copies', () => {
      service.register(body).subscribe();

      const req = httpTesting.expectOne('/api/v1/books');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(body);
      req.flush(BOOK, { status: 201, statusText: 'Created' });
    });

    it('désactive le toast générique pour les 400 et 409 seulement', () => {
      service.register(body).subscribe();

      const req = httpTesting.expectOne('/api/v1/books');
      expect(req.request.context.get(SKIP_ERROR_TOAST)).toEqual(new Set([400, 409]));
      req.flush(BOOK, { status: 201, statusText: 'Created' });
    });

    it('renvoie le livre créé', () => {
      let created: Book | undefined;

      service.register(body).subscribe((book) => (created = book));
      httpTesting.expectOne('/api/v1/books').flush(BOOK, { status: 201, statusText: 'Created' });

      expect(created).toEqual(BOOK);
    });

    it('propage une erreur 400 avec son ProblemDetail', () => {
      let error: { status: number; error: { code: string } } | undefined;
      service.register(body).subscribe({ error: (e: typeof error) => (error = e) });

      httpTesting
        .expectOne('/api/v1/books')
        .flush(
          { code: 'VALIDATION_FAILED', errors: [] },
          { status: 400, statusText: 'Bad Request' },
        );

      expect(error?.status).toBe(400);
      expect(error?.error.code).toBe('VALIDATION_FAILED');
    });
  });

  /** RM-LOAN-21, RM-LOAN-22 : emprunt depuis le catalogue (contrat docs/api/loan.md). */
  describe('borrow', () => {
    const CREATED = {
      id: '6d1f0c4b-8a2e-4f7a-b3d9-2c5e7a9b1f30',
      bookId: BOOK.id,
      borrowedOn: '2026-11-24',
      dueOn: '2026-12-08',
    };

    it('envoie POST /api/v1/loans avec le seul bookId', () => {
      service.borrow(BOOK.id).subscribe();

      const req = httpTesting.expectOne('/api/v1/loans');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ bookId: BOOK.id });
      req.flush(CREATED, { status: 201, statusText: 'Created' });
    });

    it('désactive le toast générique pour 404, 409 et 503 seulement', () => {
      service.borrow(BOOK.id).subscribe();

      const req = httpTesting.expectOne('/api/v1/loans');
      expect(req.request.context.get(SKIP_ERROR_TOAST)).toEqual(new Set([404, 409, 503]));
      req.flush(CREATED, { status: 201, statusText: 'Created' });
    });

    it('renvoie l’emprunt créé', () => {
      let created: unknown;

      service.borrow(BOOK.id).subscribe((loan) => (created = loan));
      httpTesting.expectOne('/api/v1/loans').flush(CREATED, { status: 201, statusText: 'Created' });

      expect(created).toEqual(CREATED);
    });

    it.each([404, 409, 503])('propage une erreur %i à l’appelant', (status) => {
      let received: number | undefined;
      service.borrow(BOOK.id).subscribe({ error: (e: { status: number }) => (received = e.status) });

      httpTesting
        .expectOne('/api/v1/loans')
        .flush({ code: 'X' }, { status, statusText: 'Error' });

      expect(received).toBe(status);
    });
  });
});
