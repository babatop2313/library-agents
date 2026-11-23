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
    void firstValueFrom(http.get('/api/v1/books'));

    httpTesting.expectOne('/api/v1/books').flush([]);
  });
});
