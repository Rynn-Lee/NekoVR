import path from 'node:path';
import { authorizeExistingPath } from './filesystem-authorization.ts';

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
  const absoluteRoot = path.resolve(datasetsRoot);
  return authorizeExistingPath({
    requestedPath: path.join(absoluteRoot, `${safeId}.nvrdata`),
    allowedRoots: [absoluteRoot],
    targetTypes: ['regular-file'],
    directChild: true,
    requiredExtension: '.nvrdata',
  });
}
