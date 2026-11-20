import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/home/pages/home').then((m) => m.Home) },
  { path: '**', redirectTo: '' },
];
