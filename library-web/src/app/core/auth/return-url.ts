/** Accepte uniquement un chemin interne (`/...`), ni `//` ni `/\` ; sinon `/`. */
export function safeReturnUrl(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) {
    return '/';
  }
  return raw;
}
