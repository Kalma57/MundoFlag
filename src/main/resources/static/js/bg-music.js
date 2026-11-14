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

    const audio = new Audio();
    // Nothing is downloaded until the music is actually about to play (the first tap on a page),
    // so the 2 MB song never competes with the game itself while a page is loading on a phone.
    audio.preload = 'none';
    audio.volume = VOLUME;

    function load(index, startAt) {
        state.track = index % TRACKS.length;
        audio.src = TRACKS[state.track];
        if (startAt > 0) {
            audio.addEventListener('loadedmetadata', () => { audio.currentTime = startAt; }, { once: true });
        }
    }

    function save() {
        const time = audio.src ? (audio.currentTime || 0) : state.time;
        write(sessionStorage, STATE_KEY, JSON.stringify({ track: state.track, time: time }));
    }

    function tryPlay() {
        if (muted) return;
        if (!audio.src) load(state.track, state.time);
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

    // Phone / app: stop the music while the app is in the background (home button, another app,
    // screen locked) and pick it up again on return - otherwise it keeps playing over everything.
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) { save(); audio.pause(); }
        else tryPlay();
    });
    setInterval(save, 2000);

    // Small mute/unmute button in the corner.
    function buildButton() {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.id = 'bg-music-toggle';
        // env(safe-area-inset-*) keeps it clear of the iPhone home bar / rounded corners (0 elsewhere)
        btn.style.cssText = 'position:fixed;bottom:calc(16px + env(safe-area-inset-bottom, 0px));' +
            'left:calc(16px + env(safe-area-inset-left, 0px));z-index:9999;width:44px;height:44px;' +
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

    // Music already playing on the previous page of this tab: carry on right away (the song is
    // already in the browser's cache by then). A first visit waits for the first tap instead.
    if (state.time > 0) tryPlay();
    if (document.body) buildButton(); else document.addEventListener('DOMContentLoaded', buildButton);
})();
