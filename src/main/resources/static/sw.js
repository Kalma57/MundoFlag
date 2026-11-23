// MundoFlag service worker: keeps the game's heavy, rarely-changing files on the device, so the
// globe - and the Daily Quiz, whose loading screen waits for the globe - opens without downloading
// them again. Registered by /js/mobile.js once a page has finished loading.
//
// Only the files matched by KEEP_ON_DEVICE are ever answered from the device. Pages, guesses, rooms,
// the Daily Quiz and everything else always go to the server exactly as before.

// Bump this whenever one of the kept files changes WITHOUT its ?v= changing (for example a new
// /assets/polygon-geometry.bin): devices then drop every old copy and download fresh ones.
const VERSION = 'mundoflag-static-v1';

// Downloaded once, in the background, right after the first visit (~7.5 MB). The ?v= values must
// match the pages' own links (src/test/.../ServiceWorkerTest checks this).
const PRECACHE = [
  '/assets/polygon-geometry.bin',
  '/assets/polygon-geometry-manifest.json',
  '/assets/countries-50m.json',
  '/assets/earth-day.jpg',
  '/three/three.module.js?v=2',
  '/js/three-globe.bundle.js?v=1',
  '/js/topojson-client.min.js?v=2',
  '/controls/OrbitControls.js',
];

// Requests answered from the device first (and saved there the first time they're downloaded)
const KEEP_ON_DEVICE = [
  /^\/assets\//,
  /^\/three\//,
  /^\/fonts\//,
  /^\/controls\//,
  /^\/js\/three-globe\.bundle\.js$/,
  /^\/js\/topojson-client\.min\.js$/,
];

// First time this version is seen: download the list into a fresh "drawer" (cache) named VERSION.
// If any download fails, this version simply isn't installed and the site keeps working as before.
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(VERSION)
      .then(cache => cache.addAll(PRECACHE))
      .then(() => self.skipWaiting()));      // take over right away instead of on the next visit
});

// Once installed: throw away drawers left by older versions, and start handling already-open pages.
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(names => Promise.all(names.filter(name => name !== VERSION).map(name => caches.delete(name))))
      .then(() => self.clients.claim()));
});

// Every request a page makes passes through here.
self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;   // not ours: network as usual
  if (!KEEP_ON_DEVICE.some(rule => rule.test(url.pathname))) return;              // pages, guesses...: network

  event.respondWith(caches.open(VERSION).then(async cache => {
    const saved = await cache.match(request);
    if (saved) return saved;                                // already on the device: no download
    const fresh = await fetch(request);                     // not yet: download it once...
    if (fresh.ok) cache.put(request, fresh.clone());        // ...and keep it for next time
    return fresh;
  }));
});
