export type Role = 'LIBRARIAN' | 'MEMBER';

/** Corps renvoyé par `POST /api/v1/auth/login` et `GET /api/v1/auth/me` (aucun jeton). */
export interface CurrentUser {
  userId: string;
  email: string | null;
  roles: Role[];
  expiresAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}
