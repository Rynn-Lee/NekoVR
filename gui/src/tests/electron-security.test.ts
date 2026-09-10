import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import {
  authorizeExistingPath,
  type FilesystemAuthorizationAdapter,
} from '../../electron/main/filesystem-authorization.ts';
import {
  handleExternalNavigation,
  handleExternalWindowOpen,
  isExternalUrlAllowed,
} from '../../electron/main/external-url-policy.ts';

describe('typed Electron IPC contract', () => {
  it('keeps shared, main, and preload invoke channels in lockstep without literals', () => {
    const shared = readFileSync('electron/shared.ts', 'utf8');
    const main = readFileSync('electron/main/index.ts', 'utf8');
    const preload = readFileSync('electron/preload/index.ts', 'utf8');
    const contractBlock = shared.match(
      /export const IPC_CHANNELS = \{(?<body>[\s\S]*?)\} as const;/
    )?.groups?.body;
    assert.ok(contractBlock);
    const contract = new Set(
      [...contractBlock.matchAll(/^\s*([A-Z0-9_]+):/gm)].map((match) => match[1])
    );
    contract.delete('SERVER_STATUS');
    const mainChannels = new Set(
      [...main.matchAll(/handleIpc\(IPC_CHANNELS\.([A-Z0-9_]+)/g)].map(
        (match) => match[1]
      )
    );
    const preloadChannels = new Set(
      [...preload.matchAll(/invokeIpc\(\s*IPC_CHANNELS\.([A-Z0-9_]+)/g)].map(
        (match) => match[1]
      )
    );
    assert.deepEqual([...mainChannels].sort(), [...contract].sort());
    assert.deepEqual([...preloadChannels].sort(), [...contract].sort());
    assert.doesNotMatch(main, /handleIpc\(\s*['"]/);
    assert.doesNotMatch(preload, /(?:ipcRenderer\.invoke|invokeIpc)\(\s*['"]/);
    assert.match(main, /send\(IPC_CHANNELS\.SERVER_STATUS/);
    assert.match(preload, /on\(IPC_CHANNELS\.SERVER_STATUS/);
  });
});

describe('canonical filesystem authorization', () => {
  it('distinguishes regular files and directories and rejects lexical escapes', () => {
    const parent = mkdtempSync(path.join(tmpdir(), 'nekovr-auth-'));
    const root = path.join(parent, 'managed');
    const sibling = path.join(parent, 'managed-sibling');
    const nested = path.join(root, 'nested');
    mkdirSync(root);
    mkdirSync(sibling);
    mkdirSync(nested);
    const file = path.join(root, 'allowed.txt');
    const nestedFile = path.join(nested, 'nested.txt');
    const siblingFile = path.join(sibling, 'outside.txt');
    writeFileSync(file, 'allowed');
    writeFileSync(nestedFile, 'nested');
    writeFileSync(siblingFile, 'outside');

    const authorize = (requestedPath: string, directChild = false) =>
      authorizeExistingPath({
        requestedPath,
        allowedRoots: [root],
        targetTypes: ['regular-file'],
        directChild,
      });
    assert.equal(authorize(file), file);
    assert.equal(authorize(nestedFile), nestedFile);
    assert.equal(authorize(nestedFile, true), null);
    assert.equal(authorize(siblingFile), null, 'sibling-prefix escape');
    assert.equal(
      authorize(path.join(root, '..', 'managed-sibling', 'outside.txt')),
      null
    );
    assert.equal(authorize(path.join(root, 'missing.txt')), null);
    assert.equal(authorize(root), null, 'directory is not a regular file');
    assert.equal(
      authorizeExistingPath({
        requestedPath: root,
        allowedRoots: [root],
        targetTypes: ['directory'],
      }),
      root
    );
  });

  it('deterministically rejects symlink, junction, and canonical parent-link escapes', () => {
    const root = path.resolve('virtual-managed-root');
    const outside = path.resolve('virtual-outside');
    const link = path.join(root, 'file-link');
    const junction = path.join(root, 'junction');
    const escaped = path.join(root, 'linked-parent', 'secret.txt');
    const node = (kind: 'directory' | 'file' | 'link') => ({
      isDirectory: () => kind === 'directory',
      isFile: () => kind === 'file',
      isSymbolicLink: () => kind === 'link',
    });
    const filesystem: FilesystemAuthorizationAdapter = {
      realpath: (target) => {
        if (target === escaped) return path.join(outside, 'secret.txt');
        return target;
      },
      lstat: (target) => {
        if (target === root || target === outside) return node('directory');
        if (target === link || target === junction) return node('link');
        return node('file');
      },
    };
    const request = (requestedPath: string) =>
      authorizeExistingPath(
        {
          requestedPath,
          allowedRoots: [root],
          targetTypes: ['regular-file', 'directory'],
        },
        filesystem
      );
    assert.equal(request(link), null);
    assert.equal(request(junction), null);
    assert.equal(request(escaped), null);
  });
});

describe('central external URL and navigation policy', () => {
  it('accepts only explicit scheme, host, and path boundaries', () => {
    for (const url of [
      'https://slimevr.dev/docs',
      'https://docs.slimevr.dev/server',
      'https://github.com/SlimeVR/SlimeVR-Server',
      'https://discord.gg/slimevr',
      'steam://open/main',
      'ms-settings:network',
    ]) {
      assert.equal(isExternalUrlAllowed(url), true, url);
    }
    for (const url of [
      'http://slimevr.dev/docs',
      'https://evilslimevr.dev/docs',
      'https://slimevr.dev.evil.example/docs',
      'https://user@slimevr.dev/docs',
      'https://slimevr.dev:444/docs',
      'https://github.com/SlimeVRevil/repository',
      'https://github.com/SlimeVR.evil/repository',
      'https://slimevr.dev/%2e%2e/secret',
      'https://slimevr.dev/docs%2f..%2fsecret',
      'javascript:alert(1)',
      'steam://open/%2e%2e/secret',
    ]) {
      assert.equal(isExternalUrlAllowed(url), false, url);
    }
  });

  it('always blocks renderer navigation and opens only approved URLs externally', async () => {
    const opened: string[] = [];
    let prevented = false;
    handleExternalNavigation(
      { preventDefault: () => (prevented = true) },
      'https://slimevr.dev/docs',
      (url) => {
        opened.push(url);
      }
    );
    const denied = handleExternalWindowOpen('https://evil.example', (url) => {
      opened.push(url);
    });
    await Promise.resolve();
    assert.equal(prevented, true);
    assert.deepEqual(denied, { action: 'deny' });
    assert.deepEqual(opened, ['https://slimevr.dev/docs']);
  });
});
