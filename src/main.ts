import './style.css';
import { initAuth, getToken, signOut, fetchUserEmail } from './auth';
import { listFolder, MY_DRIVE, type DriveItem, type Crumb } from './browser';
import { downloadFile, resumableUpload, uploadSmallFile, createFolder, shareAnyone } from './drive';
import { trimVideo, toTimestamp, preloadFfmpeg } from './trimmer';
import { Timeline } from './timeline';
import { renderLoadFailure } from './load-state';
import { buildAppProperties } from './metadata';

const $ = <T extends HTMLElement = HTMLElement>(id: string) =>
  document.getElementById(id) as T;

const video = $<HTMLVideoElement>('video');
const timeline = new Timeline($('timeline'));

// ---- state ----
interface Picked { id: string; name: string; mimeType: string; size: number }
let picked: Picked | null = null;
let sourceBlob: Blob | null = null;
let objectUrl = '';
let duration = 0;
let browsePath: Crumb[] = [MY_DRIVE]; // breadcrumb stack for the main browser
let destFolder: Crumb | null = null; // upload destination
let previewingSelection = false;
let signInPending = false;
let browseRequestId = 0;
let folderModalRequestId = 0;

// ---- tiny UI helpers ----
function showScreen(id: string): void {
  for (const s of document.querySelectorAll<HTMLElement>('.screen')) {
    s.hidden = s.id !== id;
  }
}

function toast(msg: string, ms = 6000): void {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  setTimeout(() => (t.hidden = true), ms);
}

function reportError(error: unknown): void {
  busyHide();
  toast(error instanceof Error ? error.message : String(error));
  console.error(error);
}

function runAsync(fn: () => Promise<void>): void {
  void fn().catch(reportError);
}

function busy(title: string): void {
  $('busy-title').textContent = title;
  $('busy-status').textContent = '';
  $<HTMLProgressElement>('busy-bar').removeAttribute('value');
  $('busy').hidden = false;
}
function busyProgress(frac: number | null, status: string): void {
  const bar = $<HTMLProgressElement>('busy-bar');
  if (frac === null) bar.removeAttribute('value');
  else bar.value = frac;
  $('busy-status').textContent = status;
}
function busyHide(): void {
  $('busy').hidden = true;
}

function fmt(sec: number): string {
  if (!isFinite(sec)) return '–';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = (sec % 60).toFixed(1).padStart(4, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}
function fmtBytes(n: number): string {
  if (!n) return '';
  if (n >= 1e9) return `${(n / 1e9).toFixed(2)} GB`;
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)} MB`;
  return `${Math.round(n / 1e3)} KB`;
}

function updateReadout(): void {
  $('out-in').textContent = fmt(timeline.inSec);
  $('out-out').textContent = fmt(timeline.outSec);
  $('out-len').textContent = fmt(timeline.outSec - timeline.inSec);
}

// ---- Drive browser ----
const svgFolder =
  '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2Z"/></svg>';
const svgVideo =
  '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm6 4v8l6-4-6-4Z"/></svg>';

function renderBreadcrumbs(el: HTMLElement, path: Crumb[], onGo: (i: number) => void): void {
  el.innerHTML = '';
  path.forEach((crumb, i) => {
    if (i > 0) {
      const sep = document.createElement('span');
      sep.className = 'crumb-sep';
      sep.textContent = '›';
      el.appendChild(sep);
    }
    const b = document.createElement('button');
    b.className = 'crumb';
    b.textContent = crumb.name;
    b.disabled = i === path.length - 1;
    b.addEventListener('click', () => onGo(i));
    el.appendChild(b);
  });
}

async function loadBrowser(): Promise<void> {
  const requestId = ++browseRequestId;
  const listEl = $('browse-list');
  // Sharing "My Drive" itself makes no sense; only enable inside a folder.
  $<HTMLButtonElement>('btn-share-folder').disabled = browsePath.length <= 1;
  renderBreadcrumbs($('breadcrumbs'), browsePath, (i) => {
    browsePath = browsePath.slice(0, i + 1);
    void loadBrowser();
  });
  listEl.setAttribute('aria-busy', 'true');
  listEl.innerHTML = '<div class="browse-empty">Loading…</div>';
  try {
    const token = await getToken();
    const items = await listFolder(browsePath[browsePath.length - 1].id, token);
    if (requestId !== browseRequestId) return;
    listEl.setAttribute('aria-busy', 'false');
    renderItems(listEl, items, false, (item) => {
      if (item.isFolder) {
        browsePath = [...browsePath, { id: item.id, name: item.name }];
        void loadBrowser();
      } else {
        runAsync(() => openVideo(item));
      }
    });
  } catch (error) {
    if (requestId !== browseRequestId) return;
    renderLoadFailure(listEl, error, loadBrowser);
    toast('Could not load this Drive folder.');
  }
}

function renderItems(
  listEl: HTMLElement,
  items: DriveItem[],
  foldersOnly: boolean,
  onOpen: (item: DriveItem) => void,
): void {
  const visible = items.filter((i) => i.isFolder || (!foldersOnly && i.isVideo));
  const hiddenCount = items.length - visible.length;
  listEl.innerHTML = '';
  if (!visible.length) {
    listEl.innerHTML = `<div class="browse-empty">${
      foldersOnly ? 'No subfolders here.' : 'No folders or videos here.'
    }</div>`;
  }
  for (const item of visible) {
    const row = document.createElement('button');
    row.className = 'browse-row' + (item.isFolder ? ' is-folder' : ' is-video');
    row.innerHTML =
      `<span class="row-icon">${item.isFolder ? svgFolder : svgVideo}</span>` +
      `<span class="row-name">${escapeHtml(item.name)}</span>` +
      `<span class="row-meta">${item.isFolder ? '' : fmtBytes(item.size)}</span>`;
    row.addEventListener('click', () => onOpen(item));
    listEl.appendChild(row);
  }
  if (!foldersOnly && hiddenCount > 0) {
    const note = document.createElement('div');
    note.className = 'browse-hint';
    note.textContent = `${hiddenCount} non-video file${hiddenCount > 1 ? 's' : ''} hidden`;
    listEl.appendChild(note);
  }
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
}

async function openVideo(item: DriveItem): Promise<void> {
  picked = { id: item.id, name: item.name, mimeType: item.mimeType, size: item.size };
  // Default the save destination to the folder the video came from.
  destFolder = browsePath[browsePath.length - 1];
  $('dest-label').textContent = destFolder.name;

  // Load ffmpeg.wasm in the background now so it is ready by the time the
  // download finishes and the user hits Save — the core load stops being
  // part of the trim's wait.
  void preloadFfmpeg().catch(() => {});

  if (item.size > 1.9e9) {
    toast('This file is close to the in-browser 2 GB limit; trimming may fail.', 9000);
  }

  busy('Downloading from Drive');
  const token = await getToken();
  try {
    sourceBlob = await downloadFile(item.id, token, item.size, (rec, total) =>
      busyProgress(total ? rec / total : null, `${fmtBytes(rec)} of ${fmtBytes(total)}`),
    );
  } finally {
    busyHide();
  }

  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = URL.createObjectURL(sourceBlob);
  video.src = objectUrl;
  await new Promise<void>((resolve, reject) => {
    video.onloadedmetadata = () => resolve();
    video.onerror = () => reject(new Error('This browser cannot play this video format.'));
  });
  duration = video.duration;
  if (!isFinite(duration)) {
    video.currentTime = 1e9;
    await new Promise<void>((r) => (video.onseeked = () => r()));
    duration = video.duration;
    video.currentTime = 0;
  }

  timeline.setDuration(duration);
  updateReadout();
  void timeline.drawFilmstrip(objectUrl);

  const ext = item.name.match(/\.[^.]+$/)?.[0] ?? '.mp4';
  $('file-label').textContent = `${item.name} (${fmtBytes(item.size)}, ${fmt(duration)})`;
  $<HTMLInputElement>('meta-name').value = item.name.replace(/\.[^.]+$/, '') + '-trimmed' + ext;
  showScreen('screen-edit');
}

// ---- folder chooser modal (save destination) ----
let fmPath: Crumb[] = [MY_DRIVE];

async function openFolderModal(): Promise<void> {
  fmPath = [...browsePath]; // start where the user is browsing
  $('folder-modal').hidden = false;
  await loadFolderModal();
}
async function loadFolderModal(): Promise<void> {
  const requestId = ++folderModalRequestId;
  const listEl = $('fm-list');
  renderBreadcrumbs($('fm-breadcrumbs'), fmPath, (i) => {
    fmPath = fmPath.slice(0, i + 1);
    void loadFolderModal();
  });
  listEl.setAttribute('aria-busy', 'true');
  listEl.innerHTML = '<div class="browse-empty">Loading…</div>';
  try {
    const token = await getToken();
    const items = await listFolder(fmPath[fmPath.length - 1].id, token);
    if (requestId !== folderModalRequestId) return;
    listEl.setAttribute('aria-busy', 'false');
    renderItems(listEl, items, true, (item) => {
      fmPath = [...fmPath, { id: item.id, name: item.name }];
      void loadFolderModal();
    });
  } catch (error) {
    if (requestId !== folderModalRequestId) return;
    renderLoadFailure(listEl, error, loadFolderModal);
    toast('Could not load this Drive folder.');
  }
}

// ---- new folder (inline input prepended to a list) ----
function startNewFolder(
  listEl: HTMLElement,
  parentId: string,
  reload: () => Promise<void>,
): void {
  if (listEl.querySelector('.new-row')) return; // already open
  const row = document.createElement('div');
  row.className = 'browse-row new-row';
  row.innerHTML =
    '<span class="row-icon folder-tint"></span>' +
    '<input class="new-folder-input" placeholder="New folder name" />' +
    '<button class="new-ok primary small" type="button">Create</button>' +
    '<button class="new-cancel ghost small" type="button">Cancel</button>';
  listEl.prepend(row);
  const input = row.querySelector('.new-folder-input') as HTMLInputElement;
  input.focus();
  const cancel = () => row.remove();
  const create = () => {
    const name = input.value.trim();
    if (!name) return;
    row.remove();
    void (async () => {
      busy('Creating folder');
      try {
        const token = await getToken();
        await createFolder(name, parentId, token);
      } finally {
        busyHide();
      }
      await reload();
    })().catch((e) => toast(e?.message ?? String(e)));
  };
  row.querySelector('.new-ok')!.addEventListener('click', create);
  row.querySelector('.new-cancel')!.addEventListener('click', cancel);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') create();
    else if (e.key === 'Escape') cancel();
  });
}

async function copyLink(link: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(link);
    toast('Link copied to clipboard', 3000);
  } catch {
    toast('Copy failed — select the link and copy manually', 5000);
  }
}

// ---- metadata key/value rows ----
function addMetaRow(key = '', value = ''): void {
  const row = document.createElement('div');
  row.className = 'kv-row';
  row.innerHTML = `
    <input class="kv-key" placeholder="Key (e.g. Lecture)" />
    <input class="kv-val" placeholder="Value" />
    <button class="kv-del ghost" type="button" title="Remove">&times;</button>`;
  (row.querySelector('.kv-key') as HTMLInputElement).value = key;
  (row.querySelector('.kv-val') as HTMLInputElement).value = value;
  row.querySelector('.kv-del')!.addEventListener('click', () => row.remove());
  $('meta-rows').appendChild(row);
}
function collectMeta(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const row of document.querySelectorAll('#meta-rows .kv-row')) {
    const k = (row.querySelector('.kv-key') as HTMLInputElement).value.trim();
    const v = (row.querySelector('.kv-val') as HTMLInputElement).value.trim();
    if (k && v) out[k] = v;
  }
  return out;
}

// ---- flow ----
async function handleSignIn(): Promise<void> {
  if (signInPending) return;
  signInPending = true;
  const button = $<HTMLButtonElement>('btn-signin');
  button.disabled = true;
  try {
    const token = await getToken(true);
    const email = await fetchUserEmail(token);
    if (email) {
      $('account-email').textContent = email;
      $('account-chip').hidden = false;
    }
    browsePath = [MY_DRIVE];
    showScreen('screen-browse');
    await loadBrowser();
  } finally {
    signInPending = false;
    button.disabled = false;
  }
}

async function runTrim(): Promise<{ blob: Blob; name: string; mimeType: string }> {
  if (!sourceBlob || !picked) throw new Error('No video loaded');
  if (timeline.outSec - timeline.inSec < 0.2) throw new Error('Selection is empty');
  video.pause();
  const precise = $<HTMLInputElement>('chk-precise').checked;
  busy(precise ? 'Trimming (re-encoding)' : 'Trimming (lossless copy)');
  try {
    const res = await trimVideo({
      blob: sourceBlob,
      inputName: picked.name,
      startSec: timeline.inSec,
      endSec: timeline.outSec,
      precise,
      onProgress: (r) => busyProgress(r, `${Math.round(r * 100)}%`),
    });
    let name = $<HTMLInputElement>('meta-name').value.trim() || res.outputName;
    const wantedExt = res.outputName.split('.').pop()!;
    if (!name.toLowerCase().endsWith(`.${wantedExt}`)) {
      name = name.replace(/\.[^.]+$/, '') + `.${wantedExt}`;
    }
    return { blob: res.blob, name, mimeType: res.mimeType };
  } finally {
    busyHide();
  }
}

async function handleSave(): Promise<void> {
  const out = await runTrim();
  const token = await getToken();
  const meta = collectMeta();
  const desc = $<HTMLTextAreaElement>('meta-desc').value.trim();
  const kvLines = Object.entries(meta).map(([k, v]) => `${k}: ${v}`).join('\n');

  const propertyResult = buildAppProperties({
    tool: 'framecut',
    sourceFileId: picked!.id,
    trimStart: toTimestamp(timeline.inSec),
    trimEnd: toTimestamp(timeline.outSec),
  }, meta);
  if (propertyResult.omitted || propertyResult.truncated) {
    toast(
      'Some searchable Drive metadata exceeded API limits; the full values remain in the description and sidecar.',
      9000,
    );
  }

  busy('Uploading to Drive');
  let result;
  try {
    result = await resumableUpload(
      out.blob,
      {
        name: out.name,
        mimeType: out.mimeType,
        description: [desc, kvLines].filter(Boolean).join('\n\n'),
        parents: destFolder ? [destFolder.id] : undefined,
        appProperties: propertyResult.properties,
      },
      token,
      (sent, total) => busyProgress(sent / total, `${fmtBytes(sent)} of ${fmtBytes(total)}`),
    );

    if ($<HTMLInputElement>('chk-sidecar').checked && Object.keys(meta).length > 0) {
      busyProgress(null, 'Saving metadata sidecar…');
      await uploadSmallFile(
        JSON.stringify(meta, null, 2),
        {
          name: out.name.replace(/\.[^.]+$/, '') + '.json',
          mimeType: 'application/json',
          parents: destFolder ? [destFolder.id] : undefined,
        },
        token,
      );
    }

    if ($<HTMLInputElement>('chk-share').checked) {
      busyProgress(null, 'Making it shareable…');
      await shareAnyone(result.id, token);
    }
  } finally {
    busyHide();
  }

  const shared = $<HTMLInputElement>('chk-share').checked;
  $('done-summary').textContent =
    `${result.name} (${fmtBytes(out.blob.size)}) saved to ${destFolder?.name ?? 'My Drive'}.` +
    (shared ? ' Anyone with the link can view it.' : '');
  const shareRow = $('share-row');
  shareRow.hidden = !shared;
  if (shared) $<HTMLInputElement>('share-link').value = result.webViewLink;
  $<HTMLAnchorElement>('done-link').href = result.webViewLink;
  showScreen('screen-done');
}

async function handleDownloadLocal(): Promise<void> {
  const out = await runTrim();
  const url = URL.createObjectURL(out.blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = out.name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function backToBrowse(): void {
  video.pause();
  video.removeAttribute('src');
  video.load();
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = '';
  sourceBlob = null;
  picked = null;
  $<HTMLTextAreaElement>('meta-desc').value = '';
  showScreen('screen-browse');
  void loadBrowser();
}

// ---- wiring ----
function guard(fn: () => Promise<void>): () => void {
  return () => runAsync(fn);
}

timeline.onChange = () => {
  updateReadout();
  previewingSelection = false;
};
timeline.onSeek = (t) => {
  video.currentTime = t;
  previewingSelection = false;
};

video.addEventListener('timeupdate', () => {
  timeline.setPlayhead(video.currentTime);
  if (previewingSelection && video.currentTime >= timeline.outSec) {
    video.pause();
    previewingSelection = false;
  }
});

$('btn-signin').addEventListener('click', guard(handleSignIn));
$('btn-signout').addEventListener('click', () => {
  signOut();
  $('account-chip').hidden = true;
  showScreen('screen-signin');
});
$('btn-set-in').addEventListener('click', () => timeline.setIn(video.currentTime, true));
$('btn-set-out').addEventListener('click', () => timeline.setOut(video.currentTime, true));
$('btn-preview-sel').addEventListener('click', () => {
  video.currentTime = timeline.inSec;
  previewingSelection = true;
  void video.play();
});
$('btn-add-row').addEventListener('click', () => addMetaRow());
$('btn-new-folder').addEventListener('click', () =>
  startNewFolder($('browse-list'), browsePath[browsePath.length - 1].id, loadBrowser),
);
$('btn-share-folder').addEventListener('click', guard(async () => {
  const folder = browsePath[browsePath.length - 1];
  const token = await getToken();
  await shareAnyone(folder.id, token);
  await copyLink(`https://drive.google.com/drive/folders/${folder.id}`);
}));
$('fm-new-folder').addEventListener('click', () =>
  startNewFolder($('fm-list'), fmPath[fmPath.length - 1].id, loadFolderModal),
);
$('btn-copy-link').addEventListener('click', () =>
  void copyLink($<HTMLInputElement>('share-link').value),
);
$('btn-pick-folder').addEventListener('click', guard(openFolderModal));
$('fm-cancel').addEventListener('click', () => ($('folder-modal').hidden = true));
$('fm-choose').addEventListener('click', () => {
  destFolder = fmPath[fmPath.length - 1];
  $('dest-label').textContent = destFolder.name;
  $('folder-modal').hidden = true;
});
$('btn-save').addEventListener('click', guard(handleSave));
$('btn-download-local').addEventListener('click', guard(handleDownloadLocal));
$('btn-back').addEventListener('click', backToBrowse);
$('btn-again').addEventListener('click', backToBrowse);

$('chk-precise').addEventListener('change', () => {
  if (!picked) return;
  const srcExt = picked.name.match(/\.([^.]+)$/)?.[1] ?? 'mp4';
  const nameInput = $<HTMLInputElement>('meta-name');
  const newExt = $<HTMLInputElement>('chk-precise').checked ? 'mp4' : srcExt;
  nameInput.value = nameInput.value.replace(/\.[^.]+$/, '') + `.${newExt}`;
});

document.addEventListener('keydown', (e) => {
  if ($('screen-edit').hidden) return;
  const tag = (e.target as HTMLElement).tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA') return;
  switch (e.key) {
    case ' ':
      e.preventDefault();
      video.paused ? void video.play() : video.pause();
      break;
    case 'i': case 'I': timeline.setIn(video.currentTime, true); break;
    case 'o': case 'O': timeline.setOut(video.currentTime, true); break;
    case 'ArrowLeft':
      video.currentTime = Math.max(0, video.currentTime - (e.shiftKey ? 5 : 1));
      break;
    case 'ArrowRight':
      video.currentTime = Math.min(duration, video.currentTime + (e.shiftKey ? 5 : 1));
      break;
    case '[': video.currentTime = timeline.inSec; break;
    case ']': video.currentTime = timeline.outSec; break;
  }
});

// ---- boot ----
addMetaRow('Lecture');
addMetaRow('Topic');
addMetaRow('Date', new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }));

initAuth().catch((e) => toast(`Could not load Google sign-in: ${e.message}`));
showScreen('screen-signin');
