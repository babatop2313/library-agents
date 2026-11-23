import { safeReturnUrl } from './return-url';

describe('safeReturnUrl', () => {
  it('accepte un chemin interne', () => {
    expect(safeReturnUrl('/books')).toBe('/books');
  });

  it('conserve les paramètres et le fragment d’un chemin interne', () => {
    expect(safeReturnUrl('/books?page=2#top')).toBe('/books?page=2#top');
  });

  it('accepte la racine', () => {
    expect(safeReturnUrl('/')).toBe('/');
  });

  it.each([
    ['https://evil.example'],
    ['http://evil.example/path'],
    ['//evil.example'],
    ['/\\evil.example'],
    ['evil.example'],
    ['javascript:alert(1)'],
    [''],
  ])('refuse « %s » et retombe sur la racine', (raw) => {
    expect(safeReturnUrl(raw)).toBe('/');
  });

  it('retombe sur la racine pour null ou undefined', () => {
    expect(safeReturnUrl(null)).toBe('/');
    expect(safeReturnUrl(undefined)).toBe('/');
  });
});
