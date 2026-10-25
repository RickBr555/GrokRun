import type { IntervalConfig, PhaseKind, PhasePlan, ScheduledPhase } from "./types";

export function phaseLabel(
  kind: PhaseKind,
  sprint: number | null,
): string {
  switch (kind) {
    case "warmup":
      return "Aquecimento";
    case "sprint":
      return sprint ? `Tiro ${sprint}` : "Tiro";
    case "rest":
      return "Descanso";
    case "cycleRest":
      return "Descanso entre ciclos";
    case "cooldown":
      return "Desaquecimento";
    case "run":
      return "Corrida";
  }
}

/**
 * Aquecimento → [tiro → descanso] × tiros (sem descanso após o último tiro
 * do ciclo) → descanso entre ciclos → repete → desaquecimento.
 */
export function buildIntervalPhases(cfg: IntervalConfig): PhasePlan[] {
  const phases: PhasePlan[] = [];
  const push = (
    kind: PhaseKind,
    durationSec: number,
    cycle: number | null,
    sprint: number | null,
  ) => {
    if (durationSec <= 0) return;
    phases.push({
      kind,
      durationSec,
      cycle,
      sprint,
      label: phaseLabel(kind, sprint),
    });
  };

  push("warmup", cfg.warmupSec, null, null);
  const cycles = Math.max(1, Math.floor(cfg.cycles));
  const sprints = Math.max(1, Math.floor(cfg.sprintsPerCycle));
  for (let c = 1; c <= cycles; c++) {
    for (let s = 1; s <= sprints; s++) {
      push("sprint", cfg.sprintSec, c, s);
      if (s !== sprints) push("rest", cfg.restSec, c, s);
    }
    if (c !== cycles) push("cycleRest", cfg.cycleRestSec, c, null);
  }
  push("cooldown", cfg.cooldownSec, null, null);
  return phases;
}

export function schedulePhases(plans: PhasePlan[]): ScheduledPhase[] {
  let t = 0;
  return plans.map((p) => {
    const startMs = t;
    const endMs = t + p.durationSec * 1000;
    t = endMs;
    return { ...p, startMs, endMs };
  });
}

export function totalSeconds(plans: { durationSec: number }[]): number {
  return plans.reduce((sum, p) => sum + Math.max(0, p.durationSec), 0);
}

/** Índice da fase ativa, ou `phases.length` se o treino acabou. */
export function phaseIndexAt(phases: ScheduledPhase[], elapsedMs: number): number {
  for (let i = 0; i < phases.length; i++) {
    if (elapsedMs < phases[i].endMs) return i;
  }
  return phases.length;
}

export function buildRunPhase(): ScheduledPhase {
  return {
    kind: "run",
    durationSec: -1,
    cycle: null,
    sprint: null,
    label: "Corrida",
    startMs: 0,
    endMs: 1e15,
  };
}

/** Horário absoluto da troca, no mesmo eixo de `Date.now()` / elapsedRealtime. */
export function absoluteBoundary(
  anchorEpoch: number,
  pausedAccumMs: number,
  endOffsetMs: number,
): number {
  return anchorEpoch + pausedAccumMs + endOffsetMs;
}

export function recomputeBoundaries(
  anchorEpoch: number,
  pausedAccumMs: number,
  phases: ScheduledPhase[],
): number[] {
  return phases.map((p) => absoluteBoundary(anchorEpoch, pausedAccumMs, p.endMs));
}
