import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthService } from './auth.service';
import { CurrentUser } from './current-user.model';

const ALICE: CurrentUser = {
  userId: '11111111-1111-1111-1111-111111111111',
  email: 'alice@example.org',
  roles: ['MEMBER'],
  expiresAt: '2026-11-20T11:00:00Z',
};

describe('AuthService', () => {
  let service: AuthService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  describe('login', () => {
    it('envoie les identifiants en POST et conserve l’utilisateur dans le signal', () => {
      let received: CurrentUser | undefined;
      service
        .login({ email: 'alice@example.org', password: 'S3cret!pass-long' })
        .subscribe((u) => (received = u));

      const req = httpTesting.expectOne('/api/v1/auth/login');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        email: 'alice@example.org',
        password: 'S3cret!pass-long',
      });
      req.flush(ALICE);

      expect(received).toEqual(ALICE);
      expect(service.user()).toEqual(ALICE);
      expect(service.isAuthenticated()).toBe(true);
    });

    it('n’ajoute aucun en-tête Authorization', () => {
      service.login({ email: 'a@b.c', password: 'x' }).subscribe();

      const req = httpTesting.expectOne('/api/v1/auth/login');
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush(ALICE);
    });

    it('ne stocke rien dans localStorage ni sessionStorage', () => {
      service.login({ email: 'a@b.c', password: 'x' }).subscribe();
      httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);

      expect(localStorage.length).toBe(0);
      expect(sessionStorage.length).toBe(0);
    });

    it('laisse l’état vide et propage l’erreur sur un 401', () => {
      let status: number | undefined;
      service
        .login({ email: 'a@b.c', password: 'mauvais' })
        .subscribe({ error: (e: HttpErrorResponse) => (status = e.status) });

      httpTesting
        .expectOne('/api/v1/auth/login')
        .flush({ code: 'INVALID_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });

      expect(status).toBe(401);
      expect(service.user()).toBeNull();
      expect(service.isAuthenticated()).toBe(false);
    });
  });

  describe('restoreSession', () => {
    it('appelle GET /me et connecte l’utilisateur', async () => {
      const done = service.restoreSession();

      const req = httpTesting.expectOne('/api/v1/auth/me');
      expect(req.request.method).toBe('GET');
      req.flush(ALICE);
      await done;

      expect(service.user()).toEqual(ALICE);
    });

    it('résout sans erreur et reste déconnecté sur un 401', async () => {
      const done = service.restoreSession();

      httpTesting
        .expectOne('/api/v1/auth/me')
        .flush(null, { status: 401, statusText: 'Unauthorized' });

      await expect(done).resolves.toBeUndefined();
      expect(service.user()).toBeNull();
    });

    it('résout sans erreur sur un échec réseau', async () => {
      const done = service.restoreSession();

      httpTesting.expectOne('/api/v1/auth/me').error(new ProgressEvent('error'));

      await expect(done).resolves.toBeUndefined();
      expect(service.isAuthenticated()).toBe(false);
    });
  });

  describe('logout', () => {
    beforeEach(() => {
      service.login({ email: 'a@b.c', password: 'x' }).subscribe();
      httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);
    });

    it('appelle POST /logout puis vide la session', () => {
      service.logout().subscribe();

      const req = httpTesting.expectOne('/api/v1/auth/logout');
      expect(req.request.method).toBe('POST');
      req.flush(null, { status: 204, statusText: 'No Content' });

      expect(service.user()).toBeNull();
    });

    it('vide la session même si l’appel échoue', () => {
      let completed = false;
      service.logout().subscribe({ complete: () => (completed = true) });

      httpTesting
        .expectOne('/api/v1/auth/logout')
        .flush(null, { status: 500, statusText: 'Server Error' });

      expect(completed).toBe(true);
      expect(service.user()).toBeNull();
    });

    it('vide la session même sur un échec réseau', () => {
      service.logout().subscribe();

      httpTesting.expectOne('/api/v1/auth/logout').error(new ProgressEvent('error'));

      expect(service.isAuthenticated()).toBe(false);
    });
  });

  describe('expireSession', () => {
    it('renvoie false quand personne n’est connecté', () => {
      expect(service.expireSession()).toBe(false);
    });

    it('vide la session et renvoie true une seule fois', () => {
      service.login({ email: 'a@b.c', password: 'x' }).subscribe();
      httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);

      expect(service.expireSession()).toBe(true);
      expect(service.user()).toBeNull();
      expect(service.expireSession()).toBe(false);
    });
  });

  describe('hasRole', () => {
    it('reflète les rôles de l’utilisateur courant', () => {
      expect(service.hasRole('MEMBER')).toBe(false);

      service.login({ email: 'a@b.c', password: 'x' }).subscribe();
      httpTesting
        .expectOne('/api/v1/auth/login')
        .flush({ ...ALICE, roles: ['MEMBER', 'LIBRARIAN'] });

      expect(service.hasRole('MEMBER')).toBe(true);
      expect(service.hasRole('LIBRARIAN')).toBe(true);
    });
  });
});
