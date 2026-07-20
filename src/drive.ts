const API = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';

export interface UploadResult {
  id: string;
  name: string;
  webViewLink: string;
}

const DL_CHUNK = 12 * 1024 * 1024; // per ranged request
const DL_CONCURRENCY = 5; // parallel connections

export async function downloadFile(
  fileId: string,
  token: string,
  expectedSize: number,
  onProgress: (received: number, total: number) => void,
): Promise<Blob> {
  const url = `${API}/files/${fileId}?alt=media&supportsAllDrives=true`;
  // Parallel ranged download: multiple connections get past the per-connection
  // throttling that makes single-stream Drive downloads slow. Needs a known
  // size; falls back to a single stream if size is unknown or ranges fail.
  if (expectedSize > DL_CHUNK) {
    try {
      return await downloadParallel(url, token, expectedSize, onProgress);
    } catch {
      /* fall through to single-stream */
    }
  }
  return downloadStream(url, token, expectedSize, onProgress);
}

async function downloadParallel(
  url: string,
  token: string,
  total: number,
  onProgress: (received: number, total: number) => void,
): Promise<Blob> {
  const numChunks = Math.ceil(total / DL_CHUNK);
  const parts: (Blob | null)[] = new Array(numChunks).fill(null);
  let received = 0;
  let next = 0;

  async function worker(): Promise<void> {
    for (let i = next++; i < numChunks; i = next++) {
      const start = i * DL_CHUNK;
      const end = Math.min(start + DL_CHUNK, total) - 1;
      const r = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Range: `bytes=${start}-${end}` },
      });
      if (r.status !== 206) throw new Error('ranges-unsupported'); // triggers fallback
      const reader = r.body!.getReader();
      const acc: Uint8Array[] = [];
      let chunkReceived = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        acc.push(value);
        chunkReceived += value.length;
        received += value.length;
        onProgress(received, total);
      }
      if (chunkReceived !== end - start + 1) throw new Error('incomplete-range');
      parts[i] = new Blob(acc as BlobPart[]);
    }
  }

  await Promise.all(
    Array.from({ length: Math.min(DL_CONCURRENCY, numChunks) }, worker),
  );
  const blob = new Blob(parts as BlobPart[]);
  if (blob.size !== total) throw new Error('incomplete-download');
  return blob;
}

async function downloadStream(
  url: string,
  token: string,
  expectedSize: number,
  onProgress: (received: number, total: number) => void,
): Promise<Blob> {
  const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!r.ok) throw new Error(`Download failed (HTTP ${r.status})`);
  const contentLength = r.headers.get('Content-Length');
  const total = Number(contentLength ?? expectedSize);
  const reader = r.body!.getReader();
  const chunks: Uint8Array[] = [];
  let received = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    received += value.length;
    onProgress(received, total);
  }
  if (contentLength !== null && received !== total) {
    throw new Error(`Download incomplete (received ${received} of ${total} bytes)`);
  }
  return new Blob(chunks as BlobPart[]);
}

export interface UploadMeta {
  name: string;
  mimeType: string;
  description?: string;
  parents?: string[];
  appProperties?: Record<string, string>;
}

/**
 * Resumable upload: required for large files, survives transient network
 * errors (each 8 MB chunk is retried independently).
 */
export async function resumableUpload(
  blob: Blob,
  meta: UploadMeta,
  token: string,
  onProgress: (sent: number, total: number) => void,
): Promise<UploadResult> {
  const start = await fetch(
    `${UPLOAD}/files?uploadType=resumable&supportsAllDrives=true&fields=id,name,webViewLink`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json; charset=UTF-8',
        'X-Upload-Content-Type': meta.mimeType,
        'X-Upload-Content-Length': String(blob.size),
      },
      body: JSON.stringify(meta),
    },
  );
  if (!start.ok) {
    throw new Error(`Upload init failed (HTTP ${start.status}): ${await start.text()}`);
  }
  const sessionUri = start.headers.get('Location');
  if (!sessionUri) throw new Error('Upload init returned no session URI');

  const CHUNK = 8 * 1024 * 1024; // multiple of 256 KiB, required by the API
  let offset = 0;
  for (let attempt = 0; offset < blob.size; ) {
    const end = Math.min(offset + CHUNK, blob.size);
    const piece = blob.slice(offset, end);
    let r: Response;
    try {
      r = await fetch(sessionUri, {
        method: 'PUT',
        headers: {
          'Content-Range': `bytes ${offset}-${end - 1}/${blob.size}`,
        },
        body: piece,
      });
    } catch (e) {
      if (++attempt > 5) throw e;
      await new Promise((res) => setTimeout(res, 1000 * attempt));
      const status = await queryUploadStatus(sessionUri, blob.size);
      if (status.result) {
        onProgress(blob.size, blob.size);
        return status.result;
      }
      offset = status.offset;
      continue;
    }
    if (r.status === 308) {
      const range = r.headers.get('Range'); // "bytes=0-8388607"
      offset = range ? Number(range.split('-')[1]) + 1 : end;
      attempt = 0;
      onProgress(offset, blob.size);
    } else if (r.ok) {
      onProgress(blob.size, blob.size);
      return (await r.json()) as UploadResult;
    } else if (r.status >= 500 && attempt < 5) {
      attempt++;
      await new Promise((res) => setTimeout(res, 1000 * attempt));
      const status = await queryUploadStatus(sessionUri, blob.size);
      if (status.result) {
        onProgress(blob.size, blob.size);
        return status.result;
      }
      offset = status.offset;
    } else {
      throw new Error(`Upload failed (HTTP ${r.status}): ${await r.text()}`);
    }
  }
  throw new Error('Upload ended unexpectedly');
}

async function queryUploadStatus(
  sessionUri: string,
  total: number,
): Promise<{ offset: number; result?: UploadResult }> {
  const r = await fetch(sessionUri, {
    method: 'PUT',
    headers: { 'Content-Range': `bytes */${total}` },
  });
  if (r.status === 308) {
    const range = r.headers.get('Range');
    return { offset: range ? Number(range.split('-')[1]) + 1 : 0 };
  }
  if (r.ok) return { offset: total, result: (await r.json()) as UploadResult };
  throw new Error(`Upload status failed (HTTP ${r.status}): ${await r.text()}`);
}

/** Create a subfolder and return its id + shareable webViewLink. */
export async function createFolder(
  name: string,
  parentId: string,
  token: string,
): Promise<{ id: string; name: string; webViewLink: string }> {
  const r = await fetch(
    `${API}/files?fields=id,name,webViewLink&supportsAllDrives=true`,
    {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        mimeType: 'application/vnd.google-apps.folder',
        parents: [parentId],
      }),
    },
  );
  if (!r.ok) throw new Error(`Could not create folder (HTTP ${r.status})`);
  return r.json();
}

/** Grant "anyone with the link can view" on a file or folder. */
export async function shareAnyone(fileId: string, token: string): Promise<void> {
  const r = await fetch(
    `${API}/files/${fileId}/permissions?supportsAllDrives=true`,
    {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'reader', type: 'anyone' }),
    },
  );
  // 200 = created, 400 with existing permission is fine to ignore.
  if (!r.ok && r.status !== 400) {
    throw new Error(`Could not share (HTTP ${r.status})`);
  }
}

/** Small helper for the JSON metadata sidecar (multipart, single request). */
export async function uploadSmallFile(
  content: string,
  meta: UploadMeta,
  token: string,
): Promise<UploadResult> {
  const boundary = 'meta_sidecar_boundary';
  const body =
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n` +
    JSON.stringify(meta) +
    `\r\n--${boundary}\r\nContent-Type: ${meta.mimeType}\r\n\r\n` +
    content +
    `\r\n--${boundary}--`;
  const r = await fetch(
    `${UPLOAD}/files?uploadType=multipart&supportsAllDrives=true&fields=id,name,webViewLink`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': `multipart/related; boundary=${boundary}`,
      },
      body,
    },
  );
  if (!r.ok) throw new Error(`Sidecar upload failed (HTTP ${r.status})`);
  return (await r.json()) as UploadResult;
}
