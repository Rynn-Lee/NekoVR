import path from 'node:path';
import { existsSync, lstatSync, mkdirSync, realpathSync } from 'node:fs';

export function sanitizeDatasetSessionId(sessionId: string): string | null {
  return /^[A-Za-z0-9_-]{1,128}$/.test(sessionId) ? sessionId : null;
}

/** Resolve one regular archive directly below the canonical managed root. */
export function resolveManagedDatasetArchive(
  datasetsRoot: string,
  sessionId: string
): string | null {
  const safeId = sanitizeDatasetSessionId(sessionId);
  if (!safeId) return null;
  try {
    const absoluteRoot = path.resolve(datasetsRoot);
    if (!existsSync(absoluteRoot)) mkdirSync(absoluteRoot, { recursive: true });
    const canonicalRoot = realpathSync.native(absoluteRoot);
    const candidate = path.join(canonicalRoot, `${safeId}.nvrdata`);
    if (!existsSync(candidate)) return null;
    const stat = lstatSync(candidate);
    if (stat.isSymbolicLink() || !stat.isFile()) return null;
    const canonicalTarget = realpathSync.native(candidate);
    return path.dirname(canonicalTarget) === canonicalRoot ? canonicalTarget : null;
  } catch {
    return null;
  }
}
