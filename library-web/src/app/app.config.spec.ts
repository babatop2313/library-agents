import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { appConfig } from './app.config';
import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';

describe('appConfig (authentification)', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
    const init = TestBed.inject(ApplicationInitStatus);
    // RM-AUTH-17 : la session est restaurée au démarrage
    httpTesting
      .expectOne('/api/v1/auth/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await init.donePromise;
  });

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
    httpTesting.verify();
  });

  it('restaure la session par GET /me sans notification sur un 401 : visiteur non connecté', () => {
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
  });

  it('copie le cookie XSRF-TOKEN dans l’en-tête X-XSRF-TOKEN d’une requête modifiante', () => {
    document.cookie = 'XSRF-TOKEN=jeton-csrf; Path=/';

    http.post('/api/v1/books', {}).subscribe();

    const req = httpTesting.expectOne('/api/v1/books');
    expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('jeton-csrf');
    req.flush({});
  });

  it('n’ajoute pas l’en-tête CSRF à une requête GET', () => {
    document.cookie = 'XSRF-TOKEN=jeton-csrf; Path=/';

    http.get('/api/v1/books').subscribe();

    const req = httpTesting.expectOne('/api/v1/books');
    expect(req.request.headers.has('X-XSRF-TOKEN')).toBe(false);
    req.flush([]);
  });

  it('n’ajoute jamais d’en-tête Authorization (le jeton est dans un cookie HttpOnly)', () => {
    http.post('/api/v1/books', {}).subscribe();

    const req = httpTesting.expectOne('/api/v1/books');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('protège toutes les routes sauf /login par authGuard et réserve /login aux visiteurs', () => {
    const login = routes.find((r) => r.path === 'login');
    const protectedRoot = routes.find((r) => r.path === '');

    expect(login?.canActivate).toHaveLength(1);
    expect(protectedRoot?.canActivateChild).toHaveLength(1);
    expect(routes.find((r) => r.path === '**')?.redirectTo).toBe('');
  });
});
