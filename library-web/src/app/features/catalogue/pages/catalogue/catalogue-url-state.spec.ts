import { convertToParamMap, Params } from '@angular/router';

import { DEFAULT_SIZE, PAGE_SIZES, SEARCH_MAX_LENGTH } from '../../data/catalogue.constants';
import {
  CatalogueUrlState,
  parseCatalogueUrl,
  toApiParams,
  toQueryParams,
} from './catalogue-url-state';

function parse(params: Params): CatalogueUrlState {
  return parseCatalogueUrl(convertToParamMap(params));
}

/** RM-CAT-31 : l'état (page, taille, q) est porté par l'URL, `page` en base 1. */
describe('catalogue-url-state', () => {
  describe('parseCatalogueUrl', () => {
    it('applique les valeurs par défaut sans paramètre', () => {
      expect(parse({})).toEqual({ page: 1, size: 10, q: '' });
    });

    it('lit q, page et size', () => {
      expect(parse({ q: 'camus', page: '3', size: '20' })).toEqual({
        page: 3,
        size: 20,
        q: 'camus',
      });
    });

    it.each(['abc', '0', '-2', '1.5', '', ' ', 'NaN', 'Infinity'])(
      'ramène page=%j à 1 (absente, non numérique ou < 1)',
      (page) => {
        expect(parse({ page }).page).toBe(1);
      },
    );

    it('accepte une page entière >= 1', () => {
      expect(parse({ page: '1' }).page).toBe(1);
      expect(parse({ page: '12' }).page).toBe(12);
    });

    it.each(PAGE_SIZES)('accepte la taille %i', (size) => {
      expect(parse({ size: String(size) }).size).toBe(size);
    });

    it.each(['0', '5', '51', '100', 'abc', '-10', ''])(
      'ramène size=%j à la taille par défaut',
      (size) => {
        expect(parse({ size }).size).toBe(DEFAULT_SIZE);
      },
    );

    it('rogne q', () => {
      expect(parse({ q: '  zola  ' }).q).toBe('zola');
    });

    it('traite un q blanc comme absent', () => {
      expect(parse({ q: '   ' }).q).toBe('');
    });

    it('tronque q à 100 caractères', () => {
      expect(parse({ q: 'a'.repeat(150) }).q).toHaveLength(SEARCH_MAX_LENGTH);
    });

    it('conserve les caractères spéciaux tels quels', () => {
      expect(parse({ q: '100% _ \\ <b>' }).q).toBe('100% _ \\ <b>');
    });
  });

  describe('toApiParams', () => {
    it('convertit la page en base 0', () => {
      expect(toApiParams({ page: 1, size: 10, q: '' })).toEqual({ page: 0, size: 10 });
      expect(toApiParams({ page: 3, size: 20, q: '' })).toEqual({ page: 2, size: 20 });
    });

    it('omet q quand il est vide', () => {
      expect('q' in toApiParams({ page: 1, size: 10, q: '' })).toBe(false);
    });

    it('transmet q quand il est renseigné', () => {
      expect(toApiParams({ page: 1, size: 20, q: 'camus' })).toEqual({
        page: 0,
        size: 20,
        q: 'camus',
      });
    });
  });

  describe('toQueryParams', () => {
    it('omet les valeurs par défaut', () => {
      expect(toQueryParams({ page: 1, size: 10, q: '' })).toEqual({
        q: undefined,
        page: undefined,
        size: undefined,
      });
    });

    it('écrit page en base 1, size et q quand ils diffèrent du défaut', () => {
      expect(toQueryParams({ page: 2, size: 50, q: 'camus' })).toEqual({
        q: 'camus',
        page: 2,
        size: 50,
      });
    });

    it('est l’inverse de parseCatalogueUrl', () => {
      const state: CatalogueUrlState = { page: 4, size: 20, q: 'victor hugo' };
      const queryParams = Object.fromEntries(
        Object.entries(toQueryParams(state)).map(([key, value]) => [key, String(value)]),
      );

      expect(parse(queryParams)).toEqual(state);
    });
  });
});
