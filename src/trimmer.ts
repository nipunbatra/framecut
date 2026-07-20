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
  avi: 'video/x-msvideo',
  ogv: 'video/ogg',
  ogg: 'video/ogg',
};

export interface TrimPlan {
  args: string[];
  outExt: string;
  outputPath: string;
  mimeType: string;
}

/** Build and validate the ffmpeg command without touching the WASM runtime. */
export function buildTrimPlan(
  inputName: string,
  startSec: number,
  endSec: number,
  precise: boolean,
  inputPath = inputName,
  outputStem = 'out',
): TrimPlan {
  if (![startSec, endSec].every(Number.isFinite) || startSec < 0 || endSec <= startSec) {
    throw new Error('Trim range must have a finite end after its non-negative start');
  }
  const inExt = ext(inputName);
  const outExt = precise ? 'mp4' : inExt;
  const outputPath = `${outputStem}.${outExt}`;
  const duration = endSec - startSec;
  const args = precise
    ? [
        '-ss', toTimestamp(startSec),
        '-i', inputPath,
        '-t', toTimestamp(duration),
        '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '17',
        '-c:a', 'aac', '-b:a', '128k',
        '-movflags', '+faststart',
        outputPath,
      ]
    : [
        '-ss', toTimestamp(startSec),
        '-i', inputPath,
        '-t', toTimestamp(duration),
        '-c', 'copy',
        '-avoid_negative_ts', 'make_zero',
        ...(outExt === 'mp4' || outExt === 'm4v' || outExt === 'mov'
          ? ['-movflags', '+faststart']
          : []),
        outputPath,
      ];
  return { args, outExt, outputPath, mimeType: MIME[outExt] ?? 'video/mp4' };
}

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
      .then(() => ff)
      .catch((error) => {
        // A transient CDN/network failure must not poison every later retry.
        loadPromise = null;
        throw error;
      });
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
  const inExt = ext(opts.inputName);
  const n = ++mountCounter;
  const mountDir = `/in${n}`;
  const inName = `input.${inExt}`;
  const initialPlan = buildTrimPlan(
    opts.inputName, opts.startSec, opts.endSec, opts.precise, inName, `out${n}`,
  );
  const ff = await preloadFfmpeg();
  curProgress = opts.onProgress ?? null;
  curLog = opts.onLog ?? null;

  let mounted = false;
  let mountDirCreated = false;
  try {
    // WORKERFS reads the File lazily instead of copying it into the WASM heap,
    // so the input file does not count against the ~2 GB heap ceiling.
    const file = new File([opts.blob], inName, { type: opts.blob.type });
    await ff.createDir(mountDir);
    mountDirCreated = true;
    await ff.mount('WORKERFS' as any, { files: [file] } as any, mountDir);
    mounted = true;
  } catch {
    await ff.writeFile(inName, await fetchFile(opts.blob));
  }
  const input = mounted ? `${mountDir}/${inName}` : inName;
  const plan = input === inName
    ? initialPlan
    : buildTrimPlan(opts.inputName, opts.startSec, opts.endSec, opts.precise, input, `out${n}`);

  try {
    const code = await ff.exec(plan.args);
    if (code !== 0) throw new Error(`ffmpeg exited with code ${code}`);
    const data = (await ff.readFile(plan.outputPath)) as Uint8Array;
    const stem = opts.inputName.replace(/\.[^.]+$/, '');
    return {
      blob: new Blob([data.slice().buffer as ArrayBuffer], { type: plan.mimeType }),
      outputName: `${stem}-trimmed.${plan.outExt}`,
      mimeType: plan.mimeType,
    };
  } finally {
    // Free everything from this trim without tearing down the loaded core.
    curProgress = null;
    curLog = null;
    try { await ff.deleteFile(plan.outputPath); } catch { /* ignore */ }
    if (mounted) {
      try { await ff.unmount(mountDir); } catch { /* ignore */ }
    } else {
      try { await ff.deleteFile(inName); } catch { /* ignore */ }
    }
    if (mountDirCreated) {
      try { await ff.deleteDir(mountDir); } catch { /* ignore */ }
    }
  }
}
