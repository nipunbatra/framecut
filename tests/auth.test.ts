// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

interface Harness {
  client: {
    callback: (response: unknown) => void;
    error_callback: (error: unknown) => void;
    requestAccessToken: ReturnType<typeof vi.fn>;
  };
  initTokenClient: ReturnType<typeof vi.fn>;
  revoke: ReturnType<typeof vi.fn>;
}

let appendedScripts: HTMLScriptElement[] = [];

function googleHarness(responses: unknown[] = [{ access_token: 'token-1', expires_in: 3600 }]): Harness {
  const queue = [...responses];
  const client = {
    callback: (_response: unknown) => {},
    error_callback: (_error: unknown) => {},
    requestAccessToken: vi.fn(() => client.callback(queue.shift())),
  };
  const initTokenClient = vi.fn(() => client);
  const revoke = vi.fn((_token: string, done: () => void) => done());
  vi.stubGlobal('google', { accounts: { oauth2: { initTokenClient, revoke } } });
  return { client, initTokenClient, revoke };
}

function dispatchScript(type: 'load' | 'error'): void {
  const script = appendedScripts.at(-1);
  if (!script) throw new Error('Google script was not added');
  script.dispatchEvent(new Event(type));
}

describe('Google authorization', () => {
  beforeEach(() => {
    vi.resetModules();
    document.head.innerHTML = '';
    document.body.innerHTML = '';
    appendedScripts = [];
    vi.spyOn(document.head, 'appendChild').mockImplementation((node) => {
      appendedScripts.push(node as HTMLScriptElement);
      return node;
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('initializes one keyless Drive token client', async () => {
    const harness = googleHarness();
    const auth = await import('../src/auth');
    const first = auth.initAuth();
    const second = auth.initAuth();
    expect(appendedScripts).toHaveLength(1);
    dispatchScript('load');
    await Promise.all([first, second]);

    expect(harness.initTokenClient).toHaveBeenCalledTimes(1);
    expect(harness.initTokenClient).toHaveBeenCalledWith(expect.objectContaining({
      client_id: expect.stringMatching(/^754571415429-.+\.apps\.googleusercontent\.com$/),
      scope: 'https://www.googleapis.com/auth/drive',
    }));
  });

  it('waits for initialization when sign-in is clicked early', async () => {
    const harness = googleHarness();
    const auth = await import('../src/auth');
    const tokenPromise = auth.getToken(true);
    dispatchScript('load');

    await expect(tokenPromise).resolves.toBe('token-1');
    expect(harness.client.requestAccessToken).toHaveBeenCalledWith({ prompt: '' });
  });

  it('coalesces concurrent token requests into one GIS interaction', async () => {
    const harness = googleHarness();
    const auth = await import('../src/auth');
    const first = auth.getToken(true);
    const second = auth.getToken(true);
    dispatchScript('load');

    await expect(Promise.all([first, second])).resolves.toEqual(['token-1', 'token-1']);
    expect(harness.client.requestAccessToken).toHaveBeenCalledTimes(1);
  });

  it('reuses a fresh in-memory token without another popup request', async () => {
    const harness = googleHarness();
    const auth = await import('../src/auth');
    const first = auth.getToken();
    dispatchScript('load');
    await expect(first).resolves.toBe('token-1');
    await expect(auth.getToken()).resolves.toBe('token-1');
    expect(harness.client.requestAccessToken).toHaveBeenCalledTimes(1);
  });

  it('renews a token inside the five-minute expiry window', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-20T00:00:00Z'));
    const harness = googleHarness([
      { access_token: 'token-1', expires_in: 3600 },
      { access_token: 'token-2', expires_in: 3600 },
    ]);
    const auth = await import('../src/auth');
    const first = auth.getToken();
    dispatchScript('load');
    await expect(first).resolves.toBe('token-1');

    vi.setSystemTime(new Date('2026-07-20T00:56:00Z'));
    await expect(auth.getToken()).resolves.toBe('token-2');
    expect(harness.client.requestAccessToken).toHaveBeenCalledTimes(2);
  });

  it('uses a silent prompt for non-interactive renewal', async () => {
    const harness = googleHarness();
    const auth = await import('../src/auth');
    const token = auth.getToken(false);
    dispatchScript('load');
    await token;
    expect(harness.client.requestAccessToken).toHaveBeenCalledWith({ prompt: 'none' });
  });

  it('reports popup failures', async () => {
    const harness = googleHarness();
    harness.client.requestAccessToken.mockImplementation(() => {
      harness.client.error_callback({ type: 'popup_failed_to_open' });
    });
    const auth = await import('../src/auth');
    const token = auth.getToken();
    dispatchScript('load');
    await expect(token).rejects.toThrow('popup_failed_to_open');
  });

  it('removes a failed script so authorization can retry', async () => {
    googleHarness();
    const auth = await import('../src/auth');
    const failed = auth.loadScript('https://accounts.google.com/gsi/client');
    dispatchScript('error');
    await expect(failed).rejects.toThrow('Failed to load');
    expect(appendedScripts).toHaveLength(1);

    const retry = auth.loadScript('https://accounts.google.com/gsi/client');
    expect(retry).not.toBe(failed);
    expect(appendedScripts).toHaveLength(2);
    dispatchScript('load');
    await expect(retry).resolves.toBeUndefined();
  });

  it('revokes and clears the current token on sign-out', async () => {
    const harness = googleHarness();
    const auth = await import('../src/auth');
    const token = auth.getToken();
    dispatchScript('load');
    await token;

    auth.signOut();
    expect(harness.revoke).toHaveBeenCalledWith('token-1', expect.any(Function));
    expect(auth.currentToken()).toBeNull();
    expect(auth.hasToken()).toBe(false);
  });

  it('reads the account email through Drive and tolerates API errors', async () => {
    googleHarness();
    const auth = await import('../src/auth');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ user: { emailAddress: 'n@example.com' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response('', { status: 503 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(auth.fetchUserEmail('token')).resolves.toBe('n@example.com');
    await expect(auth.fetchUserEmail('token')).resolves.toBe('');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token');
  });
});
