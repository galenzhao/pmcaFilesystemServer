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
            "). Paste coordinates manually — common on plain HTTP."
        );
      },
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 }
    );
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
      status.text("Enter valid latitude and longitude.");
      return;
    }
    if (alt != null && isNaN(alt)) {
      status.text("Altitude must be a number or empty.");
      return;
    }

    status.text("Writing GPS to " + files.length + " file(s)...");
    $("#gps-apply").prop("disabled", true);

    // form-urlencoded + Connection:close — NanoHTTPD keep-alive + JSON body
    // previously left unread bytes that corrupted the next request line.
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
  $("#gps-status").text(
    "Uses camera Avindex GPS write (JPEG/ARW). On HTTP Wi‑Fi many browsers block geolocation — paste lat/lon if needed."
  );
  $("#gps-apply").prop("disabled", false);
  $("#gpsModel").modal("show");
}

module.exports = {
  init: init,
};
