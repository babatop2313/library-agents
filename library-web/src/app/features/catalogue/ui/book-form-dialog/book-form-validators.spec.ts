import { FormControl } from '@angular/forms';

import {
  copiesRange,
  isbnFormat,
  maxTrimmedLength,
  notBlank,
  TEXT_MAX_LENGTH,
} from './book-form-validators';

/** RM-CAT-34 : validation client, jamais plus stricte que le backend. */
describe('book-form-validators', () => {
  describe('notBlank', () => {
    it.each(['', ' ', '   ', '\t'])('refuse %j', (value) => {
      expect(notBlank(new FormControl(value, { nonNullable: true }))).toEqual({ required: true });
    });

    it('accepte un texte non blanc', () => {
      expect(notBlank(new FormControl(' a ', { nonNullable: true }))).toBeNull();
    });
  });

  describe('maxTrimmedLength', () => {
    const validator = maxTrimmedLength(TEXT_MAX_LENGTH);

    it('accepte 255 caractères', () => {
      expect(validator(new FormControl('a'.repeat(255)))).toBeNull();
    });

    it('refuse 256 caractères', () => {
      expect(validator(new FormControl('a'.repeat(256)))).toEqual({ tooLong: true });
    });

    it('mesure la longueur après trim, comme le backend', () => {
      expect(validator(new FormControl('  ' + 'a'.repeat(255) + '  '))).toBeNull();
    });

    it('accepte une valeur nulle', () => {
      expect(validator(new FormControl(null))).toBeNull();
    });
  });

  describe('isbnFormat', () => {
    it.each([
      '9782070368228',
      '978-2-07-036822-8',
      '978 2 07 036822 8',
      '  9782070368228  ',
      '978-2 07-036822 8',
    ])('accepte %j (13 chiffres après retrait des espaces et tirets)', (value) => {
      expect(isbnFormat(new FormControl(value, { nonNullable: true }))).toBeNull();
    });

    it.each([
      '978207036822',
      '97820703682280',
      'abcdefghijklm',
      '978-2-07-03682A-8',
      '978.2070368228',
    ])('refuse %j', (value) => {
      expect(isbnFormat(new FormControl(value, { nonNullable: true }))).toEqual({
        isbnFormat: true,
      });
    });

    it('laisse la valeur vide à notBlank', () => {
      expect(isbnFormat(new FormControl('', { nonNullable: true }))).toBeNull();
      expect(isbnFormat(new FormControl('   ', { nonNullable: true }))).toBeNull();
    });
  });

  describe('copiesRange', () => {
    it('exige une valeur', () => {
      expect(copiesRange(new FormControl<number | null>(null))).toEqual({ required: true });
    });

    it.each([1, 2, 500, 1000])('accepte %i', (value) => {
      expect(copiesRange(new FormControl<number | null>(value))).toBeNull();
    });

    it.each([0, -1, 1001, 5000])('refuse %i (hors de 1 à 1000)', (value) => {
      expect(copiesRange(new FormControl<number | null>(value))).toEqual({ copiesRange: true });
    });

    it('refuse un nombre décimal', () => {
      expect(copiesRange(new FormControl<number | null>(2.5))).toEqual({ copiesRange: true });
    });
  });
});
