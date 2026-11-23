import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';

import { SKIP_ERROR_TOAST } from './error-toast.context';
import { isProblemDetail } from './problem-detail';

const TECHNICAL_ERROR = 'Une erreur technique est survenue. Réessayez plus tard.';
const FORBIDDEN = "Vous n'avez pas les droits nécessaires";

/**
 * Traduit les erreurs HTTP en notifications. Le 401 (ProblemDetail `UNAUTHENTICATED`, ou `INVALID_CREDENTIALS`
 * affiché dans le formulaire de connexion) est laissé à `authInterceptor` / à la page : aucune notification ici.
 * Le 403 (`ACCESS_DENIED`) affiche un message fixe, sans lire le corps. Placé après `authInterceptor`.
 * L'erreur est toujours propagée : l'appelant peut reporter les erreurs par champ sur son formulaire.
 * Le contexte `SKIP_ERROR_TOAST` supprime la notification pour certains statuts (sauf 0, 403 et 5xx).
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const messages = inject(MessageService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status !== 401 && !isSkipped(req, error)) {
        messages.add({ severity: 'error', ...toMessage(error) });
      }
      return throwError(() => error);
    }),
  );
};

/** Seuls les statuts « métier » (400, 409…) peuvent être pris en charge par l'appelant ; jamais 0, 403 ni 5xx. */
function isSkipped(req: HttpRequest<unknown>, error: HttpErrorResponse): boolean {
  const technical = error.status === 0 || error.status === 403 || error.status >= 500;
  return !technical && req.context.get(SKIP_ERROR_TOAST).has(error.status);
}

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
