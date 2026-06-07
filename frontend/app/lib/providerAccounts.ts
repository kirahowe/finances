import type { Account, AvailableAccount } from './api';

// An available account annotated with whether it's already imported. `connected`
// accounts are the remembered selection - shown checked + locked in the UI.
export interface SelectableAccount extends AvailableAccount {
  connected: boolean;
}

export interface InstitutionGroup {
  institutionId: string;
  institutionName: string;
  institutionLogo?: string;
  accounts: SelectableAccount[];
}

// Mark each available account `connected` if its external-id already exists among
// the app's imported accounts (matched on `account/external-id`).
export function partitionConnected(
  available: AvailableAccount[],
  existingAccounts: Account[]
): SelectableAccount[] {
  const connectedIds = new Set(
    existingAccounts
      .map((a) => a['account/external-id'])
      .filter((id): id is string => Boolean(id))
  );
  return available.map((a) => ({ ...a, connected: connectedIds.has(a['external-id']) }));
}

// Group accounts under their institution, preserving first-seen order for both
// institutions and the accounts within each.
export function groupByInstitution(accounts: SelectableAccount[]): InstitutionGroup[] {
  const groups = new Map<string, InstitutionGroup>();
  for (const account of accounts) {
    const key = account['institution-id'];
    let group = groups.get(key);
    if (!group) {
      group = {
        institutionId: key,
        institutionName: account['institution-name'],
        institutionLogo: account['institution-logo'] ?? undefined,
        accounts: [],
      };
      groups.set(key, group);
    }
    group.accounts.push(account);
  }
  return [...groups.values()];
}
