import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ConfirmationService, Confirmation, MessageService } from 'primeng/api';
import { Observable, of, Subject, throwError } from 'rxjs';

import { CurrentLoan } from '../../data/loans.model';
import { LoansService } from '../../data/loans.service';
import { MyLoans } from './my-loans';

function loan(index: number, overrides: Partial<CurrentLoan> = {}): CurrentLoan {
  return {
    id: `loan-${index}`,
    bookId: `book-${index}`,
    isbn: `97820703600${index}${index}`,
    title: `Livre ${index}`,
    author: 'Auteur',
    borrowedOn: '2026-11-24',
    dueOn: '2026-12-08',
    overdue: false,
    ...overrides,
  };
}

function problem(status: number, code: string): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: { title: 'Titre', status, code } });
}

/** RM-LOAN-24, RM-LOAN-25, RM-LOAN-26, RM-LOAN-32 : page « Mes emprunts ». */
describe('MyLoans (page)', () => {
  let listMine: ReturnType<typeof vi.fn<() => Observable<CurrentLoan[]>>>;
  let giveBack: ReturnType<typeof vi.fn<(id: string) => Observable<void>>>;
  let messages: MessageService;
  let fixture: ComponentFixture<MyLoans>;

  beforeEach(() => {
    listMine = vi.fn<() => Observable<CurrentLoan[]>>(() => of([loan(1), loan(2)]));
    giveBack = vi.fn<(id: string) => Observable<void>>(() => of(undefined));
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideRouter([]),
        { provide: LoansService, useValue: { listMine, giveBack } },
      ],
    });
    messages = TestBed.inject(MessageService);
    vi.spyOn(messages, 'add');
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function open(): Promise<HTMLElement> {
    fixture = TestBed.createComponent(MyLoans);
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  function confirmation(): ConfirmationService {
    return fixture.debugElement.injector.get(ConfirmationService);
  }

  function rows(element: HTMLElement): HTMLTableRowElement[] {
    return Array.from(element.querySelectorAll<HTMLTableRowElement>('tbody tr'));
  }

  function giveBackButton(element: HTMLElement, index: number): HTMLButtonElement {
    return element.querySelectorAll<HTMLButtonElement>('tbody p-button button')[index];
  }

  /** Clique « Rendre », puis renvoie la confirmation demandée (sans afficher le dialogue réel). */
  async function askGiveBack(element: HTMLElement, index: number): Promise<Confirmation> {
    const spy = vi.spyOn(confirmation(), 'confirm').mockImplementation(() => confirmation());
    giveBackButton(element, index).click();
    await settle();
    return spy.mock.calls[0][0];
  }

  function toasts(): { severity: string; summary: string; detail?: string }[] {
    return (messages.add as ReturnType<typeof vi.fn>).mock.calls.map(
      (call) => call[0] as { severity: string; summary: string; detail?: string },
    );
  }

  function counter(element: HTMLElement): string {
    return (element.querySelector('.counter-zone')?.textContent ?? '').replace(/\s+/g, ' ');
  }

  describe('chargement', () => {
    it('appelle GET une fois à l’ouverture', async () => {
      await open();

      expect(listMine).toHaveBeenCalledTimes(1);
    });

    it('affiche le titre « Mes emprunts » et une ligne par emprunt', async () => {
      const element = await open();

      expect(element.querySelector('h1')?.textContent).toContain('Mes emprunts');
      expect(rows(element)).toHaveLength(2);
      expect(rows(element)[0].textContent).toContain('Livre 1');
    });

    it('affiche un état de chargement tant que la réponse n’est pas arrivée', async () => {
      const pending = new Subject<CurrentLoan[]>();
      listMine.mockReturnValue(pending);

      const element = await open();

      expect(element.textContent).toContain('Chargement de vos emprunts');
      expect(element.querySelectorAll('tbody p-skeleton').length).toBeGreaterThan(0);
      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('true');
    });

    it('affiche l’état vide avec un lien vers le catalogue quand il n’y a aucun emprunt', async () => {
      listMine.mockReturnValue(of([]));

      const element = await open();

      expect(element.textContent).toContain("Vous n'avez aucun emprunt en cours");
      expect(element.querySelector('p-button[routerLink="/catalogue"]')).not.toBeNull();
      expect(counter(element)).toContain('0 / 3 emprunts en cours');
    });

    it('affiche l’erreur et « Réessayer » en cas d’échec, sans compteur', async () => {
      listMine.mockReturnValue(throwError(() => problem(500, 'X')));

      const element = await open();

      expect(element.textContent).toContain("Vos emprunts n'ont pas pu être chargés");
      expect(element.querySelector('p-button[label="Réessayer"]')).not.toBeNull();
      expect(element.querySelector('.counter')).toBeNull();
    });

    it('« Réessayer » relance le chargement et affiche la liste en cas de succès', async () => {
      listMine.mockReturnValueOnce(throwError(() => problem(500, 'X')));
      const element = await open();

      element.querySelector<HTMLButtonElement>('p-button[label="Réessayer"] button')?.click();
      await settle();

      expect(listMine).toHaveBeenCalledTimes(2);
      expect(rows(element)).toHaveLength(2);
      expect(element.textContent).not.toContain("n'ont pas pu être chargés");
    });

    it('affiche un message de droits insuffisants pour un 403', async () => {
      listMine.mockReturnValue(throwError(() => problem(403, 'ACCESS_DENIED')));

      const element = await open();

      expect(element.textContent).toContain(
        'pas les droits nécessaires pour consulter vos emprunts',
      );
      expect(element.querySelector('p-button[label="Réessayer"]')).toBeNull();
    });
  });

  describe('compteur et bandeau', () => {
    it('affiche « 2 / 3 emprunts en cours » sans aide de limite', async () => {
      const element = await open();

      expect(counter(element)).toContain('2 / 3 emprunts en cours');
      expect(counter(element)).not.toContain('Limite atteinte');
    });

    it('annonce le compteur aux lecteurs d’écran (aria-live, texte parlé)', async () => {
      const element = await open();

      const zone = element.querySelector('.counter-zone');
      expect(zone?.getAttribute('aria-live')).toBe('polite');
      expect(zone?.querySelector('.visually-hidden')?.textContent).toBe(
        '2 emprunts en cours sur 3 autorisés',
      );
    });

    it('utilise le singulier pour un seul emprunt', async () => {
      listMine.mockReturnValue(of([loan(1)]));

      const element = await open();

      expect(element.querySelector('.counter .visually-hidden')?.textContent).toBe(
        '1 emprunt en cours sur 3 autorisés',
      );
    });

    it('affiche l’aide « Limite atteinte » à 3 / 3', async () => {
      listMine.mockReturnValue(of([loan(1), loan(2), loan(3)]));

      const element = await open();

      expect(counter(element)).toContain('3 / 3 emprunts en cours');
      expect(counter(element)).toContain(
        'Limite atteinte : rendez un livre pour en emprunter un autre.',
      );
    });

    it('n’affiche pas de bandeau de retard quand aucun emprunt n’est en retard', async () => {
      const element = await open();

      expect(element.querySelector('p-message')).toBeNull();
    });

    it('affiche le bandeau de retard dès qu’un emprunt est en retard', async () => {
      listMine.mockReturnValue(of([loan(1, { overdue: true }), loan(2)]));

      const element = await open();

      expect(element.querySelector('p-message')?.textContent).toContain(
        'Vous ne pouvez plus emprunter tant que vous avez un livre en retard',
      );
    });

    it('le bandeau de retard n’est ni une alerte ni une zone live (pas de double annonce)', async () => {
      listMine.mockReturnValue(of([loan(1, { overdue: true })]));

      const element = await open();

      const banner = element.querySelector('p-message');
      expect(banner).not.toBeNull();
      expect(element.querySelector('p-message[role]')).toBeNull();
      expect(element.querySelector('p-message[aria-live]')).toBeNull();
      expect(banner?.querySelector('[role="alert"]')).toBeNull();
      expect(element.querySelector('[role="alert"]')).toBeNull();
    });

    it('n’affiche pas le bandeau de retard en cas d’erreur de chargement', async () => {
      listMine.mockReturnValue(throwError(() => problem(500, 'X')));

      const element = await open();

      expect(element.querySelector('p-message')).toBeNull();
    });

    it('affiche « Livre indisponible » pour un emprunt dont le livre n’existe plus (RM-LOAN-32)', async () => {
      listMine.mockReturnValue(of([loan(1, { title: null, author: null, isbn: null })]));

      const element = await open();

      expect(rows(element)[0].textContent).toContain('Livre indisponible');
    });
  });

  describe('confirmation du retour', () => {
    it('demande confirmation avant d’appeler le backend, avec le focus initial sur « Annuler »', async () => {
      const element = await open();

      const request = await askGiveBack(element, 0);

      expect(request.header).toBe('Rendre ce livre ?');
      expect(request.message).toContain('« Livre 1 »');
      expect(request.acceptLabel).toBe('Rendre');
      expect(request.rejectLabel).toBe('Annuler');
      expect(request.defaultFocus).toBe('reject');
      expect(giveBack).not.toHaveBeenCalled();
    });

    it('décrit un livre indisponible par sa date d’emprunt', async () => {
      listMine.mockReturnValue(of([loan(1, { title: null, author: null, isbn: null })]));
      const element = await open();

      const request = await askGiveBack(element, 0);

      expect(request.message).toContain('de ce livre, emprunté le 24/11/2026');
    });

    it('affiche réellement le dialogue avec les boutons « Annuler » et « Rendre » et donne le focus à « Annuler »', async () => {
      const element = await open();

      giveBackButton(element, 0).click();
      await settle();
      await new Promise((resolve) => setTimeout(resolve, 50));
      fixture.detectChanges();

      expect(document.body.textContent).toContain('Rendre ce livre ?');
      const active = document.activeElement as HTMLElement | null;
      expect(active?.textContent).toContain('Annuler');
      expect(giveBack).not.toHaveBeenCalled();
    });

    it('annuler ne rend rien et redonne le focus au bouton « Rendre » de la ligne', async () => {
      const element = await open();
      const request = await askGiveBack(element, 1);

      request.reject?.();
      await settle();

      expect(giveBack).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(giveBackButton(element, 1));
    });
  });

  describe('retour', () => {
    async function confirmed(element: HTMLElement, index = 0): Promise<void> {
      const request = await askGiveBack(element, index);
      request.accept?.();
      await settle();
    }

    it('succès 204 : appelle POST pour l’emprunt, notifie « Livre rendu » et recharge la liste', async () => {
      const element = await open();
      listMine.mockReturnValue(of([loan(2)]));

      await confirmed(element, 0);

      expect(giveBack).toHaveBeenCalledWith('loan-1');
      expect(toasts()).toEqual([{ severity: 'success', summary: 'Livre rendu', life: 3000 }]);
      expect(listMine).toHaveBeenCalledTimes(2);
      expect(rows(element)).toHaveLength(1);
      expect(counter(element)).toContain('1 / 3 emprunts en cours');
    });

    it('succès : redonne le focus au titre de la page, la ligne ayant disparu', async () => {
      const element = await open();
      listMine.mockReturnValue(of([loan(2)]));

      await confirmed(element, 0);

      expect(document.activeElement).toBe(element.querySelector('h1'));
    });

    it('409 LOAN_ALREADY_RETURNED : toast « Cet emprunt est déjà rendu » (warn) et rechargement', async () => {
      giveBack.mockReturnValue(throwError(() => problem(409, 'LOAN_ALREADY_RETURNED')));
      const element = await open();

      await confirmed(element);

      expect(toasts()).toEqual([
        { severity: 'warn', summary: 'Cet emprunt est déjà rendu', life: 5000 },
      ]);
      expect(listMine).toHaveBeenCalledTimes(2);
    });

    it('404 LOAN_NOT_FOUND : toast « Emprunt introuvable » (warn) et rechargement', async () => {
      giveBack.mockReturnValue(throwError(() => problem(404, 'LOAN_NOT_FOUND')));
      const element = await open();

      await confirmed(element);

      expect(toasts()).toEqual([{ severity: 'warn', summary: 'Emprunt introuvable', life: 5000 }]);
      expect(listMine).toHaveBeenCalledTimes(2);
    });

    it('503 LOAN_BUSY : toast warn avec le message du contrat, sans rechargement, ligne conservée', async () => {
      giveBack.mockReturnValue(throwError(() => problem(503, 'LOAN_BUSY')));
      const element = await open();

      await confirmed(element);

      expect(toasts()).toEqual([
        {
          severity: 'warn',
          summary: 'Le service est momentanément occupé, réessayez',
          life: 5000,
        },
      ]);
      expect(listMine).toHaveBeenCalledTimes(1);
      expect(rows(element)).toHaveLength(2);
      expect(document.activeElement).toBe(giveBackButton(element, 0));
    });

    it.each([403, 500, 0])(
      'erreur %i : aucun toast de l’écran (intercepteur), pas de rechargement, bouton réactivé',
      async (status) => {
        giveBack.mockReturnValue(throwError(() => new HttpErrorResponse({ status })));
        const element = await open();

        await confirmed(element);

        expect(messages.add).not.toHaveBeenCalled();
        expect(listMine).toHaveBeenCalledTimes(1);
        expect(giveBackButton(element, 0).disabled).toBe(false);
      },
    );

    it('désactive tous les boutons « Rendre » pendant l’envoi, avec l’indicateur sur la ligne', async () => {
      const pending = new Subject<void>();
      giveBack.mockReturnValue(pending);
      const element = await open();

      await confirmed(element, 1);

      const buttons = Array.from(
        element.querySelectorAll<HTMLButtonElement>('tbody p-button button'),
      );
      expect(buttons.every((b) => b.disabled)).toBe(true);
      expect(element.querySelectorAll('tbody .p-button-loading-icon')).toHaveLength(1);
    });

    it('un seul envoi à la fois : une seconde confirmation pendant l’envoi est ignorée', async () => {
      const pending = new Subject<void>();
      giveBack.mockReturnValue(pending);
      const element = await open();
      const first = await askGiveBack(element, 0);
      first.accept?.();
      await settle();
      const confirm = vi.spyOn(confirmation(), 'confirm');
      confirm.mockClear();

      // Un autre clic (forcé : le bouton est désactivé) ne doit ni confirmer ni envoyer.
      const instance = fixture.componentInstance as unknown as {
        askGiveBack(loan: CurrentLoan): void;
      };
      instance.askGiveBack(loan(2));
      first.accept?.();

      expect(confirm).not.toHaveBeenCalled();
      expect(giveBack).toHaveBeenCalledTimes(1);
    });

    it('réactive les boutons après la réponse', async () => {
      const pending = new Subject<void>();
      giveBack.mockReturnValue(pending);
      const element = await open();
      await confirmed(element, 0);
      listMine.mockReturnValue(of([loan(2)]));

      pending.next();
      pending.complete();
      await settle();

      expect(
        Array.from(element.querySelectorAll<HTMLButtonElement>('tbody p-button button')).some(
          (b) => b.disabled,
        ),
      ).toBe(false);
    });
  });
});
