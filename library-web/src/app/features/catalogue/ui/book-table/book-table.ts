import { BreakpointObserver } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ButtonModule } from 'primeng/button';
import { PaginatorModule, PaginatorState } from 'primeng/paginator';
import { SkeletonModule } from 'primeng/skeleton';
import { TableModule } from 'primeng/table';
import { map } from 'rxjs';

import { Book } from '../../data/catalogue.model';
import { PAGE_SIZES } from '../../data/catalogue.constants';

/**
 * Rendu de la liste :
 * - `skeleton` : premier chargement ; `reloading` : voile sur les lignes précédentes ;
 * - `ready` : liste (ou état vide / sans résultat) ; `error` / `forbidden` : échec du chargement.
 */
export type BookTableStatus = 'skeleton' | 'reloading' | 'ready' | 'error' | 'forbidden';

export interface BookPageChange {
  /** Page en base 1 (celle de l'URL). */
  page: number;
  size: number;
}

const MAX_SKELETON_ROWS = 10;

@Component({
  selector: 'app-book-table',
  imports: [TableModule, PaginatorModule, ButtonModule, SkeletonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './book-table.html',
  styleUrl: './book-table.scss',
})
export class BookTable {
  readonly books = input.required<Book[]>();
  readonly status = input.required<BookTableStatus>();
  readonly retrying = input(false);
  readonly totalElements = input(0);
  /** Page courante, base 1. */
  readonly page = input(1);
  readonly size = input(10);
  readonly query = input('');
  /** Vrai pour un bibliothécaire : adapte le texte du catalogue vide. */
  readonly canRegister = input(false);
  /** Vrai pour un membre : ajoute la colonne d'action « Emprunter ». */
  readonly canBorrow = input(false);
  /** Livre en cours d'emprunt : désactive les autres boutons. */
  readonly borrowingId = input<string | null>(null);

  readonly pageChange = output<BookPageChange>();
  readonly retry = output<void>();
  readonly clearSearch = output<void>();
  readonly borrow = output<Book>();

  protected readonly pageSizes = [...PAGE_SIZES];
  protected readonly mobile = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 575.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  private readonly isMedium = toSignal(
    inject(BreakpointObserver)
      .observe('(min-width: 768px)')
      .pipe(map((state) => state.matches)),
    { initialValue: true },
  );
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Nombre de colonnes visibles, pour que les états vide et erreur occupent toute la largeur. */
  protected readonly colspan = computed(() => {
    if (!this.canBorrow()) {
      return 4;
    }
    return this.isMedium() ? 5 : 2;
  });

  protected readonly rows = computed<(Book | null)[]>(() =>
    this.status() === 'skeleton'
      ? Array.from({ length: Math.min(this.size(), MAX_SKELETON_ROWS) }, () => null)
      : this.books(),
  );
  protected readonly first = computed(() => (this.page() - 1) * this.size());
  protected readonly showPaginator = computed(
    () => this.totalElements() > 0 && (this.status() === 'ready' || this.status() === 'reloading'),
  );

  protected availability(book: Book): string {
    const label = book.availableCopies === 1 ? 'disponible' : 'disponibles';
    return `${book.availableCopies} ${label} sur ${book.totalCopies}`;
  }

  protected borrowLabel(book: Book): string {
    return `Emprunter ${book.title}`;
  }

  /** Redonne le focus au bouton « Emprunter » d'un livre ; faux si la ligne n'existe plus. */
  focusBorrow(bookId: string): boolean {
    const host = Array.from(
      this.host.nativeElement.querySelectorAll<HTMLElement>('p-button[data-book-id]'),
    ).find((element) => element.dataset['bookId'] === bookId);
    const button = host?.querySelector('button');
    button?.focus();
    return button != null;
  }

  protected onPage(event: PaginatorState): void {
    const size = event.rows ?? this.size();
    const page = size === this.size() ? (event.page ?? 0) + 1 : 1;
    this.pageChange.emit({ page, size });
  }
}
