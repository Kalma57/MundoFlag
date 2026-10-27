// Runs on the home page only. While the visitor is still choosing a game, quietly downloads
// everything the globe screens need (3D library, country shapes, earth image), so the browser
// has it cached and a globe screen only has to build the globe, not download it.
// The URLs must match the globe screens exactly (same ?v=), or the cache won't be hit.
(function () {
    // Libraries are imported (not just preloaded) because only a real import also pulls in
    // everything they import in turn, e.g. the files the external three-globe link points to.
    // Importing them only defines code - nothing is drawn on the home page.
    const LIBRARIES = ['three-globe', 'three-conic-polygon-geometry', '/controls/OrbitControls.js'];
    const MODULES = ['/js/globe.js?v=2056']; // our own globe code: download only, don't run
    const FILES = [
        '/assets/countries-50m.json',
        '/assets/polygon-geometry-manifest.json',
        '/assets/polygon-geometry.bin',
        '/js/topojson-client.min.js?v=2'
    ];
    // Loaded as an image the same way the globe loads it (crossOrigin), so its cached copy is reused.
    const IMAGES = ['/assets/earth-day.jpg'];

    function preload() {
        const conn = navigator.connection;
        if (conn && conn.saveData) return; // visitor asked to save data - don't spend it for them

        LIBRARIES.forEach(name => import(name).catch(() => { /* the game page will just load it itself */ }));
        MODULES.forEach(href => {
            const link = document.createElement('link');
            link.rel = 'modulepreload';
            link.href = href;
            document.head.appendChild(link);
        });
        IMAGES.forEach(src => {
            const img = new Image();
            img.crossOrigin = 'anonymous';
            img.src = src;
        });
        FILES.forEach(url => fetch(url, { priority: 'low' }).catch(() => { /* the game page will just load it itself */ }));
    }

    // Wait until the home page itself has finished loading, so this never slows it down.
    function whenIdle() {
        if ('requestIdleCallback' in window) requestIdleCallback(preload, { timeout: 3000 });
        else setTimeout(preload, 1000);
    }
    if (document.readyState === 'complete') whenIdle(); else window.addEventListener('load', whenIdle);
})();
