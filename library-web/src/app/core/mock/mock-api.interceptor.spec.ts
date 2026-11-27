import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptorsFromDi,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import mockUsers from '../../../mocks/auth/users.json';
import { CurrentUser } from '../auth/current-user.model';
import { ProblemDetail } from '../http/problem-detail';
import { mockProviders } from './mock.providers.mock';

describe('MockApiInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  const librarian = mockUsers[0];

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

  function login(email: string, password: string): Promise<CurrentUser> {
    return firstValueFrom(http.post<CurrentUser>('/api/v1/auth/login', { email, password }));
  }

  function me(): Promise<CurrentUser> {
    return firstValueFrom(http.get<CurrentUser>('/api/v1/auth/me'));
  }

  it('connecte un utilisateur fictif et renvoie son identité sans jeton', async () => {
    const user = await respond(login(librarian.email, librarian.password));

    expect(user.email).toBe(librarian.email);
    expect(user.roles).toEqual(['LIBRARIAN']);
    expect(Object.keys(user).sort()).toEqual(['email', 'expiresAt', 'roles', 'userId']);
    expect(await respond(me())).toEqual(user);
  });

  it('répond après un délai réaliste (au moins 200 ms)', async () => {
    let done = false;
    void login(librarian.email, librarian.password).then(() => (done = true));

    await vi.advanceTimersByTimeAsync(199);
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(201);
    expect(done).toBe(true);
  });

  it('refuse un mauvais mot de passe en 401 INVALID_CREDENTIALS', async () => {
    const error = await failure(login(librarian.email, 'mauvais'));

    expect(error.status).toBe(401);
    expect((error.error as ProblemDetail).code).toBe('INVALID_CREDENTIALS');
  });

  it('refuse un corps invalide en 400 avec les erreurs par champ', async () => {
    const error = await failure(login('pas-un-email', ''));

    expect(error.status).toBe(400);
    const body = error.error as ProblemDetail;
    expect(body.code).toBe('VALIDATION_FAILED');
    expect(body.errors?.map((e) => `${e.field}:${e.code}`)).toEqual([
      'email:INVALID_FORMAT',
      'password:BLANK',
    ]);
  });

  it('renvoie 401 UNAUTHENTICATED sur /me sans session, puis après déconnexion', async () => {
    expect((await failure(me())).status).toBe(401);

    await respond(login(librarian.email, librarian.password));
    await respond(firstValueFrom(http.post('/api/v1/auth/logout', null)));

    const error = await failure(me());
    expect(error.status).toBe(401);
    expect((error.error as ProblemDetail).code).toBe('UNAUTHENTICATED');
  });

  it('laisse passer vers la vraie API un endpoint absent des jeux de données', () => {
    void firstValueFrom(http.get('/api/v1/members'));

    httpTesting.expectOne('/api/v1/members').flush([]);
  });

  /** RM-LOAN-27 : les endpoints d'emprunt sont servis par l'intercepteur simulé, derrière la session. */
  describe('emprunts', () => {
    const member = mockUsers[1];
    const peste = '00000000-0000-4000-8000-000000001002';

    it('refuse un emprunt sans session en 401 UNAUTHENTICATED', async () => {
      const error = await failure(firstValueFrom(http.post('/api/v1/loans', { bookId: peste })));

      expect(error.status).toBe(401);
      expect((error.error as ProblemDetail).code).toBe('UNAUTHENTICATED');
    });

    it('crée un emprunt (201), le liste, puis le rend (204)', async () => {
      await respond(login(member.email, member.password));

      const created = await respond(
        firstValueFrom(http.post<{ id: string; dueOn: string }>('/api/v1/loans', { bookId: peste })),
      );
      const listed = await respond(firstValueFrom(http.get<{ id: string }[]>('/api/v1/loans')));
      await respond(firstValueFrom(http.post(`/api/v1/loans/${created.id}/return`, null)));
      const after = await respond(firstValueFrom(http.get<unknown[]>('/api/v1/loans')));

      expect(listed.map((loan) => loan.id)).toEqual([created.id]);
      expect(after).toEqual([]);
    });

    it('renvoie 409 LOAN_ALREADY_RETURNED au second retour', async () => {
      await respond(login(member.email, member.password));
      const created = await respond(
        firstValueFrom(http.post<{ id: string }>('/api/v1/loans', { bookId: peste })),
      );
      await respond(firstValueFrom(http.post(`/api/v1/loans/${created.id}/return`, null)));

      const error = await failure(
        firstValueFrom(http.post(`/api/v1/loans/${created.id}/return`, null)),
      );

      expect(error.status).toBe(409);
      expect((error.error as ProblemDetail).code).toBe('LOAN_ALREADY_RETURNED');
    });

    it('partage l’état avec le catalogue : le livre emprunté en dernier exemplaire disparaît de la liste', async () => {
      const python = '00000000-0000-4000-8000-000000001006';
      await respond(login(member.email, member.password));
      const ids = async (): Promise<string[]> =>
        (
          await respond(
            firstValueFrom(
              http.get<{ content: { id: string }[] }>('/api/v1/books', { params: { size: 50 } }),
            ),
          )
        ).content.map((book) => book.id);
      expect(await ids()).toContain(python);

      await respond(firstValueFrom(http.post('/api/v1/loans', { bookId: python })));

      expect(await ids()).not.toContain(python);
    });
  });
});
