import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';

const EXCLUDED_URLS = ['/api/v1/auth/login', '/api/v1/auth/me'];

/**
 * Sur un 401 (hors connexion et /me) : vide la session, notifie une seule fois et redirige vers la
 * connexion avec `returnUrl`. N'ajoute aucun en-tête (le jeton est dans un cookie HttpOnly) ; le 403 est ignoré.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const messages = inject(MessageService);

  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !EXCLUDED_URLS.some((url) => req.url.startsWith(url)) &&
        auth.expireSession()
      ) {
        messages.add({
          severity: 'warn',
          summary: 'Session expirée',
          detail: 'Session expirée, veuillez vous reconnecter',
        });
        void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      }
      return throwError(() => error);
    }),
  );
};
