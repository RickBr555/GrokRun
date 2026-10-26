import { useEffect, useRef, useState, type ReactNode } from "react";
import { Settings } from "lucide-react";
import { buildIntervalPhases, schedulePhases, totalSeconds } from "@/lib/grokrun/engine";
import { formatClock } from "@/lib/grokrun/format";
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
import { BackBar, MASCOTS, Meadow, RunCycle, mascotName, mascotSrc } from "./bits";
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

const SCENE = [
  { src: "/scene/bg_sky.webp", top: "0%", height: "100%", speed: 14 },
  { src: "/scene/bg_town.webp", top: "25%", height: "44%", speed: 40 },
  { src: "/scene/bg_trees.webp", top: "40%", height: "33%", speed: 100 },
  { src: "/scene/bg_track.webp", top: "66%", height: "34%", speed: 240 },
];

function IvotiScene({ id }: { id: MascotId }) {
  const [sec, setSec] = useState(0);
  const widths = useRef<Record<string, number>>({});
  const [, bump] = useState(0);
  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) return;
    let raf = 0;
    const t0 = performance.now();
    const loop = (t: number) => {
      setSec((t - t0) / 1000);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, []);
  return (
    <div className="absolute inset-0 overflow-hidden bg-[#8ec6ea]">
      {SCENE.map((layer) => {
        const w = widths.current[layer.src] || 0;
        const x = w ? -((sec * layer.speed) % w) : 0;
        return (
          <div
            key={layer.src}
            className="pointer-events-none absolute inset-x-0 overflow-hidden"
            style={{ top: layer.top, height: layer.height }}
          >
            <div className="flex h-full" style={{ transform: `translate3d(${x}px,0,0)` }}>
              <img
                src={layer.src}
                alt=""
                className="h-full w-auto max-w-none"
                onLoad={(e) => {
                  widths.current[layer.src] = e.currentTarget.clientWidth;
                  bump((n) => n + 1);
                }}
              />
              <img src={layer.src} alt="" className="h-full w-auto max-w-none" />
            </div>
          </div>
        );
      })}
      <div className="runner-slot pointer-events-none absolute bottom-[8%] left-1/2 h-[44%] w-[48%] -translate-x-1/2">
        <RunCycle id={id} className="h-full w-full" />
      </div>
    </div>
  );
}

export function Home({
  mascot,
  name,
  profileReady,
  running,
  elapsedSec,
  paused,
  onSaveProfile,
  onStart,
  onPlay,
  onSettings,
}: {
  mascot: MascotId;
  name: string;
  profileReady: boolean;
  running: boolean;
  elapsedSec: number;
  paused: boolean;
  onSaveProfile: (name: string, id: MascotId) => void;
  onStart: () => void;
  onPlay: () => void;
  onSettings: () => void;
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
    <div className="flex h-full min-h-0 flex-col">
      <div className="relative min-h-0 flex-1">
        <IvotiScene id={mascot} />
        <div className="absolute inset-x-0 top-0 flex h-16 items-center px-4">
          <button
            type="button"
            aria-label="Ajustes"
            className="grid size-11 place-items-center rounded-full border-[3px] border-ink bg-cream shadow-[3px_3px_0_#1c140c]"
            onClick={onSettings}
          >
            <Settings size={22} />
          </button>
          <p className="sky-title pointer-events-none absolute inset-x-0 text-center font-display text-4xl tracking-wide">
            GROK RUN
          </p>
        </div>
        {running ? (
          <button type="button" className="run-pill" onClick={onStart}>
            <span className={`run-dot ${paused ? "" : "run-dot-live"}`} />
            <span className="min-w-0 flex-1 truncate text-left">
              {paused ? "Corrida pausada" : "Corrida em andamento"} • {formatClock(elapsedSec)}
            </span>
            <span className="run-pill-go">Voltar ›</span>
          </button>
        ) : null}
      </div>
      <div className="shrink-0 border-t-[3px] border-ink bg-ink px-7 py-3.5 text-paper">
        <p className="text-center font-display text-xl">
          {name || "Grok"} · {mascotName(mascot)}
        </p>
        <button type="button" className="btn btn-cinder mt-3 w-full py-3 text-2xl" onClick={onStart}>
          {running ? "VOLTAR À CORRIDA" : "COMEÇAR"}
        </button>
        <button type="button" className="btn btn-cream mt-3 w-full py-3 text-2xl" onClick={onPlay}>
          Jogo de rua
        </button>
      </div>
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
    <Meadow>
      <BackBar title="Modo" onBack={onBack} />
      <button type="button" className="card overflow-hidden p-0 text-left" onClick={() => onPick("corrida")}>
        <div className="lane-art">
          <div className="lane-art-sky" />
          <div className="lane-art-grass lane-art-grass-back" />
          <div className="lane-art-track" />
          <div className="lane-art-grass lane-art-grass-front" />
          <div className="lane-art-cast">
            {MASCOTS.map((m, i) => (
              <RunCycle
                key={m.id}
                id={m.id}
                small
                hz={1.3 * (420 / (360 + i * 40))}
                className={`h-14 w-14 ${i % 2 ? "-translate-y-2" : ""}`}
              />
            ))}
          </div>
        </div>
        <div className="p-3">
          <p className="font-display text-2xl leading-none">Corrida</p>
          <p className="text-sm text-ink/70">O grupo atravessa junto, no ritmo da faixa.</p>
        </div>
      </button>
      <button type="button" className="card overflow-hidden p-0 text-left" onClick={() => onPick("tiros")}>
        <div className="lane-art">
          <div className="lane-art-sky" />
          <div className="lane-art-grass lane-art-grass-back" />
          <div className="lane-art-track" />
          <div className="lane-art-grass lane-art-grass-front" />
          <div className="lane-art-cast lane-art-cast-one">
            <div className="lane-dash-one">
              <RunCycle id={mascot} small hz={1.3 * (420 / 160)} className="h-[5.2rem] w-[5.2rem]" />
            </div>
          </div>
        </div>
        <div className="p-3">
          <p className="font-display text-2xl leading-none">Tiros</p>
          <p className="text-sm text-ink/70">Um só, bem mais rápido, de um lado ao outro.</p>
        </div>
      </button>
    </Meadow>
  );
}

