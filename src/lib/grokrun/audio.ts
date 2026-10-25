import type { WhistleId } from "./types";

/**
 * Apitos via Web Audio. Cada momento do treino usa o apito escolhido.
 * O volume é baixo de propósito (no Android isso equivale a
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).
 */

let ctx: AudioContext | null = null;

function audio(): AudioContext | null {
  if (typeof window === "undefined") return null;
  const AC =
    window.AudioContext ||
    (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AC) return null;
  if (!ctx) ctx = new AC();
  return ctx;
}

export function unlockAudio(): void {
  const c = audio();
  if (c && c.state === "suspended") void c.resume();
}

function tone(
  freq: number,
  dur: number,
  when: number,
  type: OscillatorType,
  gain: number,
  slideTo?: number,
): void {
  const c = audio();
  if (!c) return;
  const o = c.createOscillator();
  const g = c.createGain();
  o.type = type;
  o.frequency.setValueAtTime(freq, when);
  if (slideTo) o.frequency.exponentialRampToValueAtTime(Math.max(40, slideTo), when + dur);
  g.gain.setValueAtTime(gain, when);
  g.gain.exponentialRampToValueAtTime(0.0001, when + dur);
  o.connect(g);
  g.connect(c.destination);
  o.start(when);
  o.stop(when + dur + 0.02);
}

export function beepCountdown(): void {
  const c = audio();
  if (!c) return;
  tone(880, 0.09, c.currentTime, "square", 0.09);
}

export function cueSprint(): void {
  const c = audio();
  if (!c) return;
  const t = c.currentTime;
  tone(740, 0.12, t, "square", 0.14, 1400);
  tone(990, 0.14, t + 0.14, "square", 0.12, 1700);
  buzz([0, 40, 50, 40]);
}

export function cueRest(): void {
  const c = audio();
  if (!c) return;
  const t = c.currentTime;
  tone(320, 0.22, t, "sine", 0.16);
  tone(240, 0.28, t + 0.18, "sine", 0.12);
  buzz([0, 180]);
}

export function cueStart(): void {
  const c = audio();
  if (!c) return;
  tone(520, 0.16, c.currentTime, "square", 0.13, 880);
  buzz([0, 60, 40, 60]);
}

export function cueDone(): void {
  const c = audio();
  if (!c) return;
  const t = c.currentTime;
  tone(523, 0.16, t, "square", 0.12);
  tone(659, 0.16, t + 0.16, "square", 0.12);
  tone(784, 0.28, t + 0.32, "square", 0.14);
  buzz([0, 80, 50, 80, 50, 220]);
}

/** Rápido demais: bipe curto agudo duplo. */
export function cueTooFast(): void {
  const c = audio();
  if (!c) return;
  const t = c.currentTime;
  tone(1760, 0.07, t, "square", 0.06);
  tone(1760, 0.07, t + 0.11, "square", 0.06);
  buzz([0, 30, 40, 30]);
}

/** Lento demais: bipe grave longo. */
export function cueTooSlow(): void {
  const c = audio();
  if (!c) return;
  tone(196, 0.55, c.currentTime, "sine", 0.08);
  buzz([0, 280]);
}

export function buzz(pattern: number[]): void {
  if (typeof navigator !== "undefined" && typeof navigator.vibrate === "function") {
    navigator.vibrate(pattern);
  }
}

function noise(when: number, dur: number, gain: number, freq: number): void {
  const c = audio();
  if (!c) return;
  const len = Math.max(1, Math.floor(c.sampleRate * dur));
  const buf = c.createBuffer(1, len, c.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1;
  const src = c.createBufferSource();
  src.buffer = buf;
  const bp = c.createBiquadFilter();
  bp.type = "bandpass";
  bp.frequency.setValueAtTime(freq, when);
  bp.Q.value = 5;
  const g = c.createGain();
  g.gain.setValueAtTime(gain, when);
  g.gain.exponentialRampToValueAtTime(0.0001, when + dur);
  src.connect(bp);
  bp.connect(g);
  g.connect(c.destination);
  src.start(when);
  src.stop(when + dur + 0.02);
}

function peep(when: number, dur: number, f0: number, f1: number, gain = 0.15): void {
  const c = audio();
  if (!c) return;
  const o = c.createOscillator();
  const filter = c.createBiquadFilter();
  const g = c.createGain();
  filter.type = "bandpass";
  filter.frequency.setValueAtTime((f0 + f1) / 2, when);
  filter.Q.value = 7;
  o.type = "sine";
  o.frequency.setValueAtTime(Math.max(80, f0), when);
  o.frequency.exponentialRampToValueAtTime(Math.max(80, f1), when + dur);
  g.gain.setValueAtTime(0.0001, when);
  g.gain.exponentialRampToValueAtTime(gain, when + Math.min(0.03, dur * 0.25));
  g.gain.exponentialRampToValueAtTime(0.0001, when + dur);
  o.connect(filter);
  filter.connect(g);
  g.connect(c.destination);
  o.start(when);
  o.stop(when + dur + 0.02);
  noise(when, Math.min(0.08, dur), gain * 0.35, (f0 + f1) / 2);
}

export const WHISTLE_CHOICES: { id: WhistleId; label: string }[] = [
  { id: "curto", label: "Curto" },
  { id: "longo", label: "Longo" },
  { id: "duplo", label: "Duplo" },
  { id: "triplo", label: "Triplo" },
  { id: "sobe", label: "Sobe" },
  { id: "desce", label: "Desce" },
  { id: "trinado", label: "Trinado" },
  { id: "agudo", label: "Agudo" },
];

export function playWhistle(id: WhistleId): void {
  unlockAudio();
  const c = audio();
  if (!c) return;
  const t = c.currentTime;
  if (id === "curto") {
    peep(t, 0.16, 2500, 2100);
    buzz([0, 40]);
  } else if (id === "longo") {
    peep(t, 0.75, 2200, 1700, 0.14);
    buzz([0, 220]);
  } else if (id === "duplo") {
    peep(t, 0.12, 2700, 2400);
    peep(t + 0.2, 0.14, 2800, 2500);
    buzz([0, 30, 50, 40]);
  } else if (id === "triplo") {
    peep(t, 0.09, 2400, 2200);
    peep(t + 0.16, 0.09, 2600, 2400);
    peep(t + 0.32, 0.2, 2900, 2500);
    buzz([0, 30, 40, 30, 40, 60]);
  } else if (id === "sobe") {
    peep(t, 0.46, 1200, 3200, 0.15);
    buzz([0, 90]);
  } else if (id === "desce") {
    peep(t, 0.55, 3000, 780, 0.14);
    buzz([0, 180]);
  } else if (id === "agudo") {
    peep(t, 0.18, 3400, 3700, 0.11);
    buzz([0, 35]);
  } else {
    for (let i = 0; i < 6; i++) peep(t + i * 0.08, 0.05, 3000, 2500, 0.1);
    buzz([0, 20, 20, 20, 20, 20, 20, 20]);
  }
}

export async function requestWakeLock(): Promise<WakeLockSentinel | null> {
  try {
    if (!("wakeLock" in navigator)) return null;
    return await navigator.wakeLock.request("screen");
  } catch {
    return null;
  }
}

export async function notifyPhase(title: string, body: string): Promise<void> {
  try {
    if (typeof Notification === "undefined") return;
    if (Notification.permission !== "granted") return;
    if (!document.hidden) return;
    new Notification(title, { body, tag: "grokrun-workout", silent: false });
  } catch {
    /* notificações são opcionais */
  }
}
