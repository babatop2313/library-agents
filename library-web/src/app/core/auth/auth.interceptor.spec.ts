import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { MessageService } from 'primeng/api';

import { errorInterceptor } from '../http/error.interceptor';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { CurrentUser } from './current-user.model';

@Component({ template: '' })
class Blank {}

const ALICE: CurrentUser = {
  userId: '11111111-1111-1111-1111-111111111111',
  email: 'alice@example.org',
  roles: ['MEMBER'],
  expiresAt: '2026-11-20T11:00:00Z',
};

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let auth: AuthService;
  let router: Router;
  let messages: MessageService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideRouter([
          { path: 'login', component: Blank },
          { path: 'books', component: Blank },
          { path: '', component: Blank },
        ]),
        provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    messages = TestBed.inject(MessageService);
    vi.spyOn(messages, 'add');
    await router.navigateByUrl('/books?page=2');
  });

  afterEach(() => httpTesting.verify());

  function givenLoggedIn(): void {
    auth.login({ email: 'a@b.c', password: 'x' }).subscribe();
    httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);
  }

  function failWith(url: string, status: number): void {
    http.get(url).subscribe({ error: () => undefined });
    httpTesting.expectOne(url).flush(null, { status, statusText: 'Error' });
  }

  it('vide la session, notifie et redirige vers /login avec returnUrl sur un 401', async () => {
    givenLoggedIn();
    const navigate = vi.spyOn(router, 'navigate');

    failWith('/api/v1/books', 401);
    await Promise.resolve();

    expect(auth.isAuthenticated()).toBe(false);
    expect(messages.add).toHaveBeenCalledTimes(1);
    expect(messages.add).toHaveBeenCalledWith(
      expect.objectContaining({ detail: 'Session expirée, veuillez vous reconnecter' }),
    );
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/books?page=2' },
    });
  });

  it('n’émet qu’une notification quand plusieurs requêtes échouent en 401', () => {
    givenLoggedIn();
    const navigate = vi.spyOn(router, 'navigate');

    http.get('/api/v1/a').subscribe({ error: () => undefined });
    http.get('/api/v1/b').subscribe({ error: () => undefined });
    httpTesting.expectOne('/api/v1/a').flush(null, { status: 401, statusText: 'Unauthorized' });
    httpTesting.expectOne('/api/v1/b').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(messages.add).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('ne fait rien sur un 401 de /login (identifiants invalides)', () => {
    const navigate = vi.spyOn(router, 'navigate');

    failWith('/api/v1/auth/login', 401);

    expect(messages.add).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ne fait rien sur un 401 de /me (non connecté au démarrage)', () => {
    givenLoggedIn();
    const navigate = vi.spyOn(router, 'navigate');

    failWith('/api/v1/auth/me', 401);

    expect(auth.isAuthenticated()).toBe(true);
    expect(messages.add).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ne notifie pas la session expirée si personne n’était connecté', () => {
    const navigate = vi.spyOn(router, 'navigate');

    failWith('/api/v1/books', 401);

    expect(messages.add).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ignore un 403 : session conservée, pas de redirection (notification gérée par errorInterceptor)', () => {
    givenLoggedIn();
    const navigate = vi.spyOn(router, 'navigate');

    failWith('/api/v1/books', 403);

    expect(auth.isAuthenticated()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    expect(messages.add).toHaveBeenCalledTimes(1);
    expect(messages.add).toHaveBeenCalledWith(expect.objectContaining({ summary: 'Accès refusé' }));
  });

  it('propage l’erreur à l’appelant', () => {
    givenLoggedIn();
    let status: number | undefined;

    http.get('/api/v1/books').subscribe({ error: (e: HttpErrorResponse) => (status = e.status) });
    httpTesting.expectOne('/api/v1/books').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(status).toBe(401);
  });

  it('n’ajoute aucun en-tête Authorization', () => {
    givenLoggedIn();

    http.get('/api/v1/books').subscribe();
    const req = httpTesting.expectOne('/api/v1/books');

    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush([]);
  });
});
