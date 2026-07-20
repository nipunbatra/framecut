/**
 * Trim timeline: filmstrip background, draggable in/out handles, playhead.
 * Pointer events only, so mouse and touch behave identically.
 */
export class Timeline {
  private root: HTMLElement;
  private strip: HTMLCanvasElement;
  private shadeL: HTMLDivElement;
  private shadeR: HTMLDivElement;
  private handleIn: HTMLDivElement;
  private handleOut: HTMLDivElement;
  private playhead: HTMLDivElement;

  private duration = 0;
  inSec = 0;
  outSec = 0;

  onChange: (inSec: number, outSec: number) => void = () => {};
  onSeek: (t: number) => void = () => {};

  constructor(container: HTMLElement) {
    this.root = container;
    this.root.innerHTML = '';
    this.strip = document.createElement('canvas');
    this.strip.className = 'tl-strip';
    this.shadeL = this.mkDiv('tl-shade');
    this.shadeR = this.mkDiv('tl-shade');
    this.handleIn = this.mkDiv('tl-handle in');
    this.handleOut = this.mkDiv('tl-handle out');
    this.playhead = this.mkDiv('tl-playhead');
    this.root.append(this.strip, this.shadeL, this.shadeR, this.handleIn, this.handleOut, this.playhead);

    this.bindDrag(this.handleIn, (t) => this.setIn(t, true));
    this.bindDrag(this.handleOut, (t) => this.setOut(t, true));
    this.root.addEventListener('pointerdown', (e) => {
      if (e.target === this.handleIn || e.target === this.handleOut) return;
      this.onSeek(this.pxToTime(e.clientX));
    });
  }

  private mkDiv(cls: string): HTMLDivElement {
    const d = document.createElement('div');
    d.className = cls;
    return d;
  }

  setDuration(d: number): void {
    this.duration = Number.isFinite(d) && d > 0 ? d : 0;
    this.inSec = 0;
    this.outSec = this.duration;
    this.render();
  }

  setIn(t: number, emit = false): void {
    if (!Number.isFinite(t)) return;
    this.inSec = Math.max(0, Math.min(t, this.outSec - 0.1));
    this.render();
    if (emit) this.onChange(this.inSec, this.outSec);
  }

  setOut(t: number, emit = false): void {
    if (!Number.isFinite(t)) return;
    this.outSec = Math.min(this.duration, Math.max(t, this.inSec + 0.1));
    this.render();
    if (emit) this.onChange(this.inSec, this.outSec);
  }

  setPlayhead(t: number): void {
    if (!this.duration) return;
    const bounded = Math.max(0, Math.min(this.duration, Number.isFinite(t) ? t : 0));
    this.playhead.style.left = `${(bounded / this.duration) * 100}%`;
  }

  private pxToTime(clientX: number): number {
    const rect = this.root.getBoundingClientRect();
    if (!Number.isFinite(rect.width) || rect.width <= 0) return 0;
    const frac = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    return frac * this.duration;
  }

  private bindDrag(handle: HTMLElement, apply: (t: number) => void): void {
    handle.addEventListener('pointerdown', (e) => {
      e.stopPropagation();
      handle.setPointerCapture(e.pointerId);
      const move = (ev: PointerEvent) => apply(this.pxToTime(ev.clientX));
      const stop = () => {
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', stop);
        handle.removeEventListener('pointercancel', stop);
        handle.removeEventListener('lostpointercapture', stop);
      };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', stop);
      handle.addEventListener('pointercancel', stop);
      handle.addEventListener('lostpointercapture', stop);
    });
  }

  private render(): void {
    if (!this.duration) {
      for (const el of [this.handleIn, this.handleOut, this.shadeL, this.shadeR, this.playhead]) {
        el.removeAttribute('style');
      }
      return;
    }
    const inPct = (this.inSec / this.duration) * 100;
    const outPct = (this.outSec / this.duration) * 100;
    this.handleIn.style.left = `calc(${inPct}% - 14px)`;
    this.handleOut.style.left = `${outPct}%`;
    this.shadeL.style.left = '0';
    this.shadeL.style.width = `${inPct}%`;
    this.shadeR.style.left = `${outPct}%`;
    this.shadeR.style.width = `${100 - outPct}%`;
  }

  /**
   * Draw a filmstrip by seeking a hidden clone of the video. Best-effort:
   * failures (odd codecs, huge files) just leave a plain background.
   */
  async drawFilmstrip(src: string): Promise<void> {
    try {
      const v = document.createElement('video');
      v.muted = true;
      v.preload = 'auto';
      v.src = src;
      await once(v, 'loadedmetadata', 15000);
      let dur = v.duration;
      if (!isFinite(dur)) {
        // MediaRecorder webm files report Infinity until seeked far ahead
        v.currentTime = 1e9;
        await once(v, 'seeked', 15000);
        dur = v.duration;
        if (!isFinite(dur)) return;
      }
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = this.root.clientWidth * dpr;
      const h = this.root.clientHeight * dpr;
      this.strip.width = w;
      this.strip.height = h;
      const ctx = this.strip.getContext('2d')!;
      const n = Math.max(4, Math.min(16, Math.floor(this.root.clientWidth / 72)));
      const tw = w / n;
      for (let i = 0; i < n; i++) {
        v.currentTime = ((i + 0.5) / n) * dur;
        await once(v, 'seeked', 8000);
        const vw = v.videoWidth || 16;
        const vh = v.videoHeight || 9;
        const scale = Math.max(tw / vw, h / vh);
        const dw = vw * scale;
        const dh = vh * scale;
        ctx.drawImage(v, i * tw + (tw - dw) / 2, (h - dh) / 2, dw, dh);
      }
      v.removeAttribute('src');
      v.load();
    } catch {
      /* filmstrip is decorative */
    }
  }
}

function once(el: HTMLElement, event: string, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => {
      el.removeEventListener(event, h);
      reject(new Error(`timeout waiting for ${event}`));
    }, timeoutMs);
    const h = () => {
      clearTimeout(t);
      resolve();
    };
    el.addEventListener(event, h, { once: true });
  });
}
