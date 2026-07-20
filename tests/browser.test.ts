import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getFolder, listFolder, MY_DRIVE } from '../src/browser';

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
    expect(secondUrl.searchParams.get('pageToken')).toBe('page-2');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer secret-token');
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

  it('exports a stable root breadcrumb', () => {
    expect(MY_DRIVE).toEqual({ id: 'root', name: 'My Drive' });
  });
});
