// Drop-in replacement for the 'three-conic-polygon-geometry' package, resolved
// via the page's import map. three-globe imports this bare specifier itself
// (left external when it is bundled) to build each country's cap+side mesh.
//
// WHY: live-triangulating all ~241 countries takes ~7.5s of blocking
// main-thread work on every page load (see tools/globe-geometry-build). This
// wrapper checks a precomputed-geometry registry (hash(coords) -> offsets
// into a prebuilt binary blob) supplied by globe.js at startup via
// __setPrecomputedPolygonGeometry(), and builds a plain BufferGeometry
// directly from the precomputed typed arrays — no triangulation. Any lookup
// miss (registry not yet supplied, or a genuinely new/changed polygon) falls
// back to the exact real ConicPolygonGeometry call, so this is a pure speed
// optimization with a guaranteed-safe fallback, never a correctness
// requirement.
//
// hashCoords must stay byte-identical to tools/globe-geometry-build/hash-fn-source.js
// (the same function precompute-geometry.js uses to build the manifest keys).

import * as THREE from 'three';
// Served from this site (bundled with three left external - see the bundle-conic-polygon script in
// tools/globe-geometry-build/package.json) rather than from a third-party CDN.
import RealConicPolygonGeometry from '/js/three-conic-polygon-geometry-2.1.3.min.js';

function hashCoords(jsonStr) {
  let h1 = 0x811c9dc5;
  let h2 = 0xc271a137;
  for (let i = 0; i < jsonStr.length; i++) {
    const c = jsonStr.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193);
    h2 = Math.imul(h2 ^ c, 0x85ebca6b);
  }
  return (h1 >>> 0).toString(36) + '_' + (h2 >>> 0).toString(36);
}

let manifest = null;
let positions = null; // Float32Array view over the fetched .bin
let indices = null;   // Uint16Array view over the fetched .bin

export function __setPrecomputedPolygonGeometry(m, arrayBuffer) {
  const header = new Uint32Array(arrayBuffer, 0, 2);
  const totalPosFloats = header[0];
  const totalIdxU16 = header[1];
  manifest = m;
  positions = new Float32Array(arrayBuffer, 8, totalPosFloats);
  indices = new Uint16Array(arrayBuffer, 8 + totalPosFloats * 4, totalIdxU16);
}

export default function ConicPolygonGeometry(coords, minHeight, maxHeight, closedBottom, closedTop, includeSides, curvatureResolution) {
  if (manifest) {
    const key = hashCoords(JSON.stringify(coords));
    const entry = manifest[key];
    if (entry) {
      const geom = new THREE.BufferGeometry();
      geom.setAttribute('position', new THREE.BufferAttribute(
        positions.subarray(entry.posOffset, entry.posOffset + entry.posCount), 3
      ));
      geom.setIndex(new THREE.BufferAttribute(
        indices.subarray(entry.idxOffset, entry.idxOffset + entry.idxCount), 1
      ));
      entry.groups.forEach(g => geom.addGroup(g[0], g[1], g[2]));
      // Mimic ConicPolygonGeometry's own .parameters so three-globe's
      // objMatch() cache-check (which reads conicObj.geometry.parameters)
      // keeps working — without this every color update would rebuild the
      // geometry again instead of reusing the cached mesh.
      geom.parameters = { polygonGeoJson: coords, curvatureResolution };
      return geom;
    }
  }
  return new RealConicPolygonGeometry(coords, minHeight, maxHeight, closedBottom, closedTop, includeSides, curvatureResolution);
}
