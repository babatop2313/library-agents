import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MessageService } from 'primeng/api';
import { Observable, of, Subject, throwError } from 'rxjs';

import { AuthService } from '../../../../core/auth/auth.service';
import { Role } from '../../../../core/auth/current-user.model';
import { Page } from '../../../../core/http/page';
import { ProblemDetail } from '../../../../core/http/problem-detail';
import { Book, BookSearchParams, RegisterBookRequest } from '../../data/catalogue.model';
import { CatalogueService } from '../../data/catalogue.service';
import { BookFormDialog } from '../../ui/book-form-dialog/book-form-dialog';
import { BookSearch } from '../../ui/book-search/book-search';
import { BookTable } from '../../ui/book-table/book-table';
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

const NEW_BOOK: RegisterBookRequest = {
  isbn: '978-2-07-036822-8',
  title: "L'Étranger",
  author: 'Albert Camus',
  copies: 3,
};

/** RM-CAT-27 à RM-CAT-33, RM-CAT-35 à RM-CAT-40 : page Catalogue (liste, recherche, URL, enregistrement). */
describe('Catalogue (page)', () => {
  let roles: Role[];
  let search: ReturnType<typeof vi.fn<(params: BookSearchParams) => Observable<Page<Book>>>>;
  let register: ReturnType<typeof vi.fn<(body: RegisterBookRequest) => Observable<Book>>>;
  let messages: MessageService;
  let router: Router;
  let harness: RouterTestingHarness;

  beforeEach(() => {
    roles = ['MEMBER'];
    search = vi.fn<(params: BookSearchParams) => Observable<Page<Book>>>(() => of(pageOf(10, 0, 10, 25)));
    register = vi.fn<(body: RegisterBookRequest) => Observable<Book>>(() => of(book(99)));
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideRouter([{ path: 'catalogue', component: Catalogue }]),
        { provide: CatalogueService, useValue: { search, register } },
        { provide: AuthService, useValue: { hasRole: (role: Role) => roles.includes(role) } },
      ],
    });
    messages = TestBed.inject(MessageService);
    vi.spyOn(messages, 'add');
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    document.body.querySelectorAll('.p-dialog-mask').forEach((mask) => mask.remove());
  });

  async function open(url = '/catalogue'): Promise<HTMLElement> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, Catalogue);
    await settle();
    return harness.fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
    harness.fixture.detectChanges();
  }

  function component(): Catalogue {
    return harness.fixture.debugElement.query(By.directive(Catalogue)).componentInstance;
  }

  function child<T>(type: new (...args: never[]) => T): T {
    return harness.fixture.debugElement.query(By.directive(type)).componentInstance;
  }

  function rows(element: HTMLElement): NodeListOf<Element> {
    return element.querySelectorAll('tbody tr');
  }

  function addButton(element: HTMLElement): HTMLElement | null {
    return element.querySelector('p-button[label="Ajouter un livre"]');
  }

  function lastSearch(): BookSearchParams {
    return search.mock.calls.at(-1)![0];
  }

  describe('liste', () => {
    it('charge la première page : GET page=0, size=10, sans q', async () => {
      const element = await open();

      expect(search).toHaveBeenCalledTimes(1);
      expect(search).toHaveBeenCalledWith({ page: 0, size: 10 });
      expect(rows(element)).toHaveLength(10);
      expect(rows(element)[0].textContent).toContain('Livre 00');
    });

    it('affiche le titre « Catalogue », les colonnes et le paginateur à 3 pages pour 25 livres', async () => {
      const element = await open();

      expect(element.querySelector('h1')?.textContent).toContain('Catalogue');
      expect(element.querySelectorAll('th')).toHaveLength(4);
      expect(element.querySelectorAll('.p-paginator-page')).toHaveLength(3);
    });

    it('annonce le nombre de livres disponibles dans une zone aria-live', async () => {
      const element = await open();

      const counter = element.querySelector('[aria-live="polite"]');
      expect(counter?.textContent).toContain('25 livres disponibles');
    });

    it('annonce « 1 livre disponible » au singulier', async () => {
      search.mockReturnValue(of(pageOf(1, 0, 10, 1)));

      const element = await open();

      expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
        '1 livre disponible',
      );
    });

    it('affiche un état de chargement pendant l’appel', async () => {
      const pending = new Subject<Page<Book>>();
      search.mockReturnValue(pending);

      const element = await open();

      expect(element.querySelectorAll('tbody p-skeleton').length).toBeGreaterThan(0);
      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('true');
      expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain('Chargement');
    });

    it('affiche « Aucun livre disponible pour le moment », sans paginateur, pour un catalogue vide', async () => {
      search.mockReturnValue(of(pageOf(0, 0, 10, 0)));

      const element = await open();

      expect(element.textContent).toContain('Aucun livre disponible pour le moment');
      expect(element.querySelector('p-paginator')).toBeNull();
    });

    it('affiche « Aucun livre ne correspond à "<q>" » pour une recherche sans résultat', async () => {
      search.mockReturnValue(of(pageOf(0, 0, 10, 0)));

      const element = await open('/catalogue?q=introuvab');

      expect(element.textContent).toContain('Aucun livre ne correspond à "introuvab"');
    });

    it('échappe le texte recherché affiché (jamais en HTML brut)', async () => {
      search.mockReturnValue(of(pageOf(0, 0, 10, 0)));

      const element = await open(
        '/catalogue?q=' + encodeURIComponent('<img src=x onerror=alert(1)>'),
      );

      expect(element.querySelector('img')).toBeNull();
      expect(element.textContent).toContain('<img src=x onerror=alert(1)>');
    });

    it('annonce « 2 résultats pour "camus" » pour une recherche', async () => {
      search.mockReturnValue(of(pageOf(2, 0, 10, 2)));

      const element = await open('/catalogue?q=camus');

      expect(element.querySelector('[aria-live="polite"]')?.textContent).toContain(
        '2 résultats pour "camus"',
      );
    });
  });

  describe('URL partageable', () => {
    it('/catalogue?q=camus&size=20 : champ pré-rempli et requête q=camus&size=20', async () => {
      const element = await open('/catalogue?q=camus&size=20');

      expect(lastSearch()).toEqual({ page: 0, size: 20, q: 'camus' });
      expect((element.querySelector('input[type="search"]') as HTMLInputElement).value).toBe(
        'camus',
      );
    });

    it('convertit la page de l’URL (base 1) en page de l’API (base 0)', async () => {
      await open('/catalogue?page=3');

      expect(lastSearch()).toEqual({ page: 2, size: 10 });
    });

    it.each(['abc', '0', '-4', ''])('page=%j dans l’URL vaut la page 1', async (page) => {
      await open(`/catalogue?page=${page}`);

      expect(lastSearch().page).toBe(0);
    });

    it('ignore une taille hors des choix 10, 20, 50', async () => {
      await open('/catalogue?size=7');

      expect(lastSearch().size).toBe(10);
    });

    it('place le numéro de page de la vue dans le paginateur', async () => {
      search.mockReturnValue(of(pageOf(5, 2, 10, 25)));

      const element = await open('/catalogue?page=3');

      const paginator = harness.fixture.debugElement.query(By.css('p-paginator')).componentInstance;
      expect(paginator.first).toBe(20);
      expect(rows(element)).toHaveLength(5);
    });
  });

  describe('pagination et recherche', () => {
    it('changer de page met à jour l’URL (base 1) et appelle l’API avec page=1', async () => {
      await open();
      search.mockClear();

      child(BookTable).pageChange.emit({ page: 2, size: 10 });
      await settle();

      expect(router.url).toBe('/catalogue?page=2');
      expect(search).toHaveBeenCalledTimes(1);
      expect(lastSearch()).toEqual({ page: 1, size: 10 });
    });

    it('changer la taille met à jour l’URL et recharge', async () => {
      await open();
      search.mockClear();

      child(BookTable).pageChange.emit({ page: 1, size: 50 });
      await settle();

      expect(router.url).toBe('/catalogue?size=50');
      expect(lastSearch()).toEqual({ page: 0, size: 50 });
    });

    it('une recherche revient à la page 0 et conserve la taille', async () => {
      await open('/catalogue?page=3&size=20');
      search.mockClear();

      child(BookSearch).searchChange.emit('camus');
      await settle();

      expect(lastSearch()).toEqual({ page: 0, size: 20, q: 'camus' });
      expect(router.url).toBe('/catalogue?q=camus&size=20');
    });

    it('un seul appel par recherche', async () => {
      await open();
      search.mockClear();

      child(BookSearch).searchChange.emit('camus');
      await settle();

      expect(search).toHaveBeenCalledTimes(1);
    });

    it('effacer la recherche retire q de l’URL et recharge sans filtre', async () => {
      await open('/catalogue?q=camus');
      search.mockClear();

      child(BookSearch).searchChange.emit('');
      await settle();

      expect(router.url).toBe('/catalogue');
      expect(lastSearch()).toEqual({ page: 0, size: 10 });
    });

    it('ignore la réponse obsolète : seule la dernière recherche est affichée', async () => {
      const first = new Subject<Page<Book>>();
      const second = new Subject<Page<Book>>();
      await open();
      search.mockReset();
      search.mockReturnValueOnce(first).mockReturnValueOnce(second);

      child(BookSearch).searchChange.emit('ca');
      await settle();
      child(BookSearch).searchChange.emit('cam');
      await settle();
      second.next({ ...pageOf(1, 0, 10, 1), content: [{ ...book(1), title: 'Camus récent' }] });
      await settle();
      first.next({ ...pageOf(1, 0, 10, 1), content: [{ ...book(2), title: 'Réponse obsolète' }] });
      await settle();

      const text = (harness.fixture.nativeElement as HTMLElement).textContent;
      expect(text).toContain('Camus récent');
      expect(text).not.toContain('Réponse obsolète');
      expect(first.observed).toBe(false);
    });

    it('une page au-delà de la dernière redirige vers la dernière page sans empiler l’historique', async () => {
      search.mockImplementation((params: BookSearchParams) =>
        of(params.page === 9 ? { ...pageOf(0, 9, 10, 25), content: [] } : pageOf(5, 2, 10, 25)),
      );
      const navigate = vi.spyOn(router, 'navigate');

      await open('/catalogue?page=10');

      expect(navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({
          queryParams: expect.objectContaining({ page: 3 }),
          replaceUrl: true,
        }),
      );
      expect(router.url).toBe('/catalogue?page=3');
    });
  });

  describe('erreurs de chargement', () => {
    it('affiche l’erreur et « Réessayer » ; le bouton recharge la liste', async () => {
      search.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
      const element = await open();
      expect(element.textContent).toContain("Le catalogue n'a pas pu être chargé");
      expect(search).toHaveBeenCalledTimes(1);

      search.mockReturnValue(of(pageOf(10, 0, 10, 25)));
      (element.querySelector('.empty-state p-button button') as HTMLButtonElement).click();
      await settle();

      expect(search).toHaveBeenCalledTimes(2);
      expect(rows(element)).toHaveLength(10);
      expect(element.textContent).not.toContain("Le catalogue n'a pas pu être chargé");
    });

    it('affiche un message de droits insuffisants pour un 403', async () => {
      search.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));

      const element = await open();

      expect(element.textContent).toContain('pas les droits nécessaires');
      expect(element.textContent).not.toContain('Réessayer');
    });

    it('n’annonce aucun compteur en cas d’erreur', async () => {
      search.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

      const element = await open();

      expect(element.querySelector('[aria-live="polite"]')?.textContent?.trim()).toBe('');
    });
  });

  describe('bouton « Ajouter un livre » selon le rôle', () => {
    it('est masqué pour un MEMBER, sans message', async () => {
      roles = ['MEMBER'];

      const element = await open();

      expect(addButton(element)).toBeNull();
      expect(element.querySelector('app-book-form-dialog')).toBeNull();
      expect(element.textContent).not.toContain('Ajouter un livre');
    });

    it('est affiché pour un LIBRARIAN', async () => {
      roles = ['LIBRARIAN'];

      const element = await open();

      expect(addButton(element)).not.toBeNull();
    });

    it('est affiché pour un utilisateur cumulant LIBRARIAN et MEMBER', async () => {
      roles = ['MEMBER', 'LIBRARIAN'];

      const element = await open();

      expect(addButton(element)).not.toBeNull();
    });

    it('ouvre le dialogue d’enregistrement au clic', async () => {
      roles = ['LIBRARIAN'];
      const element = await open();

      (addButton(element)?.querySelector('button') as HTMLButtonElement).click();
      await settle();

      expect(component()['registerOpen']()).toBe(true);
      expect(child(BookFormDialog).visible()).toBe(true);
    });
  });

  describe('enregistrement', () => {
    beforeEach(() => {
      roles = ['LIBRARIAN'];
    });

    async function openWithDialog(url = '/catalogue'): Promise<HTMLElement> {
      const element = await open(url);
      (addButton(element)?.querySelector('button') as HTMLButtonElement).click();
      await settle();
      return element;
    }

    it('succès : envoie le livre, notifie « Livre enregistré », ferme le dialogue et recharge', async () => {
      await openWithDialog();
      search.mockClear();

      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();

      expect(register).toHaveBeenCalledWith(NEW_BOOK);
      expect(messages.add).toHaveBeenCalledWith(
        expect.objectContaining({ severity: 'success', summary: 'Livre enregistré' }),
      );
      expect(component()['registerOpen']()).toBe(false);
      expect(search).toHaveBeenCalledTimes(1);
    });

    it('succès : conserve la page courante et la recherche au rechargement', async () => {
      search.mockReturnValue(of(pageOf(10, 2, 20, 100)));
      await openWithDialog('/catalogue?q=camus&page=3&size=20');
      search.mockClear();

      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();

      expect(lastSearch()).toEqual({ page: 2, size: 20, q: 'camus' });
      expect(router.url).toBe('/catalogue?q=camus&page=3&size=20');
    });

    it('envoi unique : un second envoi pendant l’appel est ignoré', async () => {
      const pending = new Subject<Book>();
      register.mockReturnValue(pending);
      await openWithDialog();

      child(BookFormDialog).save.emit(NEW_BOOK);
      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();

      expect(register).toHaveBeenCalledTimes(1);
      expect(component()['submitting']()).toBe(true);
      expect(child(BookFormDialog).submitting()).toBe(true);

      pending.next(book(99));
      pending.complete();
      await settle();

      expect(component()['submitting']()).toBe(false);
    });

    it('400 : l’erreur est transmise au formulaire, le dialogue reste ouvert, pas de rechargement', async () => {
      const problem: ProblemDetail = {
        title: 'Validation échouée',
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'isbn', code: 'INVALID_FORMAT', message: 'ISBN invalide' }],
      };
      register.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: problem })),
      );
      await openWithDialog();
      search.mockClear();

      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();

      expect(component()['serverError']()).toEqual({ status: 400, ...problem });
      expect(component()['registerOpen']()).toBe(true);
      expect(component()['submitting']()).toBe(false);
      expect(search).not.toHaveBeenCalled();
      expect(messages.add).not.toHaveBeenCalled();
    });

    it('409 : l’erreur est transmise au formulaire, aucun livre n’est ajouté à la liste', async () => {
      const problem: ProblemDetail = { title: 'ISBN déjà enregistré', code: 'ISBN_ALREADY_EXISTS' };
      register.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409, error: problem })),
      );
      await openWithDialog();
      search.mockClear();

      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();

      expect(component()['serverError']()).toEqual({ status: 409, ...problem });
      expect(component()['registerOpen']()).toBe(true);
      expect(search).not.toHaveBeenCalled();
    });

    it('400 sans corps ProblemDetail : transmet au moins le statut', async () => {
      register.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400 })));
      await openWithDialog();

      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();

      expect(component()['serverError']()).toEqual({ status: 400 });
    });

    it.each([403, 500, 0])(
      '%i : le dialogue reste ouvert, sans erreur de champ (toast de l’intercepteur)',
      async (status) => {
        register.mockReturnValue(throwError(() => new HttpErrorResponse({ status })));
        await openWithDialog();
        search.mockClear();

        child(BookFormDialog).save.emit(NEW_BOOK);
        await settle();

        expect(component()['serverError']()).toBeNull();
        expect(component()['registerOpen']()).toBe(true);
        expect(component()['submitting']()).toBe(false);
        expect(search).not.toHaveBeenCalled();
      },
    );

    it('efface l’erreur du serveur à la réouverture du dialogue', async () => {
      register.mockReturnValue(
        throwError(
          () => new HttpErrorResponse({ status: 409, error: { code: 'ISBN_ALREADY_EXISTS' } }),
        ),
      );
      const element = await openWithDialog();
      child(BookFormDialog).save.emit(NEW_BOOK);
      await settle();
      expect(component()['serverError']()).not.toBeNull();

      child(BookFormDialog).visible.set(false);
      await settle();
      (addButton(element)?.querySelector('button') as HTMLButtonElement).click();
      await settle();

      expect(component()['serverError']()).toBeNull();
    });
  });
});
