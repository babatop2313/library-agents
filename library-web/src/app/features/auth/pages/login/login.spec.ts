import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { providePrimeNG } from 'primeng/config';

import { AuthService } from '../../../../core/auth/auth.service';
import { Login } from './login';

@Component({ template: '' })
class Blank {}

const ALICE = {
  userId: '11111111-1111-1111-1111-111111111111',
  email: 'alice@example.org',
  roles: ['MEMBER'],
  expiresAt: '2026-11-20T11:00:00Z',
};

describe('Login', () => {
  let httpTesting: HttpTestingController;
  let router: Router;
  let root: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'login', component: Login },
          { path: 'books', component: Blank },
          { path: '', component: Blank },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        providePrimeNG(),
      ],
    });
    httpTesting = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => httpTesting.verify());

  async function open(url = '/login'): Promise<RouterTestingHarness> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, Login);
    root = harness.routeNativeElement as HTMLElement;
    return harness;
  }

  const emailInput = () => root.querySelector('#email') as HTMLInputElement;
  const passwordInput = () => root.querySelector('#password') as HTMLInputElement;
  const submitButton = () => root.querySelector('p-button button') as HTMLButtonElement;
  const form = () => root.querySelector('form') as HTMLFormElement;

  async function type(
    harness: RouterTestingHarness,
    email: string,
    password: string,
  ): Promise<void> {
    emailInput().value = email;
    emailInput().dispatchEvent(new Event('input'));
    passwordInput().value = password;
    passwordInput().dispatchEvent(new Event('input'));
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  it('affiche les champs e-mail et mot de passe sans identifiant pré-rempli', async () => {
    await open();

    expect(emailInput().value).toBe('');
    expect(passwordInput().value).toBe('');
    expect(emailInput().getAttribute('autocomplete')).toBe('username');
    expect(passwordInput().getAttribute('autocomplete')).toBe('current-password');
    expect(root.textContent).toContain('Se connecter');
  });

  it('masque le mot de passe par défaut et propose un bouton pour l’afficher puis le masquer', async () => {
    const harness = await open();
    expect(passwordInput().type).toBe('password');

    const toggle = () =>
      root.querySelector(
        'p-password [class*="toggle"], p-password svg, p-password i',
      ) as HTMLElement;
    expect(toggle()).not.toBeNull();

    toggle().dispatchEvent(new MouseEvent('click', { bubbles: true }));
    harness.detectChanges();
    expect(passwordInput().type).toBe('text');

    toggle().dispatchEvent(new MouseEvent('click', { bubbles: true }));
    harness.detectChanges();
    expect(passwordInput().type).toBe('password');
  });

  it('désactive le bouton tant que le formulaire est incomplet et n’émet aucun appel', async () => {
    const harness = await open();
    expect(submitButton().disabled).toBe(true);

    await type(harness, 'alice@example.org', '');
    expect(submitButton().disabled).toBe(true);

    await type(harness, '', 'secret');
    expect(submitButton().disabled).toBe(true);

    form().dispatchEvent(new Event('submit'));
    httpTesting.expectNone('/api/v1/auth/login');
  });

  it('désactive le bouton pour un e-mail mal formé', async () => {
    const harness = await open();

    await type(harness, 'pas-un-email', 'secret');

    expect(submitButton().disabled).toBe(true);
  });

  it('active le bouton quand l’e-mail est valide et le mot de passe saisi', async () => {
    const harness = await open();

    await type(harness, 'alice@example.org', 'secret');

    expect(submitButton().disabled).toBe(false);
  });

  it('envoie l’e-mail sans espaces et redirige vers la racine après connexion', async () => {
    const harness = await open();
    await type(harness, '  alice@example.org  ', 'S3cret!pass-long');

    form().dispatchEvent(new Event('submit'));
    const req = httpTesting.expectOne('/api/v1/auth/login');
    expect(req.request.body).toEqual({ email: 'alice@example.org', password: 'S3cret!pass-long' });
    req.flush(ALICE);
    await harness.fixture.whenStable();

    expect(router.url).toBe('/');
    expect(TestBed.inject(AuthService).user()?.email).toBe('alice@example.org');
  });

  it('redirige vers la page demandée (returnUrl interne)', async () => {
    const harness = await open('/login?returnUrl=%2Fbooks');
    await type(harness, 'alice@example.org', 'secret');

    form().dispatchEvent(new Event('submit'));
    httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);
    await harness.fixture.whenStable();

    expect(router.url).toBe('/books');
  });

  it.each([['https://evil.example'], ['//evil.example']])(
    'ignore le returnUrl malveillant « %s » et redirige vers la racine',
    async (returnUrl) => {
      const harness = await open(`/login?returnUrl=${encodeURIComponent(returnUrl)}`);
      await type(harness, 'alice@example.org', 'secret');

      form().dispatchEvent(new Event('submit'));
      httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);
      await harness.fixture.whenStable();

      expect(router.url).toBe('/');
    },
  );

  it.each([
    ['annulée', () => Promise.resolve(false)],
    ['en échec', () => Promise.reject(new Error('navigation'))],
  ])('réactive le bouton quand la navigation après connexion est %s', async (_label, outcome) => {
    const harness = await open();
    await type(harness, 'alice@example.org', 'secret');
    vi.spyOn(router, 'navigateByUrl').mockImplementation(outcome);

    form().dispatchEvent(new Event('submit'));
    httpTesting.expectOne('/api/v1/auth/login').flush(ALICE);
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(submitButton().disabled).toBe(false);
  });

  it('n’émet qu’un seul appel en cas de double soumission', async () => {
    const harness = await open();
    await type(harness, 'alice@example.org', 'secret');

    form().dispatchEvent(new Event('submit'));
    form().dispatchEvent(new Event('submit'));
    harness.detectChanges();

    const requests = httpTesting.match('/api/v1/auth/login');
    expect(requests).toHaveLength(1);
    expect(submitButton().disabled).toBe(true);
    requests[0].flush(ALICE);
  });

  it('affiche le message générique sur un 401, conserve l’e-mail et vide le mot de passe', async () => {
    const harness = await open();
    await type(harness, 'alice@example.org', 'mauvais');

    form().dispatchEvent(new Event('submit'));
    httpTesting
      .expectOne('/api/v1/auth/login')
      .flush(
        { title: 'Identifiants invalides', code: 'INVALID_CREDENTIALS' },
        { status: 401, statusText: 'Unauthorized' },
      );
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(root.querySelector('p-message')?.textContent).toContain(
      'E-mail ou mot de passe incorrect',
    );
    expect(emailInput().value).toBe('alice@example.org');
    expect(passwordInput().value).toBe('');
    expect(router.url).toBe('/login');
    expect(root.textContent).not.toContain('inconnu');
  });

  it('réactive le bouton après un échec pour permettre une nouvelle tentative', async () => {
    const harness = await open();
    await type(harness, 'alice@example.org', 'mauvais');

    form().dispatchEvent(new Event('submit'));
    httpTesting
      .expectOne('/api/v1/auth/login')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await type(harness, 'alice@example.org', 'autre');

    expect(submitButton().disabled).toBe(false);
  });

  it('reporte les erreurs par champ d’un 400 sur le formulaire', async () => {
    const harness = await open();
    await type(harness, 'alice@example.org', 'secret');

    form().dispatchEvent(new Event('submit'));
    httpTesting.expectOne('/api/v1/auth/login').flush(
      {
        title: 'Validation échouée',
        code: 'VALIDATION_FAILED',
        errors: [
          {
            field: 'email',
            code: 'INVALID_FORMAT',
            message: "Le champ 'email' a un format invalide",
          },
        ],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(root.textContent).toContain("Le champ 'email' a un format invalide");
    expect(root.querySelector('p-message')).toBeNull();
  });

  it('n’affiche pas le message d’identifiants invalides sur une erreur serveur', async () => {
    const harness = await open();
    await type(harness, 'alice@example.org', 'secret');

    form().dispatchEvent(new Event('submit'));
    httpTesting
      .expectOne('/api/v1/auth/login')
      .flush(null, { status: 500, statusText: 'Server Error' });
    harness.detectChanges();

    expect(root.querySelector('p-message')).toBeNull();
    expect(submitButton().disabled).toBe(false);
  });
});
