const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Formate une `LocalDate` du contrat (`yyyy-MM-dd`) au format français `jj/mm/aaaa`.
 * Découpage de la chaîne, sans `new Date()` : aucune conversion de fuseau, donc aucun décalage d'un jour.
 * Une valeur qui n'est pas une date ISO est renvoyée telle quelle.
 */
export function formatIsoDate(value: string): string {
  const match = ISO_DATE.exec(value);
  return match ? `${match[3]}/${match[2]}/${match[1]}` : value;
}
