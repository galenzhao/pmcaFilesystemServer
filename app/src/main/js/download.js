const saveAs = require("file-saver").saveAs;
const JSZip = require("jszip");

function init(meta) {
  $(document).on("change", ".checkbox-download-day", function (event) {
    $(".card[data-day='" + $(this).data("day") + "'] .checkbox-download")
      .prop("checked", $(this).prop("checked"))
      .trigger("change");
  });

  $(document).on("change", ".checkbox-download", function (event) {
    if ($(".checkbox-download:checked").length > 0) {
      let size = 0;
      $(".checkbox-download:checked").each(function () {
        const el = $(this).parents(".card-link");
        size += parseInt(el.data("size"));
      });
      const count = $(".checkbox-download:checked").length;
      $("#download").text(`Download ${count} files - ${bytesToSize(size)}`);
      $("#download").show();
      $("#set-gps").text(`Set GPS (${count})`).show();
      $("#clear-gps").text(`Clear GPS (${count})`).show();
      $("#delete-media").text(`Delete (${count})`).show();
      $("#rotate-cw").show();
      $("#rotate-ccw").show();
    } else {
      $("#download").hide();
      $("#set-gps").hide();
      $("#clear-gps").hide();
      $("#delete-media").hide();
      $("#rotate-cw").hide();
      $("#rotate-ccw").hide();
    }
  });
  $("#download").hide();
  $("#set-gps").hide();
  $("#clear-gps").hide();
  $("#delete-media").hide();
  $("#rotate-cw").hide();
  $("#rotate-ccw").hide();

  $("#download").click(function () {
    if ($(".checkbox-download:checked").length === 0) {
      return;
    }
    $("#downloadSizeModel").modal("show");
  });

  $("#download-start").click(function () {
    const size = $('input[name="dl-size"]:checked').val() || "original";
    $("#downloadSizeModel").modal("hide");
    startZipDownload(meta, size);
  });
}

function startZipDownload(meta, sizePreset) {
  $("#zip-progress").css("width", "0%");
  $("#zip-log").text("");
  $("#zipModel").modal({ backdrop: "static", keyboard: false });
  const files = [];
  $(".checkbox-download:checked").each(function () {
    const el = $(this).parents(".card-link");
    files.push({
      name: el.data("name"),
      file: el.data("file"),
      href: el.data("href"),
    });
  });

  $("#zip-log").append(
    "Downloading " +
      files.length +
      " files as ZIP (" +
      sizePreset +
      ")...\n"
  );

  const zip = new JSZip();
  const promises = [];
  let startedFiles = 0;
  let finishedFiles = 0;
  for (const file of files) {
    const url =
      sizePreset === "original"
        ? file.href
        : "/api/resize.do?f=" +
          encodeURIComponent(file.file) +
          "&size=" +
          encodeURIComponent(sizePreset);
    const zipName =
      sizePreset === "original"
        ? file.name
        : renameForSize(file.name, sizePreset);
    promises.push(
      fetch(url, { credentials: "same-origin" })
        .then(function (response) {
          appendZipLog("Downloading " + zipName + "...");
          startedFiles++;
          updateZipProgress(files.length, startedFiles, finishedFiles, 0);
          if (response.status === 200 || response.status === 0) {
            return Promise.resolve(response.blob());
          }
          return Promise.reject(new Error(response.statusText));
        })
        .then(function (blob) {
          appendZipLog("Added " + zipName + " to zip");
          zip.file(zipName, blob);
          finishedFiles++;
          updateZipProgress(files.length, startedFiles, finishedFiles, 0);
        })
    );
  }
  Promise.all(promises)
    .then(function () {
      updateZipProgress(files.length, files.length, files.length, 0);
      appendZipLog("Compressing ZIP file...");
      return zip.generateAsync({ type: "blob" }, function (metadata) {
        updateZipProgress(
          files.length,
          files.length,
          files.length,
          metadata.percent
        );
      });
    })
    .then(function (blob) {
      appendZipLog("Downloading ZIP file...");
      saveAs(blob, "download-" + meta.model + "-" + sizePreset + ".zip");
      appendZipLog("Done");
      updateZipProgress(files.length, files.length, files.length, 100);
      $("#zip-progress").css("width", 100 + "%");
      setTimeout(function () {
        $("#zipModel").modal("hide");
      }, 2000);
    })
    .catch(function (err) {
      console.warn(err);
      appendZipLog("Error: " + (err && err.message ? err.message : err));
    });
}

function renameForSize(name, sizePreset) {
  const tag = sizePreset === "vga" ? "_vga" : "_2m";
  const dot = name.lastIndexOf(".");
  const stem = dot > 0 ? name.substring(0, dot) : name;
  const lower = name.toLowerCase();
  if (lower.endsWith(".arw") || lower.endsWith(".raw")) {
    return stem + tag + ".JPG";
  }
  if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
    return stem + tag + ".JPG";
  }
  return name;
}

function appendZipLog(message) {
  const el = $("#zip-log");
  el.append(message + "\n");
  el.scrollTop(el.prop("scrollHeight"));
}

function updateZipProgress(total, started, finished, compressing) {
  const percent =
    (started / total) * 0.4 +
    (finished / total) * 0.4 +
    (compressing / 100) * 0.1;
  $("#zip-progress").css("width", percent * 100 + "%");
  $("#zip-progress").addClass("progress-bar-striped progress-bar-animated");
}

function bytesToSize(bytes) {
  var sizes = ["Bytes", "KB", "MB", "GB", "TB"];
  if (bytes == 0) return "0 Byte";
  var i = parseInt(Math.floor(Math.log(bytes) / Math.log(1024)));
  return Math.round(bytes / Math.pow(1024, i), 2) + " " + sizes[i];
}

module.exports = {
  init: init,
};
