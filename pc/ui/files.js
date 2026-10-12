/* Eclipse — file browser shared by the PC window, the browser page and the
   phone app. Each one gives an "adapter" that knows how to reach the files:

   adapter = {
     devices()               -> Promise<[{id, name, kind: "pc"|"phone", online}]>
     roots(dev)              -> Promise<{roots: [{name, path, kind}]}>
     list(dev, path)         -> Promise<{path, parent, entries: [{name, dir, size, mtime}], more}>
     download(dev, path, entry, ui)  starts a download (ui.transfer for progress)
     open(dev, path, entry)  optional: show the file
     upload(dev, dir, files, ui)     optional: send File objects (browser, PC)
     pick(dev, dir, ui)      optional: the phone's own file picker
     zip(dev, path)          optional: download a folder as .zip
     sep(dev)                path separator ("\\" for a Windows PC)
     empty                   text when there is no device
   }
   No optional chaining (old Android WebViews). */
(function () {
  "use strict";

  var ICONS = {
    folder: '<path d="M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z" fill="currentColor" opacity=".9"/>',
    file: '<path d="M7 3h7l5 5v13H7z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M14 3v5h5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>',
    image: '<rect x="3.5" y="5" width="17" height="14" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="9" cy="10" r="1.8" fill="currentColor"/><path d="M5 17l4.5-4.5 3 3 2.5-2.5L19.5 17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>',
    video: '<rect x="3.5" y="6" width="12" height="12" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M15.5 10.5l5-3v9l-5-3z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>',
    audio: '<path d="M9 17V6l10-2v11" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><circle cx="6.5" cy="17" r="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="16.5" cy="15" r="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/>',
    archive: '<rect x="4" y="4" width="16" height="16" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M12 4v3m0 2v2m0 2v2" stroke="currentColor" stroke-width="1.8"/>',
    doc: '<path d="M7 3h7l5 5v13H7z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M14 3v5h5M10 13h6M10 17h6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    app: '<rect x="4" y="4" width="16" height="16" rx="4" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M9 12h6M12 9v6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    disk: '<rect x="3.5" y="6" width="17" height="12" rx="3" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="16.5" cy="12" r="1.3" fill="currentColor"/>',
    home: '<path d="M4 11l8-6.5 8 6.5M6.5 9.5V19h11V9.5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>',
    pc: '<rect x="3" y="4.5" width="18" height="12" rx="2" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M9 20h6M12 16.5V20" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    phone: '<rect x="7" y="3" width="10" height="18" rx="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M11 18h2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
    up: '<path d="M12 19V6M6 11l6-6 6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    down: '<path d="M12 5v13M6 13l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    send: '<path d="M12 19V7M7 11.5L12 6.5l5 5M5 4.5h14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    refresh: '<path d="M19 12a7 7 0 1 1-2.1-5M19 4.5V8h-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    open: '<path d="M14 4h6v6M20 4l-9 9M18 14v5H5V6h5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    zip: '<path d="M12 5v10M7.5 10.5L12 15l4.5-4.5M5 19h14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    x: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    search: '<circle cx="11" cy="11" r="6" fill="none" stroke="currentColor" stroke-width="2"/><path d="M15.5 15.5L20 20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    chev: '<path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
  };
  function icon(name, cls) {
    return '<svg class="' + (cls || "") + '" viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[name] || ICONS.file) + "</svg>";
  }
  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  var EXT = {
    image: "jpg jpeg png gif webp heic heif bmp tif tiff svg avif raw dng cr2 nef arw",
    video: "mp4 mkv mov avi webm m4v 3gp wmv flv mpg mpeg ts",
    audio: "mp3 wav flac ogg m4a aac opus wma",
    archive: "zip rar 7z tar gz bz2 xz tgz iso",
    doc: "pdf doc docx odt txt md rtf xls xlsx ods csv ppt pptx odp json xml log ini html htm",
    app: "exe msi apk deb appimage bat sh"
  };
  function kindOf(name) {
    var i = name.lastIndexOf(".");
    if (i < 0) return "file";
    var e = name.slice(i + 1).toLowerCase();
    for (var k in EXT) if ((" " + EXT[k] + " ").indexOf(" " + e + " ") >= 0) return k;
    return "file";
  }
  function size(n) {
    if (n < 1024) return n + " o";
    var u = ["Ko", "Mo", "Go", "To"], i = -1;
    do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
    return (n >= 100 ? Math.round(n) : n.toFixed(1).replace(".", ",")) + " " + u[i];
  }
  function date(ms) {
    if (!ms) return "";
    var d = new Date(ms), now = new Date();
    var p = function (x) { return (x < 10 ? "0" : "") + x; };
    if (d.toDateString() === now.toDateString()) return "Aujourd'hui " + p(d.getHours()) + ":" + p(d.getMinutes());
    return p(d.getDate()) + "/" + p(d.getMonth() + 1) + "/" + d.getFullYear();
  }
  function el(html) {
    var t = document.createElement("template");
    t.innerHTML = html.trim();
    return t.content.firstChild;
  }

  function Browser(root, adapter, opts) {
    this.root = root;
    this.a = adapter;
    this.opts = opts || {};
    this.dev = null;      // selected device
    this.devs = [];
    this.path = null;     // null: the device's roots
    this.data = null;
    this.filter = "";
    this.hidden = false;
    this.sort = "name";
    this.seq = 0;
    this.transfers = {};
    this.build();
  }

  Browser.prototype.build = function () {
    var self = this;
    this.root.classList.add("fx");
    this.root.innerHTML =
      '<div class="fx-devs" role="tablist"></div>' +
      '<div class="fx-bar">' +
      '  <button class="fx-ibtn fx-up" title="Dossier parent" aria-label="Dossier parent">' + icon("up") + "</button>" +
      '  <div class="fx-crumbs"></div>' +
      '  <button class="fx-ibtn fx-reload" title="Actualiser" aria-label="Actualiser">' + icon("refresh") + "</button>" +
      "</div>" +
      '<div class="fx-tools">' +
      '  <label class="fx-search">' + icon("search") + '<input type="search" placeholder="Rechercher dans ce dossier" autocomplete="off" spellcheck="false"></label>' +
      '  <select class="fx-sort" aria-label="Trier"><option value="name">Nom</option><option value="date">Date</option><option value="size">Taille</option></select>' +
      '  <button class="fx-btn fx-send">' + icon("send") + "<span>Envoyer ici</span></button>" +
      '  <button class="fx-btn fx-zip" hidden>' + icon("zip") + "<span>Tout le dossier (.zip)</span></button>" +
      '  <input type="file" class="fx-file" multiple hidden>' +
      "</div>" +
      '<div class="fx-msg" hidden></div>' +
      '<ul class="fx-list"></ul>' +
      '<label class="fx-hidden"><input type="checkbox"> Afficher les fichiers cachés</label>' +
      '<div class="fx-drop" hidden><div>' + icon("send") + "<strong>Dépose les fichiers pour les envoyer ici</strong></div></div>" +
      '<div class="fx-sheet" hidden></div>' +
      '<div class="fx-transfers"></div>';
    var q = function (s) { return self.root.querySelector(s); };
    this.$devs = q(".fx-devs");
    this.$crumbs = q(".fx-crumbs");
    this.$list = q(".fx-list");
    this.$msg = q(".fx-msg");
    this.$send = q(".fx-send");
    this.$zip = q(".fx-zip");
    this.$file = q(".fx-file");
    this.$sheet = q(".fx-sheet");
    this.$tr = q(".fx-transfers");
    this.$up = q(".fx-up");
    this.$tools = q(".fx-tools");
    this.$hiddenRow = q(".fx-hidden");
    q(".fx-reload").onclick = function () { self.load(); };
    this.$up.onclick = function () { self.goUp(); };
    var search = q(".fx-search input");
    search.oninput = function () { self.filter = search.value.toLowerCase(); self.render(); };
    this.$search = search;
    var sort = q(".fx-sort");
    sort.onchange = function () { self.sort = sort.value; self.render(); };
    q(".fx-hidden input").onchange = function (e) { self.hidden = e.target.checked; self.render(); };
    this.$send.onclick = function () {
      if (self.a.pick) self.a.pick(self.dev, self.path, self.ui());
      else self.$file.click();
    };
    this.$file.onchange = function () {
      var files = Array.prototype.slice.call(self.$file.files || []);
      self.$file.value = "";
      if (files.length) self.a.upload(self.dev, self.path, files, self.ui());
    };
    this.$zip.onclick = function () { if (self.a.zip) self.a.zip(self.dev, self.path); };
    if (this.a.upload) this.dragDrop();
    this.$sheet.onclick = function (e) { if (e.target === self.$sheet) self.closeSheet(); };
  };

  Browser.prototype.dragDrop = function () {
    var self = this, drop = this.root.querySelector(".fx-drop"), depth = 0;
    var can = function () { return self.dev && self.path; };
    this.root.addEventListener("dragenter", function (e) {
      if (!can() || !e.dataTransfer || Array.prototype.indexOf.call(e.dataTransfer.types, "Files") < 0) return;
      depth++; drop.hidden = false; e.preventDefault();
    });
    this.root.addEventListener("dragleave", function () { if (--depth <= 0) { depth = 0; drop.hidden = true; } });
    this.root.addEventListener("dragover", function (e) { if (can()) e.preventDefault(); });
    this.root.addEventListener("drop", function (e) {
      depth = 0; drop.hidden = true;
      if (!can()) return;
      e.preventDefault();
      var files = Array.prototype.slice.call(e.dataTransfer.files || []);
      if (files.length) self.a.upload(self.dev, self.path, files, self.ui());
    });
  };

  // ui: what an adapter uses to show transfers and messages.
  Browser.prototype.ui = function () {
    var self = this;
    return {
      transfer: function (t) { self.transfer(t); },
      toast: function (msg, bad) { if (self.opts.toast) self.opts.toast(msg, bad); },
      refresh: function (dev, dir) { if (self.dev && self.dev.id === dev.id && self.path === dir) self.load(true); },
      device: function () { return self.dev; },
      path: function () { return self.path; }
    };
  };

  // t = {id, name, dir: "down"|"up", pct (0..100, -1 unknown), state: "run"|"done"|"error", error, action: {label, run}}
  Browser.prototype.transfer = function (t) {
    var self = this, row = this.transfers[t.id];
    if (!row) {
      row = el('<div class="fx-tr"><span class="fx-tr-ico"></span><div class="fx-tr-txt"><b></b><span></span><div class="fx-tr-bar"><i></i></div></div><button class="fx-ibtn fx-tr-x" aria-label="Fermer">' + icon("x") + "</button></div>");
      row.querySelector(".fx-tr-x").onclick = function () { row.remove(); delete self.transfers[t.id]; };
      this.transfers[t.id] = row;
      this.$tr.appendChild(row);
    }
    row.querySelector(".fx-tr-ico").innerHTML = icon(t.dir === "up" ? "send" : "down");
    row.querySelector("b").textContent = t.name || "";
    var info = row.querySelector(".fx-tr-txt span"), bar = row.querySelector(".fx-tr-bar");
    row.classList.toggle("fx-bad", t.state === "error");
    row.classList.toggle("fx-ok", t.state === "done");
    var old = row.querySelector(".fx-tr-act");
    if (old) old.remove();
    if (t.state === "error") {
      info.textContent = t.error || "Échec";
      bar.hidden = true;
    } else if (t.state === "done") {
      info.textContent = t.doneText || (t.dir === "up" ? "Envoyé" : "Téléchargé");
      bar.hidden = true;
      if (t.action) {
        var b = el('<button class="fx-btn sm fx-tr-act"></button>');
        b.textContent = t.action.label;
        b.onclick = t.action.run;
        row.insertBefore(b, row.querySelector(".fx-tr-x"));
      }
      setTimeout(function () { if (row.parentNode && !t.action) { row.remove(); delete self.transfers[t.id]; } }, 6000);
    } else {
      bar.hidden = false;
      bar.classList.toggle("indet", !(t.pct >= 0));
      bar.firstChild.style.width = (t.pct >= 0 ? t.pct : 30) + "%";
      info.textContent = (t.dir === "up" ? "Envoi" : "Téléchargement") + (t.pct >= 0 ? " — " + Math.floor(t.pct) + " %" : "…");
    }
  };

  // ---- devices ----
  Browser.prototype.start = function () {
    var self = this;
    return this.a.devices().then(function (ds) {
      self.devs = ds || [];
      var keep = self.dev && self.devs.filter(function (d) { return d.id === self.dev.id; })[0];
      self.renderDevs();
      if (keep) {
        self.dev = keep;
        self.markDev();
        return;
      }
      var first = self.devs.filter(function (d) { return d.online; })[0] || self.devs[0];
      if (first) self.pick(first);
      else self.showEmpty();
    }, function (e) {
      self.message(e && e.message ? e.message : "Impossible de lister les appareils.");
    });
  };

  Browser.prototype.renderDevs = function () {
    var self = this;
    this.$devs.innerHTML = "";
    this.$devs.hidden = this.devs.length < 2 && !this.opts.alwaysDevices;
    this.devs.forEach(function (d) {
      var b = el('<button class="fx-dev" role="tab">' + icon(d.kind === "phone" ? "phone" : "pc") + "<span></span><i></i></button>");
      b.querySelector("span").textContent = d.name;
      b.classList.toggle("off", !d.online);
      b.title = d.online ? d.name : d.name + " — pas joignable pour l'instant";
      b.onclick = function () { self.pick(d); };
      b._dev = d;
      self.$devs.appendChild(b);
    });
    this.markDev();
  };

  Browser.prototype.markDev = function () {
    var self = this;
    Array.prototype.forEach.call(this.$devs.children, function (b) {
      b.classList.toggle("on", !!self.dev && b._dev.id === self.dev.id);
    });
  };

  Browser.prototype.pick = function (d) {
    this.dev = d;
    this.path = null;
    this.markDev();
    this.load();
  };

  Browser.prototype.showEmpty = function () {
    this.dev = null;
    this.$crumbs.innerHTML = "";
    this.$list.innerHTML = "";
    this.$tools.hidden = true;
    this.$up.disabled = true;
    this.message(this.a.empty || "Aucun appareil pour l'instant.", true);
  };

  Browser.prototype.message = function (text, soft) {
    this.$msg.hidden = !text;
    this.$msg.textContent = text || "";
    this.$msg.classList.toggle("soft", !!soft);
  };

  // ---- folders ----
  Browser.prototype.open = function (path) {
    this.path = path;
    this.$search.value = "";
    this.filter = "";
    this.load();
  };

  Browser.prototype.goUp = function () {
    var self = this;
    if (this.path === null) return;
    var isRoot = (this.roots || []).some(function (r) { return r.path === self.path; });
    var parent = this.data && this.data.path === this.path ? this.data.parent : "";
    this.open(isRoot || !parent ? null : parent);
  };

  Browser.prototype.load = function (quiet) {
    var self = this, my = ++this.seq, dev = this.dev;
    if (!dev) return;
    if (!quiet) {
      this.$list.innerHTML = '<li class="fx-wait"><span class="fx-spin"></span>Chargement…</li>';
      this.message("");
    }
    var p = this.path === null ? this.a.roots(dev) : this.a.list(dev, this.path);
    p.then(function (data) {
      if (my !== self.seq) return;
      self.message("");
      if (self.path === null) { self.roots = data.roots || []; self.data = null; }
      else self.data = data;
      self.render();
    }, function (e) {
      if (my !== self.seq) return;
      self.$list.innerHTML = "";
      self.message(e && e.message ? e.message : "Impossible d'ouvrir ce dossier.");
      self.renderCrumbs();
    });
  };

  Browser.prototype.sepOf = function () {
    return this.a.sep ? this.a.sep(this.dev, this.path) : "/";
  };

  Browser.prototype.join = function (dir, name) {
    var s = this.sepOf();
    return dir.charAt(dir.length - 1) === s ? dir + name : dir + s + name;
  };

  Browser.prototype.renderCrumbs = function () {
    var self = this;
    this.$crumbs.innerHTML = "";
    var add = function (label, path, ico) {
      var b = el('<button class="fx-crumb">' + (ico ? icon(ico) : "") + "<span></span></button>");
      b.querySelector("span").textContent = label;
      b.onclick = function () { self.open(path); };
      if (self.$crumbs.children.length) self.$crumbs.appendChild(el('<svg class="fx-sep" viewBox="0 0 24 24">' + ICONS.chev + "</svg>"));
      self.$crumbs.appendChild(b);
    };
    add(this.dev ? this.dev.name : "", null, this.dev && this.dev.kind === "phone" ? "phone" : "pc");
    if (this.path === null) return;
    // The deepest root containing the path is shown as one step.
    var best = null;
    (this.roots || []).forEach(function (r) {
      if (self.path.indexOf(r.path) === 0 && (!best || r.path.length > best.path.length)) best = r;
    });
    var s = this.sepOf(), start = best ? best.path : "", rest = this.path.slice(start.length);
    if (best) add(best.name, best.path);
    var acc = start;
    rest.split(s).forEach(function (part) {
      if (!part) return;
      acc = acc ? self.join(acc, part) : (s === "/" ? "/" + part : part + s);
      add(part, acc);
    });
    this.$crumbs.scrollLeft = 99999;
  };

  Browser.prototype.render = function () {
    var self = this;
    this.renderCrumbs();
    this.$tools.hidden = this.path === null;
    this.$hiddenRow.hidden = this.path === null;
    this.$up.disabled = this.path === null;
    this.$send.hidden = !(this.a.upload || this.a.pick);
    this.$zip.hidden = !(this.a.zip && this.dev && this.dev.kind === "pc" && this.path !== null);
    this.$list.innerHTML = "";
    if (this.path === null) {
      if (!this.roots || !this.roots.length) { this.message("Rien à afficher sur cet appareil.", true); return; }
      this.roots.forEach(function (r) {
        var li = el('<li class="fx-row fx-root">' + icon(r.kind === "disk" ? "disk" : r.kind === "home" ? "home" : "folder", "fx-ico " + (r.kind === "folder" ? "k-folder" : "k-disk")) +
          '<div class="fx-name"><b></b><span></span></div>' + icon("chev", "fx-go") + "</li>");
        li.querySelector("b").textContent = r.name;
        li.querySelector("span").textContent = r.path;
        li.onclick = function () { self.open(r.path); };
        self.$list.appendChild(li);
      });
      return;
    }
    var es = (this.data && this.data.entries) || [];
    var shown = es.filter(function (e) {
      if (!self.hidden && (e.name.charAt(0) === "." || /^(desktop\.ini|thumbs\.db|\$recycle\.bin|system volume information)$/i.test(e.name))) return false;
      return !self.filter || e.name.toLowerCase().indexOf(self.filter) >= 0;
    });
    shown.sort(function (a, b) {
      if (a.dir !== b.dir) return a.dir ? -1 : 1;
      if (self.sort === "date") return (b.mtime || 0) - (a.mtime || 0);
      if (self.sort === "size" && !a.dir) return (b.size || 0) - (a.size || 0);
      return a.name.toLowerCase() < b.name.toLowerCase() ? -1 : a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0;
    });
    if (!shown.length) {
      this.message(this.filter ? "Aucun fichier ne correspond." : "Ce dossier est vide.", true);
    }
    var frag = document.createDocumentFragment(), max = 1500;
    shown.slice(0, max).forEach(function (e) {
      var k = e.dir ? "folder" : kindOf(e.name);
      var li = el('<li class="fx-row">' + icon(k, "fx-ico k-" + k) +
        '<div class="fx-name"><b></b><span></span></div>' +
        (e.dir ? icon("chev", "fx-go") : '<button class="fx-ibtn fx-dl" title="Télécharger" aria-label="Télécharger">' + icon("down") + "</button>") + "</li>");
      li.querySelector("b").textContent = e.name;
      li.querySelector("span").textContent = e.dir ? date(e.mtime) : size(e.size || 0) + (e.mtime ? " · " + date(e.mtime) : "");
      var full = self.join(self.path, e.name);
      if (e.dir) li.onclick = function () { self.open(full); };
      else {
        li.onclick = function () { self.fileSheet(e, full); };
        li.querySelector(".fx-dl").onclick = function (ev) { ev.stopPropagation(); self.a.download(self.dev, full, e, self.ui()); };
      }
      frag.appendChild(li);
    });
    this.$list.appendChild(frag);
    if (shown.length > max || (this.data && this.data.more)) {
      this.$list.appendChild(el('<li class="fx-more">Ce dossier contient beaucoup de fichiers : utilise la recherche pour trouver le bon.</li>'));
    }
  };

  Browser.prototype.fileSheet = function (e, full) {
    var self = this, k = kindOf(e.name);
    this.$sheet.innerHTML = "";
    var card = el('<div class="fx-card"><div class="fx-card-head">' + icon(k, "fx-ico big k-" + k) + '<div><b></b><span></span></div></div><div class="fx-card-acts"></div></div>');
    card.querySelector("b").textContent = e.name;
    card.querySelector("span").textContent = size(e.size || 0) + (e.mtime ? " · modifié le " + date(e.mtime) : "");
    var acts = card.querySelector(".fx-card-acts");
    var btn = function (label, ico, primary, run) {
      var b = el('<button class="fx-btn' + (primary ? " primary" : "") + '">' + icon(ico) + "<span></span></button>");
      b.querySelector("span").textContent = label;
      b.onclick = function () { self.closeSheet(); run(); };
      acts.appendChild(b);
    };
    btn(this.opts.downloadLabel || "Télécharger", "down", true, function () { self.a.download(self.dev, full, e, self.ui()); });
    if (this.a.open) btn("Ouvrir", "open", false, function () { self.a.open(self.dev, full, e, self.ui()); });
    btn("Fermer", "x", false, function () {});
    this.$sheet.appendChild(card);
    this.$sheet.hidden = false;
  };

  Browser.prototype.closeSheet = function () { this.$sheet.hidden = true; };

  // ---- helpers for adapters reaching an HTTP server ----

  // xhrUpload sends one file with progress; resolves with the JSON answer.
  function xhrUpload(url, file, headers, onPct) {
    return new Promise(function (resolve, reject) {
      var x = new XMLHttpRequest();
      x.open("POST", url);
      for (var h in headers) x.setRequestHeader(h, headers[h]);
      x.upload.onprogress = function (e) { if (e.lengthComputable) onPct(e.loaded * 100 / e.total); };
      x.onload = function () {
        var j = null;
        try { j = JSON.parse(x.responseText); } catch (e) { /* not JSON */ }
        if (x.status >= 200 && x.status < 300) resolve(j || {});
        else reject(new Error((j && j.error) || "Erreur " + x.status));
      };
      x.onerror = function () { reject(new Error("Connexion perdue")); };
      x.send(file);
    });
  }

  // uploadAll: one file after the other, each with its own progress row.
  function uploadAll(files, ui, send) {
    var i = 0;
    var next = function () {
      if (i >= files.length) return Promise.resolve();
      var f = files[i++], id = "up" + Date.now() + Math.random();
      ui.transfer({ id: id, name: f.name, dir: "up", pct: 0, state: "run" });
      return send(f, function (p) { ui.transfer({ id: id, name: f.name, dir: "up", pct: p, state: "run" }); }).then(function (j) {
        var t = { id: id, name: f.name, dir: "up", state: "done" };
        if (j && j.name && j.name !== f.name) t.doneText = "Envoyé (enregistré sous « " + j.name + " »)";
        ui.transfer(t);
      }, function (e) {
        ui.transfer({ id: id, name: f.name, dir: "up", state: "error", error: e.message });
      }).then(next);
    };
    return next();
  }

  function getJSON(url, headers) {
    return fetch(url, { headers: headers || {}, credentials: "same-origin", cache: "no-store" }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) {
          var e = new Error((j && j.error) || "Erreur " + r.status);
          e.status = r.status;
          throw e;
        }
        return j;
      });
    });
  }

  window.EclipseFiles = {
    Browser: Browser, icon: icon, size: size, kindOf: kindOf,
    xhrUpload: xhrUpload, uploadAll: uploadAll, getJSON: getJSON, esc: esc
  };
})();
