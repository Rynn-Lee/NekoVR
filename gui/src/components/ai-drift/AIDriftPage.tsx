import { useState } from 'react';
import { AIDriftIcon } from '@/components/commons/icon/BrainIcon';
import { WrenchIcon } from '@/components/commons/icon/WrenchIcons';
import { RecordIcon } from '@/components/commons/icon/RecordIcon';
import { DownloadIcon } from '@/components/commons/icon/DownloadIcon';
import { GPUStatusBadge } from './GPUStatusBadge';
import { ModelFilePicker } from './ModelFilePicker';
import { DatasetRecorderWidget } from './DatasetRecorderWidget';
import { AutoUpdaterWidget } from '@/components/updater/AutoUpdaterWidget';
import { Typography } from '@/components/commons/Typography';

export function AIDriftPage() {
  const [aiEnabled, setAiEnabled] = useState(true);
  const [loadedModelFile, setLoadedModelFile] = useState<File | null>(null);

  return (
    <div className="flex flex-col gap-4 p-4 md:p-6 max-w-4xl mx-auto w-full h-full overflow-y-auto">
      {/* 1. ИИ-коррекция дрифта */}
      <div className="bg-background-70 border border-background-50 rounded-2xl p-5 shadow-sm flex flex-col gap-4">
        <div className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-3.5">
            <div className="w-10 h-10 bg-background-60 border border-background-50 flex justify-center items-center rounded-xl fill-background-10 text-background-10">
              <AIDriftIcon size={22} />
            </div>
            <div className="flex flex-col">
              <Typography
                variant="section-title"
                className="!text-base font-bold text-background-10"
              >
                ИИ-коррекция дрифта
              </Typography>
              <span className="text-xs text-background-30">
                Автоматическое устранение дрифта IMU-трекеров нейросетью в
                реальном времени
              </span>
            </div>
          </div>

          <label className="flex items-center gap-2 cursor-pointer select-none">
            <span className="text-xs font-semibold text-background-20">
              {aiEnabled ? 'Включено' : 'Выключено'}
            </span>
            <input
              type="checkbox"
              checked={aiEnabled}
              onChange={(e) => setAiEnabled(e.target.checked)}
              className="w-5 h-5 rounded accent-accent-background-30 cursor-pointer"
            />
          </label>
        </div>

        <GPUStatusBadge
          isModelLoaded={!!loadedModelFile}
          isAiEnabled={aiEnabled}
        />
      </div>

      {/* 2. Файл модели */}
      <div className="bg-background-70 border border-background-50 rounded-2xl p-5 shadow-sm flex flex-col gap-3">
        <div className="flex items-center gap-3.5 mb-1">
          <div className="w-10 h-10 bg-background-60 border border-background-50 flex justify-center items-center rounded-xl fill-background-10 text-background-10">
            <WrenchIcon />
          </div>
          <div className="flex flex-col">
            <Typography
              variant="section-title"
              className="!text-base font-bold text-background-10"
            >
              Файл модели (.onnx)
            </Typography>
            <span className="text-xs text-background-30">
              Загрузка и подключение обученной модели нейросети
            </span>
          </div>
        </div>

        <ModelFilePicker onModelChange={setLoadedModelFile} />
      </div>

      {/* 3. Сбор датасетов */}
      <div className="bg-background-70 border border-background-50 rounded-2xl p-5 shadow-sm flex flex-col gap-3">
        <div className="flex items-center gap-3.5 mb-1">
          <div className="w-10 h-10 bg-background-60 border border-background-50 flex justify-center items-center rounded-xl fill-background-10 text-background-10">
            <RecordIcon />
          </div>
          <div className="flex flex-col">
            <Typography
              variant="section-title"
              className="!text-base font-bold text-background-10"
            >
              Сбор датасетов (50 Гц)
            </Typography>
            <span className="text-xs text-background-30">
              Запись ориентации шлема и IMU-трекеров для обучения нейросетей
            </span>
          </div>
        </div>

        <DatasetRecorderWidget />
      </div>

      {/* 4. Автообновление */}
      <div className="bg-background-70 border border-background-50 rounded-2xl p-5 shadow-sm flex flex-col gap-3">
        <div className="flex items-center gap-3.5 mb-1">
          <div className="w-10 h-10 bg-background-60 border border-background-50 flex justify-center items-center rounded-xl fill-background-10 text-background-10">
            <DownloadIcon />
          </div>
          <div className="flex flex-col">
            <Typography
              variant="section-title"
              className="!text-base font-bold text-background-10"
            >
              Автообновление (GitHub)
            </Typography>
            <span className="text-xs text-background-30">
              Синхронизация и проверка обновлений из ветки main
            </span>
          </div>
        </div>

        <AutoUpdaterWidget />
      </div>
    </div>
  );
}
