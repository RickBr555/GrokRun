import { useEffect, useRef, useState } from "react";
import type { MascotId } from "@/lib/grokrun/types";
import { BackBar, mascotSrc } from "./bits";

const BEST_KEY = "grokrun-race-best";
const SLOTS = 3;

type Slot = { x: number; on: boolean };

/**
 * Endless runner: the bot stays put, the track and the hurdles move.
 * Jump is a velocity that gravity pulls back down. A hit ends the run.
 */
export function RaceGame({ id, onBack }: { id: MascotId; onBack: () => void }) {
  const stageRef = useRef<HTMLDivElement>(null);
  const runnerRef = useRef<HTMLDivElement>(null);
  const bobRef = useRef<HTMLImageElement>(null);
  const scoreRef = useRef<HTMLSpanElement>(null);
  const slotRefs = useRef<(HTMLDivElement | null)[]>([]);
  const [phase, setPhase] = useState<"ready" | "run" | "dead">("ready");
  const [finalScore, setFinalScore] = useState(0);
  const [best, setBest] = useState(0);

  useEffect(() => {
    const saved = Number(localStorage.getItem(BEST_KEY) || 0);
    if (Number.isFinite(saved)) setBest(saved);
  }, []);

  useEffect(() => {
    const stage = stageRef.current;
    const runner = runnerRef.current;
    const bob = bobRef.current;
    if (!stage || !runner || !bob) return;

    const sim = {
      on: false,
      y: 0,
      vy: 0,
      speed: 340,
      dist: 0,
      wait: 0.7,
      slots: Array.from({ length: SLOTS }, () => ({ x: 0, on: false }) as Slot),
      last: 0,
    };

    const paintScore = () => {
      if (scoreRef.current) scoreRef.current.textContent = `${Math.floor(sim.dist)} m`;
    };

    const place = () => {
      runner.style.transform = `translateY(${-sim.y}px)`;
      bob.classList.toggle("race-bob", sim.y < 2);
      sim.slots.forEach((slot, i) => {
        const el = slotRefs.current[i];
        if (!el) return;
        el.style.transform = `translateX(${slot.x}px)`;
        el.style.opacity = slot.on ? "1" : "0";
      });
    };

    const jump = () => {
      if (!sim.on || sim.y > 2) return;
      sim.vy = 860;
    };

    const start = () => {
      if (sim.on) {
        jump();
        return;
      }
      sim.on = true;
      sim.y = 0;
      sim.vy = 860;
      sim.speed = 340;
      sim.dist = 0;
      sim.wait = 0.85;
      sim.slots.forEach((s) => {
        s.on = false;
        s.x = 0;
      });
      sim.last = performance.now();
      setPhase("run");
      paintScore();
    };

    const finish = () => {
      if (!sim.on) return;
      sim.on = false;
      const score = Math.floor(sim.dist);
      const prev = Number(localStorage.getItem(BEST_KEY) || 0);
      const next = Math.max(prev, score);
      localStorage.setItem(BEST_KEY, String(next));
      setBest(next);
      setFinalScore(score);
      setPhase("dead");
    };

    let raf = 0;
    const frame = (now: number) => {
      raf = requestAnimationFrame(frame);
      const dt = Math.min(0.033, (now - (sim.last || now)) / 1000);
      sim.last = now;
      if (!sim.on) return;
      const w = stage.clientWidth;
      sim.vy -= 2400 * dt;
      sim.y = Math.max(0, sim.y + sim.vy * dt);
      if (sim.y === 0) sim.vy = 0;
      sim.speed = Math.min(640, sim.speed + 8 * dt);
      sim.dist += (sim.speed * dt) / 18;
      sim.wait -= dt;
      if (sim.wait <= 0) {
        const free = sim.slots.find((s) => !s.on);
        if (free) {
          free.on = true;
          free.x = w + 10;
        }
        sim.wait = Math.max(0.72, 1.35 - sim.dist / 400);
      }
      const runnerLeft = w * 0.16 + 28;
      const runnerRight = runnerLeft + 36;
      for (const slot of sim.slots) {
        if (!slot.on) continue;
        slot.x -= sim.speed * dt;
        if (slot.x < -80) slot.on = false;
        const hitX = slot.x < runnerRight && slot.x + 28 > runnerLeft;
        if (hitX && sim.y < 46) finish();
      }
      place();
      paintScore();
    };
    raf = requestAnimationFrame(frame);
    place();

    const down = (e: Event) => {
      e.preventDefault();
      start();
    };
    stage.addEventListener("pointerdown", down);
    const key = (e: KeyboardEvent) => {
      if (e.code === "Space" || e.code === "ArrowUp") {
        e.preventDefault();
        start();
      }
    };
    window.addEventListener("keydown", key);
    return () => {
      cancelAnimationFrame(raf);
      stage.removeEventListener("pointerdown", down);
      window.removeEventListener("keydown", key);
    };
  }, []);

  return (
    <div className="flex flex-col gap-3">
      <BackBar title="Jogo" onBack={onBack} />
      <div className="flex items-end justify-between px-1">
        <p className="font-display text-3xl leading-none">
          <span ref={scoreRef}>0 m</span>
        </p>
        <p className="text-sm text-ink/70">recorde {best} m</p>
      </div>
      <div ref={stageRef} className="race" role="button" aria-label="Toque para pular">
        <div className="lane-sky" />
        <div className="lane-grass" />
        <div className="lane-track">
          <div className="lane-lines" />
          <div className="lane-lines lane-lines-2" />
        </div>
        {Array.from({ length: SLOTS }, (_, i) => (
          <div
            key={i}
            ref={(el) => {
              slotRefs.current[i] = el;
            }}
            className="race-hurdle"
          >
            <span />
          </div>
        ))}
        <div ref={runnerRef} className="race-runner">
          <img ref={bobRef} src={mascotSrc(id, "run")} alt="" className="race-bob" />
        </div>
        {phase !== "run" && (
          <div className="race-banner">
            {phase === "ready" ? (
              <>
                <p className="font-display text-3xl leading-none">Toque para pular</p>
                <p className="text-sm">O obstáculo vem da direita. Pule por cima.</p>
              </>
            ) : (
              <>
                <p className="font-display text-3xl leading-none">Bateu</p>
                <p className="text-sm">
                  {finalScore} m{finalScore >= best && finalScore > 0 ? " · novo recorde" : ""}
                </p>
                <p className="text-sm">Toque para tentar de novo</p>
              </>
            )}
          </div>
        )}
      </div>
      <p className="text-sm text-ink/65">
        O boneco fica parado. A pista anda. Cada toque é um pulo, só com o pé no chão.
      </p>
    </div>
  );
}
