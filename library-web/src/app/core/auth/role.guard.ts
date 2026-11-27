import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';
import { Role } from './current-user.model';

/**
 * Route réservée à un rôle : sinon redirection vers `fallback`. Masque l'interface ; le backend
 * reste la référence (RM-LOAN-23).
 */
export function roleGuard(role: Role, fallback = '/catalogue'): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    return auth.hasRole(role) ? true : router.createUrlTree([fallback]);
  };
}
