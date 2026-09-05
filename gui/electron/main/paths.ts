import { app } from 'electron';
import path, { join } from 'node:path';
import { getPlatform } from './utils';
import { spawnSync, execSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { options } from './cli';

const javaBin = getPlatform() === 'windows' ? 'java.exe' : 'java';
export const CONFIG_IDENTIFIER = 'dev.nekovr.NekoVR';

export const getGuiDataFolder = () => {
  const platform = getPlatform();

  switch (platform) {
    case 'linux':
      if (process.env['XDG_DATA_HOME'])
        return join(process.env['XDG_DATA_HOME'], CONFIG_IDENTIFIER);
      return join(app.getPath('home'), '.local/share', CONFIG_IDENTIFIER);
    case 'windows':
      return join(app.getPath('appData'), CONFIG_IDENTIFIER);
    case 'macos':
      return join(
        app.getPath('home'),
        'Library/Application Support',
        CONFIG_IDENTIFIER
      );
    case 'unknown':
      throw 'error';
  }
};

export const getServerDataFolder = () => {
  const platform = getPlatform();

  switch (platform) {
    case 'linux':
    case 'windows':
    case 'macos':
      return join(app.getPath('appData'), CONFIG_IDENTIFIER);
    case 'unknown':
      throw 'error';
  }
};

export const getLogsFolder = () => {
  return join(getGuiDataFolder(), 'logs');
};

export const getDatasetsFolder = () => {
  const envOverride = process.env['NEKOVR_DATASETS_DIR'];
  if (envOverride) return path.resolve(envOverride);
  if (!app.isPackaged) {
    return join(process.cwd(), 'datasets');
  }
  return join(getServerDataFolder(), 'datasets');
};

export const getExeFolder = () => {
  return path.dirname(app.getPath('exe'));
};

export const getWindowStateFile = () =>
  join(getServerDataFolder(), '.window-state.json');

const localJavaBin = (sharedDir: string) => {
  const platform = getPlatform();
  switch (platform) {
    case 'macos':
      return join(sharedDir, '../../../../jre/Contents/Home/bin', javaBin);
    default:
      return join(sharedDir, 'jre/bin', javaBin);
  }
};

const javaHomeBin = () => {
  const javaHome = process.env['JAVA_HOME'];
  if (!javaHome) return null;
  const javaHomeJre = join(javaHome, 'bin', javaBin);
  return javaHomeJre;
};

const findJavasInDirectory = (baseDir: string): string[] => {
  const list: string[] = [];
  if (!existsSync(baseDir)) return list;
  try {
    const entries = readdirSync(baseDir, { withFileTypes: true });
    for (const e of entries) {
      if (e.isDirectory()) {
        const bin = path.join(baseDir, e.name, 'bin', javaBin);
        if (existsSync(bin)) list.push(bin);
      }
    }
  } catch {
    // Ignore permission or file system errors
  }
  return list;
};

const checkJavaVersion = (javaExecutable: string): number | null => {
  try {
    const res = spawnSync(javaExecutable, ['-version'], {
      encoding: 'utf8',
      shell: false,
      timeout: 5000,
    });

    const output = ((res.stderr || '') + (res.stdout || '')).trim();
    if (!output && res.status !== 0) return null;

    // Match 'version "23"' or 'version "21.0.2"' or 'version "17.0.1"' or 'openjdk 21.0.1' or 'java 23'
    const match =
      output.match(/version "(?:1\.)?(\d+)/i) ||
      output.match(/(?:openjdk|java)\s+(?:version\s+)?(?:1\.)?(\d+)/i) ||
      output.match(/(\d+)\.\d+\.\d+/);

    if (match && match[1]) {
      const major = parseInt(match[1], 10);
      if (!isNaN(major)) return major;
    }

    if (res.status === 0) return 21;
    return null;
  } catch {
    return null;
  }
};

export const findSystemJRE = async (sharedDir: string): Promise<string | null> => {
  const isWin = getPlatform() === 'windows';
  const candidates: string[] = [];

  // 1. Check local bundled JRE (e.g. installed alongside NekoVR)
  const localJre = localJavaBin(sharedDir);
  if (localJre && existsSync(localJre)) {
    candidates.push(localJre);
  }

  // 2. Check user app directory jre
  const exeJre = join(getExeFolder(), 'jre', 'bin', javaBin);
  if (existsSync(exeJre)) {
    candidates.push(exeJre);
  }

  // 3. Check JAVA_HOME
  const envJre = javaHomeBin();
  if (envJre && existsSync(envJre)) {
    candidates.push(envJre);
  }

  // 4. On Windows: use where.exe and scan standard installation directories
  if (isWin) {
    try {
      const whereOut = execSync('where.exe java', {
        encoding: 'utf8',
        stdio: ['pipe', 'pipe', 'ignore'],
      });
      for (const line of whereOut
        .split(/\r?\n/)
        .map((s) => s.trim())
        .filter(Boolean)) {
        candidates.push(line);
      }
    } catch {
      // Ignore where.exe failure
    }

    const programFilesDirs = [
      'C:\\MyApps\\SlimeVR Server\\jre\\bin\\java.exe',
      'C:\\Program Files\\Java\\jdk-17\\bin\\java.exe',
      'C:\\Program Files\\Java\\jdk-21.0.11\\bin\\java.exe',
      'C:\\Program Files\\Common Files\\Oracle\\Java\\javapath\\java.exe',
      'C:\\Program Files (x86)\\Common Files\\Oracle\\Java\\javapath\\java.exe',
      ...findJavasInDirectory('C:\\Program Files\\Java'),
      ...findJavasInDirectory('C:\\Program Files\\Eclipse Adoptium'),
      ...findJavasInDirectory('C:\\Program Files\\Microsoft'),
      ...findJavasInDirectory('C:\\Program Files\\BellSoft'),
      ...findJavasInDirectory('C:\\Program Files\\Amazon Corretto'),
      ...findJavasInDirectory('C:\\Program Files\\Zulu'),
      ...findJavasInDirectory('C:\\Program Files (x86)\\Java'),
    ];

    for (const p of programFilesDirs) {
      if (existsSync(p)) candidates.push(p);
    }
  } else {
    // Linux and macOS directories
    const unixDirs = [
      ...findJavasInDirectory('/usr/lib/jvm'),
      ...findJavasInDirectory('/Library/Java/JavaVirtualMachines'),
      ...findJavasInDirectory('/opt/homebrew/opt'),
    ];
    for (const p of unixDirs) {
      if (existsSync(p)) candidates.push(p);
    }
  }

  // 5. PATH fallback
  candidates.push('java');

  // Check valid versions >= 17
  for (const p of candidates) {
    if (!p) continue;
    if (p !== 'java' && !existsSync(p)) continue;

    const version = checkJavaVersion(p);
    if (version && version >= 17) {
      return p;
    }
  }

  // Direct fallback: return first existing file
  for (const p of candidates) {
    if (p && p !== 'java' && existsSync(p)) {
      return p;
    }
  }

  return candidates.includes('java') ? 'java' : null;
};

export const findServerJar = () => {
  const paths = [
    options.path ? path.resolve(options.path) : undefined,
    app.isPackaged ? path.resolve(process.resourcesPath) : undefined,
    // AppImage passes the fakeroot in `APPDIR` env var.
    process.env['APPDIR']
      ? path.resolve(join(process.env['APPDIR'], 'usr/share/slimevr/'))
      : undefined,
    path.dirname(app.getPath('exe')),
    // For flatpack container
    path.resolve('/app/share/slimevr/'),
    path.resolve('/usr/share/slimevr/'),

    // For macos on steam
    path.resolve(`${app.getPath('exe')}/../../../../`),
  ];
  return paths
    .filter((p) => !!p)
    .map((p) => join(p!, 'slimevr.jar'))
    .find((p) => existsSync(p));
};
