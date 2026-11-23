import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandler,
  HttpInterceptor,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { Injectable } from '@angular/core';
import { mergeMap, Observable, of, throwError, timer } from 'rxjs';

import invalidCredentials from '../../../mocks/auth/invalid-credentials.json';
import unauthenticated from '../../../mocks/auth/unauthenticated.json';
import mockUsers from '../../../mocks/auth/users.json';
import validationFailed from '../../../mocks/auth/validation-failed.json';
import { CurrentUser, Role } from '../auth/current-user.model';
import { FieldError, ProblemDetail } from '../http/problem-detail';

/** Utilisateur fictif des jeux de données (mot de passe fictif, jamais un vrai secret). */
interface MockUser {
  userId: string;
  email: string;
  password: string;
  roles: Role[];
}

type MockResult = HttpResponse<unknown> | HttpErrorResponse;

interface MockEndpoint {
  method: string;
  path: string;
  handle: (req: HttpRequest<unknown>) => MockResult;
}

const MIN_DELAY_MS = 200;
const MAX_DELAY_MS = 400;
const SESSION_TTL_MS = 60 * 60 * 1000;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const EMAIL_MAX_LENGTH = 254;

/**
 * Mode données simulées (configuration `mock` uniquement) : sert les endpoints connus de `/api` à partir
 * des jeux de données de `src/mocks/`, avec un délai réaliste et un état en mémoire (session courante).
 * Toute autre requête est transmise telle quelle (vraie API via le proxy).
 * Enregistré en dernier : `authInterceptor` et `errorInterceptor` voient les erreurs simulées comme les vraies.
 */
@Injectable()
export class MockApiInterceptor implements HttpInterceptor {
  private readonly users = mockUsers as MockUser[];
  private session: CurrentUser | null = null;

  private readonly endpoints: MockEndpoint[] = [
    { method: 'POST', path: '/api/v1/auth/login', handle: (req) => this.login(req) },
    { method: 'GET', path: '/api/v1/auth/me', handle: () => this.me() },
    { method: 'POST', path: '/api/v1/auth/logout', handle: () => this.logout() },
  ];

  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    const path = req.url.split('?')[0];
    const endpoint = this.endpoints.find((e) => e.method === req.method && e.path === path);
    if (!endpoint) {
      return next.handle(req);
    }
    const latency = MIN_DELAY_MS + Math.floor(Math.random() * (MAX_DELAY_MS - MIN_DELAY_MS + 1));
    // L'état (session) change à l'émission, après le délai, comme une vraie réponse serveur.
    return timer(latency).pipe(
      mergeMap(() => {
        const result = endpoint.handle(req);
        return result instanceof HttpErrorResponse ? throwError(() => result) : of(result);
      }),
    );
  }

  private login(req: HttpRequest<unknown>): MockResult {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const email = typeof body['email'] === 'string' ? body['email'].trim().toLowerCase() : '';
    const password = typeof body['password'] === 'string' ? body['password'] : '';

    const errors: FieldError[] = [];
    if (email === '') {
      errors.push({ field: 'email', code: 'BLANK', message: "L'e-mail est obligatoire" });
    } else if (email.length > EMAIL_MAX_LENGTH) {
      errors.push({ field: 'email', code: 'TOO_LONG', message: "L'e-mail est trop long" });
    } else if (!EMAIL_PATTERN.test(email)) {
      errors.push({ field: 'email', code: 'INVALID_FORMAT', message: "L'e-mail est mal formé" });
    }
    if (password === '') {
      errors.push({ field: 'password', code: 'BLANK', message: 'Le mot de passe est obligatoire' });
    }
    if (errors.length > 0) {
      return problem({ ...(validationFailed as ProblemDetail), errors });
    }

    const user = this.users.find((u) => u.email === email && u.password === password);
    if (!user) {
      return problem(invalidCredentials as ProblemDetail);
    }
    this.session = {
      userId: user.userId,
      email: user.email,
      roles: user.roles,
      expiresAt: new Date(Date.now() + SESSION_TTL_MS).toISOString(),
    };
    return new HttpResponse({ status: 200, body: this.session, url: req.url });
  }

  private me(): MockResult {
    if (this.session === null || Date.parse(this.session.expiresAt) <= Date.now()) {
      this.session = null;
      return problem(unauthenticated as ProblemDetail);
    }
    return new HttpResponse({ status: 200, body: this.session });
  }

  private logout(): MockResult {
    this.session = null;
    return new HttpResponse({ status: 204, body: null });
  }
}

function problem(body: ProblemDetail): HttpErrorResponse {
  const status = body.status ?? 500;
  return new HttpErrorResponse({ status, statusText: statusText(status), error: body });
}

function statusText(status: number): string {
  const texts: Record<number, string> = {
    400: 'Bad Request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not Found',
    409: 'Conflict',
  };
  return texts[status] ?? 'Error';
}
