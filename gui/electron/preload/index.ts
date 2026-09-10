import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import type { GHGet, GHReturn, IElectronAPI, ServerStatusEvent } from './interface';
import { IPC_CHANNELS, type IpcInvokeMap } from '../shared';

function invokeIpc<K extends keyof IpcInvokeMap>(
  channel: K,
  ...args: Parameters<IpcInvokeMap[K]>
): Promise<Awaited<ReturnType<IpcInvokeMap[K]>>> {
  return ipcRenderer.invoke(channel, ...args);
}

function invokeGh<T extends GHGet>(request: T): Promise<GHReturn[T['type']]> {
  return invokeIpc(IPC_CHANNELS.GH_FETCH, request) as unknown as Promise<
    GHReturn[T['type']]
  >;
}

contextBridge.exposeInMainWorld('electronAPI', {
  onServerStatus: (callback) => {
    const subscription = (_event: IpcRendererEvent, value: ServerStatusEvent) =>
      callback(value);
    ipcRenderer.on(IPC_CHANNELS.SERVER_STATUS, subscription);
    return () => ipcRenderer.removeListener(IPC_CHANNELS.SERVER_STATUS, subscription);
  },
  openUrl: (url) => invokeIpc(IPC_CHANNELS.OPEN_URL, url),
  osStats: () => invokeIpc(IPC_CHANNELS.OS_STATS),
  close: () => invokeIpc(IPC_CHANNELS.WINDOW_ACTIONS, 'close'),
  hide: () => invokeIpc(IPC_CHANNELS.WINDOW_ACTIONS, 'hide'),
  minimize: () => invokeIpc(IPC_CHANNELS.WINDOW_ACTIONS, 'minimize'),
  toggleMaximize: () => invokeIpc(IPC_CHANNELS.WINDOW_ACTIONS, 'toggle-maximize'),
  getStorage: async (type) => {
    return {
      get: async <T>(key: string) =>
        (await invokeIpc(IPC_CHANNELS.STORAGE, {
          type,
          method: 'get',
          key,
        })) as T | undefined,
      set: async (key, value) => {
        await invokeIpc(IPC_CHANNELS.STORAGE, {
          type,
          method: 'set',
          key,
          value,
        });
      },
      delete: async (key) =>
        Boolean(await invokeIpc(IPC_CHANNELS.STORAGE, { type, method: 'delete', key })),
      save: async () =>
        Boolean(await invokeIpc(IPC_CHANNELS.STORAGE, { type, method: 'save' })),
    };
  },
  log: (type, ...args) => invokeIpc(IPC_CHANNELS.LOG, type, ...args),
  i18nOverride: async () => invokeIpc(IPC_CHANNELS.I18N_OVERRIDE),
  showDecorations: () => {},
  setTranslations: () => {},
  openDialog: (options) => invokeIpc(IPC_CHANNELS.OPEN_DIALOG, options),
  saveDialog: (options) => invokeIpc(IPC_CHANNELS.SAVE_DIALOG, options),
  openConfigFolder: async () =>
    invokeIpc(
      IPC_CHANNELS.OPEN_FILE,
      await invokeIpc(IPC_CHANNELS.GET_FOLDER, 'config')
    ),
  openLogsFolder: async () =>
    invokeIpc(IPC_CHANNELS.OPEN_FILE, await invokeIpc(IPC_CHANNELS.GET_FOLDER, 'logs')),
  openDatasetsFolder: async () =>
    invokeIpc(
      IPC_CHANNELS.OPEN_FILE,
      await invokeIpc(IPC_CHANNELS.GET_FOLDER, 'datasets')
    ),
  revealDataset: (sessionId: string) =>
    invokeIpc(IPC_CHANNELS.REVEAL_DATASET, sessionId),
  exportDataset: (sessionId: string) =>
    invokeIpc(IPC_CHANNELS.EXPORT_DATASET, sessionId),
  openFile: (path) => invokeIpc(IPC_CHANNELS.OPEN_FILE, path),
  ghGet: invokeGh,
  setPresence: (options) => invokeIpc(IPC_CHANNELS.DISCORD_PRESENCE, options),
  getInstallDir: () => invokeIpc(IPC_CHANNELS.GET_FOLDER, 'exe'),
  isSteam: () => invokeIpc(IPC_CHANNELS.IS_STEAM),
} satisfies IElectronAPI);
