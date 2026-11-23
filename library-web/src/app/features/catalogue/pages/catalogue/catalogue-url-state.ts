import { ParamMap, Params } from '@angular/router';

import { DEFAULT_SIZE, PAGE_SIZES, SEARCH_MAX_LENGTH } from '../../data/catalogue.constants';


/** État de la liste porté par l'URL : `page` en base 1, `q` rogné et tronqué. */
export interface CatalogueUrlState {
  page: number;
  size: number;
  q: string;
}

export function parseCatalogueUrl(params: ParamMap): CatalogueUrlState {
  const rawPage = Number(params.get('page'));
  const page = Number.isInteger(rawPage) && rawPage >= 1 ? rawPage : 1;
  const rawSize = Number(params.get('size'));
  const size = PAGE_SIZES.some((s) => s === rawSize) ? rawSize : DEFAULT_SIZE;
  const q = (params.get('q') ?? '').trim().slice(0, SEARCH_MAX_LENGTH).trim();
  return { page, size, q };
}

/** Paramètres de l'API : page en base 0. */
export function toApiParams(state: CatalogueUrlState): { page: number; size: number; q?: string } {
  return state.q === ''
    ? { page: state.page - 1, size: state.size }
    : { page: state.page - 1, size: state.size, q: state.q };
}

/** Paramètres d'URL : les valeurs par défaut sont omises. */
export function toQueryParams(state: CatalogueUrlState): Params {
  return {
    q: state.q === '' ? undefined : state.q,
    page: state.page === 1 ? undefined : state.page,
    size: state.size === DEFAULT_SIZE ? undefined : state.size,
  };
}
