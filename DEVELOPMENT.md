# DEVELOPMENT.md — FindMyCar Developer Guide

## Setup

No build tools required. Open files directly or use a local server:

```bash
# Quick local server (Python)
python3 -m http.server 8080 --directory /path/to/find_my_car
# Then open http://localhost:8080

# Or use Node.js
npx serve find_my_car
```

> **Note:** Camera and microphone require HTTPS or localhost. Use `https://localhost` or test on GitHub Pages.

## File Structure

```
find_my_car/
├── index.html          ← Single HTML file: all views, modals inline
├── style.css           ← All styles; CSS variables at top; RTL layout
├── js/
│   ├── config.js       ← Frozen CFG constants
│   ├── store.js        ← localStorage CRUD (Store)
│   ├── utils.js        ← Pure helpers (Utils): uuid, formatters, Haversine, escHtml
│   ├── geocoder.js     ← reverseGeocode() → AddressObj; normalizeAddress()
│   ├── map.js          ← MapController (Leaflet map + detail mini-map)
│   ├── camera.js       ← CameraController (getUserMedia, capture, file fallback)
│   ├── voice.js        ← VoiceController (MediaRecorder, blob URL lifecycle)
│   ├── ui.js           ← UIController (DOM rendering, modals, toasts, theme)
│   ├── return-modal.js ← ReturnModal (auto-show return-to-car flow)
│   └── app.js          ← FindMyCarApp orchestrator (entry point)
├── sw.js               ← Service Worker; cache strategies
├── manifest.json       ← PWA config; icons; shortcuts
├── .nojekyll           ← Disables GitHub Pages Jekyll processing
├── icons/
│   ├── icon.svg        ← Vector icon (scalable, primary)
│   ├── icon-192.png
│   ├── icon-512.png
│   └── icon-180.png    (apple-touch-icon)
├── README.md
├── CLAUDE.md           ← Claude Code context
├── DEVELOPMENT.md      ← This file
└── CHANGELOG.md
```

## Architecture Principles

1. **Zero dependencies** — vanilla JS, no npm, no bundler
2. **ES Modules** — `import`/`export`; `<script type="module">`; no globals except `window.L` (Leaflet)
3. **Private class fields** — `#field` and `#method()` throughout for encapsulation
4. **Single orchestrator** — `FindMyCarApp` owns shared state, delegates to focused controllers
5. **Persistent state** — `CFG.keys.*` localStorage keys, versioned
6. **Graceful degradation** — every API has a fallback (GPS → error msg, camera → file input, voice → file input, share → clipboard)
7. **RTL-first** — Hebrew is the primary language; all layout is `direction: rtl`

## CSS Architecture

```css
/* Layered specificity */
:root { /* design tokens */ }
[data-theme="light"] { /* light overrides */ }
/* Component styles by section */
/* .header, .map-wrapper, .parking-card, etc. */
/* Animations at bottom */
```

**Rules:**
- Use `var(--token-name)` everywhere, never raw hex colors
- Mobile-first: base styles for mobile, `@media (min-width: X)` for desktop
- All interactive elements ≥ 44px touch target
- Use `dvh` (dynamic viewport height) not `vh` to handle mobile browser chrome
- `env(safe-area-inset-bottom)` for iPhone notch/home indicator

## JS Architecture

```
js/config.js        exports CFG (frozen config object)
js/store.js         exports Store (localStorage CRUD)
js/utils.js         exports Utils (uuid, formatters, Haversine, escHtml, el)
js/geocoder.js      exports reverseGeocode(), normalizeAddress()
js/map.js           exports MapController
js/camera.js        exports CameraController
js/voice.js         exports VoiceController
js/ui.js            exports UIController
js/return-modal.js  exports ReturnModal
js/app.js           imports all above; boots FindMyCarApp
```

**Import pattern:**
```javascript
// Each module imports only what it needs
import { CFG } from './config.js';
import { Utils } from './utils.js';
// ...

export class MyController { /* ... */ }
```

**Private fields convention:**
```javascript
class FindMyCarApp {
  #state = { current: null, history: [], /* ... */ };
  #map   = new MapController();
  // All methods are private unless called by external code
  #init()       { /* boot sequence */ }
  #bindEvents() { /* all event listeners */ }
  #saveNewParking() { /* ... */ }
}
window.addEventListener('DOMContentLoaded', () => { window.app = new FindMyCarApp(); });
```

**Leaflet usage:** Always access as `window.L` inside method bodies (not at module top-level) to avoid initialization timing issues with the CDN `<script>` tag.

## Service Worker Strategy

| Resource | Strategy | Reason |
|----------|----------|--------|
| App shell (HTML/CSS/JS) | Cache-first | Always load latest from cache |
| Leaflet CDN | Cache-first | Stable version pinned |
| Map tiles (OSM) | Stale-while-revalidate | Serve cached, update bg |
| Nominatim API | Network-only | Live data, no staleness |

**Updating SW:** Change `CACHE_NAME` version in `sw.js` to force cache invalidation on next visit.

## Adding New Features

### New Modal
```html
<!-- in index.html, before </body> -->
<div id="myModal" class="modal" role="dialog" aria-modal="true" style="display:none;">
  <div class="modal-backdrop" data-close="myModal"></div>
  <div class="modal-sheet">
    <div class="modal-handle"></div>
    <div class="modal-header">
      <h3 class="modal-title">My Modal</h3>
      <button class="modal-close-btn" data-close="myModal">✕</button>
    </div>
    <div class="modal-body">...</div>
    <div class="modal-footer">
      <button class="modal-btn secondary" data-close="myModal">ביטול</button>
      <button class="modal-btn primary" id="myModalSaveBtn">שמור</button>
    </div>
  </div>
</div>
```

```javascript
// in js/app.js #bindEvents()
Utils.el('triggerBtn').addEventListener('click', () => this.#ui.openModal('myModal'));
Utils.el('myModalSaveBtn').addEventListener('click', () => this.#handleMyModalSave());
// For cleanup on close, add a case in #closeModal():
#closeModal(id) {
  if (id === 'myModal') { /* cleanup */ }
  this.#ui.closeModal(id);
}
```

### New Data Field
1. Add to parking object in `#saveNewParking()` in `js/app.js`
2. Save via `Store.set(CFG.keys.current, this.#state.current)`
3. Display via `UIController` method (add to `js/ui.js`)
4. Render in `_renderHistoryItem()` in `js/ui.js`
5. Show in `buildDetailModal()` in `js/ui.js`

### New Storage Key
```javascript
// Add to CFG.keys in js/config.js
export const CFG = Object.freeze({
  keys: Object.freeze({
    current: 'fmc_current_v1',
    history: 'fmc_history_v1',
    theme:   'fmc_theme_v1',
    myNew:   'fmc_mynew_v1'  // ← add here
  })
});
```

### New Module
1. Create `js/mymodule.js` with `export class MyController { ... }`
2. Import in `js/app.js`: `import { MyController } from './mymodule.js';`
3. Add `<link rel="modulepreload" href="js/mymodule.js">` to `index.html`
4. Add `'./js/mymodule.js'` to `STATIC_ASSETS` in `sw.js`
5. Bump `CACHE_NAME` in `sw.js`

## Debugging

`#state` is a private field and not accessible from the console directly. Use localStorage:

```javascript
// In browser console:
JSON.parse(localStorage.getItem('fmc_current_v1'))   // current parking
JSON.parse(localStorage.getItem('fmc_history_v1'))   // history array
localStorage                                         // all keys

// Force inject a test parking (bypasses GPS):
localStorage.setItem('fmc_current_v1', JSON.stringify({
  id: 'test', timestamp: new Date().toISOString(),
  location: { lat: 32.0853, lng: 34.7818, accuracy: 10 },
  address: { display: 'דיזנגוף 50, תל אביב', street: 'דיזנגוף', houseNumber: '50', city: 'תל אביב', neighborhood: null },
  description: null, photo: null, voice: null, voiceDuration: 0
}));
location.reload(); // reload to pick up injected data
```

## Browser Compatibility

| Feature | Chrome Android | Safari iOS | Firefox Android |
|---------|---------------|------------|-----------------|
| GPS | ✅ | ✅ (HTTPS) | ✅ |
| Camera | ✅ | ✅ iOS 14.3+ | ✅ |
| Voice | ✅ | ✅ iOS 14.5+ | ✅ |
| PWA Install | ✅ | ✅ (Add to Home) | ⚠️ Limited |
| Service Worker | ✅ | ✅ | ✅ |
| Web Share | ✅ | ✅ | ✅ |

## Performance Considerations

- **Images:** Compressed to max 900px / 72% JPEG before base64 (≈100-200KB per photo)
- **Voice:** WebM/Opus ≈ 10KB/second → 5min max = ≈3MB; stored as base64
- **localStorage limit:** ~5MB typical; ~20 photos + voices would fill it → warn user if near limit
- **Map:** OSM tile cache grows unbounded; consider clearing old tiles if storage is a concern

## Deployment

Push to `main` branch → GitHub Pages auto-deploys in ~1 minute.

```bash
git add -A
git commit -m "feat: your change"
git push origin main
```

**Always bump `CACHE_NAME` in `sw.js` when changing app files**, so users get the update instead of cached old version.
