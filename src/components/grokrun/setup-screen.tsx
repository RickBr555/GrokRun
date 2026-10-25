import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Flag, History, TrendingUp, Volume2 } from "lucide-react";
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
import { BackBar, LaneCross, MascotCel, MASCOTS, mascotName, mascotSrc, NightChase, PhaseBar, RunClip, TimeStepper, TrackStage } from "./bits";
import { RaceGame } from "./RaceGame";
import { BarMini, ElevationChart, PaceChart } from "./Charts";
import { MapView } from "./MapView";
import { useWorkout, type Hud } from "./useWorkout";

export function GpsToggle({ mode, onChange }: { mode: GpsMode; onChange: (m: GpsMode) => void }) {
  return (
    <div className="grid grid-cols-2 gap-2">
      <button
        type="button"
        className={`chip ${mode === "sim" ? "chip-on" : ""}`}
        onClick={() => onChange("sim")}
      >
        Pista simulada
      </button>
      <button
        type="button"
        className={`chip ${mode === "device" ? "chip-on" : ""}`}
        onClick={() => onChange("device")}
      >
        GPS do aparelho
      </button>
    </div>
  );
}

export function IntervalSetup({
  cfg,
  presets,
  gpsMode,
  onGps,
  onChange,
  onBack,
  onStart,
  onSavePreset,
  onDeletePreset,
  onUsePreset,
}: {
  cfg: IntervalConfig;
  presets: Preset[];
  gpsMode: GpsMode;
  onGps: (m: GpsMode) => void;
  onChange: (c: IntervalConfig) => void;
  onBack: () => void;
  onStart: () => void;
  onSavePreset: (name: string) => void;
  onDeletePreset: (id: string) => void;
  onUsePreset: (p: Preset) => void;
}) {
  const phases = schedulePhases(buildIntervalPhases(cfg));
  const total = totalSeconds(phases);
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState("");
  const set = (patch: Partial<IntervalConfig>) => onChange({ ...cfg, ...patch });
  const mine = presets.filter((p) => p.mode === "tiros");
  return (
    <div className="flex flex-col gap-2">
      <BackBar title="Tiros" onBack={onBack} />
      <div className="flex gap-2 overflow-x-auto pb-1">
        {mine.map((p) => (
          <button key={p.id} type="button" className="chip shrink-0" onClick={() => onUsePreset(p)}>
            {p.name}
          </button>
        ))}
      </div>
      <div className="card px-3">
        <TimeStepper label="Aquecimento" value={cfg.warmupSec} step={15} onChange={(n) => set({ warmupSec: n })} />
        <TimeStepper label="Duração do tiro" value={cfg.sprintSec} min={5} step={5} onChange={(n) => set({ sprintSec: n })} />
        <TimeStepper label="Descanso entre tiros" value={cfg.restSec} step={5} onChange={(n) => set({ restSec: n })} />
        <CountStepper label="Tiros por ciclo" value={cfg.sprintsPerCycle} min={1} max={30} onChange={(n) => set({ sprintsPerCycle: n })} />
        <CountStepper label="Ciclos" value={cfg.cycles} min={1} max={20} onChange={(n) => set({ cycles: n })} />
        <TimeStepper label="Descanso entre ciclos" hint="Pode ser zero" value={cfg.cycleRestSec} step={15} onChange={(n) => set({ cycleRestSec: n })} />
        <TimeStepper label="Desaquecimento" value={cfg.cooldownSec} step={15} onChange={(n) => set({ cooldownSec: n })} />
      </div>
      <div className="card p-3">
        <div className="mb-2 flex items-end justify-between">
          <p className="text-sm text-ink/70">Tempo total</p>
          <p className="font-display text-4xl leading-none tabular-nums">{formatClock(total)}</p>
        </div>
        <PhaseBar parts={phases} />
        <p className="mt-2 text-sm text-ink/65">
          Sem descanso depois do último tiro de cada ciclo.
        </p>
      </div>
      <GpsToggle mode={gpsMode} onChange={onGps} />
      {naming ? (
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!name.trim()) return;
            onSavePreset(name.trim());
            setName("");
            setNaming(false);
          }}
        >
          <input
            className="min-w-0 flex-1 rounded-full border-[3px] border-ink bg-cream px-3 py-2"
            placeholder="Nome do preset"
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-label="Nome do preset"
          />
          <button type="submit" className="btn btn-ink px-4">
            Salvar
          </button>
        </form>
      ) : (
        <button type="button" className="btn btn-cream py-2" onClick={() => setNaming(true)}>
          Salvar preset
        </button>
      )}
      {mine.length > defaultPresets().filter((p) => p.mode === "tiros").length ? (
        <div className="flex flex-wrap gap-2">
          {mine
            .filter((p) => !p.id.startsWith("preset-"))
            .map((p) => (
              <button key={p.id} type="button" className="text-sm text-ink/70 underline" onClick={() => onDeletePreset(p.id)}>
                Apagar {p.name}
              </button>
            ))}
        </div>
      ) : null}
      <button type="button" className="btn btn-cinder py-3 text-2xl" onClick={onStart} disabled={cfg.sprintSec <= 0}>
        Começar tiros
      </button>
    </div>
  );
}

export function CountStepper({
  label,
  value,
  min,
  max,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (n: number) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-2.5">
      <p className="font-display text-lg leading-tight">{label}</p>
      <div className="flex items-center gap-2">
        <button type="button" className="step-btn" aria-label={`Diminuir ${label}`} onClick={() => onChange(Math.max(min, value - 1))}>
          –
        </button>
        <span className="w-10 text-center font-display text-xl tabular-nums">{value}</span>
        <button type="button" className="step-btn" aria-label={`Aumentar ${label}`} onClick={() => onChange(Math.min(max, value + 1))}>
          +
        </button>
      </div>
    </div>
  );
}

export function RunSetup({
  cfg,
  presets,
  gpsMode,
  onGps,
  onChange,
  onBack,
  onStart,
  onSavePreset,
  onDeletePreset,
  onUsePreset,
}: {
  cfg: RunConfig;
  presets: Preset[];
  gpsMode: GpsMode;
  onGps: (m: GpsMode) => void;
  onChange: (c: RunConfig) => void;
  onBack: () => void;
  onStart: () => void;
  onSavePreset: (name: string) => void;
  onDeletePreset: (id: string) => void;
  onUsePreset: (p: Preset) => void;
}) {
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState("");
  const mine = presets.filter((p) => p.mode === "corrida");
  const fast = Math.min(cfg.fastSec, cfg.slowSec);
  const slow = Math.max(cfg.fastSec, cfg.slowSec);
  return (
    <div className="flex flex-col gap-3">
      <BackBar title="Corrida" onBack={onBack} />
      <div className="flex gap-2 overflow-x-auto">
        {mine.map((p) => (
          <button key={p.id} type="button" className="chip shrink-0" onClick={() => onUsePreset(p)}>
            {p.name}
          </button>
        ))}
      </div>
      <div className="card px-3">
        <TimeStepper
          label="Pace mais rápido"
          hint="Abaixo disso, apito agudo"
          value={fast}
          min={3 * 60}
          max={12 * 60}
          step={5}
          onChange={(n) => onChange({ ...cfg, fastSec: Math.min(n, slow - 5) })}
        />
        <TimeStepper
          label="Pace mais lento"
          hint="Acima disso, apito grave"
          value={slow}
          min={3 * 60 + 5}
          max={15 * 60}
          step={5}
          onChange={(n) => onChange({ ...cfg, slowSec: Math.max(n, fast + 5) })}
        />
        <CountStepper
          label="Janela do pace"
          value={cfg.windowSec}
          min={15}
          max={30}
          onChange={(n) => onChange({ ...cfg, windowSec: n })}
        />
      </div>
      <p className="text-sm text-ink/70">
        O pace é a média dos últimos {cfg.windowSec} s. O alerta espera 5 s fora da faixa e
        não repete antes de 15 s. Dentro da faixa, silêncio.
      </p>
      <GpsToggle mode={gpsMode} onChange={onGps} />
      {naming ? (
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!name.trim()) return;
            onSavePreset(name.trim());
            setName("");
            setNaming(false);
          }}
        >
          <input
            className="min-w-0 flex-1 rounded-full border-[3px] border-ink bg-cream px-3 py-2"
            placeholder="Nome do preset"
            value={name}
            onChange={(e) => setName(e.target.value)}
            aria-label="Nome do preset"
          />
          <button type="submit" className="btn btn-ink px-4">
            Salvar
          </button>
        </form>
      ) : (
        <button type="button" className="btn btn-cream py-2" onClick={() => setNaming(true)}>
          Salvar preset
        </button>
      )}
      <div className="flex flex-wrap gap-2">
        {mine
          .filter((p) => !p.id.startsWith("preset-"))
          .map((p) => (
            <button key={p.id} type="button" className="text-sm underline" onClick={() => onDeletePreset(p.id)}>
              Apagar {p.name}
            </button>
          ))}
      </div>
      <button type="button" className="btn btn-cinder py-3 text-2xl" onClick={onStart}>
        Começar corrida
      </button>
    </div>
  );
}

