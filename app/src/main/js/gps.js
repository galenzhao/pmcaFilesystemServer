function init(base) {
  $("#set-gps").click(function () {
    const files = selectedFiles();
    if (files.length === 0) {
      alert("Select one or more photos first.");
      return;
    }
    openGpsModal(files);
  });

  $("#info-set-gps").click(function () {
    const file = $("#infoModel").data("file");
    if (!file) {
      return;
    }
    $("#infoModel").modal("hide");
    openGpsModal([file]);
  });

  $(document).on("click", ".btn-day-gps", function (event) {
    event.preventDefault();
    event.stopPropagation();
    const dayId = $(this).data("day");
    const files = [];
    $('.card-link[data-day="' + dayId + '"]').each(function () {
      files.push($(this).data("file"));
    });
    if (files.length === 0) {
      alert("No photos for this day.");
      return;
    }
    openGpsModal(files);
  });

  $("#gps-use-browser").click(function () {
    const status = $("#gps-status");
    status.text("Requesting browser location...");
    if (!navigator.geolocation) {
      status.text("Geolocation is not available in this browser. Enter coordinates manually.");
      return;
    }
    navigator.geolocation.getCurrentPosition(
      function (pos) {
        $("#gps-lat").val(pos.coords.latitude.toFixed(6));
        $("#gps-lon").val(pos.coords.longitude.toFixed(6));
        if (pos.coords.altitude != null && !isNaN(pos.coords.altitude)) {
          $("#gps-alt").val(pos.coords.altitude.toFixed(1));
        }
        status.text(
          "Got location (±" +
            Math.round(pos.coords.accuracy || 0) +
            " m). HTTP pages may block this — use manual entry if it fails."
        );
      },
      function (err) {
        status.text(
          "Browser location failed (" +
            (err && err.message ? err.message : "denied") +
            "). Paste a Maps link or coordinates."
        );
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
    );
  });

  $("#gps-parse-url").click(function () {
    applyMapsPaste($("#gps-maps-url").val(), $("#gps-status"));
  });

  $("#gps-maps-url").on("keydown", function (e) {
    if (e.key === "Enter") {
      e.preventDefault();
      applyMapsPaste($(this).val(), $("#gps-status"));
    }
  });

  $("#gps-clear").click(function () {
    const files = $("#gpsModel").data("files") || [];
    const status = $("#gps-status");
    if (!files.length) {
      status.text("No files selected.");
      return;
    }
    status.text("Clearing GPS on " + files.length + " file(s)...");
    $("#gps-clear").prop("disabled", true);
    $("#gps-apply").prop("disabled", true);
    require("./media-ops")
      .clearGps(base, files, status)
      .finally(function () {
        $("#gps-clear").prop("disabled", false);
        $("#gps-apply").prop("disabled", false);
      });
  });

  $("#gps-apply").click(function () {
    const lat = parseFloat($("#gps-lat").val());
    const lon = parseFloat($("#gps-lon").val());
    const altRaw = $("#gps-alt").val();
    const alt = altRaw === "" ? null : parseFloat(altRaw);
    const files = $("#gpsModel").data("files") || [];
    const status = $("#gps-status");

    if (!files.length) {
      status.text("No files selected.");
      return;
    }
    if (isNaN(lat) || isNaN(lon)) {
      status.text("Enter valid latitude and longitude (or parse a Maps link).");
      return;
    }
    if (alt != null && isNaN(alt)) {
      status.text("Altitude must be a number or empty.");
      return;
    }

    status.text("Writing GPS to " + files.length + " file(s)...");
    $("#gps-apply").prop("disabled", true);

    const form = { lat: String(lat), lon: String(lon), files: JSON.stringify(files) };
    if (alt != null) {
      form.alt = String(alt);
    }

    fetch(base + "/api/gps.do", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        Connection: "close",
      },
      body: $.param(form),
    })
      .then(async function (resp) {
        const text = await resp.text();
        let json;
        try {
          json = JSON.parse(text);
        } catch (e) {
          throw new Error(text || resp.statusText);
        }
        if (!resp.ok) {
          throw new Error(json.error || text || resp.statusText);
        }
        let msg = "Done: " + json.ok + " ok, " + json.failed + " failed.";
        if (typeof json.writes === "number" && json.writes !== json.ok) {
          msg += " (" + json.writes + " Avindex write(s); JPEG+RAW pairs share one write)";
        }
        if (json.results) {
          const errors = [];
          for (let i = 0; i < json.results.length; i++) {
            const r = json.results[i];
            if (!r.success) {
              errors.push((r.file || "") + ": " + (r.error || "error"));
            }
          }
          if (errors.length) {
            msg += "\n" + errors.join("\n");
          }
        }
        status.text(msg);
        if (json.failed === 0) {
          setTimeout(function () {
            $("#gpsModel").modal("hide");
          }, 1200);
        }
      })
      .catch(function (err) {
        status.text("Error: " + (err && err.message ? err.message : err));
      })
      .finally(function () {
        $("#gps-apply").prop("disabled", false);
      });
  });
}

function selectedFiles() {
  const files = [];
  $(".checkbox-download:checked").each(function () {
    const el = $(this).parents(".card-link");
    files.push(el.data("file"));
  });
  return files;
}

function openGpsModal(files) {
  $("#gpsModel").data("files", files);
  $("#gps-file-count").text(files.length);
  $("#gps-maps-url").val("");
  $("#gps-status").text(
    "Uses camera Avindex GPS write (JPEG/ARW). Paste a Maps link or lat,lon — geolocation often blocked on HTTP."
  );
  $("#gps-apply").prop("disabled", false);
  $("#gpsModel").modal("show");
}

function applyMapsPaste(raw, status) {
  const parsed = parseLatLon(raw);
  if (!parsed) {
    status.text(
      "Could not parse. Try a Google/Apple/OSM link, or plain \"lat, lon\" (e.g. 31.2304, 121.4737)."
    );
    return;
  }
  $("#gps-lat").val(parsed.lat.toFixed(6));
  $("#gps-lon").val(parsed.lon.toFixed(6));
  status.text("Parsed " + parsed.lat.toFixed(6) + ", " + parsed.lon.toFixed(6)
    + (parsed.source ? " (" + parsed.source + ")" : ""));
}

/**
 * Parse Google Maps / Apple Maps / OSM / plain "lat,lon" pastes.
 */
function parseLatLon(raw) {
  if (raw == null) {
    return null;
  }
  const text = String(raw).trim();
  if (!text) {
    return null;
  }

  let m = text.match(/@(-?\d+\.\d+),(-?\d+\.\d+)/);
  if (m) {
    return validate(m[1], m[2], "maps @");
  }

  m = text.match(/[?&](?:q|query|ll)=(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)/i);
  if (m) {
    return validate(m[1], m[2], "q/ll");
  }

  m = text.match(/[?&]mlat=(-?\d+\.\d+).*?[?&#]mlon=(-?\d+\.\d+)/i);
  if (m) {
    return validate(m[1], m[2], "osm mlat");
  }

  m = text.match(/#map=\d+\/(-?\d+\.\d+)\/(-?\d+\.\d+)/);
  if (m) {
    return validate(m[1], m[2], "osm hash");
  }

  m = text.match(/!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)/);
  if (m) {
    return validate(m[1], m[2], "google 3d/4d");
  }

  m = text.match(/(-?\d{1,3}\.\d+)\s*[,;\s]\s*(-?\d{1,3}\.\d+)/);
  if (m) {
    return validate(m[1], m[2], "plain");
  }

  return null;
}

function validate(latStr, lonStr, source) {
  const lat = parseFloat(latStr);
  const lon = parseFloat(lonStr);
  if (isNaN(lat) || isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    return null;
  }
  return { lat: lat, lon: lon, source: source };
}

module.exports = {
  init: init,
  parseLatLon: parseLatLon,
};
