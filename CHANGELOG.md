# CHANGELOG — FindMyCar

All notable changes to this project are documented here.  
Format: `[version] YYYY-MM-DD`

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
