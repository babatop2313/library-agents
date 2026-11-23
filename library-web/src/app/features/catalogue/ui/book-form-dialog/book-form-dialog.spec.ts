import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ProblemDetail } from '../../../../core/http/problem-detail';
import { RegisterBookRequest } from '../../data/catalogue.model';
import { BookFormDialog } from './book-form-dialog';

/** RM-CAT-34 à RM-CAT-38, RM-CAT-41 : formulaire d'enregistrement d'un livre. */
describe('BookFormDialog', () => {
  let fixture: ComponentFixture<BookFormDialog>;
  let saved: RegisterBookRequest[];

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [BookFormDialog] });
    fixture = TestBed.createComponent(BookFormDialog);
    saved = [];
    fixture.componentInstance.save.subscribe((body) => saved.push(body));
    await open();
  });

  afterEach(() => {
    fixture.destroy();
    document.body.querySelectorAll('.p-dialog-mask').forEach((mask) => mask.remove());
  });

  async function open(): Promise<void> {
    fixture.componentRef.setInput('visible', true);
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function field(id: 'isbn' | 'title' | 'author'): HTMLInputElement {
    return document.getElementById(`book-${id}`) as HTMLInputElement;
  }

  function type(id: 'isbn' | 'title' | 'author', value: string): void {
    const input = field(id);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function setCopies(value: number | null): void {
    fixture.componentInstance['form'].controls.copies.setValue(value);
  }

  function fillValid(): void {
    type('isbn', '978-2-07-036822-8');
    type('title', "L'Étranger");
    type('author', 'Albert Camus');
    setCopies(3);
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(document.querySelectorAll('.p-dialog button')).find((b) =>
      b.textContent?.includes(label),
    );
    if (!found) {
      throw new Error(`Bouton « ${label} » introuvable`);
    }
    return found as HTMLButtonElement;
  }

  function errorOf(name: string): string | null {
    return document.getElementById(`book-${name}-error`)?.textContent?.trim() ?? null;
  }

  async function submit(): Promise<void> {
    button('Enregistrer').click();
    await settle();
  }

  async function serverError(error: ProblemDetail): Promise<void> {
    fixture.componentRef.setInput('serverError', error);
    await settle();
  }

  describe('affichage', () => {
    it('ouvre un dialogue « Ajouter un livre » avec les quatre champs et leurs libellés associés', () => {
      expect(document.body.textContent).toContain('Ajouter un livre');
      for (const [id, label] of [
        ['isbn', 'ISBN'],
        ['title', 'Titre'],
        ['author', 'Auteur'],
      ] as const) {
        const element = document.querySelector(`label[for="book-${id}"]`);
        expect(element?.textContent).toContain(label);
        expect(field(id)).not.toBeNull();
      }
      expect(document.querySelector('label[for="book-copies"]')?.textContent).toContain(
        "Nombre d'exemplaires",
      );
      expect(document.getElementById('book-copies')).not.toBeNull();
    });

    it('relie chaque champ à son aide par aria-describedby et le marque obligatoire', () => {
      expect(field('isbn').getAttribute('aria-describedby')).toBe('book-isbn-help');
      expect(field('title').getAttribute('aria-describedby')).toBe('book-title-help');
      expect(field('isbn').getAttribute('aria-required')).toBe('true');
      expect(document.getElementById('book-isbn-help')).not.toBeNull();
    });

    it('n’affiche aucune erreur à l’ouverture', () => {
      expect(document.querySelectorAll('[id$="-error"]')).toHaveLength(0);
    });
  });

  describe('validation côté client', () => {
    it('formulaire vide : les quatre champs sont en erreur et rien n’est envoyé', async () => {
      await submit();

      expect(saved).toEqual([]);
      expect(errorOf('isbn')).toContain("Saisissez l'ISBN");
      expect(errorOf('title')).toContain('Saisissez le titre');
      expect(errorOf('author')).toContain("Saisissez l'auteur");
      expect(errorOf('copies')).toContain("Saisissez le nombre d'exemplaires");
    });

    it('marque les champs invalides et relie l’erreur par aria-describedby', async () => {
      await submit();

      expect(field('isbn').getAttribute('aria-invalid')).toBe('true');
      expect(field('isbn').getAttribute('aria-describedby')).toBe('book-isbn-help book-isbn-error');
    });

    it('place le focus sur le premier champ en erreur', async () => {
      type('isbn', '9782070368228');
      await submit();
      await settle();

      expect(document.activeElement).toBe(field('title'));
    });

    it('refuse un ISBN qui n’a pas 13 chiffres', async () => {
      fillValid();
      type('isbn', '12345');
      await submit();

      expect(saved).toEqual([]);
      expect(errorOf('isbn')).toContain('13 chiffres');
    });

    it('refuse un titre de plus de 255 caractères', async () => {
      fillValid();
      type('title', 'a'.repeat(256));
      await submit();

      expect(saved).toEqual([]);
      expect(errorOf('title')).toContain('255');
    });

    it('refuse un auteur de plus de 255 caractères', async () => {
      fillValid();
      type('author', 'a'.repeat(256));
      await submit();

      expect(saved).toEqual([]);
      expect(errorOf('author')).toContain('255');
    });

    it.each([0, 1001, -3])('refuse %i exemplaires', async (copies) => {
      fillValid();
      setCopies(copies);
      await submit();

      expect(saved).toEqual([]);
      expect(errorOf('copies')).toContain('entre 1 et 1000');
    });

    it('refuse un titre blanc', async () => {
      fillValid();
      type('title', '   ');
      await submit();

      expect(saved).toEqual([]);
      expect(errorOf('title')).toContain('Saisissez le titre');
    });

    it('n’envoie jamais moins que le backend accepterait : ISBN avec tirets accepté', async () => {
      fillValid();
      await submit();

      expect(errorOf('isbn')).toBeNull();
      expect(saved).toHaveLength(1);
    });
  });

  describe('envoi', () => {
    it('émet isbn, title, author et copies tels que saisis', async () => {
      fillValid();
      await submit();

      expect(saved).toEqual([
        { isbn: '978-2-07-036822-8', title: "L'Étranger", author: 'Albert Camus', copies: 3 },
      ]);
    });

    it('valide aussi par la touche Entrée dans un champ', async () => {
      fillValid();

      field('title').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await settle();

      expect(saved).toHaveLength(1);
    });

    it('n’émet rien et verrouille les champs et les boutons pendant l’envoi', async () => {
      fillValid();
      fixture.componentRef.setInput('submitting', true);
      await settle();

      button('Enregistrer').click();
      field('title').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      await settle();

      expect(saved).toEqual([]);
      expect(button('Enregistrer').disabled).toBe(true);
      expect(button('Annuler').disabled).toBe(true);
      expect(field('isbn').readOnly).toBe(true);
    });

    it('ne se ferme pas pendant l’envoi', async () => {
      fixture.componentRef.setInput('submitting', true);
      await settle();

      fixture.componentInstance['close']();

      expect(fixture.componentInstance.visible()).toBe(true);
    });
  });

  describe('fermeture et réinitialisation', () => {
    it('ferme le dialogue avec « Annuler »', async () => {
      button('Annuler').click();
      await settle();

      expect(fixture.componentInstance.visible()).toBe(false);
    });

    it('réinitialise le formulaire et les erreurs à chaque ouverture', async () => {
      fillValid();
      await submit();
      await serverError({ status: 409, code: 'ISBN_ALREADY_EXISTS' });
      fixture.componentRef.setInput('visible', false);
      await settle();

      await open();

      expect(field('isbn').value).toBe('');
      expect(field('title').value).toBe('');
      expect(document.querySelectorAll('[id$="-error"]')).toHaveLength(0);
    });
  });

  describe('erreurs du serveur', () => {
    const validation: ProblemDetail = {
      status: 400,
      code: 'VALIDATION_FAILED',
      errors: [
        { field: 'isbn', code: 'INVALID_FORMAT', message: "Le champ 'isbn' a un format invalide" },
        {
          field: 'copies',
          code: 'OUT_OF_RANGE',
          message: "Le champ 'copies' est hors des bornes autorisées",
        },
      ],
    };

    it('affiche chaque erreur sous le champ correspondant, formulaire ouvert et valeurs conservées', async () => {
      fillValid();

      await serverError(validation);

      expect(errorOf('isbn')).toBe("Le champ 'isbn' a un format invalide");
      expect(errorOf('copies')).toBe("Le champ 'copies' est hors des bornes autorisées");
      expect(errorOf('title')).toBeNull();
      expect(field('isbn').getAttribute('aria-invalid')).toBe('true');
      expect(fixture.componentInstance.visible()).toBe(true);
      expect(field('isbn').value).toBe('978-2-07-036822-8');
      expect(field('title').value).toBe("L'Étranger");
    });

    it('place le focus sur le premier champ en erreur', async () => {
      fillValid();

      await serverError(validation);
      await settle();

      expect(document.activeElement).toBe(field('isbn'));
    });

    it('efface l’erreur d’un champ dès qu’il est modifié', async () => {
      fillValid();
      await serverError(validation);
      expect(errorOf('isbn')).not.toBeNull();

      type('isbn', '9782070368228');
      await settle();

      expect(errorOf('isbn')).toBeNull();
      expect(field('isbn').getAttribute('aria-invalid')).toBeNull();
      expect(errorOf('copies')).not.toBeNull();
    });

    it('409 ISBN_ALREADY_EXISTS : erreur sous le champ ISBN avec le détail du serveur', async () => {
      fillValid();

      await serverError({
        status: 409,
        code: 'ISBN_ALREADY_EXISTS',
        detail: 'Un livre avec cet ISBN existe déjà',
      });

      expect(errorOf('isbn')).toBe('Un livre avec cet ISBN existe déjà');
      expect(fixture.componentInstance.visible()).toBe(true);
      expect(field('title').value).toBe("L'Étranger");
    });

    it('409 sans détail : texte fixe « Cet ISBN existe déjà au catalogue »', async () => {
      fillValid();

      await serverError({ status: 409, code: 'ISBN_ALREADY_EXISTS' });

      expect(errorOf('isbn')).toBe('Cet ISBN existe déjà au catalogue');
    });

    it('erreur sur un champ inconnu : message général dans le formulaire', async () => {
      fillValid();

      await serverError({
        status: 400,
        code: 'VALIDATION_FAILED',
        errors: [
          { field: 'publisher', code: 'REQUIRED', message: "Le champ 'publisher' est obligatoire" },
        ],
      });

      const general = document.querySelector('p-message[role="alert"]');
      expect(general?.textContent).toContain("Le livre n'a pas pu être enregistré.");
      expect(general?.textContent).toContain("Le champ 'publisher' est obligatoire");
      expect(document.querySelectorAll('[id$="-error"]')).toHaveLength(0);
    });

    it('400 sans errors[] (JSON malformé) : message général de repli', async () => {
      fillValid();

      await serverError({ status: 400, code: 'VALIDATION_FAILED', errors: [] });

      const general = document.querySelector('p-message[role="alert"]');
      expect(general?.textContent).toContain('Vérifiez les champs');
    });

    it('champs connus et inconnus : erreurs sous les champs et message général', async () => {
      fillValid();

      await serverError({
        status: 400,
        code: 'VALIDATION_FAILED',
        errors: [
          { field: 'title', code: 'TOO_LONG', message: 'Titre trop long' },
          { field: 'zzz', code: 'X', message: 'Champ étranger' },
        ],
      });

      expect(errorOf('title')).toBe('Titre trop long');
      expect(document.querySelector('p-message[role="alert"]')?.textContent).toContain(
        'Champ étranger',
      );
    });

    it('efface le message général à la prochaine soumission', async () => {
      fillValid();
      await serverError({ status: 400, code: 'VALIDATION_FAILED', errors: [] });
      expect(document.querySelector('p-message[role="alert"]')).not.toBeNull();

      await submit();

      expect(document.querySelector('p-message[role="alert"]')).toBeNull();
    });
  });
});
