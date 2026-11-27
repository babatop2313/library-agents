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
import { ConfirmationService, MessageService } from 'primeng/api';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { MessageModule } from 'primeng/message';
import { catchError, map, Observable, of, startWith, Subject, switchMap, tap } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { formatIsoDate } from '../../../../shared/format/iso-date';
import { CurrentLoan, MAX_ACTIVE_LOANS } from '../../data/loans.model';
import { LoansService } from '../../data/loans.service';
import { returnErrorMessage } from '../../data/return-errors';
import { LoanTable, LoanTableStatus } from '../../ui/loan-table/loan-table';

/** Cause de l'échec du dernier chargement. */
type Failure = 'error' | 'forbidden';

type View =
  | { kind: 'loading'; previous: CurrentLoan[] | null; after: Failure | null }
  | { kind: 'loaded'; loans: CurrentLoan[] }
  | { kind: 'error'; failure: Failure };

const INITIAL_VIEW: View = { kind: 'loading', previous: null, after: null };

@Component({
  selector: 'app-my-loans',
  imports: [ConfirmDialogModule, MessageModule, LoanTable],
  providers: [ConfirmationService],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-loans.html',
  styleUrl: './my-loans.scss',
})
export class MyLoans {
  private readonly service = inject(LoansService);
  private readonly messages = inject(MessageService);
  private readonly confirmation = inject(ConfirmationService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly reload$ = new Subject<void>();
  private readonly title = viewChild<ElementRef<HTMLElement>>('title');
  private readonly table = viewChild(LoanTable);
  private lastLoaded: CurrentLoan[] | null = null;
  private lastFailure: Failure | null = null;
  private focusTitleOnLoad = false;

  protected readonly view = toSignal(this.load(), { initialValue: INITIAL_VIEW });
  /** Emprunt en cours de retour : un seul envoi à la fois. */
  protected readonly returningId = signal<string | null>(null);

  protected readonly max = MAX_ACTIVE_LOANS;

  protected readonly tableStatus = computed<LoanTableStatus>(() => {
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
  /** Emprunts affichés : ceux de la liste chargée, ou les précédents pendant un rechargement. */
  protected readonly loans = computed<CurrentLoan[]>(() => {
    const view = this.view();
    if (view.kind === 'loaded') {
      return view.loans;
    }
    return view.kind === 'loading' ? (view.previous ?? []) : [];
  });
  protected readonly failed = computed(() => {
    const status = this.tableStatus();
    return status === 'error' || status === 'forbidden';
  });
  protected readonly isLoadingFirst = computed(() => this.tableStatus() === 'skeleton');
  protected readonly count = computed(() => this.loans().length);
  protected readonly limitReached = computed(() => this.count() >= this.max);
  protected readonly hasOverdue = computed(() => this.loans().some((loan) => loan.overdue));

  /** Compteur lu par les lecteurs d'écran (le texte visible est masqué pour eux). */
  protected readonly spokenCount = computed(() => {
    const n = this.count();
    return `${n} ${n > 1 ? 'emprunts' : 'emprunt'} en cours sur ${this.max} autorisés`;
  });

  protected retry(): void {
    this.reload$.next();
  }

  /** Demande confirmation (focus initial sur « Annuler »), puis rend l'emprunt. */
  protected askGiveBack(loan: CurrentLoan): void {
    if (this.returningId() !== null) {
      return;
    }
    const subject =
      loan.title === null
        ? `de ce livre, emprunté le ${formatIsoDate(loan.borrowedOn)}`
        : `de « ${loan.title} »`;
    this.confirmation.confirm({
      header: 'Rendre ce livre ?',
      message: `Confirmez-vous le retour ${subject} ?`,
      acceptLabel: 'Rendre',
      rejectLabel: 'Annuler',
      acceptButtonProps: { severity: 'primary' },
      rejectButtonProps: { severity: 'secondary', text: true },
      defaultFocus: 'reject',
      accept: () => this.giveBack(loan),
      reject: () => this.focusRow(loan.id),
    });
  }

  private giveBack(loan: CurrentLoan): void {
    if (this.returningId() !== null) {
      return;
    }
    this.returningId.set(loan.id);
    this.service
      .giveBack(loan.id)
      .pipe(
        finalize(() => this.returningId.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.messages.add({ severity: 'success', summary: 'Livre rendu', life: 3000 });
          this.focusTitleOnLoad = true;
          this.reload$.next();
        },
        error: (error: unknown) => {
          const message = returnErrorMessage(error);
          if (message === null) {
            // 403, 5xx, réseau : notification de l'intercepteur.
            this.focusRow(loan.id);
            return;
          }
          this.messages.add({ severity: 'warn', summary: message, life: 5000 });
          if (error instanceof HttpErrorResponse && error.status === 503) {
            // Rien n'a été modifié : la ligne reste en place, sans rechargement.
            this.focusRow(loan.id);
            return;
          }
          this.focusTitleOnLoad = true;
          this.reload$.next();
        },
      });
  }

  private load(): Observable<View> {
    return this.reload$.pipe(
      startWith(undefined),
      switchMap(() =>
        this.service.listMine().pipe(
          map((loans): View => {
            this.lastLoaded = loans;
            this.lastFailure = null;
            return { kind: 'loaded', loans };
          }),
          tap(() => this.restoreFocus()),
          catchError((error: unknown) => {
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

  /** Après un retour, la ligne a disparu : le focus va au titre de la page. */
  private restoreFocus(): void {
    if (this.focusTitleOnLoad) {
      this.focusTitleOnLoad = false;
      setTimeout(() => this.title()?.nativeElement.focus());
    }
  }

  private focusRow(loanId: string): void {
    setTimeout(() => this.table()?.focusGiveBack(loanId));
  }
}
