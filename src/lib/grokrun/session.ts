import { buildIntervalPhases, buildRunPhase, recomputeBoundaries, schedulePhases } from "./engine";
import {
  altitudePark,
  altitudeTrack,
  parkPoint,
  pressureToAltitude,
  trackPoint,
  TrackFilter,
  type RawFix,
} from "./geo";
import { makeRecord, movingPace } from "./metrics";
import type {
  GpsMode,
  IntervalConfig,
  KmSplit,
  MascotId,
  PhaseKind,
  PhaseRecord,
  RunConfig,
  ScheduledPhase,
  TrackPoint,
  WorkoutMode,
} from "./types";

export interface Session {
  id: string;
  mode: WorkoutMode;
  mascot: MascotId;
  interval?: IntervalConfig;
  run?: RunConfig;
  phases: ScheduledPhase[];
  /** Epoch em que o relógio do treino começou (fim da contagem). */
  anchorEpoch: number;
  pausedAccum: number;
  pauseStarted: number | null;
  status: "prepare" | "running" | "paused" | "done";
  prepareEnds: number;
  /** Horários absolutos de fim de cada fase. Recalculados ao retomar. */
  endsAt: number[];
  points: TrackPoint[];
  records: PhaseRecord[];
  closedUntil: number;
  splits: KmSplit[];
  nextSplitKm: number;
  splitElapsedSec: number;
  inZoneMs: number;
  paceSampleMs: number;
  outsideSince: number | null;
  outsideKind: "fast" | "slow" | null;
  lastAlertEpoch: number;
  gpsMode: GpsMode;
  simDist: number;
  lastSimMs: number;
  distM: number;
  fired: string[];
  startedAt: number;
  lastPace: number | null;
  lastSpeed: number | null;
  lastTick: number;
  baroAnchor: number | null;
  alert: "fast" | "slow" | null;
  /** manual = o botão; auto = parou na rua. */
  pauseReason: "manual" | "auto" | null;
  stillSince: number | null;
  moveSince: number | null;
  /** Último instante com deslocamento de verdade. */
  movedAt: number;
  /** Velocidade instantânea do GPS, km/h. */
  instKmh: number | null;
  lastFixAt: number;
  /** Multiplica a velocidade dos tiros na simulação (evolução de exemplo). */
  sprintFactor: number;
}

export interface SessionDraft {
  mode: WorkoutMode;
  mascot: MascotId;
  interval?: IntervalConfig;
  run?: RunConfig;
  gpsMode: GpsMode;
  now: number;
}

export function createSession(draft: SessionDraft): Session {
  const phases =
    draft.mode === "tiros"
      ? schedulePhases(buildIntervalPhases(draft.interval!))
      : [buildRunPhase()];
  const prepareEnds = draft.now + 3000;
  return {
    id: crypto.randomUUID(),
    mode: draft.mode,
    mascot: draft.mascot,
    interval: draft.interval,
    run: draft.run,
    phases,
    anchorEpoch: prepareEnds,
    pausedAccum: 0,
    pauseStarted: null,
    status: "prepare",
    prepareEnds,
    endsAt: [],
    points: [],
    records: [],
    closedUntil: 0,
    splits: [],
    nextSplitKm: 1,
    splitElapsedSec: 0,
    inZoneMs: 0,
    paceSampleMs: 0,
    outsideSince: null,
    outsideKind: null,
    lastAlertEpoch: 0,
    gpsMode: draft.gpsMode,
    simDist: 0,
    lastSimMs: 0,
    distM: 0,
    fired: [],
    startedAt: draft.now,
    lastPace: null,
    lastSpeed: null,
    lastTick: draft.now,
    baroAnchor: null,
    alert: null,
    pauseReason: null,
    stillSince: null,
    moveSince: null,
    movedAt: draft.now,
    instKmh: null,
    lastFixAt: 0,
    sprintFactor: 1,
  };
}

export function elapsedMs(s: Session, now: number): number {
  if (s.status === "prepare") return 0;
  const frozen = s.status === "paused" || s.status === "done";
  const freeze = frozen && s.pauseStarted != null ? s.pauseStarted : now;
  return Math.max(0, freeze - s.anchorEpoch - s.pausedAccum);
}

export function activeIndex(s: Session, now: number): number {
  const elapsed = elapsedMs(s, now);
  if (s.mode === "corrida") return s.status === "prepare" ? -1 : 0;
  for (let i = 0; i < s.phases.length; i++) {
    if (elapsed < s.phases[i].endMs) return i;
  }
  return s.phases.length;
}

function armBoundaries(s: Session): void {
  s.endsAt = recomputeBoundaries(s.anchorEpoch, s.pausedAccum, s.phases);
}

export function pauseSession(s: Session, now: number, reason: "manual" | "auto" = "manual"): void {
  if (s.status !== "running") return;
  s.pauseStarted = now;
  s.status = "paused";
  s.pauseReason = reason;
  s.alert = null;
  s.stillSince = null;
  s.moveSince = null;
}

export function resumeSession(s: Session, now: number): void {
  if (s.status !== "paused" || s.pauseStarted == null) return;
  s.pausedAccum += now - s.pauseStarted;
  s.pauseStarted = null;
  s.status = "running";
  s.pauseReason = null;
  s.lastTick = now;
  s.stillSince = null;
  s.moveSince = null;
  s.movedAt = now;
  armBoundaries(s);
}

const STOP_KMH = 1.15;
const GO_KMH = 2.3;
const STOP_GAP_MS = 4500;

/**
 * Pausa o relógio se a pessoa parou na rua, e solta quando volta a andar.
 * Pausa manual não é desfeita aqui.
 */
export function noteMotion(s: Session, now: number, kmh: number | null, autoPause: boolean): void {
  if (kmh != null) s.instKmh = kmh;
  if (!autoPause || s.pauseReason === "manual" || s.status === "prepare" || s.status === "done") return;
  if (!s.lastFixAt || now - s.lastFixAt > 8000) return;
  const slow = kmh == null || kmh < STOP_KMH;
  const stale = now - s.movedAt >= STOP_GAP_MS;
  if (s.status === "running") {
    if (slow && stale && s.points.length > 1) {
      if (s.stillSince == null) s.stillSince = now;
      if (now - s.stillSince >= 700) pauseSession(s, now, "auto");
    } else {
      s.stillSince = null;
    }
    return;
  }
  if (s.status === "paused" && s.pauseReason === "auto") {
    const going = kmh != null && kmh >= GO_KMH;
    if (going) {
      if (s.moveSince == null) s.moveSince = now;
      if (now - s.moveSince >= 1200) resumeSession(s, now);
    } else {
      s.moveSince = null;
    }
  }
}

export interface CueEvent {
  type: "countdown" | "phase" | "done" | "fast" | "slow";
  kind?: PhaseKind;
}

/**
 * Avança o relógio. O tempo vem de horários absolutos, nunca de um
 * contador que incrementa a cada tick — se a aba dormiu, o treino
 * alcança a fase certa no próximo pulso.
 */
export function advance(s: Session, now: number): CueEvent[] {
  const events: CueEvent[] = [];
  if (s.status === "done") return events;

  if (s.status === "prepare") {
    const left = s.prepareEnds - now;
    const n = left > 2000 ? 3 : left > 1000 ? 2 : left > 0 ? 1 : 0;
    if (n > 0) {
      const key = `prep-${n}`;
      if (!s.fired.includes(key)) {
        s.fired.push(key);
        events.push({ type: "countdown" });
      }
    }
    if (now >= s.prepareEnds) {
      s.status = "running";
      s.anchorEpoch = s.prepareEnds;
      s.pausedAccum = 0;
      s.lastTick = s.prepareEnds;
      armBoundaries(s);
      events.push({ type: "phase", kind: s.phases[0]?.kind ?? "run" });
      s.fired.push("enter-0");
    } else {
      return events;
    }
  }

  if (s.status === "paused") return events;

  const elapsed = elapsedMs(s, now);
  const idx = activeIndex(s, now);

  if (s.mode === "tiros") {
    const current = idx < s.phases.length ? idx : s.phases.length - 1;
    const phase = s.phases[current];
    if (phase && idx < s.phases.length && phase.durationSec > 0) {
      const remain = phase.endMs - elapsed;
      const n = remain <= 1000 ? 1 : remain <= 2000 ? 2 : remain <= 3000 ? 3 : 0;
      if (n > 0 && phase.durationSec >= n) {
        const key = `cd-${current}-${n}`;
        if (!s.fired.includes(key)) {
          s.fired.push(key);
          events.push({ type: "countdown" });
        }
      }
    }
    if (idx >= s.phases.length) {
      closeThrough(s, s.phases.length, elapsed);
      // O plano acabou, mas a corrida só termina no Finalizar.
      if (!s.fired.includes("plan-done")) {
        s.fired.push("plan-done");
        events.push({ type: "done" });
      }
      return events;
    }
    for (let i = s.closedUntil + 1; i <= idx; i++) {
      const key = `enter-${i}`;
      if (!s.fired.includes(key)) {
        s.fired.push(key);
        events.push({ type: "phase", kind: s.phases[i].kind });
      }
    }
    closeThrough(s, idx, elapsed);
  }

  return events;
}

function closeThrough(s: Session, uptoExclusive: number, elapsed: number): void {
  while (s.closedUntil < uptoExclusive && s.closedUntil < s.phases.length) {
    const i = s.closedUntil;
    s.records.push(makeRecord(s.phases[i], elapsed, s.points, i));
    s.closedUntil = i + 1;
  }
}

export function notePaceSample(s: Session, now: number): CueEvent | null {
  if (s.mode !== "corrida" || s.status !== "running" || !s.run) {
    s.alert = null;
    return null;
  }
  const dt = Math.min(2000, Math.max(0, now - s.lastTick));
  s.lastTick = now;
  const window = movingPace(s.points, now, s.run.windowSec);
  s.lastPace = window.paceSec;
  s.lastSpeed = window.speedKmh;
  if (window.paceSec == null) {
    s.outsideSince = null;
    s.outsideKind = null;
    s.alert = null;
    return null;
  }
  s.paceSampleMs += dt;
  const fast = Math.min(s.run.fastSec, s.run.slowSec);
  const slow = Math.max(s.run.fastSec, s.run.slowSec);
  let kind: "fast" | "slow" | "in" = "in";
  if (window.paceSec < fast) kind = "fast";
  else if (window.paceSec > slow) kind = "slow";
  if (kind === "in") {
    s.inZoneMs += dt;
    s.outsideSince = null;
    s.outsideKind = null;
    s.alert = null;
    return null;
  }
  if (s.outsideKind !== kind) {
    s.outsideKind = kind;
    s.outsideSince = now;
    s.alert = null;
    return null;
  }
  if (s.outsideSince == null) s.outsideSince = now;
  if (now - s.outsideSince >= 5000 && now - s.lastAlertEpoch >= 15000) {
    s.lastAlertEpoch = now;
    s.alert = kind;
    return { type: kind };
  }
  s.alert = now - s.outsideSince >= 5000 ? kind : null;
  return null;
}

export function ingestFix(
  s: Session,
  filter: TrackFilter,
  raw: RawFix,
  now: number,
  phaseIndex: number,
): void {
  if (s.status !== "running") return;
  const accepted = filter.push(raw);
  if (!accepted) return;
  s.distM += accepted.stepM;
  const phase = s.phases[Math.min(phaseIndex, s.phases.length - 1)];
  const point: TrackPoint = {
    lat: accepted.lat,
    lon: accepted.lon,
    t: raw.t,
    acc: raw.acc,
    alt: anchorAlt(s, raw.alt),
    altSource: s.baroAnchor != null ? "baro" : "gps",
    phase: phase?.kind ?? "run",
    phaseIndex: Math.max(0, phaseIndex),
    distM: s.distM,
  };
  s.points.push(point);
  s.lastFixAt = now;
  if (accepted.stepM >= 0.8) s.movedAt = now;
  const elapsedSec = elapsedMs(s, now) / 1000;
  while (s.distM >= s.nextSplitKm * 1000) {
    const splitSec = elapsedSec - s.splitElapsedSec;
    s.splits.push({
      km: s.nextSplitKm,
      timeSec: elapsedSec,
      splitSec,
      paceSec: splitSec,
    });
    s.splitElapsedSec = elapsedSec;
    s.nextSplitKm += 1;
  }
  if (s.mode === "tiros" || s.mode === "corrida") {
    const windowSec = s.run?.windowSec ?? 20;
    const pace = movingPace(s.points, raw.t, windowSec);
    if (s.mode === "tiros") {
      s.lastPace = pace.paceSec;
      s.lastSpeed = pace.speedKmh;
    }
  }
}

function anchorAlt(s: Session, alt: number | null): number | null {
  if (alt == null) return null;
  if (s.baroAnchor == null) return alt;
  return alt;
}

export function notePressure(s: Session, pressureHpa: number, gpsAlt: number | null): number {
  const rel = pressureToAltitude(pressureHpa);
  if (s.baroAnchor == null) {
    const base = gpsAlt ?? rel;
    s.baroAnchor = base - rel;
  }
  return rel + s.baroAnchor;
}

export function simStep(s: Session, now: number): RawFix | null {
  if (s.status !== "running" || s.gpsMode !== "sim") return null;
  const elapsed = elapsedMs(s, now);
  const idx = Math.max(0, Math.min(activeIndex(s, now), s.phases.length - 1));
  const phase = s.phases[idx];
  const last = s.lastSimMs || now - 1000;
  const dt = Math.min(2, Math.max(0.2, (now - last) / 1000));
  s.lastSimMs = now;
  const speed = speedFor(s, phase?.kind ?? "run", phase?.sprint ?? 1, elapsed / 1000);
  s.simDist += speed * dt;
  const ll = s.mode === "corrida" ? parkPoint(s.simDist) : trackPoint(s.simDist);
  const alt = s.mode === "corrida" ? altitudePark(s.simDist) : altitudeTrack(s.simDist);
  return {
    lat: ll.lat,
    lon: ll.lon,
    t: now,
    acc: 6,
    alt,
  };
}

function speedFor(s: Session, kind: PhaseKind, sprint: number, elapsedSec: number): number {
  if (kind === "run" && s.run) {
    const fast = Math.min(s.run.fastSec, s.run.slowSec);
    const slow = Math.max(s.run.fastSec, s.run.slowSec);
    const mid = (fast + slow) / 2;
    const base = 1000 / mid;
    const cycle = elapsedSec % 90;
    if (cycle > 58 && cycle < 78) {
      const faster = 1000 / Math.max(140, fast - 45);
      const slower = 1000 / (slow + 50);
      return elapsedSec % 180 < 90 ? faster : slower;
    }
    return base;
  }
  if (kind === "warmup") return 2.65;
  if (kind === "sprint") return (3.55 + (sprint % 3) * 0.22) * s.sprintFactor;
  if (kind === "rest") return 1.35;
  if (kind === "cycleRest") return 1.15;
  if (kind === "cooldown") return 1.55;
  return 2.4;
}

export function finalizeRecords(s: Session, now: number): void {
  const elapsed = s.status === "prepare" ? 0 : elapsedMs(s, now);
  const idx = s.mode === "corrida" ? 1 : activeIndex(s, now);
  const through = s.status === "done" ? s.phases.length : Math.min(s.phases.length, idx + (s.mode === "corrida" ? 1 : 0));
  if (s.mode === "corrida") {
    s.records = [makeRecord(s.phases[0], elapsed, s.points, 0)];
    s.closedUntil = 1;
    return;
  }
  closeThrough(s, through === idx ? idx : through, elapsed);
  if (s.status !== "done" && idx < s.phases.length && s.closedUntil === idx) {
    s.records.push(makeRecord(s.phases[idx], elapsed, s.points, idx));
    s.closedUntil = idx + 1;
  }
}
