import { Role } from './current-user.model';

export type RoleSeverity = 'info' | 'secondary';

const LABELS: Record<Role, string> = {
  LIBRARIAN: 'Bibliothécaire',
  MEMBER: 'Membre',
};

const SEVERITIES: Record<Role, RoleSeverity> = {
  LIBRARIAN: 'info',
  MEMBER: 'secondary',
};

export function roleLabel(role: Role): string {
  return LABELS[role] ?? role;
}

export function roleSeverity(role: Role): RoleSeverity {
  return SEVERITIES[role] ?? 'secondary';
}
