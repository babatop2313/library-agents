import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';

import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('guards', () => {
  const authenticated = signal(false);
  let router: Router;

  beforeEach(() => {
    authenticated.set(false);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: authenticated } },
      ],
    });
    router = TestBed.inject(Router);
  });

  function runAuthGuard(url: string): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  function runGuestGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      guestGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  describe('authGuard', () => {
    it('laisse passer un utilisateur connecté', () => {
      authenticated.set(true);

      expect(runAuthGuard('/books')).toBe(true);
    });

    it('redirige un visiteur vers /login en conservant l’URL demandée', () => {
      const result = runAuthGuard('/books?page=2');

      expect(result).toBeInstanceOf(UrlTree);
      expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fbooks%3Fpage%3D2');
    });

    it('redirige vers /login?returnUrl=%2F pour la racine', () => {
      expect(router.serializeUrl(runAuthGuard('/') as UrlTree)).toBe('/login?returnUrl=%2F');
    });

    it('refuse de nouveau l’accès après déconnexion (retour navigateur)', () => {
      authenticated.set(true);
      expect(runAuthGuard('/books')).toBe(true);

      authenticated.set(false);

      expect(runAuthGuard('/books')).toBeInstanceOf(UrlTree);
    });
  });

  describe('guestGuard', () => {
    it('laisse un visiteur accéder à /login', () => {
      expect(runGuestGuard()).toBe(true);
    });

    it('redirige un utilisateur connecté vers la racine', () => {
      authenticated.set(true);

      const result = runGuestGuard();

      expect(result).toBeInstanceOf(UrlTree);
      expect(router.serializeUrl(result as UrlTree)).toBe('/');
    });
  });
});
