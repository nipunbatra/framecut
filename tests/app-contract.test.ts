import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { CONFIG } from '../src/config';

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
const sourceFiles = ['auth.ts', 'browser.ts', 'config.ts', 'drive.ts', 'main.ts', 'timeline.ts', 'trimmer.ts']
  .map((name) => readFileSync(new URL(`../src/${name}`, import.meta.url), 'utf8'))
  .join('\n');

describe('application contract and deployment safety', () => {
  it('keeps every HTML id unique', () => {
    const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('contains the controls required by the TypeScript application', () => {
    const required = [
      'btn-signin', 'btn-signout', 'browse-list', 'video', 'timeline', 'meta-name',
      'btn-save', 'btn-download-local', 'folder-modal', 'busy', 'toast',
    ];
    for (const id of required) expect(html).toContain(`id="${id}"`);
  });

  it('provides a concise, keyboard-accessible first-use path', () => {
    expect(html).toContain('class="skip-link" href="#main"');
    expect(html).toContain('<main id="main">');
    expect(html).toContain('aria-label="FrameCut workflow"');
    expect(html).toContain('>Choose</li>');
    expect(html).toContain('>Trim</li>');
    expect(html).toContain('>Save</li>');
  });

  it('uses the renamed product, repository, and companion site', () => {
    expect(html).toContain('<title>FrameCut — trim Google Drive video</title>');
    expect(html).toContain('https://nipunbatra.github.io/framecue/app.html');
    expect(html).not.toContain('video-subtitle-overlay');
    expect(html).not.toContain('video-trim-metadata-store');
  });

  it('discloses the actual Drive capability without the retired drive.file claim', () => {
    expect(html).toContain('browse and download the video you choose');
    expect(html).toContain('uses Drive access—not Gmail');
    expect(html).toContain('revoked when you sign out');
    expect(html).not.toContain('drive.file');
    expect(CONFIG.SCOPES).toBe('https://www.googleapis.com/auth/drive');
  });

  it('uses a well-formed public OAuth client without an API key or secret', () => {
    expect(CONFIG.CLIENT_ID).toMatch(/^754571415429-.+\.apps\.googleusercontent\.com$/);
    expect(CONFIG).not.toHaveProperty('API_KEY');
    expect(sourceFiles).not.toMatch(/AIza[0-9A-Za-z_-]{20,}/);
    expect(sourceFiles).not.toContain('client_secret');
    expect(sourceFiles).not.toContain('refresh_token');
  });
});
