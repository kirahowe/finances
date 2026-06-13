import { describe, it, expect } from 'vitest';
import {
  applyDescriptionOverlay,
  setTxDescriptionOverride,
  setSplitDescriptionOverride,
  EMPTY_DESCRIPTION_OVERRIDES,
} from '../../app/lib/descriptionOverlay';
import type { Transaction } from '../../app/lib/api';

// A server snapshot row: the backend sends effective-description = the import when there
// is no override.
const tx = (id: number, description: string | null): Transaction => ({
  'db/id': id,
  'transaction/posted-date': '2026-05-01',
  'transaction/payee': `Payee ${id}`,
  'transaction/amount': -10,
  'transaction/description': description,
  'transaction/effective-description': description,
});

const split = (id: number, parts: Array<{ id: number; memo?: string }>): Transaction => ({
  'db/id': id,
  'transaction/posted-date': '2026-05-01',
  'transaction/payee': `Payee ${id}`,
  'transaction/amount': -100,
  'transaction/splits': parts.map((p, i) => ({
    'db/id': p.id,
    'split/amount': -50,
    'split/order': i,
    ...(p.memo !== undefined ? { 'split/memo': p.memo } : {}),
  })),
});

describe('applyDescriptionOverlay', () => {
  it('overlays an edit as both the user override and the effective description', () => {
    const txns = [tx(1, 'STARBUCKS #1234')];
    const out = applyDescriptionOverlay(
      txns,
      setTxDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 1, 'Coffee with Sam')
    );
    expect(out[0]['transaction/effective-description']).toBe('Coffee with Sam');
    expect(out[0]['transaction/user-description']).toBe('Coffee with Sam');
    // The imported description is preserved untouched.
    expect(out[0]['transaction/description']).toBe('STARBUCKS #1234');
  });

  it('an empty edit reverts the effective description to the import', () => {
    const txns = [tx(1, 'IMPORTED')];
    const out = applyDescriptionOverlay(
      txns,
      setTxDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 1, '')
    );
    expect(out[0]['transaction/effective-description']).toBe('IMPORTED');
    expect(out[0]['transaction/user-description']).toBeNull();
  });

  it('fills in a missing imported description', () => {
    const txns = [tx(1, null)];
    const out = applyDescriptionOverlay(
      txns,
      setTxDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 1, 'Filled in')
    );
    expect(out[0]['transaction/effective-description']).toBe('Filled in');
  });

  it("overlays a split part's description onto its memo, leaving siblings untouched", () => {
    const txns = [split(5, [{ id: 51 }, { id: 52, memo: 'kept' }])];
    const out = applyDescriptionOverlay(
      txns,
      setSplitDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 51, 'Groceries portion')
    );
    expect(out[0]['transaction/splits']![0]['split/memo']).toBe('Groceries portion');
    expect(out[0]['transaction/splits']![1]['split/memo']).toBe('kept');
  });

  it("an empty split edit clears that part's memo", () => {
    const txns = [split(5, [{ id: 51, memo: 'remove me' }])];
    const out = applyDescriptionOverlay(
      txns,
      setSplitDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 51, '')
    );
    expect(out[0]['transaction/splits']![0]['split/memo']).toBeNull();
  });

  it('overlays a transaction and a split edit independently', () => {
    const txns = [tx(1, 'A'), split(5, [{ id: 51 }])];
    const out = applyDescriptionOverlay(
      txns,
      setSplitDescriptionOverride(
        setTxDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 1, 'edited'),
        51,
        'part'
      )
    );
    expect(out[0]['transaction/effective-description']).toBe('edited');
    expect(out[1]['transaction/splits']![0]['split/memo']).toBe('part');
  });

  it('returns untouched rows by identity (referentially stable)', () => {
    const txns = [tx(1, 'A'), tx(2, 'B'), split(5, [{ id: 51 }])];
    const out = applyDescriptionOverlay(
      txns,
      setTxDescriptionOverride(EMPTY_DESCRIPTION_OVERRIDES, 1, 'edited')
    );
    expect(out[0]).not.toBe(txns[0]); // edited row is a fresh object
    expect(out[1]).toBe(txns[1]); // untouched row is the same reference
    expect(out[2]).toBe(txns[2]); // untouched split is the same reference
  });
});
