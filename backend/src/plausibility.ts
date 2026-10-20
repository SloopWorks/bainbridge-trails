import { numVar } from "./util";

export interface Limits {
  speedMps: number;
  slackM: number;
}

export function limitsFrom(env: Env): Limits {
  return { speedMps: numVar(env.PLAUSIBLE_SPEED_MPS, 4.0), slackM: numVar(env.PLAUSIBLE_SLACK_M, 500) };
}

/** Longest duration credited for one walk (P1-3). Longer values are clamped. */
export const MAX_WALK_DURATION_S = 6 * 3600;
/** Walks per upload: the first accepted upload (sign-in backfill) may carry more (P0-2). */
export const MAX_WALKS_PER_UPLOAD = 200;
export const MAX_WALKS_BACKFILL = 2000;
/** Stored walk ids per user (P0-2). */
export const MAX_STORED_WALKS = 10_000;
/** P1-3: after the first accepted upload, new walk time must fit in the time since the last walk upload (+ this). */
export const WALK_TIME_TOLERANCE_S = 15 * 60;

/** TL-2 / P1-3: slack only applies when the upload credits at least one new walk. */
export function allowedGrowthM(newWalks: number, newWalkSeconds: number, lim: Limits): number {
  return newWalks === 0 ? 0 : lim.speedMps * newWalkSeconds + lim.slackM;
}
