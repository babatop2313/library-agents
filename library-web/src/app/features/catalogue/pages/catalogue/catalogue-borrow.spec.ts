import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { delay, Observable, of, Subject, throwError } from 'rxjs';

import { AuthService } from '../../../../core/auth/auth.service';
import { Role } from '../../../../core/auth/current-user.model';
import { Page } from '../../../../core/http/page';
import { Book, BookSearchParams, LoanCreated } from '../../data/catalogue.model';
import { CatalogueService } from '../../data/catalogue.service';
import { Catalogue } from './catalogue';

function book(index: number): Book {
  const n = String(index).padStart(2, '0');
  return {
    id: `id-${n}`,
    isbn: `97820703600${n}`,
    title: `Livre ${n}`,
    author: 'Auteur',
    totalCopies: 3,
    availableCopies: 2,
  };
}

function pageOf(count: number, page = 0, size = 10, total = count): Page<Book> {
  return {
    content: Array.from({ length: count }, (_, i) => book(page * size + i)),
    page,
    size,
    totalElements: total,
    totalPages: Math.ceil(total / size),
  };
}

const CREATED: LoanCreated = {
  id: '6d1f0c4b-8a2e-4f7a-b3d9-2c5e7a9b1f30',
  bookId: 'id-00',
  borrowedOn: '2026-11-24',
  dueOn: '2026-12-08',
};

function problem(status: number, code: string): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: { title: 'Titre', status, code } });
}

/** RM-LOAN-21, RM-LOAN-22, RM-LOAN-26 : emprunter depuis la page Catalogue. */
describe('Catalogue (page) : emprunter', () => {
  let roles: Role[];
  let search: ReturnType<typeof vi.fn<(params: BookSearchParams) => Observable<Page<Book>>>>;
  let borrow: ReturnType<typeof vi.fn<(bookId: string) => Observable<LoanCreated>>>;
  let messages: MessageService;
  let harness: RouterTestingHarness;
  let page: Catalogue;

  beforeEach(() => {
    roles = ['MEMBER'];
    search = vi.fn<(params: BookSearchParams) => Observable<Page<Book>>>(() =>
      of(pageOf(10, 0, 10, 25)),
    );
    borrow = vi.fn<(bookId: string) => Observable<LoanCreated>>(() => of(CREATED));
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideRouter([{ path: 'catalogue', component: Catalogue }]),
        { provide: CatalogueService, useValue: { search, register: vi.fn(), borrow } },
        { provide: AuthService, useValue: { hasRole: (role: Role) => roles.includes(role) } },
      ],
    });
    messages = TestBed.inject(MessageService);
    vi.spyOn(messages, 'add');
  });

  async function settle(): Promise<void> {
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
  }

  async function later(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 30));
    harness.fixture.detectChanges();
  }

  async function open(url = '/catalogue'): Promise<HTMLElement> {
    harness = await RouterTestingHarness.create();
    page = await harness.navigateByUrl(url, Catalogue);
    await settle();
    return harness.fixture.nativeElement as HTMLElement;
  }

  function borrowButtons(element: HTMLElement): HTMLButtonElement[] {
    return Array.from(element.querySelectorAll<HTMLButtonElement>('tbody p-button button'));
  }

  async function clickBorrow(element: HTMLElement, index = 0): Promise<void> {
    borrowButtons(element)[index].click();
    await settle();
  }

  function toasts(): { severity: string; summary: string; detail?: string }[] {
    return (messages.add as ReturnType<typeof vi.fn>).mock.calls.map(
      (call) => call[0] as { severity: string; summary: string; detail?: string },
    );
  }

  describe('bouton « Emprunter » selon le rôle', () => {
    it('est affiché sur chaque ligne pour un MEMBER', async () => {
      roles = ['MEMBER'];

      const element = await open();

      expect(borrowButtons(element)).toHaveLength(10);
      expect(borrowButtons(element)[0].getAttribute('aria-label')).toBe('Emprunter Livre 00');
    });

    it('est masqué pour un LIBRARIAN seul', async () => {
      roles = ['LIBRARIAN'];

      const element = await open();

      expect(borrowButtons(element)).toHaveLength(0);
      expect(element.textContent).not.toContain('Emprunter');
    });

    it('est affiché pour un utilisateur cumulant LIBRARIAN et MEMBER', async () => {
      roles = ['LIBRARIAN', 'MEMBER'];

      const element = await open();

      expect(borrowButtons(element)).toHaveLength(10);
      expect(element.querySelector('p-button[label="Ajouter un livre"]')).not.toBeNull();
    });
  });

  describe('succès (201)', () => {
    it('envoie le bookId de la ligne cliquée, une seule fois', async () => {
      const element = await open();

      await clickBorrow(element, 2);

      expect(borrow).toHaveBeenCalledTimes(1);
      expect(borrow).toHaveBeenCalledWith('id-02');
    });

    it('notifie « Livre emprunté » avec l’échéance au format français', async () => {
      const element = await open();

      await clickBorrow(element);

      expect(toasts()).toEqual([
        {
          severity: 'success',
          summary: 'Livre emprunté',
          detail: 'À rendre au plus tard le 08/12/2026.',
          life: 3000,
        },
      ]);
    });

    it('recharge la liste en conservant la page et la recherche de l’URL', async () => {
      const element = await open('/catalogue?page=2&size=10&q=camus');
      search.mockClear();

      await clickBorrow(element);

      expect(search).toHaveBeenCalledTimes(1);
      expect(search).toHaveBeenCalledWith({ page: 1, size: 10, q: 'camus' });
    });

    it('rend le focus au bouton de la même ligne quand le livre reste au catalogue', async () => {
      const element = await open();
      search.mockReturnValue(of(pageOf(10, 0, 10, 25)).pipe(delay(0)));

      await clickBorrow(element, 3);
      await later();

      expect(document.activeElement).toBe(borrowButtons(element)[3]);
    });

    it('rend le focus au champ de recherche quand la ligne a disparu (dernier exemplaire)', async () => {
      const element = await open();
      search.mockReturnValue(
        of({ ...pageOf(9, 0, 10, 24), content: pageOf(10).content.slice(1) }).pipe(delay(0)),
      );

      await clickBorrow(element, 0);
      await later();

      expect(document.activeElement).toBe(element.querySelector('app-book-search input'));
    });
  });

  describe('un seul envoi à la fois', () => {
    it('désactive tous les boutons pendant l’appel, avec l’indicateur sur la ligne', async () => {
      const pending = new Subject<LoanCreated>();
      borrow.mockReturnValue(pending);
      const element = await open();

      await clickBorrow(element, 1);

      expect(borrowButtons(element).every((b) => b.disabled)).toBe(true);
      expect(element.querySelectorAll('tbody .p-button-loading-icon')).toHaveLength(1);
    });

    it('double clic = une seule requête', async () => {
      const pending = new Subject<LoanCreated>();
      borrow.mockReturnValue(pending);
      const element = await open();
      const button = borrowButtons(element)[0];

      button.click();
      button.click();
      await settle();

      expect(borrow).toHaveBeenCalledTimes(1);
    });

    it('ignore un second appel direct tant que le premier est en cours', async () => {
      const pending = new Subject<LoanCreated>();
      borrow.mockReturnValue(pending);
      const element = await open();
      await clickBorrow(element, 0);
      const instance = page as unknown as { borrow(book: Book): void };

      instance.borrow(book(5));

      expect(borrow).toHaveBeenCalledTimes(1);
    });

    it('réactive les boutons une fois la réponse reçue', async () => {
      const pending = new Subject<LoanCreated>();
      borrow.mockReturnValue(pending);
      const element = await open();
      await clickBorrow(element, 0);

      pending.next(CREATED);
      pending.complete();
      await settle();

      expect(borrowButtons(element).some((b) => b.disabled)).toBe(false);
    });
  });

  describe('refus métier', () => {
    it.each([
      ['LOAN_LIMIT_REACHED', 409, 'Vous avez atteint la limite de 3 emprunts en cours'],
      ['MEMBER_HAS_OVERDUE_LOAN', 409, "Rendez d'abord vos livres en retard"],
      ['BOOK_ALREADY_BORROWED', 409, 'Vous avez déjà emprunté ce livre'],
    ])('%s : toast warn « %s », sans rechargement', async (code, status, message) => {
      borrow.mockReturnValue(throwError(() => problem(status, code)));
      const element = await open();
      search.mockClear();

      await clickBorrow(element);

      expect(toasts()).toEqual([{ severity: 'warn', summary: message, life: 5000 }]);
      expect(search).not.toHaveBeenCalled();
    });

    it('BOOK_NOT_AVAILABLE : toast « Ce livre n’est plus disponible » et rechargement', async () => {
      borrow.mockReturnValue(throwError(() => problem(409, 'BOOK_NOT_AVAILABLE')));
      const element = await open();
      search.mockClear();

      await clickBorrow(element);

      expect(toasts()).toEqual([
        { severity: 'warn', summary: "Ce livre n'est plus disponible", life: 5000 },
      ]);
      expect(search).toHaveBeenCalledTimes(1);
    });

    it('404 BOOK_NOT_FOUND : toast « Ce livre n’existe plus » et rechargement', async () => {
      borrow.mockReturnValue(throwError(() => problem(404, 'BOOK_NOT_FOUND')));
      const element = await open();
      search.mockClear();

      await clickBorrow(element);

      expect(toasts()).toEqual([
        { severity: 'warn', summary: "Ce livre n'existe plus", life: 5000 },
      ]);
      expect(search).toHaveBeenCalledTimes(1);
    });

    it('503 LOAN_BUSY : toast warn avec le message du contrat, sans rechargement', async () => {
      borrow.mockReturnValue(throwError(() => problem(503, 'LOAN_BUSY')));
      const element = await open();
      search.mockClear();

      await clickBorrow(element);

      expect(toasts()).toEqual([
        {
          severity: 'warn',
          summary: 'Le service est momentanément occupé, réessayez',
          life: 5000,
        },
      ]);
      expect(search).not.toHaveBeenCalled();
    });

    it.each([403, 500, 0])(
      'erreur %i : aucun toast de l’écran (intercepteur), pas de rechargement, boutons réactivés',
      async (status) => {
        borrow.mockReturnValue(throwError(() => new HttpErrorResponse({ status })));
        const element = await open();
        search.mockClear();

        await clickBorrow(element);

        expect(messages.add).not.toHaveBeenCalled();
        expect(search).not.toHaveBeenCalled();
        expect(borrowButtons(element).some((b) => b.disabled)).toBe(false);
      },
    );
  });

  /** Revue : focus programmé avant le rechargement (réponse synchrone ou non), jamais déplacé par un rechargement raté. */
  describe('focus après emprunt ou refus périmé', () => {
    function searchInput(element: HTMLElement): Element | null {
      return element.querySelector('app-book-search input');
    }

    it('succès avec rechargement synchrone : focus sur le bouton de la même ligne', async () => {
      const element = await open();

      await clickBorrow(element, 4);
      await later();

      expect(document.activeElement).toBe(
        element.querySelector('p-button[data-book-id="id-04"] button'),
      );
    });

    it('succès avec rechargement synchrone : focus sur la recherche si la ligne a disparu', async () => {
      const element = await open();
      search.mockReturnValue(of({ ...pageOf(9, 0, 10, 24), content: pageOf(10).content.slice(1) }));

      await clickBorrow(element, 0);
      await later();

      expect(document.activeElement).toBe(searchInput(element));
    });

    it.each([
      ['BOOK_NOT_AVAILABLE', 409],
      ['BOOK_NOT_FOUND', 404],
    ])('%s : recharge et place le focus sur la ligne restante', async (code, status) => {
      borrow.mockReturnValue(throwError(() => problem(status, code)));
      const element = await open();
      search.mockClear();

      await clickBorrow(element, 2);
      await later();

      expect(search).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(
        element.querySelector('p-button[data-book-id="id-02"] button'),
      );
    });

    it('BOOK_NOT_AVAILABLE : focus sur la recherche quand le livre a disparu de la liste', async () => {
      borrow.mockReturnValue(throwError(() => problem(409, 'BOOK_NOT_AVAILABLE')));
      const element = await open();
      search.mockReturnValue(of({ ...pageOf(9, 0, 10, 24), content: pageOf(10).content.slice(1) }));

      await clickBorrow(element, 0);
      await later();

      expect(document.activeElement).toBe(searchInput(element));
    });

    it.each([
      ['LOAN_LIMIT_REACHED', 409],
      ['MEMBER_HAS_OVERDUE_LOAN', 409],
      ['BOOK_ALREADY_BORROWED', 409],
      ['LOAN_BUSY', 503],
    ])('%s : ni rechargement ni focus programmé', async (code, status) => {
      borrow.mockReturnValue(throwError(() => problem(status, code)));
      const element = await open();
      search.mockClear();

      await clickBorrow(element, 1);
      await later();

      expect(search).not.toHaveBeenCalled();
      expect(document.activeElement).not.toBe(searchInput(element));
      expect(document.activeElement).not.toBe(borrowButtons(element)[1]);
    });

    it('rechargement raté après un emprunt : le chargement suivant ne déplace pas le focus', async () => {
      const element = await open();
      search.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));

      await clickBorrow(element, 1);
      await later();
      search.mockReturnValue(of(pageOf(10, 1, 10, 25)));
      await harness.navigateByUrl('/catalogue?page=2', Catalogue);
      await settle();
      await later();

      expect(document.activeElement).not.toBe(searchInput(element));
      expect(
        document.activeElement?.closest('p-button[data-book-id]') ?? null,
      ).toBeNull();
    });
  });
});
