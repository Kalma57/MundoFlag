// Background music shared by every page: alternates track1 -> track2 -> track1 ... for as long as the
// visitor stays on the site. Each page is a full reload, so the current track + position are saved in
// sessionStorage and picked up by the next page. The mute choice is remembered in localStorage.
(function () {
    const TRACKS = ['/audio/track1.mp3', '/audio/track2.mp3'];
    const VOLUME = 0.35;
    const STATE_KEY = 'mundoflag.music.state';   // {track, time} - per tab, cleared when the tab closes
    const MUTED_KEY = 'mundoflag.music.muted';   // "1" / "0" - remembered across visits

    function read(storage, key) { try { return storage.getItem(key); } catch (e) { return null; } }
    function write(storage, key, value) { try { storage.setItem(key, value); } catch (e) { /* storage blocked */ } }

    let state = { track: 0, time: 0 };
    try { state = Object.assign(state, JSON.parse(read(sessionStorage, STATE_KEY)) || {}); } catch (e) { /* bad JSON */ }
    let muted = read(localStorage, MUTED_KEY) === '1';

    // Position the new page still has to jump to. Until that jump happens, audio.currentTime
    // reads 0, so saving it then would wipe the real position and restart the song.
    let pendingTime = null;

    const audio = new Audio();
    audio.preload = 'auto';
    audio.volume = VOLUME;

    function load(index, startAt) {
        state.track = index % TRACKS.length;
        audio.src = TRACKS[state.track];
        pendingTime = startAt > 0 ? startAt : null;
        if (pendingTime !== null) {
            audio.addEventListener('loadedmetadata', () => {
                audio.currentTime = pendingTime;
                pendingTime = null;
            }, { once: true });
        }
    }

    function save() {
        const time = pendingTime !== null ? pendingTime : (audio.currentTime || 0);
        write(sessionStorage, STATE_KEY, JSON.stringify({ track: state.track, time: time }));
    }

    function tryPlay() {
        if (muted) return;
        audio.play().catch(() => { /* blocked until the first click/key - see unlock below */ });
    }

    // When a song ends, move to the other one.
    audio.addEventListener('ended', () => { load(state.track + 1, 0); tryPlay(); });

    // Browsers block sound until the visitor interacts with the page, so start on the first interaction.
    function unlock() {
        tryPlay();
        ['pointerdown', 'keydown', 'touchstart'].forEach(ev => document.removeEventListener(ev, unlock, true));
    }
    ['pointerdown', 'keydown', 'touchstart'].forEach(ev => document.addEventListener(ev, unlock, true));

    // Save the position before leaving the page (pagehide also fires on mobile / bfcache).
    window.addEventListener('pagehide', save);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') save(); });
    setInterval(save, 1000);

    // Small mute/unmute button in the corner.
    function buildButton() {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.id = 'bg-music-toggle';
        btn.style.cssText = 'position:fixed;bottom:16px;left:16px;z-index:9999;width:44px;height:44px;' +
            'border-radius:50%;border:none;cursor:pointer;font-size:20px;line-height:44px;padding:0;' +
            'background:rgba(0,0,0,0.55);color:#fff;box-shadow:0 2px 8px rgba(0,0,0,0.3);';
        const render = () => {
            btn.textContent = muted ? '🔇' : '🔊';
            btn.title = muted ? 'Turn music on' : 'Turn music off';
            btn.setAttribute('aria-label', btn.title);
        };
        btn.addEventListener('click', () => {
            muted = !muted;
            write(localStorage, MUTED_KEY, muted ? '1' : '0');
            if (muted) audio.pause(); else tryPlay();
            render();
        });
        render();
        document.body.appendChild(btn);
    }

    load(state.track, state.time);
    tryPlay(); // works right away if the browser already allows sound on this site
    if (document.body) buildButton(); else document.addEventListener('DOMContentLoaded', buildButton);
})();
