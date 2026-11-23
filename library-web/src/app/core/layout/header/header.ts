import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { finalize } from 'rxjs';

import { AuthService } from '../../auth/auth.service';
import { roleLabel, roleSeverity } from '../../auth/role-label';

@Component({
  selector: 'app-header',
  imports: [RouterLink, RouterLinkActive, ButtonModule, TagModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './header.html',
  styleUrl: './header.scss',
})
export class Header {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly user = this.auth.user;
  protected readonly label = roleLabel;
  protected readonly severity = roleSeverity;

  protected readonly loggingOut = signal(false);

  protected logout(): void {
    if (this.loggingOut()) {
      return;   // double clic : un seul appel de déconnexion
    }
    this.loggingOut.set(true);
    this.auth
      .logout()
      .pipe(finalize(() => this.loggingOut.set(false)))
      .subscribe(() => {
        void this.router.navigate(['/login'], { replaceUrl: true });
      });
  }
}
