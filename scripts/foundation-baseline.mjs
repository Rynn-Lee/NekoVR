import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const pnpm = windows ? 'pnpm.cmd' : 'pnpm';
const gradle = join(root, windows ? 'gradlew.bat' : 'gradlew');

function run(command, args, options = {}) {
  process.stdout.write(`\n> ${command} ${args.join(' ')}\n`);
  const result = spawnSync(command, args, {
    cwd: root,
    env: { ...process.env, ...options.env },
    stdio: 'inherit',
    shell: windows && /\.(cmd|bat)$/i.test(command),
  });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function findPython() {
  const configured = process.env.NEKOVR_PYTHON;
  const candidates = [
    configured && { command: configured, prefix: [] },
    {
      command: join(
        root,
        'dataset',
        '.venv',
        windows ? 'Scripts/python.exe' : 'bin/python'
      ),
      prefix: [],
    },
    {
      command: join(
        root,
        '.venv',
        windows ? 'Scripts/python.exe' : 'bin/python'
      ),
      prefix: [],
    },
    {
      command: join(
        root,
        'ml',
        '.venv',
        windows ? 'Scripts/python.exe' : 'bin/python'
      ),
      prefix: [],
    },
    { command: 'python3', prefix: [] },
    { command: 'python', prefix: [] },
    windows && { command: 'py', prefix: ['-3'] },
  ].filter(Boolean);

  for (const candidate of candidates) {
    if (candidate.command.includes('/') || candidate.command.includes('\\')) {
      if (!existsSync(candidate.command)) continue;
    }
    const probe = spawnSync(
      candidate.command,
      [...candidate.prefix, '--version'],
      {
        cwd: root,
        stdio: 'ignore',
        shell: windows && /\.(cmd|bat)$/i.test(candidate.command),
      }
    );
    if (!probe.error && probe.status === 0) return candidate;
  }
  throw new Error(
    'Python 3 was not found; set NEKOVR_PYTHON to a supported interpreter'
  );
}

run(pnpm, ['-C', 'gui', 'test']);
run(pnpm, ['-C', 'gui', 'lint']);
run(pnpm, ['-C', 'gui', 'build']);
run(gradle, [':server:core:test', '--rerun-tasks']);

const fixtureDirectory = mkdtempSync(join(tmpdir(), 'nekovr-kotlin-dataset-'));
try {
  run(gradle, [
    ':server:core:generateDatasetPilots',
    `-PdatasetPilotOutput=${fixtureDirectory}`,
  ]);
  run(gradle, [
    ':server:core:generateDatasetConformanceFixture',
    `-PdatasetConformanceOutput=${fixtureDirectory}`,
  ]);
  const python = findPython();
  run(
    python.command,
    [
      ...python.prefix,
      '-m',
      'unittest',
      'discover',
      '-s',
      'dataset/python/tests',
      '-v',
    ],
    {
      env: {
        NEKOVR_KOTLIN_DATASET_FIXTURES: fixtureDirectory,
        PYTHONDONTWRITEBYTECODE: '1',
      },
    }
  );
} finally {
  const relativeFixture = relative(
    resolve(tmpdir()),
    resolve(fixtureDirectory)
  );
  if (relativeFixture && !relativeFixture.startsWith('..')) {
    rmSync(fixtureDirectory, { recursive: true, force: true });
  }
}

process.stdout.write('\nFoundation verification baseline passed.\n');
