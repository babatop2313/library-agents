import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export const TEXT_MAX_LENGTH = 255;
export const COPIES_MIN = 1;
export const COPIES_MAX = 1000;

/** Refuse un texte vide ou blanc (le « requis » et le « blanc » partagent le même message). */
export function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim().length === 0 ? { required: true } : null;
}

/** Longueur maximale mesurée après retrait des espaces de début et de fin, comme le backend. */
export function maxTrimmedLength(max: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value: unknown = control.value;
    return typeof value === 'string' && value.trim().length > max ? { tooLong: true } : null;
  };
}

/** ISBN : espaces et tirets retirés, puis exactement 13 chiffres (jamais plus strict que le backend). */
export function isbnFormat(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value.trim();
  if (value.length === 0) {
    return null; // laissé à `notBlank`
  }
  return /^\d{13}$/.test(value.replace(/[\s-]/g, '')) ? null : { isbnFormat: true };
}

/** Nombre d'exemplaires : obligatoire, entier, de 1 à 1000. */
export function copiesRange(control: AbstractControl<number | null>): ValidationErrors | null {
  const value = control.value;
  if (value === null) {
    return { required: true };
  }
  return Number.isInteger(value) && value >= COPIES_MIN && value <= COPIES_MAX
    ? null
    : { copiesRange: true };
}
