import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SKIP_ERROR_TOAST } from '../../../core/http/error-toast.context';
import { CurrentLoan } from './loans.model';
import { LoansService } from './loans.service';

const LOAN: CurrentLoan = {
  id: 'a1b2c3d4-0000-4000-8000-000000000101',
  bookId: '8b1e4d70-52a3-4f0b-b6c9-7e2d1a9f3c55',
  isbn: '9782070360420',
  title: 'La Peste',
  author: 'Albert Camus',
  borrowedOn: '2026-11-05',
  dueOn: '2026-11-19',
  overdue: true,
};

describe('LoansService', () => {
  let service: LoansService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(LoansService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  describe('listMine', () => {
    it('appelle GET /api/v1/loans (chemin relatif, sans paramètre)', () => {
      service.listMine().subscribe();

      const req = httpTesting.expectOne('/api/v1/loans');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.keys()).toHaveLength(0);
      req.flush([]);
    });

    it('renvoie les emprunts tels que le backend les fournit', () => {
      let received: CurrentLoan[] | undefined;
      service.listMine().subscribe((loans) => (received = loans));

      httpTesting.expectOne('/api/v1/loans').flush([LOAN]);

      expect(received).toEqual([LOAN]);
    });

    it('ne supprime pas la notification d’erreur (aucun statut ignoré)', () => {
      service.listMine().subscribe();

      const req = httpTesting.expectOne('/api/v1/loans');
      expect(req.request.context.get(SKIP_ERROR_TOAST).size).toBe(0);
      req.flush([]);
    });
  });

  describe('giveBack', () => {
    it('appelle POST /api/v1/loans/{id}/return sans corps', () => {
      service.giveBack(LOAN.id).subscribe();

      const req = httpTesting.expectOne(`/api/v1/loans/${LOAN.id}/return`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('ignore la notification de l’intercepteur pour 404, 409 et 503 seulement', () => {
      service.giveBack(LOAN.id).subscribe();

      const req = httpTesting.expectOne(`/api/v1/loans/${LOAN.id}/return`);
      expect(req.request.context.get(SKIP_ERROR_TOAST)).toEqual(new Set([404, 409, 503]));
      req.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('encode l’identifiant dans l’URL', () => {
      service.giveBack('a/b c').subscribe();

      const req = httpTesting.expectOne('/api/v1/loans/a%2Fb%20c/return');
      expect(req.request.method).toBe('POST');
      req.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('propage une erreur 409', () => {
      let status: number | undefined;
      service
        .giveBack(LOAN.id)
        .subscribe({ error: (e: { status: number }) => (status = e.status) });

      httpTesting
        .expectOne(`/api/v1/loans/${LOAN.id}/return`)
        .flush({ code: 'LOAN_ALREADY_RETURNED' }, { status: 409, statusText: 'Conflict' });

      expect(status).toBe(409);
    });
  });
});
