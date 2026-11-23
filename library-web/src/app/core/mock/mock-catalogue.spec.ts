import {
  HttpClient,
  HttpErrorResponse,
  HttpResponse,
  provideHttpClient,
  withInterceptorsFromDi,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import initialBooks from '../../../mocks/catalogue/books.json';
import mockUsers from '../../../mocks/auth/users.json';
import { Page } from '../http/page';
import { ProblemDetail } from '../http/problem-detail';
import { mockProviders } from './mock.providers.mock';

interface MockBook {
  id: string;
  isbn: string;
  title: string;
  author: string;
  totalCopies: number;
  availableCopies: number;
}

const URL = '/api/v1/books';
const librarian = mockUsers[0];
const member = mockUsers[1];
const AVAILABLE = (initialBooks as MockBook[]).filter((b) => b.availableCopies >= 1);

/** Q12 : le catalogue est simulé en mode `mock` selon le contrat (liste, recherche, pagination, enregistrement). */
describe('Catalogue simulé (mode mock)', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
        ...mockProviders,
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
    vi.useRealTimers();
  });

  async function respond<T>(request: Promise<T>): Promise<T> {
    await vi.advanceTimersByTimeAsync(400);
    return request;
  }

  async function failure(request: Promise<unknown>): Promise<HttpErrorResponse> {
    const result = request.then(
      () => {
        throw new Error('une erreur était attendue');
      },
      (error: unknown) => error as HttpErrorResponse,
    );
    await vi.advanceTimersByTimeAsync(400);
    return result;
  }

  async function loginAs(user: { email: string; password: string }): Promise<void> {
    await respond(firstValueFrom(http.post('/api/v1/auth/login', user)));
  }

  function list(params: Record<string, string> = {}): Promise<Page<MockBook>> {
    return firstValueFrom(http.get<Page<MockBook>>(URL, { params }));
  }

  function titles(page: Page<MockBook>): string[] {
    return page.content.map((book) => book.title);
  }

  const NEW_BOOK = {
    isbn: '978-2-07-036822-8',
    title: 'Un nouveau livre',
    author: 'Quelqu’un',
    copies: 3,
  };

  describe('GET /api/v1/books', () => {
    it('répond 401 UNAUTHENTICATED sans session', async () => {
      const error = await failure(list());

      expect(error.status).toBe(401);
      expect((error.error as ProblemDetail).code).toBe('UNAUTHENTICATED');
    });

    it('renvoie la première page de 10 livres disponibles, avec l’enveloppe du contrat', async () => {
      await loginAs(member);

      const page = await respond(list());

      expect(page.content).toHaveLength(10);
      expect(page.page).toBe(0);
      expect(page.size).toBe(10);
      expect(page.totalElements).toBe(AVAILABLE.length);
      expect(page.totalPages).toBe(Math.ceil(AVAILABLE.length / 10));
      expect(Object.keys(page.content[0]).sort()).toEqual([
        'author',
        'availableCopies',
        'id',
        'isbn',
        'title',
        'totalCopies',
      ]);
    });

    it('accepte un bibliothécaire comme un membre', async () => {
      await loginAs(librarian);

      const page = await respond(list());

      expect(page.totalElements).toBe(AVAILABLE.length);
    });

    it('exclut les livres sans exemplaire disponible de la liste et du total', async () => {
      await loginAs(member);

      const page = await respond(list({ size: '50' }));

      expect(page.content.every((book) => book.availableCopies >= 1)).toBe(true);
      expect(titles(page)).not.toContain('Tout est prêté');
      expect(page.totalElements).toBe((initialBooks as MockBook[]).length - 2);
    });

    it('trie par titre sans tenir compte de la casse, puis par ISBN', async () => {
      await loginAs(member);

      const page = await respond(list({ size: '50' }));

      const expected = [...page.content].sort((a, b) => {
        const titleA = a.title.toLowerCase();
        const titleB = b.title.toLowerCase();
        if (titleA !== titleB) {
          return titleA < titleB ? -1 : 1;
        }
        return a.isbn < b.isbn ? -1 : 1;
      });
      expect(page.content).toEqual(expected);
    });

    it('pages successives : sans chevauchement ni perte', async () => {
      await loginAs(member);
      const all: string[] = [];
      for (let p = 0; p < Math.ceil(AVAILABLE.length / 10); p++) {
        all.push(...titles(await respond(list({ page: String(p), size: '10' }))));
      }

      expect(all).toHaveLength(AVAILABLE.length);
      expect(new Set(all).size).toBe(AVAILABLE.length);
    });

    it('page au-delà de la dernière : 200, contenu vide, totaux corrects', async () => {
      await loginAs(member);

      const page = await respond(list({ page: '99' }));

      expect(page.content).toEqual([]);
      expect(page.totalElements).toBe(AVAILABLE.length);
    });

    it('recherche insensible à la casse sur le titre ou l’auteur', async () => {
      await loginAs(member);

      const lower = await respond(list({ q: 'camus' }));
      const upper = await respond(list({ q: 'CAMUS' }));
      const byTitle = await respond(list({ q: 'peste' }));

      expect(titles(lower)).toEqual(["L'Étranger", 'La Peste']);
      expect(titles(upper)).toEqual(titles(lower));
      expect(lower.totalElements).toBe(2);
      expect(titles(byTitle)).toEqual(['La Peste']);
    });

    it('recherche sensible aux accents', async () => {
      await loginAs(member);

      const page = await respond(list({ q: 'etranger' }));

      expect(page.content).toEqual([]);
    });

    it('recherche sans résultat : 200, vide, totaux à 0', async () => {
      await loginAs(member);

      const page = await respond(list({ q: 'introuvab' }));

      expect(page.content).toEqual([]);
      expect(page.totalElements).toBe(0);
      expect(page.totalPages).toBe(0);
    });

    it('q vide ou blanc : aucun filtre', async () => {
      await loginAs(member);

      expect((await respond(list({ q: '' }))).totalElements).toBe(AVAILABLE.length);
      expect((await respond(list({ q: '   ' }))).totalElements).toBe(AVAILABLE.length);
    });

    it('les caractères %, _ et \\ sont littéraux', async () => {
      await loginAs(member);

      expect(titles(await respond(list({ q: '100%' })))).toEqual(['100% Python']);
      expect(titles(await respond(list({ q: '%' })))).toEqual(['100% Python']);
      expect(titles(await respond(list({ q: '_' })))).toEqual(['Mon_livre_test']);
      expect(titles(await respond(list({ q: '\\' })))).toEqual(['Chemin\\Secret']);
    });

    it('ne cherche pas dans l’ISBN', async () => {
      await loginAs(member);

      const page = await respond(list({ q: '9782070360024' }));

      expect(page.totalElements).toBe(0);
    });

    it('recherche et pagination combinées : filtre avant pagination', async () => {
      await loginAs(member);

      const page = await respond(list({ q: 'hugo', size: '10', page: '1' }));

      const hugo = AVAILABLE.filter((b) => b.author.toLowerCase().includes('hugo')).length;
      expect(page.totalElements).toBe(hugo);
      expect(page.content).toHaveLength(hugo - 10);
    });

    it('ignore un paramètre sort inconnu', async () => {
      await loginAs(member);

      const plain = await respond(list());
      const sorted = await respond(list({ sort: 'author,desc' }));

      expect(sorted).toEqual(plain);
    });

    it.each([
      ['page', '-1', 'OUT_OF_RANGE'],
      ['page', 'abc', 'INVALID_FORMAT'],
      ['size', '0', 'OUT_OF_RANGE'],
      ['size', '51', 'OUT_OF_RANGE'],
      ['size', 'abc', 'INVALID_FORMAT'],
    ])('400 VALIDATION_FAILED pour %s=%s', async (param, value, code) => {
      await loginAs(member);

      const error = await failure(list({ [param]: value }));

      expect(error.status).toBe(400);
      const body = error.error as ProblemDetail;
      expect(body.code).toBe('VALIDATION_FAILED');
      expect(body.errors).toEqual([expect.objectContaining({ field: param, code })]);
    });

    it('400 pour un q de 101 caractères, 200 pour 100', async () => {
      await loginAs(member);

      const error = await failure(list({ q: 'a'.repeat(101) }));
      const ok = await respond(list({ q: 'a'.repeat(100) }));

      expect(error.status).toBe(400);
      expect((error.error as ProblemDetail).errors?.[0].field).toBe('q');
      expect(ok.totalElements).toBe(0);
    });

    it('rapporte toutes les erreurs de paramètres ensemble', async () => {
      await loginAs(member);

      const error = await failure(list({ page: '-1', size: '99', q: 'a'.repeat(101) }));

      expect((error.error as ProblemDetail).errors?.map((e) => e.field)).toEqual([
        'page',
        'size',
        'q',
      ]);
    });
  });

  describe('POST /api/v1/books', () => {
    function register(body: unknown): Promise<HttpResponse<MockBook>> {
      return firstValueFrom(http.post<MockBook>(URL, body, { observe: 'response' }));
    }

    it('répond 401 sans session', async () => {
      expect((await failure(register(NEW_BOOK))).status).toBe(401);
    });

    it('répond 403 ACCESS_DENIED pour un membre', async () => {
      await loginAs(member);

      const error = await failure(register(NEW_BOOK));

      expect(error.status).toBe(403);
      expect((error.error as ProblemDetail).code).toBe('ACCESS_DENIED');
    });

    it('crée un livre : 201, Location, tous les exemplaires disponibles, ISBN normalisé', async () => {
      await loginAs(librarian);

      const response = await respond(register(NEW_BOOK));

      expect(response.status).toBe(201);
      expect(response.body?.isbn).toBe('9782070368228');
      expect(response.body?.totalCopies).toBe(3);
      expect(response.body?.availableCopies).toBe(3);
      expect(response.headers.get('Location')).toBe(`${URL}/${response.body?.id}`);
    });

    it('le livre créé apparaît ensuite dans la liste et la recherche', async () => {
      await loginAs(librarian);
      await respond(register(NEW_BOOK));

      const page = await respond(list({ q: 'nouveau livre' }));

      expect(titles(page)).toEqual(['Un nouveau livre']);
      expect((await respond(list())).totalElements).toBe(AVAILABLE.length + 1);
    });

    it('409 ISBN_ALREADY_EXISTS sans errors[] pour un ISBN existant (même avec tirets)', async () => {
      await loginAs(librarian);
      await respond(register(NEW_BOOK));

      const error = await failure(register({ ...NEW_BOOK, isbn: '9782070368228' }));

      expect(error.status).toBe(409);
      const body = error.error as ProblemDetail;
      expect(body.code).toBe('ISBN_ALREADY_EXISTS');
      expect(body.errors).toBeUndefined();
    });

    it('409 pour un ISBN du jeu de données initial', async () => {
      await loginAs(librarian);

      const error = await failure(register({ ...NEW_BOOK, isbn: '9782070360024' }));

      expect(error.status).toBe(409);
    });

    it('400 avec une erreur par champ pour un corps vide', async () => {
      await loginAs(librarian);

      const error = await failure(register({}));

      expect(error.status).toBe(400);
      const body = error.error as ProblemDetail;
      expect(body.code).toBe('VALIDATION_FAILED');
      expect(body.errors?.map((e) => `${e.field}:${e.code}`)).toEqual([
        'isbn:REQUIRED',
        'title:REQUIRED',
        'author:REQUIRED',
        'copies:REQUIRED',
      ]);
    });

    it('400 : ISBN mal formé, titre blanc, auteur trop long, exemplaires hors bornes', async () => {
      await loginAs(librarian);

      const error = await failure(
        register({ isbn: '123', title: '   ', author: 'a'.repeat(256), copies: 1001 }),
      );

      expect((error.error as ProblemDetail).errors?.map((e) => `${e.field}:${e.code}`)).toEqual([
        'isbn:INVALID_FORMAT',
        'title:BLANK',
        'author:TOO_LONG',
        'copies:OUT_OF_RANGE',
      ]);
    });

    it('400 : exemplaires décimal ou non numérique', async () => {
      await loginAs(librarian);

      const decimal = await failure(register({ ...NEW_BOOK, copies: 2.5 }));
      const text = await failure(register({ ...NEW_BOOK, copies: 'trois' }));

      expect((decimal.error as ProblemDetail).errors?.[0].code).toBe('INVALID_FORMAT');
      expect((text.error as ProblemDetail).errors?.[0].code).toBe('INVALID_FORMAT');
    });

    it('accepte les bornes : 255 caractères et 1 ou 1000 exemplaires', async () => {
      await loginAs(librarian);

      const first = await respond(
        register({
          isbn: '9780000000001',
          title: 'a'.repeat(255),
          author: 'b'.repeat(255),
          copies: 1,
        }),
      );
      const second = await respond(register({ ...NEW_BOOK, isbn: '9780000000002', copies: 1000 }));

      expect(first.status).toBe(201);
      expect(second.status).toBe(201);
    });
  });

  it('laisse passer vers la vraie API un endpoint de livre non simulé', () => {
    void firstValueFrom(http.get(`${URL}/00000000-0000-4000-8000-000000001001`));

    httpTesting.expectOne(`${URL}/00000000-0000-4000-8000-000000001001`).flush({});
  });
});
