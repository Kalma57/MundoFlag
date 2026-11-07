/*
 * Builds src/main/resources/static/js/three-globe.bundle.js — a fully local,
 * self-contained bundle of three-globe with two modifications:
 *
 *  1. (existing, from the earlier local-vendoring pass) 'three' and every
 *     'three/examples/jsm/*' subpath are left external, since those are
 *     already vendored locally and resolved via the page's import map.
 *
 *  2. (new) The single call site in the polygon layer that builds each
 *     country's 3D geometry live —
 *       new ConicPolygonGeometry(coords, 0, GLOBE_RADIUS, false, true, true, capCurvatureResolution)
 *     — is replaced with a call to an injected __getPolygonGeometry()
 *     wrapper. That wrapper hashes `coords` (using the exact same function
 *     as tools/globe-geometry-build/precompute-geometry.js — see
 *     hash-fn-source.js, injected verbatim below so the two can never
 *     drift apart) and looks it up in a precomputed-geometry registry.
 *     If found: builds a plain THREE.BufferGeometry directly from the
 *     precomputed typed arrays (no triangulation). If not found (e.g. the
 *     registry hasn't been supplied, or a genuinely new/changed polygon):
 *     falls back to the exact original `new ConicPolygonGeometry(...)`
 *     call — so behavior is byte-for-byte identical to before whenever the
 *     precomputed data isn't available, by construction. This is a pure
 *     speed optimization with a guaranteed-safe fallback, never a
 *     correctness requirement.
 *
 *     globe.js supplies the registry by calling the extra named export
 *     `__setPrecomputedPolygonGeometry(manifest, arrayBuffer)` after
 *     fetching /assets/polygon-geometry-manifest.json and
 *     /assets/polygon-geometry.bin, before calling world.polygonsData(...).
 *
 * Run with: npm install && npm run build   (see package.json)
 */

const fs = require('fs');
const path = require('path');
const esbuild = require('esbuild');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const SRC = path.join(__dirname, 'node_modules', 'three-globe', 'dist', 'three-globe.mjs');
const PATCHED = path.join(__dirname, 'three-globe.patched.mjs');
const OUT = path.join(REPO_ROOT, 'src/main/resources/static/js/three-globe.bundle.js');

const ORIGINAL_CALL = 'new ConicPolygonGeometry(coords, 0, GLOBE_RADIUS, false, true, true, capCurvatureResolution)';
const REPLACEMENT_CALL = '__getPolygonGeometry(coords, GLOBE_RADIUS, capCurvatureResolution)';

let src = fs.readFileSync(SRC, 'utf8');

const occurrences = src.split(ORIGINAL_CALL).length - 1;
if (occurrences !== 1) {
  throw new Error(
    `Expected exactly 1 occurrence of the polygon geometry call site in ${SRC}, found ${occurrences}. ` +
    `three-globe's internals likely changed — re-verify the call site (e.g. via its sourcemap, src/layers/polygons.js) before patching.`
  );
}

src = src.replace(ORIGINAL_CALL, REPLACEMENT_CALL);

// Inject right after the last top-level `import` line, so `ConicPolygonGeometry`,
// `GLOBE_RADIUS` etc. are all already in scope for our injected function
// (function declarations are hoisted within the module regardless of exact
// position, but keeping it textually near the top stays readable).
const importLines = src.split('\n');
let lastImportLine = -1;
for (let i = 0; i < importLines.length; i++) {
  if (/^import /.test(importLines[i])) lastImportLine = i;
}
if (lastImportLine === -1) throw new Error('Could not find any top-level import statement to anchor the patch after.');

const { hashCoords } = require('./hash-fn-source');
const hashFnSource = fs.readFileSync(path.join(__dirname, 'hash-fn-source.js'), 'utf8')
  // hash-fn-source.js is a CommonJS module (module.exports = ...) so it can be
  // require()'d from precompute-geometry.js; strip that line when inlining
  // the function into an ES module — the function body itself is plain ES
  // and identical either way.
  .replace(/module\.exports\s*=\s*\{[^}]*\};?\s*$/, '');

const injected = `
${hashFnSource}

// ---- injected by tools/globe-geometry-build/build-three-globe-bundle.js ----
// The FULL three namespace. (Don't use three-globe's own internal aliases like THREE$h: those
// are "window.THREE or a small hand-picked subset" objects that lack BufferGeometry, which made
// every precomputed lookup throw "BufferGeometry is not a constructor".)
import * as __THREE_FULL from 'three';
let __precomputedPolygonManifest = null;
let __precomputedPolygonPositions = null; // Float32Array view over the fetched .bin
let __precomputedPolygonIndices = null;   // Uint16Array view over the fetched .bin

export function __setPrecomputedPolygonGeometry(manifest, arrayBuffer) {
  const header = new Uint32Array(arrayBuffer, 0, 2);
  const totalPosFloats = header[0];
  const totalIdxU16 = header[1];
  __precomputedPolygonManifest = manifest;
  __precomputedPolygonPositions = new Float32Array(arrayBuffer, 8, totalPosFloats);
  __precomputedPolygonIndices = new Uint16Array(arrayBuffer, 8 + totalPosFloats * 4, totalIdxU16);
}

function __getPolygonGeometry(coords, radius, capCurvatureResolution) {
  if (__precomputedPolygonManifest) {
    const key = hashCoords(JSON.stringify(coords));
    const entry = __precomputedPolygonManifest[key];
    if (entry) {
      const geom = new __THREE_FULL.BufferGeometry();
      geom.setAttribute('position', new __THREE_FULL.BufferAttribute(
        __precomputedPolygonPositions.subarray(entry.posOffset, entry.posOffset + entry.posCount), 3
      ));
      geom.setIndex(new __THREE_FULL.BufferAttribute(
        __precomputedPolygonIndices.subarray(entry.idxOffset, entry.idxOffset + entry.idxCount), 1
      ));
      entry.groups.forEach(g => geom.addGroup(g[0], g[1], g[2]));
      // Mimic ConicPolygonGeometry's own .parameters so the existing
      // objMatch() cache-check in the polygon layer's update() (which reads
      // conicObj.geometry.parameters) keeps working — without this, every
      // color-update re-render would rebuild the geometry from the
      // precomputed arrays again instead of reusing the cached object.
      geom.parameters = { polygonGeoJson: coords, curvatureResolution: capCurvatureResolution };
      return geom;
    }
  }
  // Fallback — identical to three-globe's original, unpatched behavior.
  return new ConicPolygonGeometry(coords, 0, radius, false, true, true, capCurvatureResolution);
}
// ---- end injected code ----
`;

importLines.splice(lastImportLine + 1, 0, injected);
src = importLines.join('\n');

fs.writeFileSync(PATCHED, src);
console.log('Wrote patched source to', PATCHED);

esbuild.buildSync({
  entryPoints: [PATCHED],
  bundle: true,
  format: 'esm',
  external: ['three', 'three/examples/jsm/*'],
  outfile: OUT,
  minify: true
});

console.log('Wrote bundle to', OUT, `(${(fs.statSync(OUT).size / 1024).toFixed(1)} KB)`);
