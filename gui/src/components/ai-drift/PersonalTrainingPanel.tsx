import { useMemo, useState } from 'react';
import {
  PersonalTrainingOperation,
  PersonalTrainingPreset,
  PersonalTrainingProvider,
  PersonalTrainingResourcePolicyT,
  PersonalTrainingVrPolicy,
  type AIModelDescriptorT,
  type DatasetSessionInfoT,
} from 'solarxr-protocol';
import {
  defaultPersonalResources,
  usePersonalTraining,
} from '@/hooks/personal-training';

const STAGES = [
  'Validate',
  'Index',
  'Preprocess/cache',
  'Split',
  'Train',
  'Evaluate',
  'Export',
  'Parity',
  'Final validation',
];

export function PersonalTrainingPanel({
  control,
  availableSessions,
  availableModels,
}: {
  control: ReturnType<typeof usePersonalTraining>;
  availableSessions: DatasetSessionInfoT[];
  availableModels: AIModelDescriptorT[];
}) {
  const [profileId, setProfileId] = useState('');
  const [baseModel, setBaseModel] = useState('');
  const [selectedSessions, setSelectedSessions] = useState<string[]>([]);
  const [preset, setPreset] = useState(PersonalTrainingPreset.BALANCED);
  const [provider, setProvider] = useState(PersonalTrainingProvider.AUTO);
  const [resources, setResources] = useState(defaultPersonalResources);
  const sessions = useMemo(() => selectedSessions, [selectedSessions]);
  const updateResource = (
    field: keyof PersonalTrainingResourcePolicyT,
    value: number
  ) =>
    setResources(
      Object.assign(new PersonalTrainingResourcePolicyT(), resources, {
        [field]: value,
      })
    );
  const status = control.status;

  return (
    <div
      className="grid gap-4 lg:grid-cols-2"
      data-testid="personal-training-panel"
    >
      <section className="space-y-3 rounded-md bg-background-70 p-4">
        <h2 className="text-base font-semibold">Personal Training</h2>
        <label className="block text-xs">
          Profile
          <select
            className="mt-1 w-full bg-background-80 p-2"
            value={profileId}
            onChange={(e) => setProfileId(e.target.value)}
          >
            <option value="">Select profile</option>
            {control.profiles.map((p) => (
              <option key={String(p.profileId)} value={String(p.profileId)}>
                {String(p.pseudonym || p.profileId)}
              </option>
            ))}
          </select>
        </label>
        <label className="block text-xs">
          Personalization-ready base model
          <select
            className="mt-1 w-full bg-background-80 p-2"
            value={baseModel}
            onChange={(e) => setBaseModel(e.target.value)}
          >
            <option value="">Select base model</option>
            {availableModels.map((model) => (
              <option
                key={String(model.modelSha256)}
                value={String(model.modelSha256)}
              >
                {String(model.name || model.modelSha256)}
              </option>
            ))}
          </select>
        </label>
        <fieldset className="max-h-40 overflow-y-auto rounded bg-background-80 p-2 text-xs">
          <legend>Validated dataset sessions</legend>
          {availableSessions.length === 0 && <p>No recorded sessions.</p>}
          {availableSessions.map((session) => {
            const id = String(session.sessionId);
            const hash = String(session.archiveSha256);
            return (
              <label key={id} className="flex gap-2 py-1">
                <input
                  type="checkbox"
                  disabled={!session.isValid || !hash}
                  checked={selectedSessions.includes(hash)}
                  onChange={(e) =>
                    setSelectedSessions((current) =>
                      e.target.checked
                        ? [...current, hash]
                        : current.filter((value) => value !== hash)
                    )
                  }
                />
                <span>
                  {id} · {(Number(session.durationNs) / 1e9).toFixed(1)}s ·{' '}
                  {session.resetCount} resets
                  {!session.isValid
                    ? ` · ${String(session.validationError || 'not validated')}`
                    : ''}
                </span>
              </label>
            );
          })}
        </fieldset>
        <div className="flex gap-2">
          <button
            className="bg-background-60 px-3 py-2 text-xs"
            onClick={() =>
              control.checkEligibility(profileId, baseModel, sessions)
            }
          >
            Analyze coverage
          </button>
          <span className="text-xs">{sessions.length} selected</span>
        </div>
        {control.eligibility && (
          <div className="rounded bg-background-80 p-3 text-xs">
            <b>{control.eligibility.ready ? 'Eligible' : 'Not eligible'}</b>
            <div>
              {Number(control.eligibility.coverage?.usableSeconds ?? 0).toFixed(
                1
              )}{' '}
              usable seconds ·{' '}
              {String(control.eligibility.coverage?.usableWindows ?? 0)} windows
              · {String(control.eligibility.coverage?.resetLabels ?? 0)} reset
              labels
            </div>
            {control.eligibility.findings.map((f) => (
              <p
                key={String(f.code)}
                className={f.blocking ? 'text-status-critical' : ''}
              >
                {String(f.message)}
              </p>
            ))}
          </div>
        )}
        <div className="flex gap-2">
          {[
            ['Quick', PersonalTrainingPreset.QUICK],
            ['Balanced', PersonalTrainingPreset.BALANCED],
            ['Thorough', PersonalTrainingPreset.THOROUGH],
          ].map(([label, value]) => (
            <button
              key={String(label)}
              onClick={() => setPreset(value as PersonalTrainingPreset)}
              className={`px-3 py-2 text-xs ${preset === value ? 'bg-accent-background-30' : 'bg-background-60'}`}
            >
              {label}
            </button>
          ))}
        </div>
        <label className="block text-xs">
          Provider
          <select
            className="ml-2 bg-background-80 p-1"
            value={provider}
            onChange={(e) => setProvider(Number(e.target.value))}
          >
            <option value={PersonalTrainingProvider.AUTO}>Auto</option>
            <option value={PersonalTrainingProvider.CPU}>CPU</option>
            <option value={PersonalTrainingProvider.CUDA}>CUDA</option>
          </select>
        </label>
        <div className="grid grid-cols-2 gap-2 text-xs">
          {(
            [
              ['cpuThreads', 'CPU threads'],
              ['gpuMemoryMib', 'GPU memory MiB'],
              ['gpuUtilizationPercent', 'GPU %'],
              ['ramMib', 'RAM MiB'],
              ['diskMib', 'Disk MiB'],
              ['ioMibPerSecond', 'I/O MiB/s'],
            ] as const
          ).map(([field, label]) => (
            <label key={field}>
              {label}
              <input
                type="number"
                className="w-full bg-background-80 p-1"
                value={Number(resources[field])}
                onChange={(e) => updateResource(field, Number(e.target.value))}
              />
            </label>
          ))}
        </div>
        <label className="block text-xs">
          During active VR
          <select
            className="ml-2 bg-background-80 p-1"
            value={resources.activeVrPolicy}
            onChange={(e) =>
              setResources(
                Object.assign(
                  new PersonalTrainingResourcePolicyT(),
                  resources,
                  { activeVrPolicy: Number(e.target.value) }
                )
              )
            }
          >
            <option value={PersonalTrainingVrPolicy.PAUSE}>
              Checkpoint and pause
            </option>
            <option value={PersonalTrainingVrPolicy.THROTTLE}>Throttle</option>
            <option value={PersonalTrainingVrPolicy.CONTINUE}>Continue</option>
          </select>
        </label>
        <button
          disabled={
            !control.connected || !profileId || !baseModel || !sessions.length
          }
          className="bg-accent-background-30 px-4 py-2 text-sm disabled:opacity-50"
          onClick={() =>
            control.createJob(
              profileId,
              baseModel,
              sessions,
              preset,
              provider,
              resources
            )
          }
        >
          Create training job
        </button>
      </section>
      <section className="space-y-3 rounded-md bg-background-70 p-4">
        <h2 className="text-base font-semibold">Authoritative job status</h2>
        {!status ? (
          <p className="text-xs text-background-30">No active job.</p>
        ) : (
          <>
            <div className="text-xs">
              {String(status.jobId)} · version {String(status.jobVersion)} ·
              state {status.state}
            </div>
            <progress className="w-full" value={status.progress} max={1} />
            <div className="text-xs">
              ETA {String(status.etaSeconds)}s · current{' '}
              {status.currentMetric.toFixed(5)} · best{' '}
              {status.bestMetric.toFixed(5)}
            </div>
            <ol className="grid grid-cols-3 gap-1 text-xs">
              {STAGES.map((stage, index) => (
                <li
                  key={stage}
                  className={
                    status.stage >= index + 1
                      ? 'text-status-success'
                      : 'text-background-40'
                  }
                >
                  {index + 1}. {stage}
                </li>
              ))}
            </ol>
            {status.usage && (
              <div className="text-xs">
                CPU {status.usage.cpuThreads} · GPU {status.usage.gpuMemoryMib}{' '}
                MiB/{status.usage.gpuUtilizationPercent}% · RAM{' '}
                {status.usage.ramMib} MiB · disk {status.usage.diskMib} MiB ·
                I/O {status.usage.ioMibPerSecond.toFixed(1)} MiB/s
              </div>
            )}
            {status.error && (
              <p role="alert" className="text-status-critical">
                {String(status.error)}
              </p>
            )}
            <div className="flex gap-2">
              <button
                onClick={() =>
                  control.jobAction(PersonalTrainingOperation.JOB_START)
                }
              >
                Start/resume
              </button>
              <button
                onClick={() =>
                  control.jobAction(PersonalTrainingOperation.JOB_PAUSE)
                }
              >
                Pause
              </button>
              <button
                onClick={() =>
                  control.jobAction(PersonalTrainingOperation.JOB_CANCEL)
                }
              >
                Cancel
              </button>
              <button
                onClick={() =>
                  control.jobAction(PersonalTrainingOperation.EVALUATE)
                }
              >
                Evaluate
              </button>
              <button
                onClick={() =>
                  control.jobAction(PersonalTrainingOperation.EXPORT)
                }
              >
                Export
              </button>
              <button
                onClick={() =>
                  control.jobAction(PersonalTrainingOperation.ACTIVATE)
                }
              >
                Activate
              </button>
            </div>
          </>
        )}
        {control.lastAction?.error && (
          <p role="alert" className="text-status-critical">
            {String(control.lastAction.error)}
          </p>
        )}
      </section>
    </div>
  );
}
