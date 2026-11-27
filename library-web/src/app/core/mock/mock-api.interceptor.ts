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
import { MockCatalogue } from './mock-catalogue';
import { MockLoans } from './mock-loans';
import { problem } from './mock-problem';

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
  /** Chemin exact, ou motif (`RegExp`) dont le premier groupe capturé est passé à `handle`. */
  path: string | RegExp;
  handle: (req: HttpRequest<unknown>, param: string) => MockResult;
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
  private readonly catalogue = new MockCatalogue();
  private readonly loans = new MockLoans(this.catalogue);
  private session: CurrentUser | null = null;

  private readonly endpoints: MockEndpoint[] = [
    { method: 'POST', path: '/api/v1/auth/login', handle: (req) => this.login(req) },
    { method: 'GET', path: '/api/v1/auth/me', handle: () => this.me() },
    { method: 'POST', path: '/api/v1/auth/logout', handle: () => this.logout() },
    {
      method: 'GET',
      path: '/api/v1/books',
      handle: (req) => this.withSession((user) => this.catalogue.search(req, user.roles)),
    },
    {
      method: 'POST',
      path: '/api/v1/books',
      handle: (req) => this.withSession((user) => this.catalogue.register(req, user.roles)),
    },
    {
      method: 'POST',
      path: '/api/v1/loans',
      handle: (req) => this.withSession((user) => this.loans.borrow(req, user)),
    },
    {
      method: 'GET',
      path: '/api/v1/loans',
      handle: () => this.withSession((user) => this.loans.list(user)),
    },
    {
      method: 'POST',
      path: /^\/api\/v1\/loans\/([^/]+)\/return$/,
      handle: (_req, id) => this.withSession((user) => this.loans.giveBack(id, user)),
    },
  ];

  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    const path = req.url.split('?')[0];
    let param = '';
    const endpoint = this.endpoints.find((e) => {
      if (e.method !== req.method) {
        return false;
      }
      if (typeof e.path === 'string') {
        return e.path === path;
      }
      const match = e.path.exec(path);
      param = match ? decodeURIComponent(match[1]) : '';
      return match !== null;
    });
    if (!endpoint) {
      return next.handle(req);
    }
    const latency = MIN_DELAY_MS + Math.floor(Math.random() * (MAX_DELAY_MS - MIN_DELAY_MS + 1));
    // L'état (session) change à l'émission, après le délai, comme une vraie réponse serveur.
    return timer(latency).pipe(
      mergeMap(() => {
        const result = endpoint.handle(req, param);
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

  /** 401 sans session valide ; sinon délègue avec l'utilisateur connecté. */
  private withSession(handle: (user: CurrentUser) => MockResult): MockResult {
    if (this.session === null || Date.parse(this.session.expiresAt) <= Date.now()) {
      this.session = null;
      return problem(unauthenticated as ProblemDetail);
    }
    return handle(this.session);
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
