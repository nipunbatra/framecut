import { describe, expect, it } from 'vitest';

import { buildAppProperties } from '../src/metadata';

describe('Drive app metadata limits', () => {
  const fixed = {
    tool: 'framecut', sourceFileId: 'source', trimStart: '00:00:00.000', trimEnd: '00:00:01.000',
  };

  it('preserves fixed provenance fields on user-key collisions', () => {
    const result = buildAppProperties(fixed, { tool: 'spoofed', Topic: 'Regression' });
    expect(result.properties.tool).toBe('framecut');
    expect(result.properties.Topic).toBe('Regression');
    expect(result.omitted).toBe(1);
  });

  it('caps private properties at thirty including fixed fields', () => {
    const user = Object.fromEntries(Array.from({ length: 40 }, (_, i) => [`field${i}`, `value${i}`]));
    const result = buildAppProperties(fixed, user);
    expect(Object.keys(result.properties)).toHaveLength(30);
    expect(result.omitted).toBe(14);
  });

  it('enforces the 124-byte combined UTF-8 key/value limit', () => {
    const result = buildAppProperties(fixed, { Topic: '🙂'.repeat(100) });
    const value = result.properties.Topic;
    expect(new TextEncoder().encode(`Topic${value}`).length).toBeLessThanOrEqual(124);
    expect(value.endsWith('�')).toBe(false);
    expect(result.truncated).toBe(1);
  });

  it('omits a key that leaves no room for a value', () => {
    const result = buildAppProperties(fixed, { ['k'.repeat(124)]: 'value' });
    expect(Object.keys(result.properties)).toEqual(Object.keys(fixed));
    expect(result.omitted).toBe(1);
  });
});
