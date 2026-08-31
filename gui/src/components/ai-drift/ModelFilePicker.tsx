import { useRef, useState, ChangeEvent } from 'react';
import { Button } from '@/components/commons/Button';

export function ModelFilePicker({
  onModelChange,
}: {
  onModelChange?: (file: File | null) => void;
}) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] || null;
    setSelectedFile(file);
    if (onModelChange) {
      onModelChange(file);
    }
  };

  const handleClear = () => {
    setSelectedFile(null);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
    if (onModelChange) {
      onModelChange(null);
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes >= 1024 * 1024) {
      return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
    }
    return `${(bytes / 1024).toFixed(1)} KB`;
  };

  return (
    <div className="flex flex-col gap-3 w-full">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 rounded-lg bg-background-60 border border-background-50">
        <div className="flex flex-col">
          <span className="text-xs font-semibold text-background-10 font-mono">
            {selectedFile ? selectedFile.name : 'Файл модели не выбран'}
          </span>
          <span className="text-[11px] text-background-30">
            {selectedFile
              ? `Размер: ${formatFileSize(selectedFile.size)} • Формат: ONNX Runtime`
              : 'Выберите файл .onnx с диска для активации нейросетевой модели'}
          </span>
        </div>

        <div className="flex items-center gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept=".onnx"
            onChange={handleFileChange}
            className="hidden"
          />
          {selectedFile && (
            <Button
              variant="tertiary"
              onClick={handleClear}
              className="!h-8 !text-xs !px-3"
            >
              Очистить
            </Button>
          )}
          <Button
            variant="secondary"
            onClick={() => fileInputRef.current?.click()}
            className="!h-8 !text-xs !px-3"
          >
            📁 Выбрать файл (.onnx)
          </Button>
        </div>
      </div>
    </div>
  );
}
