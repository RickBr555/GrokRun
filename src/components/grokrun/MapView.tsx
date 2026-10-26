import { useEffect, useRef, useState } from "react";
import type { TrackPoint } from "@/lib/grokrun/types";

const COLOR: Record<string, string> = {
  warmup: "#e6a317",
  sprint: "#e23d2b",
  rest: "#1c140c",
  cycleRest: "#2f6b3a",
  cooldown: "#fff8ea",
  run: "#e23d2b",
};

export function MapView({ points }: { points: TrackPoint[] }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el || points.length < 2) return;
    let map: { remove: () => void } | null = null;
    let cancelled = false;

    void (async () => {
      const L = await import("leaflet");
      await import("leaflet/dist/leaflet.css");
      if (cancelled || !ref.current) return;
      const view = L.map(ref.current, { zoomControl: false, attributionControl: true });
      L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "&copy; OpenStreetMap",
      }).addTo(view);

      let seg: TrackPoint[] = [];
      const flush = () => {
        if (seg.length < 2) return;
        const kind = seg[0].phase;
        L.polyline(
          seg.map((p) => [p.lat, p.lon] as [number, number]),
          {
            color: COLOR[kind] ?? "#1c140c",
            weight: kind === "cooldown" ? 5 : 6,
            opacity: 0.95,
          },
        ).addTo(view);
      };
      for (const p of points) {
        if (seg.length && seg[seg.length - 1].phase !== p.phase) {
          seg.push(p);
          flush();
          seg = [p];
        } else {
          seg.push(p);
        }
      }
      flush();

      const starts = new Map<number, TrackPoint>();
      const ends = new Map<number, TrackPoint>();
      for (const p of points) {
        if (p.phase !== "sprint") continue;
        if (!starts.has(p.phaseIndex)) starts.set(p.phaseIndex, p);
        ends.set(p.phaseIndex, p);
      }
      let n = 1;
      for (const [, p] of starts) {
        const icon = L.divIcon({
          className: "sprint-pin",
          html: `<span>${n}</span>`,
          iconSize: [22, 22],
          iconAnchor: [11, 11],
        });
        L.marker([p.lat, p.lon], { icon, title: `Início do tiro ${n}` }).addTo(view);
        n += 1;
      }
      for (const [, p] of ends) {
        const icon = L.divIcon({
          className: "end-pin",
          html: "<span></span>",
          iconSize: [12, 12],
          iconAnchor: [6, 6],
        });
        L.marker([p.lat, p.lon], { icon, title: "Fim do tiro" }).addTo(view);
      }

      view.fitBounds(L.latLngBounds(points.map((p) => [p.lat, p.lon] as [number, number])).pad(0.25));
      map = view;
    })();

    return () => {
      cancelled = true;
      map?.remove();
    };
  }, [points]);

  if (points.length < 2) {
    return (
      <div className="card grid h-48 place-items-center px-4 text-center text-ink/70">
        Sem percurso gravado neste treino.
      </div>
    );
  }

  return (
    <div className="card h-72 overflow-hidden p-0">
      <div ref={ref} className="h-full w-full" />
    </div>
  );
}

export function LiveMap({ points, className = "h-56" }: { points: TrackPoint[]; className?: string }) {
  const host = useRef<HTMLDivElement>(null);
  const api = useRef<{
    map: {
      remove: () => void;
      panTo: (ll: [number, number], opts?: { animate?: boolean }) => void;
      setView: (ll: [number, number], zoom: number) => void;
      on: (ev: string, fn: () => void) => void;
    };
    line: { setLatLngs: (ll: [number, number][]) => void };
    dot: { setLatLng: (ll: [number, number]) => void };
  } | null>(null);
  const follow = useRef(true);
  const [following, setFollowing] = useState(true);
  const last = points.at(-1);
  const stamp = `${points.length}:${last?.t ?? 0}:${last?.lat ?? 0}:${last?.lon ?? 0}`;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    let dead = false;
    void (async () => {
      const L = await import("leaflet");
      await import("leaflet/dist/leaflet.css");
      if (dead || !host.current) return;
      const map = L.map(host.current, { zoomControl: false, attributionControl: true });
      L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        attribution: "&copy; OpenStreetMap",
      }).addTo(map);
      const line = L.polyline([], { color: "#e23d2b", weight: 6, opacity: 0.95 }).addTo(map);
      const icon = L.divIcon({
        className: "live-dot",
        html: '<span class="live-dot-core"></span>',
        iconSize: [22, 22],
        iconAnchor: [11, 11],
      });
      const dot = L.marker([-29.591, -51.16], { icon, interactive: false }).addTo(map);
      map.setView([-29.591, -51.16], 16);
      map.on("dragstart", () => {
        follow.current = false;
        setFollowing(false);
      });
      api.current = { map, line, dot };
    })();
    return () => {
      dead = true;
      api.current?.map.remove();
      api.current = null;
    };
  }, []);

  useEffect(() => {
    const view = api.current;
    if (!view || !last) return;
    view.line.setLatLngs(points.map((p) => [p.lat, p.lon]));
    view.dot.setLatLng([last.lat, last.lon]);
    if (follow.current) view.map.panTo([last.lat, last.lon], { animate: true });
  }, [stamp, points, last]);

  return (
    <div className={`card relative overflow-hidden p-0 ${className}`}>
      <div ref={host} className="h-full w-full" />
      {!last ? (
        <p className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 rounded-xl bg-ink/80 px-3 py-1.5 text-sm font-bold text-white">
          Procurando GPS…
        </p>
      ) : null}
      {!following && last ? (
        <button
          type="button"
          className="absolute right-2 bottom-2 rounded-full bg-[#1e88e5] px-3 py-1.5 text-sm font-bold text-white"
          onClick={() => {
            follow.current = true;
            setFollowing(true);
            api.current?.map.setView([last.lat, last.lon], 17);
          }}
        >
          ◎ Centralizar
        </button>
      ) : null}
    </div>
  );
}
