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
import { Home, ModePick, NavButton } from "./home-screen";
import { IntervalSetup, RunSetup } from "./setup-screen";
import { Live } from "./live-screen";
import { Summary, Activities, Detail } from "./after-screen";
import { Evolution, Perms } from "./more-screen";

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
        <p className="text-ink/70">Abrindo a pista…</p>
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
        <button
          type="button"
          className="btn btn-cream px-3 py-2 text-sm"
          onClick={() => go({ name: "perms" })}
        >
          Ajustes
        </button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
        {screen.name === "home" && (
          <Home
            mascot={store.mascot}
            name={store.runnerName}
            profileReady={store.profileReady}
            running={engine != null}
            onSaveProfile={(name, id) =>
              patch({ ...store, runnerName: name.trim() || "Grok", mascot: id, profileReady: true })
            }
            onStart={() => {
              if (engine) go({ name: "live", mode: engine.mode });
              else go({ name: "mode" });
            }}
            onPlay={() => go({ name: "game" })}
          />
        )}
        {screen.name === "game" && <RaceGame id={store.mascot} onBack={() => go({ name: "home" })} />}
        {screen.name === "mode" && (
          <ModePick
            mascot={store.mascot}
            onBack={() => go({ name: "home" })}
            onPick={(mode) => go({ name: "setup", mode })}
          />
        )}
        {screen.name === "setup" && screen.mode === "tiros" && (
          <IntervalSetup
            cfg={intervalCfg}
            presets={store.presets}
            gpsMode={gpsMode}
            onGps={setGpsMode}
            onChange={setIntervalCfg}
            onBack={() => go({ name: "mode" })}
            onSavePreset={(name) =>
              patch({
                ...store,
                presets: [
                  { id: crypto.randomUUID(), name, mode: "tiros", interval: { ...intervalCfg } },
                  ...store.presets,
                ],
              })
            }
            onDeletePreset={(id) =>
              patch({ ...store, presets: store.presets.filter((p) => p.id !== id) })
            }
            onUsePreset={(p) => p.interval && setIntervalCfg(p.interval)}
            onStart={() => begin("tiros")}
          />
        )}
        {screen.name === "setup" && screen.mode === "corrida" && (
          <RunSetup
            cfg={runCfg}
            presets={store.presets}
            gpsMode={gpsMode}
            onGps={setGpsMode}
            onChange={setRunCfg}
            onBack={() => go({ name: "mode" })}
            onSavePreset={(name) =>
              patch({
                ...store,
                presets: [
                  { id: crypto.randomUUID(), name, mode: "corrida", run: { ...runCfg } },
                  ...store.presets,
                ],
              })
            }
            onDeletePreset={(id) =>
              patch({ ...store, presets: store.presets.filter((p) => p.id !== id) })
            }
            onUsePreset={(p) => p.run && setRunCfg(p.run)}
            onStart={() => begin("corrida")}
          />
        )}
        {engine && (
          <div className={screen.name === "live" ? "" : "hidden"}>
            <Live
              key={engine.key}
              night={nightOn && screen.name === "live"}
              whistles={store.whistles ?? DEFAULT_WHISTLES}
              autoPause={store.autoPause !== false}
              onHud={setHud}
              onMinimize={() => go({ name: "home" })}
              draft={{
                mode: engine.mode,
                mascot: store.mascot,
                interval: engine.mode === "tiros" ? intervalCfg : undefined,
                run: engine.mode === "corrida" ? runCfg : undefined,
                gpsMode: engine.initial?.gpsMode ?? gpsMode,
              }}
              initial={engine.initial}
              onAbort={() => {
                setEngine(null);
                setHud(null);
                go({ name: "home" });
              }}
              onFinished={(activity) => {
                setEngine(null);
                setHud(null);
                patch({ ...store, activities: [activity, ...store.activities] });
                go({ name: "summary", id: activity.id });
              }}
            />
          </div>
        )}
        {screen.name === "summary" && (
          <Summary
            activity={store.activities.find((a) => a.id === screen.id) ?? null}
            onMap={() => go({ name: "detail", id: screen.id })}
            onClose={() => go({ name: "home" })}
          />
        )}
        {screen.name === "activities" && (
          <Activities
            activities={store.activities}
            onOpen={(id) => go({ name: "detail", id })}
            onClearDemos={() =>
              patch({ ...store, activities: store.activities.filter((a) => !a.demo) })
            }
          />
        )}
        {screen.name === "detail" && (
          <Detail
            activity={store.activities.find((a) => a.id === screen.id) ?? null}
            onBack={() => go({ name: "activities" })}
            onDelete={() => {
              patch({ ...store, activities: store.activities.filter((a) => a.id !== screen.id) });
              go({ name: "activities" });
            }}
          />
        )}
        {screen.name === "evolution" && <Evolution activities={store.activities} />}
        {screen.name === "perms" && (
          <Perms
            seen={store.permsSeen}
            store={store}
            onBack={() => go({ name: "home" })}
            onSeen={() => patch({ ...store, permsSeen: true })}
            onGps={() => setGpsMode("device")}
            onPatch={patch}
          />
        )}
      </div>
      {engine && screen.name !== "live" ? (
        <button
          type="button"
          className="mx-3 mb-2 card px-3 py-2 text-left"
          onClick={() => go({ name: "live", mode: engine.mode })}
        >
          <span className="block font-display text-lg leading-none">Corrida em andamento</span>
          <span className="mt-1 block text-sm text-ink/70">
            {hud?.status === "paused"
              ? hud.pauseReason === "auto"
                ? "Parado na rua · tempo pausado"
                : "Pausado na mão"
              : (hud?.phaseLabel ?? "Relógio seguindo")}
            {" · "}
            {formatClock(hud?.remainSec ?? hud?.elapsedSec ?? 0)}
          </span>
        </button>
      ) : null}
      {screen.name === "home" || screen.name === "activities" || screen.name === "evolution" ? (
        <nav className="grid grid-cols-3 gap-2 border-t-[3px] border-ink bg-paper px-3 py-2">
          <NavButton
            label="Pista"
            on={screen.name === "home"}
            onClick={() => go({ name: "home" })}
            icon={<Flag size={18} />}
          />
          <NavButton
            label="Atividades"
            on={screen.name === "activities"}
            onClick={() => go({ name: "activities" })}
            icon={<History size={18} />}
          />
          <NavButton
            label="Evolução"
            on={screen.name === "evolution"}
            onClick={() => go({ name: "evolution" })}
            icon={<TrendingUp size={18} />}
          />
        </nav>
      ) : null}
    </div>
  );
}

