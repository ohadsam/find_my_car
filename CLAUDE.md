# FindMyCar - Project Context for Claude Code

## Project Overview

FindMyCar is a Hebrew-language Progressive Web App (PWA) for saving and finding car parking locations. Single-page app built with vanilla HTML/CSS/JavaScript — no build system, no frameworks.

**Live URL:** `https://ohadsam.github.io/find_my_car/`
**Repo:** `ohadsam/find_my_car`
**Main Branch:** `main` (deployed via GitHub Pages)

## Architecture

| File | Purpose |
|------|---------|
| `index.html` | Complete app HTML — all views, modals, and components inline |
| `style.css` | Full stylesheet with CSS variables, dark/light themes, RTL layout |
| `js/config.js` | Frozen `CFG` constants (versions, keys, limits, URLs) |
| `js/store.js` | `Store` — localStorage CRUD with JSON parsing |
| `js/utils.js` | `Utils` — pure helpers: uuid, formatters, Haversine, image compression, escHtml |
| `js/geocoder.js` | `reverseGeocode()` → structured `AddressObj`; `normalizeAddress()` |
| `js/map.js` | `MapController` — Leaflet map + detail mini-map lifecycle |
| `js/camera.js` | `CameraController` — getUserMedia, capture, file fallback |
| `js/voice.js` | `VoiceController` — MediaRecorder, blob URL lifecycle |
| `js/ui.js` | `UIController` — DOM rendering, modals, toasts, theme |
| `js/return-modal.js` | `ReturnModal` — auto-show return-to-car flow on app entry |
| `js/app.js` | `FindMyCarApp` orchestrator — state, events, parking lifecycle |
| `sw.js` | Service Worker — cache strategies for app shell, tiles, Leaflet CDN |
| `manifest.json` | PWA manifest — Hebrew locale, icons, shortcuts |

**Module DAG (no circular imports):** `config` → `utils/geocoder/store` → `map/camera/voice/ui/return-modal` → `app`

Loaded via `<script type="module" src="js/app.js">` with `<link rel="modulepreload">` for all modules.

## Data Model

```javascript
// Current parking (localStorage key: fmc_current_v1)
{
  id: "string (uuid)",
  timestamp: "ISO 8601 string",
  location: { lat: number, lng: number, accuracy: number },
  address: AddressObj | string | null,  // structured (v1.1+) or legacy string
  description: "string | null", // user text, max 300 chars
  photo: "data:image/jpeg;base64,... | null",   // compressed JPEG
  voice: "data:audio/webm;base64,... | null",   // MediaRecorder output
  voiceDuration: number          // seconds
}

// AddressObj (v1.1.0+) — from js/geocoder.js
{
  display:     string,       // full display string (street + number + city)
  street:      string|null,
  houseNumber: string|null,
  city:        string|null,
  neighborhood:string|null,
}

// History (localStorage key: fmc_history_v1)
// Array of parking objects (max 30), most recent first
```

## App State (`FindMyCarApp.#state`)

Private field — not accessible from console. Internal shape:

```javascript
{
  current:         Parking | null,   // active parking session
  history:         Parking[],        // past sessions (max 30)
  theme:           'dark' | 'light',
  currentView:     'homeView' | 'historyView',
  userPos:         {lat, lng, accuracy} | null,
  watchId:         number | null,    // geolocation.watchPosition id
  timerIntervalId: number | null,    // parking elapsed timer
  installPrompt:   BeforeInstallPromptEvent | null,
  activeNavTarget: Parking | null,   // for nav/detail modals
  detailItemId:    string | null,    // for detail delete
}
```

Map, camera, and voice state are now owned by their respective controllers (`#map`, `#camera`, `#voice` private fields on `FindMyCarApp`).

## Key Methods

### `js/app.js` — `FindMyCarApp` (private `#` methods)

| Method | Description |
|--------|-------------|
| `#saveNewParking()` | Get GPS → create parking object → save → geocode in bg |
| `#resetParking()` | Move current to history → clear current → update UI |
| `#geocodeCurrentParking()` | reverseGeocode → update ui + marker |
| `#openCameraModal()` | open CameraController → show photo modal |
| `#savePhoto()` | get captured photo → save to current → update UI |
| `#openVoiceModal()` | open VoiceController → show voice modal |
| `#saveVoice()` | get captured voice → save to current → update UI |
| `#saveDescription()` | read textarea → save to current → update UI |
| `#shareParking(p)` | Web Share API → clipboard fallback |
| `#showView(id)` | Switch active view + nav btn state |
| `#closeModal(id)` | Camera/voice/map cleanup + `ui.closeModal(id)` |

### `js/ui.js` — `UIController`

| Method | Description |
|--------|-------------|
| `updateAll(state)` | Refresh chip + home view + history + badge |
| `updateAddress(p)` | Two-line address: street+number / city+neighborhood |
| `buildDetailModal(item, cbs)` | Populate detail modal content |
| `showToast(msg, type)` | Floating notification (success/error/info/warning) |
| `openModal(id)` / `closeModal(id)` | Show/hide modal + body overflow |
| `showView(viewId, mapCtrl)` | Switch views, invalidate map size |

### `js/geocoder.js`

| Export | Description |
|--------|-------------|
| `reverseGeocode(lat, lng)` | Fetch Nominatim → `AddressObj \| null` |
| `normalizeAddress(addr)` | `AddressObj \| string` → display string |

## Styling Conventions

- **CSS variables** in `:root` and `[data-theme="light"]` — always use vars, not raw colors
- **RTL layout** via `direction: rtl; text-align: right` on body
- **Spacing**: 8px grid (4, 8, 12, 16, 20, 24, 32px)
- **Border radius**: sm=8, md=14, lg=20, xl=28, full=9999
- **Animation**: use `--transition` var or explicit `250ms cubic-bezier(0.4,0,0.2,1)`
- **Mobile-first**: target min-width 320px, test at 375px and 414px

## External Dependencies (CDN)

| Library | Version | Purpose |
|---------|---------|---------|
| Leaflet.js | 1.9.4 | Maps (via unpkg CDN) |
| Heebo font | latest | Hebrew-optimized typography (Google Fonts) |
| OpenStreetMap | - | Map tiles (tile.openstreetmap.org) |
| Nominatim | - | Reverse geocoding (nominatim.openstreetmap.org) |

## Development Notes

- **No build step** — edit files directly, deploy by pushing to `main`
- **GitHub Pages** serves from root of `main` branch
- **`.nojekyll`** disables Jekyll processing (required for `_` prefixed files to work)
- **Service Worker** caches app shell on install; update `CACHE_NAME` version on each deploy
- **Images** are compressed to max 900px wide, 72% JPEG quality before base64
- **localStorage limit** ~5MB; photos ~100-200KB each, voice ~50KB/min
- **iOS camera** requires HTTPS — OK on GitHub Pages; getUserMedia fails on HTTP
- **Nominatim ToS** requires meaningful user-agent; currently using default fetch headers

## Common Tasks

### Add a new feature
1. Update `index.html` with new UI elements
2. Add event bindings in `#bindEvents()` in `js/app.js`
3. Add logic methods to `FindMyCarApp` (use `#privateMethod()` style)
4. Update `style.css` for any new components
5. Update `CHANGELOG.md`
6. If adding a new module: add to `sw.js` `STATIC_ASSETS` and add `<link rel="modulepreload">` in `index.html`
7. Bump service worker `CACHE_NAME` on each deploy

### Change color scheme
Edit CSS variables in `:root` (dark) and `[data-theme="light"]` (light) blocks in `style.css`.

### Add a new modal
1. Add modal HTML in `index.html` (use existing modal structure)
2. Call `this.#ui.openModal('myModal')` / `this.#closeModal('myModal')` from app.js
3. Add backdrop `data-close="myModal"` attribute
4. Add close button with `data-close="myModal"` attribute
5. If the modal has cleanup (camera/voice/map), add a case in `#closeModal()`

### Update icons
Run: `python3 /tmp/gen_icons.py` (from repo root)

## Testing Checklist

- [ ] Save parking with GPS
- [ ] Save parking offline (GPS available)
- [ ] GPS permission denied → error handling
- [ ] Add photo via camera
- [ ] Add photo via file picker (iOS fallback)
- [ ] Add voice recording
- [ ] Add voice via file picker
- [ ] Add text description
- [ ] Update GPS location
- [ ] Navigate (Waze/Google/Apple)
- [ ] Share location
- [ ] Reset parking → moves to history
- [ ] View history items
- [ ] Open detail modal with photo/audio
- [ ] Delete history item
- [ ] Clear all history
- [ ] Dark/light theme toggle
- [ ] Install PWA banner
- [ ] Offline mode (map cached, no geocoding)
- [ ] RTL layout correct on all screens
- [ ] Return-to-car modal appears on re-entry with active parking
- [ ] "זזתי" clears parking → moved to history
- [ ] "המשך לנווט" dismisses modal, parking stays active
- [ ] Address shows two-line format (street+number / city)
