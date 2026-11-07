import * as THREE from 'three';
import { OrbitControls } from '/controls/OrbitControls.js';
import ThreeGlobe from 'three-globe';
import { __setPrecomputedPolygonGeometry } from 'three-conic-polygon-geometry';

// Country name mapping - critical for matching between DB and map
const countryNameMapping = {
    // Cases of "&" vs "and" and abbreviations
    "Antigua & Barbuda": "Antigua and Barb.",
    "Trinidad & Tobago": "Trinidad and Tobago",
    "Bosnia & Herzegovina": "Bosnia and Herz.",
    "São Tomé & Príncipe": "São Tomé and Principe",
    "St Vincent & the Grenadines": "St. Vin. and Gren.",
    "St Kitts & Nevis": "St. Kitts and Nevis",
    "St Lucia": "Saint Lucia",
    "St Martin": "St-Martin",
    "St Barthélemy": "St-Barthélemy",
    "St Helena": "Saint Helena",
    "St Pierre & Miquelon": "St. Pierre and Miquelon",
    "Turks & Caicos Islands": "Turks and Caicos Is.",
    "Wallis & Futuna": "Wallis and Futuna Is.",
    "Svalbard & Jan Mayen": "Svalbard",
    "South Georgia & South Sandwich Islands": "S. Geo. and the Is.",
    "Heard & McDonald Islands": "Heard I. and McDonald Is.",

    // United States (DB says "United States", map says "United States of America").
    // "United Kingdom" needs no entry at all - the DB and map names already match exactly.
    // (Stale "USA"/"UK" aliases used to live here too, but neither is the DB's real name -
    // they only ever silently broke the reverse map-name -> DB-name lookup this click-based
    // game depends on, redirecting a correct click to a name the DB doesn't have.)
    "United States": "United States of America",

    // Republic name changes (TopoJSON uses abbreviations)
    "Central African Republic": "Central African Rep.",
    "Dominican Republic": "Dominican Rep.",
    "Equatorial Guinea": "Eq. Guinea",

    // Congo
    "Congo - Kinshasa": "Dem. Rep. Congo",
    "Congo - Brazzaville": "Congo",

    // Political and regional adjustments
    "North Macedonia": "Macedonia",
    "Myanmar (Burma)": "Myanmar",
    "Western Sahara": "W. Sahara",
    "South Sudan": "S. Sudan",
    "Eswatini": "eSwatini",
    "Cape Verde": "Cabo Verde",
    "Falkland Islands": "Falkland Is.",
    "Solomon Islands": "Solomon Is.",

    // Apostrophe fix (your DB uses a curly apostrophe ’, the map expects a straight one ')
    "Côte d’Ivoire": "Côte d'Ivoire",

    // Chinese territories
    "Macao SAR China": "Macao",
    "Hong Kong SAR China": "Hong Kong",

    // Small islands/territories present as real polygons in the map data, just under a more
    // abbreviated name than our DB uses (previously unmapped, so clicking them always failed).
    "British Virgin Islands": "British Virgin Is.",
    "Cayman Islands": "Cayman Is.",
    "Cook Islands": "Cook Is.",
    "French Polynesia": "Fr. Polynesia",
    "Marshall Islands": "Marshall Is.",
    "Northern Mariana Islands": "N. Mariana Is.",
    "Pitcairn Islands": "Pitcairn Is.",
    "Vatican City": "Vatican",
    "Faroe Islands": "Faeroe Is.",
    "US Virgin Islands": "U.S. Virgin Is.",
    "British Indian Ocean Territory": "Br. Indian Ocean Ter."
};

// Coordinates for micro-states that are too small to appear as their own
// polygon in the world-atlas dataset. These are rendered as point markers
// instead, but only for names actually missing from the loaded map data
// (checked at runtime in initGlobe) — everything else is a real vector
// polygon now, so it stays crisp and visible at any zoom level.
const tinyCountriesExtras = {
    "Trinidad and Tobago": { lat: 10.69, lon: -61.22 },
    "St. Vin. and Gren.": { lat: 12.98, lon: -61.28 },
    "Grenada": { lat: 12.11, lon: -61.67 },
    "Saint Lucia": { lat: 13.90, lon: -60.97 },
    "Barbados": { lat: 13.19, lon: -59.54 },
    "Luxembourg": { lat: 49.81, lon: 6.12 },
    "Monaco": { lat: 43.75, lon: 7.41 },
    "Malta": { lat: 35.93, lon: 14.37 },
    "Andorra": { lat: 42.54, lon: 1.60 },
    "Anguilla": { lat: 18.22, lon: -63.06 },
    "Aruba": { lat: 12.52, lon: -69.96 },
    "Bermuda": { lat: 32.32, lon: -64.75 },
    "Gibraltar": { lat: 36.13, lon: -5.34 },
    "Guernsey": { lat: 49.46, lon: -2.58 },
    "Isle of Man": { lat: 54.23, lon: -4.54 },
    "Jersey": { lat: 49.21, lon: -2.13 },
    "Liechtenstein": { lat: 47.16, lon: 9.55 },
    "Macau": { lat: 22.19, lon: 113.54 },
    "Nauru": { lat: -0.52, lon: 166.93 },
    "San Marino": { lat: 43.94, lon: 12.45 },
    "Singapore": { lat: 1.35, lon: 103.81 },
    "Tuvalu": { lat: -7.10, lon: 177.64 }
};

// Some countries' map polygon is a MultiPolygon that includes overseas territories our own DB
// models as their OWN separate country (own capital, own flag) - Mayotte/Réunion/Martinique/
// Guadeloupe/French Guiana are all real rows in the Countries table, not just parts of France,
// and Svalbard & Jan Mayen is its own row too, not just part of Norway. Left in place, clicking
// (or coloring a guess of) the parent would wrongly claim that territory. Indices found by
// inspecting the actual /assets/countries-50m.json - see tools/ for how to re-derive these if
// the map data file is ever swapped out.
const DETACHED_SUBPOLYGONS = {
    "France": [3, 4, 5, 6, 7, 8, 9],   // Mayotte, Réunion, Martinique, Guadeloupe (x3), French Guiana
    "Norway": [22, 23, 24, 25, 26, 27, 28, 29, 30, 31]  // Jan Mayen + the Svalbard archipelago
};

function stripDetachedSubPolygons(data) {
    for (const feature of data.features) {
        const toStrip = DETACHED_SUBPOLYGONS[feature.properties.name];
        if (toStrip && feature.geometry.type === 'MultiPolygon') {
            feature.geometry.coordinates = feature.geometry.coordinates.filter((_, i) => !toStrip.includes(i));
        }
    }
}

/**
 * A few countries (Russia, Fiji, Antarctica) have a ring that crosses the antimeridian
 * (e.g. one vertex at 179.9°, the next at -179.9° - a real ~0.2° hop, but a naive point-in-
 * polygon test sees a fake ~359.8° edge spanning nearly the whole globe's longitude range).
 * That turns into false-positive hits at the SAME latitude but completely unrelated
 * longitudes (verified: a click near Jan Mayen at 71°N, 8°W was wrongly resolving to Russia,
 * whose Arctic coast sits at a similar latitude on the other side of the date line).
 * Fixed once here by "unwrapping" each ring's longitudes into a continuous sequence (letting
 * them run past ±180° instead of snapping back) - findCountryAtLatLon then tests the click
 * point at its normal longitude plus/minus 360° so it still matches whichever "copy" the ring
 * ended up unwrapped into.
 */
function unwrapAntimeridianRings(data) {
    for (const feature of data.features) {
        const geom = feature.geometry;
        if (!geom) continue;
        const polys = geom.type === 'Polygon' ? [geom.coordinates] : geom.type === 'MultiPolygon' ? geom.coordinates : [];
        for (const poly of polys) {
            for (const ring of poly) {
                let offset = 0;
                let prevRawLon = ring[0][0];
                for (let i = 1; i < ring.length; i++) {
                    const rawLon = ring[i][0];
                    const diff = rawLon - prevRawLon;
                    if (diff > 180) offset -= 360;
                    else if (diff < -180) offset += 360;
                    if (offset !== 0) ring[i][0] = rawLon + offset;
                    prevRawLon = rawLon;
                }
            }
        }
    }
}

// Real map polygons with no corresponding row in our own Countries DB at all (disputed
// territories, uninhabited possessions, autonomous regions never modeled as their own
// "country" here) - a click here can never be confirmed, so it should never even register as
// a selection in the first place rather than letting the player pick something and then
// silently fail on Confirm.
const NON_PLAYABLE_TERRITORIES = new Set([
    "Ashmore and Cartier Is.", "Fr. S. Antarctic Lands", "Indian Ocean Ter.",
    "N. Cyprus", "Palestine", "Siachen Glacier", "Somaliland", "Åland"
]);

// Reverse of countryNameMapping (map-name -> DB-name) - built once below, used to translate
// a clicked polygon's map-dataset name back into the name our own DB/backend expects.
const reverseCountryNameMapping = {};
for (const [dbName, mapName] of Object.entries(countryNameMapping)) {
    reverseCountryNameMapping[mapName] = dbName;
}
function mapNameToDbName(mapName) {
    return reverseCountryNameMapping[mapName] || mapName;
}

const SKY_COLOR = 0xffffff;

let scene, camera, renderer, controls, world;
let countriesData = null;
let guessedCountriesColors = {};
let missingPolygonNames = new Set();
let guessedPoints = [];
let selectedMapName = null;
let countryClickCallback = null;

/** Camera distance at which the whole globe (radius 100 + atmosphere) fits the canvas, in its narrower direction. */
function fitDistance(canvas) {
    const aspect = canvas.clientWidth / Math.max(1, canvas.clientHeight);
    const vHalf = (50 / 2) * Math.PI / 180;                      // camera fov is 50deg vertical
    const hHalf = Math.atan(Math.tan(vHalf) * aspect);
    return Math.max(200, (100 * 1.12) / Math.sin(Math.min(vHalf, hHalf)));
}

export async function initGlobe() {
    const canvas = document.getElementById('globeCanvas');
    if (!canvas) return;

    scene = new THREE.Scene();
    scene.background = new THREE.Color(SKY_COLOR);

    camera = new THREE.PerspectiveCamera(50, canvas.clientWidth / canvas.clientHeight, 0.1, 2000);
    camera.position.set(0, 0, 200);

    renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
    // Cap the pixel ratio — on 3x/4x-DPI screens rendering at the full
    // native ratio multiplies the pixel count for little visible gain
    // and was a real contributor to the frame-rate lag.
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    // false = leave the canvas's CSS size to the page; only the drawing buffer follows it
    renderer.setSize(canvas.clientWidth, canvas.clientHeight, false);

    // Phones: the desktop camera distance (200) crops the top and bottom of the globe in a small,
    // squarer frame - start far enough back that the whole globe fits.
    if (canvas.clientWidth < 600) camera.position.set(0, 0, fitDistance(canvas));

    // Keep the picture sharp and undistorted when the frame changes size (phone rotation,
    // window resize, the layout settling after fonts load) - it used to be sized only once.
    if (window.ResizeObserver) {
        new ResizeObserver(() => {
            const w = canvas.clientWidth, h = canvas.clientHeight;
            if (!w || !h) return;
            renderer.setSize(w, h, false);
            camera.aspect = w / h;
            camera.updateProjectionMatrix();
            renderer.render(scene, camera);
        }).observe(canvas);
    }

    // Soft fill light + a "sun" so the sphere shows a realistic lit/shaded side
    scene.add(new THREE.AmbientLight(0xffffff, 1.3));
    const sun = new THREE.DirectionalLight(0xffffff, 1.1);
    sun.position.set(-2, 1, 1);
    scene.add(sun);

    world = new ThreeGlobe()
        .globeImageUrl('/assets/earth-day.jpg')
        .showAtmosphere(true)
        .atmosphereColor('#ffffff')
        .atmosphereAltitude(0.18)
        .polygonCapColor(capColorFor)
        .polygonSideColor(sideColorFor)
        .polygonStrokeColor(strokeColorFor)
        .polygonAltitude(altitudeFor)
        .polygonsTransitionDuration(200)
        .pointLat('lat')
        .pointLng('lng')
        .pointColor('color')
        .pointAltitude(0.012)
        .pointRadius(0.45)
        .pointResolution(24)
        .pointsMerge(false)
        .pointsData([]);

    scene.add(world);

    controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = false;
    controls.rotateSpeed = 0.25;
    // GLOBE_RADIUS is hardcoded to 100 inside three-globe — going below
    // that puts the camera inside the (backface-culled) sphere, which
    // renders as a blank white screen. 118 keeps a safe margin outside
    // the atmosphere shell (radius 100 * 1.18) while still allowing a
    // close zoom on small countries.
    controls.minDistance = 118;
    controls.maxDistance = 480;

    setupClickDetection(canvas);

    try {
        const [topoResponse, manifest, geometryBuffer] = await Promise.all([
            fetch('/assets/countries-50m.json').then(r => r.json()),
            fetch('/assets/polygon-geometry-manifest.json').then(r => r.json()),
            fetch('/assets/polygon-geometry.bin').then(r => r.arrayBuffer())
        ]);
        countriesData = topojson.feature(topoResponse, topoResponse.objects.countries);
        stripDetachedSubPolygons(countriesData);
        unwrapAntimeridianRings(countriesData);

        // Only micro-states genuinely missing a polygon in this dataset need
        // the point-marker fallback — everything else renders as a real,
        // true-to-scale vector polygon.
        const featureNames = new Set(countriesData.features.map(f => f.properties.name));
        missingPolygonNames = new Set(
            Object.keys(tinyCountriesExtras).filter(name => !featureNames.has(name))
        );

        // Supplies the precomputed cap/side triangulation for every country so
        // the browser never has to build it live (~7.5s of blocking work for
        // all 241 countries otherwise) — see tools/globe-geometry-build.
        __setPrecomputedPolygonGeometry(manifest, geometryBuffer);

        world.polygonsData(countriesData.features);
    } catch (error) {
        console.error("Globe Error:", error);
    }

    animate();
}

function capColorFor(feat) {
    if (feat.properties.name === selectedMapName) return 'rgba(124,77,255,0.55)';
    return guessedCountriesColors[feat.properties.name] || 'rgba(0,0,0,0)';
}

function sideColorFor(feat) {
    if (feat.properties.name === selectedMapName) return 'rgba(124,77,255,0.3)';
    return guessedCountriesColors[feat.properties.name] ? 'rgba(0,0,0,0.25)' : 'rgba(0,0,0,0)';
}

function strokeColorFor(feat) {
    if (feat.properties.name === selectedMapName) return '#7c4dff';
    return guessedCountriesColors[feat.properties.name] ? '#222222' : 'rgba(0,0,0,0)';
}

function altitudeFor(feat) {
    if (feat.properties.name === selectedMapName) return 0.006;
    return guessedCountriesColors[feat.properties.name] ? 0.006 : 0;
}

/**
 * Click-to-select support (used by the Capital Locations globe game). The bare `three-globe`
 * package (as opposed to the higher-level `globe.gl`) has no built-in onPolygonClick - it's
 * just a THREE.Object3D meant to be dropped into your own scene, so hit-testing is done by
 * hand here: raycast against the globe to find the clicked (lat, lng) - using the exact
 * inverse of three-globe's own polar2Cartesian formula so it lines up with the rendered
 * polygons - then a standard point-in-polygon test against the same GeoJSON features
 * colorCountry() already paints finds which country was clicked.
 */
const raycaster = new THREE.Raycaster();
const pointerNDC = new THREE.Vector2();
let pointerDownPos = null;

function setupClickDetection(canvas) {
    canvas.addEventListener('pointerdown', (e) => {
        pointerDownPos = { x: e.clientX, y: e.clientY };
    });
    canvas.addEventListener('pointerup', (e) => {
        if (!pointerDownPos) return;
        const dx = e.clientX - pointerDownPos.x;
        const dy = e.clientY - pointerDownPos.y;
        pointerDownPos = null;
        // Only a near-stationary press counts as a click - anything more was a
        // drag-to-rotate gesture and must never register as a guess. A fingertip wobbles more
        // than a mouse during a tap, so touch gets a little more room.
        const maxMove = e.pointerType === 'touch' ? 12 : 6;
        if (Math.sqrt(dx * dx + dy * dy) > maxMove) return;
        handleCanvasClick(e, canvas);
    });
}

function handleCanvasClick(event, canvas) {
    if (!world || !countriesData || !camera) return;

    const rect = canvas.getBoundingClientRect();
    pointerNDC.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
    pointerNDC.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

    raycaster.setFromCamera(pointerNDC, camera);
    const intersects = raycaster.intersectObject(world, true);
    // Only accept a hit near the globe's own radius (100) - excludes the larger, semi-transparent
    // atmosphere shell (radius ~118 here) so a click through the atmosphere still resolves to
    // whatever's on the actual surface underneath it, not the shell itself.
    const surfaceHit = intersects.find(i => i.point.length() < 106);
    if (!surfaceHit) return; // clicked empty space/background, not the globe

    const { lat, lng } = cartesianToLatLon(surfaceHit.point);
    const feature = findCountryAtLatLon(lat, lng);
    if (!feature || NON_PLAYABLE_TERRITORIES.has(feature.properties.name)) return;
    if (countryClickCallback) {
        countryClickCallback(mapNameToDbName(feature.properties.name));
    }
}

/** Exact inverse of three-globe's own polar2Cartesian(lat, lng, alt) -> {x, y, z}. */
function cartesianToLatLon({ x, y, z }) {
    const r = Math.sqrt(x * x + y * y + z * z);
    const phi = Math.acos(y / r);
    const theta = Math.atan2(z, x);
    return {
        lat: 90 - phi * 180 / Math.PI,
        lng: 90 - theta * 180 / Math.PI - (theta < -Math.PI / 2 ? 360 : 0)
    };
}

function findCountryAtLatLon(lat, lng) {
    // Try the click's longitude as-is, and shifted a full turn either way, so it still lines
    // up with whichever "copy" an antimeridian-unwrapped ring (see unwrapAntimeridianRings)
    // ended up sitting at.
    for (const lngVariant of [lng, lng + 360, lng - 360]) {
        for (const feature of countriesData.features) {
            const geom = feature.geometry;
            if (!geom) continue;
            if (geom.type === 'Polygon' && pointInPolygonRings(lngVariant, lat, geom.coordinates)) return feature;
            if (geom.type === 'MultiPolygon') {
                for (const poly of geom.coordinates) {
                    if (pointInPolygonRings(lngVariant, lat, poly)) return feature;
                }
            }
        }
    }
    return null;
}

/** rings[0] is the outer boundary, any further rings are holes to subtract - standard GeoJSON. */
function pointInPolygonRings(lon, lat, rings) {
    if (!pointInRing(lon, lat, rings[0])) return false;
    for (let i = 1; i < rings.length; i++) {
        if (pointInRing(lon, lat, rings[i])) return false;
    }
    return true;
}

/** Standard even-odd ray-casting point-in-polygon test. */
function pointInRing(lon, lat, ring) {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const [xi, yi] = ring[i];
        const [xj, yj] = ring[j];
        const intersects = ((yi > lat) !== (yj > lat)) &&
            (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi);
        if (intersects) inside = !inside;
    }
    return inside;
}

/** Registers the callback fired with a DB country name whenever the player clicks the globe. */
export function onCountryClick(callback) {
    countryClickCallback = callback;
}

/** True if this country has already been colored (a guess was already made/revealed on it). */
export function isCountryColored(dbCountryName) {
    if (!dbCountryName) return false;
    const cleanName = dbCountryName.trim();
    const mapName = countryNameMapping[cleanName] || cleanName;
    return guessedCountriesColors[mapName] !== undefined;
}

/** Highlights one country as "selected but not yet confirmed" - pass null to clear it. */
export function setSelectedCountry(dbCountryName) {
    if (!dbCountryName) {
        selectedMapName = null;
    } else {
        const cleanName = dbCountryName.trim();
        selectedMapName = countryNameMapping[cleanName] || cleanName;
    }
    scheduleColorRefresh();
}

export function colorCountry(dbCountryName, hexColor) {
    if (!world || !countriesData) return;

    const cleanName = dbCountryName.trim();
    const mapName = countryNameMapping[cleanName] || cleanName;
    guessedCountriesColors[mapName] = hexColor;

    if (missingPolygonNames.has(mapName)) {
        const coords = tinyCountriesExtras[mapName];
        if (coords) {
            const existing = guessedPoints.find(p => p.name === mapName);
            if (existing) {
                existing.color = hexColor;
            } else {
                guessedPoints.push({ name: mapName, lat: coords.lat, lng: coords.lon, color: hexColor });
            }
            world.pointsData(guessedPoints);
        }
    }

    scheduleColorRefresh();
}

// Re-running the polygon layer's full digest (world.polygonsData(...)) is by
// far the most expensive part of coloring a country - it re-evaluates the
// cap/side/stroke/altitude accessors for every one of the ~350 country
// sub-polygons on the globe, not just the single one that actually changed,
// which is what made every guess feel like it "stuck" for a moment right as
// the color/zoom feedback was supposed to appear. Deferring the refresh to
// the next animation frame (and coalescing any back-to-back colorCountry()
// calls into a single refresh) lets the synchronous guess-handling code that
// runs right after colorCountry() - including the instant camera jump in
// focusOnCountry() - finish and paint first, so the globe's heavier repaint
// no longer blocks the guess from feeling immediate.
let colorRefreshScheduled = false;
function scheduleColorRefresh() {
    if (colorRefreshScheduled) return;
    colorRefreshScheduled = true;
    requestAnimationFrame(() => {
        colorRefreshScheduled = false;
        if (world && countriesData) world.polygonsData(countriesData.features);
    });
}

function animate() {
    requestAnimationFrame(animate);
    if (controls) controls.update();
    if (renderer && scene && camera) renderer.render(scene, camera);
}

export function resetGlobeColors() {
    guessedCountriesColors = {};
    guessedPoints = [];
    selectedMapName = null;
    if (world) {
        world.pointsData(guessedPoints);
        if (countriesData) world.polygonsData(countriesData.features);
    }
}

export function focusOnCountry(lat, lon) {
    if (!world || !camera) return;

    // getCoords(lat, lng, altitude) returns the point on the globe's surface
    // scaled out by (1 + altitude) globe-radii — reused directly as a close-up
    // camera position so tiny countries fill much more of the screen.
    const { x, y, z } = world.getCoords(lat, lon, 0.9);
    camera.position.set(x, y, z);
    camera.lookAt(0, 0, 0);
    if (controls) controls.update();
}
