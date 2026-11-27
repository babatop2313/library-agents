import { HttpErrorResponse } from '@angular/common/http';

import { isProblemDetail } from '../../../core/http/problem-detail';

/** Refus d'emprunt à notifier par l'écran (docs/api/loan.md, RM-LOAN-22). */
export interface BorrowFailure {
  message: string;
  /** Vrai si l'état affiché est périmé (stock ou livre changés) : la liste doit être rechargée. */
  reload: boolean;
}

const BY_CODE: Record<string, BorrowFailure> = {
  BOOK_NOT_AVAILABLE: { message: "Ce livre n'est plus disponible", reload: true },
  LOAN_LIMIT_REACHED: {
    message: 'Vous avez atteint la limite de 3 emprunts en cours',
    reload: false,
  },
  MEMBER_HAS_OVERDUE_LOAN: { message: "Rendez d'abord vos livres en retard", reload: false },
  BOOK_ALREADY_BORROWED: { message: 'Vous avez déjà emprunté ce livre', reload: false },
  BOOK_NOT_FOUND: { message: "Ce livre n'existe plus", reload: true },
  LOAN_BUSY: { message: 'Le service est momentanément occupé, réessayez', reload: false },
};

const BY_STATUS: Record<number, BorrowFailure> = {
  404: BY_CODE['BOOK_NOT_FOUND'],
  409: { message: "Cet emprunt n'a pas pu être enregistré", reload: true },
  503: BY_CODE['LOAN_BUSY'],
};

/**
 * Traduit un refus d'emprunt (404, 409, 503) d'après le `code` du ProblemDetail, sinon d'après le statut.
 * `null` pour toute autre erreur (403, 5xx, réseau) : la notification est celle de l'intercepteur.
 */
export function borrowFailure(error: unknown): BorrowFailure | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (isProblemDetail(error.error) && error.error.code && Object.hasOwn(BY_CODE, error.error.code)) {
    return BY_CODE[error.error.code];
  }
  return BY_STATUS[error.status] ?? null;
}
