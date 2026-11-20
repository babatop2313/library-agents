import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="container py-5">
      <h1>Bienvenue à la bibliothèque</h1>
      <p class="mt-3">Les fonctionnalités arrivent bientôt.</p>
    </section>
  `,
})
export class Home {}
