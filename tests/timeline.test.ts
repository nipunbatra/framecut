// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Timeline } from '../src/timeline';

function makeTimeline(width = 200): { root: HTMLDivElement; timeline: Timeline } {
  const root = document.createElement('div');
  Object.defineProperty(root, 'getBoundingClientRect', {
    value: () => ({ left: 0, top: 0, width, height: 64, right: width, bottom: 64, x: 0, y: 0, toJSON: () => ({}) }),
  });
  document.body.appendChild(root);
  return { root, timeline: new Timeline(root) };
}

describe('trim timeline', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('initializes the full duration and renders handles and shades', () => {
    const { root, timeline } = makeTimeline();
    timeline.setDuration(100);
    expect(timeline.inSec).toBe(0);
    expect(timeline.outSec).toBe(100);
    expect((root.querySelector('.tl-handle.in') as HTMLElement).style.left).toBe('calc(0% - 14px)');
    expect((root.querySelector('.tl-handle.out') as HTMLElement).style.left).toBe('100%');
    expect((root.querySelectorAll('.tl-shade')[1] as HTMLElement).style.width).toBe('0%');
  });

  it('clamps start and end while preserving a 0.1 second selection', () => {
    const { timeline } = makeTimeline();
    timeline.setDuration(10);
    timeline.setOut(4);
    timeline.setIn(99);
    expect(timeline.inSec).toBeCloseTo(3.9);
    timeline.setOut(-5);
    expect(timeline.outSec).toBeCloseTo(4.0);
    timeline.setIn(-10);
    expect(timeline.inSec).toBe(0);
    timeline.setOut(99);
    expect(timeline.outSec).toBe(10);
  });

  it('ignores non-finite selection updates', () => {
    const { timeline } = makeTimeline();
    timeline.setDuration(10);
    timeline.setIn(2);
    timeline.setOut(8);
    timeline.setIn(Number.NaN);
    timeline.setOut(Number.POSITIVE_INFINITY);
    expect(timeline.inSec).toBe(2);
    expect(timeline.outSec).toBe(8);
  });

  it('maps clicks on a zero-width timeline safely to zero', () => {
    const { root, timeline } = makeTimeline(0);
    timeline.setDuration(10);
    const seek = vi.fn();
    timeline.onSeek = seek;
    root.dispatchEvent(new PointerEvent('pointerdown', { clientX: 5, bubbles: true }));
    expect(seek).toHaveBeenCalledWith(0);
  });

  it('emits changes only when requested', () => {
    const { timeline } = makeTimeline();
    const changed = vi.fn();
    timeline.onChange = changed;
    timeline.setDuration(10);
    timeline.setIn(2);
    timeline.setOut(8, true);
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenCalledWith(2, 8);
  });

  it('maps a timeline click to a bounded seek time', () => {
    const { root, timeline } = makeTimeline(200);
    timeline.setDuration(80);
    const seek = vi.fn();
    timeline.onSeek = seek;
    root.dispatchEvent(new PointerEvent('pointerdown', { clientX: 50, bubbles: true }));
    root.dispatchEvent(new PointerEvent('pointerdown', { clientX: 999, bubbles: true }));
    expect(seek.mock.calls[0][0]).toBe(20);
    expect(seek.mock.calls[1][0]).toBe(80);
  });

  it('drags a handle with pointer events and emits the new selection', () => {
    const { root, timeline } = makeTimeline(200);
    timeline.setDuration(100);
    const changed = vi.fn();
    timeline.onChange = changed;
    const handle = root.querySelector('.tl-handle.in') as HTMLElement;
    Object.defineProperty(handle, 'setPointerCapture', { value: vi.fn() });

    handle.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 1, clientX: 0, bubbles: true }));
    handle.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientX: 100, bubbles: true }));
    handle.dispatchEvent(new PointerEvent('pointerup', { pointerId: 1, clientX: 100, bubbles: true }));

    expect(timeline.inSec).toBe(50);
    expect(changed).toHaveBeenLastCalledWith(50, 100);
  });

  it('stops updating a drag after pointer cancellation', () => {
    const { root, timeline } = makeTimeline(200);
    timeline.setDuration(100);
    const handle = root.querySelector('.tl-handle.in') as HTMLElement;
    Object.defineProperty(handle, 'setPointerCapture', { value: vi.fn() });

    handle.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 1, clientX: 0, bubbles: true }));
    handle.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientX: 50, bubbles: true }));
    handle.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 1, bubbles: true }));
    handle.dispatchEvent(new PointerEvent('pointermove', { pointerId: 1, clientX: 150, bubbles: true }));

    expect(timeline.inSec).toBe(25);
  });

  it('positions the playhead with tabular percentage math', () => {
    const { root, timeline } = makeTimeline();
    timeline.setDuration(40);
    timeline.setPlayhead(10);
    expect((root.querySelector('.tl-playhead') as HTMLElement).style.left).toBe('25%');
  });

  it('clamps the playhead to the valid timeline range', () => {
    const { root, timeline } = makeTimeline();
    const playhead = root.querySelector('.tl-playhead') as HTMLElement;
    timeline.setDuration(40);
    timeline.setPlayhead(-1);
    expect(playhead.style.left).toBe('0%');
    timeline.setPlayhead(99);
    expect(playhead.style.left).toBe('100%');
    timeline.setPlayhead(Number.NaN);
    expect(playhead.style.left).toBe('0%');
  });

  it('normalizes an invalid duration and clears stale positions', () => {
    const { root, timeline } = makeTimeline();
    timeline.setDuration(10);
    timeline.setPlayhead(5);
    timeline.setDuration(Number.NaN);

    expect(timeline.inSec).toBe(0);
    expect(timeline.outSec).toBe(0);
    expect((root.querySelector('.tl-handle.out') as HTMLElement).style.left).toBe('');
    expect((root.querySelector('.tl-playhead') as HTMLElement).style.left).toBe('');
  });

  it('ignores playhead updates before a duration exists', () => {
    const { root, timeline } = makeTimeline();
    timeline.setPlayhead(10);
    expect((root.querySelector('.tl-playhead') as HTMLElement).style.left).toBe('');
  });
});
