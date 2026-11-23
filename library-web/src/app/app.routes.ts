import { Routes } from '@angular/router';

import { authGuard, guestGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/pages/login/login').then((m) => m.Login),
  },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./features/home/pages/home').then((m) => m.Home) },
      {
        path: 'catalogue',
        title: 'Catalogue',
        loadChildren: () =>
          import('./features/catalogue/catalogue.routes').then((m) => m.CATALOGUE_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
