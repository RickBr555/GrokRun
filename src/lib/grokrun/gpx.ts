import type { Activity } from "./types";

function iso(t: number): string {
  return new Date(t).toISOString();
}

function esc(s: string): string {
  return s.replace(/&/g, "&").replace(/</g, "<").replace(/>/g, ">");
}

export function activityToGpx(activity: Activity): string {
  const name = activity.mode === "tiros" ? "GrokRun tiros" : "GrokRun corrida";
  const pts = activity.points
    .map((p) => {
      const ele = p.alt != null ? `<ele>${p.alt.toFixed(1)}</ele>` : "";
      return `      <trkpt lat="${p.lat.toFixed(6)}" lon="${p.lon.toFixed(6)}"><time>${iso(p.t)}</time>${ele}</trkpt>`;
    })
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="GrokRun" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>${esc(name)}</name>
    <time>${iso(activity.startedAt)}</time>
  </metadata>
  <trk>
    <name>${esc(name)}</name>
    <type>${activity.mode === "tiros" ? "running" : "running"}</type>
    <trkseg>
${pts}
    </trkseg>
  </trk>
</gpx>
`;
}

export function downloadText(filename: string, contents: string, mime: string): void {
  const blob = new Blob([contents], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
