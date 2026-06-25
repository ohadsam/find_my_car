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
├── index.html      ← Single HTML file: all views, modals inline
├── style.css       ← All styles; CSS variables at top; RTL layout
├── app.js          ← All JS logic; class-based; zero dependencies
├── sw.js           ← Service Worker; cache strategies
├── manifest.json   ← PWA config; icons; shortcuts
├── .nojekyll       ← Disables GitHub Pages Jekyll processing
├── icons/
│   ├── icon.svg    ← Vector icon (scalable, primary)
│   ├── icon-192.png
│   ├── icon-512.png
│   └── icon-180.png (apple-touch-icon)
├── README.md
├── CLAUDE.md       ← Claude Code context
├── DEVELOPMENT.md  ← This file
└── CHANGELOG.md
```

## Architecture Principles

1. **Zero dependencies** — vanilla JS, no npm, no bundler
2. **Single class** — `FindMyCarApp` owns all state and methods
3. **Persistent state** — `CFG.keys.*` localStorage keys, versioned
4. **Graceful degradation** — every API has a fallback (GPS → error msg, camera → file input, voice → file input, share → clipboard)
5. **RTL-first** — Hebrew is the primary language; all layout is `direction: rtl`

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

```javascript
// Module: CFG — readonly config object
const CFG = { version, keys, maxHistory, ... };

// Module: Store — localStorage CRUD with JSON parsing
const Store = { get(k,def), set(k,v), remove(k) };

// Module: Utils — pure helper functions
const Utils = { uuid, formatTime, formatDate, formatElapsed,
                formatDuration, distance, formatDistance,
                compressImage, blobToBase64, el };

// Main: FindMyCarApp — everything else
class FindMyCarApp {
  constructor() { /* state init */ }
  _init()        { /* boot sequence */ }
  _bindEvents()  { /* all event listeners */ }
  // ... feature methods
}
window.app = new FindMyCarApp(); // singleton
```

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
// in _bindEvents()
Utils.el('triggerBtn').addEventListener('click', () => this._openModal('myModal'));
Utils.el('myModalSaveBtn').addEventListener('click', () => this._handleMyModalSave());
```

### New Data Field
1. Add to parking object in `_saveNewParking()`
2. Save via `Store.set(CFG.keys.current, this.state.current)`
3. Display in `_populateParkingCard()`
4. Render in `_renderHistoryItem()`
5. Show in `_openDetailModal()`

### New Storage Key
```javascript
// Add to CFG.keys
const CFG = {
  keys: {
    current: 'fmc_current_v1',
    history: 'fmc_history_v1',
    theme:   'fmc_theme_v1',
    myNew:   'fmc_mynew_v1'  // ← add here
  }
};
```

## Debugging

```javascript
// In browser console:
app.state              // full app state
app.state.current      // current parking
app.state.history      // history array
Store.get('fmc_current_v1')  // raw stored data
localStorage           // all keys

// Force save parking (bypasses GPS in dev)
app.state.current = {
  id: 'test', timestamp: new Date().toISOString(),
  location: { lat: 32.0853, lng: 34.7818, accuracy: 10 },
  address: 'Tel Aviv', description: null, photo: null, voice: null, voiceDuration: 0
};
Store.set('fmc_current_v1', app.state.current);
app._updateUI();
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
