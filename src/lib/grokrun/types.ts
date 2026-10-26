export type MascotId = "bola" | "triangulo" | "hexagono" | "fantasma" | "oblongo" | "quadrado";

const MASCOT_ALIAS: Record<string, MascotId> = {
  bola: "bola",
  triangulo: "triangulo",
  pingo: "triangulo",
  hexagono: "hexagono",
  nuvem: "hexagono",
  fantasma: "fantasma",
  oblongo: "oblongo",
  chapeu: "oblongo",
  cone: "oblongo",
  rosa: "oblongo",
  quadrado: "quadrado",
  toalha: "quadrado",
  gorro: "quadrado",
};

export function canonMascot(id: string | undefined | null): MascotId {
  if (id && id in MASCOT_ALIAS) return MASCOT_ALIAS[id];
  return "bola";
}

export type WorkoutMode = "tiros" | "corrida";

export type ThemeId = "amarelo" | "laranja" | "verde" | "azul";

export type NightMode = "auto" | "on" | "off";

export type WhistleId =
  | "curto"
  | "longo"
  | "duplo"
  | "triplo"
  | "sobe"
  | "desce"
  | "trinado"
  | "agudo";

export interface WhistleMap {
  start: WhistleId;
  sprint: WhistleId;
  rest: WhistleId;
  finalRest: WhistleId;
  done: WhistleId;
}

export const DEFAULT_WHISTLES: WhistleMap = {
  start: "triplo",
  sprint: "duplo",
  rest: "longo",
  finalRest: "desce",
  done: "sobe",
};

export type PhaseKind =
  | "warmup"
  | "sprint"
  | "rest"
  | "cycleRest"
  | "cooldown"
  | "run";

export type AltSource = "baro" | "gps" | "dem" | "estimated";

export type GpsMode = "device" | "sim";

export interface IntervalConfig {
  warmupSec: number;
  sprintSec: number;
  restSec: number;
  sprintsPerCycle: number;
  cycles: number;
  cycleRestSec: number;
  cooldownSec: number;
}

export interface RunConfig {
  /** Limite rápido, em segundos por km (menor número = mais rápido). */
  fastSec: number;
  /** Limite lento, em segundos por km. */
  slowSec: number;
  /** Janela da média móvel, 15–30 s. */
  windowSec: number;
}

export interface PhasePlan {
  kind: PhaseKind;
  durationSec: number;
  cycle: number | null;
  sprint: number | null;
  label: string;
}

export interface ScheduledPhase extends PhasePlan {
  startMs: number;
  endMs: number;
}

export interface TrackPoint {
  lat: number;
  lon: number;
  t: number;
  acc: number;
  alt: number | null;
  altSource: AltSource;
  phase: PhaseKind;
  phaseIndex: number;
  distM: number;
}

export interface PhaseRecord {
  kind: PhaseKind;
  label: string;
  cycle: number | null;
  sprint: number | null;
  durationSec: number;
  distanceM: number;
  avgPaceSec: number | null;
  avgSpeedKmh: number;
}

export interface KmSplit {
  km: number;
  timeSec: number;
  splitSec: number;
  paceSec: number;
}

export interface Activity {
  id: string;
  startedAt: number;
  mode: WorkoutMode;
  mascot: MascotId;
  interval?: IntervalConfig;
  run?: RunConfig;
  phases: PhaseRecord[];
  splits: KmSplit[];
  points: TrackPoint[];
  distanceM: number;
  durationSec: number;
  elevationGain: number;
  elevationLoss: number;
  elevationEstimated: boolean;
  elevationSource: AltSource;
  inZoneRatio: number | null;
  gpsMode: GpsMode;
  demo?: boolean;
}

export interface Preset {
  id: string;
  name: string;
  mode: WorkoutMode;
  interval?: IntervalConfig;
  run?: RunConfig;
}

export interface StoreData {
  mascot: MascotId;
  runnerName: string;
  profileReady: boolean;
  theme: ThemeId;
  nightMode: NightMode;
  autoPause: boolean;
  whistles: WhistleMap;
  activities: Activity[];
  presets: Preset[];
  permsSeen: boolean;
}
