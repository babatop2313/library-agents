import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { CurrentLoan } from '../../data/loans.model';
import { LoanTable, LoanTableStatus } from './loan-table';

const LOANS: CurrentLoan[] = [
  {
    id: 'l-1',
    bookId: 'b-1',
    isbn: '9782070360420',
    title: 'La Peste',
    author: 'Albert Camus',
    borrowedOn: '2026-11-05',
    dueOn: '2026-11-19',
    overdue: true,
  },
  {
    id: 'l-2',
    bookId: 'b-2',
    isbn: '9782070360024',
    title: "L'Étranger",
    author: 'Albert Camus',
    borrowedOn: '2026-11-24',
    dueOn: '2026-12-08',
    overdue: false,
  },
  {
    id: 'l-3',
    bookId: 'b-3',
    isbn: null,
    title: null,
    author: null,
    borrowedOn: '2026-11-24',
    dueOn: '2026-12-08',
    overdue: false,
  },
];

/** RM-LOAN-24, RM-LOAN-26, RM-LOAN-32 : rendu du tableau « Mes emprunts ». */
describe('LoanTable', () => {
  let fixture: ComponentFixture<LoanTable>;
  let given: CurrentLoan[];
  let retries: number;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [LoanTable], providers: [provideRouter([])] });
    fixture = TestBed.createComponent(LoanTable);
    given = [];
    retries = 0;
    fixture.componentInstance.giveBack.subscribe((loan) => given.push(loan));
    fixture.componentInstance.retry.subscribe(() => retries++);
  });

  async function render(
    inputs: {
      loans?: CurrentLoan[];
      status?: LoanTableStatus;
      returningId?: string | null;
      retrying?: boolean;
    } = {},
  ): Promise<HTMLElement> {
    fixture.componentRef.setInput('loans', inputs.loans ?? LOANS);
    fixture.componentRef.setInput('status', inputs.status ?? 'ready');
    fixture.componentRef.setInput('returningId', inputs.returningId ?? null);
    fixture.componentRef.setInput('retrying', inputs.retrying ?? false);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function rows(element: HTMLElement): HTMLTableRowElement[] {
    return Array.from(element.querySelectorAll<HTMLTableRowElement>('tbody tr'));
  }

  function giveBackButtons(element: HTMLElement): HTMLButtonElement[] {
    return Array.from(element.querySelectorAll<HTMLButtonElement>('tbody p-button button'));
  }

  describe('liste', () => {
    it('affiche les colonnes Titre, Auteur, ISBN, Emprunté le, À rendre au plus tard le, Statut et Action', async () => {
      const element = await render();

      const headers = Array.from(element.querySelectorAll('th')).map((th) =>
        th.textContent?.trim(),
      );
      expect(headers).toEqual([
        'Titre',
        'Auteur',
        'ISBN',
        'Emprunté le',
        'À rendre au plus tard le',
        'Statut',
        'Action',
      ]);
    });

    it('porte une légende accessible', async () => {
      const element = await render();

      expect(element.querySelector('caption')?.textContent).toContain('Mes emprunts en cours');
    });

    it('rend une ligne par emprunt avec titre, auteur, ISBN et dates au format français', async () => {
      const element = await render();

      expect(rows(element)).toHaveLength(3);
      const first = rows(element)[0].textContent ?? '';
      expect(first).toContain('La Peste');
      expect(first).toContain('Albert Camus');
      expect(first).toContain('9782070360420');
      expect(first).toContain('05/11/2026');
      expect(first).toContain('19/11/2026');
    });

    it('affiche le tag « En retard » pour un emprunt en retard et « En cours » sinon', async () => {
      const element = await render();

      const [late, ongoing] = rows(element);
      expect(late.querySelector('td:nth-child(6) p-tag')?.getAttribute('value')).toBe('En retard');
      expect(ongoing.querySelector('td:nth-child(6) p-tag')?.getAttribute('value')).toBe(
        'En cours',
      );
      expect(late.querySelector('td:nth-child(6) p-tag')?.getAttribute('severity')).toBe('danger');
    });

    it('marque l’échéance d’un emprunt en retard par une classe en plus de la couleur du tag', async () => {
      const element = await render();

      expect(rows(element)[0].querySelector('td:nth-child(5)')?.classList).toContain('overdue');
      expect(rows(element)[1].querySelector('td:nth-child(5)')?.classList).not.toContain('overdue');
    });

    it('affiche « Livre indisponible » quand le titre est nul (livre supprimé)', async () => {
      const element = await render();

      const row = rows(element)[2];
      expect(row.querySelector('.cell-title .unavailable')?.textContent).toBe('Livre indisponible');
    });

    it('affiche un tiret et « Non renseigné » (lecteurs d’écran) pour l’auteur et l’ISBN nuls', async () => {
      const element = await render();
      const cells = [2, 3].map((n) => rows(element)[2].querySelector(`td:nth-child(${n})`));
      for (const cell of cells) {
        expect(cell?.querySelector('[aria-hidden="true"]')?.textContent).toBe('—');
        expect(cell?.querySelector('.visually-hidden')?.textContent).toBe('Non renseigné');
      }
    });

    it('n’affiche pas « Non renseigné » quand auteur et ISBN existent', async () => {
      const element = await render({ loans: [LOANS[0]] });

      expect(rows(element)[0].textContent).not.toContain('Non renseigné');
    });

    it('échappe le titre (jamais de HTML brut)', async () => {
      const element = await render({
        loans: [{ ...LOANS[0], title: '<img src=x onerror=alert(1)>' }],
      });

      expect(element.querySelector('img')).toBeNull();
      expect(element.textContent).toContain('<img src=x onerror=alert(1)>');
    });
  });

  describe('bouton Rendre', () => {
    it('porte le libellé accessible « Rendre <titre> »', async () => {
      const element = await render();

      expect(giveBackButtons(element)[0].getAttribute('aria-label')).toBe('Rendre La Peste');
      expect(giveBackButtons(element)[1].getAttribute('aria-label')).toBe("Rendre L'Étranger");
    });

    it('utilise un libellé avec la date d’emprunt quand le titre est nul', async () => {
      const element = await render();

      expect(giveBackButtons(element)[2].getAttribute('aria-label')).toBe(
        'Rendre le livre indisponible emprunté le 24/11/2026',
      );
    });

    it('émet l’emprunt de la ligne au clic', async () => {
      const element = await render();

      giveBackButtons(element)[1].click();

      expect(given).toEqual([LOANS[1]]);
    });

    it('désactive tous les boutons pendant un retour, avec l’indicateur sur la ligne concernée', async () => {
      const element = await render({ returningId: 'l-2' });

      expect(giveBackButtons(element).every((b) => b.disabled)).toBe(true);
      expect(element.querySelectorAll('tbody .p-button-loading-icon')).toHaveLength(1);
    });

    it('ne désactive aucun bouton sans retour en cours', async () => {
      const element = await render();

      expect(giveBackButtons(element).some((b) => b.disabled)).toBe(false);
    });

    it('focusGiveBack donne le focus au bouton de l’emprunt demandé', async () => {
      const element = await render();

      fixture.componentInstance.focusGiveBack('l-2');

      expect(document.activeElement).toBe(giveBackButtons(element)[1]);
    });
  });

  describe('états', () => {
    it('affiche des lignes squelettes au premier chargement et marque le tableau occupé', async () => {
      const element = await render({ loans: [], status: 'skeleton' });

      expect(element.querySelectorAll('tbody p-skeleton').length).toBeGreaterThan(0);
      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('true');
      expect(giveBackButtons(element)).toHaveLength(0);
    });

    it('conserve les lignes précédentes pendant un rechargement, tableau occupé', async () => {
      const element = await render({ status: 'reloading' });

      expect(element.textContent).toContain('La Peste');
      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('true');
    });

    it('n’est pas occupé quand la liste est prête', async () => {
      const element = await render();

      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('false');
    });

    it('affiche l’état vide avec un lien vers le catalogue', async () => {
      const element = await render({ loans: [] });

      expect(element.textContent).toContain("Vous n'avez aucun emprunt en cours");
      expect(element.querySelector('p-button[label="Parcourir le catalogue"]')).not.toBeNull();
      expect(element.querySelector('p-button[routerLink="/catalogue"]')).not.toBeNull();
    });

    it('affiche l’erreur de chargement avec « Réessayer », qui émet retry', async () => {
      const element = await render({ loans: [], status: 'error' });

      expect(element.textContent).toContain("Vos emprunts n'ont pas pu être chargés");
      element.querySelector<HTMLButtonElement>('p-button[label="Réessayer"] button')?.click();
      expect(retries).toBe(1);
    });

    it('affiche l’indicateur sur « Réessayer » pendant une nouvelle tentative', async () => {
      const element = await render({ loans: [], status: 'error', retrying: true });

      expect(
        element.querySelector('p-button[label="Réessayer"] .p-button-loading-icon'),
      ).not.toBeNull();
    });

    it('affiche un message de droits insuffisants pour un 403, sans « Réessayer »', async () => {
      const element = await render({ loans: [], status: 'forbidden' });

      expect(element.textContent).toContain("Vous n'avez pas les droits nécessaires");
      expect(element.querySelector('p-button[label="Réessayer"]')).toBeNull();
    });
  });
});
