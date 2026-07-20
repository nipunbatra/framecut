import { describe, expect, it, vi } from 'vitest';

vi.mock('@ffmpeg/ffmpeg', () => ({ FFmpeg: class {} }));
vi.mock('@ffmpeg/util', () => ({ fetchFile: vi.fn() }));

import { buildTrimPlan, toTimestamp } from '../src/trimmer';

describe('trim command construction', () => {
  it.each([
    [0, '00:00:00.000'],
    [1.234, '00:00:01.234'],
    [61.005, '00:01:01.005'],
    [3661.25, '01:01:01.250'],
  ])('formats %s seconds as %s', (seconds, expected) => {
    expect(toTimestamp(seconds)).toBe(expected);
  });

  it('builds a fast MP4 stream-copy command with faststart', () => {
    const plan = buildTrimPlan('Lecture.MP4', 2.5, 12.5, false, '/input/video.mp4', 'result');
    expect(plan).toMatchObject({ outExt: 'mp4', outputPath: 'result.mp4', mimeType: 'video/mp4' });
    expect(plan.args).toEqual([
      '-ss', '00:00:02.500', '-i', '/input/video.mp4', '-t', '00:00:10.000',
      '-c', 'copy', '-avoid_negative_ts', 'make_zero', '-movflags', '+faststart', 'result.mp4',
    ]);
  });

  it('preserves WebM without MP4-only flags', () => {
    const plan = buildTrimPlan('clip.webm', 0, 5, false);
    expect(plan.outExt).toBe('webm');
    expect(plan.mimeType).toBe('video/webm');
    expect(plan.args).not.toContain('-movflags');
    expect(plan.args.at(-1)).toBe('out.webm');
  });

  it('uses H.264/AAC and MP4 for a precise cut', () => {
    const plan = buildTrimPlan('source.mov', 1, 3.25, true);
    expect(plan).toMatchObject({ outExt: 'mp4', mimeType: 'video/mp4' });
    expect(plan.args).toContain('libx264');
    expect(plan.args).toContain('aac');
    expect(plan.args).toContain('+faststart');
    expect(plan.args.at(-1)).toBe('out.mp4');
  });

  it.each([
    ['movie.avi', 'video/x-msvideo'],
    ['movie.ogv', 'video/ogg'],
    ['movie.mkv', 'video/x-matroska'],
  ])('assigns the correct MIME type for %s', (name, mime) => {
    expect(buildTrimPlan(name, 0, 1, false).mimeType).toBe(mime);
  });

  it.each([
    [-1, 2],
    [2, 2],
    [3, 2],
    [Number.NaN, 2],
    [0, Number.POSITIVE_INFINITY],
  ])('rejects the invalid range %s..%s', (start, end) => {
    expect(() => buildTrimPlan('clip.mp4', start, end, false)).toThrow('Trim range');
  });
});
