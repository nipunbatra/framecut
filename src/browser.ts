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

/**
 * List children of a folder. Folders first, then everything else, name-sorted.
 * Follows pagination so large folders return fully. Works across My Drive and
 * shared drives.
 */
export async function listFolder(folderId: string, token: string): Promise<DriveItem[]> {
  const items: DriveItem[] = [];
  let pageToken: string | undefined;
  const seenPageTokens = new Set<string>();
  const escapedFolderId = folderId.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
  do {
    const marker = pageToken ?? '';
    if (seenPageTokens.has(marker)) {
      throw new Error('Drive repeated a page token while listing this folder');
    }
    seenPageTokens.add(marker);
    const params = new URLSearchParams({
      q: `'${escapedFolderId}' in parents and trashed=false`,
      fields: 'nextPageToken, files(id,name,mimeType,size,modifiedTime)',
      orderBy: 'folder,name',
      pageSize: '1000',
      supportsAllDrives: 'true',
      includeItemsFromAllDrives: 'true',
      corpora: 'allDrives',
    });
    if (pageToken) params.set('pageToken', pageToken);
    const r = await fetch(`${API}/files?${params}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) throw new Error(`Could not list folder (HTTP ${r.status})`);
    const j = await r.json();
    for (const f of j.files ?? []) {
      const isFolder = f.mimeType === FOLDER_MIME;
      items.push({
        id: f.id,
        name: f.name,
        mimeType: f.mimeType,
        size: Number(f.size ?? 0),
        isFolder,
        isVideo: (f.mimeType as string).startsWith('video/'),
        modifiedTime: f.modifiedTime ?? '',
      });
    }
    pageToken = j.nextPageToken;
  } while (pageToken);
  return items;
}

/** Human display name for the root of My Drive. */
export const MY_DRIVE: Crumb = { id: 'root', name: 'My Drive' };

/** Resolve a folder's own metadata (used to seed breadcrumbs when needed). */
export async function getFolder(folderId: string, token: string): Promise<Crumb> {
  const r = await fetch(
    `${API}/files/${folderId}?fields=id,name&supportsAllDrives=true`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!r.ok) return { id: folderId, name: 'Folder' };
  const j = await r.json();
  return { id: j.id, name: j.name };
}
