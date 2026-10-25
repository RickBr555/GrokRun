import type { PhaseRecord, ScheduledPhase, TrackPoint } from "./types";
import { elevationChange, smoothAltitudes } from "./geo";

export function phaseDistance(points: TrackPoint[], phaseIndex: number): number {
  let base = 0;
  let last = 0;
  let any = false;
  for (const p of points) {
    if (p.phaseIndex < phaseIndex) base = p.distM;
    if (p.phaseIndex === phaseIndex) {
      any = true;
      last = p.distM;
    }
  }
  return any ? Math.max(0, last - base) : 0;
}

export function makeRecord(
  phase: ScheduledPhase,
  elapsedMs: number,
  points: TrackPoint[],
  phaseIndex: number,
): PhaseRecord {
  const end = Math.min(elapsedMs, phase.durationSec < 0 ? elapsedMs : phase.endMs);
  const durationSec = Math.max(0, (end - phase.startMs) / 1000);
  const distanceM = phaseDistance(points, phaseIndex);
  const avgSpeedKmh = durationSec > 0 ? distanceM / 1000 / (durationSec / 3600) : 0;
  const avgPaceSec = distanceM >= 8 ? durationSec / (distanceM / 1000) : null;
  return {
    kind: phase.kind,
    label: phase.label,
    cycle: phase.cycle,
    sprint: phase.sprint,
    durationSec,
    distanceM,
    avgPaceSec,
    avgSpeedKmh,
  };
}

export function movingPace(
  points: TrackPoint[],
  now: number,
  windowSec: number,
): { paceSec: number | null; speedKmh: number | null } {
  const cutoff = now - windowSec * 1000;
  let first: TrackPoint | null = null;
  let last: TrackPoint | null = null;
  for (const p of points) {
    if (p.t < cutoff) continue;
    if (!first) first = p;
    last = p;
  }
  if (!first || !last || first === last) return { paceSec: null, speedKmh: null };
  const dt = (last.t - first.t) / 1000;
  const d = last.distM - first.distM;
  if (dt < Math.min(5, windowSec * 0.4)) return { paceSec: null, speedKmh: null };
  if (d < 1) return { paceSec: 99999, speedKmh: 0 };
  return { paceSec: dt / (d / 1000), speedKmh: (d / dt) * 3.6 };
}

export function applySmoothedElevation(points: TrackPoint[]): {
  points: TrackPoint[];
  gain: number;
  loss: number;
} {
  const smoothed = smoothAltitudes(points.map((p) => p.alt));
  const next = points.map((p, i) => ({ ...p, alt: smoothed[i] }));
  const { gain, loss } = elevationChange(next.map((p) => p.alt));
  return { points: next, gain, loss };
}

export async function refineElevation(points: TrackPoint[]): Promise<{
  points: TrackPoint[];
  gain: number;
  loss: number;
  estimated: boolean;
  source: TrackPoint["altSource"];
}> {
  if (points.length === 0) {
    return { points, gain: 0, loss: 0, estimated: true, source: "estimated" };
  }
  const hasBaro = points.some((p) => p.altSource === "baro" && p.alt != null);
  if (hasBaro) {
    const smoothed = applySmoothedElevation(points);
    return { ...smoothed, estimated: false, source: "baro" };
  }
  try {
    const elev = await fetchOpenMeteo(points);
    if (elev && elev.length === points.length) {
      const replaced = points.map((p, i) => ({
        ...p,
        alt: elev[i],
        altSource: "dem" as const,
      }));
      const smoothed = applySmoothedElevation(replaced);
      return { ...smoothed, estimated: false, source: "dem" };
    }
  } catch {
    /* offline ou API fora: cai no GPS suavizado */
  }
  const smoothed = applySmoothedElevation(points);
  return { ...smoothed, estimated: true, source: "estimated" };
}

async function fetchOpenMeteo(points: TrackPoint[]): Promise<number[] | null> {
  const out: number[] = [];
  const step = points.length > 240 ? Math.ceil(points.length / 240) : 1;
  const sampleIdx: number[] = [];
  for (let i = 0; i < points.length; i += step) sampleIdx.push(i);
  if (sampleIdx[sampleIdx.length - 1] !== points.length - 1) {
    sampleIdx.push(points.length - 1);
  }
  const sampled: number[] = [];
  for (let i = 0; i < sampleIdx.length; i += 80) {
    const slice = sampleIdx.slice(i, i + 80);
    const lat = slice.map((idx) => points[idx].lat.toFixed(5)).join(",");
    const lon = slice.map((idx) => points[idx].lon.toFixed(5)).join(",");
    const res = await fetch(
      `https://api.open-meteo.com/v1/elevation?latitude=${lat}&longitude=${lon}`,
    );
    if (!res.ok) return null;
    const json = (await res.json()) as { elevation?: number[] };
    if (!json.elevation || json.elevation.length !== slice.length) return null;
    sampled.push(...json.elevation);
  }
  let s = 0;
  for (let i = 0; i < points.length; i++) {
    while (s < sampleIdx.length - 2 && sampleIdx[s + 1] < i) s++;
    const i0 = sampleIdx[s];
    const i1 = sampleIdx[Math.min(s + 1, sampleIdx.length - 1)];
    const z0 = sampled[s];
    const z1 = sampled[Math.min(s + 1, sampled.length - 1)];
    const t = i1 === i0 ? 0 : (i - i0) / (i1 - i0);
    out.push(z0 + (z1 - z0) * t);
  }
  return out;
}

export function downsample(points: TrackPoint[], max = 900): TrackPoint[] {
  if (points.length <= max) return points;
  const must = new Set<number>([0, points.length - 1]);
  for (let i = 1; i < points.length; i++) {
    if (points[i].phaseIndex !== points[i - 1].phaseIndex) must.add(i);
  }
  const step = Math.max(1, Math.ceil(points.length / max));
  const out: TrackPoint[] = [];
  for (let i = 0; i < points.length; i++) {
    if (must.has(i) || i % step === 0) out.push(points[i]);
  }
  return out;
}
