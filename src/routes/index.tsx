import { createFileRoute } from "@tanstack/react-router";
import { GrokApp } from "@/components/grokrun/App";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return <GrokApp />;
}
