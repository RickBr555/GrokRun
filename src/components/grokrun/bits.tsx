import { useEffect, useRef, type ReactNode } from "react";
import type { MascotId, PhaseKind } from "@/lib/grokrun/types";
import { formatClock } from "@/lib/grokrun/format";

export const MASCOTS: { id: MascotId; name: string; tag: string }[] = [
  { id: "bola", name: "Bola", tag: "" },
  { id: "triangulo", name: "Triângulo", tag: "" },
  { id: "hexagono", name: "Hexágono", tag: "" },
  { id: "fantasma", name: "Fantasma", tag: "" },
  { id: "oblongo", name: "Boné", tag: "" },
  { id: "quadrado", name: "Quadrado", tag: "" },
];

export function mascotSrc(id: MascotId, pose: "idle" | "run" | "rest" | "scared" = "idle"): string {
  const frame = pose === "scared" ? "idle" : pose;
  return `/scene/mascot_${id}_${frame}.png`;
}

export function mascotVideo(id: MascotId): string {
  return `/mascots/${id}-run.mp4`;
}

export function mascotName(id: MascotId): string {
  return MASCOTS.find((m) => m.id === id)?.name ?? "Bola";
}

/** Ciclo de 16 quadros (folha 4×4), 1,3 voltas por segundo. O fantasma já flutua na folha. */
export function RunCycle({
  id,
  className = "",
  hz = 1.3,
  small = false,
}: {
  id: MascotId;
  className?: string;
  hz?: number;
  small?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const el = ref.current;
    if (!el) return;
    const place = (frame: number) => {
      const col = frame % 4;
      const row = Math.floor(frame / 4);
      el.style.backgroundPosition = `${col * (100 / 3)}% ${row * (100 / 3)}%`;
    };
    if (reduce) {
      place(0);
      return;
    }
    let raf = 0;
    const t0 = performance.now();
    const loop = (t: number) => {
      place(Math.floor(((t - t0) / 1000) * hz * 16) % 16);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [hz]);
  return (
    <div
      ref={ref}
      aria-hidden="true"
      className={`run-cycle ${className}`}
      style={{ backgroundImage: `url(/scene/runsheet_${id}${small ? "_s" : ""}.webp)` }}
    />
  );
}

export function Meadow({
  critters,
  children,
}: {
  critters?: { id: MascotId; pose: "idle" | "rest" }[];
  children: ReactNode;
}) {
  return (
    <div className="meadow relative -mx-4 min-h-full px-4 pb-28">
      <div className="meadow-sky pointer-events-none absolute inset-0" />
      <div className="meadow-grass pointer-events-none absolute inset-x-0 bottom-0 h-24" />
      {critters?.length ? (
        <div className="pointer-events-none absolute inset-x-1 bottom-8 flex items-end justify-between">
          {critters.map((c, i) => (
            <img
              key={`${c.id}-${i}`}
              src={mascotSrc(c.id, c.pose)}
              alt=""
              className="h-14 w-auto object-contain"
            />
          ))}
        </div>
      ) : null}
      <div className="relative flex flex-col gap-3">{children}</div>
    </div>
  );
}

export function RunClip({
  id,
  rate = 1,
  className = "",
}: {
  id: MascotId;
  rate?: number;
  className?: string;
}) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.playbackRate = rate;
  }, [rate]);
  return (
    <video
      ref={ref}
      src={mascotVideo(id)}
      poster={mascotSrc(id, "idle")}
      autoPlay
      loop
      muted
      playsInline
      className={`object-contain ${className || "h-full w-full"}`}
    />
  );
}

export function MascotCel({
  id,
  pose,
  className = "",
  frameClass = "h-44 w-32",
}: {
  id: MascotId;
  pose: "idle" | "run" | "rest" | "scared";
  className?: string;
  frameClass?: string;
}) {
  return (
    <div className={`standee ${frameClass}`}>
      <img src={mascotSrc(id, pose)} alt="" className={`h-full w-full object-contain ${className}`} />
    </div>
  );
}

export function TrackStage({ id }: { id: MascotId }) {
  return (
    <div className="relative mx-auto h-72 w-full overflow-hidden">
      <div className="absolute inset-x-3 top-3 h-44 rounded-[50%] border-[14px] border-cinder bg-cinder/30" />
      <div className="absolute inset-x-8 top-7 h-36 rounded-[50%] border-[3px] border-dashed border-cream/80" />
      <div className="absolute inset-x-14 top-12 h-24 rounded-[50%] border-[3px] border-ink bg-grass" />
      <div className="absolute inset-x-0 bottom-0 flex justify-center">
        <MascotCel id={id} pose="run" frameClass="h-52 w-40" />
      </div>
    </div>
  );
}

export function NightChase({ id }: { id: MascotId }) {
  return (
    <div className="night-stage" aria-hidden="true">
      <div className="night-actor">
        <div className="night-sticker">
          <img className="night-clip night-clip-run" src={mascotSrc(id, "run")} alt="" />
          <img className="night-clip night-clip-scared" src={mascotSrc(id, "scared")} alt="" />
        </div>
      </div>
      <div className="night-ghost-wrap">
        <img className="night-ghost" src={mascotSrc("fantasma", "run")} alt="" />
      </div>
    </div>
  );
}

export function LaneCross({ ids, fast = false }: { ids: MascotId[]; fast?: boolean }) {
  return (
    <div className={`lane ${fast ? "lane-fast" : "lane-pack"}`} aria-hidden="true">
      <div className="lane-sky" />
      <div className="lane-grass" />
      <div className="lane-track">
        <div className="lane-lines" />
        <div className="lane-lines lane-lines-2" />
      </div>
      {fast ? <div className="lane-wind" /> : null}
      <div className="lane-mover">
        {ids.map((id) => (
          <img key={id} src={mascotSrc(id, "run")} alt="" className="lane-clip" />
        ))}
      </div>
    </div>
  );
}

export function Stepper({
  label,
  hint,
  value,
  min,
  max,
  step,
  onChange,
  display,
}: {
  label: string;
  hint?: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (n: number) => void;
  display: string;
}) {
  return (
    <div className="flex items-center justify-between gap-3 py-2.5">
      <div className="min-w-0">
        <p className="font-display text-lg leading-tight">{label}</p>
        {hint ? <p className="text-sm text-ink/65">{hint}</p> : null}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <button
          type="button"
          className="step-btn"
          aria-label={`Diminuir ${label}`}
          onClick={() => onChange(Math.max(min, value - step))}
        >
          –
        </button>
        <span className="w-16 text-center font-display text-xl tabular-nums">{display}</span>
        <button
          type="button"
          className="step-btn"
          aria-label={`Aumentar ${label}`}
          onClick={() => onChange(Math.min(max, value + step))}
        >
          +
        </button>
      </div>
    </div>
  );
}

export function TimeStepper(props: {
  label: string;
  hint?: string;
  value: number;
  onChange: (n: number) => void;
  min?: number;
  max?: number;
  step?: number;
}) {
  return (
    <Stepper
      label={props.label}
      hint={props.hint}
      value={props.value}
      min={props.min ?? 0}
      max={props.max ?? 60 * 40}
      step={props.step ?? 5}
      onChange={props.onChange}
      display={formatClock(props.value)}
    />
  );
}

const PHASE_BG: Record<PhaseKind, string> = {
  warmup: "bg-gold",
  sprint: "bg-cinder",
  rest: "bg-ink",
  cycleRest: "bg-grass",
  cooldown: "bg-gold",
  run: "bg-cinder",
};

export function phaseClass(kind: PhaseKind): string {
  return PHASE_BG[kind];
}

export function PhaseBar({
  parts,
}: {
  parts: { kind: PhaseKind; durationSec: number; label: string }[];
}) {
  const total = parts.reduce((s, p) => s + p.durationSec, 0) || 1;
  return (
    <div className="flex h-4 overflow-hidden rounded-full border-[3px] border-ink">
      {parts.map((p, i) => (
        <div
          key={`${p.label}-${i}`}
          title={p.label}
          className={phaseClass(p.kind)}
          style={{ width: `${(p.durationSec / total) * 100}%` }}
        />
      ))}
    </div>
  );
}

export function BackBar({ title, onBack }: { title: string; onBack: () => void }) {
  return (
    <div className="mb-3 flex items-center gap-3">
      <button type="button" className="btn btn-cream px-4 py-2 text-base" onClick={onBack}>
        Voltar
      </button>
      <h1 className="font-display text-2xl leading-none">{title}</h1>
    </div>
  );
}
