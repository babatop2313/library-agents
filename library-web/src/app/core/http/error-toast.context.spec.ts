import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';

import { SKIP_ERROR_TOAST, skipErrorToast } from './error-toast.context';
import { errorInterceptor } from './error.interceptor';

/** RM-CAT-40 : option réutilisable de `core/http` qui supprime le toast générique pour certains statuts. */
describe('skipErrorToast / errorInterceptor', () => {
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

  function post(status: number, skipped: number[], body: object | null = null): number | undefined {
    let received: number | undefined;
    http
      .post('/api/v1/books', {}, { context: skipErrorToast(...skipped) })
      .subscribe({ error: (e: { status: number }) => (received = e.status) });
    httpTesting.expectOne('/api/v1/books').flush(body, { status, statusText: 'Error' });
    return received;
  }

  it('est vide par défaut : aucun statut ignoré', () => {
    const context = skipErrorToast();

    expect(context.get(SKIP_ERROR_TOAST).size).toBe(0);
    expect(SKIP_ERROR_TOAST.defaultValue()).toEqual(new Set());
  });

  it('conserve les statuts demandés dans le contexte', () => {
    expect(skipErrorToast(400, 409).get(SKIP_ERROR_TOAST)).toEqual(new Set([400, 409]));
  });

  it.each([400, 409])('ne notifie pas un %i ignoré, et propage l’erreur', (status) => {
    const received = post(status, [400, 409], { title: 'Validation échouée', status });

    expect(messages.add).not.toHaveBeenCalled();
    expect(received).toBe(status);
  });

  it('notifie toujours un statut absent de la liste ignorée', () => {
    post(404, [400, 409], { title: 'Introuvable', detail: 'Pas là' });

    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'Introuvable',
      detail: 'Pas là',
    });
  });

  it('notifie toujours un 403, même ignoré', () => {
    post(403, [400, 403, 409]);

    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'Accès refusé',
      detail: "Vous n'avez pas les droits nécessaires",
    });
  });

  it.each([500, 502, 503])('notifie toujours un %i, même ignoré', (status) => {
    post(status, [status, 400, 409]);

    expect(messages.add).toHaveBeenCalledWith({
      severity: 'error',
      summary: 'Erreur',
      detail: 'Une erreur technique est survenue. Réessayez plus tard.',
    });
  });

  it('notifie toujours une erreur réseau (statut 0), même ignorée', () => {
    http.post('/api/v1/books', {}, { context: skipErrorToast(0, 400) }).subscribe({
      error: () => undefined,
    });
    httpTesting.expectOne('/api/v1/books').error(new ProgressEvent('error'), { status: 0 });

    expect(messages.add).toHaveBeenCalledTimes(1);
    expect(messages.add).toHaveBeenCalledWith(
      expect.objectContaining({ severity: 'error', summary: 'Erreur' }),
    );
  });

  it('ne change rien pour une requête sans contexte : un 400 reste notifié', () => {
    http.get('/api/v1/books').subscribe({ error: () => undefined });
    httpTesting
      .expectOne('/api/v1/books')
      .flush({ title: 'Validation échouée' }, { status: 400, statusText: 'Bad Request' });

    expect(messages.add).toHaveBeenCalledTimes(1);
  });

  it('ne ressent pas le contexte d’une autre requête', () => {
    post(400, [400]);
    http.get('/api/v1/books').subscribe({ error: () => undefined });
    httpTesting
      .expectOne('/api/v1/books')
      .flush({ title: 'Validation échouée' }, { status: 400, statusText: 'Bad Request' });

    expect(messages.add).toHaveBeenCalledTimes(1);
  });
});
