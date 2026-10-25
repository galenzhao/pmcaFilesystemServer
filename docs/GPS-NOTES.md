# NEX-5R GPS / Geotag Notes

Context from firmware dump analysis (`fwtool.py` / `outDir`) and the gallery **Set GPS** feature in this app.

## Camera: NEX-5R has no GPS hardware

Dump confirmed model `NEX-5R` in `Backup.bin`. Official specs: Wi‑Fi / NFC only, **no GPS chip**.

Firmware still contains a shared MediaTek GPS stack (platform reuse with GPS-capable bodies):

| Artifact | Meaning |
|----------|---------|
| `libgps_rsrc.so`, `libmnl.so`, `mnl_process.elf` | MTK GPS daemon / resource layer |
| `/setting/gps/assistme.dat` | EPO **assist data**, not photo coordinates |
| empty `gps/` dir on flash | no assist data ever used on this body |
| UI strings “GPS Settings / On/Off” | shared UI; gated by capability |
| `device.gps.supported` / `MprCtrl::IsGpsSupport` | reads `BKID_MPR_SYS_GPS_TYPE` → **NOT** on NEX-5R |
| `WifiSettings.apk` present, no GpsSettings app | Android side matches “Wi‑Fi yes, GPS no” |

**Conclusion:** software leftovers ≠ hardware. Onboard geotag at shutter time is not available without injection or post-processing.

## How stock firmware writes GPS at capture (GPS bodies)

```
MTK chip (/dev/ttyAM1)
  → mnl_process
  → GpsRsrcMainNotifyPosFromMnl(gps2api_posdata)
  → DefObjCamera PARAMID_GPS_*
  → pvt_UpdateStillShootingGpsInfo
  → MprIf::SetStillGpsInfo(bool, GpsInfo*)
  → WriteStill / EXIF (JPEG + ARW)

Video: MovieRecEtcGpsMgr (same live GpsInfo stream)
```

OpenMemories Java `Gps` (`com.sony.scalar.sysutil.didep.Gps`) is **status-only** (power / fix listener). Camera Remote API has **no** `setGPS`.

Decompiled **Smart Remote Control** (`com.sony.imaging.app.srctrl`) also does **not** write GPS into photos; it only optionally shows GPS status UI (`isGpsSupported()` hardcodes `false` in that APK). Historical PlayMemories “Location Information” used a private phone↔camera path into native still write — download service ended Aug 2025.

## Approaches that do / don’t work

### Config file with lat/lon → auto geotag at shoot

**Does not work.** `/setting/gps` holds assist ephemeris, not EXIF coordinates.

### At-capture inject `SetStillGpsInfo`

Needs native / MCMD / reverse PMCA protocol. Not what this app does.

### Practical (what this app does)

**Post-hoc geotag** of stills already on the card via Sony’s Avindex / MPR stack while the camera runs this PMCA HTTP server.

---

## Feature: Set GPS in `/gallery`

Version: `0.1.3-SNAPSHOT`  
Debug APK: `app/build/outputs/apk/debug/pmcaFilesystemServer-0.1.3-SNAPSHOT-debug.apk`

### Behaviour

1. Select photos (checkboxes) or open detail → **Set GPS**
2. **Use browser location** and/or paste lat/lon (/ optional alt)
3. **Write GPS** → `POST /api/gps.do` → firmware `AvindexStore.Images.Media.updateGps`

Verified on NEX-5R: JPEG and ARW both receive EXIF GPS (checked with `exiftool` on the SD card).

### Exact firmware call (device-verified)

```text
AvindexStore.Images.Media.updateGps(
    ContentResolver cr,
    Uri baseUri,   // content://…/images/media   (no trailing /id)
    long origId,   // row _id
    ContentValues values
) → int            // 0 = fail; non-zero = ok (observed 1 for JPEG-only, 5 for JPEG+RAW row)
```

Internal path:

1. `getUniqId(cr, baseUri, origId)` → e.g. `avindex://1000/00000001-default/00000006-…`
2. `AvindexGpsParam` filled from ContentValues keys (`GPSLatitude`, `GPSLatitudeRef`, …)
3. `InfraScalarMprWrapper.updateGps(uniqId, param.toArray())` (native; class may be hidden from Java)

**Not** `AvindexStore.updateGps` on the outer class — that overload does not exist on this firmware.

Avindex index columns (lowercase): `_data`, `dcf_folder_number`, `dcf_file_number`, `has_gps_info`, `latitude`, `longitude`, `exist_jpeg`, `exist_raw`.  
`_data` is an Avindex URI, **not** `/mnt/sdcard/DCIM/...`.

Latitude/longitude in the DB after write are fixed-point ×1e7 (e.g. `310000000` = 31.0°).

### JPEG + RAW = one Avindex row

Same DCF folder/file number (e.g. `100MSDCF` / `DSC01002`) is a **single** media row with `exist_jpeg=1` and `exist_raw=1`. One `updateGps` writes GPS into **both** files.

The app therefore **dedupes** a multi-select of `DSC01002.JPG` + `DSC01002.ARW` to one write (prefers JPEG as the primary path). Response includes `writes` (actual Avindex calls) vs `ok` (selected paths covered).

If only the JPG path is written, the sibling ARW still gets GPS (confirmed on card). Looking up by `.ARW` filename alone used to fail MediaManager name match; resolve now also matches DCF number / JPG aliases.

### Backend

| Class | Role |
|-------|------|
| `AvindexGpsWriter` | Resolve still → Uri/`_id`, build GPS `ContentValues`, call `Images.Media.updateGps` |
| `AvindexApiProbe` | Once at server start: log API signatures / column names / `EXTERNAL_CONTENT_URI` |
| `GpsExifWriter` | **Read-only** for `/api/exif.do` preview |
| `HttpServer` | `POST /api/gps.do`, DCF dedupe, auth, media path checks |
| `Logger` / main menu **Debug log** | Optional SD `LOG.TXT` (default on; toggle Off when done) |

### HTTP API

`POST /api/gps.do` with `Content-Type: application/x-www-form-urlencoded` (preferred; NanoHTTPD keep-alive + raw JSON bodies previously corrupted the next request line):

| Field | Meaning |
|-------|---------|
| `lat`, `lon` | Required |
| `alt` | Optional |
| `files` | JSON array string of absolute paths, e.g. `["/mnt/sdcard/DCIM/100MSDCF/DSC01002.JPG"]` |

Raw JSON body is still accepted as fallback. Responses use `Connection: close`. Server drains POST bodies before auth early-returns (avoids keep-alive pollution).

Example response fields: `ok`, `failed`, `writes`, `engine: "avindex"`, per-file `success`, `deduped`, `primary`, `note`.

### Frontend

- `app/src/main/js/gps.js` → bundled `assets/js/main.js`
- Rebuild: `cd app && npm run build` (uses `NODE_OPTIONS=--openssl-legacy-provider`)

### Logging

- Path tried: `/mnt/sdcard/DCIM/PMCAFS/LOG.TXT` (and aliases under `/storage/sdcard0`, …)
- Main menu → **Debug log** → On/Off (persisted). Off = no more `LOG.TXT` writes.
- Useful lines: `AvindexApiProbe:`, `AvindexGpsWriter.write`, `updateGps =>`, `snapshot BEFORE/AFTER`, `HttpServer.setGps`

### Limitations / gotchas

| Issue | Note |
|-------|------|
| HTTP + geolocation | Camera Wi‑Fi is plain HTTP; browsers often block geolocation. Paste lat/lon. |
| Stills only | JPEG + ARW via Avindex; MP4/MTS rejected. |
| Must be indexed | Unindexed paths → “Not in Avindex”. Refresh uses `waitAndUpdateDatabase`. |
| Camera clock | Unset clock → `GPSDateStamp` like `1970:01:01`; coords still correct. |
| `GPSTimeStamp` format | Firmware wants `H/1:M/1:S/1` (colons), not comma-separated. |
| Auth | HTTP Basic; form POST uses `credentials: "same-origin"`. |
| Debug APK install | `adb install -r -t …-debug.apk` (Android Debug cert). |

### Build

```bash
cd app && npm install && npm run build
export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"   # or Zulu 8
cd .. && ./gradlew assembleDebug
```

### Device verification checklist

1. Install debug APK (`adb install -r -t`).
2. Leave **Debug log** On for first runs; open `/gallery`.
3. Confirm `LOG.TXT` shows `Images$Media.updateGps(ContentResolver, Uri, long, ContentValues)`.
4. Set GPS on one JPEG → `ok: 1`, `updateGps =>` non-zero; pull file / `exiftool -GPS:all`.
5. Set GPS on JPEG+ARW pair → `writes: 1`, both files show GPS on PC.
6. ARW-only selection should resolve via DCF / JPG alias (after current build).
7. Turn **Debug log** Off when stable.

### Possible next steps

- [x] Clear GPS (`deleteGps`) — see [MEDIA-OPS-NOTES.md](MEDIA-OPS-NOTES.md)
- [x] Paste Maps share URL / plain lat,lon
- [x] Batch by day (day header Set GPS)
- [ ] Research movie URIs with `updateGps` (likely still-only)
- [ ] Long-term: at-capture `SetStillGpsInfo` injection

## Related firmware dump paths

- Model: `outDir/nflasha2_unpacked/Backup.bin` (`NEX-5R`)
- Avindex odex: `outDir/nflasha9_unpacked_unpacked/framework/com.sony.scalar.database_library.odex`
- Native: `outDir/nflasha9_unpacked_unpacked/lib/libavindex.so` (`updateGpsNative(String,[B)I`)
- GPS libs: `outDir/nflasha8_unpacked_unpacked/lib/libgps_rsrc.so`, `libmpr.so`
