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

/**
 * The account menu's second line (the username) only when it says something the first line (the shown name) does
 * not: "Dana Reyes" over "Dana Reyes" is shown once (U020). Compared without case and surrounding spaces.
 */
export function secondNameLine(shownName: string, username: string): string | null {
  const id = username.trim();
  if (!id || id.toLowerCase() === shownName.trim().toLowerCase()) return null;
  return id;
}
