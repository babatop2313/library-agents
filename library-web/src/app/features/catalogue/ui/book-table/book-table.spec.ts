import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';

import { Book } from '../../data/catalogue.model';
import { BookPageChange, BookTable, BookTableStatus } from './book-table';

const BOOKS: Book[] = [
  {
    id: '1',
    isbn: '9782070360024',
    title: "L'Étranger",
    author: 'Albert Camus',
    totalCopies: 3,
    availableCopies: 2,
  },
  {
    id: '2',
    isbn: '9782070360420',
    title: 'La Peste',
    author: 'Albert Camus',
    totalCopies: 1,
    availableCopies: 1,
  },
];

/** RM-CAT-28, RM-CAT-30, RM-CAT-32, RM-CAT-41 : rendu du tableau, états et pagination serveur. */
describe('BookTable', () => {
  let fixture: ComponentFixture<BookTable>;
  let pages: BookPageChange[];
  let retries: number;
  let clears: number;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [BookTable] });
    fixture = TestBed.createComponent(BookTable);
    pages = [];
    retries = 0;
    clears = 0;
    fixture.componentInstance.pageChange.subscribe((change) => pages.push(change));
    fixture.componentInstance.retry.subscribe(() => retries++);
    fixture.componentInstance.clearSearch.subscribe(() => clears++);
  });

  async function render(
    inputs: {
      books?: Book[];
      status?: BookTableStatus;
      total?: number;
      page?: number;
      size?: number;
      query?: string;
      canRegister?: boolean;
      retrying?: boolean;
    } = {},
  ): Promise<HTMLElement> {
    fixture.componentRef.setInput('books', inputs.books ?? []);
    fixture.componentRef.setInput('status', inputs.status ?? 'ready');
    fixture.componentRef.setInput('totalElements', inputs.total ?? (inputs.books ?? []).length);
    fixture.componentRef.setInput('page', inputs.page ?? 1);
    fixture.componentRef.setInput('size', inputs.size ?? 10);
    fixture.componentRef.setInput('query', inputs.query ?? '');
    fixture.componentRef.setInput('canRegister', inputs.canRegister ?? false);
    fixture.componentRef.setInput('retrying', inputs.retrying ?? false);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  describe('liste', () => {
    it('affiche les colonnes Titre, Auteur, ISBN et Exemplaires disponibles', async () => {
      const element = await render({ books: BOOKS });

      const headers = Array.from(element.querySelectorAll('th')).map((th) => th.textContent);
      expect(headers[0]).toContain('Titre');
      expect(headers[1]).toContain('Auteur');
      expect(headers[2]).toContain('ISBN');
      expect(headers[3]).toContain('Exemplaires disponibles');
      element.querySelectorAll('th').forEach((th) => expect(th.getAttribute('scope')).toBe('col'));
    });

    it('rend une ligne par livre avec titre, auteur et ISBN', async () => {
      const element = await render({ books: BOOKS });

      const rows = element.querySelectorAll('tbody tr');
      expect(rows).toHaveLength(2);
      expect(rows[0].textContent).toContain("L'Étranger");
      expect(rows[0].textContent).toContain('Albert Camus');
      expect(rows[0].textContent).toContain('9782070360024');
      expect(rows[1].textContent).toContain('La Peste');
    });

    it('affiche « disponibles / total » (« 2 / 3 ») car le total est fourni par l’API', async () => {
      const element = await render({ books: BOOKS });

      const cell = element.querySelectorAll('tbody tr')[0].querySelector('td.text-end');
      expect(cell?.textContent).toContain('2 / 3');
    });

    it('fournit un équivalent textuel accessible (pluriel et singulier)', async () => {
      const element = await render({ books: BOOKS });

      const hidden = Array.from(element.querySelectorAll('tbody .visually-hidden')).map((e) =>
        e.textContent?.trim(),
      );
      expect(hidden).toEqual(['2 disponibles sur 3', '1 disponible sur 1']);
    });

    it('porte une légende accessible au tableau', async () => {
      const element = await render({ books: BOOKS });

      expect(element.querySelector('caption')?.textContent).toContain('Livres disponibles');
    });

    it('échappe le texte des livres (jamais de HTML brut)', async () => {
      const element = await render({
        books: [{ ...BOOKS[0], title: '<img src=x onerror=alert(1)>' }],
      });

      expect(element.querySelector('tbody img')).toBeNull();
      expect(element.querySelector('tbody')?.textContent).toContain('<img src=x onerror=alert(1)>');
    });
  });

  describe('chargement', () => {
    it('affiche des lignes squelettes au premier chargement et marque le tableau occupé', async () => {
      const element = await render({ status: 'skeleton', size: 20 });

      expect(element.querySelectorAll('tbody tr p-skeleton').length).toBeGreaterThan(0);
      expect(element.querySelectorAll('tbody tr')).toHaveLength(10);
      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('true');
    });

    it('limite les squelettes à la taille de la page quand elle est plus petite', async () => {
      const element = await render({ status: 'skeleton', size: 10 });

      expect(element.querySelectorAll('tbody tr')).toHaveLength(10);
    });

    it('conserve les lignes précédentes pendant un rechargement', async () => {
      const element = await render({ books: BOOKS, status: 'reloading' });

      expect(element.querySelectorAll('tbody tr')).toHaveLength(2);
      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('true');
    });

    it('n’est pas occupé quand la liste est prête', async () => {
      const element = await render({ books: BOOKS });

      expect(element.querySelector('p-table')?.getAttribute('aria-busy')).toBe('false');
    });
  });

  describe('états vides', () => {
    it('affiche « Aucun livre disponible pour le moment » sans recherche', async () => {
      const element = await render({ books: [] });

      expect(element.textContent).toContain('Aucun livre disponible pour le moment');
    });

    it('invite un bibliothécaire à ajouter un livre', async () => {
      const element = await render({ books: [], canRegister: true });

      expect(element.textContent).toContain('Utilisez « Ajouter un livre »');
    });

    it('ne propose pas l’ajout à un membre', async () => {
      const element = await render({ books: [], canRegister: false });

      expect(element.textContent).not.toContain('Ajouter un livre');
      expect(element.textContent).toContain('Revenez un peu plus tard');
    });

    it('affiche « Aucun livre ne correspond à "<q>" » pour une recherche sans résultat', async () => {
      const element = await render({ books: [], query: 'introuvab' });

      expect(element.textContent).toContain('Aucun livre ne correspond à "introuvab"');
      expect(element.textContent).not.toContain('Aucun livre disponible pour le moment');
    });

    it('affiche le texte de recherche échappé, jamais en HTML brut', async () => {
      const element = await render({ books: [], query: '<b>gras</b>' });

      expect(element.querySelector('.empty-state b')).toBeNull();
      expect(element.textContent).toContain('Aucun livre ne correspond à "<b>gras</b>"');
    });

    it('émet clearSearch depuis « Effacer la recherche »', async () => {
      const element = await render({ books: [], query: 'zzz' });

      (element.querySelector('.empty-state p-button button') as HTMLButtonElement).click();

      expect(clears).toBe(1);
    });
  });

  describe('erreurs', () => {
    it('affiche l’erreur de chargement avec le bouton « Réessayer »', async () => {
      const element = await render({ status: 'error' });

      expect(element.textContent).toContain("Le catalogue n'a pas pu être chargé");
      expect(element.textContent).toContain('Réessayer');
    });

    it('émet retry au clic sur « Réessayer »', async () => {
      const element = await render({ status: 'error' });

      (element.querySelector('.empty-state p-button button') as HTMLButtonElement).click();

      expect(retries).toBe(1);
    });

    it('affiche l’état de chargement sur le bouton pendant une nouvelle tentative', async () => {
      const element = await render({ status: 'error', retrying: true });

      const button = element.querySelector('.empty-state p-button button') as HTMLButtonElement;
      expect(button.disabled).toBe(true);
    });

    it('affiche un message de droits insuffisants pour un 403, sans « Réessayer »', async () => {
      const element = await render({ status: 'forbidden' });

      expect(element.textContent).toContain('pas les droits nécessaires');
      expect(element.textContent).not.toContain('Réessayer');
    });

    it('masque le paginateur en cas d’erreur', async () => {
      const element = await render({ status: 'error', total: 25 });

      expect(element.querySelector('p-paginator')).toBeNull();
    });
  });

  describe('pagination serveur', () => {
    it('affiche le paginateur quand il y a des résultats (25 livres : 3 pages)', async () => {
      const element = await render({ books: BOOKS, total: 25 });

      expect(element.querySelector('p-paginator')).not.toBeNull();
      expect(element.querySelectorAll('.p-paginator-page')).toHaveLength(3);
    });

    it('masque le paginateur pour un catalogue vide', async () => {
      const element = await render({ books: [], total: 0 });

      expect(element.querySelector('p-paginator')).toBeNull();
    });

    it('propose les tailles 10, 20 et 50', async () => {
      const element = await render({ books: BOOKS, total: 25 });

      const paginator = fixture.debugElement.query(By.css('p-paginator'));
      expect(paginator.componentInstance.rowsPerPageOptions).toEqual([10, 20, 50]);
      expect(element.querySelector('.p-paginator-rpp-dropdown, p-select')).not.toBeNull();
    });

    it('place la première ligne selon la page courante (base 1)', async () => {
      await render({ books: BOOKS, total: 25, page: 3, size: 10 });

      const paginator = fixture.debugElement.query(By.css('p-paginator'));
      expect(paginator.componentInstance.first).toBe(20);
      expect(paginator.componentInstance.rows).toBe(10);
    });

    it('émet la page en base 1 au changement de page', async () => {
      await render({ books: BOOKS, total: 25, page: 1, size: 10 });

      fixture.debugElement
        .query(By.css('p-paginator'))
        .triggerEventHandler('onPageChange', { page: 1, rows: 10, first: 10, pageCount: 3 });

      expect(pages).toEqual([{ page: 2, size: 10 }]);
    });

    it('revient à la page 1 quand la taille change', async () => {
      await render({ books: BOOKS, total: 25, page: 3, size: 10 });

      fixture.debugElement
        .query(By.css('p-paginator'))
        .triggerEventHandler('onPageChange', { page: 1, rows: 20, first: 20, pageCount: 2 });

      expect(pages).toEqual([{ page: 1, size: 20 }]);
    });

    it('émet aussi depuis un clic sur le bouton de page suivante', async () => {
      const element = await render({ books: BOOKS, total: 25, page: 1, size: 10 });

      (element.querySelector('.p-paginator-next') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(pages).toEqual([{ page: 2, size: 10 }]);
    });

    it('ne trie pas côté client : le tableau n’a aucune colonne triable', async () => {
      const element = await render({ books: BOOKS });

      expect(element.querySelector('p-sorticon, [pSortableColumn]')).toBeNull();
    });
  });
});
