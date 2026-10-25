function selectedFiles() {
  const files = [];
  $(".checkbox-download:checked").each(function () {
    const el = $(this).parents(".card-link");
    files.push(el.data("file"));
  });
  return files;
}

function postForm(base, path, form) {
  return fetch(base + path, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
      Connection: "close",
    },
    body: $.param(form),
  }).then(async function (resp) {
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
    return json;
  });
}

function summarize(json, noun) {
  let msg = "Done: " + json.ok + " ok, " + json.failed + " failed.";
  if (typeof json.writes === "number" && json.writes !== json.ok) {
    msg += " (" + json.writes + " Avindex " + noun + "(s); JPEG+RAW pairs share one call)";
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
  return msg;
}

function requireSelection(files) {
  if (!files || files.length === 0) {
    alert("Select one or more photos first.");
    return false;
  }
  return true;
}

function init(base) {
  $("#clear-gps").click(function () {
    const files = selectedFiles();
    if (!requireSelection(files)) {
      return;
    }
    clearGps(base, files, null);
  });

  $("#info-clear-gps").click(function () {
    const file = $("#infoModel").data("file");
    if (!file) {
      return;
    }
    $("#infoModel").modal("hide");
    clearGps(base, [file], null);
  });

  $("#rotate-cw").click(function () {
    rotate(base, selectedFiles(), 90);
  });
  $("#rotate-ccw").click(function () {
    rotate(base, selectedFiles(), -90);
  });
  $("#info-rotate-cw").click(function () {
    const file = $("#infoModel").data("file");
    if (!file) {
      return;
    }
    rotate(base, [file], 90);
  });
  $("#info-rotate-ccw").click(function () {
    const file = $("#infoModel").data("file");
    if (!file) {
      return;
    }
    rotate(base, [file], -90);
  });

  $("#delete-media").click(function () {
    const files = selectedFiles();
    if (!requireSelection(files)) {
      return;
    }
    openDeleteModal(files);
  });
  $("#info-delete").click(function () {
    const file = $("#infoModel").data("file");
    if (!file) {
      return;
    }
    $("#infoModel").modal("hide");
    openDeleteModal([file]);
  });

  $("#delete-confirm").click(function () {
    const files = $("#deleteModel").data("files") || [];
    const status = $("#delete-status");
    if (!files.length) {
      status.text("No files selected.");
      return;
    }
    status.text("Deleting " + files.length + " file(s)...");
    $("#delete-confirm").prop("disabled", true);
    postForm(base, "/api/delete.do", {
      files: JSON.stringify(files),
      confirm: "true",
    })
      .then(function (json) {
        status.text(summarize(json, "delete"));
        if (json.failed === 0) {
          setTimeout(function () {
            $("#deleteModel").modal("hide");
            location.reload();
          }, 900);
        }
      })
      .catch(function (err) {
        status.text("Error: " + (err && err.message ? err.message : err));
      })
      .finally(function () {
        $("#delete-confirm").prop("disabled", false);
      });
  });
}

function clearGps(base, files, statusEl) {
  if (!files || files.length === 0) {
    if (statusEl) {
      statusEl.text("No files selected.");
    } else {
      alert("Select one or more photos first.");
    }
    return Promise.reject(new Error("No files selected"));
  }
  if (statusEl) {
    statusEl.text("Clearing GPS on " + files.length + " file(s)...");
  }
  return postForm(base, "/api/gps/clear.do", { files: JSON.stringify(files) })
    .then(function (json) {
      const msg = summarize(json, "clear");
      if (statusEl) {
        statusEl.text(msg);
        if (json.failed === 0) {
          setTimeout(function () {
            $("#gpsModel").modal("hide");
            location.reload();
          }, 900);
        }
      } else {
        alert(msg);
        if (json.failed === 0) {
          location.reload();
        }
      }
      return json;
    })
    .catch(function (err) {
      const msg = "Error: " + (err && err.message ? err.message : err);
      if (statusEl) {
        statusEl.text(msg);
      } else {
        alert(msg);
      }
      throw err;
    });
}

function rotate(base, files, angle) {
  if (!requireSelection(files)) {
    return;
  }
  postForm(base, "/api/rotate.do", {
    files: JSON.stringify(files),
    angle: String(angle),
  })
    .then(function (json) {
      alert(summarize(json, "rotate"));
      if (json.failed === 0) {
        location.reload();
      }
    })
    .catch(function (err) {
      alert("Error: " + (err && err.message ? err.message : err));
    });
}

function openDeleteModal(files) {
  $("#deleteModel").data("files", files);
  $("#delete-file-count").text(files.length);
  $("#delete-status").text("Ready to delete " + files.length + " path(s).");
  $("#delete-confirm").prop("disabled", false);
  $("#deleteModel").modal("show");
}

module.exports = {
  init: init,
  clearGps: clearGps,
  selectedFiles: selectedFiles,
};
