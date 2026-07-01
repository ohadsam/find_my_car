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
| `js/config.js` | Frozen `CFG` constants (versions, keys, limits, vehicleIcons, URLs) |
| `js/store.js` | `Store` — localStorage CRUD with JSON parsing |
| `js/utils.js` | `Utils` — pure helpers: uuid, formatters, Haversine, image compression, escHtml, dataUrlToFile |
| `js/geocoder.js` | `reverseGeocode()` → structured `AddressObj`; `normalizeAddress()` |
| `js/map.js` | `MapController` — Leaflet map + detail mini-map lifecycle |
| `js/camera.js` | `CameraController` — getUserMedia, capture, file fallback |
| `js/voice.js` | `VoiceController` — MediaRecorder, blob URL lifecycle |
| `js/ui.js` | `UIController` — DOM rendering, modals, toasts, theme, vehicle selector, settings view |
| `js/return-modal.js` | `ReturnModal` — auto-show return-to-car flow on app entry |
| `js/vehicles.js` | `VehicleController` — vehicle CRUD + localStorage migration |
| `js/bluetooth.js` | `BluetoothController` — device watch via `enumerateDevices` + `devicechange`; connect/disconnect callbacks |
| `js/app.js` | `FindMyCarApp` orchestrator — state, events, parking lifecycle, vehicle switching, WhatsApp sharing, Bluetooth |
| `sw.js` | Service Worker — cache strategies for app shell, tiles, Leaflet CDN |
| `manifest.json` | PWA manifest — Hebrew locale, icons, shortcuts |
| `package.json` | devDependencies: Vitest, Playwright |
| `vitest.config.js` | Unit test config (jsdom environment) |
| `playwright.config.js` | E2E test config (Chromium, Python HTTP server) |
| `tests/unit/` | Vitest unit tests for utils, store, vehicles |
| `tests/e2e/` | Playwright e2e smoke tests |

**Module DAG (no circular imports):** `config` → `utils/geocoder/store` → `map/camera/voice/ui/return-modal/vehicles/bluetooth` → `app`

Loaded via `<script type="module" src="js/app.js">` with `<link rel="modulepreload">` for all modules.

## Data Model

```javascript
// Vehicle (localStorage key: fmc_vehicles_v1 → array)
{
  id:                 "string (uuid)",
  name:               "string (max 30 chars)",
  icon:               "emoji string",
  plate:              "string (max 15 chars) | null",   // optional license plate
  color:              "string (max 20 chars) | null",   // optional vehicle color
  bluetoothDevice:    "string | null",   // device label linked via BT settings
  bluetoothAutoEnd:   boolean,           // auto-end parking on BT connect
  bluetoothAutoStart: boolean,           // auto-start parking on BT disconnect
  bluetoothStartPopup:boolean,           // show media popup after auto-start
}

// Bluetooth settings (localStorage key: fmc_bluetooth_v1)
{
  enabled: boolean   // master switch for all Bluetooth features
}

// Active vehicle ID (localStorage key: fmc_active_v1)
"vehicleId"

// Current parking per vehicle (localStorage key: fmc_cur_{vehicleId})
{
  id: "string (uuid)",
  timestamp: "ISO 8601 string",
  location: { lat: number, lng: number, accuracy: number },
  address: AddressObj | string | null,  // structured (v1.1+) or legacy string
  description: "string | null", // user text, max 300 chars
  photo: "data:image/jpeg;base64,... | null",   // compressed JPEG
  voice: "data:audio/webm;base64,... | null",   // MediaRecorder output
  voiceDuration: number,         // seconds
  btStartDevice: "string | null",  // BT device label that auto-started this parking (v1.6.1+)
  btEndDevice:   "string | null",  // BT device label that auto-ended this parking (v1.6.1+)
  btEndTime:     "ISO 8601 string | null",  // timestamp when BT end was triggered (v1.6.1+)
}

// AddressObj (v1.1.0+) — from js/geocoder.js
{
  display:     string,       // full display string (street + number + city)
  street:      string|null,
  houseNumber: string|null,
  city:        string|null,
  neighborhood:string|null,
}

// History per vehicle (localStorage key: fmc_hist_{vehicleId})
// Array of parking objects (max 30), most recent first

// Legacy keys (fmc_current_v1, fmc_history_v1) migrated on first v1.2.0 load
```

## App State (`FindMyCarApp.#state`)

Private field — not accessible from console. Internal shape:

```javascript
{
  current:         Parking | null,   // active parking session (active vehicle)
  history:         Parking[],        // past sessions (max 30, active vehicle)
  theme:           'dark' | 'light',
  currentView:     'homeView' | 'historyView' | 'settingsView',
  userPos:         {lat, lng, accuracy} | null,
  watchId:         number | null,    // geolocation.watchPosition id
  timerIntervalId: number | null,    // parking elapsed timer
  installPrompt:   BeforeInstallPromptEvent | null,
  activeNavTarget: Parking | null,   // for nav/detail modals
  detailItemId:    string | null,    // for detail delete
  vehicles:        Vehicle[],        // all vehicles
  activeVehicleId: string | null,    // currently selected vehicle id
  vehicleEditId:       string | null,    // vehicle being edited (null = creating)
  vehicleDeleteId:     string | null,    // vehicle pending delete confirm
  btPendingVehicleId:  string | null,    // vehicle waiting for BT end-parking confirm
  btPendingLabel:      string | null,    // BT device label that triggered the confirm modal
}
```

Map, camera, voice, and Bluetooth state are owned by their respective controllers (`#map`, `#camera`, `#voice`, `#bluetooth` private fields on `FindMyCarApp`).

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
| `#onBtConnected(label)` | BT connect → find matching vehicle → auto-end or show confirm modal |
| `#onBtDisconnected(label)` | BT disconnect → find matching vehicle → auto-start parking + optional popup |
| `#markBtEnd(vehicleId, label)` | Annotate current parking with BT end device + timestamp before it moves to history |
| `#btEndParking(vehicleId)` | End parking for a vehicle (active or background) |
| `#btScanDevices()` | Scan audio devices; prompt mic permission if labels hidden |
| `#openBtSettingsModal()` | Render and open `btSettingsModal` |
| `#btSettingsCbs()` | Returns `{ onToggleEnabled, onToggleVehicle, onSetAll }` callbacks |
| `#updateBtBadge()` | Update BT settings button badge (count of linked vehicles) |

### `js/ui.js` — `UIController`

| Method | Description |
|--------|-------------|
| `updateAll(state)` | Refresh chip + home view + history + badge |
| `updateAddress(p)` | Two-line address: street+number / city+neighborhood |
| `buildDetailModal(item, cbs)` | Populate detail modal content |
| `showToast(msg, type)` | Floating notification (success/error/info/warning) |
| `openModal(id)` / `closeModal(id)` | Show/hide modal + body overflow |
| `showView(viewId, mapCtrl)` | Switch views, invalidate map size |
| `setBtDeviceValue(label)` | Show linked device name in vehicle modal |
| `showBtDeviceList(devices, onSelect)` | Render scannable device list (deduped by label) |
| `showBtPermissionRequest(onRequest)` | Render mic-permission prompt in device list area |
| `renderBtSettingsModal(btSettings, vehicles, cbs)` | Render full BT settings (master switch + global + per-vehicle) |
| `updateBtSettingsBtn(linkedCount)` | Update linked-device badge on BT settings button |

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
- [ ] Bluetooth: link a device to a vehicle in vehicle settings
- [ ] Bluetooth: connect to linked device → confirm end-parking modal appears
- [ ] Bluetooth: auto-end on connect (when bluetoothAutoEnd = true)
- [ ] Bluetooth: disconnect from linked device → auto-start parking
- [ ] Bluetooth: disconnect → media popup appears (when bluetoothStartPopup = true)
- [ ] Bluetooth: master switch disables all BT features
- [ ] Bluetooth: global toggles in BT settings screen apply to all linked vehicles
- [ ] Bluetooth: per-vehicle toggles in BT settings screen work independently
- [ ] Bluetooth: unlink device from vehicle removes all auto-behavior
