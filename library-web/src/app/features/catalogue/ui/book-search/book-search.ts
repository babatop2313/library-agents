import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';

import { SEARCH_MAX_LENGTH } from '../../data/catalogue.constants';

const DEBOUNCE_MS = 300;

/** Champ de recherche : émet le texte rogné après 300 ms sans frappe, ou tout de suite sur Entrée. */
@Component({
  selector: 'app-book-search',
  imports: [ButtonModule, IconFieldModule, InputIconModule, InputTextModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './book-search.html',
  styleUrl: './book-search.scss',
})
export class BookSearch {
  /** Texte de recherche courant de l'URL : le champ se resynchronise s'il change de l'extérieur. */
  readonly value = input('');
  readonly searchChange = output<string>();

  protected readonly maxLength = SEARCH_MAX_LENGTH;
  protected readonly text = signal('');

  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');
  private lastEmitted = '';
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cancelTimer());
    effect(() => {
      const external = this.value();
      if (external !== this.lastEmitted) {
        this.lastEmitted = external;
        this.cancelTimer();
        this.text.set(external);
      }
    });
  }

  protected onInput(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
    this.cancelTimer();
    this.timer = setTimeout(() => this.emit(), DEBOUNCE_MS);
  }

  protected submitNow(): void {
    this.cancelTimer();
    this.emit();
  }

  /** Donne le focus au champ de recherche. */
  focus(): void {
    this.field().nativeElement.focus();
  }

  /** Vide le champ, relance sans filtre et rend le focus au champ. */
  clear(): void {
    this.text.set('');
    this.cancelTimer();
    this.emit();
    this.field().nativeElement.focus();
  }

  private emit(): void {
    const trimmed = this.text().trim().slice(0, SEARCH_MAX_LENGTH);
    if (trimmed !== this.lastEmitted) {
      this.lastEmitted = trimmed;
      this.searchChange.emit(trimmed);
    }
  }

  private cancelTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }
}
