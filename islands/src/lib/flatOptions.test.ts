import { describe, expect, it } from 'vitest';
import { filterFlatOptions, type FlatOption } from './flatOptions';

const options: FlatOption[] = [
  { id: 1, label: 'Chequing' },
  { id: 2, label: 'Savings' },
  { id: 3, label: 'Visa' },
  { id: 4, label: 'US Savings' },
];

describe('filterFlatOptions', () => {
  it('returns every option for a blank filter', () => {
    expect(filterFlatOptions(options, '')).toEqual(options);
  });

  it('treats a whitespace-only filter as blank', () => {
    expect(filterFlatOptions(options, '   ')).toEqual(options);
  });

  it('filters by case-insensitive substring on the label', () => {
    expect(filterFlatOptions(options, 'VIS')).toEqual([{ id: 3, label: 'Visa' }]);
    expect(filterFlatOptions(options, 'sav')).toEqual([
      { id: 2, label: 'Savings' },
      { id: 4, label: 'US Savings' },
    ]);
  });

  it('matches anywhere in the label, not just the start', () => {
    expect(filterFlatOptions(options, 'quing')).toEqual([{ id: 1, label: 'Chequing' }]);
  });

  it('trims surrounding whitespace from the query', () => {
    expect(filterFlatOptions(options, '  visa ')).toEqual([{ id: 3, label: 'Visa' }]);
  });

  it('returns an empty list when nothing matches', () => {
    expect(filterFlatOptions(options, 'zzz')).toEqual([]);
  });

  it('preserves the original order of matching options', () => {
    expect(filterFlatOptions(options, 's').map((o) => o.id)).toEqual([2, 3, 4]);
  });
});
