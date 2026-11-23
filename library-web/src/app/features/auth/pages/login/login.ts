import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { PasswordModule } from 'primeng/password';
import { map } from 'rxjs';

import { AuthService } from '../../../../core/auth/auth.service';
import { safeReturnUrl } from '../../../../core/auth/return-url';
import { isProblemDetail } from '../../../../core/http/problem-detail';

const EMAIL_FORMAT = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const EMAIL_MAX_LENGTH = 254;

function emailFormat(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value.trim();
  if (value.length === 0) {
    return null;
  }
  if (value.length > EMAIL_MAX_LENGTH) {
    return { emailTooLong: true };
  }
  return EMAIL_FORMAT.test(value) ? null : { emailFormat: true };
}

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, ButtonModule, InputTextModule, MessageModule, PasswordModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, emailFormat]],
    password: ['', [Validators.required]],
  });
  protected readonly submitting = signal(false);
  protected readonly invalidCredentials = signal(false);
  protected readonly formValid = toSignal(
    this.form.statusChanges.pipe(map((status) => status === 'VALID')),
    { initialValue: this.form.valid },
  );

  protected submit(): void {
    if (this.submitting()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.invalidCredentials.set(false);
    const { email, password } = this.form.getRawValue();

    this.auth.login({ email: email.trim(), password }).subscribe({
      next: () => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        // Navigation annulée (guard) ou en échec : la page reste affichée, le bouton doit redevenir utilisable.
        this.router.navigateByUrl(safeReturnUrl(returnUrl), { replaceUrl: true }).then(
          (navigated) => {
            if (!navigated) {
              this.submitting.set(false);
            }
          },
          () => this.submitting.set(false),
        );
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        this.handleError(error);
      },
    });
  }

  private handleError(error: unknown): void {
    if (!(error instanceof HttpErrorResponse)) {
      return;
    }
    if (error.status === 401) {
      this.invalidCredentials.set(true);
      this.form.controls.password.reset();
    } else if (error.status === 400 && isProblemDetail(error.error)) {
      for (const fieldError of error.error.errors ?? []) {
        const control = this.form.get(fieldError.field);
        control?.setErrors({ server: fieldError.message });
        control?.markAsTouched();
      }
    }
  }
}
