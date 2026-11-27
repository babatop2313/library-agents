import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BookSearch } from './book-search';

/** RM-CAT-29 : anti-rebond de 300 ms, validation par Entrée, bouton d'effacement. */
describe('BookSearch', () => {
  let fixture: ComponentFixture<BookSearch>;
  let emitted: string[];

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [BookSearch] });
    fixture = TestBed.createComponent(BookSearch);
    emitted = [];
    fixture.componentInstance.searchChange.subscribe((q) => emitted.push(q));
    await fixture.whenStable();
    // Minuteurs simulés après le premier rendu : le planificateur d'Angular utilise setTimeout.
    vi.useFakeTimers();
  });

  afterEach(() => vi.useRealTimers());

  function input(): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector('input') as HTMLInputElement;
  }

  function type(text: string): void {
    const element = input();
    element.value = text;
    element.dispatchEvent(new Event('input'));
  }

  function clearButton(): HTMLButtonElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('p-button button');
  }

  function setValue(value: string): void {
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
  }

  it('associe le libellé « Rechercher par titre ou auteur » au champ', () => {
    const label = (fixture.nativeElement as HTMLElement).querySelector('label') as HTMLLabelElement;

    expect(label.textContent).toContain('Rechercher par titre ou auteur');
    expect(label.htmlFor).toBe(input().id);
  });

  it('limite la saisie à 100 caractères', () => {
    expect(input().getAttribute('maxlength')).toBe('100');
  });

  it('n’émet rien avant la fin de la temporisation de 300 ms', () => {
    type('camus');
    vi.advanceTimersByTime(299);

    expect(emitted).toEqual([]);
  });

  it('émet une seule fois le texte après 300 ms sans frappe', () => {
    type('camus');
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual(['camus']);
  });

  it('saisie rapide « c », « ca », « cam » : un seul appel avec « cam »', () => {
    type('c');
    vi.advanceTimersByTime(100);
    type('ca');
    vi.advanceTimersByTime(100);
    type('cam');
    vi.advanceTimersByTime(299);
    expect(emitted).toEqual([]);

    vi.advanceTimersByTime(1);

    expect(emitted).toEqual(['cam']);
  });

  it('rogne le texte avant de l’émettre', () => {
    type('  zola  ');
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual(['zola']);
  });

  it('émet tout de suite à la validation par Entrée, sans second envoi à l’échéance', () => {
    type('camus');
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(emitted).toEqual(['camus']);

    vi.advanceTimersByTime(300);

    expect(emitted).toEqual(['camus']);
  });

  it('n’émet pas deux fois la même valeur', () => {
    type('camus');
    vi.advanceTimersByTime(300);
    type('camus ');
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual(['camus']);
  });

  it('n’émet rien si le texte retombe à la valeur déjà appliquée', () => {
    type('c');
    vi.advanceTimersByTime(100);
    type('');
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual([]);
  });

  it('émet une chaîne vide quand on efface le texte déjà appliqué', () => {
    type('camus');
    vi.advanceTimersByTime(300);
    type('');
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual(['camus', '']);
  });

  it('n’affiche le bouton d’effacement que si le champ contient du texte', () => {
    expect(clearButton()).toBeNull();

    type('camus');
    fixture.detectChanges();

    expect(clearButton()).not.toBeNull();
    expect(clearButton()?.getAttribute('aria-label')).toBe('Effacer la recherche');
  });

  it('vide le champ, relance sans filtre et rend le focus au champ', () => {
    type('camus');
    vi.advanceTimersByTime(300);
    fixture.detectChanges();
    const focus = vi.spyOn(input(), 'focus');

    clearButton()?.click();
    fixture.detectChanges();

    expect(emitted).toEqual(['camus', '']);
    expect(input().value).toBe('');
    expect(focus).toHaveBeenCalled();
    expect(clearButton()).toBeNull();
  });

  it('clear() annule la temporisation en attente', () => {
    type('camus');
    fixture.componentInstance.clear();
    vi.advanceTimersByTime(300);
    fixture.detectChanges();

    expect(emitted).toEqual([]);
  });

  it('affiche la valeur de l’URL fournie en entrée sans la réémettre', () => {
    setValue('camus');

    expect(input().value).toBe('camus');
    vi.advanceTimersByTime(300);
    expect(emitted).toEqual([]);
  });

  it('se resynchronise quand la valeur de l’URL change de l’extérieur', () => {
    setValue('camus');
    setValue('zola');

    expect(input().value).toBe('zola');
  });

  it('annule la temporisation en attente quand l’URL impose une autre valeur', () => {
    type('cam');
    setValue('hugo');
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual([]);
    expect(input().value).toBe('hugo');
  });

  it('annule la temporisation à la destruction du composant', () => {
    type('camus');
    fixture.destroy();
    vi.advanceTimersByTime(300);

    expect(emitted).toEqual([]);
  });

  /** RM-LOAN-26 : après un emprunt qui retire la ligne, le focus revient au champ de recherche. */
  it('focus() donne le focus au champ de recherche', () => {
    fixture.componentInstance.focus();

    expect(document.activeElement).toBe(input());
  });
});
