import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Book } from '../../data/catalogue.model';
import { BookTable } from './book-table';

const BOOKS: Book[] = [
  {
    id: 'b-1',
    isbn: '9782070360024',
    title: "L'Étranger",
    author: 'Albert Camus',
    totalCopies: 3,
    availableCopies: 2,
  },
  {
    id: 'b-2',
    isbn: '9782070360420',
    title: 'La Peste',
    author: 'Albert Camus',
    totalCopies: 1,
    availableCopies: 1,
  },
];

/** RM-LOAN-21, RM-LOAN-26 : colonne d'action « Emprunter » du tableau du catalogue. */
describe('BookTable : action Emprunter', () => {
  let fixture: ComponentFixture<BookTable>;
  let borrowed: Book[];

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [BookTable] });
    fixture = TestBed.createComponent(BookTable);
    borrowed = [];
    fixture.componentInstance.borrow.subscribe((book) => borrowed.push(book));
  });

  async function render(
    inputs: { canBorrow?: boolean; borrowingId?: string | null; books?: Book[] } = {},
  ): Promise<HTMLElement> {
    const books = inputs.books ?? BOOKS;
    fixture.componentRef.setInput('books', books);
    fixture.componentRef.setInput('status', 'ready');
    fixture.componentRef.setInput('totalElements', books.length);
    fixture.componentRef.setInput('canBorrow', inputs.canBorrow ?? true);
    fixture.componentRef.setInput('borrowingId', inputs.borrowingId ?? null);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function buttons(element: HTMLElement): HTMLButtonElement[] {
    return Array.from(element.querySelectorAll<HTMLButtonElement>('tbody p-button button'));
  }

  it('affiche un bouton « Emprunter » par ligne quand canBorrow est vrai', async () => {
    const element = await render();

    expect(buttons(element)).toHaveLength(2);
    expect(buttons(element)[0].textContent).toContain('Emprunter');
  });

  it('ajoute la colonne d’action (5 colonnes au lieu de 4)', async () => {
    const element = await render();

    expect(element.querySelectorAll('th')).toHaveLength(5);
  });

  it('masque tout bouton et la colonne d’action quand canBorrow est faux', async () => {
    const element = await render({ canBorrow: false });

    expect(buttons(element)).toHaveLength(0);
    expect(element.textContent).not.toContain('Emprunter');
    expect(element.querySelectorAll('th')).toHaveLength(4);
  });

  it('porte le libellé accessible « Emprunter <titre> »', async () => {
    const element = await render();

    expect(buttons(element).map((b) => b.getAttribute('aria-label'))).toEqual([
      "Emprunter L'Étranger",
      'Emprunter La Peste',
    ]);
  });

  it('émet le livre de la ligne au clic', async () => {
    const element = await render();

    buttons(element)[1].click();

    expect(borrowed).toEqual([BOOKS[1]]);
  });

  it('désactive tous les boutons pendant un emprunt et montre l’indicateur sur la ligne concernée', async () => {
    const element = await render({ borrowingId: 'b-1' });

    expect(buttons(element).every((b) => b.disabled)).toBe(true);
    expect(
      buttons(element)[0].querySelector('.p-button-loading-icon, .p-button-icon'),
    ).not.toBeNull();
    expect(element.querySelectorAll('tbody .p-button-loading-icon')).toHaveLength(1);
  });

  it('ne désactive aucun bouton sans emprunt en cours', async () => {
    const element = await render({ borrowingId: null });

    expect(buttons(element).some((b) => b.disabled)).toBe(false);
  });

  it('focusBorrow donne le focus au bouton du livre et renvoie vrai', async () => {
    const element = await render();

    const focused = fixture.componentInstance.focusBorrow('b-2');

    expect(focused).toBe(true);
    expect(document.activeElement).toBe(buttons(element)[1]);
  });

  it('focusBorrow renvoie faux si la ligne n’existe plus', async () => {
    await render();

    expect(fixture.componentInstance.focusBorrow('absent')).toBe(false);
  });
});
