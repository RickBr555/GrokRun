export interface LatLng {
  lat: number;
  lon: number;
}

const R = 6371000;

export function haversine(a: LatLng, b: LatLng): number {
  const φ1 = (a.lat * Math.PI) / 180;
  const φ2 = (b.lat * Math.PI) / 180;
  const dφ = ((b.lat - a.lat) * Math.PI) / 180;
  const dλ = ((b.lon - a.lon) * Math.PI) / 180;
  const s =
    Math.sin(dφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(dλ / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
}

export interface RawFix {
  lat: number;
  lon: number;
  t: number;
  acc: number;
  alt: number | null;
}

/**
 * Descarta precisão > 20 m e saltos acima de 25 km/h, comparando o ponto
 * cru com o último aceito. A suavização é só na coordenada desenhada.
 */
export class TrackFilter {
  private lat = 0;
  private lon = 0;
  private ready = false;
  private lastRaw: { lat: number; lon: number; t: number } | null = null;

  reset(): void {
    this.ready = false;
    this.lastRaw = null;
  }

  push(raw: RawFix, maxAcc = 20, maxKmh = 25): { lat: number; lon: number; stepM: number } | null {
    if (!Number.isFinite(raw.lat) || !Number.isFinite(raw.lon)) return null;
    if (!Number.isFinite(raw.acc) || raw.acc > maxAcc) return null;
    let stepM = 0;
    if (this.lastRaw) {
      const dt = (raw.t - this.lastRaw.t) / 1000;
      if (dt < 0.3) return null;
      stepM = haversine(this.lastRaw, raw);
      if ((stepM / dt) * 3.6 > maxKmh) return null;
    }
    const alpha = 0.6;
    if (!this.ready) {
      this.lat = raw.lat;
      this.lon = raw.lon;
      this.ready = true;
    } else {
      this.lat += alpha * (raw.lat - this.lat);
      this.lon += alpha * (raw.lon - this.lon);
    }
    this.lastRaw = { lat: raw.lat, lon: raw.lon, t: raw.t };
    return { lat: this.lat, lon: this.lon, stepM };
  }
}

/** Pista de 400 m (duas retas + duas curvas) em torno do Ibirapuera. */
export const TRACK_CENTER: LatLng = { lat: -23.587416, lon: -46.657634 };

export function trackPoint(distM: number, center: LatLng = TRACK_CENTER): LatLng {
  const straight = 84.39;
  const radius = 36.5;
  const curve = Math.PI * radius;
  const lap = 2 * straight + 2 * curve;
  let d = ((distM % lap) + lap) % lap;
  let x = 0;
  let y = 0;
  if (d < straight) {
    x = -straight / 2 + d;
    y = radius;
  } else if (d < straight + curve) {
    const a = (d - straight) / radius;
    x = straight / 2 + Math.sin(a) * radius;
    y = Math.cos(a) * radius;
  } else if (d < 2 * straight + curve) {
    const s = d - straight - curve;
    x = straight / 2 - s;
    y = -radius;
  } else {
    const a = (d - 2 * straight - curve) / radius;
    x = -straight / 2 - Math.sin(a) * radius;
    y = -Math.cos(a) * radius;
  }
  return metersToLatLng(center, x, y);
}

/** Volta de parque de 1 km (círculo) para a corrida simulada. */
export function parkPoint(distM: number, center: LatLng = TRACK_CENTER): LatLng {
  const radius = 1000 / (2 * Math.PI);
  const ang = distM / radius;
  const x = Math.sin(ang) * radius;
  const y = -Math.cos(ang) * radius;
  return metersToLatLng(center, x, y);
}

function metersToLatLng(center: LatLng, eastM: number, northM: number): LatLng {
  const lat = center.lat + northM / 110540;
  const lon =
    center.lon + eastM / (111320 * Math.cos((center.lat * Math.PI) / 180));
  return { lat, lon };
}

export function altitudeTrack(distM: number): number {
  return 728 + 5.5 * Math.sin(distM / 420) + 1.2 * Math.sin(distM / 70);
}

export function altitudePark(distM: number): number {
  return 718 + 16 * Math.sin(distM / 380) + 6 * Math.sin(distM / 95);
}

/** Ganho e perda com limiar para ignorar ruído (padrão 2,5 m). */
export function elevationChange(
  alts: Array<number | null>,
  threshold = 2.5,
): { gain: number; loss: number } {
  let gain = 0;
  let loss = 0;
  let extreme: number | null = null;
  for (const alt of alts) {
    if (alt == null || !Number.isFinite(alt)) continue;
    if (extreme == null) {
      extreme = alt;
      continue;
    }
    const diff = alt - extreme;
    if (diff >= threshold) {
      gain += diff;
      extreme = alt;
    } else if (diff <= -threshold) {
      loss += -diff;
      extreme = alt;
    }
  }
  return { gain, loss };
}

export function smoothAltitudes(alts: Array<number | null>, alpha = 0.35): Array<number | null> {
  let prev: number | null = null;
  return alts.map((alt) => {
    if (alt == null || !Number.isFinite(alt)) return prev;
    prev = prev == null ? alt : prev + alpha * (alt - prev);
    return prev;
  });
}

/**
 * Altitude relativa a partir da pressão, no mesmo modelo de
 * SensorManager.getAltitude: 44330 * (1 - (p/p0)^(1/5.255)).
 */
export function pressureToAltitude(pressureHpa: number, seaLevel = 1013.25): number {
  return 44330 * (1 - Math.pow(pressureHpa / seaLevel, 1 / 5.255));
}
