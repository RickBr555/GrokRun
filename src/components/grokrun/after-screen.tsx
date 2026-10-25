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
import { Stat, sprintRows } from "./live-screen";

export function Summary({
  activity,
  onMap,
  onClose,
}: {
  activity: Activity | null;
  onMap: () => void;
  onClose: () => void;
}) {
  if (!activity) {
    return (
      <div>
        <p>Treino não encontrado.</p>
        <button type="button" className="btn btn-cream mt-3 px-4 py-2" onClick={onClose}>
          Fechar
        </button>
      </div>
    );
  }
  const sprints = sprintRows(activity.phases);
  const paces = sprints.map((s) => s.avgPaceSec).filter((n): n is number => n != null);
  const best = paces.length ? Math.min(...paces) : null;
  const worst = paces.length ? Math.max(...paces) : null;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <MascotCel id={activity.mascot} pose="rest" frameClass="h-24 w-24" />
        <div>
          <h1 className="font-display text-3xl leading-none">Treino salvo</h1>
          <p className="text-sm text-ink/70">
            {activity.mode === "tiros" ? "Tiros" : "Corrida"} · {formatWhen(activity.startedAt)}
          </p>
        </div>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <Stat k="Tempo" v={formatClock(activity.durationSec)} />
        <Stat k="Distância" v={formatKm(activity.distanceM)} />
        <Stat k="Elevação" v={`+${Math.round(activity.elevationGain)} m`} />
      </div>
      <p className="text-sm text-ink/65">
        {activity.elevationSource === "dem"
          ? "Altitude corrigida pelo Open-Meteo."
          : activity.elevationSource === "baro"
            ? "Altitude do barômetro, ancorada no GPS."
            : "Altitude estimada (GPS suavizado)."}
        {activity.gpsMode === "sim" ? " Percurso de demonstração." : ""}
      </p>
      {sprints.length ? (
        <div className="card overflow-x-auto p-2">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="font-display">
                <th className="px-1 py-1">Tiro</th>
                <th className="px-1">Tempo</th>
                <th className="px-1">Dist.</th>
                <th className="px-1">Pace</th>
                <th className="px-1">km/h</th>
              </tr>
            </thead>
            <tbody>
              {sprints.map((s, i) => (
                <tr key={i} className="border-t border-ink/15">
                  <td className="px-1 py-1">
                    {s.cycle ? `${s.cycle}.${s.sprint}` : s.sprint}
                    {s.avgPaceSec != null && s.avgPaceSec === best ? " · melhor" : ""}
                    {s.avgPaceSec != null && worst != null && best !== worst && s.avgPaceSec === worst ? " · lento" : ""}
                  </td>
                  <td className="px-1 tabular-nums">{formatClock(s.durationSec)}</td>
                  <td className="px-1 tabular-nums">{formatKm(s.distanceM)}</td>
                  <td className="px-1 tabular-nums">{formatPace(s.avgPaceSec)}</td>
                  <td className="px-1 tabular-nums">{formatSpeed(s.avgSpeedKmh)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <PhaseList phases={activity.phases} />
      <div className="grid grid-cols-2 gap-2">
        <button type="button" className="btn btn-gold py-3" onClick={onMap}>
          Ver percurso
        </button>
        <button
          type="button"
          className="btn btn-cream py-3"
          onClick={() => downloadText(`grokrun-${activity.id}.gpx`, activityToGpx(activity), "application/gpx+xml")}
        >
          Exportar GPX
        </button>
      </div>
      <button type="button" className="btn btn-ink py-3" onClick={onClose}>
        Fechar
      </button>
    </div>
  );
}

export function PhaseList({ phases }: { phases: PhaseRecord[] }) {
  if (!phases.length) return null;
  return (
    <div className="card p-3">
      <p className="font-display text-lg">Por fase</p>
      <ul className="mt-1 flex flex-col gap-1 text-sm">
        {phases.map((p, i) => (
          <li key={i} className="flex justify-between gap-2 border-t border-ink/10 py-1">
            <span>{p.label}</span>
            <span className="tabular-nums text-ink/75">
              {formatClock(p.durationSec)} · {formatPace(p.avgPaceSec)} · {formatSpeed(p.avgSpeedKmh)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function Activities({
  activities,
  onOpen,
  onClearDemos,
}: {
  activities: Activity[];
  onOpen: (id: string) => void;
  onClearDemos: () => void;
}) {
  const hasDemo = activities.some((a) => a.demo);
  return (
    <div className="flex flex-col gap-3">
      <h1 className="font-display text-3xl leading-none">Atividades</h1>
      {activities.length === 0 ? (
        <p className="text-ink/70">Nenhum treino ainda. A pista está esperando.</p>
      ) : null}
      {activities.map((a) => (
        <button key={a.id} type="button" className="card flex items-center gap-3 p-2 text-left" onClick={() => onOpen(a.id)}>
          <img src={mascotSrc(a.mascot, "rest")} alt="" className="h-16 w-16 rounded-2xl border-[3px] border-ink object-cover" />
          <span className="min-w-0 flex-1">
            <span className="block font-display text-xl leading-none">
              {a.mode === "tiros" ? "Tiros" : "Corrida"}
              {a.demo ? " · exemplo" : ""}
            </span>
            <span className="block text-sm text-ink/70">
              {formatWhen(a.startedAt)} · {formatKm(a.distanceM)} · {formatClock(a.durationSec)}
            </span>
          </span>
        </button>
      ))}
      {hasDemo ? (
        <button type="button" className="btn btn-cream py-2" onClick={onClearDemos}>
          Apagar exemplos
        </button>
      ) : null}
    </div>
  );
}

export function Detail({
  activity,
  onBack,
  onDelete,
}: {
  activity: Activity | null;
  onBack: () => void;
  onDelete: () => void;
}) {
  const [ask, setAsk] = useState(false);
  const elev = useMemo(() => {
    if (!activity) return [];
    const pts = activity.points.filter((p) => p.alt != null);
    const step = Math.max(1, Math.floor(pts.length / 80));
    return pts.filter((_, i) => i % step === 0).map((p) => ({ km: Number((p.distM / 1000).toFixed(3)), alt: p.alt as number }));
  }, [activity]);
  if (!activity) {
    return (
      <div>
        <BackBar title="Atividade" onBack={onBack} />
        <p>Não encontrada.</p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-3">
      <BackBar title={activity.mode === "tiros" ? "Tiros" : "Corrida"} onBack={onBack} />
      <p className="text-sm text-ink/70">
        {formatWhen(activity.startedAt)} · {mascotName(activity.mascot)} · {formatKm(activity.distanceM)}
      </p>
      <MapView points={activity.points} />
      <div className="flex flex-wrap gap-2 text-xs">
        <Legend swatch="bg-gold" label="Aquecimento" />
        <Legend swatch="bg-cinder" label="Tiro" />
        <Legend swatch="bg-ink" label="Descanso" />
        <Legend swatch="bg-grass" label="Entre ciclos" />
      </div>
      <div>
        <p className="mb-1 font-display text-lg">Elevação × distância</p>
        <ElevationChart data={elev} />
        <p className="mt-1 text-sm text-ink/70">
          +{formatMeters(activity.elevationGain)} / −{formatMeters(activity.elevationLoss)}
          {activity.elevationEstimated ? " · estimada" : activity.elevationSource === "dem" ? " · Open-Meteo" : " · barômetro"}
        </p>
      </div>
      {activity.splits.length ? (
        <div className="card p-3">
          <p className="font-display">Parciais</p>
          <ul className="text-sm">
            {activity.splits.map((s) => (
              <li key={s.km} className="flex justify-between border-t border-ink/10 py-1">
                <span>km {s.km}</span>
                <span className="tabular-nums">
                  {formatClock(s.splitSec)} · {formatPace(s.paceSec)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <PhaseList phases={activity.phases} />
      <button
        type="button"
        className="btn btn-gold py-3"
        onClick={() => downloadText(`grokrun-${activity.id}.gpx`, activityToGpx(activity), "application/gpx+xml")}
      >
        Exportar GPX
      </button>
      {ask ? (
        <div className="flex gap-2">
          <button type="button" className="btn btn-cinder flex-1 py-2" onClick={onDelete}>
            Apagar
          </button>
          <button type="button" className="btn btn-cream px-4 py-2" onClick={() => setAsk(false)}>
            Cancelar
          </button>
        </div>
      ) : (
        <button type="button" className="btn btn-cream py-2" onClick={() => setAsk(true)}>
          Apagar atividade
        </button>
      )}
    </div>
  );
}

export function Legend({ swatch, label }: { swatch: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1">
      <span className={`inline-block h-3 w-3 border-2 border-ink ${swatch}`} />
      {label}
    </span>
  );
}

