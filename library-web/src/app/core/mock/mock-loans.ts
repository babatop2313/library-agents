import { HttpErrorResponse, HttpHeaders, HttpRequest, HttpResponse } from '@angular/common/http';

import accessDenied from '../../../mocks/loans/access-denied.json';
import bookAlreadyBorrowed from '../../../mocks/loans/book-already-borrowed.json';
import bookNotAvailable from '../../../mocks/loans/book-not-available.json';
import bookNotFound from '../../../mocks/loans/book-not-found.json';
import loanAlreadyReturned from '../../../mocks/loans/loan-already-returned.json';
import loanLimitReached from '../../../mocks/loans/loan-limit-reached.json';
import loanNotFound from '../../../mocks/loans/loan-not-found.json';
import seedLoans from '../../../mocks/loans/loans.json';
import memberHasOverdueLoan from '../../../mocks/loans/member-has-overdue-loan.json';
import validationFailed from '../../../mocks/loans/validation-failed.json';
import { CurrentUser } from '../auth/current-user.model';
import { ProblemDetail } from '../http/problem-detail';
import { MockCatalogue } from './mock-catalogue';
import { problem } from './mock-problem';

// Types locaux : `core` n'importe jamais `features` (voir docs/api/loan.md pour le contrat).
interface MockLoan {
  id: string;
  memberId: string;
  bookId: string;
  /** `yyyy-MM-dd`, date locale. */
  borrowedOn: string;
  dueOn: string;
  returnedOn: string | null;
}

/** Ligne de la graine `loans.json` : dates relatives à aujourd'hui, calculées au chargement. */
interface SeedLoan {
  id: string;
  memberId: string;
  bookId: string;
  borrowedDaysAgo: number;
  dueInDays: number;
}

type MockResult = HttpResponse<unknown> | HttpErrorResponse;

const LOANS_URL = '/api/v1/loans';
const MAX_ACTIVE_LOANS = 3;
const LOAN_DURATION_DAYS = 14;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Emprunts simulés (configuration `mock`) : mêmes règles et même ordre d'évaluation que
 * `POST` / `GET /api/v1/loans` et `POST /api/v1/loans/{id}/return`. État en mémoire, partagé avec le
 * catalogue simulé : emprunter baisse `availableCopies`, rendre l'augmente, jusqu'au rechargement de la page.
 * Le membre concerné est toujours l'utilisateur connecté.
 */
export class MockLoans {
  private readonly loans: MockLoan[] = (seedLoans as SeedLoan[]).map((seed) => ({
    id: seed.id,
    memberId: seed.memberId,
    bookId: seed.bookId,
    borrowedOn: localIsoDate(-seed.borrowedDaysAgo),
    dueOn: localIsoDate(seed.dueInDays),
    returnedOn: null,
  }));

  private readonly catalogue: MockCatalogue;

  constructor(catalogue: MockCatalogue) {
    this.catalogue = catalogue;
    // Cohérence du stock : chaque emprunt actif de la graine occupe un exemplaire (livre inconnu : ignoré).
    for (const loan of this.loans) {
      this.catalogue.takeCopy(loan.bookId);
    }
  }

  /** Emprunte un livre : 403, 400, puis 404, retard, limite, déjà emprunté, indisponible ; sinon 201. */
  borrow(req: HttpRequest<unknown>, user: CurrentUser): MockResult {
    if (!user.roles.includes('MEMBER')) {
      return problem(accessDenied as ProblemDetail);
    }

    const body = typeof req.body === 'object' && req.body !== null ? req.body : {};
    const raw = (body as Record<string, unknown>)['bookId'];
    if (raw === undefined || raw === null) {
      return problem(withErrors('REQUIRED', "Le champ 'bookId' est obligatoire"));
    }
    if (typeof raw !== 'string' || !UUID.test(raw)) {
      return problem(withErrors('INVALID_FORMAT', "Le champ 'bookId' a un format invalide"));
    }
    const bookId = raw.toLowerCase();

    if (this.catalogue.findById(bookId) === null) {
      return problem(bookNotFound as ProblemDetail);
    }
    const today = localIsoDate(0);
    const active = this.activeOf(user.userId);
    if (active.some((loan) => loan.dueOn < today)) {
      return problem(memberHasOverdueLoan as ProblemDetail);
    }
    if (active.length >= MAX_ACTIVE_LOANS) {
      return problem(loanLimitReached as ProblemDetail);
    }
    if (active.some((loan) => loan.bookId === bookId)) {
      return problem(bookAlreadyBorrowed as ProblemDetail);
    }
    if (!this.catalogue.takeCopy(bookId)) {
      return problem(bookNotAvailable as ProblemDetail);
    }

    const loan: MockLoan = {
      id: crypto.randomUUID(),
      memberId: user.userId,
      bookId,
      borrowedOn: today,
      dueOn: localIsoDate(LOAN_DURATION_DAYS),
      returnedOn: null,
    };
    this.loans.push(loan);
    return new HttpResponse({
      status: 201,
      body: { id: loan.id, bookId, borrowedOn: loan.borrowedOn, dueOn: loan.dueOn },
      headers: new HttpHeaders({ Location: `${LOANS_URL}/${loan.id}` }),
    });
  }

  /** Emprunts actifs de l'utilisateur, triés par échéance puis identifiant ; `overdue` à la date locale. */
  list(user: CurrentUser): MockResult {
    if (!user.roles.includes('MEMBER')) {
      return problem(accessDenied as ProblemDetail);
    }
    const today = localIsoDate(0);
    const body = this.activeOf(user.userId).map((loan) => {
      const book = this.catalogue.findById(loan.bookId);
      return {
        id: loan.id,
        bookId: loan.bookId,
        isbn: book?.isbn ?? null,
        title: book?.title ?? null,
        author: book?.author ?? null,
        borrowedOn: loan.borrowedOn,
        dueOn: loan.dueOn,
        overdue: loan.dueOn < today,
      };
    });
    return new HttpResponse({ status: 200, body });
  }

  /** Rend un emprunt : 404 si inconnu ou d'un autre membre, 409 si déjà rendu, sinon 204. */
  giveBack(id: string, user: CurrentUser): MockResult {
    if (!user.roles.includes('MEMBER')) {
      return problem(accessDenied as ProblemDetail);
    }
    const loan = this.loans.find(
      (candidate) => candidate.id === id.toLowerCase() && candidate.memberId === user.userId,
    );
    if (!loan) {
      return problem(loanNotFound as ProblemDetail);
    }
    if (loan.returnedOn !== null) {
      return problem(loanAlreadyReturned as ProblemDetail);
    }
    loan.returnedOn = localIsoDate(0);
    this.catalogue.returnCopy(loan.bookId);
    return new HttpResponse({ status: 204, body: null });
  }

  private activeOf(memberId: string): MockLoan[] {
    return this.loans
      .filter((loan) => loan.memberId === memberId && loan.returnedOn === null)
      .sort((a, b) => compareText(a.dueOn, b.dueOn) || compareText(a.id, b.id));
  }
}

function withErrors(code: string, message: string): ProblemDetail {
  return {
    ...(validationFailed as ProblemDetail),
    errors: [{ field: 'bookId', code, message }],
  };
}

function compareText(a: string, b: string): number {
  if (a === b) {
    return 0;
  }
  return a < b ? -1 : 1;
}

/** Date locale (jour du navigateur) décalée de `offsetDays` jours, au format `yyyy-MM-dd`. */
function localIsoDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}
