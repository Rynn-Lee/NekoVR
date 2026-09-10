import { lstatSync, realpathSync } from 'node:fs';
import path from 'node:path';

export type AuthorizedTargetType = 'directory' | 'regular-file';

export interface FilesystemAuthorizationAdapter {
  realpath(target: string): string;
  lstat(target: string): {
    isDirectory(): boolean;
    isFile(): boolean;
    isSymbolicLink(): boolean;
  };
}

const nativeFilesystem: FilesystemAuthorizationAdapter = {
  realpath: realpathSync.native,
  lstat: lstatSync,
};

export interface ExistingPathAuthorization {
  requestedPath: string;
  allowedRoots: readonly string[];
  targetTypes: readonly AuthorizedTargetType[];
  directChild?: boolean;
  requiredExtension?: string;
}

function isContained(canonicalRoot: string, canonicalTarget: string): boolean {
  const relative = path.relative(canonicalRoot, canonicalTarget);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

/** Authorize and return an existing canonical target, or null on any ambiguity. */
export function authorizeExistingPath(
  request: ExistingPathAuthorization,
  filesystem: FilesystemAuthorizationAdapter = nativeFilesystem
): string | null {
  try {
    const requestedPath = path.resolve(request.requestedPath);
    const requestedStat = filesystem.lstat(requestedPath);
    if (requestedStat.isSymbolicLink()) return null;

    const canonicalTarget = filesystem.realpath(requestedPath);
    const targetStat = filesystem.lstat(canonicalTarget);
    if (targetStat.isSymbolicLink()) return null;

    const targetType: AuthorizedTargetType | null = targetStat.isFile()
      ? 'regular-file'
      : targetStat.isDirectory()
        ? 'directory'
        : null;
    if (!targetType || !request.targetTypes.includes(targetType)) return null;
    if (
      request.requiredExtension &&
      path.extname(canonicalTarget).toLowerCase() !==
        request.requiredExtension.toLowerCase()
    ) {
      return null;
    }

    for (const root of request.allowedRoots) {
      const canonicalRoot = filesystem.realpath(path.resolve(root));
      const rootStat = filesystem.lstat(canonicalRoot);
      if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) continue;
      if (!isContained(canonicalRoot, canonicalTarget)) continue;
      if (request.directChild && path.dirname(canonicalTarget) !== canonicalRoot) {
        continue;
      }
      return canonicalTarget;
    }
  } catch {
    // Missing/inaccessible roots and targets are denied.
  }
  return null;
}
