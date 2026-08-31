import { useEffect, useState } from 'react';
import classNames from 'classnames';
import { detectHardwareGPU, HardwareInfo } from '@/utils/hardwareDetection';

export function GPUStatusBadge({
  isModelLoaded = false,
  isAiEnabled = true,
}: {
  isModelLoaded?: boolean;
  isAiEnabled?: boolean;
}) {
  const [hardware, setHardware] = useState<HardwareInfo>({
    gpuName: 'Определение оборудования...',
    vendor: 'Unknown',
    provider: 'CPU',
    isHardwareAccelerated: false,
  });

  useEffect(() => {
    const info = detectHardwareGPU();
    setHardware(info);
  }, []);

  const isReady = isModelLoaded && isAiEnabled;

  return (
    <div className="flex flex-col gap-2 w-full">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 rounded-lg bg-background-60 border border-background-50">
        <div className="flex items-center gap-3">
          <div
            className={classNames(
              'w-2.5 h-2.5 rounded-full',
              isReady
                ? 'bg-green-500'
                : hardware.isHardwareAccelerated
                ? 'bg-yellow-500'
                : 'bg-orange-500'
            )}
          />
          <div className="flex flex-col">
            <span className="text-xs font-semibold text-background-10">
              {hardware.gpuName}
            </span>
            <span className="text-[11px] text-background-30">
              Бэкенд: {hardware.provider} •{' '}
              {hardware.isHardwareAccelerated
                ? 'Аппаратное ускорение доступно'
                : 'Программный режим (CPU)'}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-background-70 border border-background-50 text-xs">
          <span className="text-background-30">Статус ИИ:</span>
          <span
            className={classNames(
              'font-semibold text-[11px]',
              isReady ? 'text-green-400' : 'text-background-30'
            )}
          >
            {!isAiEnabled
              ? 'Отключен пользователем'
              : isModelLoaded
              ? 'Готов к инференсу'
              : 'Ожидает файл модели'}
          </span>
        </div>
      </div>
    </div>
  );
}
