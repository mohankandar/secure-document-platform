/**
 * Avatar initials from what `/me` sends (the username: an email, a login such as "jordan.park",
 * or a display name such as "Arun Pillai"). First and last word; one word gives one letter.
 */
export function initials(username: string): string {
  const parts = (username.split('@')[0] ?? '').split(/[\s._-]+/).filter(Boolean);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
  return (first + last).toUpperCase() || 'U';
}
