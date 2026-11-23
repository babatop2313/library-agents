import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Statuts HTTP pour lesquels `errorInterceptor` n'affiche pas de notification : l'appelant reporte
 * lui-même l'erreur (par exemple sur un formulaire). L'erreur reste propagée dans tous les cas.
 */
export const SKIP_ERROR_TOAST = new HttpContextToken<ReadonlySet<number>>(() => new Set<number>());

/** Contexte de requête qui supprime la notification d'erreur pour les statuts donnés. */
export function skipErrorToast(...statuses: number[]): HttpContext {
  return new HttpContext().set(SKIP_ERROR_TOAST, new Set(statuses));
}
