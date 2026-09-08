import { useCallback, useEffect, useReducer, useRef } from 'react';
import {
  AIExecutionProvider,
  ModelActionResponseT,
  ModelCatalogRefreshRequestT,
  ModelCatalogRequestT,
  ModelCatalogResponseT,
  ModelConfigureRequestT,
  ModelDownloadRequestT,
  ModelImportRequestT,
  ModelHistoryRequestT,
  ModelHistoryResponseT,
  ModelLoadRequestT,
  ModelOperationProgressT,
  ModelRuntimeStatusRequestT,
  ModelRuntimeStatusResponseT,
  ModelUnloadRequestT,
  ModelPinRequestT,
  ModelRollbackRequestT,
  ModelSwitchRequestT,
  RpcMessage,
} from 'solarxr-protocol';
import { useWebsocketAPI } from './websocket-api';
import { useInterval } from './timeout';
import { aiModelReducer, initialAIModelState } from './ai-model-state';

type ModelResponse =
  | ModelActionResponseT
  | ModelCatalogResponseT
  | ModelHistoryResponseT;
type PendingRequest = {
  resolve: (response: ModelResponse) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
};

export function useAIModelControl() {
  const { useRPCPacket, sendRPCPacket, isConnected } = useWebsocketAPI();
  const [state, dispatch] = useReducer(aiModelReducer, undefined, initialAIModelState);
  const pending = useRef(new Map<number, PendingRequest>());
  const requestSequence = useRef(0);

  const nextRequestId = useCallback(
    () => `ai-model-${Date.now()}-${++requestSequence.current}`,
    []
  );

  const sendRequest = useCallback(
    (
      type: RpcMessage,
      payload: Parameters<typeof sendRPCPacket>[1]
    ): Promise<ModelResponse> =>
      new Promise((resolve, reject) => {
        dispatch({ type: 'clear-error' });
        const transactionId = sendRPCPacket(type, payload);
        if (transactionId === null) {
          const error = new Error('AI_MODEL_CLIENT_NOT_CONNECTED');
          dispatch({ type: 'client-error', error: 'AI_MODEL_CLIENT_NOT_CONNECTED' });
          reject(error);
          return;
        }
        const timeout = setTimeout(() => {
          pending.current.delete(transactionId);
          const error = new Error('AI_MODEL_CLIENT_TIMEOUT');
          dispatch({ type: 'client-error', error: 'AI_MODEL_CLIENT_TIMEOUT' });
          reject(error);
        }, 30_000);
        pending.current.set(transactionId, { resolve, reject, timeout });
      }),
    [sendRPCPacket]
  );

  const settle = useCallback(
    (transactionId: number | undefined, response: ModelResponse) => {
      if (transactionId === undefined) return;
      const entry = pending.current.get(transactionId);
      if (!entry) return;
      clearTimeout(entry.timeout);
      pending.current.delete(transactionId);
      entry.resolve(response);
    },
    []
  );

  const refreshRuntime = useCallback(() => {
    if (!isConnected) return;
    sendRPCPacket(
      RpcMessage.ModelRuntimeStatusRequest,
      new ModelRuntimeStatusRequestT(nextRequestId())
    );
  }, [isConnected, nextRequestId, sendRPCPacket]);

  const refreshCatalogState = useCallback(() => {
    if (!isConnected) return;
    sendRPCPacket(
      RpcMessage.ModelCatalogRequest,
      new ModelCatalogRequestT(nextRequestId())
    );
  }, [isConnected, nextRequestId, sendRPCPacket]);

  const refreshHistory = useCallback(() => {
    if (!isConnected) return;
    sendRPCPacket(
      RpcMessage.ModelHistoryRequest,
      new ModelHistoryRequestT(nextRequestId())
    );
  }, [isConnected, nextRequestId, sendRPCPacket]);

  useRPCPacket(
    RpcMessage.ModelHistoryResponse,
    (response: ModelHistoryResponseT, transactionId) => {
      dispatch({ type: 'history', response });
      settle(transactionId, response);
    }
  );
  useRPCPacket(
    RpcMessage.ModelCatalogResponse,
    (response: ModelCatalogResponseT, transactionId) => {
      dispatch({ type: 'catalog', response });
      settle(transactionId, response);
    }
  );
  useRPCPacket(
    RpcMessage.ModelRuntimeStatusResponse,
    (response: ModelRuntimeStatusResponseT) => dispatch({ type: 'runtime', response })
  );
  useRPCPacket(RpcMessage.ModelOperationProgress, (response: ModelOperationProgressT) =>
    dispatch({ type: 'progress', response })
  );
  useRPCPacket(
    RpcMessage.ModelActionResponse,
    (response: ModelActionResponseT, transactionId) => {
      dispatch({ type: 'action', response });
      settle(transactionId, response);
      refreshRuntime();
      refreshCatalogState();
      refreshHistory();
    }
  );

  useEffect(() => {
    dispatch({ type: 'connection', connected: isConnected });
    if (isConnected) {
      refreshRuntime();
      refreshCatalogState();
      refreshHistory();
      return;
    }
    const hadPending = pending.current.size > 0;
    for (const entry of pending.current.values()) {
      clearTimeout(entry.timeout);
      entry.reject(new Error('AI_MODEL_CLIENT_DISCONNECTED'));
    }
    pending.current.clear();
    if (hadPending)
      dispatch({ type: 'client-error', error: 'AI_MODEL_CLIENT_DISCONNECTED' });
  }, [isConnected, refreshCatalogState, refreshHistory, refreshRuntime]);

  useEffect(
    () => () => {
      for (const entry of pending.current.values()) clearTimeout(entry.timeout);
      pending.current.clear();
    },
    []
  );

  useInterval(refreshRuntime, isConnected ? 1_000 : null);

  const configure = useCallback(
    (configuration: ModelConfigureRequestT) => {
      configuration.requestId = nextRequestId();
      return sendRequest(RpcMessage.ModelConfigureRequest, configuration);
    },
    [nextRequestId, sendRequest]
  );
  const load = useCallback(
    (modelSha256: string, provider: AIExecutionProvider = AIExecutionProvider.AUTO) =>
      sendRequest(
        RpcMessage.ModelLoadRequest,
        new ModelLoadRequestT(nextRequestId(), modelSha256, provider)
      ),
    [nextRequestId, sendRequest]
  );
  const unload = useCallback(
    () =>
      sendRequest(
        RpcMessage.ModelUnloadRequest,
        new ModelUnloadRequestT(nextRequestId())
      ),
    [nextRequestId, sendRequest]
  );
  const download = useCallback(
    (modelSha256: string) =>
      sendRequest(
        RpcMessage.ModelDownloadRequest,
        new ModelDownloadRequestT(nextRequestId(), modelSha256)
      ),
    [nextRequestId, sendRequest]
  );
  const refreshCatalog = useCallback(
    (catalogUrl = '') =>
      sendRequest(
        RpcMessage.ModelCatalogRefreshRequest,
        new ModelCatalogRefreshRequestT(nextRequestId(), catalogUrl)
      ),
    [nextRequestId, sendRequest]
  );
  const importModel = useCallback(
    (modelPath: string, metadataPath: string, expectedModelSha256 = '') =>
      sendRequest(
        RpcMessage.ModelImportRequest,
        new ModelImportRequestT(
          nextRequestId(),
          modelPath,
          metadataPath,
          expectedModelSha256
        )
      ),
    [nextRequestId, sendRequest]
  );
  const pin = useCallback(
    (modelSha256: string, pinned: boolean) =>
      sendRequest(
        RpcMessage.ModelPinRequest,
        new ModelPinRequestT(nextRequestId(), modelSha256, pinned)
      ),
    [nextRequestId, sendRequest]
  );
  const switchModel = useCallback(
    (modelSha256: string, provider: AIExecutionProvider) =>
      sendRequest(
        RpcMessage.ModelSwitchRequest,
        new ModelSwitchRequestT(nextRequestId(), modelSha256, provider)
      ),
    [nextRequestId, sendRequest]
  );
  const rollback = useCallback(
    (provider: AIExecutionProvider = AIExecutionProvider.AUTO) =>
      sendRequest(
        RpcMessage.ModelRollbackRequest,
        new ModelRollbackRequestT(nextRequestId(), provider)
      ),
    [nextRequestId, sendRequest]
  );

  return {
    connected: state.connected,
    catalog: state.catalog,
    history: state.history,
    historyEntries: state.history?.entries ?? [],
    rollbackModelSha256: state.history?.rollbackModelSha256 ?? '',
    models: state.catalog?.models ?? [],
    runtime: state.runtime,
    configuration: state.runtime?.configuration ?? null,
    activeModelSha256: state.runtime?.activeModelSha256 ?? '',
    activeProvider: state.runtime?.activeProvider ?? AIExecutionProvider.AUTO,
    progress: state.progress,
    lastAction: state.lastAction,
    operationError: state.operationError,
    refreshRuntime,
    refreshCatalogState,
    refreshCatalog,
    refreshHistory,
    configure,
    load,
    unload,
    download,
    importModel,
    pin,
    switchModel,
    rollback,
  };
}
