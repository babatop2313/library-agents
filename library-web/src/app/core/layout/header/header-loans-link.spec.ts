import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../auth/auth.service';
import { CurrentUser, Role } from '../../auth/current-user.model';
import { Header } from './header';

function userWith(...roles: Role[]): CurrentUser {
  return {
    userId: '22222222-2222-2222-2222-222222222222',
    email: 'membre@example.org',
    roles,
    expiresAt: '2026-11-20T11:00:00Z',
  };
}

/** RM-LOAN-23 : lien « Mes emprunts » dans l'en-tête, réservé aux utilisateurs ayant le rôle MEMBER. */
describe('Header : lien Mes emprunts', () => {
  const user = signal<CurrentUser | null>(null);

  beforeEach(() => {
    user.set(null);
    TestBed.configureTestingModule({
      imports: [Header],
      providers: [
        provideRouter([
          { path: 'catalogue', component: Header },
          { path: 'emprunts', component: Header },
          { path: '', component: Header },
        ]),
        { provide: AuthService, useValue: {
            user,
            logout: () => of(undefined),
            hasRole: (role: Role) => user()?.roles.includes(role) ?? false,
          } },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function link(element: HTMLElement): HTMLAnchorElement | null {
    return element.querySelector('a[href="/emprunts"]');
  }

  it('n’affiche pas le lien à un visiteur non connecté', async () => {
    const element = await render();

    expect(link(element)).toBeNull();
    expect(element.textContent).not.toContain('Mes emprunts');
  });

  it('affiche « Mes emprunts » vers /emprunts à un MEMBER', async () => {
    user.set(userWith('MEMBER'));

    const element = await render();

    expect(link(element)?.textContent).toContain('Mes emprunts');
  });

  it('masque le lien à un LIBRARIAN seul, tout en gardant « Catalogue »', async () => {
    user.set(userWith('LIBRARIAN'));

    const element = await render();

    expect(link(element)).toBeNull();
    expect(element.querySelector('a[href="/catalogue"]')).not.toBeNull();
  });

  it('affiche le lien à un utilisateur cumulant LIBRARIAN et MEMBER', async () => {
    user.set(userWith('LIBRARIAN', 'MEMBER'));

    const element = await render();

    expect(link(element)).not.toBeNull();
  });

  it('retire le lien après la déconnexion', async () => {
    user.set(userWith('MEMBER'));
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    expect(link(fixture.nativeElement as HTMLElement)).not.toBeNull();

    user.set(null);
    await fixture.whenStable();

    expect(link(fixture.nativeElement as HTMLElement)).toBeNull();
  });

  it('marque le lien actif (classe et aria-current) sur /emprunts, pas ailleurs', async () => {
    user.set(userWith('MEMBER'));
    const fixture = TestBed.createComponent(Header);
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/catalogue');
    await fixture.whenStable();
    const anchor = link(fixture.nativeElement as HTMLElement) as HTMLAnchorElement;
    expect(anchor.classList.contains('active')).toBe(false);
    expect(anchor.getAttribute('aria-current')).toBeNull();

    await router.navigateByUrl('/emprunts');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(anchor.classList.contains('active')).toBe(true);
    expect(anchor.getAttribute('aria-current')).toBe('page');
  });
});
