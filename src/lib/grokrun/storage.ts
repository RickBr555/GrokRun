import type { Session } from "./session";
import { DEFAULT_WHISTLES, type StoreData } from "./types";

const STORE_KEY = "grokrun-v1";
const LIVE_KEY = "grokrun-live";

export function normalizeStore(data: StoreData): StoreData {
  return {
    ...data,
    runnerName: typeof data.runnerName === "string" ? data.runnerName : "",
    profileReady: data.profileReady === true,
    theme: data.theme ?? "amarelo",
    nightMode: data.nightMode ?? "auto",
    autoPause: data.autoPause !== false,
    whistles: { ...DEFAULT_WHISTLES, ...(data.whistles ?? {}) },
    mascot: data.mascot ?? "bola",
  };
}

export function loadStore(): StoreData | null {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (!raw) return null;
    const data = JSON.parse(raw) as StoreData;
    if (!data || !Array.isArray(data.activities) || !Array.isArray(data.presets)) return null;
    return normalizeStore(data);
  } catch {
    return null;
  }
}

export function saveStore(data: StoreData): void {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(data));
  } catch {
    /* cota cheia: o treino em memória continua */
  }
}

export function loadLive(): Session | null {
  try {
    const raw = localStorage.getItem(LIVE_KEY) ?? sessionStorage.getItem(LIVE_KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    if (!s || !s.phases || s.status === "done") return null;
    s.pauseReason ??= s.status === "paused" ? "manual" : null;
    s.stillSince ??= null;
    s.moveSince ??= null;
    s.movedAt ??= s.lastTick || Date.now();
    s.instKmh ??= null;
    s.lastFixAt ??= 0;
    s.endsAt ??= [];
    s.fired ??= [];
    s.points ??= [];
    return s;
  } catch {
    return null;
  }
}

export function saveLive(s: Session | null): void {
  try {
    if (!s || s.status === "done") {
      localStorage.removeItem(LIVE_KEY);
      sessionStorage.removeItem(LIVE_KEY);
    } else {
      localStorage.setItem(LIVE_KEY, JSON.stringify(s));
    }
  } catch {
    /* sessão grande demais: o relógio em memória segue */
  }
}
