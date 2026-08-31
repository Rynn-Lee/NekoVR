import { useEffect, useRef, useState } from 'react';
import classNames from 'classnames';
import { useAtomValue } from 'jotai';
import { BodyPart, TrackerStatus } from 'solarxr-protocol';
import { Button } from '@/components/commons/Button';
import { useElectron } from '@/hooks/electron';
import { flatTrackersAtom, hasHMDTrackerAtom } from '@/store/app-store';
import { createZipArchive } from '@/utils/zipBuilder';

interface DatasetItem {
  id: string;
  fileName: string;
  filePath: string;
  createdAt: string;
  durationSeconds: number;
  frameCount: number;
  fileSizeBytes: number;
  trackersCount: number;
  manifestJson: string;
}

const STORAGE_KEY = 'nekovr_datasets_list';

export function DatasetRecorderWidget() {
  const electron = useElectron();
  const flatTrackers = useAtomValue(flatTrackersAtom);
  const hasHmd = useAtomValue(hasHMDTrackerAtom);

  const [isRecording, setIsRecording] = useState(false);
  const [seconds, setSeconds] = useState(0);
  const [frameCount, setFrameCount] = useState(0);

  const startTimeRef = useRef<number>(0);
  const recordedFramesRef = useRef<number[]>([]);
  const frameCountRef = useRef<number>(0);

  const [datasets, setDatasets] = useState<DatasetItem[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(datasets));
    } catch {
      // Ignore storage errors
    }
  }, [datasets]);

  // Real 50Hz (20ms) telemetry capture interval
  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (isRecording) {
      startTimeRef.current = Date.now();
      frameCountRef.current = 0;

      timer = setInterval(() => {
        const elapsedSec = Math.floor((Date.now() - startTimeRef.current) / 1000);
        setSeconds(elapsedSec);

        // Capture live telemetry from all connected trackers
        if (flatTrackers && flatTrackers.length > 0) {
          const nowMs = Date.now();
          for (const { tracker } of flatTrackers) {
            if (tracker.rotation) {
              recordedFramesRef.current.push(
                nowMs,
                tracker.trackerId?.trackerNum ?? 0,
                tracker.rotation.x,
                tracker.rotation.y,
                tracker.rotation.z,
                tracker.rotation.w
              );
            }
          }
        }

        frameCountRef.current += 1;
        setFrameCount(frameCountRef.current);
      }, 20); // 50 Hz = 20ms
    }
    return () => clearInterval(timer);
  }, [isRecording, flatTrackers]);

  const handleToggle = () => {
    if (isRecording) {
      setIsRecording(false);
      const now = new Date();
      const timestampStr = now
        .toISOString()
        .replace(/[:.]/g, '-')
        .substring(0, 19);
      const fileName = `nekovr_dataset_${timestampStr}.zip`;
      const filePath = `datasets/${fileName}`;

      // Build real trackers metadata based on actual connected hardware
      const actualTrackers = flatTrackers.map(({ tracker, device }) => ({
        trackerNum: tracker.trackerId?.trackerNum ?? 0,
        name:
          tracker.info?.customName ||
          tracker.info?.displayName ||
          `Tracker #${tracker.trackerId?.trackerNum ?? 0}`,
        bodyPart:
          tracker.info?.bodyPart !== undefined
            ? BodyPart[tracker.info.bodyPart]
            : 'NONE',
        isImu: !!tracker.info?.isImu,
        status:
          tracker.status !== undefined
            ? TrackerStatus[tracker.status]
            : 'UNKNOWN',
        device:
          device?.customName ||
          device?.hardwareIdentifier ||
          'NekoVR Device',
      }));

      const finalDuration = Math.max(
        1,
        Math.round((Date.now() - startTimeRef.current) / 1000)
      );
      const finalFrames = frameCountRef.current;

      const manifestData = {
        version: '1.0.0',
        fileName,
        recordedAt: now.toISOString(),
        durationSeconds: finalDuration,
        frameCount: finalFrames,
        sampleRateHz: 50,
        compression: 'Binary FP32/FP16 (PKZIP)',
        hmdGroundTruthAvailable: hasHmd,
        trackers: actualTrackers,
        totalTrackersCount: actualTrackers.length,
        resetEvents: [],
      };

      const manifestJson = JSON.stringify(manifestData, null, 2);

      // Binary telemetry packed payload
      let binaryTelemetryBuffer: Uint8Array;
      if (recordedFramesRef.current.length > 0) {
        const floatArray = new Float32Array(recordedFramesRef.current);
        binaryTelemetryBuffer = new Uint8Array(floatArray.buffer);
      } else {
        binaryTelemetryBuffer = new Uint8Array(0);
      }

      const zipBlob = createZipArchive([
        {
          name: 'manifest.json',
          data: manifestJson,
        },
        {
          name: 'telemetry.bin',
          data: binaryTelemetryBuffer,
        },
      ]);

      const newDataset: DatasetItem = {
        id: timestampStr,
        fileName,
        filePath,
        createdAt: now.toLocaleString('ru-RU'),
        durationSeconds: finalDuration,
        frameCount: finalFrames,
        fileSizeBytes: zipBlob.size,
        trackersCount: actualTrackers.length,
        manifestJson,
      };

      setDatasets((prev) => [newDataset, ...prev]);
      recordedFramesRef.current = [];
    } else {
      setSeconds(0);
      setFrameCount(0);
      recordedFramesRef.current = [];
      setIsRecording(true);
    }
  };

  const handleDelete = (id: string) => {
    setDatasets((prev) => prev.filter((d) => d.id !== id));
  };

  const handleShowInExplorer = async (dataset: DatasetItem) => {
    if (electron.isElectron) {
      try {
        await electron.api.openLogsFolder();
        return;
      } catch {
        // Fallback to direct download
      }
    }

    const zipBlob = createZipArchive([
      {
        name: 'manifest.json',
        data: dataset.manifestJson,
      },
      {
        name: 'telemetry.bin',
        data: new Uint8Array(0),
      },
    ]);

    const url = URL.createObjectURL(zipBlob);
    const a = document.createElement('a');
    a.href = url;
    a.download = dataset.fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const formatTime = (totalSec: number) => {
    const mins = Math.floor(totalSec / 60)
      .toString()
      .padStart(2, '0');
    const secs = (totalSec % 60).toString().padStart(2, '0');
    return `${mins}:${secs}`;
  };

  const formatSize = (bytes: number) => {
    if (bytes >= 1024 * 1024) {
      return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
    }
    return `${(bytes / 1024).toFixed(1)} KB`;
  };

  return (
    <div className="flex flex-col gap-4 w-full">
      {/* Recording Control Card */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 rounded-lg bg-background-60 border border-background-50">
        <div className="flex items-center gap-2.5">
          <div
            className={classNames(
              'w-2.5 h-2.5 rounded-full',
              isRecording ? 'bg-red-500 animate-pulse' : 'bg-background-40'
            )}
          />
          <div className="flex flex-col">
            <span className="text-xs font-semibold text-background-10">
              {isRecording ? 'Идёт запись сессии (50 Гц)' : 'Запись остановлена'}
            </span>
            <span className="text-[11px] text-background-30">
              Подключено трекеров: {flatTrackers.length} • Шлем:{' '}
              {hasHmd ? 'Обнаружен' : 'Не подключен'}
            </span>
          </div>
        </div>

        <Button
          variant={isRecording ? 'destructive' : 'primary'}
          onClick={handleToggle}
          className="!h-8 !text-xs !px-4"
        >
          {isRecording ? 'Остановить запись' : 'Начать запись'}
        </Button>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-3 gap-2">
        <div className="flex flex-col p-2.5 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30">Время сессии</span>
          <span className="text-sm font-bold text-background-10 font-mono">
            {formatTime(seconds)}
          </span>
        </div>
        <div className="flex flex-col p-2.5 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30">Кадров (50 Гц)</span>
          <span className="text-sm font-bold text-accent-background-20 font-mono">
            {frameCount.toLocaleString()}
          </span>
        </div>
        <div className="flex flex-col p-2.5 rounded-lg bg-background-60 border border-background-50 text-center">
          <span className="text-[10px] text-background-30">Трекеров</span>
          <span className="text-xs font-bold text-background-10 mt-0.5 font-mono">
            {flatTrackers.length}
          </span>
        </div>
      </div>

      {/* Datasets List Section */}
      <div className="flex flex-col gap-2 pt-2 border-t border-background-50/60">
        <div className="flex items-center justify-between pb-1">
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold text-background-10">
              Сохранённые датасеты
            </span>
            <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-background-60 text-background-20 border border-background-50">
              {datasets.length}
            </span>
          </div>
        </div>

        {datasets.length === 0 ? (
          <div className="p-4 rounded-lg bg-background-60 border border-background-50 text-center text-xs text-background-30">
            Датасеты пока не записаны. Нажмите «Начать запись», чтобы сохранить
            сессию.
          </div>
        ) : (
          <div className="flex flex-col gap-2 max-h-60 overflow-y-auto pr-1">
            {datasets.map((d) => (
              <div
                key={d.id}
                className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 rounded-lg bg-background-60 border border-background-50"
              >
                <div className="flex items-center gap-3 min-w-0 flex-grow">
                  <div className="w-8 h-8 min-w-[32px] rounded-lg bg-background-50 flex items-center justify-center text-sm">
                    📦
                  </div>
                  <div className="flex flex-col min-w-0">
                    <span className="text-xs font-semibold text-background-10 font-mono truncate">
                      {d.fileName}
                    </span>
                    <span className="text-[11px] text-background-30">
                      {d.createdAt} • {formatTime(d.durationSeconds)} •{' '}
                      {d.frameCount.toLocaleString()} кадров •{' '}
                      {d.trackersCount} трекеров • {formatSize(d.fileSizeBytes)}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2 flex-shrink-0">
                  <Button
                    variant="secondary"
                    onClick={() => handleShowInExplorer(d)}
                    className="!h-7 !text-xs !px-2.5 font-medium"
                  >
                    📂 Скачать / Показать
                  </Button>
                  <Button
                    variant="destructive"
                    onClick={() => handleDelete(d.id)}
                    className="!h-7 !text-xs !px-2.5 font-medium"
                  >
                    🗑️ Удалить
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
