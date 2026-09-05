import { useLocalization } from '@fluent/react';
import { useOnboarding } from '@/hooks/onboarding';
import { Button } from '@/components/commons/Button';
import { SlimeVRIcon } from '@/components/commons/icon/SimevrIcon';
import { LangSelector } from '@/components/commons/LangSelector';
import { Typography } from '@/components/commons/Typography';
import { useNavigate } from 'react-router-dom';
import { useConfig } from '@/hooks/config';

export function HomePage() {
  const nav = useNavigate();
  const { l10n } = useLocalization();
  const { applyProgress, onboardingStarted } = useOnboarding();
  const { setConfig } = useConfig();

  applyProgress(0.1);

  const start = () => {
    onboardingStarted();
    nav('/onboarding/quiz/slime-set');
  };

  const handleSkip = async () => {
    await setConfig({ doneOnboarding: true });
    nav('/');
  };

  return (
    <div className="flex relative flex-col h-full items-center justify-center px-4 py-6 overflow-hidden select-none">
      {/* Background Decorative Ambient Glows */}
      <div className="absolute w-[500px] h-[500px] bg-accent-background-30/10 rounded-full blur-3xl pointer-events-none -top-20 -left-20 animate-pulse" />
      <div className="absolute w-[450px] h-[450px] bg-accent-background-30/10 rounded-full blur-3xl pointer-events-none -bottom-20 -right-20" />

      {/* Main Hero Card Container */}
      <div className="relative z-10 flex flex-col items-center max-w-lg w-full bg-background-70/90 backdrop-blur-xl border border-background-50 rounded-3xl p-8 md:p-10 shadow-2xl transition-all duration-300">
        {/* NekoVR Logo Badge with Glowing Ring */}
        <div className="relative mb-6 group">
          <div className="absolute -inset-2 bg-gradient-to-r from-accent-background-30 to-accent-background-20 rounded-3xl blur opacity-30 group-hover:opacity-60 transition duration-300" />
          <div className="relative w-24 h-24 rounded-2xl bg-background-60 border border-background-40 flex items-center justify-center p-3 shadow-inner">
            <SlimeVRIcon width={64} height={64} />
          </div>
        </div>

        {/* Title & Tagline */}
        <div className="flex flex-col items-center text-center gap-2 mb-8">
          <Typography
            variant="main-title"
            className="!text-2xl md:!text-3xl font-extrabold tracking-tight text-background-10"
          >
            {l10n.getString('onboarding-home') || 'Добро пожаловать в NekoVR'}
          </Typography>
          <Typography className="text-background-30 text-xs md:text-sm max-w-sm font-medium">
            {l10n.getString('onboarding-home-description') ||
              'Система отслеживания движений всего тела нового поколения'}
          </Typography>
        </div>

        {/* Action Buttons */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-3 w-full">
          <Button
            variant="primary"
            onClick={start}
            className="w-full sm:w-auto min-w-[180px] h-12 text-sm md:text-base font-bold shadow-lg shadow-accent-background-30/20 hover:shadow-accent-background-30/40 rounded-xl"
          >
            {l10n.getString('onboarding-home-start') || 'Начать настройку'}
          </Button>
          <Button
            variant="tertiary"
            onClick={handleSkip}
            className="w-full sm:w-auto h-12 text-xs md:text-sm font-semibold rounded-xl text-background-20 hover:text-background-10"
          >
            {l10n.getString('onboarding-skip') || 'Пропустить настройку'}
          </Button>
        </div>
      </div>

      {/* Floating Bottom Language Selector */}
      <div className="absolute right-4 bottom-4 z-50">
        <div className="bg-background-70/90 backdrop-blur-md border border-background-50 rounded-xl shadow-lg p-1">
          <LangSelector />
        </div>
      </div>
    </div>
  );
}
