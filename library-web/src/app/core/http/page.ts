/** Page de résultats renvoyée par les endpoints paginés (`page` en base 0). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
