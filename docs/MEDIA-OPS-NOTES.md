# Avindex still ops (clear GPS / rotate / delete)

Companion to [GPS-NOTES.md](GPS-NOTES.md). Same firmware bridge (`AvindexStore.Images.Media` → `InfraScalarMprWrapper` / `libavindex.so`) as Set GPS.

Version: `0.1.6-SNAPSHOT`

## APIs

| Op | HTTP | Firmware |
|----|------|----------|
| Clear GPS | `POST /api/gps/clear.do` `files=` | `Images.Media.deleteGps(cr, baseUri, long)` → `int` (0 = fail) |
| Rotate | `POST /api/rotate.do` `files=` + `angle=` (±90 / 180) | `rotateImage(cr, baseUri, long, relativeAngle)` → `boolean` |
| Delete | `POST /api/delete.do` `files=` + `confirm=true` | `deleteImage(cr, baseUri, long)` → `boolean` |

Relative angle codes (firmware):

| UI degrees | Constant | Value |
|------------|----------|-------|
| +90 | `RELATIVE_ANGLE_PLUS_90` | 0 |
| +180 | `RELATIVE_ANGLE_PLUS_180` | 1 |
| −90 | `RELATIVE_ANGLE_MINUS_90` | 3 |

JPEG+RAW with the same DCF number share **one** Avindex row — the server dedupes selections (prefers JPEG as primary), same as Set GPS.

## Quirks (verify on device)

1. **Protected stills** — `deleteImage` may return `false` with little detail. Surface the error; do not force FS delete.
2. **After delete** — gallery should reload; card paths for the pair disappear together.
3. **Rotate** — orientation is written through Avindex (not only EXIF Orientation tag). Confirm thumbs/EXIF after rotate.
4. **Clear GPS** — pair of Set GPS; ARW+JPEG clear with one call.
5. **Video** — rejected (still-only Avindex APIs).
6. **Multi-delete API** — `deleteMultiImage` + observer exists but is unused; loop of `deleteImage` per DCF group is enough and simpler.

## Status strip (`GET /api/meta.do`)

Extra fields when available:

- `firmware`, `serial` (DeviceInfo reflection)
- `batteryPercent` (`ACTION_BATTERY_CHANGED`)
- `freeBytes` / `freeSpace` (`StatFs` on external storage; Avindex `getAvailableSize` fallback)
- `cameraClock` (`TimeUtil.getCurrentCalendar` if present, else Android clock) — **read-only** on NEX-5R

## Connect QR (camera screen)

Wi‑Fi / Wi‑Fi Direct server screens show a QR encoding:

`http://user:pass@host:8080/gallery`

(Inspired by Smart Remote’s connect QR; content is HTTP Basic URL instead of `W01:S:…` Wi‑Fi fields.)

## Gallery extras (0.1.5+)

| Feature | How |
|---------|-----|
| Paste Maps URL | GPS modal → Parse (Google `@lat,lon`, `q=`, Apple `ll=`, OSM `mlat`/`#map`, or plain `lat, lon`) |
| Set GPS by day | Day header **Set GPS** selects that day’s stills |
| Download size | ZIP flow: Original / ~2MP / VGA via `GET /api/resize.do?f=&size=` |
| Thumbs | `thumbnail.do` uses EXIF/embedded first; Avindex screennail only as slow fallback |
| GPS map | `/map` + `GET /api/geo.do` — offline lat/lon scatter (no tiles) |
| Protect (read) | EXIF modal shows Avindex `DCF_TBLProtectInfo` (write not available) |
| Name search | Gallery sidebar filters cards by filename |

Resize notes: RAW without sibling JPG and video always download original. 2M/VGA on RAW+JPEG uses the JPG sibling and names `*_2m.JPG` / `*_vga.JPG`.

## Device checklist

1. Clear GPS on a geotagged JPEG → EXIF GPS gone; sibling ARW cleared if paired.
2. Rotate +90 twice → upright again; both JPG and ARW if paired.
3. Delete one still → Avindex row gone; protect a file and confirm failure message.
4. Meta shows battery / free space on gallery sidebar.
5. Scan QR from phone → gallery with auth (browser must accept userinfo in URL).
6. Paste a Maps share link → lat/lon filled; day **Set GPS** writes the whole day.
7. ZIP download at 2M — phone gets smaller JPEGs; thumbs load via Avindex when available.
