import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const page = readFileSync(
  new URL('../components/ai-drift/AIDriftPage.tsx', import.meta.url),
  'utf8'
);
const panel = readFileSync(
  new URL('../components/ai-drift/PersonalTrainingPanel.tsx', import.meta.url),
  'utf8'
);
const hook = readFileSync(
  new URL('../hooks/personal-training.ts', import.meta.url),
  'utf8'
);

describe('authoritative personal training surface', () => {
  it('keeps the subscription mounted while navigating tabs', () => {
    assert.ok(
      page.indexOf('usePersonalTraining()') < page.indexOf('activeTab ===')
    );
  });

  it('exposes the full staged job and lifecycle controls', () => {
    for (const stage of [
      'Validate',
      'Index',
      'Preprocess/cache',
      'Split',
      'Train',
      'Evaluate',
      'Export',
      'Parity',
      'Final validation',
    ])
      assert.match(panel, new RegExp(stage.replace('/', '\\/')));
    for (const operation of [
      'ELIGIBILITY',
      'JOB_CREATE',
      'JOB_START',
      'JOB_PAUSE',
      'JOB_CANCEL',
      'EVALUATE',
      'EXPORT',
      'ACTIVATE',
    ])
      assert.match(
        `${panel}\n${hook}`,
        new RegExp(`PersonalTrainingOperation\\.${operation}`)
      );
  });

  it('selects only validated, server-hashed dataset archives', () => {
    assert.match(panel, /disabled={!session\.isValid \|\| !hash}/);
    assert.match(panel, /session\.archiveSha256/);
  });
});
