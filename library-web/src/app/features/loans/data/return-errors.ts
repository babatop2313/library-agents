import { HttpErrorResponse } from '@angular/common/http';

import { isProblemDetail } from '../../../core/http/problem-detail';

/** Message affiché pour un 503 `LOAN_BUSY` (docs/api/loan.md). */
export const LOAN_BUSY_MESSAGE = 'Le service est momentanément occupé, réessayez';

const MESSAGES_BY_CODE: Record<string, string> = {
  LOAN_ALREADY_RETURNED: 'Cet emprunt est déjà rendu',
  LOAN_NOT_FOUND: 'Emprunt introuvable',
  LOAN_BUSY: LOAN_BUSY_MESSAGE,
};

const MESSAGES_BY_STATUS: Record<number, string> = {
  404: 'Emprunt introuvable',
  409: 'Cet emprunt est déjà rendu',
  503: LOAN_BUSY_MESSAGE,
};

/**
 * Message d'un refus de retour (404, 409, 503), d'après le `code` du ProblemDetail, sinon d'après le statut.
 * `null` pour toute autre erreur : la notification est celle de l'intercepteur.
 */
export function returnErrorMessage(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (isProblemDetail(error.error) && error.error.code && Object.hasOwn(MESSAGES_BY_CODE, error.error.code)) {
    return MESSAGES_BY_CODE[error.error.code];
  }
  return MESSAGES_BY_STATUS[error.status] ?? null;
}
