import {
  DatasetActionResponseT,
  DatasetListResponseT,
  DatasetRecordingState,
  DatasetRecordingStatusResponseT,
  DatasetRecoverableInfoT,
  DatasetSessionInfoT,
} from 'solarxr-protocol';

export type DatasetRecorderClientState = {
  connected: boolean;
  status: DatasetRecordingStatusResponseT;
  sessions: DatasetSessionInfoT[];
  recoverable: DatasetRecoverableInfoT[];
  lastAction: DatasetActionResponseT | null;
};

export type DatasetRecorderClientAction =
  | { type: 'connection'; connected: boolean }
  | { type: 'status'; status: DatasetRecordingStatusResponseT }
  | { type: 'list'; response: DatasetListResponseT }
  | { type: 'action'; response: DatasetActionResponseT };

export const initialDatasetRecorderState = (): DatasetRecorderClientState => ({
  connected: false,
  status: new DatasetRecordingStatusResponseT(DatasetRecordingState.IDLE),
  sessions: [],
  recoverable: [],
  lastAction: null,
});

export function datasetRecorderReducer(
  state: DatasetRecorderClientState,
  action: DatasetRecorderClientAction
): DatasetRecorderClientState {
  switch (action.type) {
    case 'connection':
      return { ...state, connected: action.connected };
    case 'status':
      if (action.status.statusVersion < state.status.statusVersion) return state;
      return { ...state, status: action.status };
    case 'list':
      return {
        ...state,
        sessions: action.response.sessions ?? [],
        recoverable: action.response.recoverable ?? [],
      };
    case 'action':
      return { ...state, lastAction: action.response };
  }
}

export function isAuthorizedDatasetDesktopAction(
  response: DatasetActionResponseT,
  operation: number,
  sessionId: string
): boolean {
  const responseSessionId =
    typeof response.sessionId === 'string' ? response.sessionId : '';
  const responsePath = typeof response.path === 'string' ? response.path : '';
  return (
    response.success &&
    response.operation === operation &&
    responseSessionId === sessionId &&
    responsePath === ''
  );
}
