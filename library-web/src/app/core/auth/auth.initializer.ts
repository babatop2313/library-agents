import { EnvironmentProviders, inject, provideAppInitializer } from '@angular/core';

import { AuthService } from './auth.service';

/** Restaure la session (GET /api/v1/auth/me) avant le premier routage. */
export function provideAuthInitializer(): EnvironmentProviders {
  return provideAppInitializer(() => inject(AuthService).restoreSession());
}
