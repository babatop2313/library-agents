import { formatIsoDate } from './iso-date';

/** RM-LOAN-24 : dates au format français, sans décalage de fuseau. */
describe('formatIsoDate', () => {
  it('formate une date ISO en jj/mm/aaaa', () => {
    expect(formatIsoDate('2026-12-08')).toBe('08/12/2026');
  });

  it('conserve les zéros de tête du jour et du mois', () => {
    expect(formatIsoDate('2026-01-05')).toBe('05/01/2026');
  });

  it('ne dépend pas du fuseau : le 1er janvier reste le 1er janvier', () => {
    expect(formatIsoDate('2027-01-01')).toBe('01/01/2027');
    expect(formatIsoDate('2026-12-31')).toBe('31/12/2026');
  });

  it.each(['', 'demain', '2026-12-08T10:00:00Z', '08/12/2026', '2026-1-8'])(
    'renvoie telle quelle une valeur qui n’est pas une date ISO (%j)',
    (value) => {
      expect(formatIsoDate(value)).toBe(value);
    },
  );
});
