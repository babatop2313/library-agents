import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of, Subject } from 'rxjs';

import { AuthService } from '../../auth/auth.service';
import { CurrentUser } from '../../auth/current-user.model';
import { Header } from './header';

const ALICE: CurrentUser = {
  userId: '11111111-1111-1111-1111-111111111111',
  email: 'alice@example.org',
  roles: ['MEMBER', 'LIBRARIAN'],
  expiresAt: '2026-11-20T11:00:00Z',
};

describe('Header', () => {
  const user = signal<CurrentUser | null>(null);
  const logout = vi.fn(() => of(undefined));
  let router: Router;

  beforeEach(() => {
    user.set(null);
    logout.mockClear();
    TestBed.configureTestingModule({
      imports: [Header],
      providers: [provideRouter([]), { provide: AuthService, useValue: { user, logout } }],
    });
    router = TestBed.inject(Router);
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('affiche seulement le titre quand personne n’est connecté', async () => {
    const element = await render();

    expect(element.textContent).toContain('Bibliothèque');
    expect(element.querySelector('.app-user')).toBeNull();
    expect(element.querySelector('p-tag')).toBeNull();
    expect(element.querySelector('p-button')).toBeNull();
    expect(element.textContent).not.toContain('Se déconnecter');
  });

  it('affiche l’e-mail, un badge par rôle et le bouton de déconnexion quand l’utilisateur est connecté', async () => {
    user.set(ALICE);

    const element = await render();

    expect(element.querySelector('.app-user')?.textContent).toContain('alice@example.org');
    const tags = Array.from(element.querySelectorAll('p-tag')).map((t) => t.textContent?.trim());
    expect(tags).toEqual(['Membre', 'Bibliothécaire']);
    expect(element.querySelector('p-button')?.textContent).toContain('Se déconnecter');
  });

  it('affiche un libellé de repli quand l’e-mail est absent (jeton de développement)', async () => {
    user.set({ ...ALICE, email: null });

    const element = await render();

    expect(element.querySelector('.app-user')?.textContent).toContain('Utilisateur connecté');
  });

  it('se déconnecte puis redirige vers /login en remplaçant l’historique', async () => {
    user.set(ALICE);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const element = await render();

    (element.querySelector('p-button button') as HTMLButtonElement).click();

    expect(logout).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/login'], { replaceUrl: true });
  });

  it('n’émet qu’un seul appel de déconnexion en cas de double clic, puis réactive le bouton', async () => {
    user.set(ALICE);
    const pending = new Subject<undefined>();
    logout.mockReturnValueOnce(pending);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    const button = () => (fixture.nativeElement as HTMLElement).querySelector('p-button button') as HTMLButtonElement;

    button().click();
    button().click();
    fixture.detectChanges();

    expect(logout).toHaveBeenCalledTimes(1);
    expect(button().disabled).toBe(true);

    pending.next(undefined);
    pending.complete();
    fixture.detectChanges();

    expect(button().disabled).toBe(false);
  });

  it('masque l’identité dès que la session est vidée', async () => {
    user.set(ALICE);
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();

    user.set(null);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).querySelector('.app-user')).toBeNull();
  });
});
