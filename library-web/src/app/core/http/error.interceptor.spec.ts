import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';

import { errorInterceptor } from './error.interceptor';

describe('errorInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
  let messages: MessageService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        MessageService,
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
    messages = TestBed.inject(MessageService);
    vi.spyOn(messages, 'add');
  });

  afterEach(() => httpTesting.verify());

  function failWith(status: number, body: object | null = null): void {
    http.get('/api/v1/test').subscribe({ error: () => undefined });
    httpTesting.expectOne('/api/v1/test').flush(body, { status, statusText: 'Error' });
  }

  it('affiche le titre et le détail du ProblemDetail', () => {
    failWith(409, { title: 'ISBN déjà enregistré', detail: 'Le livre existe déjà', status: 409 });

    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'ISBN déjà enregistré',
      detail: 'Le livre existe déjà',
    });
  });

  it('affiche un message générique sans détail technique pour un 5xx', () => {
    failWith(500, { title: 'Internal Server Error', detail: 'NullPointerException at ...' });

    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'Erreur',
      detail: 'Une erreur technique est survenue. Réessayez plus tard.',
    });
  });

  it('affiche un message de droits insuffisants pour un 403', () => {
    failWith(403);

    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'Accès refusé',
      detail: "Vous n'avez pas les droits nécessaires",
    });
  });

  it("ne notifie pas un 401 (géré par l'intercepteur d'authentification)", () => {
    failWith(401);

    expect(messages.add).not.toHaveBeenCalled();
  });

  it("propage l'erreur à l'appelant", () => {
    let status: number | undefined;
    http.get('/api/v1/test').subscribe({ error: (e: { status: number }) => (status = e.status) });
    httpTesting.expectOne('/api/v1/test').flush(null, { status: 400, statusText: 'Bad Request' });

    expect(status).toBe(400);
  });
});
