import { isBlocked } from "./names";

/** Public name: the custom name unless absent or caught by the current blocklist (then the anon name). */
export function publicName(u: { display_name: string | null; anon_name: string }): string {
  if (u.display_name === null || isBlocked(u.display_name)) return u.anon_name;
  return u.display_name;
}
