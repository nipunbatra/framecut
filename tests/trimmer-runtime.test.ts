// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

const runtime = vi.hoisted(() => ({
  instance: {
    on: vi.fn(),
    load: vi.fn(),
  },
  constructor: vi.fn(),
}));

vi.mock('@ffmpeg/ffmpeg', () => ({
  FFmpeg: class {
    constructor() {
      runtime.constructor();
      return runtime.instance;
    }
  },
}));
vi.mock('@ffmpeg/util', () => ({ fetchFile: vi.fn() }));

describe('ffmpeg runtime loading', () => {
  beforeEach(() => {
    vi.resetModules();
    runtime.instance.on.mockReset();
    runtime.instance.load.mockReset();
    runtime.constructor.mockReset();
  });

  it('shares an in-flight load and retries after a transient failure', async () => {
    runtime.instance.load
      .mockRejectedValueOnce(new Error('CDN unavailable'))
      .mockResolvedValueOnce(undefined);
    const { preloadFfmpeg } = await import('../src/trimmer');

    const first = preloadFfmpeg();
    const concurrent = preloadFfmpeg();
    expect(first).toBe(concurrent);
    await expect(first).rejects.toThrow('CDN unavailable');
    await expect(preloadFfmpeg()).resolves.toBe(runtime.instance);

    expect(runtime.instance.load).toHaveBeenCalledTimes(2);
    expect(runtime.constructor).toHaveBeenCalledTimes(2);
  });
});
