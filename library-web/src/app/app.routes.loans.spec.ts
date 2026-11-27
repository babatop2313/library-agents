import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { Role } from './core/auth/current-user.model';

/** RM-LOAN-23 : route `/emprunts` chargée à la demande, protégée par `authGuard` puis réservée au rôle MEMBER. */
describe('routes : emprunts', () => {
  const authenticated = signal(false);
  const roles = signal<Role[]>([]);
  let router: Router;

  beforeEach(() => {
    authenticated.set(false);
    roles.set([]);
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: authenticated,
            hasRole: (role: Role) => roles().includes(role),
          },
        },
      ],
    });
    router = TestBed.inject(Router);
  });

  it('déclare /emprunts sous le parent protégé, en chargement paresseux, avec roleGuard', () => {
    const parent = routes.find((route) => route.path === '' && route.children);
    const loans = parent?.children?.find((route) => route.path === 'emprunts');

    expect(parent?.canActivateChild?.length).toBe(1);
    expect(loans?.loadChildren).toBeTypeOf('function');
    expect(loans?.canActivate?.length).toBe(1);
    expect(loans?.title).toBe('Mes emprunts');
  });

  it('redirige un visiteur de /emprunts vers /login?returnUrl=/emprunts', async () => {
    await router.navigateByUrl('/emprunts');

    expect(router.url).toBe('/login?returnUrl=%2Femprunts');
  });

  it('redirige un LIBRARIAN seul de /emprunts vers /catalogue', async () => {
    authenticated.set(true);
    roles.set(['LIBRARIAN']);

    await router.navigateByUrl('/emprunts');

    expect(router.url).toBe('/catalogue');
  });

  it('laisse un MEMBER ouvrir /emprunts', async () => {
    authenticated.set(true);
    roles.set(['MEMBER']);

    await router.navigateByUrl('/emprunts');

    expect(router.url).toBe('/emprunts');
  });

  it('laisse un utilisateur cumulant LIBRARIAN et MEMBER ouvrir /emprunts', async () => {
    authenticated.set(true);
    roles.set(['LIBRARIAN', 'MEMBER']);

    await router.navigateByUrl('/emprunts');

    expect(router.url).toBe('/emprunts');
  });

  it('charge la feature emprunts à la demande (route enfant vide vers la page)', async () => {
    const { LOANS_ROUTES } = await import('./features/loans/loans.routes');

    expect(LOANS_ROUTES).toHaveLength(1);
    expect(LOANS_ROUTES[0].path).toBe('');
    expect(LOANS_ROUTES[0].loadComponent).toBeTypeOf('function');
  });
});
