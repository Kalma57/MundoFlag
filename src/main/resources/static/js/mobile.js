// Shared phone / app helpers, loaded by every page (after bg-music.js).
//
// 1. Country autocomplete. Every "type a country" box used the browser's built-in <datalist>
//    dropdown, which looks different on every phone and on iPhone often doesn't show at all.
//    This replaces it with one suggestion list that looks and behaves the same everywhere,
//    sits right under the box (or above it, if the keyboard leaves no room below), and also
//    knows the alternative names (typing "ivory" suggests Côte d’Ivoire, "holland" suggests
//    Netherlands). Picking a suggestion only fills the box - the page's own Guess button / Enter
//    still sends it, exactly like before. The page's <datalist> stays in the HTML as the source
//    of the names, so no game page needed to change.
//
// 2. Inside the native app (Capacitor): marks <html> with .is-native-app and makes Android's
//    back button go back a page (and leave the app from the home screen) instead of closing it.
(function () {
    'use strict';

    // ------------------------------------------------------------------ name matching

    // Same idea as the server's CountryNameAliases.normalize, but keeps word breaks (for "starts a word" matches)
    function norm(s) {
        return (s || '').toLowerCase()
            .replace(/ø/g, 'o').replace(/æ/g, 'ae').replace(/œ/g, 'oe').replace(/ß/g, 'ss').replace(/ł/g, 'l')
            .normalize('NFD').replace(/[̀-ͯ]/g, '')
            .replace(/['’‘`´]/g, '')
            .replace(/&/g, ' and ')
            .replace(/[^a-z0-9]+/g, ' ')
            .split(' ').filter(w => w && w !== 'the')
            .map(w => (w === 'st' || w === 'sint') ? 'saint' : w)
            .join(' ');
    }

    let aliasesByName = null;      // { "Côte d’Ivoire": ["Ivory Coast", ...], ... }
    function loadAliases() {
        if (aliasesByName) return;
        aliasesByName = {};
        fetch('/countries/aliases').then(r => r.ok ? r.json() : {}).then(d => { aliasesByName = d || {}; })
            .catch(() => { /* suggestions still work with the official names only */ });
    }

    // 0 = the name starts with what was typed, 1 = one of its words does, 2 = it's somewhere inside
    function rank(text, q) {
        if (text.startsWith(q)) return 0;
        if ((' ' + text).includes(' ' + q)) return 1;
        if (q.length >= 3 && text.replace(/ /g, '').includes(q.replace(/ /g, ''))) return 2;
        return -1;
    }

    function suggestionsFor(typed, names) {
        const q = norm(typed);
        if (!q) return [];
        const hits = [];
        for (const name of names) {
            let best = rank(norm(name), q), via = null;
            if (best !== 0) {
                for (const alias of (aliasesByName && aliasesByName[name]) || []) {
                    const r = rank(norm(alias), q);
                    if (r !== -1 && (best === -1 || r + 0.5 < best)) { best = r + 0.5; via = alias; }
                }
            }
            if (best !== -1) hits.push({ name, via, best });
        }
        hits.sort((a, b) => a.best - b.best || a.name.localeCompare(b.name));
        return hits.slice(0, 6);
    }

    // ------------------------------------------------------------------ the dropdown

    let box = null;            // the shared suggestion list element
    let current = null;        // { input, items, active }

    function ensureBox() {
        if (box) return box;
        box = document.createElement('div');
        box.className = 'mf-suggest';
        box.setAttribute('role', 'listbox');
        box.hidden = true;
        // pointerdown/mousedown + preventDefault: the input keeps focus (and the phone keeps its keyboard up).
        // The pick itself happens on "click", NOT on pointerdown: hiding the list on pointerdown let
        // the same tap's click land on whatever was underneath - usually the GUESS button, which
        // sent the guess straight away.
        const keepFocus = e => e.preventDefault();
        box.addEventListener('pointerdown', keepFocus);
        box.addEventListener('mousedown', keepFocus);
        box.addEventListener('click', e => {
            const item = e.target.closest('.mf-suggest-item');
            if (item && current) choose(current.input, current.items[+item.dataset.i].name);
        });
        document.body.appendChild(box);
        return box;
    }

    function close() {
        if (box) box.hidden = true;
        current = null;
    }

    function place(input) {
        const r = input.getBoundingClientRect();
        const vv = window.visualViewport;
        const top = vv ? vv.offsetTop : 0;
        const bottom = vv ? vv.offsetTop + vv.height : window.innerHeight;
        const below = bottom - r.bottom - 8, above = r.top - top - 8;
        const showAbove = below < 170 && above > below;
        box.style.left = Math.max(8, r.left) + 'px';
        box.style.width = Math.min(r.width, window.innerWidth - 16) + 'px';
        box.style.maxHeight = Math.max(120, Math.min(300, showAbove ? above : below)) + 'px';
        if (showAbove) { box.style.top = 'auto'; box.style.bottom = (window.innerHeight - r.top + 4) + 'px'; }
        else { box.style.bottom = 'auto'; box.style.top = (r.bottom + 4) + 'px'; }
    }

    function render(input) {
        const listId = input.dataset.mfList;
        const dl = listId && document.getElementById(listId);
        const names = dl ? Array.from(dl.options, o => o.value).filter(Boolean) : [];
        const items = input.disabled ? [] : suggestionsFor(input.value, names);
        // nothing useful to show: no matches, or the box already holds exactly the one match
        if (!items.length || (items.length === 1 && items[0].name === input.value)) { close(); return; }

        ensureBox();
        current = { input, items, active: -1 };
        box.innerHTML = '';
        items.forEach((it, i) => {
            const row = document.createElement('div');
            row.className = 'mf-suggest-item';
            row.setAttribute('role', 'option');
            row.dataset.i = i;
            row.textContent = it.name;
            if (it.via) {
                const hint = document.createElement('span');
                hint.className = 'mf-suggest-alias';
                hint.textContent = it.via;
                row.appendChild(hint);
            }
            box.appendChild(row);
        });
        box.hidden = false;
        place(input);
    }

    function highlight(delta) {
        if (!current) return;
        // -1 = nothing highlighted; wraps around past either end
        const n = current.items.length;
        let next = current.active + delta;
        if (next >= n) next = -1;
        if (next < -1) next = n - 1;
        current.active = next;
        Array.from(box.children).forEach((el, i) => el.classList.toggle('active', i === current.active));
        const el = box.children[current.active];
        if (el) el.scrollIntoView({ block: 'nearest' });
    }

    function choose(input, name) {
        input.value = name;
        close();
        input.dispatchEvent(new Event('input', { bubbles: true }));   // pages that watch typing see the new value
        close();                                                     // ...without reopening the list
        input.focus();
    }

    function attach(input) {
        if (input.dataset.mfList) return;
        input.dataset.mfList = input.getAttribute('list');
        input.removeAttribute('list');                 // no more native dropdown on top of ours
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('autocorrect', 'off');      // iOS: don't "fix" country names
        input.setAttribute('autocapitalize', 'off');
        input.setAttribute('spellcheck', 'false');
        input.setAttribute('enterkeyhint', 'go');      // keyboard shows "Go" instead of "return"
        loadAliases();

        input.addEventListener('input', e => { if (e.isTrusted) render(input); });
        input.addEventListener('focus', () => { if (input.value) render(input); });
        input.addEventListener('blur', () => setTimeout(() => { if (current && current.input === input) close(); }, 120));
    }

    // Arrow keys move through the list; Enter on a highlighted row picks it (and is kept away from
    // the page, so it doesn't also send a guess). Enter with nothing highlighted is left alone:
    // the page sends whatever was typed, like it always did.
    window.addEventListener('keydown', e => {
        if (!current || e.target !== current.input || box.hidden) return;
        if (e.key === 'ArrowDown') { e.preventDefault(); highlight(1); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); highlight(-1); }
        else if (e.key === 'Escape') { close(); }
        else if (e.key === 'Enter' || e.key === 'Tab') {
            if (current.active >= 0) {
                e.preventDefault();
                e.stopImmediatePropagation();
                choose(current.input, current.items[current.active].name);
            } else {
                close();
            }
        }
    }, true);

    const reposition = () => { if (current) place(current.input); };
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', reposition);
        window.visualViewport.addEventListener('scroll', reposition);
    }

    function attachAll() { document.querySelectorAll('input[list]').forEach(attach); }

    // ------------------------------------------------------------------ native app (Capacitor)

    function setUpNativeApp() {
        const cap = window.Capacitor;
        if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return;
        document.documentElement.classList.add('is-native-app');
        const App = cap.Plugins && cap.Plugins.App;
        if (App && App.addListener) {
            App.addListener('backButton', () => {
                if (location.pathname === '/' || location.pathname === '') App.exitApp();
                else history.back();
            });
        }
    }

    // ------------------------------------------------------------------ anonymous visit count

    // Where this page is running: the store app, a home-screen shortcut, or a normal browser tab
    function platform() {
        const cap = window.Capacitor;
        if (cap && cap.isNativePlatform && cap.isNativePlatform()) return 'app';
        if (window.navigator.standalone || (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches)) return 'home';
        return 'web';
    }

    // At most once a day per device, tell the server "a visit happened" - with only the platform and
    // the days of this device's first and previous visit, which the device itself remembers. No ID is
    // sent or stored, so the server can count visitors and returning visitors but can't tell who is who.
    // (Server side: com.example.flagdemo.Stats. Explained on /privacy.)
    function countVisit() {
        const KEY = 'mundoflag.visits';
        const today = new Date().toISOString().slice(0, 10);    // UTC day, same as the server's
        let seen;
        try { seen = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { return; } // no storage: don't count (would double-count)
        if (seen.last === today) return;
        const body = new URLSearchParams({ p: platform(), first: seen.first || today, prev: seen.last || '' });
        try { localStorage.setItem(KEY, JSON.stringify({ first: seen.first || today, last: today })); } catch (e) { return; }
        if (navigator.sendBeacon) navigator.sendBeacon('/stats/visit', body);
        else fetch('/stats/visit', { method: 'POST', body, keepalive: true }).catch(() => {});
    }

    function countEvent(name) {
        const body = new URLSearchParams({ name });
        if (navigator.sendBeacon) navigator.sendBeacon('/stats/event', body);
        else fetch('/stats/event', { method: 'POST', body, keepalive: true }).catch(() => {});
    }

    // ------------------------------------------------------------------ sharing

    function toast(text) {
        const t = document.createElement('div');
        t.className = 'mf-toast';
        t.textContent = text;
        document.body.appendChild(t);
        setTimeout(() => t.classList.add('show'), 10);
        setTimeout(() => { t.classList.remove('show'); setTimeout(() => t.remove(), 300); }, 2200);
    }

    // Opens the phone's share sheet (WhatsApp, Messages...) - the app's native one inside the app, the
    // browser's where it has one - otherwise copies the text and says so.
    async function share(text) {
        const cap = window.Capacitor;
        const NativeShare = cap && cap.isNativePlatform && cap.isNativePlatform() && cap.Plugins && cap.Plugins.Share;
        try {
            if (NativeShare) { await NativeShare.share({ text }); return 'shared'; }
            if (navigator.share) { await navigator.share({ text }); return 'shared'; }
        } catch (e) {
            if (e && e.name === 'AbortError') return 'cancelled';     // closed the share sheet
        }
        try {
            await navigator.clipboard.writeText(text);
        } catch (e) {
            const ta = document.createElement('textarea');            // older browsers / no permission
            ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
            document.body.appendChild(ta); ta.select();
            try { document.execCommand('copy'); } catch (e2) { /* nothing more we can do */ }
            ta.remove();
        }
        toast('Copied - paste it anywhere to share');
        return 'copied';
    }

    window.MundoFlag = { share, countEvent, platform };

    function init() {
        countVisit();
        attachAll();
        // pages that build their guess box later (e.g. after a round starts) are picked up too
        new MutationObserver(attachAll).observe(document.body, { childList: true, subtree: true });
        setUpNativeApp();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
