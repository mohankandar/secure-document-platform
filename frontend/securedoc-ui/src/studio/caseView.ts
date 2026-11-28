import { useCallback } from 'react';
import useSWR from 'swr';
import { apiClient } from '../api/client';
import type { PackageSummary } from '../types';
import { checked, isRecord } from './failure';
import { priorityRank } from './words';

/**
 * Task 0108: the Cases page's View menu (sort, order, columns, density), saved on the server with the person
 * (GET/PUT /me/case-view). `columns` null means the default columns.
 */
export type CaseViewDensity = 'compact' | 'comfortable';
export type CaseViewOrder = 'asc' | 'desc';

export interface CaseViewSettings {
  sort: string;
  order: CaseViewOrder;
  columns: string[] | null;
  density: CaseViewDensity;
}

export interface CaseView {
  settings: CaseViewSettings;
  options: { sorts: string[]; columns: string[] };
}

export const DEFAULT_CASE_VIEW: CaseViewSettings = { sort: 'received', order: 'asc', columns: null, density: 'comfortable' };

export const FIELD_SORT = 'field:';
export const DEFAULT_SORTS = ['received', 'waitingLongest', 'priority'];
export const BUILT_IN_COLUMNS = ['received', 'uploaded', 'source'] as const;

const isCaseView = (v: unknown): v is CaseView => isRecord(v) && isRecord(v.settings) && isRecord(v.options)
  && typeof v.settings.sort === 'string' && Array.isArray(v.options.sorts) && Array.isArray(v.options.columns);

/** The person's view; until it has loaded (or if it cannot load) the default view, so the page never waits on it. */
export function useCaseView(enabled: boolean) {
  const { data, error, mutate } = useSWR(enabled ? 'case-view' : null,
    () => checked('GET /me/case-view', apiClient.getCaseView(), isCaseView));
  // Shown at once; if the server refuses or cannot be reached, the view goes back to what it was and the caller says so.
  const save = useCallback((next: CaseViewSettings) => mutate(() => apiClient.saveCaseView(next), {
    optimisticData: (current?: CaseView) => ({ settings: next, options: current?.options ?? { sorts: DEFAULT_SORTS, columns: [] } }),
    rollbackOnError: true, populateCache: true, revalidate: false,
  }), [mutate]);
  return { view: data, settings: data?.settings ?? DEFAULT_CASE_VIEW, error, save };
}

/** Sort by: the menu's words for each option. A field sort is named by the field's label. */
export function sortLabel(sort: string, fieldLabel: (key: string) => string): string {
  if (sort === 'received') return 'Received';
  if (sort === 'waitingLongest') return 'Waiting longest';
  if (sort === 'priority') return 'Priority';
  if (sort.startsWith(FIELD_SORT)) return fieldLabel(sort.slice(FIELD_SORT.length));
  return sort;
}

/** The order switch's two words for a sort (design: Oldest/Newest, Urgent/Low, Low→high/High→low). */
export function orderWords(sort: string): { asc: string; desc: string } {
  if (sort === 'priority') return { asc: 'Urgent first', desc: 'Low first' };
  if (sort.startsWith(FIELD_SORT)) return { asc: 'Low → high', desc: 'High → low' };
  return { asc: 'Oldest first', desc: 'Newest first' };
}

const time = (iso: string) => +new Date(iso) || 0;
const text = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/**
 * The comparator for the chosen sort and order. A case without the sorted field's value goes last in both orders. Ties
 * go to the case received first, then the case id, so the order is stable.
 */
export function caseComparator(settings: Pick<CaseViewSettings, 'sort' | 'order'>): (a: PackageSummary, b: PackageSummary) => number {
  const sign = settings.order === 'desc' ? -1 : 1;
  const key = settings.sort.startsWith(FIELD_SORT) ? settings.sort.slice(FIELD_SORT.length) : null;
  const value = (p: PackageSummary) => (key ? p.businessIdentifiers.find((i) => i.key === key)?.value ?? '' : '');
  return (a, b) => {
    let first: number;
    if (key) {
      const [va, vb] = [value(a), value(b)];
      if (!va !== !vb) return va ? -1 : 1;
      first = text.compare(va, vb);
    } else if (settings.sort === 'waitingLongest') first = time(a.updatedAt) - time(b.updatedAt);
    else if (settings.sort === 'priority') first = priorityRank(a.priority) - priorityRank(b.priority);
    else first = time(a.createdAt) - time(b.createdAt);
    if (first !== 0) return first * sign;
    return time(a.createdAt) - time(b.createdAt) || a.packageId.localeCompare(b.packageId);
  };
}

/**
 * The columns shown while the person has not chosen (the server's null): every column the entity offers except
 * Uploaded, which the case list does not carry yet. So nothing the Cases page showed before task 0108 goes away.
 */
export function shownColumns(settings: CaseViewSettings, offered: string[] | undefined): string[] {
  return settings.columns ?? (offered ?? ['received', 'source']).filter((c) => c !== 'uploaded');
}
