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
| `app.js` | All logic: `CFG` config, `Store` storage utils, `Utils` helpers, `FindMyCarApp` class |
| `sw.js` | Service Worker — cache strategies for app shell, tiles, Leaflet CDN |
| `manifest.json` | PWA manifest — Hebrew locale, icons, shortcuts |

## Data Model

```javascript
// Current parking (localStorage key: fmc_current_v1)
{
  id: "string (uuid)",
  timestamp: "ISO 8601 string",
  location: { lat: number, lng: number, accuracy: number },
  address: "string | null",     // from Nominatim reverse geocode
  description: "string | null", // user text, max 300 chars
  photo: "data:image/jpeg;base64,... | null",   // compressed JPEG
  voice: "data:audio/webm;base64,... | null",   // MediaRecorder output
  voiceDuration: number          // seconds
}

// History (localStorage key: fmc_history_v1)
// Array of parking objects (max 30), most recent first
```

## App State (`FindMyCarApp.state`)

```javascript
{
  current:     Parking | null,  // active parking session
  history:     Parking[],       // past sessions (max 30)
  theme:       'dark' | 'light',
  currentView: 'homeView' | 'historyView',
  map:         Leaflet.Map | null,
  parkMarker:  Leaflet.Marker | null,
  userMarker:  Leaflet.Marker | null,
  userPos:     {lat, lng, accuracy} | null,
  watchId:     number | null,   // geolocation.watchPosition id
  cameraStream: MediaStream | null,
  capturedPhoto: dataUrl | null,
  capturedVoice: dataUrl | null,
  isRecording:   boolean,
  timerIntervalId: number | null,  // parking elapsed timer
  installPrompt: BeforeInstallPromptEvent | null,
  detailItemId: string | null    // for detail modal
}
```

## Key Methods

| Method | Description |
|--------|-------------|
| `_saveNewParking()` | Get GPS → create parking object → save → geocode in bg |
| `_resetParking()` | Move current to history → clear current → update UI |
| `_reverseGeocode(lat, lng)` | Fetch Nominatim → return Hebrew address string |
| `_openCameraModal()` | Start camera stream → show photo modal |
| `_capturePhoto()` | Draw video to canvas → compress JPEG → show preview |
| `_startRecording()` | Get mic → MediaRecorder → collect chunks |
| `_stopRecording()` | Stop recorder → create blob → update UI |
| `_updateUI()` | Refresh status chip + home view + history |
| `_showView(id)` | Switch active view + nav btn state |
| `showToast(msg, type)` | Floating notification (success/error/info/warning) |

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
2. Add event bindings in `_bindEvents()`
3. Add logic methods to `FindMyCarApp`
4. Update `style.css` for any new components
5. Update `CHANGELOG.md`
6. Bump service worker `CACHE_NAME` if static assets changed

### Change color scheme
Edit CSS variables in `:root` (dark) and `[data-theme="light"]` (light) blocks in `style.css`.

### Add a new modal
1. Add modal HTML in `index.html` (use existing modal structure)
2. Add `_openModal('myModal')` / `_closeModal('myModal')` calls
3. Add backdrop `data-close="myModal"` attribute
4. Add close button with `data-close="myModal"` attribute

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
