import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { provideAuthInitializer } from './auth.initializer';
import { AuthService } from './auth.service';

describe('provideAuthInitializer', () => {
  let httpTesting: HttpTestingController;
  let initStatus: ApplicationInitStatus;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideAuthInitializer()],
    });
    httpTesting = TestBed.inject(HttpTestingController);
    initStatus = TestBed.inject(ApplicationInitStatus);
  });

  afterEach(() => httpTesting.verify());

  it('restaure la session au démarrage avec GET /api/v1/auth/me', async () => {
    const req = httpTesting.expectOne('/api/v1/auth/me');
    expect(req.request.method).toBe('GET');
    req.flush({
      userId: '11111111-1111-1111-1111-111111111111',
      email: 'alice@example.org',
      roles: ['MEMBER'],
      expiresAt: '2026-11-20T11:00:00Z',
    });
    await initStatus.donePromise;

    expect(TestBed.inject(AuthService).user()?.email).toBe('alice@example.org');
  });

  it('termine le démarrage sans session et sans erreur sur un 401', async () => {
    httpTesting
      .expectOne('/api/v1/auth/me')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await initStatus.donePromise;

    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
  });
});
