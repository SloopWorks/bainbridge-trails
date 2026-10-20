import { numVar } from "./util";

export interface Limits {
  speedMps: number;
  slackM: number;
}

export function limitsFrom(env: Env): Limits {
  return { speedMps: numVar(env.PLAUSIBLE_SPEED_MPS, 4.0), slackM: numVar(env.PLAUSIBLE_SLACK_M, 500) };
}

/** Longest duration credited for one walk. */
export const MAX_WALK_DURATION_S = 24 * 3600;

/** TL-2: newly covered metres <= speed x sum(durationS of walks not seen before) + slack. */
export function allowedGrowthM(newWalkSeconds: number, lim: Limits): number {
  return lim.speedMps * newWalkSeconds + lim.slackM;
}
