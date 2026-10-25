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

type Screen =
  | { name: "home" }
  | { name: "mode" }
  | { name: "setup"; mode: WorkoutMode }
  | { name: "live"; mode: WorkoutMode }
  | { name: "summary"; id: string }
  | { name: "activities" }
  | { name: "detail"; id: string }
  | { name: "evolution" }
  | { name: "game" }
  | { name: "perms" };

type Engine = { key: string; mode: WorkoutMode; initial: Session | null };

const THEMES: { id: ThemeId; label: string; hint: string }[] = [
  { id: "amarelo", label: "Amarelo", hint: "pastel" },
  { id: "laranja", label: "Laranja", hint: "quente" },
  { id: "verde", label: "Verde", hint: "da pista" },
  { id: "azul", label: "Azul", hint: "noite" },
];

const WHISTLE_MOMENTS: { key: keyof WhistleMap; label: string; hint: string }[] = [
  { key: "start", label: "Começa a corrida", hint: "aquecimento ou largada" },
  { key: "sprint", label: "Começa o tiro", hint: "" },
  { key: "rest", label: "Descanso entre tiros", hint: "" },
  { key: "finalRest", label: "Descanso final", hint: "fim das repetições" },
  { key: "done", label: "Final", hint: "treino encerrado" },
];

function useNightOn(mode: NightMode): boolean {
  const [dark, setDark] = useState(false);
  const [night, setNight] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const sync = () => setDark(mq.matches);
    sync();
    mq.addEventListener("change", sync);
    const hour = () => {
      const h = new Date().getHours();
      setNight(h >= 19 || h < 6);
    };
    hour();
    const id = window.setInterval(hour, 60_000);
    return () => {
      mq.removeEventListener("change", sync);
      window.clearInterval(id);
    };
  }, []);
  if (mode === "on") return true;
  if (mode === "off") return false;
  return dark && night;
}

export function GrokApp() {
  const [ready, setReady] = useState(false);
  const [store, setStore] = useState<StoreData | null>(null);
  const [screen, setScreen] = useState<Screen>({ name: "home" });
  const [intervalCfg, setIntervalCfg] = useState<IntervalConfig>(DEFAULT_INTERVAL);
  const [runCfg, setRunCfg] = useState<RunConfig>(DEFAULT_RUN);
  const [gpsMode, setGpsMode] = useState<GpsMode>("sim");
  const [engine, setEngine] = useState<Engine | null>(null);
  const [hud, setHud] = useState<Hud | null>(null);
  const nightOn = useNightOn(store?.nightMode ?? "auto");

  useEffect(() => {
    const saved = loadStore() ?? createInitialStore();
    setStore(saved);
    document.documentElement.dataset.theme = saved.theme ?? "amarelo";
    const live = loadLive();
    if (live) {
      setEngine({ key: live.id, mode: live.mode, initial: live });
      setScreen({ name: "live", mode: live.mode });
      setGpsMode(live.gpsMode);
    }
    setReady(true);
  }, []);

  useEffect(() => {
    if (ready && store) saveStore(store);
  }, [ready, store]);

  useEffect(() => {
    if (store?.theme) document.documentElement.dataset.theme = store.theme;
  }, [store?.theme]);

  if (!ready || !store) {
    return (
      <div className="app-frame items-center justify-center gap-3 px-6 text-center">
        <p className="font-display text-5xl">GrokRun</p>
        <p className="text-ink/70">Abrindo a pista\u2026</p>
      </div>
    );
  }

  const patch = (next: StoreData) => setStore(next);
  const go = (s: Screen) => setScreen(s);
  const begin = (mode: WorkoutMode) => {
    if (engine) {
      go({ name: "live", mode: engine.mode });
      return;
    }
    unlockAudio();
    saveLive(null);
    setHud(null);
    setEngine({ key: crypto.randomUUID(), mode, initial: null });
    go({ name: "live", mode });
  };

  return (
    <div className="app-frame">
      <header className="flex items-center justify-between px-4 pt-4 pb-2">
        <div>
          <p className="font-display text-3xl leading-none tracking-wide">GrokRun</p>
          <p className="text-sm text-ink/65">tiros, pace e percurso</p>
        </div>
        <button type="button" className="btn btn-cream px-3 py-2 text-sm" onClick={() => go({ name: "perms" })}>
          Ajustes
        </button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
        {screen.name === "home" && (
          <Home mascot={store.mascot} name={store.runnerName} profileReady={store.profileReady} running={engine != null} onSaveProfile={(name, id) => patch({ ...store, runnerName: name.trim() || "Grok", mascot: id, profileReady: true })} onStart={() => { if (engine) go({ name: "live", mode: engine.mode }); else go({ name: "mode" }); }} onPlay={() => go({ name: "game" })} />
        )}
      </div>
    </div>
  );
}
