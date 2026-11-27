import {
  provideHttpClient,
  withInterceptors,
  withInterceptorsFromDi,
  withXsrfConfiguration,
} from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';
import { MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';

import { routes } from './app.routes';
import { provideAuthInitializer } from './core/auth/auth.initializer';
import { authInterceptor } from './core/auth/auth.interceptor';
import { errorInterceptor } from './core/http/error.interceptor';
import { mockProviders } from './core/mock/mock.providers';

/** Design system : palette primaire violette (contraste AA du texte blanc sur `primary.600`), focus de 2 px. */
const LibraryPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{violet.50}',
      100: '{violet.100}',
      200: '{violet.200}',
      300: '{violet.300}',
      400: '{violet.400}',
      500: '{violet.500}',
      600: '{violet.600}',
      700: '{violet.700}',
      800: '{violet.800}',
      900: '{violet.900}',
      950: '{violet.950}',
    },
    focusRing: { width: '2px' },
    colorScheme: {
      light: {
        primary: {
          color: '{primary.600}',
          contrastColor: '#ffffff',
          hoverColor: '{primary.700}',
          activeColor: '{primary.800}',
        },
        highlight: {
          background: '{primary.50}',
          focusBackground: '{primary.100}',
          color: '{primary.700}',
          focusColor: '{primary.800}',
        },
      },
    },
  },
  // Contraste AA (design system, 2026-11-26) : le texte `warn` / `success` d'Aura est sous 4,5:1. Fonds et bordures inchangés.
  // Jetons de la version installée de @primeuix/themes : `message.<sévérité>.color`, `toast.<sévérité>.color` / `detailColor`.
  components: {
    message: {
      warn: { color: 'light-dark({yellow.800}, {yellow.500})' },
      success: { color: 'light-dark({green.800}, {green.500})' },
    },
    toast: {
      warn: {
        color: 'light-dark({yellow.800}, {yellow.500})',
        detailColor: 'light-dark({yellow.800}, {yellow.500})',
      },
      success: {
        color: 'light-dark({green.800}, {green.500})',
        detailColor: 'light-dark({green.800}, {green.500})',
      },
    },
  },
});

/** Libellés d'accessibilité en français (paginateur, boutons des champs numériques). */
const FRENCH_TRANSLATION = {
  aria: {
    close: 'Fermer',
    pageLabel: 'Page {page}',
    firstPageLabel: 'Première page',
    prevPageLabel: 'Page précédente',
    nextPageLabel: 'Page suivante',
    lastPageLabel: 'Dernière page',
    rowsPerPageLabel: 'Lignes par page',
  },
};

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(
      withInterceptors([authInterceptor, errorInterceptor]),
      // Après les intercepteurs fonctionnels : l'intercepteur simulé (configuration mock) répond en dernier.
      withInterceptorsFromDi(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    provideAuthInitializer(),
    providePrimeNG({ theme: { preset: LibraryPreset }, translation: FRENCH_TRANSLATION }),
    MessageService,
    ...mockProviders,
  ],
};
