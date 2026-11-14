// Shared by every match/blitz screen. The server answers with an empty reply (or, for a friend
// room, roomStatus NOT_FOUND) once it no longer has this game: it was left idle until the session
// timed out, or the server restarted. Instead of a page that silently stops responding, say so and
// reload the page's own address: vs the computer that starts a new match with the same settings,
// and for a friend room it lands on the lobby with a "room no longer exists" message.
(function () {
    let handled = false;

    function isGone(data) {
        if (data === null || data === undefined) return false;   // network hiccup, not "gone"
        if (typeof data !== 'object') return false;
        if (data.roomStatus === 'NOT_FOUND') return true;
        return Object.keys(data).length === 0;
    }

    // onGone (optional): what to do instead of reloading - e.g. the classic globe games start a new
    // game in place so the globe doesn't have to be rebuilt. Returns true if the game was gone.
    window.MundoGameGone = function (data, onGone) {
        if (handled || !isGone(data)) return false;
        handled = true;

        const note = document.createElement('div');
        note.textContent = window.location.pathname.indexOf('/pvp/') !== -1
            ? 'This room no longer exists - taking you back to the lobby...'
            : 'This game expired - starting a new one...';
        note.setAttribute('role', 'status');
        note.style.cssText = 'position:fixed;left:50%;top:20px;transform:translateX(-50%);z-index:9999;' +
            'background:#333;color:#fff;padding:12px 20px;border-radius:12px;font:600 16px Nunito,sans-serif;' +
            'box-shadow:0 8px 24px rgba(0,0,0,0.25);max-width:90vw;text-align:center;';
        document.body.appendChild(note);
        setTimeout(function () {
            if (typeof onGone === 'function') {
                note.remove();
                handled = false;   // the new game can expire again later
                onGone();
            } else {
                window.location.reload();
            }
        }, 1500);
        return true;
    };
})();
