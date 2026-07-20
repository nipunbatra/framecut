// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { renderLoadFailure } from '../src/load-state';

describe('load failure state', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('ends the busy state, presents the error safely, and retries', () => {
    const container = document.createElement('div');
    container.setAttribute('aria-busy', 'true');
    const retry = vi.fn();

    renderLoadFailure(container, new Error('<script>network down</script>'), retry);

    expect(container.getAttribute('aria-busy')).toBe('false');
    expect(container.querySelector('[role="alert"]')?.textContent).toContain(
      '<script>network down</script>',
    );
    expect(container.querySelector('script')).toBeNull();
    (container.querySelector('button') as HTMLButtonElement).click();
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('accepts non-Error rejection values without injecting markup', () => {
    const container = document.createElement('div');
    renderLoadFailure(container, '<img src=x onerror=alert(1)>', vi.fn());

    expect(container.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(container.querySelector('img')).toBeNull();
  });
});
