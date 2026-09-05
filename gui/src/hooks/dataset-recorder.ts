import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import {
  CancelDatasetRecordingRequestT,
  DatasetActionResponseT,
  DatasetDeleteRequestT,
  DatasetExportRequestT,
  DatasetListRequestT,
  DatasetListResponseT,
  DatasetOperation,
  DatasetRecordingState,
  DatasetRecordingStatusRequestT,
  DatasetRecordingStatusResponseT,
  DatasetRecoverRequestT,
  DatasetRecoveryAction,
  DatasetRevealRequestT,
  DatasetValidateRequestT,
  DatasetValidateResponseT,
  RpcMessage,
  StartDatasetRecordingRequestT,
  StopDatasetRecordingRequestT,
} from 'solarxr-protocol';
import { useWebsocketAPI } from './websocket-api';
import { useInterval } from './timeout';
import {
  datasetRecorderReducer,
  initialDatasetRecorderState,
  isAuthorizedDatasetDesktopAction,
} from './dataset-recorder-state';

type PendingRequest = {
  resolve: (response: DatasetActionResponseT | DatasetRecordingStatusResponseT) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
};

const text = (value: string | Uint8Array | null | undefined): string =>
  typeof value === 'string' ? value : '';

export function useDatasetRecorder() {
  const { useRPCPacket, sendRPCPacket, isConnected } = useWebsocketAPI();
  const [state, dispatch] = useReducer(
    datasetRecorderReducer,
    undefined,
    initialDatasetRecorderState
  );
  const [validationResults, setValidationResults] = useState<
    Record<string, DatasetValidateResponseT>
  >({});
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [operationError, setOperationError] = useState('');
  const pending = useRef(new Map<number, PendingRequest>());

  const request = useCallback(
    (
      type: RpcMessage,
      payload: Parameters<typeof sendRPCPacket>[1]
    ): Promise<DatasetActionResponseT | DatasetRecordingStatusResponseT> =>
      new Promise((resolve, reject) => {
        setOperationError('');
        const transactionId = sendRPCPacket(type, payload);
        if (transactionId === null) {
          const error = new Error('Server is not connected');
          setOperationError(error.message);
          reject(error);
          return;
        }
        const timeout = setTimeout(() => {
          pending.current.delete(transactionId);
          const error = new Error('Dataset operation timed out');
          setOperationError(error.message);
          reject(error);
        }, 15_000);
        pending.current.set(transactionId, { resolve, reject, timeout });
      }),
    [sendRPCPacket]
  );

  const settle = useCallback(
    (
      transactionId: number | undefined,
      response: DatasetActionResponseT | DatasetRecordingStatusResponseT
    ) => {
      if (transactionId === undefined) return;
      const entry = pending.current.get(transactionId);
      if (!entry) return;
      clearTimeout(entry.timeout);
      pending.current.delete(transactionId);
      setOperationError('');
      entry.resolve(response);
    },
    []
  );

  const refreshStatus = useCallback(() => {
    if (isConnected)
      sendRPCPacket(
        RpcMessage.DatasetRecordingStatusRequest,
        new DatasetRecordingStatusRequestT()
      );
  }, [isConnected, sendRPCPacket]);

  const refreshList = useCallback(() => {
    if (!isConnected) return;
    setIsRefreshing(true);
    sendRPCPacket(RpcMessage.DatasetListRequest, new DatasetListRequestT());
  }, [isConnected, sendRPCPacket]);

  useRPCPacket(
    RpcMessage.DatasetRecordingStatusResponse,
    (response: DatasetRecordingStatusResponseT, transactionId) => {
      dispatch({ type: 'status', status: response });
      settle(transactionId, response);
    }
  );
  useRPCPacket(RpcMessage.DatasetListResponse, (response: DatasetListResponseT) => {
    dispatch({ type: 'list', response });
    setIsRefreshing(false);
  });
  useRPCPacket(
    RpcMessage.DatasetValidateResponse,
    (response: DatasetValidateResponseT) => {
      const sessionId = text(response.sessionId);
      if (sessionId)
        setValidationResults((previous) => ({
          ...previous,
          [sessionId]: response,
        }));
    }
  );
  useRPCPacket(
    RpcMessage.DatasetActionResponse,
    (response: DatasetActionResponseT, transactionId) => {
      dispatch({ type: 'action', response });
      settle(transactionId, response);
      refreshList();
    }
  );

  useEffect(() => {
    dispatch({ type: 'connection', connected: isConnected });
    if (isConnected) {
      refreshStatus();
      refreshList();
    } else {
      const hadPendingRequests = pending.current.size > 0;
      for (const entry of pending.current.values()) {
        clearTimeout(entry.timeout);
        entry.reject(new Error('Server disconnected'));
      }
      pending.current.clear();
      if (hadPendingRequests) setOperationError('Server disconnected');
    }
  }, [isConnected, refreshList, refreshStatus]);

  useEffect(
    () => () => {
      for (const entry of pending.current.values()) clearTimeout(entry.timeout);
      pending.current.clear();
    },
    []
  );

  const active =
    state.status.state === DatasetRecordingState.STARTING ||
    state.status.state === DatasetRecordingState.RECORDING ||
    state.status.state === DatasetRecordingState.FINALIZING;
  useInterval(refreshStatus, active ? 500 : null);

  const startRecording = useCallback(
    (profile = 0, consent = false, subjectPseudonym = '', hashIds = false) =>
      request(
        RpcMessage.StartDatasetRecordingRequest,
        new StartDatasetRecordingRequestT(
          '',
          profile,
          consent,
          subjectPseudonym,
          hashIds
        )
      ),
    [request]
  );
  const stopRecording = useCallback(
    (sessionId: string = text(state.status.sessionId), timeoutSeconds = 30) =>
      request(
        RpcMessage.StopDatasetRecordingRequest,
        new StopDatasetRecordingRequestT(timeoutSeconds, sessionId)
      ),
    [request, state.status.sessionId]
  );
  const cancelRecording = useCallback(
    (sessionId: string = text(state.status.sessionId)) =>
      request(
        RpcMessage.CancelDatasetRecordingRequest,
        new CancelDatasetRecordingRequestT(sessionId)
      ),
    [request, state.status.sessionId]
  );
  const validateSession = useCallback(
    (sessionId: string) =>
      sendRPCPacket(
        RpcMessage.DatasetValidateRequest,
        new DatasetValidateRequestT(sessionId)
      ),
    [sendRPCPacket]
  );
  const recoverSession = useCallback(
    (sessionId: string, quarantine = false) =>
      request(
        RpcMessage.DatasetRecoverRequest,
        new DatasetRecoverRequestT(
          sessionId,
          quarantine,
          quarantine ? DatasetRecoveryAction.QUARANTINE : DatasetRecoveryAction.RECOVER
        )
      ),
    [request]
  );
  const deleteSession = useCallback(
    (sessionId: string) =>
      request(RpcMessage.DatasetDeleteRequest, new DatasetDeleteRequestT(sessionId)),
    [request]
  );
  const exportSession = useCallback(
    async (sessionId: string) => {
      const acknowledged = await request(
        RpcMessage.DatasetExportRequest,
        new DatasetExportRequestT(sessionId, '')
      );
      if (
        !(acknowledged instanceof DatasetActionResponseT) ||
        !isAuthorizedDatasetDesktopAction(
          acknowledged,
          DatasetOperation.EXPORT,
          sessionId
        )
      )
        return { success: false, error: acknowledged.error || 'Export rejected' };
      if (!window.electronAPI?.exportDataset)
        return { success: false, error: 'Export is available in the desktop app' };
      return window.electronAPI.exportDataset(sessionId);
    },
    [request]
  );
  const revealSession = useCallback(
    async (sessionId: string) => {
      const acknowledged = await request(
        RpcMessage.DatasetRevealRequest,
        new DatasetRevealRequestT(sessionId)
      );
      if (
        !(acknowledged instanceof DatasetActionResponseT) ||
        !isAuthorizedDatasetDesktopAction(
          acknowledged,
          DatasetOperation.REVEAL,
          sessionId
        )
      )
        return false;
      return window.electronAPI?.revealDataset
        ? window.electronAPI.revealDataset(sessionId)
        : false;
    },
    [request]
  );

  return {
    status: state.status,
    sessions: state.sessions,
    recoverable: state.recoverable,
    lastAction: state.lastAction,
    operationError,
    validationResults,
    isRefreshing,
    isRecording: state.status.state === DatasetRecordingState.RECORDING,
    isFinalizing: state.status.state === DatasetRecordingState.FINALIZING,
    readinessFindings: state.status.readiness || [],
    hasBlockingErrors: (state.status.readiness || []).some(
      (finding) => finding.severity === 2
    ),
    refreshStatus,
    refreshList,
    startRecording,
    stopRecording,
    cancelRecording,
    validateSession,
    recoverSession,
    deleteSession,
    exportSession,
    revealSession,
  };
}
