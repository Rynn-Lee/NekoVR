/* eslint-disable @dword-design/import-alias/prefer-alias -- Node's native TypeScript test runner does not resolve the Vite @ alias. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import {
  AIExecutionProvider,
  AIModelConfigurationStateT,
  AITrackerSlotMappingT,
  AILegacyDriftMode,
} from 'solarxr-protocol';
import {
  AI_CONFIG_ADVANCED_MASK,
  AI_CONFIG_FIELD,
  configurationDraft,
  configurationRequest,
  enabledRequest,
  mappingsRequest,
} from '../components/ai-drift/ai-model-control.ts';

describe('AI model control-plane requests', () => {
  it('round-trips every editable server configuration field without enabling optimistically', () => {
    const authoritative = new AIModelConfigurationStateT();
    authoritative.enabled = true;
    authoritative.requestedProvider = AIExecutionProvider.DIRECTML;
    authoritative.contextFrames = 120;
    authoritative.confidenceThreshold = 0.72;
    authoritative.maximumCorrectionRadians = 0.31;
    authoritative.maximumRateRadiansPerSecond = 1.4;
    authoritative.maximumAccelerationRadiansPerSecondSquared = 11;
    authoritative.smoothing = 0.18;
    authoritative.staleDecaySeconds = 0.28;
    authoritative.legacyMode = AILegacyDriftMode.COMPOSE;

    const request = configurationRequest(configurationDraft(authoritative));

    assert.equal(request.fieldMask, AI_CONFIG_ADVANCED_MASK);
    assert.equal(request.enabled, false);
    assert.equal(request.provider, AIExecutionProvider.DIRECTML);
    assert.equal(request.contextFrames, 120);
    assert.equal(request.confidenceThreshold, 0.72);
    assert.equal(request.maximumCorrectionRadians, 0.31);
    assert.equal(request.maximumRateRadiansPerSecond, 1.4);
    assert.equal(request.maximumAccelerationRadiansPerSecondSquared, 11);
    assert.equal(request.smoothing, 0.18);
    assert.equal(request.staleDecaySeconds, 0.28);
    assert.equal(request.legacyMode, AILegacyDriftMode.COMPOSE);
  });

  it('uses isolated masks for enable and atomic mapping commands', () => {
    const enabled = enabledRequest(true);
    assert.equal(enabled.fieldMask, AI_CONFIG_FIELD.ENABLED);
    assert.equal(enabled.enabled, true);

    const mappings = [
      new AITrackerSlotMappingT(41, 3, 0),
      new AITrackerSlotMappingT(42, 4, 1),
    ];
    const request = mappingsRequest(mappings);
    assert.equal(request.fieldMask, AI_CONFIG_FIELD.MAPPINGS);
    assert.deepEqual(request.mappings, mappings);
  });
});

describe('localized accessible AI model surface', () => {
  it('keeps English and Russian AI control-plane message IDs in sync', () => {
    const ids = (path: string) =>
      new Set(
        [
          ...readFileSync(path, 'utf8').matchAll(/^(ai_(?:drift|model)-[\w-]+)\s*=/gm),
        ].map((match) => match[1])
      );
    const english = ids('public/i18n/en/translation.ftl');
    const russian = ids('public/i18n/ru/translation.ftl');
    assert.deepEqual([...english].sort(), [...russian].sort());
  });

  it('parses both Fluent resources without localization errors', () => {
    for (const locale of ['en', 'ru']) {
      const source = readFileSync(`public/i18n/${locale}/translation.ftl`, 'utf8')
        .split(/\r?\n/)
        .filter((line) => /^ai_(?:drift|model)-/.test(line))
        .join('\n');
      const bundle = new FluentBundle(locale);
      const errors = bundle.addResource(new FluentResource(source));
      assert.deepEqual(errors, [], `${locale} Fluent resource contains errors`);
    }
  });

  it('does not use browser WebGL as ONNX provider evidence', () => {
    const page = readFileSync(
      'src/components/ai-drift/AIModelControlPanel.tsx',
      'utf8'
    );
    assert.doesNotMatch(page, /WebGL|WEBGL_debug_renderer_info|detectHardwareGPU/);
    assert.match(page, /runtime\?\.metrics\?\.inferenceLatency/);
    assert.match(page, /control\.activeProvider/);
  });

  it('keeps authoritative model and recorder hooks mounted above tab panels', () => {
    const page = readFileSync('src/components/ai-drift/AIDriftPage.tsx', 'utf8');
    const modelHook = page.indexOf('useAIModelControl()');
    const recorderHook = page.indexOf('useDatasetRecorder()');
    const conditionalPanel = page.search(/activeTab === 'correction'/);
    assert.ok(modelHook > 0 && recorderHook > modelHook);
    assert.ok(conditionalPanel > recorderHook);
    assert.match(page, /role="tablist"/);
    assert.match(page, /role="tabpanel"/);
  });
});
