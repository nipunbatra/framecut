import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  filterItems, getFolder, listFolder, MY_DRIVE, searchDrive, SHARED_WITH_ME, sortItems,
  type DriveItem,
} from '../src/browser';

const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' },
});

describe('Drive folder browser', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('follows pagination and normalizes folders, videos, and other files', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({
        nextPageToken: 'page-2',
        files: [
          { id: 'folder', name: 'Lectures', mimeType: 'application/vnd.google-apps.folder' },
          { id: 'video', name: 'Talk.mp4', mimeType: 'video/mp4', size: '42', modifiedTime: '2026-07-20' },
        ],
      }))
      .mockResolvedValueOnce(jsonResponse({
        files: [{ id: 'doc', name: 'Notes.pdf', mimeType: 'application/pdf', size: '7' }],
      }));

    const items = await listFolder("folder'id", 'secret-token');

    expect(items).toEqual([
      expect.objectContaining({ id: 'folder', size: 0, isFolder: true, isVideo: false }),
      expect.objectContaining({ id: 'video', size: 42, isFolder: false, isVideo: true }),
      expect.objectContaining({ id: 'doc', size: 7, isFolder: false, isVideo: false, modifiedTime: '' }),
    ]);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    const firstUrl = new URL(fetchMock.mock.calls[0][0]);
    const secondUrl = new URL(fetchMock.mock.calls[1][0]);
    expect(firstUrl.searchParams.get('q')).toBe("'folder\\'id' in parents and trashed=false");
    expect(firstUrl.searchParams.get('pageSize')).toBe('1000');
    expect(firstUrl.searchParams.get('supportsAllDrives')).toBe('true');
    expect(firstUrl.searchParams.get('corpora')).toBe('allDrives');
    expect(secondUrl.searchParams.get('pageToken')).toBe('page-2');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer secret-token');
  });

  it('lists the virtual "Shared with me" root via the sharedWithMe query', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({
      files: [
        { id: 'shared-folder', name: 'Colleague videos', mimeType: 'application/vnd.google-apps.folder' },
        { id: 'shared-video', name: 'Guest lecture.mp4', mimeType: 'video/mp4', size: '99' },
      ],
    }));

    const items = await listFolder(SHARED_WITH_ME.id, 'token');

    expect(items).toEqual([
      expect.objectContaining({ id: 'shared-folder', isFolder: true }),
      expect.objectContaining({ id: 'shared-video', isVideo: true, size: 99 }),
    ]);
    const url = new URL(fetchMock.mock.calls[0][0]);
    expect(url.searchParams.get('q')).toBe('sharedWithMe and trashed=false');
    // The sharedWithMe query term is only valid in the user corpus.
    expect(url.searchParams.get('corpora')).toBe('user');
  });

  it('surfaces listing HTTP failures', async () => {
    fetchMock.mockResolvedValue(new Response('denied', { status: 403 }));
    await expect(listFolder('root', 'token')).rejects.toThrow('HTTP 403');
  });

  it('stops when Drive repeats a pagination token', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ files: [], nextPageToken: 'stuck' }));

    await expect(listFolder('root', 'token')).rejects.toThrow('repeated a page token');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('resolves folder metadata with authorization', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'abc', name: 'Course videos' }));
    await expect(getFolder('abc', 'token')).resolves.toEqual({ id: 'abc', name: 'Course videos' });
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token');
  });

  it('uses a safe fallback when folder metadata is unavailable', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 404 }));
    await expect(getFolder('missing', 'token')).resolves.toEqual({ id: 'missing', name: 'Folder' });
  });

  it('resolves the virtual shared root without an API call', async () => {
    await expect(getFolder(SHARED_WITH_ME.id, 'token')).resolves.toEqual(SHARED_WITH_ME);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('exports stable root breadcrumbs', () => {
    expect(MY_DRIVE).toEqual({ id: 'root', name: 'My Drive' });
    expect(SHARED_WITH_ME).toEqual({ id: 'shared-with-me', name: 'Shared with me' });
  });

  it('searches all of Drive for videos and folders with escaped names', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({
      files: [
        { id: 'f1', name: 'Lectures', mimeType: 'application/vnd.google-apps.folder', modifiedTime: '2026-08-01T00:00:00Z' },
        { id: 'v1', name: "Prof's lecture.mp4", mimeType: 'video/mp4', size: '5' },
      ],
    }));

    const items = await searchDrive("prof's lec", 'token');

    expect(items).toEqual([
      expect.objectContaining({ id: 'f1', isFolder: true, modifiedTime: '2026-08-01T00:00:00Z' }),
      expect.objectContaining({ id: 'v1', isVideo: true, size: 5 }),
    ]);
    const url = new URL(fetchMock.mock.calls[0][0]);
    expect(url.searchParams.get('q')).toBe(
      "name contains 'prof\\'s lec' and trashed=false and (mimeType='application/vnd.google-apps.folder' or mimeType contains 'video/')",
    );
    expect(url.searchParams.get('corpora')).toBe('allDrives');
    expect(url.searchParams.get('includeItemsFromAllDrives')).toBe('true');
  });

  it('surfaces search HTTP failures', async () => {
    fetchMock.mockResolvedValue(new Response('nope', { status: 500 }));
    await expect(searchDrive('x', 'token')).rejects.toThrow('HTTP 500');
  });
});

describe('client-side sort and filter', () => {
  const item = (over: Partial<DriveItem>): DriveItem => ({
    id: 'x', name: 'x', mimeType: 'video/mp4', size: 0,
    isFolder: false, isVideo: true, modifiedTime: '', ...over,
  });
  const folderB = item({ id: 'fb', name: 'B folder', mimeType: 'application/vnd.google-apps.folder', isFolder: true, isVideo: false, modifiedTime: '2026-01-01T00:00:00Z' });
  const folderA = item({ id: 'fa', name: 'a folder', mimeType: 'application/vnd.google-apps.folder', isFolder: true, isVideo: false, modifiedTime: '2026-06-01T00:00:00Z' });
  const oldSmall = item({ id: 'v1', name: 'Clip 2.mp4', size: 10, modifiedTime: '2025-01-01T00:00:00Z' });
  const newBig = item({ id: 'v2', name: 'Clip 10.mp4', size: 999, modifiedTime: '2026-07-01T00:00:00Z' });

  it('keeps folders first and orders names numerically, case-insensitively', () => {
    const sorted = sortItems([newBig, folderB, oldSmall, folderA], 'name', 1);
    expect(sorted.map((i) => i.id)).toEqual(['fa', 'fb', 'v1', 'v2']);
  });

  it('flips direction and sorts by modified time and size', () => {
    expect(sortItems([oldSmall, newBig], 'modified', -1).map((i) => i.id)).toEqual(['v2', 'v1']);
    expect(sortItems([newBig, oldSmall], 'size', 1).map((i) => i.id)).toEqual(['v1', 'v2']);
    expect(sortItems([folderB, folderA], 'modified', -1).map((i) => i.id)).toEqual(['fa', 'fb']);
  });

  it('does not mutate the input array', () => {
    const input = [newBig, oldSmall];
    sortItems(input, 'size', 1);
    expect(input.map((i) => i.id)).toEqual(['v2', 'v1']);
  });

  it('filters by case-insensitive substring and keeps everything for blank text', () => {
    const all = [folderA, oldSmall, newBig];
    expect(filterItems(all, '  ')).toEqual(all);
    expect(filterItems(all, 'CLIP 1').map((i) => i.id)).toEqual(['v2']);
    expect(filterItems(all, 'folder').map((i) => i.id)).toEqual(['fa']);
  });
});
