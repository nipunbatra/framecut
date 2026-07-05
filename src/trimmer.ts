import { FFmpeg } from '@ffmpeg/ffmpeg';
import { fetchFile } from '@ffmpeg/util';

export interface TrimOptions {
  blob: Blob;
  inputName: string; // original filename, used to pick container
  startSec: number;
  endSec: number;
  precise: boolean; // false: stream copy (keyframe snap); true: re-encode
  onProgress?: (ratio: number) => void; // 0..1, only meaningful for precise
  onLog?: (line: string) => void;
}

export interface TrimResult {
  blob: Blob;
  outputName: string;
  mimeType: string;
}

/** HH:MM:SS.mmm — same format the existing video-toolkit scripts use. */
export function toTimestamp(sec: number): string {
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${s.toFixed(3).padStart(6, '0')}`;
}

function ext(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : 'mp4';
}

const MIME: Record<string, string> = {
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  mov: 'video/quicktime',
  webm: 'video/webm',
  mkv: 'video/x-matroska',
};

// The 32 MB ffmpeg.wasm core is loaded once and reused for every trim.
// Reloading it per trim was the main source of trim latency.
let loadPromise: Promise<FFmpeg> | null = null;
let mountCounter = 0;
// Active callbacks for the single set of event listeners registered on load.
let curProgress: ((r: number) => void) | null = null;
let curLog: ((s: string) => void) | null = null;

/**
 * Load (or return the already-loaded) ffmpeg core. Safe to call early — e.g.
 * as soon as a video download starts — so the wasm load overlaps with the
 * download and the first trim is instant.
 */
export function preloadFfmpeg(): Promise<FFmpeg> {
  if (!loadPromise) {
    const ff = new FFmpeg();
    ff.on('progress', ({ progress }) => curProgress?.(Math.max(0, Math.min(1, progress))));
    ff.on('log', ({ message }) => curLog?.(message));
    const base = new URL('ffmpeg/', document.baseURI).href;
    loadPromise = ff
      .load({ coreURL: `${base}ffmpeg-core.js`, wasmURL: `${base}ffmpeg-core.wasm` })
      .then(() => ff);
  }
  return loadPromise;
}

/**
 * Trim in the browser with ffmpeg.wasm (single-threaded core — no
 * SharedArrayBuffer / COOP-COEP needed, so it runs on GitHub Pages and does
 * not break the Google auth popups that cross-origin isolation would).
 *
 * Fast path: stream copy with -avoid_negative_ts (a remux, near-instant).
 * Precise path: libx264 ultrafast crf 17 + aac 128k.
 */
export async function trimVideo(opts: TrimOptions): Promise<TrimResult> {
  const ff = await preloadFfmpeg();
  curProgress = opts.onProgress ?? null;
  curLog = opts.onLog ?? null;

  const inExt = ext(opts.inputName);
  const outExt = opts.precise ? 'mp4' : inExt; // copy keeps container; re-encode => mp4
  const n = ++mountCounter;
  const mountDir = `/in${n}`;
  const inName = `input.${inExt}`;
  const outPath = `out${n}.${outExt}`;
  const duration = opts.endSec - opts.startSec;

  let mounted = false;
  try {
    // WORKERFS reads the File lazily instead of copying it into the WASM heap,
    // so the input file does not count against the ~2 GB heap ceiling.
    const file = new File([opts.blob], inName, { type: opts.blob.type });
    await ff.createDir(mountDir);
    await ff.mount('WORKERFS' as any, { files: [file] } as any, mountDir);
    mounted = true;
  } catch {
    await ff.writeFile(inName, await fetchFile(opts.blob));
  }
  const input = mounted ? `${mountDir}/${inName}` : inName;

  const args = opts.precise
    ? [
        '-ss', toTimestamp(opts.startSec),
        '-i', input,
        '-t', toTimestamp(duration),
        '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '17',
        '-c:a', 'aac', '-b:a', '128k',
        '-movflags', '+faststart',
        outPath,
      ]
    : [
        '-ss', toTimestamp(opts.startSec),
        '-i', input,
        '-t', toTimestamp(duration),
        '-c', 'copy',
        '-avoid_negative_ts', 'make_zero',
        ...(outExt === 'mp4' || outExt === 'm4v' || outExt === 'mov'
          ? ['-movflags', '+faststart']
          : []),
        outPath,
      ];

  try {
    const code = await ff.exec(args);
    if (code !== 0) throw new Error(`ffmpeg exited with code ${code}`);
    const data = (await ff.readFile(outPath)) as Uint8Array;
    const mime = MIME[outExt] ?? 'video/mp4';
    const stem = opts.inputName.replace(/\.[^.]+$/, '');
    return {
      blob: new Blob([data.slice().buffer as ArrayBuffer], { type: mime }),
      outputName: `${stem}-trimmed.${outExt}`,
      mimeType: mime,
    };
  } finally {
    // Free everything from this trim without tearing down the loaded core.
    curProgress = null;
    curLog = null;
    try { await ff.deleteFile(outPath); } catch { /* ignore */ }
    if (mounted) {
      try { await ff.unmount(mountDir); } catch { /* ignore */ }
    } else {
      try { await ff.deleteFile(inName); } catch { /* ignore */ }
    }
  }
}
