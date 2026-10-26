import { totalSeconds, buildIntervalPhases } from "./engine";
import { TrackFilter } from "./geo";
import { applySmoothedElevation, downsample } from "./metrics";
import {
  advance,
  createSession,
  elapsedMs,
  finalizeRecords,
  ingestFix,
  notePaceSample,
  simStep,
  type Session,
} from "./session";
import { DEFAULT_WHISTLES, type Activity, type IntervalConfig, type MascotId, type Preset, type RunConfig, type StoreData, type WorkoutMode } from "./types";

export const DEFAULT_INTERVAL: IntervalConfig = {
  warmupSec: 5 * 60,
  sprintSec: 60,
  restSec: 60,
  sprintsPerCycle: 4,
  cycles: 1,
  cycleRestSec: 0,
  cooldownSec: 3 * 60,
};

export const DEFAULT_RUN: RunConfig = {
  fastSec: 6 * 60,
  slowSec: 6 * 60 + 30,
  windowSec: 20,
};

export function defaultPresets(): Preset[] {
  return [
    {
      id: "preset-classico",
      name: "Clássico 4×1 min",
      mode: "tiros",
      interval: { ...DEFAULT_INTERVAL },
    },
    {
      id: "preset-3x2",
      name: "3 tiros de 1 min, 2 ciclos",
      mode: "tiros",
      interval: {
        warmupSec: 5 * 60,
        sprintSec: 60,
        restSec: 60,
        sprintsPerCycle: 3,
        cycles: 2,
        cycleRestSec: 2 * 60,
        cooldownSec: 3 * 60,
      },
    },
    {
      id: "preset-curto",
      name: "Tiros curtos 8×30 s",
      mode: "tiros",
      interval: {
        warmupSec: 4 * 60,
        sprintSec: 30,
        restSec: 30,
        sprintsPerCycle: 8,
        cycles: 1,
        cycleRestSec: 0,
        cooldownSec: 3 * 60,
      },
    },
    {
      id: "preset-teste",
      name: "Teste rápido",
      mode: "tiros",
      interval: {
        warmupSec: 5,
        sprintSec: 8,
        restSec: 5,
        sprintsPerCycle: 2,
        cycles: 1,
        cycleRestSec: 0,
        cooldownSec: 5,
      },
    },
    {
      id: "preset-leve",
      name: "Leve 6:00–6:30",
      mode: "corrida",
      run: { fastSec: 6 * 60, slowSec: 6 * 60 + 30, windowSec: 20 },
    },
    {
      id: "preset-limiar",
      name: "Limiar 5:00–5:20",
      mode: "corrida",
      run: { fastSec: 5 * 60, slowSec: 5 * 60 + 20, windowSec: 20 },
    },
  ];
}

function atHour(daysAgo: number, hour: number): number {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  d.setHours(hour, 10, 0, 0);
  return d.getTime();
}

function sessionToActivity(s: Session, now: number, demo: boolean): Activity {
  finalizeRecords(s, now);
  const smoothed = applySmoothedElevation(s.points);
  const durationSec =
    s.mode === "tiros"
      ? totalSeconds(buildIntervalPhases(s.interval!))
      : elapsedMs(s, now) / 1000;
  const ratio = s.paceSampleMs > 0 ? s.inZoneMs / s.paceSampleMs : null;
  return {
    id: s.id,
    startedAt: s.startedAt,
    mode: s.mode,
    mascot: s.mascot,
    interval: s.interval,
    run: s.run,
    phases: s.records,
    splits: s.splits,
    points: downsample(smoothed.points, 500),
    distanceM: s.distM,
    durationSec,
    elevationGain: smoothed.gain,
    elevationLoss: smoothed.loss,
    elevationEstimated: true,
    elevationSource: "estimated",
    inZoneRatio: s.mode === "corrida" ? ratio : null,
    gpsMode: "sim",
    demo,
  };
}

function runSim(
  mode: WorkoutMode,
  mascot: MascotId,
  start: number,
  interval: IntervalConfig | undefined,
  run: RunConfig | undefined,
  sprintBoost: number,
  stopAfterSec: number | null,
): Activity {
  const s = createSession({
    mode,
    mascot,
    interval,
    run,
    gpsMode: "sim",
    now: start - 3000,
  });
  s.status = "running";
  s.anchorEpoch = start;
  s.startedAt = start;
  s.pausedAccum = 0;
  s.lastTick = start;
  s.sprintFactor = sprintBoost || 1;
  const filter = new TrackFilter();
  const limit = start + (stopAfterSec ?? 0) * 1000;
  const hardEnd =
    mode === "tiros" ? start + totalSeconds(buildIntervalPhases(interval!)) * 1000 + 2000 : limit;
  for (let t = start; t <= hardEnd; t += 1000) {
    const events = advance(s, t);
    if (mode === "corrida" && stopAfterSec != null && t >= limit) break;
    if (events.some((e) => e.type === "done")) break;
    const fix = simStep(s, t);
    if (fix) ingestFix(s, filter, fix, t, Math.max(0, indexOf(s, t)));
    notePaceSample(s, t);
  }
  return sessionToActivity(s, Math.min(hardEnd, start + (stopAfterSec ?? 10 ** 9) * 1000), true);
}

function indexOf(s: Session, now: number): number {
  const elapsed = Math.max(0, now - s.anchorEpoch - s.pausedAccum);
  if (s.mode === "corrida") return 0;
  for (let i = 0; i < s.phases.length; i++) if (elapsed < s.phases[i].endMs) return i;
  return Math.max(0, s.phases.length - 1);
}

export function createInitialStore(): StoreData {
  const classic: IntervalConfig = {
    warmupSec: 3 * 60,
    sprintSec: 60,
    restSec: 45,
    sprintsPerCycle: 4,
    cycles: 1,
    cycleRestSec: 0,
    cooldownSec: 2 * 60,
  };
  const doubles: IntervalConfig = {
    warmupSec: 4 * 60,
    sprintSec: 50,
    restSec: 50,
    sprintsPerCycle: 3,
    cycles: 2,
    cycleRestSec: 90,
    cooldownSec: 2 * 60,
  };
  const activities: Activity[] = [
    runSim("tiros", "oblongo", atHour(26, 7), classic, undefined, 0.9, null),
    runSim("corrida", "hexagono", atHour(22, 18), undefined, { fastSec: 360, slowSec: 390, windowSec: 20 }, 0, 32 * 60),
    runSim("tiros", "bola", atHour(18, 7), classic, undefined, 0.96, null),
    runSim("tiros", "hexagono", atHour(12, 6), doubles, undefined, 1.02, null),
    runSim("corrida", "bola", atHour(8, 19), undefined, { fastSec: 330, slowSec: 360, windowSec: 20 }, 0, 28 * 60),
    runSim("tiros", "triangulo", atHour(4, 7), classic, undefined, 1.08, null),
  ];
  return {
    mascot: "bola",
    runnerName: "",
    profileReady: false,
    theme: "amarelo",
    nightMode: "auto",
    autoPause: true,
    whistles: { ...DEFAULT_WHISTLES },
    activities,
    presets: defaultPresets(),
    permsSeen: false,
  };
}
