// The flat-list half of the combobox island's two modes (combobox.ts): a plain
// {id, label} option list with no hierarchy and no Uncategorized sentinel — used
// for pickers like the add-transaction modal's account field. Pure so it can be
// unit-tested without the Zag machinery.

/** One selectable option in the combobox's flat-list mode. */
export interface FlatOption {
  id: number;
  label: string;
}

/**
 * Filter a flat option list by case-insensitive substring on the label. A blank
 * (or whitespace-only) query returns every option, mirroring how the category
 * mode treats an empty filter.
 */
export function filterFlatOptions(options: FlatOption[], filter: string): FlatOption[] {
  const query = filter.trim().toLowerCase();
  if (!query) return options;
  return options.filter((o) => o.label.toLowerCase().includes(query));
}
