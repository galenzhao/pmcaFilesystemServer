(function () {
  const plot = document.getElementById("map-plot");
  const side = document.getElementById("side-list");
  const status = document.getElementById("status");
  const empty = document.getElementById("map-empty");
  const title = document.getElementById("title");
  let items = [];
  let active = -1;

  fetch("/api/meta.do")
    .then(function (r) {
      return r.json();
    })
    .then(function (meta) {
      if (meta && meta.model) {
        title.textContent = meta.brand + " " + meta.model + " — GPS map";
      }
    })
    .catch(function () {});

  fetch("/api/geo.do")
    .then(function (r) {
      return r.json();
    })
    .then(function (json) {
      items = json.items || [];
      status.textContent =
        items.length +
        " geotagged still(s)" +
        (json.scanned ? " (scanned " + json.scanned + ")" : "");
      render();
    })
    .catch(function (err) {
      status.textContent = "Failed to load: " + (err && err.message ? err.message : err);
    });

  function render() {
    plot.querySelectorAll(".marker,.axis-label").forEach(function (el) {
      el.remove();
    });
    side.innerHTML = "";
    if (!items.length) {
      empty.style.display = "block";
      return;
    }
    empty.style.display = "none";

    let minLat = items[0].lat;
    let maxLat = items[0].lat;
    let minLon = items[0].lon;
    let maxLon = items[0].lon;
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (it.lat < minLat) minLat = it.lat;
      if (it.lat > maxLat) maxLat = it.lat;
      if (it.lon < minLon) minLon = it.lon;
      if (it.lon > maxLon) maxLon = it.lon;
    }
    const padLat = Math.max((maxLat - minLat) * 0.08, 0.0005);
    const padLon = Math.max((maxLon - minLon) * 0.08, 0.0005);
    minLat -= padLat;
    maxLat += padLat;
    minLon -= padLon;
    maxLon += padLon;
    if (maxLat === minLat) {
      maxLat += 0.001;
      minLat -= 0.001;
    }
    if (maxLon === minLon) {
      maxLon += 0.001;
      minLon -= 0.001;
    }

    addLabel(8, 8, maxLat.toFixed(4) + "°N");
    addLabel(8, plot.clientHeight - 20, minLat.toFixed(4) + "°N");
    addLabel(plot.clientWidth - 72, plot.clientHeight - 20, maxLon.toFixed(4) + "°E");

    for (let i = 0; i < items.length; i++) {
      placeMarker(i, items[i], minLat, maxLat, minLon, maxLon);
      addSideItem(i, items[i]);
    }
  }

  function addLabel(x, y, text) {
    const el = document.createElement("div");
    el.className = "axis-label";
    el.style.left = x + "px";
    el.style.top = y + "px";
    el.textContent = text;
    plot.appendChild(el);
  }

  function placeMarker(index, item, minLat, maxLat, minLon, maxLon) {
    const x = ((item.lon - minLon) / (maxLon - minLon)) * 100;
    const y = (1 - (item.lat - minLat) / (maxLat - minLat)) * 100;
    const el = document.createElement("div");
    el.className = "marker";
    el.style.left = x + "%";
    el.style.top = y + "%";
    el.title = item.name + "\n" + item.lat.toFixed(5) + ", " + item.lon.toFixed(5);
    el.addEventListener("click", function () {
      select(index);
    });
    plot.appendChild(el);
  }

  function addSideItem(index, item) {
    const a = document.createElement("a");
    a.href = "#";
    a.className = "d-block mb-2";
    a.textContent =
      item.name + " — " + item.lat.toFixed(5) + ", " + item.lon.toFixed(5);
    a.addEventListener("click", function (e) {
      e.preventDefault();
      select(index);
    });
    side.appendChild(a);
  }

  function select(index) {
    active = index;
    const markers = plot.querySelectorAll(".marker");
    const links = side.querySelectorAll("a");
    for (let i = 0; i < markers.length; i++) {
      markers[i].classList.toggle("active", i === index);
      if (links[i]) {
        links[i].classList.toggle("active", i === index);
      }
    }
    const item = items[index];
    if (!item) {
      return;
    }
    status.innerHTML =
      "<strong>" +
      item.name +
      "</strong> — " +
      item.lat.toFixed(6) +
      ", " +
      item.lon.toFixed(6) +
      ' · <a class="text-info" href="/gallery">Open gallery</a>' +
      ' · <a class="text-info" href="' +
      item.file +
      '" download>Download</a>';
  }

  window.addEventListener("resize", function () {
    if (items.length) {
      render();
      if (active >= 0) {
        select(active);
      }
    }
  });
})();
