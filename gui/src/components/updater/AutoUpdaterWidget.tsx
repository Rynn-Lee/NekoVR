import { useState } from 'react';
import { Button } from '@/components/commons/Button';

interface CommitInfo {
  sha: string;
  message: string;
  date: string;
  url: string;
}

export function AutoUpdaterWidget() {
  const [isChecking, setIsChecking] = useState(false);
  const [commitInfo, setCommitInfo] = useState<CommitInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleCheck = async () => {
    setIsChecking(true);
    setError(null);
    try {
      const relRes = await fetch(
        'https://api.github.com/repos/Rynn-Lee/NekoVR/releases/latest',
        { headers: { Accept: 'application/vnd.github.v3+json' } }
      );

      if (relRes.ok) {
        const relData = await relRes.json();
        setCommitInfo({
          sha: relData.tag_name || 'Release',
          message: relData.name || relData.body || 'Новый релиз',
          date: relData.published_at || '',
          url: relData.html_url || 'https://github.com/Rynn-Lee/NekoVR/releases',
        });
      } else {
        const comRes = await fetch(
          'https://api.github.com/repos/Rynn-Lee/NekoVR/commits/main',
          { headers: { Accept: 'application/vnd.github.v3+json' } }
        );

        if (comRes.ok) {
          const comData = await comRes.json();
          setCommitInfo({
            sha: (comData.sha || '').substring(0, 7),
            message: comData.commit?.message?.split('\n')[0] || 'Коммит ветки main',
            date: comData.commit?.author?.date
              ? new Date(comData.commit.author.date).toLocaleString('ru-RU')
              : '',
            url:
              comData.html_url ||
              'https://github.com/Rynn-Lee/NekoVR/tree/main',
          });
        } else {
          setError(`Ошибка GitHub API (статус ${comRes.status})`);
        }
      }
    } catch (err: any) {
      setError(err?.message || 'Не удалось связаться с GitHub API');
    } finally {
      setIsChecking(false);
    }
  };

  return (
    <div className="flex flex-col gap-3 w-full">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 rounded-lg bg-background-60 border border-background-50">
        <div className="flex flex-col">
          <span className="text-xs font-semibold text-background-10">
            Репозиторий Rynn-Lee/NekoVR (ветка main)
          </span>
          <span className="text-[11px] text-background-30">
            Проверка обновлений исходного кода и релизов в один клик
          </span>
        </div>

        <Button
          variant="secondary"
          onClick={handleCheck}
          disabled={isChecking}
          className="!h-8 !text-xs !px-3"
        >
          {isChecking ? 'Проверка...' : 'Проверить обновления'}
        </Button>
      </div>

      {commitInfo && (
        <div className="flex flex-col gap-1.5 p-3 rounded-lg bg-background-60 border border-background-50 text-xs">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="text-background-30">Ветка main:</span>
              <a
                href={commitInfo.url}
                target="_blank"
                rel="noreferrer"
                className="font-mono text-accent-background-20 hover:underline"
              >
                {commitInfo.sha}
              </a>
            </div>
            {commitInfo.date && (
              <span className="text-[11px] text-background-30">
                {commitInfo.date}
              </span>
            )}
          </div>
          <span className="text-background-10 text-[11px]">
            «{commitInfo.message}»
          </span>
        </div>
      )}

      {error && (
        <div className="flex items-center gap-2 p-2.5 rounded-lg bg-red-500/10 border border-red-500/30 text-xs text-red-200">
          <span>⚠️</span>
          <span>{error}</span>
        </div>
      )}
    </div>
  );
}
