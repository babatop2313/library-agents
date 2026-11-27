import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';

import { skipErrorToast } from './error-toast.context';
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

  /** RM-LOAN-25, RM-LOAN-27 : le 503 LOAN_BUSY est notifié par l'écran, 500 et 502 restent toujours notifiés. */
  describe('503 ignoré à la demande', () => {
    function postWith(status: number, skipped: number[]): void {
      http
        .post('/api/v1/loans', {}, { context: skipErrorToast(...skipped) })
        .subscribe({ error: () => undefined });
      httpTesting
        .expectOne('/api/v1/loans')
        .flush({ title: 'Service momentanément occupé', code: 'LOAN_BUSY' }, { status, statusText: 'Error' });
    }

    it('ne notifie pas un 503 quand l’appelant le demande', () => {
      postWith(503, [404, 409, 503]);

      expect(messages.add).not.toHaveBeenCalled();
    });

    it('notifie un 503 quand l’appelant ne le demande pas', () => {
      postWith(503, [404, 409]);

      expect(messages.add).toHaveBeenCalledTimes(1);
    });

    it.each([500, 502])('notifie toujours un %i, même demandé', (status) => {
      postWith(status, [404, 409, 503, status]);

      expect(messages.add).toHaveBeenCalledWith({
        severity: 'error',
        summary: 'Erreur',
        detail: 'Une erreur technique est survenue. Réessayez plus tard.',
      });
    });
  });
});
