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
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { SkeletonModule } from 'primeng/skeleton';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { map } from 'rxjs';

import { formatIsoDate } from '../../../../shared/format/iso-date';
import { CurrentLoan, MAX_ACTIVE_LOANS } from '../../data/loans.model';

/**
 * Rendu de la liste :
 * - `skeleton` : premier chargement ; `reloading` : voile sur les lignes précédentes ;
 * - `ready` : liste (ou état vide) ; `error` / `forbidden` : échec du chargement.
 */
export type LoanTableStatus = 'skeleton' | 'reloading' | 'ready' | 'error' | 'forbidden';

@Component({
  selector: 'app-loan-table',
  imports: [TableModule, ButtonModule, SkeletonModule, TagModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './loan-table.html',
  styleUrl: './loan-table.scss',
})
export class LoanTable {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly observer = inject(BreakpointObserver);

  readonly loans = input.required<CurrentLoan[]>();
  readonly status = input.required<LoanTableStatus>();
  /** Identifiant de l'emprunt en cours de retour : désactive les autres boutons. */
  readonly returningId = input<string | null>(null);
  readonly retrying = input(false);

  readonly giveBack = output<CurrentLoan>();
  readonly retry = output<void>();

  protected readonly formatDate = formatIsoDate;

  private readonly isLarge = toSignal(
    this.observer.observe('(min-width: 992px)').pipe(map((state) => state.matches)),
    { initialValue: true },
  );
  private readonly isMedium = toSignal(
    this.observer.observe('(min-width: 768px)').pipe(map((state) => state.matches)),
    { initialValue: true },
  );

  /** Nombre de colonnes visibles, pour que les états vide et erreur occupent toute la largeur. */
  protected readonly colspan = computed(() => (this.isLarge() ? 7 : this.isMedium() ? 4 : 2));

  protected readonly rows = computed<(CurrentLoan | null)[]>(() =>
    this.status() === 'skeleton'
      ? Array.from({ length: MAX_ACTIVE_LOANS }, () => null)
      : this.loans(),
  );

  protected ariaLabel(loan: CurrentLoan): string {
    return loan.title === null
      ? `Rendre le livre indisponible emprunté le ${formatIsoDate(loan.borrowedOn)}`
      : `Rendre ${loan.title}`;
  }

  /** Redonne le focus au bouton « Rendre » d'une ligne (annulation de la confirmation). */
  focusGiveBack(loanId: string): void {
    const host = Array.from(
      this.host.nativeElement.querySelectorAll<HTMLElement>('p-button[data-loan-id]'),
    ).find((element) => element.dataset['loanId'] === loanId);
    host?.querySelector('button')?.focus();
  }
}
