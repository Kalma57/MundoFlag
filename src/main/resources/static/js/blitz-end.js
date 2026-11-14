// Shared by every Blitz screen: the end box lists only the items that were solved or skipped, so
// the one the player was still on when the clock ran out never had its answer shown. This adds
// that item as a line right under the recap. item = { name, capital, flagFile } from the server
// (only sent once the match is over); format says what to show:
//   'country'          - Flags / Globe:          "🇨🇦 Canada"
//   'country-capital'  - Flag -> Capital:        "Canada → Ottawa"
//   'capital-country'  - Capital -> Country and Capital Locations: "Ottawa → Canada"
(function () {
    window.MundoBlitzTimedOut = function (recapEl, item, format) {
        if (!recapEl) return;
        const old = recapEl.parentNode.querySelector('.timed-out-row');
        if (old) old.remove();
        if (!item || !item.name) return;

        let text = item.name;
        if (format === 'country-capital' && item.capital) text = item.name + ' → ' + item.capital;
        if (format === 'capital-country' && item.capital) text = item.capital + ' → ' + item.name;

        const row = document.createElement('div');
        row.className = 'timed-out-row';
        row.style.cssText = 'display:flex;align-items:center;justify-content:center;gap:10px;flex-wrap:wrap;' +
            'margin:4px 0 14px;padding:10px 14px;border-radius:14px;background:#fff8e1;color:#795548;' +
            'border:1px dashed #ffb74d;font-weight:700;font-size:15px;';

        const label = document.createElement('span');
        label.textContent = "⏱ Time ran out on:";
        row.appendChild(label);
        if (item.flagFile) {
            const img = document.createElement('img');
            img.src = '/DB/FlagsImages/' + encodeURIComponent(item.flagFile);
            img.alt = '';
            // flag images are squares with the flag in a 4:3 band - show just the band
            img.style.cssText = 'width:44px;height:33px;object-fit:cover;border-radius:4px;box-shadow:0 1px 4px rgba(0,0,0,0.2);';
            row.appendChild(img);
        }
        const name = document.createElement('span');
        name.style.color = '#4e342e';
        name.textContent = text;
        row.appendChild(name);

        recapEl.parentNode.insertBefore(row, recapEl.nextSibling);
    };
})();
