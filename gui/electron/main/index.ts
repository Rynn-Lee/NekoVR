import {
  app,
  BrowserWindow,
  dialog,
  Menu,
  MenuItem,
  nativeImage,
  net,
  protocol,
  screen,
  shell,
  Tray,
} from 'electron';
import { IPC_CHANNELS, ServerStatusEvent } from '../shared';
import path, { dirname, join } from 'path';
import open from 'open';
import trayIcon from '../../../assets/img/ico.png?asset';
import { readFile, stat, mkdir, writeFile } from 'fs/promises';
import { copyFileSync, existsSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { getPlatform, handleIpc, isPortAvailable } from './utils';
import {
  findServerJar,
  findSystemJRE,
  getDatasetsFolder,
  getExeFolder,
  getGuiDataFolder,
  getLogsFolder,
  getServerDataFolder,
  getWindowStateFile,
} from './paths';
import { initStores } from './store';
import { closeLogger, logger } from './logger';
import { resolveManagedDatasetArchive } from './dataset-paths';

import { spawn } from 'node:child_process';
import { discordPresence } from './presence';
import { options } from './cli';

let stores: Awaited<ReturnType<typeof initStores>>;

if (process.platform === 'linux') {
  app.commandLine.appendSwitch('disable-features', 'WaylandWpColorManagerV1');
  app.commandLine.appendSwitch('force-color-profile', 'srgb');
}

app.setPath('userData', getGuiDataFolder());
app.setPath('sessionData', join(getGuiDataFolder(), 'electron'));

protocol.registerSchemesAsPrivileged([
  {
    scheme: 'app',
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true,
    },
  },
]);

let mainWindow: BrowserWindow | null = null;

handleIpc(IPC_CHANNELS.GH_FETCH, async (e, options) => {
  if (options.type === 'fw-releases') {
    return fetch(
      'https://api.github.com/repos/SlimeVR/SlimeVR-Tracker-ESP/releases'
    ).then((res) => res.json());
  }
  if (
    options.type === 'asset' &&
    options.url.startsWith(
      'https://github.com/SlimeVR/SlimeVR-Tracker-ESP/releases/download/'
    )
  ) {
    return fetch(options.url).then((res) => res.json());
  }
  logger.error({ options }, 'attempted to fetch a non-allowlisted URL');
  return null as never;
});

handleIpc(IPC_CHANNELS.DISCORD_PRESENCE, async (e, status) => {
  if (status.enable) {
    if (!discordPresence.state.ready) await discordPresence.connect();
    discordPresence.updateActivity(status.activity, status.iconText);
  } else if (discordPresence.state.ready) {
    discordPresence.destroy();
  }
});

handleIpc(IPC_CHANNELS.GET_FOLDER, (e, folder) => {
  switch (folder) {
    case 'config':
      return getGuiDataFolder();
    case 'logs':
      return getLogsFolder();
    case 'exe':
      return getExeFolder();
    case 'datasets':
      return getDatasetsFolder();
  }
});

handleIpc(IPC_CHANNELS.OPEN_FILE, (e, requestedFile) => {
  const requestedPath = path.resolve(requestedFile);
  const allowedRoots = [
    getServerDataFolder(),
    getGuiDataFolder(),
    getLogsFolder(),
    getDatasetsFolder(),
  ];
  const isAllowed = allowedRoots.some((root) => {
    const relative = path.relative(path.resolve(root), requestedPath);
    return (
      relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
    );
  });
  if (!isAllowed) {
    logger.error({ path: requestedPath }, 'blocked unauthorized path');
    return;
  }
  void shell.openPath(requestedPath);
});

handleIpc(IPC_CHANNELS.REVEAL_DATASET, async (e, sessionId) => {
  const safePath = resolveManagedDatasetArchive(getDatasetsFolder(), sessionId);
  if (!safePath || !existsSync(safePath)) {
    logger.error(
      { sessionId },
      'blocked unauthorized or missing dataset reveal request'
    );
    return false;
  }
  shell.showItemInFolder(safePath);
  return true;
});

handleIpc(IPC_CHANNELS.EXPORT_DATASET, async (e, sessionId) => {
  const safePath = resolveManagedDatasetArchive(getDatasetsFolder(), sessionId);
  if (!safePath || !existsSync(safePath)) {
    logger.error({ sessionId }, 'dataset file not found or unauthorized for export');
    return { success: false, error: 'Dataset file not found or unauthorized' };
  }
  const defaultFilename = path.basename(safePath);
  const result = await dialog.showSaveDialog(mainWindow!, {
    title: 'Export Dataset Archive',
    defaultPath: defaultFilename,
    filters: [{ name: 'NekoVR Dataset (*.nvrdata)', extensions: ['nvrdata'] }],
  });
  if (result.canceled || !result.filePath) {
    return { success: false, error: 'Export cancelled by user' };
  }
  try {
    copyFileSync(safePath, result.filePath);
    return { success: true, path: result.filePath };
  } catch (err: unknown) {
    logger.error(err, 'Failed to copy exported dataset');
    const msg = err instanceof Error ? err.message : 'Failed to export dataset';
    return { success: false, error: msg };
  }
});

handleIpc(IPC_CHANNELS.OPEN_URL, (e, url) => {
  const allowedUrls = [
    /^steam:\/\//,
    /^ms-settings:network$/,
    /^https:\/\/(?:.+\.)?slimevr\.dev(?:\/.*)?$/,
    /^https:\/\/github\.com\/SlimeVR(?:\/.*)?$/,
    /^https:\/\/discord\.gg\/slimevr$/,
  ];
  if (allowedUrls.some((allowed) => allowed.test(url))) void open(url);
  else logger.error({ url }, 'attempted to open non-allowlisted URL');
});

handleIpc(IPC_CHANNELS.OS_STATS, async () => {
  return {
    type: getPlatform(),
  };
});

handleIpc(IPC_CHANNELS.LOG, (e, type, ...args) => {
  const message = args.map(String).join(' ');
  if (type === 'error') logger.error(message);
  else if (type === 'warn') logger.warn(message);
  else logger.info(message);
});

handleIpc(IPC_CHANNELS.STORAGE, async (e, { type, method, key, value }) => {
  const store = stores[type];
  if (!store) throw new Error(`Storage type ${type} not found`);
  switch (method) {
    case 'get':
      return store.get(key!);
    case 'set':
      return store.set(key!, value);
    case 'delete':
      return store.delete(key!);
    case 'save':
      return store.save();
  }
});

handleIpc(IPC_CHANNELS.I18N_OVERRIDE, async () => {
  const overrideFile = join(getServerDataFolder(), 'override.ftl');
  const exists = await stat(overrideFile)
    .then(() => true)
    .catch(() => false);
  if (!exists) return false;
  return readFile(overrideFile, { encoding: 'utf-8' });
});

handleIpc(IPC_CHANNELS.IS_STEAM, () => options.steam);

const defaultWindowState: {
  width: number;
  height: number;
  x?: number;
  y?: number;
} = {
  width: 1289.0,
  height: 709.0,
  x: undefined,
  y: undefined,
};

const windowState = await readFile(getWindowStateFile(), {
  encoding: 'utf-8',
})
  .then((data) => JSON.parse(data))
  .catch(() => {
    logger.error('Failed to load window state, using defaults');
    return defaultWindowState;
  });

const MIN_WIDTH = 393;
const MIN_HEIGHT = 667;

function validateWindowState(state: typeof defaultWindowState) {
  if (state.x === undefined || state.y === undefined) {
    return state;
  }

  const displays = screen.getAllDisplays();

  const isVisible = displays.some((display) => {
    return (
      state.x! >= display.bounds.x &&
      state.y! >= display.bounds.y &&
      state.x! + state.width <= display.bounds.x + display.bounds.width &&
      state.y! + state.height <= display.bounds.height
    );
  });

  if (!isVisible || state.width < MIN_WIDTH || state.height < MIN_HEIGHT) {
    return defaultWindowState;
  }

  return state;
}

const saveWindowState = async () => {
  await mkdir(dirname(getWindowStateFile()), { recursive: true });
  await writeFile(getWindowStateFile(), JSON.stringify(windowState), {
    encoding: 'utf-8',
  });
};

function createWindow() {
  const validatedState = validateWindowState(windowState);

  const icon = nativeImage.createFromPath(trayIcon);

  mainWindow = new BrowserWindow({
    width: validatedState.width,
    height: validatedState.height,
    x: validatedState.x,
    y: validatedState.y,
    minHeight: MIN_HEIGHT,
    minWidth: MIN_WIDTH,
    movable: true,
    frame: false,
    roundedCorners: true,
    icon,
    backgroundColor: '#141417',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      nodeIntegration: false,
      contextIsolation: true,
      devTools: true,
    },
  });

  mainWindow.webContents.on('before-input-event', (event, input) => {
    if (input.key === 'F12' || (input.control && input.shift && input.key === 'I')) {
      mainWindow?.webContents.toggleDevTools();
    }
  });

  if (process.env.ELECTRON_RENDERER_URL) {
    mainWindow.loadURL(process.env.ELECTRON_RENDERER_URL);
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadURL('app://./index.html');
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  handleIpc('window-actions', (e, action) => {
    if (mainWindow === null) return;
    switch (action) {
      case 'close':
        mainWindow.close();
        break;
      case 'hide':
        mainWindow.hide();
        break;
      case 'minimize':
        mainWindow.minimize();
        break;
      case 'toggle-maximize':
        if (mainWindow.isMaximized()) mainWindow.unmaximize();
        else mainWindow.maximize();
        break;
    }
  });

  handleIpc('open-dialog', (e, options) => dialog.showOpenDialog(options));
  handleIpc('save-dialog', (e, options) => dialog.showSaveDialog(options));

  const tray = new Tray(icon);
  tray.setToolTip('NekoVR');
  tray.on('click', () => {
    mainWindow?.show();
  });
  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Show',
      click: () => {
        mainWindow?.show();
      },
    },
    {
      label: 'Hide',
      click: () => {
        mainWindow?.hide();
      },
    },
    { role: 'quit' },
  ]);
  tray.setContextMenu(contextMenu);

  const updateWindowState = () => {
    if (!mainWindow) return;
    const bounds = mainWindow.getBounds();
    windowState.width = bounds.width;
    windowState.height = bounds.height;
    windowState.x = bounds.x;
    windowState.y = bounds.y;
  };

  mainWindow.on('resize', updateWindowState);
  mainWindow.on('move', updateWindowState);
  mainWindow.on('minimize', updateWindowState);
  mainWindow.on('maximize', updateWindowState);

  mainWindow.webContents.on('context-menu', (event, params) => {
    const menu = new Menu();

    menu.append(
      new MenuItem({
        label: 'Inspect Element',
        click: () => {
          mainWindow?.webContents.inspectElement(params.x, params.y);
        },
      })
    );

    menu.append(new MenuItem({ type: 'separator' }));
    menu.append(new MenuItem({ label: 'Copy', role: 'copy' }));
    menu.append(new MenuItem({ label: 'Paste', role: 'paste' }));

    if (mainWindow) menu.popup({ window: mainWindow });
  });
}

const checkEnvironmentVariables = () => {
  const disallowedVars = ['_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS'];

  const set = disallowedVars.filter((env) => !!process.env[env]);
  if (set.length > 0) {
    dialog.showErrorBox(
      'NekoVR',
      `You have environment variables ${set.join(', ')} set, which may cause the NekoVR Server to fail to launch properly.`
    );
    app.quit();
  }
};

const isServerRunning = async () => !(await isPortAvailable(21110));

const spawnServer = async () => {
  if (options.skipServerIfRunning && (await isServerRunning())) {
    logger.info(
      { skipServerIfRunning: options.skipServerIfRunning },
      'Server is already running, skipping server start'
    );
    return;
  }

  const serverJar = findServerJar();
  if (!serverJar) {
    logger.info('server jar not found, skipping');
    return;
  }
  const sharedDir = dirname(serverJar);
  const javaBin = await findSystemJRE(sharedDir);
  if (!javaBin) {
    dialog.showErrorBox(
      'NekoVR',
      'Unable to find a compatible Java version, please download Java 17 or higher'
    );
    app.quit();
    return;
  }

  logger.info({ javaBin, serverJar }, 'Found Java and server jar');
  const platform = getPlatform();

  const serverArgs = ['-Xmx128M', '-jar', serverJar];
  if (options.steam) serverArgs.push('--steam');
  if (options.install) serverArgs.push('--install');
  if (options.noUdev) serverArgs.push('--no-udev');

  serverArgs.push('run');

  const serverProcess = spawn(javaBin, serverArgs, {
    cwd: sharedDir,
    shell: false,
    env: {
      ...process.env,
      NEKOVR_DATASETS_DIR: getDatasetsFolder(),
      ...(platform === 'windows'
        ? {
            APPDATA: app.getPath('appData'),
            LOCALAPPDATA: process.env['USERPROFILE']
              ? path.join(process.env['USERPROFILE'], 'AppData', 'Local')
              : undefined,
          }
        : {}),
    },
  });

  const sendToWindow = (event: ServerStatusEvent) => {
    if (mainWindow && !mainWindow.webContents.isDestroyed()) {
      mainWindow.webContents.send(IPC_CHANNELS.SERVER_STATUS, event);
    }
  };

  serverProcess.stdout?.on('data', (message) => {
    sendToWindow({ message: message.toString(), type: 'stdout' });
  });

  serverProcess.stderr?.on('data', (message) => {
    sendToWindow({ message: message.toString(), type: 'stderr' });
  });

  serverProcess.on('error', (err) => {
    logger.error(err, 'Failed to spawn server process');
  });

  serverProcess.on('exit', () => {
    logger.info('Server process exiting');
  });

  return {
    close: () => {
      serverProcess.kill('SIGINT');
    },
    waitForExit: () =>
      new Promise<void>((resolve) => {
        if (serverProcess.exitCode !== null) return resolve();
        serverProcess.on('exit', () => resolve());
      }),
  };
};

const createFolders = async () => {
  await mkdir(getServerDataFolder(), { recursive: true });
  await mkdir(getGuiDataFolder(), { recursive: true });
  await mkdir(getDatasetsFolder(), { recursive: true });
};

let isQuitting = false;

app.whenReady().then(async () => {
  protocol.handle('app', (request) => {
    try {
      const url = new URL(request.url);
      const rendererRoot = path.resolve(__dirname, '../renderer');
      const requested = decodeURIComponent(url.pathname).replace(/^[/\\]+/, '');
      const filePath = path.resolve(rendererRoot, requested || 'index.html');
      const relative = path.relative(rendererRoot, filePath);
      if (relative.startsWith('..') || path.isAbsolute(relative)) {
        logger.error({ url: request.url }, 'blocked app protocol path traversal');
        return new Response('Forbidden', { status: 403 });
      }
      return net.fetch(pathToFileURL(filePath).toString(), {
        headers: request.headers,
      });
    } catch (err) {
      logger.error({ url: request.url, err }, 'Failed to serve app protocol file');
      return new Response('Not Found', { status: 404 });
    }
  });

  try {
    await createFolders();
  } catch (err) {
    logger.error(err, 'Failed to initialize stores');
    dialog.showErrorBox(
      'NekoVR',
      'Failed to initialize application storage. Please make sure the application has write permissions to its data folder.'
    );
    app.quit();
    return;
  }

  stores = await initStores();
  checkEnvironmentVariables();
  const server = await spawnServer();

  createWindow();

  logger.info('NekoVR started!');

  app.on('window-all-closed', () => {
    app.quit();
  });

  app.on('before-quit', async (event) => {
    if (isQuitting) return;
    isQuitting = true;
    event.preventDefault();
    logger.info('App quitting, saving...');
    server?.close();
    await server?.waitForExit();
    await stores.settings.save();
    await stores.cache.save();
    discordPresence.destroy();
    await saveWindowState();
    await closeLogger();
    app.exit(0);
  });
});
