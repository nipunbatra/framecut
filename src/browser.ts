// Custom Drive browser: lists folders and videos via the Drive REST API using
// the user's OAuth token (full `drive` scope). Replaces the Google Picker.
const API = 'https://www.googleapis.com/drive/v3';

export interface DriveItem {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  isFolder: boolean;
  isVideo: boolean;
  modifiedTime: string;
}

export interface Crumb {
  id: string;
  name: string;
}

const FOLDER_MIME = 'application/vnd.google-apps.folder';

function toItem(f: {
  id: string; name: string; mimeType: string; size?: string; modifiedTime?: string;
}): DriveItem {
  return {
    id: f.id,
    name: f.name,
    mimeType: f.mimeType,
    size: Number(f.size ?? 0),
    isFolder: f.mimeType === FOLDER_MIME,
    isVideo: f.mimeType.startsWith('video/'),
    modifiedTime: f.modifiedTime ?? '',
  };
}

export type SortKey = 'name' | 'modified' | 'size';

/**
 * Sort folders first, then by the chosen key; ties fall back to name order so
 * the result is stable and predictable. `dir` flips the primary key only.
 */
export function sortItems(items: DriveItem[], key: SortKey, dir: 1 | -1): DriveItem[] {
  return [...items].sort((a, b) => {
    if (a.isFolder !== b.isFolder) return a.isFolder ? -1 : 1;
    const byName = a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
    if (key === 'name') return dir * byName;
    const d = key === 'size' ? a.size - b.size : a.modifiedTime.localeCompare(b.modifiedTime);
    return d ? dir * d : byName;
  });
}

/** Case-insensitive substring filter on item names. Empty text keeps all. */
export function filterItems(items: DriveItem[], text: string): DriveItem[] {
  const needle = text.trim().toLowerCase();
  if (!needle) return items;
  return items.filter((i) => i.name.toLowerCase().includes(needle));
}

/**
 * Search everywhere the user can reach — My Drive, shared with me, and shared
 * drives — for videos and folders whose name matches. Single request capped at
 * 1000 results; the caller sorts client-side.
 */
export async function searchDrive(text: string, token: string): Promise<DriveItem[]> {
  const escaped = text.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  const params = new URLSearchParams({
    q: `name contains '${escaped}' and trashed=false and (mimeType='${FOLDER_MIME}' or mimeType contains 'video/')`,
    fields: 'files(id,name,mimeType,size,modifiedTime)',
    pageSize: '1000',
    supportsAllDrives: 'true',
    includeItemsFromAllDrives: 'true',
    corpora: 'allDrives',
  });
  const r = await fetch(`${API}/files?${params}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!r.ok) throw new Error(`Search failed (HTTP ${r.status})`);
  const j = await r.json();
  return (j.files ?? []).map(toItem);
}

/**
 * List children of a folder. Folders first, then everything else, name-sorted.
 * Follows pagination so large folders return fully. Works across My Drive and
 * shared drives. The virtual "Shared with me" root has no real folder id, so
 * it is listed with Drive's `sharedWithMe` query (only valid in the user
 * corpus) instead of `in parents`.
 */
export async function listFolder(folderId: string, token: string): Promise<DriveItem[]> {
  const items: DriveItem[] = [];
  let pageToken: string | undefined;
  const seenPageTokens = new Set<string>();
  const shared = folderId === SHARED_WITH_ME.id;
  const escapedFolderId = folderId.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  do {
    const marker = pageToken ?? '';
    if (seenPageTokens.has(marker)) {
      throw new Error('Drive repeated a page token while listing this folder');
    }
    seenPageTokens.add(marker);
    const params = new URLSearchParams({
      q: shared
        ? 'sharedWithMe and trashed=false'
        : `'${escapedFolderId}' in parents and trashed=false`,
      fields: 'nextPageToken, files(id,name,mimeType,size,modifiedTime)',
      orderBy: 'folder,name',
      pageSize: '1000',
      supportsAllDrives: 'true',
      includeItemsFromAllDrives: 'true',
      corpora: shared ? 'user' : 'allDrives',
    });
    if (pageToken) params.set('pageToken', pageToken);
    const r = await fetch(`${API}/files?${params}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) throw new Error(`Could not list folder (HTTP ${r.status})`);
    const j = await r.json();
    for (const f of j.files ?? []) items.push(toItem(f));
    pageToken = j.nextPageToken;
  } while (pageToken);
  return items;
}

/** Human display name for the root of My Drive. */
export const MY_DRIVE: Crumb = { id: 'root', name: 'My Drive' };

/**
 * Virtual root for files and folders shared with the user. Not a real Drive
 * folder — nothing can be created or uploaded directly under it.
 */
export const SHARED_WITH_ME: Crumb = { id: 'shared-with-me', name: 'Shared with me' };

/** Resolve a folder's own metadata (used to seed breadcrumbs when needed). */
export async function getFolder(folderId: string, token: string): Promise<Crumb> {
  if (folderId === SHARED_WITH_ME.id) return SHARED_WITH_ME;
  const r = await fetch(
    `${API}/files/${folderId}?fields=id,name&supportsAllDrives=true`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!r.ok) return { id: folderId, name: 'Folder' };
  const j = await r.json();
  return { id: j.id, name: j.name };
}
