import {
  ChangeEvent,
  InputHTMLAttributes,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useLocalization } from '@fluent/react';
import {
  AIExecutionProvider,
  AIModelProgressStage,
  AIModelKind,
  AIModelRpcLoadState,
  AITrackerSlotMappingT,
  AILegacyDriftMode,
  AIRuntimeRpcHealth,
  BodyPart,
} from 'solarxr-protocol';
import { Button } from '@/components/commons/Button';
import { ProgressBar } from '@/components/commons/ProgressBar';
import { useAIModelControl } from '@/hooks/ai-model';
import { useElectron } from '@/hooks/electron';
import {
  AIConfigurationDraft,
  configurationDraft,
  configurationRequest,
  enabledRequest,
  mappingsRequest,
  modelText,
} from './ai-model-control';

export type AIModelControl = ReturnType<typeof useAIModelControl>;

const providers = [
  AIExecutionProvider.AUTO,
  AIExecutionProvider.CPU,
  AIExecutionProvider.CUDA,
  AIExecutionProvider.TENSORRT,
  AIExecutionProvider.DIRECTML,
];

const bodyParts = Object.values(BodyPart).filter(
  (value): value is BodyPart => typeof value === 'number'
);

const inputClass =
  'h-9 rounded-md border border-background-50 bg-background-60 px-2 text-xs text-background-10 disabled:cursor-not-allowed disabled:text-background-40';

export function AIModelControlPanel({ control }: { control: AIModelControl }) {
  const { l10n } = useLocalization();
  const electron = useElectron();
  const configuration = control.configuration;
  const runtime = control.runtime;
  const inferenceReady = runtime?.inferenceReady === true;
  const effectiveCorrectionEnabled =
    runtime?.effectiveCorrectionEnabled === true;
  const [draft, setDraft] = useState<AIConfigurationDraft | null>(null);
  const [mappings, setMappings] = useState<AITrackerSlotMappingT[]>([]);
  const [selectedModelHash, setSelectedModelHash] = useState('');
  const [modelPath, setModelPath] = useState('');
  const [metadataPath, setMetadataPath] = useState('');
  const [busy, setBusy] = useState('');
  const lastConfigurationKey = useRef('');

  useEffect(() => {
    if (!configuration) return;
    const configurationKey = JSON.stringify({
      ...configurationDraft(configuration),
      mappings: configuration.mappings.map((mapping) => [
        mapping.trackerId,
        mapping.bodyRoleId,
        mapping.slot,
      ]),
    });
    if (configurationKey === lastConfigurationKey.current) return;
    lastConfigurationKey.current = configurationKey;
    setDraft(configurationDraft(configuration));
    setMappings(
      configuration.mappings.map(
        (mapping) =>
          new AITrackerSlotMappingT(
            mapping.trackerId,
            mapping.bodyRoleId,
            mapping.slot
          )
      )
    );
  }, [configuration]);

  useEffect(() => {
    if (
      control.models.length === 0 ||
      control.models.some(
        (model) => modelText(model.modelSha256) === selectedModelHash
      )
    )
      return;
    const active = control.models.find(
      (model) => modelText(model.modelSha256) === control.activeModelSha256
    );
    const recommended =
      active ??
      control.models.find((model) => model.downloaded && model.compatible) ??
      control.models[0];
    setSelectedModelHash(modelText(recommended.modelSha256));
  }, [control.activeModelSha256, control.models, selectedModelHash]);

  const selectedModel = useMemo(
    () =>
      control.models.find(
        (model) => modelText(model.modelSha256) === selectedModelHash
      ),
    [control.models, selectedModelHash]
  );

  const run = (name: string, operation: Promise<unknown>) => {
    setBusy(name);
    void operation.finally(() => setBusy('')).catch(() => undefined);
  };

  const selectImportFile = async (kind: 'model' | 'metadata') => {
    if (!electron.isElectron) return;
    const result = await electron.api.openDialog({
      properties: ['openFile'],
      filters:
        kind === 'model'
          ? [{ name: 'ONNX', extensions: ['onnx'] }]
          : [{ name: 'JSON', extensions: ['json'] }],
    });
    if (result.canceled || !result.filePaths[0]) return;
    if (kind === 'model') setModelPath(result.filePaths[0]);
    else setMetadataPath(result.filePaths[0]);
  };

  const updateNumber =
    (field: keyof AIConfigurationDraft) =>
    (event: ChangeEvent<HTMLInputElement>) => {
      const value = Number(event.target.value);
      if (!Number.isFinite(value)) return;
      setDraft((current) =>
        current ? { ...current, [field]: value } : current
      );
    };

  const healthKey =
    runtime?.health === AIRuntimeRpcHealth.HEALTHY
      ? 'healthy'
      : runtime?.health === AIRuntimeRpcHealth.DEGRADED
        ? 'degraded'
        : runtime?.health === AIRuntimeRpcHealth.WATCHDOG_TRIPPED
          ? 'watchdog'
          : runtime?.health === AIRuntimeRpcHealth.UNAVAILABLE
            ? 'unavailable'
            : 'disabled';
  const loadKey =
    runtime?.loadState === AIModelRpcLoadState.ACTIVE
      ? 'active'
      : runtime?.loadState === AIModelRpcLoadState.LOADING
        ? 'loading'
        : runtime?.loadState === AIModelRpcLoadState.ERROR
          ? 'error'
          : runtime?.loadState === AIModelRpcLoadState.UNLOADED
            ? 'unloaded'
            : 'unavailable';
  const progressKey =
    control.progress?.stage === AIModelProgressStage.FETCHING_CATALOG
      ? 'catalog'
      : control.progress?.stage === AIModelProgressStage.DOWNLOADING_MODEL
        ? 'model'
        : control.progress?.stage === AIModelProgressStage.DOWNLOADING_METADATA
          ? 'metadata'
          : control.progress?.stage === AIModelProgressStage.VERIFYING
            ? 'verifying'
            : control.progress?.stage === AIModelProgressStage.IMPORTING
              ? 'importing'
              : control.progress?.stage === AIModelProgressStage.ACTIVATING
                ? 'activating'
                : control.progress?.stage === AIModelProgressStage.COMPLETED
                  ? 'completed'
                  : control.progress?.stage === AIModelProgressStage.FAILED
                    ? 'failed'
                    : 'idle';
  const operationError =
    control.operationError === 'AI_MODEL_CLIENT_NOT_CONNECTED'
      ? l10n.getString('ai_model-error-not_connected')
      : control.operationError === 'AI_MODEL_CLIENT_TIMEOUT'
        ? l10n.getString('ai_model-error-timeout')
        : control.operationError === 'AI_MODEL_CLIENT_DISCONNECTED'
          ? l10n.getString('ai_model-error-disconnected')
          : control.operationError;

  return (
    <div className="flex flex-col gap-4">
      <div
        className="grid gap-2 rounded-lg border border-background-50 bg-background-60 p-3 text-xs sm:grid-cols-2 lg:grid-cols-4"
        role="status"
        aria-live="polite"
      >
        <span>
          <strong>{l10n.getString('ai_model-runtime-health')}:</strong>{' '}
          {l10n.getString(`ai_model-health-${healthKey}`)}
        </span>
        <span>
          <strong>{l10n.getString('ai_model-runtime-load_state')}:</strong>{' '}
          {l10n.getString(`ai_model-load-${loadKey}`)}
        </span>
        <span>
          <strong>{l10n.getString('ai_model-runtime-provider')}:</strong>{' '}
          {control.activeModelSha256
            ? AIExecutionProvider[control.activeProvider]
            : l10n.getString('ai_model-runtime-no_active_provider')}
        </span>
        <span>
          <strong>{l10n.getString('ai_model-runtime-latency')}:</strong>{' '}
          {Number(runtime?.metrics?.inferenceLatency?.p95Micros ?? 0n) / 1000}{' '}
          {l10n.getString('ai_model-unit-ms')} p95
        </span>
        <span className="sm:col-span-2">
          <strong>{l10n.getString('ai_model-runtime-package')}:</strong>{' '}
          {modelText(runtime?.runtimeFlavor) || '—'}{' '}
          {modelText(runtime?.runtimeVersion)}
        </span>
        <span>
          <strong>{l10n.getString('ai_model-runtime-rate')}:</strong>{' '}
          {runtime?.metrics?.inferenceRateHz.toFixed(1) ?? '0.0'} Hz
        </span>
        <span>
          <strong>{l10n.getString('ai_model-runtime-queue')}:</strong>{' '}
          {runtime?.metrics?.queueDepth ?? 0} /{' '}
          {Number(runtime?.metrics?.queueDrops ?? 0n)}
        </span>
        <span className="break-all sm:col-span-2 lg:col-span-4">
          <strong>{l10n.getString('ai_model-runtime-active_model')}:</strong>{' '}
          {modelText(runtime?.activeModelId) ||
            l10n.getString('ai_model-runtime-no_active_model')}
          {modelText(runtime?.activeModelVersion) &&
            ` @ ${modelText(runtime?.activeModelVersion)}`}
          {control.activeModelSha256 &&
            ` · SHA-256 ${control.activeModelSha256}`}
        </span>
      </div>

      <div
        className={`rounded-lg border p-3 text-xs ${
          inferenceReady
            ? 'border-status-success bg-status-success/10 text-status-success'
            : 'border-amber-500/30 bg-amber-500/10 text-amber-300'
        }`}
        role="status"
      >
        <strong>
          {l10n.getString(
            inferenceReady
              ? 'ai_model-readiness-ready'
              : 'ai_model-readiness-locked'
          )}
        </strong>{' '}
        {modelText(runtime?.readinessDetail)}
        {modelText(runtime?.inferenceReadyModelSha256) && (
          <span className="block break-all font-mono text-[10px]">
            {l10n.getString('ai_model-readiness-evidence-model')}:{' '}
            {modelText(runtime?.inferenceReadyModelSha256)}
          </span>
        )}
        <span className="block text-[11px]">
          {l10n.getString('ai_model-readiness-persisted-intent')}:{' '}
          {configuration?.enabled
            ? l10n.getString('ai_model-state-on')
            : l10n.getString('ai_model-state-off')}
          {' · '}
          {l10n.getString('ai_model-readiness-effective-state')}:{' '}
          {effectiveCorrectionEnabled
            ? l10n.getString('ai_model-state-on')
            : l10n.getString('ai_model-state-off')}
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant={effectiveCorrectionEnabled ? 'destructive' : 'primary'}
          disabled={
            !control.connected ||
            !configuration ||
            Boolean(busy) ||
            (!configuration.enabled && !inferenceReady)
          }
          loading={busy === 'enabled'}
          onClick={() =>
            run(
              'enabled',
              control.configure(enabledRequest(!configuration?.enabled))
            )
          }
          aria-pressed={configuration?.enabled ?? false}
        >
          {l10n.getString(
            configuration?.enabled
              ? 'ai_model-action-disable-intent'
              : inferenceReady
                ? 'ai_model-action-enable'
                : 'ai_model-action-enable-locked'
          )}
        </Button>
        <Button
          variant="secondary"
          disabled={
            !control.connected || !control.activeModelSha256 || Boolean(busy)
          }
          loading={busy === 'unload'}
          onClick={() => run('unload', control.unload())}
        >
          {l10n.getString('ai_model-action-unload')}
        </Button>
        <Button
          variant="tertiary"
          disabled={!control.connected || Boolean(busy)}
          loading={busy === 'refresh'}
          onClick={() => run('refresh', control.refreshCatalog())}
        >
          {l10n.getString('ai_model-action-refresh_catalog')}
        </Button>
        <Button
          variant="secondary"
          disabled={!control.rollbackModelSha256 || !draft || Boolean(busy)}
          loading={busy === 'rollback'}
          onClick={() =>
            run(
              'rollback',
              control.rollback(draft?.provider ?? AIExecutionProvider.AUTO)
            )
          }
        >
          {l10n.getString('ai_model-action-rollback')}
        </Button>
      </div>

      <section
        className="flex flex-col gap-2 border-t border-background-50 pt-3"
        aria-labelledby="ai-model-history-title"
      >
        <div className="flex items-center justify-between gap-2">
          <h3
            id="ai-model-history-title"
            className="text-sm font-semibold text-background-10"
          >
            {l10n.getString('ai_model-history-title')}
          </h3>
          <Button
            variant="quaternary"
            className="!h-7 !px-2 !text-xs"
            disabled={!control.connected}
            onClick={control.refreshHistory}
          >
            {l10n.getString('ai_model-history-refresh')}
          </Button>
        </div>
        {control.historyEntries.length === 0 ? (
          <p className="text-xs text-background-30">
            {l10n.getString('ai_model-history-empty')}
          </p>
        ) : (
          <div className="grid gap-2 md:grid-cols-2">
            {control.historyEntries.map((entry) => {
              const hash = modelText(entry.modelSha256);
              return (
                <article
                  key={hash}
                  className="flex items-center justify-between gap-3 border border-background-50 bg-background-60 p-2"
                >
                  <div className="min-w-0 text-xs">
                    <div className="truncate font-semibold text-background-10">
                      {entry.pinned && '★ '}
                      {modelText(entry.displayName) || modelText(entry.modelId)}
                      {entry.kind === AIModelKind.PERSONAL &&
                        ` · ${l10n.getString('ai_model-history-personal')}`}
                    </div>
                    <div className="truncate text-[11px] text-background-30">
                      {entry.active
                        ? l10n.getString('ai_model-history-active')
                        : entry.compatible
                          ? l10n.getString('ai_model-history-compatible')
                          : modelText(entry.compatibilityError)}
                    </div>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button
                      variant="quaternary"
                      className="!h-7 !px-2 !text-xs"
                      disabled={!entry.validated || Boolean(busy)}
                      onClick={() =>
                        run('pin', control.pin(hash, !entry.pinned))
                      }
                      aria-label={l10n.getString(
                        entry.pinned
                          ? 'ai_model-history-unpin'
                          : 'ai_model-history-pin'
                      )}
                    >
                      {entry.pinned ? '★' : '☆'}
                    </Button>
                    <Button
                      variant="secondary"
                      className="!h-7 !px-2 !text-xs"
                      disabled={
                        !entry.compatible ||
                        entry.active ||
                        !draft ||
                        Boolean(busy)
                      }
                      onClick={() =>
                        run(
                          'switch',
                          control.switchModel(
                            hash,
                            draft?.provider ?? AIExecutionProvider.AUTO
                          )
                        )
                      }
                    >
                      {l10n.getString(
                        inferenceReady
                          ? 'ai_model-history-switch'
                          : 'ai_model-history-switch-diagnostic'
                      )}
                    </Button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>

      <fieldset className="flex flex-col gap-3 border-t border-background-50 pt-3">
        <legend className="px-1 text-sm font-semibold text-background-10">
          {l10n.getString('ai_model-catalog-title')}
        </legend>
        <label
          className="flex flex-col gap-1 text-xs text-background-20"
          htmlFor="ai-model-catalog"
        >
          {l10n.getString('ai_model-catalog-label')}
          <select
            id="ai-model-catalog"
            className={inputClass}
            value={selectedModelHash}
            disabled={!control.connected || control.models.length === 0}
            onChange={(event) => setSelectedModelHash(event.target.value)}
          >
            {control.models.length === 0 && (
              <option value="">
                {l10n.getString('ai_model-catalog-empty')}
              </option>
            )}
            {control.models.map((model) => {
              const hash = modelText(model.modelSha256);
              return (
                <option key={hash} value={hash} disabled={!model.compatible}>
                  {modelText(model.name) || modelText(model.modelId)} —{' '}
                  {model.downloaded
                    ? l10n.getString('ai_model-catalog-downloaded')
                    : l10n.getString('ai_model-catalog-remote')}
                </option>
              );
            })}
          </select>
        </label>
        {selectedModel && (
          <div className="text-xs text-background-30">
            <div>{modelText(selectedModel.description)}</div>
            <div className="break-all font-mono">
              SHA-256: {selectedModelHash}
            </div>
            {!selectedModel.compatible && (
              <div className="text-status-critical" role="alert">
                {modelText(selectedModel.compatibilityError) ||
                  l10n.getString('ai_model-catalog-incompatible')}
              </div>
            )}
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            disabled={
              !selectedModel || selectedModel.downloaded || Boolean(busy)
            }
            loading={busy === 'download'}
            onClick={() => run('download', control.download(selectedModelHash))}
          >
            {l10n.getString('ai_model-action-download')}
          </Button>
          <Button
            variant="primary"
            disabled={
              !selectedModel?.downloaded ||
              !selectedModel.compatible ||
              !draft ||
              Boolean(busy)
            }
            loading={busy === 'activate'}
            onClick={() =>
              run('activate', control.load(selectedModelHash, draft?.provider))
            }
          >
            {l10n.getString(
              inferenceReady
                ? 'ai_model-action-activate'
                : 'ai_model-action-load-diagnostic'
            )}
          </Button>
        </div>
      </fieldset>

      <fieldset className="grid gap-3 border-t border-background-50 pt-3 sm:grid-cols-2">
        <legend className="px-1 text-sm font-semibold text-background-10">
          {l10n.getString('ai_model-import-title')}
        </legend>
        <div className="flex flex-col gap-1 text-xs">
          <label htmlFor="ai-model-path">
            {l10n.getString('ai_model-import-model')}
          </label>
          <div className="flex gap-2">
            <input
              id="ai-model-path"
              className={`${inputClass} min-w-0 flex-1`}
              value={modelPath}
              readOnly
            />
            <Button
              variant="secondary"
              disabled={!electron.isElectron || Boolean(busy)}
              onClick={() => void selectImportFile('model')}
            >
              {l10n.getString('ai_model-action-browse')}
            </Button>
          </div>
        </div>
        <div className="flex flex-col gap-1 text-xs">
          <label htmlFor="ai-metadata-path">
            {l10n.getString('ai_model-import-metadata')}
          </label>
          <div className="flex gap-2">
            <input
              id="ai-metadata-path"
              className={`${inputClass} min-w-0 flex-1`}
              value={metadataPath}
              readOnly
            />
            <Button
              variant="secondary"
              disabled={!electron.isElectron || Boolean(busy)}
              onClick={() => void selectImportFile('metadata')}
            >
              {l10n.getString('ai_model-action-browse')}
            </Button>
          </div>
        </div>
        <div className="sm:col-span-2">
          {!electron.isElectron && (
            <p className="mb-2 text-xs text-background-30">
              {l10n.getString('ai_model-import-electron_only')}
            </p>
          )}
          <Button
            variant="primary"
            disabled={
              !modelPath || !metadataPath || !control.connected || Boolean(busy)
            }
            loading={busy === 'import'}
            onClick={() =>
              run('import', control.importModel(modelPath, metadataPath))
            }
          >
            {l10n.getString('ai_model-action-import')}
          </Button>
        </div>
      </fieldset>

      {draft && (
        <fieldset className="grid gap-3 border-t border-background-50 pt-3 sm:grid-cols-2 lg:grid-cols-3">
          <legend className="px-1 text-sm font-semibold text-background-10">
            {l10n.getString('ai_model-settings-title')}
          </legend>
          <label className="flex flex-col gap-1 text-xs" htmlFor="ai-provider">
            {l10n.getString('ai_model-settings-provider')}
            <select
              id="ai-provider"
              className={inputClass}
              value={draft.provider}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  provider: Number(event.target.value) as AIExecutionProvider,
                })
              }
            >
              {providers.map((provider) => (
                <option key={provider} value={provider}>
                  {AIExecutionProvider[provider]}
                </option>
              ))}
            </select>
            <span className="text-[11px] text-background-30">
              {l10n.getString('ai_model-settings-provider_notice')}
            </span>
          </label>
          <NumberField
            id="ai-context"
            label={l10n.getString('ai_model-settings-context')}
            value={draft.contextFrames}
            min={1}
            max={4096}
            step={1}
            onChange={updateNumber('contextFrames')}
          />
          <NumberField
            id="ai-confidence"
            label={l10n.getString('ai_model-settings-confidence')}
            value={draft.confidenceThreshold}
            min={0}
            max={1}
            step={0.01}
            onChange={updateNumber('confidenceThreshold')}
          />
          <NumberField
            id="ai-max-correction"
            label={l10n.getString('ai_model-settings-max_correction')}
            value={draft.maximumCorrectionRadians}
            min={0.001}
            max={Math.PI}
            step={0.01}
            onChange={updateNumber('maximumCorrectionRadians')}
          />
          <NumberField
            id="ai-max-rate"
            label={l10n.getString('ai_model-settings-max_rate')}
            value={draft.maximumRateRadiansPerSecond}
            min={0.001}
            max={100}
            step={0.01}
            onChange={updateNumber('maximumRateRadiansPerSecond')}
          />
          <NumberField
            id="ai-max-acceleration"
            label={l10n.getString('ai_model-settings-max_acceleration')}
            value={draft.maximumAccelerationRadiansPerSecondSquared}
            min={0.001}
            max={1000}
            step={0.1}
            onChange={updateNumber(
              'maximumAccelerationRadiansPerSecondSquared'
            )}
          />
          <NumberField
            id="ai-smoothing"
            label={l10n.getString('ai_model-settings-smoothing')}
            value={draft.smoothing}
            min={0}
            max={1}
            step={0.01}
            onChange={updateNumber('smoothing')}
          />
          <NumberField
            id="ai-stale-decay"
            label={l10n.getString('ai_model-settings-stale_decay')}
            value={draft.staleDecaySeconds}
            min={0.001}
            max={60}
            step={0.01}
            onChange={updateNumber('staleDecaySeconds')}
          />
          <label
            className="flex flex-col gap-1 text-xs"
            htmlFor="ai-legacy-mode"
          >
            {l10n.getString('ai_model-settings-legacy_mode')}
            <select
              id="ai-legacy-mode"
              className={inputClass}
              value={draft.legacyMode}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  legacyMode: Number(event.target.value) as AILegacyDriftMode,
                })
              }
            >
              <option value={AILegacyDriftMode.REPLACE}>
                {l10n.getString('ai_model-settings-legacy_replace')}
              </option>
              <option value={AILegacyDriftMode.COMPOSE}>
                {l10n.getString('ai_model-settings-legacy_compose')}
              </option>
            </select>
          </label>
          <div className="flex items-end lg:col-span-3">
            <Button
              variant="primary"
              disabled={!control.connected || Boolean(busy)}
              loading={busy === 'settings'}
              onClick={() =>
                run('settings', control.configure(configurationRequest(draft)))
              }
            >
              {l10n.getString('ai_model-action-apply_settings')}
            </Button>
          </div>
        </fieldset>
      )}

      <fieldset className="flex flex-col gap-2 border-t border-background-50 pt-3">
        <legend className="px-1 text-sm font-semibold text-background-10">
          {l10n.getString('ai_model-mapping-title')}
        </legend>
        {mappings.length === 0 && (
          <span className="text-xs text-background-30">
            {l10n.getString('ai_model-mapping-empty')}
          </span>
        )}
        {mappings.map((mapping, index) => (
          <div
            className="grid grid-cols-[1fr_1fr_1fr_auto] gap-2"
            key={`${mapping.trackerId}-${index}`}
          >
            <NumberField
              id={`ai-map-tracker-${index}`}
              label={l10n.getString('ai_model-mapping-tracker')}
              value={mapping.trackerId}
              min={0}
              step={1}
              onChange={(event) =>
                setMappings((current) =>
                  current.map((item, itemIndex) =>
                    itemIndex === index
                      ? new AITrackerSlotMappingT(
                          Number(event.target.value),
                          item.bodyRoleId,
                          item.slot
                        )
                      : item
                  )
                )
              }
            />
            <label
              className="flex flex-col gap-1 text-xs text-background-20"
              htmlFor={`ai-map-role-${index}`}
            >
              {l10n.getString('ai_model-mapping-role')}
              <select
                id={`ai-map-role-${index}`}
                className={inputClass}
                value={mapping.bodyRoleId}
                onChange={(event) =>
                  setMappings((current) =>
                    current.map((item, itemIndex) =>
                      itemIndex === index
                        ? new AITrackerSlotMappingT(
                            item.trackerId,
                            Number(event.target.value),
                            item.slot
                          )
                        : item
                    )
                  )
                }
              >
                {bodyParts.map((bodyPart) => (
                  <option key={bodyPart} value={bodyPart}>
                    {l10n.getString(`body_part-${BodyPart[bodyPart]}`)}
                  </option>
                ))}
              </select>
            </label>
            <NumberField
              id={`ai-map-slot-${index}`}
              label={l10n.getString('ai_model-mapping-slot')}
              value={mapping.slot}
              min={0}
              step={1}
              onChange={(event) =>
                setMappings((current) =>
                  current.map((item, itemIndex) =>
                    itemIndex === index
                      ? new AITrackerSlotMappingT(
                          item.trackerId,
                          item.bodyRoleId,
                          Number(event.target.value)
                        )
                      : item
                  )
                )
              }
            />
            <Button
              variant="quaternary"
              className="self-end"
              aria-label={l10n.getString('ai_model-mapping-remove')}
              onClick={() =>
                setMappings((current) =>
                  current.filter((_, itemIndex) => itemIndex !== index)
                )
              }
            >
              ×
            </Button>
          </div>
        ))}
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            onClick={() =>
              setMappings((current) => [
                ...current,
                new AITrackerSlotMappingT(0, 0, current.length),
              ])
            }
          >
            {l10n.getString('ai_model-mapping-add')}
          </Button>
          <Button
            variant="primary"
            disabled={!control.connected || Boolean(busy)}
            loading={busy === 'mappings'}
            onClick={() =>
              run('mappings', control.configure(mappingsRequest(mappings)))
            }
          >
            {l10n.getString('ai_model-mapping-apply')}
          </Button>
        </div>
      </fieldset>

      {control.progress &&
        control.progress.stage !== AIModelProgressStage.IDLE && (
          <div className="flex flex-col gap-1" role="status" aria-live="polite">
            <span className="text-xs text-background-20">
              {l10n.getString(`ai_model-progress-${progressKey}`)}
            </span>
            <ProgressBar progress={control.progress.progress} />
          </div>
        )}

      {runtime?.trackers && runtime.trackers.length > 0 && (
        <div className="overflow-x-auto border-t border-background-50 pt-3">
          <table className="w-full text-left text-xs">
            <caption className="pb-2 text-left text-sm font-semibold text-background-10">
              {l10n.getString('ai_model-trackers-title')}
            </caption>
            <thead className="text-background-30">
              <tr>
                <th scope="col">
                  {l10n.getString('ai_model-mapping-tracker')}
                </th>
                <th scope="col">{l10n.getString('ai_model-mapping-role')}</th>
                <th scope="col">{l10n.getString('ai_model-mapping-slot')}</th>
                <th scope="col">
                  {l10n.getString('ai_model-trackers-confidence')}
                </th>
                <th scope="col">
                  {l10n.getString('ai_model-trackers-correction')}
                </th>
                <th scope="col">
                  {l10n.getString('ai_model-trackers-reason')}
                </th>
              </tr>
            </thead>
            <tbody>
              {runtime.trackers.map((tracker) => (
                <tr
                  key={tracker.trackerId}
                  className="border-t border-background-50"
                >
                  <td>{tracker.trackerId}</td>
                  <td>
                    {BodyPart[tracker.bodyRoleId]
                      ? l10n.getString(
                          `body_part-${BodyPart[tracker.bodyRoleId]}`
                        )
                      : tracker.bodyRoleId}
                  </td>
                  <td>{tracker.slot}</td>
                  <td>{tracker.confidence.toFixed(2)}</td>
                  <td>
                    {l10n.getString(
                      tracker.correctionApplied
                        ? 'ai_model-trackers-applied'
                        : 'ai_model-trackers-not_applied'
                    )}
                  </td>
                  <td>{modelText(tracker.rejectionReason) || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {(operationError || modelText(runtime?.lastError)) && (
        <div
          className="rounded-lg border border-status-critical bg-status-critical/10 p-3 text-xs text-status-critical"
          role="alert"
        >
          <strong>{l10n.getString('ai_model-error-title')}:</strong>{' '}
          {operationError || modelText(runtime?.lastError)}
          {modelText(runtime?.lastErrorCode) &&
            ` (${modelText(runtime?.lastErrorCode)})`}
        </div>
      )}
    </div>
  );
}

function NumberField({
  id,
  label,
  ...props
}: {
  id: string;
  label: string;
} & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label
      className="flex flex-col gap-1 text-xs text-background-20"
      htmlFor={id}
    >
      {label}
      <input {...props} id={id} type="number" className={inputClass} />
    </label>
  );
}
