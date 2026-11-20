import { roleLabel, roleSeverity } from './role-label';

describe('role-label', () => {
  it('traduit les rôles en libellés français', () => {
    expect(roleLabel('LIBRARIAN')).toBe('Bibliothécaire');
    expect(roleLabel('MEMBER')).toBe('Membre');
  });

  it('associe une sévérité de badge à chaque rôle', () => {
    expect(roleSeverity('LIBRARIAN')).toBe('info');
    expect(roleSeverity('MEMBER')).toBe('secondary');
  });

  it('retombe sur la valeur brute et la sévérité neutre pour un rôle inconnu', () => {
    expect(roleLabel('ADMIN' as never)).toBe('ADMIN');
    expect(roleSeverity('ADMIN' as never)).toBe('secondary');
  });
});
