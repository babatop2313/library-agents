import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-header',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="app-header d-flex align-items-center px-4 py-3">
      <a routerLink="/" class="app-title d-flex align-items-center gap-2">
        <i class="pi pi-book" aria-hidden="true"></i>
        <span>Bibliothèque</span>
      </a>
    </header>
  `,
  styleUrl: './header.scss',
})
export class Header {}
