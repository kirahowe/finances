import { describe, it, expect } from 'vitest';
import type { Account, AvailableAccount } from './api';
import { groupByInstitution, partitionConnected } from './providerAccounts';

const available = (
  overrides: Partial<AvailableAccount> & Pick<AvailableAccount, 'external-id'>
): AvailableAccount => ({
  name: 'Account',
  'institution-id': 'inst-a',
  'institution-name': 'Bank A',
  ...overrides,
});

const existing = (externalId: string): Account =>
  ({
    'db/id': 1,
    'account/external-name': 'x',
    'account/currency': 'CAD',
    'account/external-id': externalId,
  } as Account);

describe('partitionConnected', () => {
  it('marks accounts already imported as connected', () => {
    const result = partitionConnected(
      [available({ 'external-id': 'lunchflow-1' }), available({ 'external-id': 'lunchflow-2' })],
      [existing('lunchflow-1')]
    );
    expect(result.map((a) => [a['external-id'], a.connected])).toEqual([
      ['lunchflow-1', true],
      ['lunchflow-2', false],
    ]);
  });

  it('treats accounts with no external-id match as not connected', () => {
    const result = partitionConnected([available({ 'external-id': 'lunchflow-9' })], []);
    expect(result[0].connected).toBe(false);
  });
});

describe('groupByInstitution', () => {
  it('groups accounts under their institution, preserving first-seen order', () => {
    const accounts = partitionConnected(
      [
        available({ 'external-id': 'a1', 'institution-id': 'i1', 'institution-name': 'Bank One', 'institution-logo': 'logo-one.png' }),
        available({ 'external-id': 'b1', 'institution-id': 'i2', 'institution-name': 'Bank Two' }),
        available({ 'external-id': 'a2', 'institution-id': 'i1', 'institution-name': 'Bank One', 'institution-logo': 'logo-one.png' }),
      ],
      []
    );
    const groups = groupByInstitution(accounts);
    expect(groups.map((g) => g.institutionName)).toEqual(['Bank One', 'Bank Two']);
    expect(groups[0].accounts.map((a) => a['external-id'])).toEqual(['a1', 'a2']);
    expect(groups[1].accounts.map((a) => a['external-id'])).toEqual(['b1']);
    expect(groups[0].institutionLogo).toBe('logo-one.png');
    expect(groups[1].institutionLogo).toBeUndefined();
  });
});
