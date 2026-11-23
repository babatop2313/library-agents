import { HTTP_INTERCEPTORS } from '@angular/common/http';
import { Provider } from '@angular/core';

import { MockApiInterceptor } from './mock-api.interceptor';

/** Configuration `mock` uniquement (fileReplacements) : enregistre l'intercepteur des données simulées. */
export const mockProviders: Provider[] = [
  { provide: HTTP_INTERCEPTORS, useClass: MockApiInterceptor, multi: true },
];
