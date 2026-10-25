import { useEffect, useRef, useState } from "react";
import { totalSeconds, buildIntervalPhases } from "@/lib/grokrun/engine";
import { TrackFilter } from "@/lib/grokrun/geo";
import { downsample, refineElevation } from "@/lib/grokrun/metrics";
import {
  activeIndex,
  advance,
  createSession,
  elapsedMs,
  finalizeRecords,
  ingestFix,
  noteMotion,
  notePaceSample,
  notePressure,
  pauseSession,
  resumeSession,
  simStep,
  type CueEvent,
  type Session,
  type SessionDraft,
} from "@/lib/grokrun/session";
import { saveLive } from "@/lib/grokrun/storage";
import type { Activity, PhaseKind, WhistleMap } from "@/lib/grokrun/types";
import {
  beepCountdown,
  cueTooFast,
  cueTooSlow,
  notifyPhase,
  playWhistle,
  requestWakeLock,
} from "@/lib/grokrun/audio";

export interface Hud {
  status: Session["status"];
  prepareCount: number | null;
  phaseLabel: string;
  phaseKind: PhaseKind | null;
  remainSec: number | null;
  elapsedSec: number;
  totalSec: number | null;
  sprintText: string | null;
  cycleText: string | null;
  nextLabel: string | null;
  paceSec: number | null;
  speedKmh: number | null;
  distanceM: number;
  gps: "device" | "sim" | "buscando";
  alert: "fast" | "slow" | null;
  pauseReason: Session["pauseReason"];
  inZoneRatio: number | null;
}

function readHud(s: Session, now: number): Hud {
  const elapsed = elapsedMs(s, now);
  const idx = activeIndex(s, now);
  const phase = idx >= 0 && idx < s.phases.length ? s.phases[idx] : null;
  const totalSec = s.mode === "tiros" && s.interval ? totalSeconds(buildIntervalPhases(s.interval)) : null;
  let prepareCount: number | null = null;
  if (s.status === "prepare") {
    const left = s.prepareEnds - now;
    prepareCount = left > 2000 ? 3 : left > 1000 ? 2 : 1;
  }
  const remainSec =
    phase && phase.durationSec > 0 ? Math.max(0, (phase.endMs - elapsed) / 1000) : null;
  const next = phase && idx + 1 < s.phases.length ? s.phases[idx + 1].label : null;
  const showCount =
    phase && (phase.kind === "sprint" || phase.kind === "rest" || phase.kind === "cycleRest")
      ? phase.sprint
      : null;
  return {
    status: s.status,
    prepareCount,
    phaseLabel: s.status === "prepare" ? "Preparar" : (phase?.label ?? "Fim"),
    phaseKind: phase?.kind ?? null,
    remainSec,
    elapsedSec: elapsed / 1000,
    totalSec,
    sprintText:
      showCount && s.interval ? `${showCount}/${s.interval.sprintsPerCycle}` : null,
    cycleText: phase?.cycle && s.interval ? `${phase.cycle}/${s.interval.cycles}` : null,
    nextLabel: s.status === "prepare" ? (s.phases[0]?.label ?? null) : next,
    paceSec: s.lastPace,
    speedKmh: s.lastSpeed,
    distanceM: s.distM,
    gps: s.gpsMode === "sim" ? "sim" : s.points.length ? "device" : "buscando",
    alert: s.alert,
    pauseReason: s.pauseReason,
    inZoneRatio: s.paceSampleMs > 0 ? s.inZoneMs / s.paceSampleMs : null,
  };
}

function play(ev: CueEvent, whistles: WhistleMap): void {
  if (ev.type === "countdown") beepCountdown();
  else if (ev.type === "done") playWhistle(whistles.done);
  else if (ev.type === "fast") cueTooFast();
  else if (ev.type === "slow") cueTooSlow();
  else if (ev.kind === "sprint") playWhistle(whistles.sprint);
  else if (ev.kind === "rest") playWhistle(whistles.rest);
  else if (ev.kind === "cycleRest" || ev.kind === "cooldown") playWhistle(whistles.finalRest);
  else playWhistle(whistles.start);
}

function labelOf(ev: CueEvent): string {
  if (ev.type === "done") return "Treino concluído";
  if (ev.type === "fast") return "Rápido demais";
  if (ev.type === "slow") return "Lento demais";
  if (ev.kind === "sprint") return "Tiro";
  if (ev.kind === "rest" || ev.kind === "cycleRest") return "Descanso";
  if (ev.kind === "cooldown") return "Desaquecimento";
  if (ev.kind === "warmup") return "Aquecimento";
  return "Corrida";
}

export function useWorkout(opts: {
  draft: Omit<SessionDraft, "now">;
  initial: Session | null;
  whistles: WhistleMap;
  autoPause: boolean;
  onHud?: (hud: Hud) => void;
  onFinished: (activity: Activity) => void;
  onAbort: () => void;
}) {
  const draftRef = useRef(opts.draft);
  const initialRef = useRef(opts.initial);
  const onFinishedRef = useRef(opts.onFinished);
  const onAbortRef = useRef(opts.onAbort);
  const onHudRef = useRef(opts.onHud);
  const whistlesRef = useRef(opts.whistles);
  const autoPauseRef = useRef(opts.autoPause);
  onFinishedRef.current = opts.onFinished;
  onAbortRef.current = opts.onAbort;
  onHudRef.current = opts.onHud;
  whistlesRef.current = opts.whistles;
  autoPauseRef.current = opts.autoPause;
  const sessionRef = useRef<Session | null>(null);
  const filterRef = useRef(new TrackFilter());
  const [hud, setHud] = useState<Hud | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const stopRef = useRef<((natural: boolean) => void) | null>(null);
  const abandonRef = useRef(false);

  useEffect(() => {
    const s =
      initialRef.current ??
      createSession({ ...draftRef.current, now: Date.now() });
    sessionRef.current = s;
    const filter = filterRef.current;
    let stopped = false;
    let watchId = 0;
    let wake: WakeLockSentinel | null = null;
    let pressure: { stop?: () => void } | null = null;
    const pressureAlt = { current: null as number | null };
    const done = { current: false };

    const finish = async (natural: boolean) => {
      if (done.current) return;
      done.current = true;
      stopped = true;
      const now = Date.now();
      if (s.status !== "done") {
        finalizeRecords(s, now);
        s.pauseStarted = now;
        s.status = "done";
      } else {
        finalizeRecords(s, now);
      }
      if (!natural) playWhistle(whistlesRef.current.done);
      setSaving(true);
      const painted = readHud(s, now);
      setHud(painted);
      onHudRef.current?.(painted);
      saveLive(null);
      const refined = await refineElevation(s.points);
      const elapsed = elapsedMs(s, now);
      const activity: Activity = {
        id: s.id,
        startedAt: s.startedAt,
        mode: s.mode,
        mascot: s.mascot,
        interval: s.interval,
        run: s.run,
        phases: s.records,
        splits: s.splits,
        points: downsample(refined.points),
        distanceM: s.distM,
        durationSec: elapsed / 1000,
        elevationGain: refined.gain,
        elevationLoss: refined.loss,
        elevationEstimated: refined.estimated,
        elevationSource: refined.source,
        inZoneRatio: s.paceSampleMs > 0 ? s.inZoneMs / s.paceSampleMs : null,
        gpsMode: s.gpsMode,
      };
      onFinishedRef.current(activity);
    };
    stopRef.current = (natural) => {
      void finish(natural);
    };

    const handle = (events: CueEvent[]) => {
      const important = events.filter((e) => e.type !== "countdown");
      const cds = events.filter((e) => e.type === "countdown");
      const last = important.at(-1);
      if (document.hidden && last) void notifyPhase("GrokRun", labelOf(last));
      if (cds.length && !last) beepCountdown();
      if (last) play(last, whistlesRef.current);
    };

    const tick = () => {
      if (stopped) return;
      const now = Date.now();
      handle(advance(s, now));
      if (s.gpsMode === "sim" && s.status === "running") {
        const fix = simStep(s, now);
        if (fix) ingestFix(s, filter, fix, now, Math.max(0, activeIndex(s, now)));
      }
      noteMotion(s, now, s.instKmh ?? s.lastSpeed, autoPauseRef.current);
      const pace = notePaceSample(s, now);
      if (pace) handle([pace]);
      if (s.status === "done") {
        void finish(true);
        return;
      }
      const painted = readHud(s, now);
      setHud(painted);
      onHudRef.current?.(painted);
      if (Math.floor(now / 1000) % 2 === 0) saveLive(s);
    };

    const timer = window.setInterval(tick, 250);
    tick();

    const startWatch = () => {
      if (!navigator.geolocation || s.gpsMode !== "device") return;
      watchId = navigator.geolocation.watchPosition(
        (pos) => {
          if (s.gpsMode !== "device") return;
          const now = Date.now();
          s.lastFixAt = now;
          const speed = pos.coords.speed;
          if (speed != null && Number.isFinite(speed) && speed >= 0) s.instKmh = speed * 3.6;
          if (s.status === "paused" && s.pauseReason === "auto") {
            noteMotion(s, now, s.instKmh, autoPauseRef.current);
          }
          if (s.status !== "running") return;
          const alt = pressureAlt.current ?? (Number.isFinite(pos.coords.altitude ?? NaN) ? pos.coords.altitude : null);
          ingestFix(
            s,
            filter,
            {
              lat: pos.coords.latitude,
              lon: pos.coords.longitude,
              t: pos.timestamp || now,
              acc: pos.coords.accuracy,
              alt,
            },
            now,
            Math.max(0, activeIndex(s, now)),
          );
        },
        () => {
          /* sem sinal: o usuário pode cair para a pista simulada */
        },
        { enableHighAccuracy: true, maximumAge: 1000, timeout: 10000 },
      );
    };
    startWatch();

    try {
      const Ctor = (
        window as unknown as {
          PressureSensor?: new (o?: { frequency?: number }) => {
            pressure: number;
            onreading: (() => void) | null;
            start: () => void;
            stop: () => void;
          };
        }
      ).PressureSensor;
      if (Ctor) {
        const sensor = new Ctor({ frequency: 1 });
        sensor.onreading = () => {
          pressureAlt.current = notePressure(s, sensor.pressure, null);
        };
        sensor.start();
        pressure = sensor;
      }
    } catch {
      /* sem barômetro */
    }

    void requestWakeLock().then((lock) => {
      wake = lock;
    });
    const onVis = () => {
      if (document.visibilityState === "visible") {
        void requestWakeLock().then((lock) => {
          wake = lock;
        });
        tick();
      }
    };
    document.addEventListener("visibilitychange", onVis);

    return () => {
      stopped = true;
      window.clearInterval(timer);
      if (watchId) navigator.geolocation.clearWatch(watchId);
      void wake?.release();
      pressure?.stop?.();
      document.removeEventListener("visibilitychange", onVis);
      if (!done.current && !abandonRef.current) saveLive(s);
    };
    // sessão única por montagem
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const paint = (s: Session, now = Date.now()) => {
    const next = readHud(s, now);
    setHud(next);
    onHudRef.current?.(next);
  };
  const pause = () => {
    const s = sessionRef.current;
    if (!s || s.status !== "running") return;
    pauseSession(s, Date.now(), "manual");
    paint(s);
    saveLive(s);
  };
  const resume = () => {
    const s = sessionRef.current;
    if (!s || s.status !== "paused") return;
    resumeSession(s, Date.now());
    paint(s);
    saveLive(s);
  };
  const holdPause = () => {
    const s = sessionRef.current;
    if (!s || s.status !== "paused") return;
    s.pauseReason = "manual";
    paint(s);
    saveLive(s);
  };
  const useSim = () => {
    const s = sessionRef.current;
    if (!s) return;
    s.gpsMode = "sim";
    s.lastFixAt = Date.now();
    paint(s);
  };

  return {
    hud,
    saving,
    confirming,
    askStop: () => setConfirming(true),
    cancelStop: () => setConfirming(false),
    confirmStop: () => {
      setConfirming(false);
      const s = sessionRef.current;
      if (!s || s.status === "prepare") {
        abandonRef.current = true;
        saveLive(null);
        onAbortRef.current();
        return;
      }
      stopRef.current?.(false);
    },
    pause,
    resume,
    holdPause,
    useSim,
    session: () => sessionRef.current,
  };
}
