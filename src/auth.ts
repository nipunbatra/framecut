import { CONFIG } from './config';

let tokenClient: any = null;
let accessToken: string | null = null;
let tokenExpiry = 0; // epoch ms
let tokenRequestPromise: Promise<string> | null = null;

const loaded = new Map<string, Promise<void>>();
let initPromise: Promise<void> | null = null;

export function loadScript(src: string): Promise<void> {
  if (!loaded.has(src)) {
    const promise = new Promise<void>((resolve, reject) => {
      const s = document.createElement('script');
      s.src = src;
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => {
        s.remove();
        loaded.delete(src);
        reject(new Error(`Failed to load ${src}`));
      };
      document.head.appendChild(s);
    });
    loaded.set(src, promise);
  }
  return loaded.get(src)!;
}

export async function initAuth(): Promise<void> {
  if (!initPromise) {
    initPromise = loadScript('https://accounts.google.com/gsi/client')
      .then(() => {
        tokenClient = google.accounts.oauth2.initTokenClient({
          client_id: CONFIG.CLIENT_ID,
          scope: CONFIG.SCOPES,
          callback: () => {}, // replaced per-request in getToken()
        });
      })
      .catch((error) => {
        initPromise = null;
        throw error;
      });
  }
  await initPromise;
}

/**
 * Returns a valid access token, prompting the user only when needed.
 * Tokens live ~1h; we refresh 5 minutes early. GIS reuses the existing
 * Google session, so silent renewals do not show UI.
 */
export async function getToken(interactive = true): Promise<string> {
  if (accessToken && Date.now() < tokenExpiry - 5 * 60_000) {
    return accessToken;
  }
  if (!tokenClient) await initAuth();
  if (tokenRequestPromise) return tokenRequestPromise;

  const request = new Promise<string>((resolve, reject) => {
    tokenClient.callback = (resp: any) => {
      if (resp.error) {
        reject(new Error(`Auth failed: ${resp.error}`));
        return;
      }
      accessToken = resp.access_token;
      tokenExpiry = Date.now() + Number(resp.expires_in) * 1000;
      resolve(accessToken!);
    };
    tokenClient.error_callback = (err: any) => {
      reject(new Error(`Auth failed: ${err?.type ?? 'popup error'}`));
    };
    // '' lets Google decide: shows consent on first use, silent after.
    tokenClient.requestAccessToken({ prompt: interactive ? '' : 'none' });
  });
  tokenRequestPromise = request;
  try {
    return await request;
  } finally {
    if (tokenRequestPromise === request) tokenRequestPromise = null;
  }
}

export function hasToken(): boolean {
  return accessToken !== null && Date.now() < tokenExpiry;
}

export function currentToken(): string | null {
  return hasToken() ? accessToken : null;
}

export function signOut(): void {
  if (accessToken) {
    try {
      google.accounts.oauth2.revoke(accessToken, () => {});
    } catch {
      /* revoke is best-effort */
    }
  }
  accessToken = null;
  tokenExpiry = 0;
}

/** Fetch the signed-in user's email via the Drive about endpoint. */
export async function fetchUserEmail(token: string): Promise<string> {
  const r = await fetch(
    'https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)',
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!r.ok) return '';
  const j = await r.json();
  return j.user?.emailAddress ?? '';
}
