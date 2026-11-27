import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';

import { AuthService } from './auth.service';
import { Role } from './current-user.model';
import { roleGuard } from './role.guard';

/** RM-LOAN-23 : `/emprunts` est réservée au rôle MEMBER ; un LIBRARIAN seul est redirigé vers /catalogue. */
describe('roleGuard', () => {
  const roles = signal<Role[]>([]);
  let router: Router;

  beforeEach(() => {
    roles.set([]);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { hasRole: (role: Role) => roles().includes(role) } },
      ],
    });
    router = TestBed.inject(Router);
  });

  function run(role: Role, fallback?: string): boolean | UrlTree {
    const guard = fallback === undefined ? roleGuard(role) : roleGuard(role, fallback);
    return TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  it('laisse passer un MEMBER', () => {
    roles.set(['MEMBER']);

    expect(run('MEMBER')).toBe(true);
  });

  it('redirige un LIBRARIAN seul vers /catalogue', () => {
    roles.set(['LIBRARIAN']);

    const result = run('MEMBER');

    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe('/catalogue');
  });

  it('laisse passer un utilisateur cumulant LIBRARIAN et MEMBER', () => {
    roles.set(['LIBRARIAN', 'MEMBER']);

    expect(run('MEMBER')).toBe(true);
  });

  it('redirige un utilisateur sans rôle', () => {
    expect(run('MEMBER')).toBeInstanceOf(UrlTree);
  });

  it('utilise la redirection demandée', () => {
    roles.set(['MEMBER']);

    const result = run('LIBRARIAN', '/acces-refuse');

    expect(router.serializeUrl(result as UrlTree)).toBe('/acces-refuse');
  });
});
