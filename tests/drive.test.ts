import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createFolder,
  downloadFile,
  resumableUpload,
  shareAnyone,
  uploadSmallFile,
} from '../src/drive';

const jsonResponse = (body: unknown, init: ResponseInit = {}) => new Response(JSON.stringify(body), {
  ...init,
  headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) },
});

describe('Drive transfers and mutations', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('streams a small download with bearer auth and progress', async () => {
    fetchMock.mockResolvedValue(new Response(new Uint8Array([1, 2, 3]), {
      status: 200,
      headers: { 'Content-Length': '3' },
    }));
    const progress = vi.fn();

    const blob = await downloadFile('file-id', 'token', 3, progress);

    expect([...new Uint8Array(await blob.arrayBuffer())]).toEqual([1, 2, 3]);
    expect(progress).toHaveBeenLastCalledWith(3, 3);
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer token');
  });

  it('rejects a failed streaming download', async () => {
    fetchMock.mockResolvedValue(new Response('denied', { status: 403 }));
    await expect(downloadFile('file-id', 'token', 3, vi.fn())).rejects.toThrow('HTTP 403');
  });

  it('rejects a truncated streaming response with a declared length', async () => {
    fetchMock.mockResolvedValue(new Response(new Uint8Array([1]), {
      status: 200,
      headers: { 'Content-Length': '3' },
    }));

    await expect(downloadFile('file-id', 'token', 3, vi.fn()))
      .rejects.toThrow('Download incomplete (received 1 of 3 bytes)');
  });

  it('downloads large files in complete byte ranges', async () => {
    const chunk = 12 * 1024 * 1024;
    const total = chunk + 3;
    fetchMock.mockImplementation(async (_url: string, init: RequestInit) => {
      const range = (init.headers as Record<string, string>).Range;
      const match = range.match(/bytes=(\d+)-(\d+)/)!;
      const length = Number(match[2]) - Number(match[1]) + 1;
      return new Response(new Uint8Array(length), { status: 206 });
    });

    const blob = await downloadFile('large', 'token', total, vi.fn());

    expect(blob.size).toBe(total);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map((call) => call[1].headers.Range)).toEqual([
      `bytes=0-${chunk - 1}`,
      `bytes=${chunk}-${total - 1}`,
    ]);
  });

  it('falls back to one stream when a range is incomplete', async () => {
    const total = 12 * 1024 * 1024 + 1;
    fetchMock.mockImplementation(async (_url: string, init: RequestInit) => {
      const range = (init.headers as Record<string, string>).Range;
      if (range) return new Response(new Uint8Array([1]), { status: 206 });
      return new Response(new Uint8Array([7, 8, 9]), {
        status: 200,
        headers: { 'Content-Length': '3' },
      });
    });

    const blob = await downloadFile('large', 'token', total, vi.fn());
    expect([...new Uint8Array(await blob.arrayBuffer())]).toEqual([7, 8, 9]);
    expect(fetchMock.mock.calls.some((call) => !call[1].headers.Range)).toBe(true);
  });

  it('completes a resumable upload and reports final progress', async () => {
    fetchMock
      .mockResolvedValueOnce(new Response('', { status: 200, headers: { Location: 'https://upload/session' } }))
      .mockResolvedValueOnce(jsonResponse({ id: 'new', name: 'clip.mp4', webViewLink: 'https://drive/new' }));
    const progress = vi.fn();
    const blob = new Blob(['video'], { type: 'video/mp4' });

    const result = await resumableUpload(blob, { name: 'clip.mp4', mimeType: 'video/mp4' }, 'token', progress);

    expect(result.id).toBe('new');
    expect(fetchMock.mock.calls[0][1].headers['X-Upload-Content-Length']).toBe('5');
    expect(fetchMock.mock.calls[1][1].headers['Content-Range']).toBe('bytes 0-4/5');
    expect(progress).toHaveBeenLastCalledWith(5, 5);
  });

  it('advances a multi-chunk resumable upload from the server Range header', async () => {
    const chunk = 8 * 1024 * 1024;
    const blob = new Blob([new Uint8Array(chunk + 1)], { type: 'video/mp4' });
    fetchMock
      .mockResolvedValueOnce(new Response('', { status: 200, headers: { Location: 'https://upload/session' } }))
      .mockResolvedValueOnce(new Response('', { status: 308, headers: { Range: `bytes=0-${chunk - 1}` } }))
      .mockResolvedValueOnce(jsonResponse({ id: 'new', name: 'large.mp4', webViewLink: 'https://drive/new' }));
    const progress = vi.fn();

    await resumableUpload(blob, { name: 'large.mp4', mimeType: 'video/mp4' }, 'token', progress);

    expect(fetchMock.mock.calls[1][1].headers['Content-Range']).toBe(`bytes 0-${chunk - 1}/${chunk + 1}`);
    expect(fetchMock.mock.calls[2][1].headers['Content-Range']).toBe(`bytes ${chunk}-${chunk}/${chunk + 1}`);
    expect(progress.mock.calls).toEqual([[chunk, chunk + 1], [chunk + 1, chunk + 1]]);
  });

  it('recovers when the final upload response is lost but Drive reports completion', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(new Response('', { status: 200, headers: { Location: 'https://upload/session' } }))
      .mockRejectedValueOnce(new TypeError('network connection lost'))
      .mockResolvedValueOnce(jsonResponse({ id: 'new', name: 'clip.mp4', webViewLink: 'https://drive/new' }));
    const progress = vi.fn();

    const upload = resumableUpload(
      new Blob(['video']),
      { name: 'clip.mp4', mimeType: 'video/mp4' },
      'token',
      progress,
    );
    await vi.runAllTimersAsync();

    await expect(upload).resolves.toMatchObject({ id: 'new', name: 'clip.mp4' });
    expect(fetchMock.mock.calls[2][1].headers['Content-Range']).toBe('bytes */5');
    expect(progress).toHaveBeenLastCalledWith(5, 5);
    vi.useRealTimers();
  });

  it('surfaces a failed resumable-upload status query', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(new Response('', { status: 200, headers: { Location: 'https://upload/session' } }))
      .mockRejectedValueOnce(new TypeError('network connection lost'))
      .mockResolvedValueOnce(new Response('session expired', { status: 404 }));

    const upload = resumableUpload(
      new Blob(['video']),
      { name: 'clip.mp4', mimeType: 'video/mp4' },
      'token',
      vi.fn(),
    );
    const rejection = expect(upload).rejects.toThrow(
      'Upload status failed (HTTP 404): session expired',
    );
    await vi.runAllTimersAsync();

    await rejection;
  });

  it('rejects upload initialization errors and missing session URIs', async () => {
    fetchMock.mockResolvedValueOnce(new Response('quota', { status: 429 }));
    await expect(resumableUpload(new Blob(['x']), { name: 'x', mimeType: 'video/mp4' }, 't', vi.fn()))
      .rejects.toThrow('Upload init failed (HTTP 429): quota');

    fetchMock.mockResolvedValueOnce(new Response('', { status: 200 }));
    await expect(resumableUpload(new Blob(['x']), { name: 'x', mimeType: 'video/mp4' }, 't', vi.fn()))
      .rejects.toThrow('no session URI');
  });

  it('creates a Drive folder with the intended parent', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'folder', name: 'Exports', webViewLink: 'https://drive/folder' }));
    const result = await createFolder('Exports', 'parent', 'token');
    expect(result.id).toBe('folder');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      name: 'Exports',
      mimeType: 'application/vnd.google-apps.folder',
      parents: ['parent'],
    });
  });

  it('accepts an existing public permission but rejects other share errors', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 400 }));
    await expect(shareAnyone('file', 'token')).resolves.toBeUndefined();
    fetchMock.mockResolvedValueOnce(new Response('', { status: 500 }));
    await expect(shareAnyone('file', 'token')).rejects.toThrow('HTTP 500');
  });

  it('uploads a metadata sidecar as multipart content', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'json', name: 'clip.json', webViewLink: 'https://drive/json' }));
    await uploadSmallFile('{"Topic":"Regression"}', {
      name: 'clip.json', mimeType: 'application/json', parents: ['folder'],
    }, 'token');

    const request = fetchMock.mock.calls[0][1];
    expect(request.headers.Authorization).toBe('Bearer token');
    expect(request.headers['Content-Type']).toContain('multipart/related');
    expect(request.body).toContain('"name":"clip.json"');
    expect(request.body).toContain('{"Topic":"Regression"}');
  });
});
