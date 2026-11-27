import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import {
  catchError,
  combineLatest,
  distinctUntilChanged,
  map,
  Observable,
  of,
  startWith,
  Subject,
  switchMap,
} from 'rxjs';
import { finalize } from 'rxjs/operators';

import { AuthService } from '../../../../core/auth/auth.service';
import { Page } from '../../../../core/http/page';
import { isProblemDetail, ProblemDetail } from '../../../../core/http/problem-detail';
import { formatIsoDate } from '../../../../shared/format/iso-date';
import { borrowFailure } from '../../data/borrow-errors';
import { Book, RegisterBookRequest } from '../../data/catalogue.model';
import { CatalogueService } from '../../data/catalogue.service';
import { BookFormDialog } from '../../ui/book-form-dialog/book-form-dialog';
import { BookSearch } from '../../ui/book-search/book-search';
import { BookPageChange, BookTable, BookTableStatus } from '../../ui/book-table/book-table';
import {
  CatalogueUrlState,
  parseCatalogueUrl,
  toApiParams,
  toQueryParams,
} from './catalogue-url-state';

/** Cause de l'échec du dernier chargement. */
type Failure = 'error' | 'forbidden';

type View =
  | { kind: 'loading'; previous: Page<Book> | null; after: Failure | null }
  | { kind: 'loaded'; data: Page<Book> }
  | { kind: 'error'; failure: Failure };

const INITIAL_VIEW: View = { kind: 'loading', previous: null, after: null };

function sameState(a: CatalogueUrlState, b: CatalogueUrlState): boolean {
  return a.page === b.page && a.size === b.size && a.q === b.q;
}

@Component({
  selector: 'app-catalogue',
  imports: [ButtonModule, BookSearch, BookTable, BookFormDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './catalogue.html',
  styleUrl: './catalogue.scss',
})
export class Catalogue {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly service = inject(CatalogueService);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly auth = inject(AuthService);

  private readonly reload$ = new Subject<void>();
  private readonly search = viewChild(BookSearch);
  private readonly addButton = viewChild('addButton', { read: ElementRef });
  private readonly table = viewChild(BookTable);
  /** Action à jouer une fois la liste rechargée (focus après un emprunt). */
  private afterReload: (() => void) | null = null;
  private lastLoaded: Page<Book> | null = null;
  private lastFailure: Failure | null = null;

  /** État de la liste, porté par l'URL (`page` base 1, `size`, `q`). */
  private readonly state$ = this.route.queryParamMap.pipe(
    map(parseCatalogueUrl),
    distinctUntilChanged(sameState),
  );
  protected readonly state = toSignal(this.state$, { requireSync: true });

  protected readonly view = toSignal(this.load(), { initialValue: INITIAL_VIEW });

  protected readonly canRegister = computed(() => this.auth.hasRole('LIBRARIAN'));
  /** Le bouton « Emprunter » est réservé au rôle `MEMBER` (aussi en cumul avec `LIBRARIAN`). */
  protected readonly canBorrow = computed(() => this.auth.hasRole('MEMBER'));
  /** Livre en cours d'emprunt : un seul envoi à la fois (double clic = une requête). */
  protected readonly borrowingId = signal<string | null>(null);
  protected readonly registerOpen = signal(false);
  protected readonly submitting = signal(false);
  protected readonly serverError = signal<ProblemDetail | null>(null);

  protected readonly tableStatus = computed<BookTableStatus>(() => {
    const view = this.view();
    switch (view.kind) {
      case 'loaded':
        return 'ready';
      case 'error':
        return view.failure;
      case 'loading':
        if (view.after !== null) {
          return view.after;
        }
        return view.previous === null ? 'skeleton' : 'reloading';
    }
  });
  protected readonly retrying = computed(() => {
    const view = this.view();
    return view.kind === 'loading' && view.after !== null;
  });
  /** Données affichées : celles de la page chargée, ou les précédentes pendant un rechargement. */
  protected readonly shown = computed<Page<Book> | null>(() => {
    const view = this.view();
    if (view.kind === 'loaded') {
      return view.data;
    }
    return view.kind === 'loading' ? view.previous : null;
  });
  protected readonly books = computed(() => this.shown()?.content ?? []);
  protected readonly totalElements = computed(() => this.shown()?.totalElements ?? 0);

  /** Compteur annoncé (`aria-live`) ; vide quand il n'y a rien à annoncer. */
  protected readonly counter = computed(() => {
    const view = this.view();
    if (view.kind === 'error' || (view.kind === 'loading' && view.after !== null)) {
      return '';
    }
    if (this.shown() === null) {
      return 'Chargement du catalogue…';
    }
    const total = this.totalElements();
    const query = this.state().q;
    if (query === '') {
      if (total === 0) {
        return '';
      }
      return total === 1 ? '1 livre disponible' : `${total} livres disponibles`;
    }
    if (total === 0) {
      return `Aucun livre ne correspond à "${query}"`;
    }
    return total === 1 ? `1 résultat pour "${query}"` : `${total} résultats pour "${query}"`;
  });

  protected onSearch(q: string): void {
    this.navigate({ ...this.state(), q, page: 1 });
  }

  protected changePage(change: BookPageChange): void {
    this.navigate({ ...this.state(), page: change.page, size: change.size });
  }

  protected retry(): void {
    this.reload$.next();
  }

  protected clearSearch(): void {
    this.search()?.clear();
  }

  protected borrow(book: Book): void {
    if (this.borrowingId() !== null) {
      return;
    }
    this.borrowingId.set(book.id);
    this.service
      .borrow(book.id)
      .pipe(
        finalize(() => this.borrowingId.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (loan) => {
          this.messages.add({
            severity: 'success',
            summary: 'Livre emprunté',
            detail: `À rendre au plus tard le ${formatIsoDate(loan.dueOn)}.`,
            life: 3000,
          });
          this.focusAfterBorrow(book.id);
          this.reload$.next();
        },
        error: (error: unknown) => {
          const failure = borrowFailure(error);
          if (failure === null) {
            // 403, 5xx, réseau : notification de l'intercepteur.
            return;
          }
          this.messages.add({ severity: 'warn', summary: failure.message, life: 5000 });
          if (failure.reload) {
            this.focusAfterBorrow(book.id);
            this.reload$.next();
          }
        },
      });
  }

  protected openRegister(): void {
    this.serverError.set(null);
    this.registerOpen.set(true);
  }

  protected onRegisterVisibleChange(visible: boolean): void {
    this.registerOpen.set(visible);
    if (!visible) {
      this.focusAddButton();
    }
  }

  protected register(body: RegisterBookRequest): void {
    if (this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.serverError.set(null);
    this.service
      .register(body)
      .pipe(
        finalize(() => this.submitting.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (book) => {
          this.messages.add({
            severity: 'success',
            summary: 'Livre enregistré',
            detail: `« ${book.title} » a été ajouté au catalogue.`,
            life: 3000,
          });
          this.registerOpen.set(false);
          this.reload$.next();
          this.focusAddButton();
        },
        error: (error: unknown) => {
          if (
            error instanceof HttpErrorResponse &&
            (error.status === 400 || error.status === 409)
          ) {
            this.serverError.set(
              isProblemDetail(error.error)
                ? { status: error.status, ...error.error }
                : { status: error.status },
            );
          }
          // 403, 5xx, réseau : notification de l'intercepteur ; le dialogue reste ouvert.
        },
      });
  }

  private load(): Observable<View> {
    return combineLatest([this.state$, this.reload$.pipe(startWith(undefined))]).pipe(
      switchMap(([state]) =>
        this.service.search(toApiParams(state)).pipe(
          map((data): View => this.onLoaded(state, data)),
          catchError((error: unknown) => {
            this.afterReload = null;
            this.lastLoaded = null;
            this.lastFailure =
              error instanceof HttpErrorResponse && error.status === 403 ? 'forbidden' : 'error';
            return of<View>({ kind: 'error', failure: this.lastFailure });
          }),
          startWith<View>({
            kind: 'loading',
            previous: this.lastLoaded,
            after: this.lastFailure,
          }),
        ),
      ),
    );
  }

  /** Page demandée au-delà de la dernière : redirection vers la dernière page (sans empiler l'historique). */
  private onLoaded(state: CatalogueUrlState, data: Page<Book>): View {
    if (data.content.length === 0 && data.totalPages > 0 && state.page > data.totalPages) {
      this.navigate({ ...state, page: data.totalPages }, true);
      return { kind: 'loading', previous: this.lastLoaded, after: null };
    }
    this.lastLoaded = data;
    this.lastFailure = null;
    const after = this.afterReload;
    this.afterReload = null;
    if (after !== null) {
      setTimeout(after);
    }
    return { kind: 'loaded', data };
  }

  private navigate(state: CatalogueUrlState, replaceUrl = false): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: toQueryParams(state),
      replaceUrl,
    });
  }

  /** Focus sur le bouton de la même ligne s'il existe encore après le rechargement, sinon sur la recherche. */
  private focusAfterBorrow(bookId: string): void {
    this.afterReload = () => {
      if (!this.table()?.focusBorrow(bookId)) {
        this.search()?.focus();
      }
    };
  }

  private focusAddButton(): void {
    setTimeout(() => this.addButton()?.nativeElement.querySelector('button')?.focus());
  }
}
