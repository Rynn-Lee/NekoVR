import { useState } from 'react';
import { useLocalization } from '@fluent/react';
import { AIDriftIcon } from '@/components/commons/icon/BrainIcon';
import { useAIModelControl } from '@/hooks/ai-model';
import { useDatasetRecorder } from '@/hooks/dataset-recorder';
import { AIModelControlPanel } from './AIModelControlPanel';
import { DatasetRecorderWidget } from './DatasetRecorderWidget';
import { usePersonalTraining } from '@/hooks/personal-training';
import { PersonalTrainingPanel } from './PersonalTrainingPanel';

export type AIDriftTab = 'correction' | 'datasets' | 'personal';

export function AIDriftPage() {
  const { l10n } = useLocalization();
  const [activeTab, setActiveTab] = useState<AIDriftTab>('correction');

  // Both authoritative subscriptions remain mounted while tab content changes.
  const modelControl = useAIModelControl();
  const datasetControl = useDatasetRecorder();
  const personalTraining = usePersonalTraining();

  const tabs: { id: AIDriftTab; label: string }[] = [
    { id: 'correction', label: l10n.getString('ai_drift-tab-correction') },
    { id: 'datasets', label: l10n.getString('ai_drift-tab-datasets') },
    { id: 'personal', label: l10n.getString('ai_drift-tab-personal') },
  ];

  return (
    <main
      className="mx-auto flex h-full w-full max-w-6xl flex-col overflow-hidden px-4 pt-4 md:px-6 md:pt-6"
      aria-labelledby="ai-drift-page-title"
    >
      <header className="flex items-center gap-3 border-b border-background-50 pb-3">
        <div className="flex h-9 w-9 items-center justify-center fill-background-10">
          <AIDriftIcon size={22} />
        </div>
        <div>
          <h1
            id="ai-drift-page-title"
            className="text-lg font-bold text-background-10"
          >
            {l10n.getString('ai_drift-title')}
          </h1>
          <p className="text-xs text-background-30">
            {l10n.getString('ai_drift-description')}
          </p>
        </div>
      </header>

      <nav
        className="flex gap-1 border-b border-background-50 pt-2"
        role="tablist"
        aria-label={l10n.getString('ai_drift-tabs-label')}
      >
        {tabs.map((tab) => (
          <button
            key={tab.id}
            id={`ai-drift-tab-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.id}
            aria-controls={`ai-drift-panel-${tab.id}`}
            tabIndex={activeTab === tab.id ? 0 : -1}
            className={`border-b-2 px-4 py-2 text-sm font-semibold ${activeTab === tab.id ? 'border-accent-background-20 text-background-10' : 'border-transparent text-background-30 hover:text-background-10'}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      <section
        id={`ai-drift-panel-${activeTab}`}
        role="tabpanel"
        aria-labelledby={`ai-drift-tab-${activeTab}`}
        className="min-h-0 flex-1 overflow-y-auto py-4"
      >
        {activeTab === 'correction' && (
          <AIModelControlPanel control={modelControl} />
        )}
        {activeTab === 'datasets' && (
          <DatasetRecorderWidget control={datasetControl} />
        )}
        {activeTab === 'personal' && (
          <PersonalTrainingPanel
            control={personalTraining}
            availableSessions={datasetControl.sessions}
            availableModels={modelControl.models}
          />
        )}
      </section>
    </main>
  );
}
