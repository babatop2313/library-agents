import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';

import { AuthService } from '../../auth/auth.service';
import { CurrentUser } from '../../auth/current-user.model';
import { Header } from './header';

const MEMBER: CurrentUser = {
  userId: '22222222-2222-2222-2222-222222222222',
  email: 'membre@example.org',
  roles: ['MEMBER'],
  expiresAt: '2026-11-20T11:00:00Z',
};

/** RM-CAT-27 : lien « Catalogue » dans l'en-tête, visible seulement pour un utilisateur connecté. */
describe('Header : lien Catalogue', () => {
  const user = signal<CurrentUser | null>(null);

  beforeEach(() => {
    user.set(null);
    TestBed.configureTestingModule({
      imports: [Header],
      providers: [
        provideRouter([
          { path: 'catalogue', component: Header },
          { path: '', component: Header },
        ]),
        { provide: AuthService, useValue: { user, logout: () => of(undefined) } },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function link(element: HTMLElement): HTMLAnchorElement | null {
    return element.querySelector('a[href="/catalogue"]');
  }

  it('n’affiche pas le lien « Catalogue » à un visiteur non connecté', async () => {
    const element = await render();

    expect(link(element)).toBeNull();
    expect(element.querySelector('nav')).toBeNull();
    expect(element.textContent).not.toContain('Catalogue');
  });

  it('affiche le lien « Catalogue » vers /catalogue à un utilisateur connecté', async () => {
    user.set(MEMBER);

    const element = await render();

    expect(link(element)?.textContent).toContain('Catalogue');
    expect(element.querySelector('nav')?.getAttribute('aria-label')).toBe('Navigation principale');
  });

  it('retire le lien après la déconnexion', async () => {
    user.set(MEMBER);
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    expect(link(fixture.nativeElement as HTMLElement)).not.toBeNull();

    user.set(null);
    await fixture.whenStable();

    expect(link(fixture.nativeElement as HTMLElement)).toBeNull();
  });

  it('marque le lien actif (classe et aria-current) sur /catalogue, pas ailleurs', async () => {
    user.set(MEMBER);
    const fixture = TestBed.createComponent(Header);
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/');
    await fixture.whenStable();
    const anchor = link(fixture.nativeElement as HTMLElement) as HTMLAnchorElement;
    expect(anchor.classList.contains('active')).toBe(false);
    expect(anchor.getAttribute('aria-current')).toBeNull();

    await router.navigateByUrl('/catalogue');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(anchor.classList.contains('active')).toBe(true);
    expect(anchor.getAttribute('aria-current')).toBe('page');
  });
});
