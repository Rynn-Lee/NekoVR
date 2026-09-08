import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  AIExecutionProvider,
  AIModelConfigurationStateT,
  AIModelDescriptorT,
  AIModelHistoryEntryT,
  AIModelOperation,
  ModelActionResponseT,
  ModelCatalogResponseT,
  ModelHistoryResponseT,
  ModelRuntimeStatusResponseT,
} from 'solarxr-protocol';
// Node's type-stripping test runner cannot resolve the Vite @ alias.
// eslint-disable-next-line @dword-design/import-alias/prefer-alias
import { aiModelReducer, initialAIModelState } from '../hooks/ai-model-state.ts';

describe('authoritative AI model state reducer', () => {
  it('does not infer active model or provider from an action acknowledgement', () => {
    const action = new ModelActionResponseT();
    action.success = true;
    action.operation = AIModelOperation.LOAD;
    action.modelSha256 = 'a'.repeat(64);
    action.activeProvider = AIExecutionProvider.CUDA;

    const acknowledged = aiModelReducer(initialAIModelState(), {
      type: 'action',
      response: action,
    });
    assert.equal(acknowledged.lastAction, action);
    assert.equal(acknowledged.runtime, null);

    const runtime = new ModelRuntimeStatusResponseT();
    runtime.activeModelSha256 = 'a'.repeat(64);
    runtime.activeProvider = AIExecutionProvider.CUDA;
    runtime.configuration = new AIModelConfigurationStateT();
    runtime.configuration.enabled = true;
    const authoritative = aiModelReducer(acknowledged, {
      type: 'runtime',
      response: runtime,
    });
    assert.equal(authoritative.runtime?.activeProvider, AIExecutionProvider.CUDA);
    assert.equal(authoritative.runtime?.configuration?.enabled, true);
  });

  it('uses only the latest server catalog response as model inventory', () => {
    const first = new AIModelDescriptorT();
    first.modelSha256 = '1'.repeat(64);
    const second = new AIModelDescriptorT();
    second.modelSha256 = '2'.repeat(64);
    let state = aiModelReducer(initialAIModelState(), {
      type: 'catalog',
      response: new ModelCatalogResponseT('', 1, [first]),
    });
    state = aiModelReducer(state, {
      type: 'catalog',
      response: new ModelCatalogResponseT('', 1, [second]),
    });
    assert.deepEqual(
      state.catalog?.models.map((model) => model.modelSha256),
      ['2'.repeat(64)]
    );
  });

  it('accepts recent, pinned and rollback state only from the server history response', () => {
    const entry = new AIModelHistoryEntryT();
    entry.modelSha256 = '3'.repeat(64);
    entry.pinned = true;
    entry.compatible = true;
    const response = new ModelHistoryResponseT();
    response.entries = [entry];
    response.rollbackModelSha256 = '3'.repeat(64);

    const state = aiModelReducer(initialAIModelState(), {
      type: 'history',
      response,
    });
    assert.equal(state.history?.entries[0].pinned, true);
    assert.equal(state.history?.rollbackModelSha256, '3'.repeat(64));
  });
});
