import { useState } from 'react';
import classNames from 'classnames';
import { useLocalization } from '@fluent/react';
import { DatasetRecordingState, DatasetSessionInfoT } from 'solarxr-protocol';
import { Button } from '@/components/commons/Button';
import { Typography } from '@/components/commons/Typography';
import { ProgressBar } from '@/components/commons/ProgressBar';
import { FolderIcon } from '@/components/commons/icon/FolderIcon';
import { WarningIcon } from '@/components/commons/icon/WarningIcon';
import { CheckIcon } from '@/components/commons/icon/CheckIcon';
import { TrashIcon } from '@/components/commons/icon/TrashIcon';
import { DownloadIcon } from '@/components/commons/icon/DownloadIcon';
import { useElectron } from '@/hooks/electron';
import { useDatasetRecorder } from '@/hooks/dataset-recorder';

export function DatasetRecorderWidget() {
  const { l10n } = useLocalization();
  const electron = useElectron();
  const {
    status,
    sessions,
    recoverable,
    lastAction,
    operationError,
    validationResults,
    isRefreshing,
    isRecording,
    isFinalizing,
    readinessFindings,
    hasBlockingErrors,
    refreshList,
    startRecording,
    stopRecording,
    cancelRecording,
    validateSession,
    recoverSession,
    deleteSession,
    exportSession,
    revealSession,
  } = useDatasetRecorder();

  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [consentChecked, setConsentChecked] = useState(false);
  const [pseudonym, setPseudonym] = useState('');
  const [selectedProfile, setSelectedProfile] = useState(0);
  const [hashHardwareIds, setHashHardwareIds] = useState(false);
  const [expandedFindingsSessionId, setExpandedFindingsSessionId] = useState<
    string | null
  >(null);
  const text = (value: string | Uint8Array | null | undefined) =>
    typeof value === 'string' ? value : '';
  const run = (operation: Promise<unknown>) => {
    void operation.catch(() => undefined);
  };

  const formatTime = (totalSec: number) => {
    const mins = Math.floor(totalSec / 60)
      .toString()
      .padStart(2, '0');
    const secs = Math.floor(totalSec % 60)
      .toString()
      .padStart(2, '0');
    return `${mins}:${secs}`;
  };

  const formatSize = (bytes: number | bigint) => {
    const numBytes = Number(bytes);
    if (numBytes >= 1024 * 1024 * 1024) {
      return `${(numBytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
    }
    if (numBytes >= 1024 * 1024) {
      return `${(numBytes / (1024 * 1024)).toFixed(2)} MB`;
    }
    return `${(numBytes / 1024).toFixed(1)} KB`;
  };

  const getStateBadge = () => {
    switch (status.state) {
      case DatasetRecordingState.STARTING:
        return {
          text: l10n.getString('dataset_recorder-status-starting'),
          dotClass: 'bg-yellow-500 animate-pulse',
        };
      case DatasetRecordingState.RECORDING:
        return {
          text: l10n.getString('dataset_recorder-status-recording'),
          dotClass: 'bg-red-500 animate-pulse',
        };
      case DatasetRecordingState.FINALIZING:
        return {
          text: l10n.getString('dataset_recorder-status-finalizing'),
          dotClass: 'bg-yellow-500 animate-spin',
        };
      case DatasetRecordingState.COMPLETED:
        return {
          text: l10n.getString('dataset_recorder-status-completed'),
          dotClass: 'bg-green-500',
        };
      case DatasetRecordingState.CANCELLED:
        return {
          text: l10n.getString('dataset_recorder-status-cancelled'),
          dotClass: 'bg-background-40',
        };
      case DatasetRecordingState.FAILED:
        return {
          text: l10n.getString('dataset_recorder-status-failed'),
          dotClass: 'bg-red-600',
        };
      case DatasetRecordingState.QUARANTINED:
        return {
          text: l10n.getString('dataset_recorder-status-quarantined'),
          dotClass: 'bg-amber-500',
        };
      case DatasetRecordingState.RECOVERABLE:
        return {
          text: l10n.getString('dataset_recorder-status-recoverable'),
          dotClass: 'bg-amber-500',
        };
      case DatasetRecordingState.IDLE:
      default:
        return {
          text: l10n.getString('dataset_recorder-status-idle'),
          dotClass: 'bg-background-40',
        };
    }
  };

  const stateBadge = getStateBadge();

  const serverElapsedSec = Math.floor(
    Number(status.elapsedDurationNs) / 1_000_000_000
  );
  const isStarting = status.state === DatasetRecordingState.STARTING;
  const isActive = isRecording || isFinalizing || isStarting;

  const handleReveal = async (sessionId: string) => {
    try {
      await revealSession(sessionId);
    } catch {
      // The hook exposes the correlated operation error.
    }
  };

  const handleExport = async (sessionId: string) => {
    try {
      await exportSession(sessionId);
    } catch {
      // The hook exposes the correlated operation error.
    }
  };

  const handleOpenFolder = async () => {
    if (electron.isElectron) {
      await electron.api.openDatasetsFolder();
    }
  };

  const handleStart = () => {
    if (!consentChecked || hasBlockingErrors || isFinalizing) return;
    run(
      startRecording(
        selectedProfile,
        consentChecked,
        pseudonym.trim(),
        hashHardwareIds
      )
    );
  };

  const handleConfirmCancel = () => {
    run(cancelRecording());
    setConfirmCancel(false);
  };

  return (
    <div className="flex flex-col gap-4 w-full">
      {/* 1. Readiness Findings Banner (if any) */}
      {readinessFindings.length > 0 && (
        <div className="flex flex-col gap-2 p-3 rounded-lg bg-background-60 border border-background-50">
          <div className="flex items-center gap-2">
            <WarningIcon
              width={18}
              className={
                hasBlockingErrors
                  ? 'text-status-critical'
                  : 'text-status-warning'
              }
            />
            <span className="text-xs font-semibold text-background-10">
              {hasBlockingErrors
                ? l10n.getString('dataset_recorder-readiness-errors')
                : l10n.getString('dataset_recorder-readiness-warnings')}
            </span>
          </div>
          <div className="flex flex-col gap-1 pl-6">
            {readinessFindings.map((f, i) => {
              const findingKey = `dataset_recorder-finding-${f.code}`;
              const localizedMsg = l10n.getString(findingKey) || f.message;
              return (
                <div key={i} className="flex items-center gap-2 text-[11px]">
                  <span
                    className={classNames(
                      'px-1.5 py-0.5 rounded text-[10px] font-bold uppercase',
                      f.severity === 2
                        ? 'bg-red-500/20 text-red-400 border border-red-500/30'
                        : 'bg-yellow-500/20 text-yellow-400 border border-yellow-500/30'
                    )}
                  >
                    {f.severity === 2
                      ? l10n.getString('dataset_recorder-severity-error')
                      : l10n.getString('dataset_recorder-severity-warning')}
                  </span>
                  <span className="text-background-20">{localizedMsg}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 2. Recording Control & Setup Bar */}
      <div className="flex flex-col gap-3 p-4 rounded-xl bg-background-60 border border-background-50 shadow-sm">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div
              className={classNames(
                'w-3 h-3 rounded-full',
                stateBadge.dotClass
              )}
            />
            <div className="flex flex-col">
              <span className="text-sm font-semibold text-background-10">
                {stateBadge.text}
              </span>
              {status.sessionId && isActive && (
                <span className="text-[11px] text-background-30 font-mono">
                  {l10n.getString('dataset_recorder-session-id')}:{' '}
                  {status.sessionId}
                </span>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2">
            {isActive ? (
              <>
                <Button
                  variant="destructive"
                  onClick={() => run(stopRecording())}
                  disabled={isFinalizing || isStarting}
                  loading={isFinalizing}
                  className="!h-9 !text-xs !px-5 font-semibold"
                >
                  {isFinalizing
                    ? l10n.getString('dataset_recorder-action-stopping')
                    : l10n.getString('dataset_recorder-action-stop')}
                </Button>

                {confirmCancel ? (
                  <div className="flex items-center gap-1.5 bg-background-70 p-1 rounded-lg border border-red-500/40">
                    <span className="text-[11px] text-red-400 font-semibold px-2">
                      {l10n.getString('dataset_recorder-action-confirm_cancel')}
                    </span>
                    <Button
                      variant="destructive"
                      onClick={handleConfirmCancel}
                      aria-label={l10n.getString(
                        'dataset_recorder-action-confirm_cancel_button'
                      )}
                      className="!h-7 !text-xs !px-2.5 font-bold"
                    >
                      ✓
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => setConfirmCancel(false)}
                      aria-label={l10n.getString(
                        'dataset_recorder-action-dismiss_cancel_button'
                      )}
                      className="!h-7 !text-xs !px-2.5"
                    >
                      ✕
                    </Button>
                  </div>
                ) : (
                  <Button
                    variant="secondary"
                    onClick={() => setConfirmCancel(true)}
                    disabled={isStarting}
                    className="!h-9 !text-xs !px-3.5 font-medium text-background-30 hover:text-red-400"
                  >
                    {l10n.getString('dataset_recorder-action-cancel')}
                  </Button>
                )}
              </>
            ) : (
              <div className="flex flex-col items-end gap-1">
                <Button
                  variant="primary"
                  onClick={handleStart}
                  disabled={
                    !consentChecked || hasBlockingErrors || isFinalizing
                  }
                  className="!h-9 !text-xs !px-5 font-semibold"
                >
                  {l10n.getString('dataset_recorder-action-start')}
                </Button>
                {!consentChecked && !isRecording && (
                  <span className="text-[10px] text-yellow-500/80">
                    {l10n.getString('dataset_recorder-consent-required')}
                  </span>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Configuration row (when not recording) */}
        {!isRecording && !isFinalizing && (
          <div className="flex flex-col sm:flex-row gap-3 pt-3 border-t border-background-50/50">
            {/* Profile Selection */}
            <div className="flex flex-col gap-1.5 min-w-[200px]">
              <span className="text-[11px] font-medium text-background-30">
                {l10n.getString('dataset_recorder-profile-label')}
              </span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setSelectedProfile(0)}
                  className={classNames(
                    'px-2.5 py-1 text-xs rounded border transition-colors',
                    selectedProfile === 0
                      ? 'bg-accent-background-20/20 border-accent-background-20 text-accent-background-10 font-semibold'
                      : 'bg-background-70 border-background-50 text-background-30'
                  )}
                >
                  {l10n.getString('dataset_recorder-profile-custom')}
                </button>
                <div
                  className="px-2.5 py-1 text-xs rounded border bg-background-70/50 border-background-50/50 text-background-40 cursor-not-allowed opacity-60 flex items-center gap-1"
                  title="Production profile is locked pending dataset gate acceptance"
                >
                  <span>
                    {l10n.getString('dataset_recorder-profile-production')}
                  </span>
                </div>
              </div>
            </div>

            {/* Pseudonym Input */}
            <div className="flex flex-col gap-1.5 flex-1">
              <span className="text-[11px] font-medium text-background-30">
                {l10n.getString('dataset_recorder-pseudonym-label')}
              </span>
              <input
                type="text"
                value={pseudonym}
                onChange={(e) => setPseudonym(e.target.value)}
                placeholder={l10n.getString(
                  'dataset_recorder-pseudonym-placeholder'
                )}
                className="h-8 px-2.5 rounded bg-background-70 border border-background-50 text-xs text-background-10 placeholder:text-background-40 focus:outline-none focus:border-accent-background-30"
              />
            </div>

            {/* Consent Checkbox */}
            <div className="flex flex-col gap-2 self-end sm:self-center pt-2 sm:pt-4">
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={consentChecked}
                  onChange={(e) => setConsentChecked(e.target.checked)}
                  className="w-4 h-4 rounded bg-background-70 border-background-50 text-accent-background-30 focus:ring-0 cursor-pointer"
                />
                <span className="text-[11px] text-background-20 leading-tight">
                  {l10n.getString('dataset_recorder-consent-label')}
                </span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={hashHardwareIds}
                  onChange={(e) => setHashHardwareIds(e.target.checked)}
                  className="w-4 h-4 rounded bg-background-70 border-background-50 text-accent-background-30 focus:ring-0 cursor-pointer"
                />
                <span className="text-[11px] text-background-20 leading-tight">
                  {l10n.getString('dataset_recorder-hash-identifiers-label')}
                </span>
              </label>
            </div>
          </div>
        )}

        {/* Finalization Progress Bar */}
        {isFinalizing && (
          <div className="flex flex-col gap-1 pt-2 border-t border-background-50/50">
            <div className="flex items-center justify-between text-[11px] text-background-20 font-medium">
              <span>
                {l10n.getString('dataset_recorder-metrics-finalizing_progress')}
              </span>
              <span>
                {Math.round((status.finalizationProgress || 0) * 100)}%
              </span>
            </div>
            <ProgressBar
              progress={status.finalizationProgress || 0}
              height={8}
            />
          </div>
        )}

        {/* Active Roster Display */}
        {status.roster && status.roster.length > 0 && (
          <div className="flex flex-col gap-1 pt-2 border-t border-background-50/50">
            <span className="text-[10px] text-background-30 uppercase font-semibold">
              {l10n.getString('dataset_recorder-metrics-roster')} (
              {status.roster.length})
            </span>
            <div className="flex flex-wrap gap-1.5">
              {status.roster.map((tracker, idx) => (
                <span
                  key={idx}
                  className="px-2 py-0.5 rounded bg-background-50 text-[10px] font-mono text-background-20 border border-background-40/50"
                >
                  {text(tracker.bodyRole)} · {text(tracker.imuType)} ·{' '}
                  {text(tracker.transport)}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Error display if server reported recording error */}
      {status.error && (
        <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-xs text-red-400">
          {status.error}
        </div>
      )}
      {lastAction?.error && (
        <div
          role="alert"
          className="p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-xs text-red-400"
        >
          {lastAction.error}
        </div>
      )}
      {operationError && (
        <div
          role="alert"
          className="p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-xs text-red-400"
        >
          {operationError}
        </div>
      )}
      {status.validationFindings && status.validationFindings.length > 0 && (
        <div
          role="status"
          className="flex flex-col gap-1 p-3 rounded-lg bg-background-60 border border-background-50 text-xs"
        >
          <span className="font-semibold text-background-20">
            {l10n.getString('dataset_recorder-validation-findings')}
          </span>
          {status.validationFindings.map((finding, index) => (
            <span
              key={`${text(finding.code)}-${index}`}
              className="text-background-30"
            >
              [{finding.code}] {finding.message}
            </span>
          ))}
        </div>
      )}

      {/* 3. Live Metrics Grid */}
      <div className="grid grid-cols-2 sm:grid-cols-4 md:grid-cols-8 gap-2">
        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-time')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {formatTime(serverElapsedSec)}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-sampled_frames')}
          </span>
          <span className="text-sm font-bold text-accent-background-20 font-mono mt-0.5">
            {Number(status.sampledFrames).toLocaleString()}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-written_frames')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {Number(status.writtenFrames).toLocaleString()}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-dropped_frames')}
          </span>
          <span
            className={classNames(
              'text-sm font-bold font-mono mt-0.5',
              Number(status.droppedFrames) > 0
                ? 'text-status-critical'
                : 'text-status-success'
            )}
          >
            {Number(status.droppedFrames).toLocaleString()}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-queue_depth')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {status.queuedFrames} / {status.queueHighWatermark}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-resets')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {status.resetCount || 0}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-size')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {formatSize(status.writtenBytes)}
          </span>
        </div>

        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-disk_free')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {formatSize(status.diskFreeBytes)}
          </span>
        </div>
        <div className="flex flex-col p-2 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30 uppercase font-medium">
            {l10n.getString('dataset_recorder-metrics-disk_used')}
          </span>
          <span className="text-sm font-bold text-background-10 font-mono mt-0.5">
            {formatSize(
              BigInt(status.diskTotalBytes) - BigInt(status.diskFreeBytes)
            )}{' '}
            / {formatSize(status.diskTotalBytes)}
          </span>
        </div>
      </div>

      {/* 4. Recoverable Sessions Warning (if server discovered partial/unfinalized sessions) */}
      {recoverable.length > 0 && (
        <div className="flex flex-col gap-2 p-3 rounded-lg bg-amber-500/10 border border-amber-500/30">
          <div className="flex items-center gap-2">
            <WarningIcon width={18} className="text-amber-400" />
            <span className="text-xs font-semibold text-amber-300">
              {l10n.getString('dataset_recorder-recoverable-title')} (
              {recoverable.length})
            </span>
          </div>
          <span className="text-[11px] text-background-30 pl-6">
            {l10n.getString('dataset_recorder-recoverable-description')}
          </span>
          <div className="flex flex-col gap-2 pl-6 pt-1">
            {recoverable.map((rec) => {
              const recoverableId = text(rec.sessionId);
              return (
                <div
                  key={recoverableId}
                  className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 p-2 rounded bg-background-70 border border-background-50"
                >
                  <div className="flex flex-col min-w-0">
                    <span className="text-xs font-mono text-background-10 truncate">
                      {recoverableId}
                    </span>
                    <span className="text-[10px] text-background-30">
                      {rec.reason}
                    </span>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button
                      variant="primary"
                      onClick={() => run(recoverSession(recoverableId, false))}
                      className="!h-6 !text-[10px] !px-2 font-medium"
                    >
                      {l10n.getString('dataset_recorder-action-recover')}
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => run(recoverSession(recoverableId, true))}
                      className="!h-6 !text-[10px] !px-2 font-medium"
                    >
                      {l10n.getString('dataset_recorder-action-quarantine')}
                    </Button>
                    <Button
                      variant="destructive"
                      onClick={() => run(deleteSession(recoverableId))}
                      className="!h-6 !text-[10px] !px-2 font-medium"
                    >
                      {l10n.getString('dataset_recorder-action-delete')}
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* 5. Authoritative Server Dataset Inventory */}
      <div className="flex flex-col gap-3 pt-2 border-t border-background-50/60">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Typography
              variant="section-title"
              className="!text-sm font-semibold text-background-10"
            >
              {l10n.getString('dataset_recorder-sessions-title')}
            </Typography>
            <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-background-60 text-background-20 border border-background-50 font-mono">
              {sessions.length}
            </span>
          </div>

          <div className="flex items-center gap-2">
            {electron.isElectron && (
              <Button
                variant="secondary"
                onClick={handleOpenFolder}
                icon={<FolderIcon width={16} />}
                className="!h-7 !text-xs !px-2.5 font-medium"
              >
                {l10n.getString('dataset_recorder-action-open_folder')}
              </Button>
            )}
            <Button
              variant="tertiary"
              onClick={refreshList}
              loading={isRefreshing}
              className="!h-7 !text-xs !px-2.5 font-medium"
            >
              {l10n.getString('dataset_recorder-action-refresh')}
            </Button>
          </div>
        </div>

        {sessions.length === 0 ? (
          <div className="p-6 rounded-xl bg-background-60 border border-background-50 text-center text-xs text-background-30">
            {l10n.getString('dataset_recorder-sessions-empty')}
          </div>
        ) : (
          <div className="flex flex-col gap-2 max-h-96 overflow-y-auto pr-1">
            {sessions.map((s: DatasetSessionInfoT) => {
              const sId = typeof s.sessionId === 'string' ? s.sessionId : '';
              const durationSec = Number(s.durationNs) / 1_000_000_000;
              const validation = validationResults[sId];
              const isValid = validation ? validation.valid : s.isValid;
              const findings = validation?.findings || [];
              const isExpanded = expandedFindingsSessionId === sId;

              return (
                <div
                  key={sId}
                  className="flex flex-col gap-2 p-3 rounded-lg bg-background-60 border border-background-50 hover:border-background-40 transition-colors"
                >
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                    <div className="flex items-center gap-3 min-w-0 flex-grow">
                      <div className="w-8 h-8 min-w-[32px] rounded-lg bg-background-50 flex items-center justify-center text-background-20">
                        {isValid ? (
                          <CheckIcon
                            size={16}
                            className="text-status-success"
                          />
                        ) : (
                          <WarningIcon
                            width={16}
                            className="text-status-critical"
                          />
                        )}
                      </div>
                      <div className="flex flex-col min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-semibold text-background-10 font-mono truncate">
                            {sId || 'session'}.nvrdata
                          </span>
                          <span
                            className={classNames(
                              'text-[9px] font-bold px-1.5 py-0.2 rounded uppercase',
                              isValid
                                ? 'bg-green-500/20 text-green-400 border border-green-500/30'
                                : 'bg-red-500/20 text-red-400 border border-red-500/30'
                            )}
                          >
                            {isValid
                              ? l10n.getString(
                                  'dataset_recorder-sessions-valid'
                                )
                              : l10n.getString(
                                  'dataset_recorder-sessions-invalid'
                                )}
                          </span>
                        </div>
                        <span className="text-[11px] text-background-30">
                          {s.createdUtc ? `${s.createdUtc} • ` : ''}
                          {formatTime(durationSec)} •{' '}
                          {Number(s.writtenFrames).toLocaleString()}{' '}
                          {l10n.getString('dataset_recorder-unit-frames')} •{' '}
                          {s.trackersCount}{' '}
                          {l10n.getString('dataset_recorder-unit-trackers')} •{' '}
                          {formatSize(s.archiveBytes)}
                        </span>
                        {!isValid &&
                          (s.validationError || validation?.error) && (
                            <span className="text-[10px] text-red-400 truncate">
                              {validation?.error || s.validationError}
                            </span>
                          )}
                      </div>
                    </div>

                    <div className="flex items-center gap-1.5 flex-shrink-0">
                      {electron.isElectron && (
                        <>
                          <Button
                            variant="secondary"
                            onClick={() => void handleReveal(sId)}
                            icon={<FolderIcon width={14} />}
                            className="!h-7 !text-xs !px-2 font-medium"
                          >
                            {l10n.getString('dataset_recorder-action-reveal')}
                          </Button>

                          <Button
                            variant="secondary"
                            onClick={() => handleExport(sId)}
                            icon={<DownloadIcon width={14} />}
                            className="!h-7 !text-xs !px-2 font-medium"
                          >
                            {l10n.getString('dataset_recorder-action-export')}
                          </Button>
                        </>
                      )}

                      <Button
                        variant="tertiary"
                        onClick={() => {
                          validateSession(sId);
                          setExpandedFindingsSessionId(isExpanded ? null : sId);
                        }}
                        className="!h-7 !text-xs !px-2 font-medium"
                      >
                        {l10n.getString('dataset_recorder-action-validate')}
                      </Button>

                      {confirmDeleteId === sId ? (
                        <div className="flex items-center gap-1">
                          <Button
                            variant="destructive"
                            onClick={() => {
                              run(deleteSession(sId));
                              setConfirmDeleteId(null);
                            }}
                            aria-label={l10n.getString(
                              'dataset_recorder-action-confirm_delete_button'
                            )}
                            className="!h-7 !text-xs !px-2 font-bold"
                          >
                            ✓
                          </Button>
                          <Button
                            variant="secondary"
                            onClick={() => setConfirmDeleteId(null)}
                            aria-label={l10n.getString(
                              'dataset_recorder-action-dismiss_delete_button'
                            )}
                            className="!h-7 !text-xs !px-2"
                          >
                            ✕
                          </Button>
                        </div>
                      ) : (
                        <Button
                          variant="quaternary"
                          onClick={() => setConfirmDeleteId(sId)}
                          aria-label={l10n.getString(
                            'dataset_recorder-action-delete'
                          )}
                          icon={<TrashIcon size={14} />}
                          className="!h-7 !text-xs !px-2 font-medium text-background-30 hover:text-red-400"
                        />
                      )}
                    </div>
                  </div>

                  {/* Expandable Validation Findings */}
                  {isExpanded && findings.length > 0 && (
                    <div className="flex flex-col gap-1 p-2 rounded bg-background-70 border border-background-50 text-[11px]">
                      <span className="font-semibold text-background-20">
                        {l10n.getString('dataset_recorder-validation-findings')}
                      </span>
                      {findings.map((finding, fIdx) => (
                        <div
                          key={fIdx}
                          className="flex items-center gap-2 pl-2"
                        >
                          <span
                            className={classNames(
                              'text-[9px] font-bold px-1 rounded uppercase',
                              finding.severity === 2
                                ? 'text-red-400'
                                : 'text-yellow-400'
                            )}
                          >
                            {finding.severity === 2
                              ? l10n.getString(
                                  'dataset_recorder-severity-fatal'
                                )
                              : l10n.getString(
                                  'dataset_recorder-severity-warning'
                                )}
                          </span>
                          <span className="text-background-30 font-mono text-[10px]">
                            [{finding.code}]
                          </span>
                          <span className="text-background-20">
                            {finding.message}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
