import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';

import { isProblemDetail } from './problem-detail';

const TECHNICAL_ERROR = 'Une erreur technique est survenue. Réessayez plus tard.';
const FORBIDDEN = "Vous n'avez pas les droits nécessaires";

/**
 * Traduit les erreurs HTTP en notifications. Le 401 est laissé à l'intercepteur d'authentification.
 * L'erreur est toujours propagée : l'appelant peut reporter les erreurs par champ sur son formulaire.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const messages = inject(MessageService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status !== 401) {
        messages.add({ severity: 'error', ...toMessage(error) });
      }
      return throwError(() => error);
    }),
  );
};

function toMessage(error: HttpErrorResponse): { summary: string; detail?: string } {
  if (error.status === 0 || error.status >= 500) {
    return { summary: 'Erreur', detail: TECHNICAL_ERROR };
  }
  if (error.status === 403) {
    return { summary: 'Accès refusé', detail: FORBIDDEN };
  }
  if (isProblemDetail(error.error)) {
    return { summary: error.error.title ?? 'Erreur', detail: error.error.detail };
  }
  return { summary: 'Erreur', detail: TECHNICAL_ERROR };
}
