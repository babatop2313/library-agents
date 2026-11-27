import { HttpErrorResponse, HttpParams, HttpRequest, HttpResponse } from '@angular/common/http';

import mockUsers from '../../../mocks/auth/users.json';
import { CurrentUser, Role } from '../auth/current-user.model';
import { ProblemDetail } from '../http/problem-detail';
import { MockCatalogue } from './mock-catalogue';
import { MockLoans } from './mock-loans';

const BOOK_ETRANGER = '00000000-0000-4000-8000-000000001001'; // 3 exemplaires, 2 disponibles
const BOOK_PESTE = '00000000-0000-4000-8000-000000001002'; // 2 / 2
const BOOK_GERMINAL = '00000000-0000-4000-8000-000000001003'; // 5 / 4
const BOOK_NANA = '00000000-0000-4000-8000-000000001004'; // 2 / 1
const BOOK_PYTHON = '00000000-0000-4000-8000-000000001006'; // 1 / 1 : un seul exemplaire
const UNKNOWN_BOOK = '99999999-9999-4999-8999-999999999999';

const TODAY = new Date(2026, 10, 24, 12, 0, 0); // 24 novembre 2026 (mois base 0)

function userNamed(email: string): CurrentUser {
  const found = mockUsers.find((u) => u.email === email);
  if (!found) {
    throw new Error(`Utilisateur fictif absent : ${email}`);
  }
  return { userId: found.userId, email: found.email, roles: found.roles as Role[], expiresAt: '' };
}

/** RM-LOAN-27 : emprunts simulés (création, limite à 3, retard, déjà emprunté, rendu, état partagé avec le catalogue). */
describe('MockLoans', () => {
  const member = userNamed('membre@exemple.test'); // MEMBER, aucun emprunt
  const lateMember = userNamed('membre-retard@exemple.test'); // un emprunt en retard + un en cours
  const both = userNamed('membre-bibliothecaire@exemple.test'); // LIBRARIAN + MEMBER, 2 emprunts dont un livre absent
  const librarian = userNamed('bibliothecaire@exemple.test'); // LIBRARIAN seul

  let catalogue: MockCatalogue;
  let loans: MockLoans;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(TODAY);
    catalogue = new MockCatalogue();
    loans = new MockLoans(catalogue);
  });

  afterEach(() => vi.useRealTimers());

  function borrowRequest(body: unknown): HttpRequest<unknown> {
    return new HttpRequest('POST', '/api/v1/loans', body);
  }

  function ok<T>(result: HttpResponse<unknown> | HttpErrorResponse, status: number): T {
    expect(result).toBeInstanceOf(HttpResponse);
    expect((result as HttpResponse<unknown>).status).toBe(status);
    return (result as HttpResponse<T>).body as T;
  }

  function refusal(result: HttpResponse<unknown> | HttpErrorResponse): ProblemDetail & {
    httpStatus: number;
  } {
    expect(result).toBeInstanceOf(HttpErrorResponse);
    const error = result as HttpErrorResponse;
    return { ...(error.error as ProblemDetail), httpStatus: error.status };
  }

  function borrow(user: CurrentUser, bookId: string): HttpResponse<unknown> | HttpErrorResponse {
    return loans.borrow(borrowRequest({ bookId }), user);
  }

  interface Listed {
    id: string;
    bookId: string;
    title: string | null;
    author: string | null;
    isbn: string | null;
    borrowedOn: string;
    dueOn: string;
    overdue: boolean;
  }

  function list(user: CurrentUser): Listed[] {
    return ok<Listed[]>(loans.list(user), 200);
  }

  function availableCopies(bookId: string): number | undefined {
    const request = new HttpRequest('GET', '/api/v1/books', {
      params: new HttpParams().set('size', '50'),
    });
    const page = ok<{ content: { id: string; availableCopies: number }[] }>(
      catalogue.search(request, ['MEMBER']),
      200,
    );
    return page.content.find((book) => book.id === bookId)?.availableCopies;
  }

  describe('emprunter', () => {
    it('refuse un LIBRARIAN seul en 403 ACCESS_DENIED', () => {
      const error = refusal(borrow(librarian, BOOK_PESTE));

      expect(error.httpStatus).toBe(403);
      expect(error.code).toBe('ACCESS_DENIED');
    });

    it('crée un emprunt (201) échéant 14 jours plus tard, RM-03', () => {
      const created = ok<{ id: string; bookId: string; borrowedOn: string; dueOn: string }>(
        borrow(member, BOOK_PESTE),
        201,
      );

      expect(created.bookId).toBe(BOOK_PESTE);
      expect(created.borrowedOn).toBe('2026-11-24');
      expect(created.dueOn).toBe('2026-12-08');
    });

    it('renvoie l’en-tête Location de l’emprunt créé', () => {
      const result = borrow(member, BOOK_PESTE) as HttpResponse<{ id: string }>;

      expect(result.headers.get('Location')).toBe(`/api/v1/loans/${result.body?.id}`);
    });

    it('ajoute l’emprunt à la liste du membre, avec les informations du livre', () => {
      borrow(member, BOOK_PESTE);

      const [loan] = list(member);
      expect(loan.title).toBe('La Peste');
      expect(loan.author).toBe('Albert Camus');
      expect(loan.isbn).toBe('9782070360420');
      expect(loan.overdue).toBe(false);
    });

    it('décrémente les exemplaires disponibles du catalogue simulé', () => {
      const before = availableCopies(BOOK_GERMINAL) as number;

      borrow(member, BOOK_GERMINAL);

      expect(availableCopies(BOOK_GERMINAL)).toBe(before - 1);
    });

    it('retire du catalogue le livre dont le dernier exemplaire est emprunté', () => {
      borrow(member, BOOK_PYTHON);

      expect(availableCopies(BOOK_PYTHON)).toBeUndefined();
    });

    it('refuse un bookId absent en 400 VALIDATION_FAILED / REQUIRED', () => {
      const error = refusal(loans.borrow(borrowRequest({}), member));

      expect(error.httpStatus).toBe(400);
      expect(error.code).toBe('VALIDATION_FAILED');
      expect(error.errors?.map((e) => `${e.field}:${e.code}`)).toEqual(['bookId:REQUIRED']);
    });

    it('traite un bookId nul comme absent', () => {
      const error = refusal(loans.borrow(borrowRequest({ bookId: null }), member));

      expect(error.errors?.[0].code).toBe('REQUIRED');
    });

    it.each([['abc'], [42]])('refuse le bookId %j en 400 INVALID_FORMAT', (bookId) => {
      const error = refusal(loans.borrow(borrowRequest({ bookId }), member));

      expect(error.httpStatus).toBe(400);
      expect(error.errors?.map((e) => `${e.field}:${e.code}`)).toEqual(['bookId:INVALID_FORMAT']);
    });

    it('refuse un livre inconnu en 404 BOOK_NOT_FOUND', () => {
      const error = refusal(borrow(member, UNKNOWN_BOOK));

      expect(error.httpStatus).toBe(404);
      expect(error.code).toBe('BOOK_NOT_FOUND');
    });

    it('refuse un membre ayant un emprunt en retard en 409 MEMBER_HAS_OVERDUE_LOAN, RM-02', () => {
      const error = refusal(borrow(lateMember, BOOK_NANA));

      expect(error.httpStatus).toBe(409);
      expect(error.code).toBe('MEMBER_HAS_OVERDUE_LOAN');
      expect(availableCopies(BOOK_NANA)).toBe(1);
    });

    it('refuse un 4e emprunt actif en 409 LOAN_LIMIT_REACHED, RM-01', () => {
      ok(borrow(member, BOOK_ETRANGER), 201);
      ok(borrow(member, BOOK_PESTE), 201);
      ok(borrow(member, BOOK_GERMINAL), 201);

      const error = refusal(borrow(member, BOOK_NANA));

      expect(error.httpStatus).toBe(409);
      expect(error.code).toBe('LOAN_LIMIT_REACHED');
      expect(list(member)).toHaveLength(3);
    });

    it('refuse un livre déjà emprunté par le membre en 409 BOOK_ALREADY_BORROWED', () => {
      ok(borrow(member, BOOK_PESTE), 201);
      const stock = availableCopies(BOOK_PESTE);

      const error = refusal(borrow(member, BOOK_PESTE));

      expect(error.httpStatus).toBe(409);
      expect(error.code).toBe('BOOK_ALREADY_BORROWED');
      expect(availableCopies(BOOK_PESTE)).toBe(stock);
    });

    it('refuse un livre sans exemplaire disponible en 409 BOOK_NOT_AVAILABLE, RM-04', () => {
      ok(borrow(member, BOOK_PYTHON), 201);

      const error = refusal(borrow(both, BOOK_PYTHON));

      expect(error.httpStatus).toBe(409);
      expect(error.code).toBe('BOOK_NOT_AVAILABLE');
    });

    it('évalue le retard avant la limite (ordre du contrat)', () => {
      // lateMember a un retard et 2 emprunts : ajouter un 3e puis tester le 4e donne bien le retard.
      const error = refusal(borrow(lateMember, BOOK_ETRANGER));

      expect(error.code).toBe('MEMBER_HAS_OVERDUE_LOAN');
    });
  });

  describe('lister', () => {
    it('renvoie une liste vide pour un membre sans emprunt', () => {
      expect(list(member)).toEqual([]);
    });

    it('refuse un LIBRARIAN seul en 403', () => {
      expect(refusal(loans.list(librarian)).httpStatus).toBe(403);
    });

    it('trie par échéance croissante et marque le retard', () => {
      const result = list(lateMember);

      expect(result.map((loan) => loan.overdue)).toEqual([true, false]);
      expect(result[0].dueOn < result[1].dueOn).toBe(true);
      expect(result[0].title).toBe('La Peste');
    });

    it('calcule les dates de la graine à partir du jour courant', () => {
      const [late] = list(lateMember);

      expect(late.borrowedOn).toBe('2026-11-08');
      expect(late.dueOn).toBe('2026-11-22');
    });

    it('ne renvoie que les emprunts du membre connecté', () => {
      expect(list(lateMember).map((loan) => loan.bookId)).toEqual([BOOK_PESTE, BOOK_GERMINAL]);
      expect(list(both).map((loan) => loan.bookId)).toContain(BOOK_ETRANGER);
    });

    it('renvoie des valeurs nulles pour un livre absent du catalogue (RM-LOAN-32)', () => {
      const orphan = list(both).find((loan) => loan.title === null);

      expect(orphan).toBeDefined();
      expect(orphan?.isbn).toBeNull();
      expect(orphan?.author).toBeNull();
    });
  });

  describe('rendre', () => {
    it('rend un emprunt (204), le retire de la liste et réincrémente le catalogue', () => {
      const before = availableCopies(BOOK_GERMINAL) as number;
      const created = ok<{ id: string }>(borrow(member, BOOK_GERMINAL), 201);
      expect(availableCopies(BOOK_GERMINAL)).toBe(before - 1);

      const result = loans.giveBack(created.id, member);

      expect(ok(result, 204)).toBeNull();
      expect(list(member)).toEqual([]);
      expect(availableCopies(BOOK_GERMINAL)).toBe(before);
    });

    it('fait réapparaître au catalogue le livre rendu', () => {
      const created = ok<{ id: string }>(borrow(member, BOOK_PYTHON), 201);
      expect(availableCopies(BOOK_PYTHON)).toBeUndefined();

      loans.giveBack(created.id, member);

      expect(availableCopies(BOOK_PYTHON)).toBe(1);
    });

    it('refuse un second retour en 409 LOAN_ALREADY_RETURNED, sans second effet, RM-05', () => {
      const before = availableCopies(BOOK_PESTE);
      const created = ok<{ id: string }>(borrow(member, BOOK_PESTE), 201);
      loans.giveBack(created.id, member);

      const error = refusal(loans.giveBack(created.id, member));

      expect(error.httpStatus).toBe(409);
      expect(error.code).toBe('LOAN_ALREADY_RETURNED');
      expect(availableCopies(BOOK_PESTE)).toBe(before);
    });

    it('répond 404 LOAN_NOT_FOUND pour un identifiant inconnu ou mal formé', () => {
      for (const id of [UNKNOWN_BOOK, 'abc']) {
        const error = refusal(loans.giveBack(id, member));

        expect(error.httpStatus).toBe(404);
        expect(error.code).toBe('LOAN_NOT_FOUND');
      }
    });

    it('répond 404 pour l’emprunt d’un autre membre, comme pour un identifiant inconnu', () => {
      const [other] = list(lateMember);

      const error = refusal(loans.giveBack(other.id, member));

      expect(error.httpStatus).toBe(404);
      expect(error.code).toBe('LOAN_NOT_FOUND');
      expect(list(lateMember)).toHaveLength(2);
    });

    it('refuse un LIBRARIAN seul en 403', () => {
      expect(
        refusal(loans.giveBack('a1b2c3d4-0000-4000-8000-000000000101', librarian)).httpStatus,
      ).toBe(403);
    });

    it('rend un emprunt en retard normalement et lève le blocage, RM-02', () => {
      const [late] = list(lateMember);
      expect(late.overdue).toBe(true);
      expect(refusal(borrow(lateMember, BOOK_NANA)).code).toBe('MEMBER_HAS_OVERDUE_LOAN');

      ok(loans.giveBack(late.id, lateMember), 204);

      expect(ok(borrow(lateMember, BOOK_NANA), 201)).toBeTruthy();
    });

    it('un retour libère une place sous la limite de 3 emprunts', () => {
      const first = ok<{ id: string }>(borrow(member, BOOK_ETRANGER), 201);
      ok(borrow(member, BOOK_PESTE), 201);
      ok(borrow(member, BOOK_GERMINAL), 201);
      expect(refusal(borrow(member, BOOK_NANA)).code).toBe('LOAN_LIMIT_REACHED');

      loans.giveBack(first.id, member);

      ok(borrow(member, BOOK_NANA), 201);
    });
  });

  /** Cohérence du stock : chaque emprunt actif de la graine occupe un exemplaire du catalogue. */
  describe('stock de la graine', () => {
    function stock(bookId: string, fresh: MockCatalogue): number | undefined {
      const request = new HttpRequest('GET', '/api/v1/books', {
        params: new HttpParams().set('size', '50'),
      });
      const page = ok<{ content: { id: string; availableCopies: number }[] }>(
        fresh.search(request, ['MEMBER']),
        200,
      );
      return page.content.find((book) => book.id === bookId)?.availableCopies;
    }

    it('retire un exemplaire par emprunt actif de la graine (Peste 2→1, Germinal 4→3, Étranger 2→1)', () => {
      const fresh = new MockCatalogue();
      expect([BOOK_PESTE, BOOK_GERMINAL, BOOK_ETRANGER].map((id) => stock(id, fresh))).toEqual([
        2, 4, 2,
      ]);

      new MockLoans(fresh);

      expect([BOOK_PESTE, BOOK_GERMINAL, BOOK_ETRANGER].map((id) => stock(id, fresh))).toEqual([
        1, 3, 1,
      ]);
    });

    it('ignore l’emprunt de la graine dont le livre est inconnu (aucun autre stock modifié)', () => {
      const fresh = new MockCatalogue();
      const untouched = [BOOK_NANA, BOOK_PYTHON].map((id) => stock(id, fresh));

      new MockLoans(fresh);

      expect([BOOK_NANA, BOOK_PYTHON].map((id) => stock(id, fresh))).toEqual(untouched);
    });

    it('rendre un emprunt de la graine rétablit le stock d’origine', () => {
      const fresh = new MockCatalogue();
      const seeded = new MockLoans(fresh);
      const [late] = ok<Listed[]>(seeded.list(lateMember), 200);
      expect(late.bookId).toBe(BOOK_PESTE);

      ok(seeded.giveBack(late.id, lateMember), 204);

      expect(stock(BOOK_PESTE, fresh)).toBe(2);
    });
  });
});
