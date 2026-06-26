# CHANGELOG — FindMyCar

All notable changes to this project are documented here.  
Format: `[version] YYYY-MM-DD`

---

## [1.3.0] — 2026-06-26

### ✨ New Features

#### Version Display & What's New
- **Version tag** — Version number (e.g. `v1.3.0`) shown as a tappable pill in the header
- **What's New modal** — Automatically shown once per upgrade; also accessible by tapping the version tag
- First-install users see no popup (only returning users on upgrade)

#### Map Improvements
- **Map fix** — Removed SRI `integrity` attributes from Leaflet CDN tags that caused load failures in proxy environments
- **Map collapse/expand** — Tap the bar at the bottom of the map to hide or show it; state is persisted across sessions
- **invalidateSize on reveal** — Map tiles recalculate layout correctly after loading screen fade and after expand

#### Vehicle Parking Management
- **Clear parking per vehicle** — 🧹 button in the Vehicles settings view moves a vehicle's current parking to history without switching vehicles

### 🐛 Bug Fixes
- Deleted active vehicle no longer stays visible in the settings list after deletion
- What's New modal no longer conflicts with the return-to-car modal on upgrade

### 🏗️ Architecture
- `js/config.js` — `seenVersion` and `mapCollapsed` storage keys; `changelog` array (deeply frozen)
- `js/app.js` — `#toggleMapCollapse`, `#setMapCollapsed`, `#clearVehicleParking`, `#settingsCbs` helpers
- `js/ui.js` — `showWhatsNew(entry)` method; `renderSettingsView` now accepts `onClearParking` / `hasParking` callbacks
- `style.css` — version-tag, map-toggle-bar/btn/chevron, vehicle-parking-indicator, vehicle-clear-btn, whats-new-list; map overlay color uses `--map-overlay-bg` CSS variable

### 🔧 Service Worker
- `CACHE_NAME` bumped to `findmycar-v1.3.0`

---

## [1.2.0] — 2026-06-26

### ✨ New Features

#### Multi-Vehicle Support
- **Vehicle management** — Add, edit, and delete vehicles from a dedicated "Vehicles" settings screen (bottom nav)
- **Vehicle selector** — Horizontal chip row in the home view (shown when 2+ vehicles are configured)
- **Per-vehicle parking data** — Each vehicle maintains its own active parking and history
- **Storage migration** — On first load, existing data is automatically migrated to the default vehicle (`הרכב שלי`)
- Maximum 5 vehicles; up to 30 characters per vehicle name; 8 icon choices
- Return-to-car modal now shows the specific vehicle's name

#### WhatsApp Sharing
- **WhatsApp button** in the parking action grid
- **Content selection modal** — Choose what to include: vehicle name, address, time, description, map link, and photo
- **Smart file sharing** — If a photo is selected, uses the native `navigator.share({ files })` API for the system share sheet; falls back to `wa.me/?text=…` for text-only or when the File Share API is unavailable

#### Tests
- **33 unit tests** (Vitest + jsdom):
  - `tests/unit/utils.test.js` — UUID, escHtml, Haversine distance, dataUrlToFile
  - `tests/unit/store.test.js` — localStorage CRUD
  - `tests/unit/vehicles.test.js` — VehicleController full lifecycle
- **E2E tests** (Playwright + Chromium): `tests/e2e/app.spec.js` — smoke tests for all views and key interactions
- `package.json` with Vitest + Playwright devDependencies

### 🏗️ Architecture
- New `js/vehicles.js` module — `VehicleController` with CRUD + localStorage migration
- `js/config.js` — vehicle keys, `maxVehicles`, `maxVehicleNameLen`, `vehicleIcons`
- `js/utils.js` — added `dataUrlToFile()` for Web Share API file conversion
- `js/app.js` — vehicle state management, `#currentKey`/`#historyKey` computed getters, vehicle lifecycle methods, WhatsApp sharing
- `js/ui.js` — `renderVehicleSelector()`, `renderSettingsView()`, `populateVehicleModal()`, `getVehicleModalValues()`, `populateWhatsAppModal()`, `getWhatsAppOptions()`
- `js/return-modal.js` — `show(parking, vehicleName)` for vehicle-specific subtitle

### 🔧 Service Worker
- `CACHE_NAME` bumped to `findmycar-v1.2.0`
- `./js/vehicles.js` added to `STATIC_ASSETS`

---

## [1.1.0] — 2026-06-25

### ✨ New Features

#### Enhanced Address Detection
- **Structured geocoding** — Nominatim response parsed into `{ display, street, houseNumber, city, neighborhood }`
- **Two-line address display** — Street + house number prominent; city/neighborhood on a secondary line
- **Backward compatible** — Existing string addresses render correctly via `normalizeAddress()`

#### Return-to-Car Modal
- **Auto-shown on app entry** — Appears after loading screen if an active parking session exists
- Shows elapsed time (highlighted badge), address, photo thumbnail, description, voice indicator
- **"זזתי" button** — Archives current parking to history and clears it
- **"המשך לנווט" button** — Dismisses modal, parking session stays active

### 🏗️ Architecture — ES Module Split

Monolithic `app.js` refactored into focused ES modules under `js/`:

| Module | Responsibility |
|--------|---------------|
| `js/config.js` | Frozen `CFG` constants |
| `js/store.js` | `localStorage` wrapper |
| `js/utils.js` | Pure utilities (uuid, formatters, Haversine, image compression) |
| `js/geocoder.js` | `reverseGeocode()` → structured `AddressObj`; `normalizeAddress()` |
| `js/map.js` | `MapController` — Leaflet map + detail mini-map lifecycle |
| `js/camera.js` | `CameraController` — getUserMedia, capture, file fallback |
| `js/voice.js` | `VoiceController` — MediaRecorder, blob URL lifecycle |
| `js/ui.js` | `UIController` — DOM rendering, modals, toasts, theme |
| `js/return-modal.js` | `ReturnModal` — auto-show return-to-car flow |
| `js/app.js` | `FindMyCarApp` orchestrator — state, events, parking lifecycle |

- `<script type="module">` replaces the classic `<script>` tag
- `<link rel="modulepreload">` for all modules
- Private class fields (`#`) used throughout for encapsulation

### 🔧 Service Worker
- `CACHE_NAME` bumped to `findmycar-v1.1.0`
- `STATIC_ASSETS` updated with all `js/*.js` module paths

---

## [1.0.0] — 2026-06-25

### ✨ Initial Release

#### Core Features
- **GPS Location Saving** — One-tap save of precise GPS coordinates with automatic reverse geocoding (Hebrew address via Nominatim)
- **Interactive Map** — Leaflet.js + OpenStreetMap, shows parking marker and user's current position
- **Parking Timer** — Live elapsed time counter showing how long the car has been parked
- **Distance Calculator** — Real-time distance from user's current location to parked car (Haversine formula)

#### Media Capture
- **Photo Capture** — Camera access via `getUserMedia` with front/rear camera toggle; file picker fallback for iOS
- **Voice Recording** — `MediaRecorder` API with visual pulse animation and 5-minute limit; file picker fallback
- **Text Description** — Free-text note up to 300 characters

#### Navigation & Sharing
- **Multi-app Navigation** — Open parking location in Waze, Google Maps, or Apple Maps
- **Share Location** — Web Share API with clipboard fallback; shares address, description, time, and map link

#### History & Storage
- **Parking History** — Stores up to 30 past parking sessions with all media
- **Detail View** — Tap any history item for full details including photo, audio playback, mini-map
- **localStorage persistence** — All data stored locally, never leaves the device
- **Reset / New Parking** — Current parking archived to history before starting new session

#### PWA & UX
- **Service Worker** — Offline support with cache-first for app shell, stale-while-revalidate for map tiles
- **Installable** — `manifest.json` with icons for Android (192, 512px) and iOS (180px)
- **Install Banner** — Prompts PWA install after 3 seconds on first visit
- **Dark / Light Theme** — Toggle with instant switch, persisted in localStorage
- **RTL Layout** — Full Hebrew right-to-left support throughout
- **Safe Area Support** — Respects iPhone notch / home indicator
- **Offline Indicator** — Shows banner when map tiles fail to load

#### Design
- Dark-first design with `#060B18` background
- CSS variables for complete theming
- Glassmorphism cards with subtle borders
- Smooth 250ms animations throughout
- Bottom sheet modals with drag handle
- Touch-optimized (44px minimum tap targets)
- Heebo font for Hebrew typography

---

## Planned for v1.1.0

- [ ] Parking lot / street name disambiguation
- [ ] Multiple vehicle support
- [ ] WhatsApp direct share shortcut
- [ ] Compass bearing to parking from current position
- [ ] Export history as JSON
- [ ] Reminder notification when parked too long
- [ ] Parking cost tracker (hourly rate input)
