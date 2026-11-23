import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, finalize, firstValueFrom, Observable, of, tap } from 'rxjs';

import { CurrentUser, LoginRequest, Role } from './current-user.model';

/**
 * État de connexion. Le jeton est dans un cookie HttpOnly : il n'est ni lu, ni stocké, ni envoyé
 * par ce service (aucun en-tête Authorization).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/auth';
  private readonly state = signal<CurrentUser | null>(null);

  readonly user = this.state.asReadonly();
  readonly isAuthenticated = computed(() => this.state() !== null);

  hasRole(role: Role): boolean {
    return this.state()?.roles.includes(role) ?? false;
  }

  login(request: LoginRequest): Observable<CurrentUser> {
    return this.http
      .post<CurrentUser>(`${this.baseUrl}/login`, request)
      .pipe(tap((user) => this.state.set(user)));
  }

  /** Restaure la session au démarrage ; ne rejette jamais (401 = non connecté). */
  async restoreSession(): Promise<void> {
    try {
      const user = await firstValueFrom(this.http.get<CurrentUser>(`${this.baseUrl}/me`));
      this.state.set(user);
    } catch {
      this.state.set(null);
    }
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, null).pipe(
      catchError(() => of(undefined)),
      finalize(() => this.state.set(null)),
    );
  }

  /** Vide la session ; renvoie true seulement si elle existait (une seule notification d'expiration). */
  expireSession(): boolean {
    if (this.state() === null) {
      return false;
    }
    this.state.set(null);
    return true;
  }
}
