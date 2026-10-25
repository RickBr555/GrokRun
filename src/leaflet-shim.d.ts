declare module "leaflet" {
  // Leaflet não publica tipos; o mapa é isolado em MapView.
  const leaflet: any;
  export default leaflet;
  export function map(el: HTMLElement, options?: Record<string, unknown>): any;
  export function tileLayer(url: string, options?: Record<string, unknown>): any;
  export function polyline(latlngs: [number, number][], options?: Record<string, unknown>): any;
  export function marker(latlng: [number, number], options?: Record<string, unknown>): any;
  export function divIcon(options: Record<string, unknown>): any;
  export function latLngBounds(latlngs: [number, number][]): any;
}

declare module "leaflet/dist/leaflet.css";
