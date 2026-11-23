import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';

/** RM-CAT-27 : route `/catalogue` chargée à la demande, protégée par `authGuard`. */
describe('routes : catalogue', () => {
  const authenticated = signal(false);
  let router: Router;

  beforeEach(() => {
    authenticated.set(false);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        { provide: AuthService, useValue: { isAuthenticated: authenticated } },
      ],
    });
    router = TestBed.inject(Router);
  });

  it('déclare /catalogue sous le parent protégé par authGuard, en chargement paresseux', () => {
    const parent = routes.find((route) => route.path === '' && route.children);
    const catalogue = parent?.children?.find((route) => route.path === 'catalogue');

    expect(parent?.canActivateChild?.length).toBe(1);
    expect(catalogue?.loadChildren).toBeTypeOf('function');
    expect(catalogue?.title).toBe('Catalogue');
  });

  it('redirige un visiteur de /catalogue vers /login?returnUrl=/catalogue', async () => {
    await router.navigateByUrl('/catalogue');

    expect(router.url).toBe('/login?returnUrl=%2Fcatalogue');
  });

  it('conserve les paramètres de recherche dans returnUrl', async () => {
    await router.navigateByUrl('/catalogue?q=camus&page=2');

    expect(router.url).toBe('/login?returnUrl=%2Fcatalogue%3Fq%3Dcamus%26page%3D2');
  });

  it('laisse un utilisateur connecté ouvrir /catalogue', async () => {
    authenticated.set(true);

    await router.navigateByUrl('/catalogue');

    expect(router.url).toBe('/catalogue');
  });

  it('charge la feature catalogue à la demande (route enfant vide vers la page)', async () => {
    const { CATALOGUE_ROUTES } = await import('./features/catalogue/catalogue.routes');

    expect(CATALOGUE_ROUTES).toHaveLength(1);
    expect(CATALOGUE_ROUTES[0].path).toBe('');
    expect(CATALOGUE_ROUTES[0].loadComponent).toBeTypeOf('function');
  });
});
