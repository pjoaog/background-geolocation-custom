# @pjoaog/background-geolocation-custom

Custom fork of `@capacitor-community/background-geolocation` with:
- Interval-based fallback location updates (every 30s)
- Respects distanceFilter
- Works reliably in background even when the app is idle

> In test.