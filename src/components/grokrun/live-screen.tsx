import { useEffect, useMemo, useState, type ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { buildIntervalPhases, schedulePhases, totalSeconds } from "@/lib/grokrun/engine";
import {
  formatClock,
  formatKm,
  formatMeters,
  formatPace,
  formatPct,
  formatSpeed,
  formatWhen,
} from "@/lib/grokrun/format";
import { activityToGpx, downloadText } from "@/lib/grokrun/gpx";
import { createInitialStore, DEFAULT_INTERVAL, DEFAULT_RUN, defaultPresets } from "@/lib/grokrun/seed";
import type { Session } from "@/lib/grokrun/session";
import { loadLive, loadStore, saveLive, saveStore } from "@/lib/grokrun/storage";
import {
  DEFAULT_WHISTLES,
  type Activity,
  type GpsMode,
  type IntervalConfig,
  type MascotId,
  type NightMode,
  type PhaseRecord,
  type Preset,
  type RunConfig,
  type StoreData,
  type ThemeId,
  type WhistleId,
  type WhistleMap,
  type WorkoutMode,
} from "@/lib/grokrun/types";
import { playWhistle, unlockAudio, WHISTLE_CHOICES } from "@/lib/grokrun/audio";
import { BackBar, MascotCel, NightChase, RunCycle } from "./bits";
import { RaceGame } from "./RaceGame";
import { BarMini, ElevationChart, PaceChart } from "./Charts";
import { LiveMap } from "./MapView";
import { useWorkout, type Hud } from "./useWorkout";

export function Live({
  draft,
  initial,
  whistles,
  autoPause,
  night,
  onHud,
  onMinimize,
  onFinished,
  onAbort,
}: {
  draft: {
    mode: WorkoutMode;
    mascot: MascotId;
    interval?: IntervalConfig;
    run?: RunConfig;
    gpsMode: GpsMode;
  };
  initial: Session | null;
  whistles: WhistleMap;
  autoPause: boolean;
  night: boolean;
  onHud: (hud: Hud) => void;
  onMinimize: () => void;
  onFinished: (a: Activity) => void;
  onAbort: () => void;
}) {
  const w = useWorkout({ draft, initial, whistles, autoPause, onHud, onFinished, onAbort });
  const hud = w.hud;
  const pose =
    hud?.phaseKind === "rest" || hud?.phaseKind === "cycleRest" || hud?.phaseKind === "cooldown"
      ? "rest"
      : hud?.status === "prepare"
        ? "idle"
        : "run";
  const big =
    hud?.status === "prepare"
      ? String(hud.prepareCount ?? 3)
      : hud?.remainSec != null
        ? formatClock(hud.remainSec)
        : formatClock(hud?.elapsedSec ?? 0);
  const paused = hud?.status === "paused";
  return (
    <div className="flex h-full min-h-0 flex-col gap-2">
      {night ? <NightChase id={draft.mascot} /> : null}
      <div className="flex items-start gap-2">
        <button
          type="button"
          aria-label="Voltar para a pista"
          className="grid size-11 shrink-0 place-items-center rounded-2xl border-[3px] border-ink bg-cream"
          onClick={onMinimize}
        >
          <ArrowLeft size={22} />
        </button>
        <div className="min-w-0 flex-1">
          <p className="text-sm text-ink/65">
            {paused ? (hud?.pauseReason === "auto" ? "Parado na rua" : "Pausado") : "Agora"}
          </p>
          <h1 className="font-display text-3xl leading-none">{hud?.phaseLabel ?? "Preparar"}</h1>
          <p className="text-sm text-ink/70">
            {hud?.sprintText ? `Tiro ${hud.sprintText}` : ""}
            {hud?.sprintText && hud?.cycleText ? " · " : ""}
            {hud?.cycleText ? `Ciclo ${hud.cycleText}` : ""}
            {hud?.nextLabel ? ` · depois: ${hud.nextLabel}` : ""}
          </p>
        </div>
        {night ? null : pose === "run" ? (
          <RunCycle id={draft.mascot} small className="h-20 w-20 shrink-0" />
        ) : (
          <MascotCel id={draft.mascot} pose={pose} frameClass="h-24 w-20" />
        )}
      </div>
      <div className="card px-3 py-5 text-center">
        <p className="font-display text-7xl leading-none tabular-nums">{big}</p>
        <p className="mt-1 text-sm text-ink/60">
          {hud?.status === "prepare" ? "segundos" : hud?.remainSec != null ? "restante" : "decorrido"}
          {hud?.totalSec ? ` · total ${formatClock(hud.totalSec)}` : ""}
        </p>
      </div>
      <LiveMap points={w.session()?.points ?? []} className="min-h-40 flex-1" />
      {paused && hud?.pauseReason === "auto" ? (
        <p className="text-center text-sm text-ink/70">
          Você parou. O tempo pausou. Quando andar de novo, continua sozinho.
        </p>
      ) : null}
      {hud?.alert === "fast" ? (
        <p className="card bg-cinder px-3 py-2 text-center font-display text-sticker">Rápido demais</p>
      ) : null}
      {hud?.alert === "slow" ? (
        <p className="card bg-ink px-3 py-2 text-center font-display text-paper">Lento demais</p>
      ) : null}
      <div className="grid grid-cols-3 gap-2">
        <Stat k="Pace" v={formatPace(hud?.paceSec)} />
        <Stat k="Velocidade" v={formatSpeed(hud?.speedKmh)} />
        <Stat k="Distância" v={formatKm(hud?.distanceM ?? 0)} />
      </div>
      <div className="flex items-center justify-between text-sm">
        <span className="chip">
          {hud?.gps === "sim" ? "Pista simulada" : hud?.gps === "buscando" ? "Buscando GPS" : "GPS"}
        </span>
        {draft.mode === "corrida" && hud?.inZoneRatio != null ? (
          <span>Na faixa {formatPct(hud.inZoneRatio)}</span>
        ) : null}
      </div>
      <p className="text-sm text-ink/60">Sair desta tela não pausa. O relógio segue.</p>
      {hud?.gps === "buscando" ? (
        <button type="button" className="btn btn-gold py-2" onClick={w.useSim}>
          Usar pista simulada
        </button>
      ) : null}
      <div className="grid grid-cols-2 gap-2">
        {paused ? (
          <button type="button" className="btn btn-gold py-3 text-xl" onClick={w.resume}>
            Retomar
          </button>
        ) : (
          <button type="button" className="btn btn-cream py-3 text-xl" onClick={w.pause} disabled={hud?.status === "prepare"}>
            Pausar
          </button>
        )}
        <button type="button" className="btn btn-ink py-3 text-xl" onClick={w.askStop}>
          Finalizar
        </button>
      </div>
      {paused && hud?.pauseReason === "auto" ? (
        <button type="button" className="btn btn-cream py-2" onClick={w.holdPause}>
          Segurar pausa mesmo se eu andar
        </button>
      ) : null}
      <button type="button" className="text-sm underline" onClick={onMinimize}>
        Minimizar e continuar correndo
      </button>
      {w.saving ? <p className="text-center text-sm">Ajustando elevação…</p> : null}
      {w.confirming ? (
        <div className="card p-3">
          <p className="font-display text-xl">Finalizar corrida?</p>
          <p className="text-sm text-ink/70">O que você já correu fica salvo e aparece o resumo.</p>
          <div className="mt-2 flex gap-2">
            <button type="button" className="btn btn-cinder flex-1 py-2" onClick={w.confirmStop}>
              Sim
            </button>
            <button type="button" className="btn btn-cream flex-1 py-2" onClick={w.cancelStop}>
              Continuar
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

export function Stat({ k, v }: { k: string; v: string }) {
  return (
    <div className="card px-2 py-2 text-center">
      <p className="text-xs text-ink/60">{k}</p>
      <p className="font-display text-lg leading-tight tabular-nums">{v}</p>
    </div>
  );
}

export function sprintRows(phases: PhaseRecord[]) {
  return phases.filter((p) => p.kind === "sprint");
}

