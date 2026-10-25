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

export function NavButton({
  label,
  on,
  onClick,
  icon,
}: {
  label: string;
  on: boolean;
  onClick: () => void;
  icon: ReactNode;
}) {
  return (
    <button type="button" className={`nav-btn ${on ? "nav-on" : ""}`} onClick={onClick}>
      {icon}
      <span className="text-sm">{label}</span>
    </button>
  );
}

export function Home({
  mascot,
  name,
  profileReady,
  running,
  onSaveProfile,
  onStart,
  onPlay,
}: {
  mascot: MascotId;
  name: string;
  profileReady: boolean;
  running: boolean;
  onSaveProfile: (name: string, id: MascotId) => void;
  onStart: () => void;
  onPlay: () => void;
}) {
  const [draftName, setDraftName] = useState(name);
  const [pick, setPick] = useState<MascotId>(mascot);
  if (!profileReady) {
    return (
      <div className="flex flex-col gap-4">
        <div>
          <h1 className="font-display text-3xl leading-none">Quem corre?</h1>
          <p className="mt-1 text-sm text-ink/70">Escolhe o bot e o nome uma vez. Depois isso fica nos Ajustes.</p>
        </div>
        <input
          className="rounded-full border-[3px] border-ink bg-cream px-4 py-3"
          placeholder="Seu nome"
          aria-label="Seu nome"
          value={draftName}
          onChange={(e) => setDraftName(e.target.value)}
        />
        <div className="grid grid-cols-3 gap-2">
          {MASCOTS.map((m) => (
            <button
              key={m.id}
              type="button"
              onClick={() => setPick(m.id)}
              className={`rounded-2xl border-[3px] p-1 ${pick === m.id ? "border-ink" : "border-transparent"}`}
            >
              <img src={mascotSrc(m.id, "idle")} alt="" className="mx-auto h-24 w-full object-contain" />
              <p className="text-center font-display text-sm">{m.name}</p>
            </button>
          ))}
        </div>
        <button
          type="button"
          className="btn btn-cinder py-3 text-2xl"
          onClick={() => onSaveProfile(draftName, pick)}
        >
          É esse
        </button>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-4">
      <TrackStage id={mascot} />
      <div className="text-center">
        <p className="font-display text-3xl leading-none">{name || "Grok"}</p>
        <p className="text-ink/65">{mascotName(mascot)}</p>
      </div>
      <button type="button" className="btn btn-cinder py-3 text-2xl" onClick={onStart}>
        {running ? "Voltar à corrida" : "Começar"}
      </button>
      <button type="button" className="btn btn-cream py-3 text-2xl" onClick={onPlay}>
        Jogo da pista
      </button>
    </div>
  );
}

export function ModePick({
  mascot,
  onBack,
  onPick,
}: {
  mascot: MascotId;
  onBack: () => void;
  onPick: (m: WorkoutMode) => void;
}) {
  return (
    <div className="flex flex-col gap-4">
      <BackBar title="Modo" onBack={onBack} />
      <button type="button" className="card overflow-hidden p-0 text-left" onClick={() => onPick("corrida")}>
        <LaneCross ids={MASCOTS.map((m) => m.id)} />
        <div className="p-3">
          <p className="font-display text-2xl leading-none">Corrida</p>
          <p className="text-sm text-ink/70">O grupo atravessa junto, no ritmo da faixa.</p>
        </div>
      </button>
      <button type="button" className="card overflow-hidden p-0 text-left" onClick={() => onPick("tiros")}>
        <LaneCross ids={[mascot]} fast />
        <div className="p-3">
          <p className="font-display text-2xl leading-none">Tiros</p>
          <p className="text-sm text-ink/70">Um só, bem mais rápido, de um lado ao outro.</p>
        </div>
      </button>
    </div>
  );
}

