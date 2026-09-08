import {
  AIModelErrorCode,
  ModelActionResponseT,
  ModelCatalogResponseT,
  ModelHistoryResponseT,
  ModelOperationProgressT,
  ModelRuntimeStatusResponseT,
} from 'solarxr-protocol';

export type AIModelClientState = {
  connected: boolean;
  catalog: ModelCatalogResponseT | null;
  history: ModelHistoryResponseT | null;
  runtime: ModelRuntimeStatusResponseT | null;
  progress: ModelOperationProgressT | null;
  lastAction: ModelActionResponseT | null;
  operationError: string;
};

export type AIModelClientAction =
  | { type: 'connection'; connected: boolean }
  | { type: 'catalog'; response: ModelCatalogResponseT }
  | { type: 'history'; response: ModelHistoryResponseT }
  | { type: 'runtime'; response: ModelRuntimeStatusResponseT }
  | { type: 'progress'; response: ModelOperationProgressT }
  | { type: 'action'; response: ModelActionResponseT }
  | { type: 'client-error'; error: string }
  | { type: 'clear-error' };

export const initialAIModelState = (): AIModelClientState => ({
  connected: false,
  catalog: null,
  history: null,
  runtime: null,
  progress: null,
  lastAction: null,
  operationError: '',
});

const protocolError = (code: number, value: string | Uint8Array | null): string =>
  code === AIModelErrorCode.OK
    ? ''
    : typeof value === 'string'
      ? value
      : 'AI operation failed';

/** State changes are accepted only from unpacked server RPC messages. */
export function aiModelReducer(
  state: AIModelClientState,
  action: AIModelClientAction
): AIModelClientState {
  switch (action.type) {
    case 'connection':
      return { ...state, connected: action.connected };
    case 'catalog':
      return {
        ...state,
        catalog: action.response,
        operationError: protocolError(action.response.errorCode, action.response.error),
      };
    case 'runtime':
      return { ...state, runtime: action.response };
    case 'history':
      return {
        ...state,
        history: action.response,
        operationError: protocolError(action.response.errorCode, action.response.error),
      };
    case 'progress':
      return {
        ...state,
        progress: action.response,
        operationError: protocolError(action.response.errorCode, action.response.error),
      };
    case 'action':
      return {
        ...state,
        lastAction: action.response,
        operationError: protocolError(action.response.errorCode, action.response.error),
      };
    case 'client-error':
      return { ...state, operationError: action.error };
    case 'clear-error':
      return { ...state, operationError: '' };
  }
}
