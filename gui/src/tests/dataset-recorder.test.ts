import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import {
  DatasetActionResponseT,
  DatasetListResponseT,
  DatasetOperation,
  DatasetRecordingState,
  DatasetRecordingStatusResponseT,
  DatasetSessionInfoT,
} from 'solarxr-protocol';
// Node's type-stripping test runner cannot resolve the Vite @ alias.
// eslint-disable-next-line @dword-design/import-alias/prefer-alias
import {
  datasetRecorderReducer,
  initialDatasetRecorderState,
  isAuthorizedDatasetDesktopAction,
} from '../hooks/dataset-recorder-state.ts';
import {
  resolveManagedDatasetArchive,
  sanitizeDatasetSessionId,
} from '../../electron/main/dataset-paths.ts';

const status = (
  recorderState: DatasetRecordingState,
  version: bigint,
  sessionId = 'session-1'
) => {
  const value = new DatasetRecordingStatusResponseT();
  value.state = recorderState;
  value.statusVersion = version;
  value.sessionId = sessionId;
  return value;
};

describe('dataset recorder production state reducer', () => {
  it('follows start, record, finalize and complete broadcasts in order', () => {
    let state = initialDatasetRecorderState();
    for (const [recorderState, version] of [
      [DatasetRecordingState.STARTING, 1n],
      [DatasetRecordingState.RECORDING, 2n],
      [DatasetRecordingState.FINALIZING, 3n],
      [DatasetRecordingState.COMPLETED, 4n],
    ] as const) {
      state = datasetRecorderReducer(state, {
        type: 'status',
        status: status(recorderState, version),
      });
    }
    assert.equal(state.status.state, DatasetRecordingState.COMPLETED);
    assert.equal(state.status.statusVersion, 4n);
  });

  it('ignores a late status and refreshes authoritative state after reconnect', () => {
    let state = initialDatasetRecorderState();
    state = datasetRecorderReducer(state, {
      type: 'status',
      status: status(DatasetRecordingState.FINALIZING, 8n),
    });
    state = datasetRecorderReducer(state, { type: 'connection', connected: false });
    state = datasetRecorderReducer(state, {
      type: 'status',
      status: status(DatasetRecordingState.RECORDING, 7n),
    });
    assert.equal(state.status.state, DatasetRecordingState.FINALIZING);
    state = datasetRecorderReducer(state, { type: 'connection', connected: true });
    state = datasetRecorderReducer(state, {
      type: 'status',
      status: status(DatasetRecordingState.COMPLETED, 9n),
    });
    assert.equal(state.connected, true);
    assert.equal(state.status.state, DatasetRecordingState.COMPLETED);
  });

  it('applies real inventory and typed operation results', () => {
    const session = new DatasetSessionInfoT();
    session.sessionId = 'session-1';
    let state = datasetRecorderReducer(initialDatasetRecorderState(), {
      type: 'list',
      response: new DatasetListResponseT([session], []),
    });
    const action = new DatasetActionResponseT();
    action.operation = DatasetOperation.REVEAL;
    action.success = true;
    state = datasetRecorderReducer(state, { type: 'action', response: action });
    assert.equal(state.sessions[0].sessionId, 'session-1');
    assert.equal(state.lastAction?.operation, DatasetOperation.REVEAL);
  });

  it('authorizes desktop reveal/export only after a matching path-free server ack', () => {
    const response = new DatasetActionResponseT();
    response.success = true;
    response.operation = DatasetOperation.EXPORT;
    response.sessionId = 'session-1';
    response.path = '';
    assert.equal(
      isAuthorizedDatasetDesktopAction(response, DatasetOperation.EXPORT, 'session-1'),
      true
    );
    response.path = 'C:/renderer-supplied.nvrdata';
    assert.equal(
      isAuthorizedDatasetDesktopAction(response, DatasetOperation.EXPORT, 'session-1'),
      false
    );
  });
});

describe('Electron managed dataset archive resolver', () => {
  it('accepts only a regular archive directly in the managed root', () => {
    const root = mkdtempSync(path.join(tmpdir(), 'nekovr-datasets-'));
    const archive = path.join(root, 'session_123.nvrdata');
    writeFileSync(archive, 'fixture');
    assert.equal(resolveManagedDatasetArchive(root, 'session_123'), archive);
    assert.equal(resolveManagedDatasetArchive(root, '../session_123'), null);
    assert.equal(resolveManagedDatasetArchive(root, archive), null);
    assert.equal(sanitizeDatasetSessionId('session.123'), null);
  });

  it('rejects a symlink that escapes the managed root', (context) => {
    const root = mkdtempSync(path.join(tmpdir(), 'nekovr-datasets-'));
    const outside = path.join(
      mkdtempSync(path.join(tmpdir(), 'nekovr-outside-')),
      'secret.nvrdata'
    );
    writeFileSync(outside, 'fixture');
    try {
      symlinkSync(outside, path.join(root, 'session-link.nvrdata'), 'file');
    } catch (error) {
      context.skip(`File symlinks unavailable: ${String(error)}`);
      return;
    }
    assert.equal(resolveManagedDatasetArchive(root, 'session-link'), null);
  });
});
