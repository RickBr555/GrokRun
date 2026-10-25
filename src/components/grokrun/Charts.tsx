import { useEffect, useState } from "react";
import { formatPace } from "@/lib/grokrun/format";

type Charts = typeof import("recharts");

function useCharts(): Charts | null {
  const [lib, setLib] = useState<Charts | null>(null);
  useEffect(() => {
    void import("recharts").then(setLib);
  }, []);
  return lib;
}

const tip = {
  background: "#fff8ea",
  border: "3px solid #1c140c",
  borderRadius: 12,
  fontSize: 13,
};

export function PaceChart({ data }: { data: { dia: string; pace: number }[] }) {
  const R = useCharts();
  if (!R || data.length === 0) return <div className="card h-48" />;
  return (
    <div className="card h-56 p-2">
      <R.ResponsiveContainer width="100%" height="100%">
        <R.LineChart data={data} margin={{ top: 12, right: 8, left: 0, bottom: 0 }}>
          <R.CartesianGrid stroke="#1c140c22" vertical={false} />
          <R.XAxis dataKey="dia" tick={{ fill: "#1c140c", fontSize: 11 }} axisLine={false} tickLine={false} />
          <R.YAxis
            tickFormatter={(v: number) => formatPace(v)}
            width={48}
            tick={{ fill: "#1c140c", fontSize: 11 }}
            axisLine={false}
            tickLine={false}
            domain={["auto", "auto"]}
          />
          <R.Tooltip
            contentStyle={tip}
            formatter={(v) => [formatPace(Number(v)), "pace médio"]}
          />
          <R.Line type="monotone" dataKey="pace" stroke="#e23d2b" strokeWidth={3} dot={{ r: 4, fill: "#fff8ea", stroke: "#1c140c", strokeWidth: 2 }} />
        </R.LineChart>
      </R.ResponsiveContainer>
    </div>
  );
}

export function BarMini({
  data,
  color,
  unit,
}: {
  data: { dia: string; valor: number }[];
  color: string;
  unit: string;
}) {
  const R = useCharts();
  if (!R || data.length === 0) return <div className="card h-40" />;
  return (
    <div className="card h-44 p-2">
      <R.ResponsiveContainer width="100%" height="100%">
        <R.BarChart data={data} margin={{ top: 12, right: 8, left: 0, bottom: 0 }}>
          <R.XAxis dataKey="dia" tick={{ fill: "#1c140c", fontSize: 11 }} axisLine={false} tickLine={false} />
          <R.YAxis width={32} tick={{ fill: "#1c140c", fontSize: 11 }} axisLine={false} tickLine={false} />
          <R.Tooltip contentStyle={tip} formatter={(v) => [`${Number(v).toLocaleString("pt-BR")} ${unit}`, ""]} />
          <R.Bar dataKey="valor" fill={color} radius={[6, 6, 0, 0]} />
        </R.BarChart>
      </R.ResponsiveContainer>
    </div>
  );
}

export function ElevationChart({ data }: { data: { km: number; alt: number }[] }) {
  const R = useCharts();
  if (!R || data.length < 2) return null;
  return (
    <div className="card h-48 p-2">
      <R.ResponsiveContainer width="100%" height="100%">
        <R.AreaChart data={data} margin={{ top: 12, right: 8, left: 0, bottom: 0 }}>
          <R.CartesianGrid stroke="#1c140c22" vertical={false} />
          <R.XAxis
            dataKey="km"
            tickFormatter={(v: number) => `${v.toLocaleString("pt-BR", { maximumFractionDigits: 1 })}`}
            tick={{ fill: "#1c140c", fontSize: 11 }}
            axisLine={false}
            tickLine={false}
          />
          <R.YAxis
            width={42}
            tick={{ fill: "#1c140c", fontSize: 11 }}
            axisLine={false}
            tickLine={false}
            domain={["auto", "auto"]}
          />
          <R.Tooltip
            contentStyle={tip}
            formatter={(v) => [`${Math.round(Number(v))} m`, "altitude"]}
            labelFormatter={(v) => `${Number(v).toLocaleString("pt-BR", { maximumFractionDigits: 2 })} km`}
          />
          <R.Area type="monotone" dataKey="alt" stroke="#2f6b3a" fill="#2f6b3a55" strokeWidth={3} />
        </R.AreaChart>
      </R.ResponsiveContainer>
    </div>
  );
}
