import { Routes } from '@angular/router';

export const CATALOGUE_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/catalogue/catalogue').then((m) => m.Catalogue),
  },
];
