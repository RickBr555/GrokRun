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
import { Stat } from "./live-screen";

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

export function Evolution({ activities }: { activities: Activity[] }) {
  const tiros = activities.filter((a) => a.mode === "tiros");
  const runs = activities.filter((a) => a.mode === "corrida");
  const paceData = [...tiros]
    .map((a) => {
      const paces = a.phases.filter((p) => p.kind === "sprint" && p.avgPaceSec).map((p) => p.avgPaceSec as number);
      if (!paces.length) return null;
      const avg = paces.reduce((s, n) => s + n, 0) / paces.length;
      return { t: a.startedAt, dia: new Date(a.startedAt).toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit" }), pace: avg, best: Math.min(...paces), delta: Math.max(...paces) - Math.min(...paces) };
    })
    .filter((x): x is NonNullable<typeof x> => x != null)
    .sort((a, b) => a.t - b.t);

  const bestEver = paceData.length ? Math.min(...paceData.map((p) => p.best)) : null;
  const lastDelta = paceData.length ? paceData[paceData.length - 1].delta : null;
  const zone =
    runs.length === 0
      ? null
      : runs.reduce((s, a) => s + (a.inZoneRatio ?? 0), 0) / runs.filter((a) => a.inZoneRatio != null).length;

  const weeks = weekBars(activities);

  return (
    <div className="flex flex-col gap-3">
      <h1 className="font-display text-3xl leading-none">Evolução</h1>
      <div className="grid grid-cols-2 gap-2">
        <Stat k="Melhor tiro" v={formatPace(bestEver)} />
        <Stat k="Consistência" v={lastDelta == null ? "—" : formatClock(lastDelta)} />
        <Stat k="Na faixa" v={formatPct(Number.isFinite(zone ?? NaN) ? zone : null)} />
        <Stat k="Semana" v={formatKm(weeks.at(-1)?.meters ?? 0)} />
      </div>
      <div>
        <p className="mb-1 font-display text-lg">Pace médio dos tiros</p>
        <p className="mb-1 text-sm text-ink/65">Quanto menor, mais rápido.</p>
        <PaceChart data={paceData.map((p) => ({ dia: p.dia, pace: p.pace }))} />
      </div>
      <div>
        <p className="mb-1 font-display text-lg">Consistência (lento − rápido)</p>
        <BarMini
          data={paceData.map((p) => ({ dia: p.dia, valor: Math.round(p.delta) }))}
          color="#e6a317"
          unit="s"
        />
      </div>
      <div>
        <p className="mb-1 font-display text-lg">Distância semanal</p>
        <BarMini
          data={weeks.map((w) => ({
            dia: new Date(w.start).toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit" }),
            valor: Number((w.meters / 1000).toFixed(1)),
          }))}
          color="#e23d2b"
          unit="km"
        />
      </div>
      {runs.length ? (
        <div>
          <p className="mb-1 font-display text-lg">Tempo na faixa</p>
          <BarMini
            data={[...runs]
              .sort((a, b) => a.startedAt - b.startedAt)
              .map((a) => ({
                dia: new Date(a.startedAt).toLocaleDateString("pt-BR", { day: "2-digit", month: "2-digit" }),
                valor: Math.round((a.inZoneRatio ?? 0) * 100),
              }))}
            color="#2f6b3a"
            unit="%"
          />
        </div>
      ) : null}
    </div>
  );
}

export function weekBars(activities: Activity[]): { start: number; meters: number }[] {
  const now = new Date();
  const day = (now.getDay() + 6) % 7;
  const monday = new Date(now);
  monday.setDate(now.getDate() - day);
  monday.setHours(0, 0, 0, 0);
  const starts: number[] = [];
  for (let i = 7; i >= 0; i--) starts.push(monday.getTime() - i * 7 * 86400000);
  return starts.map((start) => ({
    start,
    meters: activities
      .filter((a) => a.startedAt >= start && a.startedAt < start + 7 * 86400000)
      .reduce((s, a) => s + a.distanceM, 0),
  }));
}

export function Perms({
  seen,
  store,
  onBack,
  onSeen,
  onGps,
  onPatch,
}: {
  seen: boolean;
  store: StoreData;
  onBack: () => void;
  onSeen: () => void;
  onGps: () => void;
  onPatch: (next: StoreData) => void;
}) {
  const [gps, setGps] = useState("Ainda não");
  const [note, setNote] = useState(typeof Notification !== "undefined" ? Notification.permission : "sem suporte");
  const [name, setName] = useState(store.runnerName);
  const whistles = store.whistles ?? DEFAULT_WHISTLES;
  const setWhistle = (key: keyof WhistleMap, id: WhistleId) =>
    onPatch({ ...store, whistles: { ...whistles, [key]: id } });
  return (
    <div className="flex flex-col gap-3">
      <BackBar title="Ajustes" onBack={onBack} />
      <div className="card p-3">
        <p className="font-display text-xl">Seu bot</p>
        <p className="text-sm text-ink/70">O nome e a forma ficam salvos. Não precisa escolher de novo a cada treino.</p>
        <input
          className="mt-2 w-full rounded-full border-[3px] border-ink bg-cream px-4 py-2"
          aria-label="Seu nome"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onBlur={() => onPatch({ ...store, runnerName: name.trim() || "Grok", profileReady: true })}
        />
        <div className="mt-2 grid grid-cols-3 gap-2">
          {MASCOTS.map((m) => (
            <button
              key={m.id}
              type="button"
              onClick={() => onPatch({ ...store, mascot: m.id, profileReady: true, runnerName: name.trim() || store.runnerName })}
              className={`rounded-2xl border-[3px] p-1 ${store.mascot === m.id ? "border-ink" : "border-transparent"}`}
            >
              <img src={mascotSrc(m.id, "idle")} alt="" className="mx-auto h-16 w-full object-contain" />
              <p className="text-center font-display text-sm">{m.name}</p>
            </button>
          ))}
        </div>
      </div>
      <div className="card p-3">
        <p className="font-display text-xl">Fundo</p>
        <div className="mt-2 grid grid-cols-4 gap-2">
          {THEMES.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => onPatch({ ...store, theme: t.id })}
              className={`flex flex-col items-center gap-1 rounded-2xl border-[3px] p-2 ${store.theme === t.id ? "border-ink bg-gold" : "border-ink/30 bg-cream"}`}
            >
              <span className={`swatch swatch-${t.id}`} />
              <span className="font-display text-sm">{t.label}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="card p-3">
        <p className="font-display text-xl">Noite</p>
        <p className="text-sm text-ink/70">
          De noite, com a tela do celular escurecida, entra um bot só: para no meio, treme as pernas,
          olha pros lados e foge. Um fantasminha corre atrás. O fundo dessa cena é o azul.
        </p>
        <div className="mt-2 grid grid-cols-3 gap-2">
          {(
            [
              ["auto", "Automático"],
              ["on", "Sempre"],
              ["off", "Nunca"],
            ] as const
          ).map(([id, label]) => (
            <button
              key={id}
              type="button"
              className={`chip ${store.nightMode === id ? "chip-on" : ""}`}
              onClick={() => onPatch({ ...store, nightMode: id })}
            >
              {label}
            </button>
          ))}
        </div>
        {store.nightMode !== "off" ? <div className="mt-3"><NightChase id={store.mascot} /></div> : null}
      </div>
      <div className="card p-3">
        <p className="font-display text-xl">Pausa na rua</p>
        <p className="text-sm text-ink/70">
          Se você parar, o tempo pausa. O botão Pausar continua aí — diferente do Strava, a pausa na mão
          não solta sozinha.
        </p>
        <button
          type="button"
          className={`chip mt-2 ${store.autoPause !== false ? "chip-on" : ""}`}
          onClick={() => onPatch({ ...store, autoPause: store.autoPause === false })}
        >
          {store.autoPause === false ? "Pausa automática desligada" : "Pausa automática ligada"}
        </button>
      </div>
      <div className="card p-3">
        <p className="font-display text-xl">Apitos</p>
        <p className="text-sm text-ink/70">Escolhe o apito de cada momento. Toque em Ouvir antes de salvar o treino.</p>
        {WHISTLE_MOMENTS.map((moment) => (
          <div key={moment.key} className="mt-3 border-t border-ink/10 pt-2">
            <div className="flex items-center justify-between gap-2">
              <p className="font-display leading-tight">{moment.label}</p>
              <button
                type="button"
                className="btn btn-cream px-3 py-1 text-sm"
                onClick={() => playWhistle(whistles[moment.key])}
              >
                Ouvir
              </button>
            </div>
            {moment.hint ? <p className="text-sm text-ink/60">{moment.hint}</p> : null}
            <div className="mt-2 flex gap-2 overflow-x-auto pb-1">
              {WHISTLE_CHOICES.map((w) => (
                <button
                  key={w.id}
                  type="button"
                  className={`chip shrink-0 ${whistles[moment.key] === w.id ? "chip-on" : ""}`}
                  onClick={() => setWhistle(moment.key, w.id)}
                >
                  {w.label}
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
      <p className="text-ink/80">
        Se você sair da tela, o relógio não pausa. Ao voltar, a fase certa já está na frente.
      </p>
      <div className="card p-3">
        <p className="font-display text-xl">Localização precisa</p>
        <p className="text-sm text-ink/70">
          Desenha o percurso e calcula o pace. Pontos com erro acima de 20 m, ou saltos acima
          de 25 km/h, são ignorados.
        </p>
        <button
          type="button"
          className="btn btn-gold mt-2 px-4 py-2"
          onClick={() => {
            onGps();
            if (!navigator.geolocation) {
              setGps("Sem GPS neste aparelho");
              return;
            }
            navigator.geolocation.getCurrentPosition(
              () => setGps("Permitida"),
              () => setGps("Negada — dá para usar a pista simulada"),
              { enableHighAccuracy: true, timeout: 8000 },
            );
          }}
        >
          Permitir localização
        </button>
        <p className="mt-1 text-sm">{gps}</p>
      </div>
      <div className="card p-3">
        <p className="font-display text-xl">Notificações</p>
        <p className="text-sm text-ink/70">Fase e tempo quando você sai da tela.</p>
        <button
          type="button"
          className="btn btn-cream mt-2 px-4 py-2"
          onClick={() => {
            if (typeof Notification === "undefined") return;
            void Notification.requestPermission().then((p) => setNote(p));
          }}
        >
          Permitir notificações
        </button>
        <p className="mt-1 text-sm">{note === "granted" ? "Permitidas" : note === "denied" ? "Negadas" : "Ainda não"}</p>
      </div>
      <div className="card p-3">
        <p className="font-display text-xl">Tela e bateria</p>
        <p className="text-sm text-ink/70">
          Durante o treino a tela tenta ficar acesa. No app Android, um serviço em primeiro
          plano com wake lock segue com a tela apagada, e o sistema pede para ignorar a
          otimização de bateria.
        </p>
      </div>
      <button
        type="button"
        className="btn btn-ink inline-flex items-center justify-center gap-2 py-2"
        onClick={() => playWhistle(whistles.sprint)}
      >
        <Volume2 size={18} /> Ouvir apitos
      </button>
      <button
        type="button"
        className="btn btn-cinder py-3"
        onClick={() => {
          onSeen();
          onBack();
        }}
      >
        {seen ? "Voltar à pista" : "Entendi"}
      </button>
      <a className="text-center text-sm underline" href="/GrokRun-android.zip">
        Baixar projeto Android
      </a>
    </div>
  );
}
