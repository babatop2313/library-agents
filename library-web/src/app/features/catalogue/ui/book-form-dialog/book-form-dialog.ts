import { DOCUMENT } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { AutoFocusModule } from 'primeng/autofocus';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';

import { ProblemDetail } from '../../../../core/http/problem-detail';
import { RegisterBookRequest } from '../../data/catalogue.model';
import {
  COPIES_MAX,
  COPIES_MIN,
  copiesRange,
  isbnFormat,
  maxTrimmedLength,
  notBlank,
  TEXT_MAX_LENGTH,
} from './book-form-validators';

type FieldName = 'isbn' | 'title' | 'author' | 'copies';

const FIELD_ORDER: readonly FieldName[] = ['isbn', 'title', 'author', 'copies'];

const DUPLICATE_ISBN = 'Cet ISBN existe déjà au catalogue';
const GENERAL_TITLE = "Le livre n'a pas pu être enregistré.";
const GENERAL_FALLBACK = "La demande n'a pas pu être traitée. Vérifiez les champs, puis réessayez.";

/** Messages d'erreur client, par champ et par clé d'erreur. */
const MESSAGES: Record<FieldName, Record<string, string>> = {
  isbn: {
    required: "Saisissez l'ISBN du livre.",
    isbnFormat: "L'ISBN doit comporter 13 chiffres (tirets et espaces acceptés).",
  },
  title: {
    required: 'Saisissez le titre du livre.',
    tooLong: 'Le titre ne doit pas dépasser 255 caractères.',
  },
  author: {
    required: "Saisissez l'auteur du livre.",
    tooLong: "Le nom de l'auteur ne doit pas dépasser 255 caractères.",
  },
  copies: {
    required: "Saisissez le nombre d'exemplaires.",
    copiesRange: "Le nombre d'exemplaires doit être compris entre 1 et 1000.",
  },
};

/** Dialogue d'enregistrement d'un livre : formulaire réactif, erreurs client et erreurs du serveur. */
@Component({
  selector: 'app-book-form-dialog',
  imports: [
    ReactiveFormsModule,
    AutoFocusModule,
    ButtonModule,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    MessageModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './book-form-dialog.html',
  styleUrl: './book-form-dialog.scss',
})
export class BookFormDialog {
  readonly visible = model(false);
  /** Envoi en cours : verrouille le formulaire et la fermeture. */
  readonly submitting = input(false);
  /** Erreur 400 / 409 renvoyée par le serveur, à reporter sur le formulaire. */
  readonly serverError = input<ProblemDetail | null>(null);
  readonly save = output<RegisterBookRequest>();

  protected readonly copiesMin = COPIES_MIN;
  protected readonly copiesMax = COPIES_MAX;
  protected readonly generalMessages = signal<string[]>([]);
  protected readonly general = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    isbn: ['', [notBlank, isbnFormat]],
    title: ['', [notBlank, maxTrimmedLength(TEXT_MAX_LENGTH)]],
    author: ['', [notBlank, maxTrimmedLength(TEXT_MAX_LENGTH)]],
    copies: [null as number | null, [copiesRange]],
  });

  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly generalElement = viewChild('generalMessage', { read: ElementRef });
  private readonly saveElement = viewChild('saveButton', { read: ElementRef });
  private wasSubmitting = false;

  constructor() {
    // Réinitialisation à chaque ouverture.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.form.reset();
          this.general.set(false);
          this.generalMessages.set([]);
        });
      }
    });

    effect(() => {
      const error = this.serverError();
      if (error !== null) {
        untracked(() => this.applyServerError(error));
      }
    });

    // Fin d'envoi sans erreur reportée sur le formulaire (403, 5xx, réseau) : le focus revient sur « Enregistrer ».
    effect(() => {
      const submitting = this.submitting();
      if (this.wasSubmitting && !submitting) {
        afterNextRender(
          () => {
            if (this.visible() && this.serverError() === null) {
              this.saveElement()?.nativeElement.querySelector('button')?.focus();
            }
          },
          { injector: this.injector },
        );
      }
      this.wasSubmitting = submitting;
    });
  }

  /** Message à afficher sous le champ, ou `null` (erreur visible après passage dans le champ ou soumission). */
  protected error(name: FieldName): string | null {
    const control = this.form.controls[name];
    if (!control.touched || control.valid) {
      return null;
    }
    for (const key of Object.keys(control.errors ?? {})) {
      const message = MESSAGES[name][key];
      if (message !== undefined) {
        return message;
      }
    }
    const server: unknown = control.getError('server');
    return typeof server === 'string' ? server : null;
  }

  protected describedBy(name: FieldName): string {
    return this.error(name) === null ? `book-${name}-help` : `book-${name}-help book-${name}-error`;
  }

  protected close(): void {
    if (!this.submitting()) {
      this.visible.set(false);
    }
  }

  protected onEnter(event: Event): void {
    if ((event.target as HTMLElement).tagName === 'INPUT') {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    this.general.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.changeDetector.markForCheck();
      this.focusFirstInvalid();
      return;
    }
    const { isbn, title, author, copies } = this.form.getRawValue();
    if (copies === null) {
      return;
    }
    this.save.emit({ isbn, title, author, copies });
  }

  private applyServerError(error: ProblemDetail): void {
    const unmatched: string[] = [];

    if (error.status === 409 || error.code === 'ISBN_ALREADY_EXISTS') {
      this.setServerError('isbn', error.detail ?? DUPLICATE_ISBN);
    } else {
      for (const fieldError of error.errors ?? []) {
        const name = FIELD_ORDER.find((field) => field === fieldError.field);
        if (name === undefined) {
          unmatched.push(fieldError.message);
        } else {
          this.setServerError(name, fieldError.message);
        }
      }
      const attached = FIELD_ORDER.some((name) => this.form.controls[name].hasError('server'));
      if (!attached) {
        this.generalMessages.set(unmatched.length > 0 ? unmatched : [GENERAL_FALLBACK]);
        this.general.set(true);
      } else if (unmatched.length > 0) {
        this.generalMessages.set(unmatched);
        this.general.set(true);
      }
    }
    this.changeDetector.markForCheck();
    afterNextRender(
      () => {
        if (this.general()) {
          this.generalElement()?.nativeElement.focus();
        } else {
          this.focusFirstInvalid();
        }
      },
      { injector: this.injector },
    );
  }

  private setServerError(name: FieldName, message: string): void {
    const control = this.form.controls[name];
    control.setErrors({ server: message });
    control.markAsTouched();
  }

  private focusFirstInvalid(): void {
    afterNextRender(
      () => {
        const first = FIELD_ORDER.find((name) => this.form.controls[name].invalid);
        if (first !== undefined) {
          this.document.getElementById(`book-${first}`)?.focus();
        }
      },
      { injector: this.injector },
    );
  }

  protected readonly generalTitle = GENERAL_TITLE;
}
