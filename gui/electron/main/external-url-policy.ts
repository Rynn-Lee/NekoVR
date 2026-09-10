export type ExternalUrlOpener = (url: string) => void | Promise<void>;

const DANGEROUS_ENCODING = /%(?:2e|2f|5c)/i;

function containsControlOrSpace(input: string): boolean {
  return [...input].some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint <= 0x20 || codePoint === 0x7f;
  });
}

function safeHttpsUrl(url: URL): boolean {
  return url.protocol === 'https:' && !url.username && !url.password && !url.port;
}

export function isExternalUrlAllowed(input: string): boolean {
  if (!input || input.length > 2048 || containsControlOrSpace(input)) return false;
  if (DANGEROUS_ENCODING.test(input)) return false;

  if (input === 'ms-settings:network') return true;
  if (/^steam:\/\/[A-Za-z0-9][A-Za-z0-9/_?&=+.-]*$/.test(input)) return true;

  let url: URL;
  try {
    url = new URL(input);
  } catch {
    return false;
  }
  if (!safeHttpsUrl(url)) return false;

  const host = url.hostname.toLowerCase();
  if (host === 'slimevr.dev' || host.endsWith('.slimevr.dev')) return true;
  if (host === 'github.com') {
    return url.pathname === '/SlimeVR' || url.pathname.startsWith('/SlimeVR/');
  }
  return (
    host === 'discord.gg' &&
    (url.pathname === '/slimevr' || url.pathname === '/slimevr/')
  );
}

export async function openApprovedExternalUrl(
  url: string,
  opener: ExternalUrlOpener
): Promise<boolean> {
  if (!isExternalUrlAllowed(url)) return false;
  await opener(url);
  return true;
}

export function handleExternalNavigation(
  event: { preventDefault(): void },
  url: string,
  opener: ExternalUrlOpener
): void {
  event.preventDefault();
  void openApprovedExternalUrl(url, opener).catch(() => undefined);
}

export function handleExternalWindowOpen(
  url: string,
  opener: ExternalUrlOpener
): { action: 'deny' } {
  void openApprovedExternalUrl(url, opener).catch(() => undefined);
  return { action: 'deny' };
}
