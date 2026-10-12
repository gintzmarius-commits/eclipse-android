"use strict";

// ---------- plumbing ----------
const params = new URLSearchParams(location.search);
const token = params.get("t") || sessionStorage.getItem("t") || "";
sessionStorage.setItem("t", token);
if (params.has("t")) history.replaceState(null, "", "/");

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];

async function api(method, path, body) {
  const opt = { method, headers: { "X-Token": token } };
  if (body !== undefined) {
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(path, opt);
  const data = await r.json().catch(() => ({}));
  if (!r.ok) {
    const e = new Error(data.error || "Erreur inattendue");
    e.detail = data.detail || "";
    throw e;
  }
  return data;
}

let toastTimer;
function toast(msg, bad = false, ms = 3200) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast" + (bad ? " bad" : "");
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}

function show(id) {
  for (const s of ["installer", "setup", "main"]) $("#" + s).hidden = s !== id;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

const icon = (id, cls = "") => `<svg class="${cls}"><use href="#${id}"/></svg>`;

// ---------- state polling ----------
let state = {};
let mainLoaded = false;
let updateShown = false;
let currentTab = "home";
let powerBusy = false;

let pollTimer = null;
let pollBusy = false;
async function poll() {
  if (pollBusy) return;
  pollBusy = true;
  clearTimeout(pollTimer);
  try {
    state = await api("GET", "/api/state");
    render();
  } catch (e) {
    /* backend restarting */
  }
  const fast = (state.phase !== "ready" && !(state.install && state.install.mode)) || state.starting || powerBusy;
  // Window hidden or minimized: no need to ask as often.
  pollBusy = false;
  pollTimer = setTimeout(poll, fast ? 400 : document.hidden ? 10000 : 3000);
}

function render() {
  if (state.install && state.install.mode) return renderInstaller(state.install);
  if (state.phase !== "ready") return renderSetup();

  show("main");
  $("#host").textContent = state.host || "Mon PC";
  $("#p-host").textContent = state.host || "ton PC";
  $("#p-ip").textContent = state.ip || "?";
  $("#ver").textContent = "Eclipse " + state.version;
  renderPower();
  if (!mainLoaded) {
    mainLoaded = true;
    requestAnimationFrame(moveThumb);
    loadGames();
    loadDevices();
    loadRemote();
    loadVersions(false);
  }
  // A new version installs itself when Eclipse opens: show it happening.
  if (state.updating && !watchingUpdate) {
    watchingUpdate = true;
    loadVersions(false).then(() => $(".tab[data-tab=versions]").click());
  }
  if (state.updatedFrom && !updateShown) {
    updateShown = true;
    const from = state.updatedFrom;
    toast(from.startsWith("StreamFacile")
      ? `StreamFacile devient Eclipse ! Tes réglages sont gardés.`
      : `Mis à jour : ${from} → ${state.version}. Tes réglages sont gardés.`, false, 5500);
  }
}

// ---------- Sunshine on / off ----------
let lastRunning = null;
let shownStartErr = "";

function renderPower() {
  const starting = state.starting || (powerBusy === "start");
  const stopping = powerBusy === "stop";
  const running = state.running && !stopping;
  const st = $("#status"), btn = $("#btn-power");
  st.className = "status" + (running ? "" : starting || stopping ? " busy" : " off");
  const failed = !running && !starting && !stopping && state.startError;
  $("#status-text").textContent = running ? "Prêt à streamer" : starting ? "Démarrage…" : stopping ? "Arrêt…" : failed ? "Sunshine n'a pas redémarré" : state.stopped ? "Streaming arrêté" : "Sunshine ne répond pas";
  if (failed && shownStartErr !== state.startError) {
    shownStartErr = state.startError;
    toast(state.startError, true, 7000);
  }
  if (!state.startError) shownStartErr = "";
  $("#hero").classList.toggle("off", !running);
  $("#ip").textContent = running
    ? (state.ip ? "Adresse sur ton Wi-Fi : " + state.ip : "Pas de réseau détecté")
    : starting ? "Sunshine se lance, ça prend environ une minute sur ce PC"
    : "Ton PC n'est plus visible depuis tes appareils";
  $("#btn-connect").hidden = !running;
  btn.disabled = starting || stopping;
  btn.classList.toggle("primary", !running && !starting && !stopping);
  btn.querySelector("span").textContent = running ? "Arrêter" : starting ? "Démarrage…" : stopping ? "Arrêt…" : failed ? "Réessayer" : "Démarrer Sunshine";
  btn.title = running ? "Arrêter Sunshine" : "Démarrer Sunshine";

  if (lastRunning !== null && lastRunning !== running && !starting && !stopping) {
    if (currentTab === "games") loadGames();
    if (currentTab === "devices") loadDevices();
  }
  if (!starting && !stopping) lastRunning = running;
}

async function startSunshine() {
  powerBusy = "start";
  state.startError = "";
  renderPower();
  try {
    await api("POST", "/api/sunshine/start");
    // the backend reports "starting" until Sunshine answers
    setTimeout(() => { powerBusy = false; }, 1200);
  } catch (e) {
    powerBusy = false;
    toast(e.message, true);
  }
}

$("#btn-power").onclick = async () => {
  if (!state.running) return startSunshine();
  powerBusy = "stop";
  renderPower();
  try {
    await api("POST", "/api/sunshine/stop");
    state.running = false;
    state.stopped = true;
    toast("Sunshine est arrêté. Ton PC n'est plus visible depuis tes appareils.");
  } catch (e) {
    toast(e.message + (e.detail ? " (" + e.detail + ")" : ""), true);
  } finally {
    powerBusy = false;
    renderPower();
    loadGames();
    loadDevices();
  }
};

function stoppedNotice() {
  return `<li class="empty">${icon("i-power")}<span>Sunshine est arrêté.<br>Démarre-le pour voir et gérer cette liste.</span>
    <button class="btn primary" data-start>Démarrer Sunshine</button></li>`;
}
document.addEventListener("click", (e) => {
  if (e.target.closest("[data-start]")) startSunshine();
});

// ---------- setup progress ----------
const ORDER = ["download", "install", "config", "start"];
const TITLES = { download: "Installation de Sunshine", install: "Installation de Sunshine", config: "Préparation de ton PC", start: "Préparation de ton PC", check: "Préparation de ton PC" };
let lastPct = 0;
let failedAt = 0;

function renderSetup() {
  show("setup");
  const s = state;
  const isErr = s.phase === "error";
  $("#setup-title").textContent = isErr ? "Un souci est survenu" : TITLES[s.phase] || "Préparation de ton PC";
  $("#setup-msg").textContent = s.message || "…";
  $("#setup-detail").innerHTML = s.detail ? esc(s.detail) : "&nbsp;";

  const known = s.percent >= 0;
  const pct = Math.round(known ? s.percent : 0);
  const pctEl = $("#setup-pct");
  pctEl.textContent = (known ? pct : lastPct) + " %";
  pctEl.classList.toggle("dim", !known);
  if (known) lastPct = pct;
  const bar = $("#setup-bar");
  bar.classList.toggle("indet", !known && !isErr);
  bar.firstElementChild.style.width = (known ? s.percent : 0) + "%";
  bar.hidden = isErr;
  pctEl.hidden = isErr;

  const cur = ORDER.indexOf(s.phase);
  for (const li of $$("#setup-steps li")) {
    const i = ORDER.indexOf(li.dataset.step);
    li.hidden = !s.needInstall && i < 2;
    li.className = "";
    if (isErr) {
      if (i < failedAt) li.className = "done";
      else if (i === failedAt) li.className = "failed";
    } else if (cur >= 0) {
      if (i < cur) li.className = "done";
      else if (i === cur) li.className = "active";
    }
  }
  if (!isErr && cur >= 0) failedAt = cur;

  $("#setup-error").hidden = !isErr;
  $("#setup-errtext").textContent = s.error || "";
}

$("#btn-retry").onclick = () => api("POST", "/api/retry").catch(() => {});

// ---------- installer (Windows) ----------
let installerShown = false;
function renderInstaller(inst) {
  show("installer");
  if (installerShown) return;
  installerShown = true;
  $("#inst-fresh").hidden = !!inst.previous;
  $("#inst-update").hidden = !(inst.previous && !inst.same && !inst.newer);
  $("#inst-same").hidden = !inst.same;
  $("#inst-newer").hidden = !inst.newer;
  $("#opt-desktop").checked = !!inst.desktop;
  $("#inst-to").textContent = state.version;
  $("#inst-to2").textContent = inst.legacy ? "Eclipse " + state.version : state.version;
  if (inst.legacy) $("#inst-note").innerHTML = "StreamFacile devient Eclipse.<br>Tes accès et tes appareils connectés sont gardés.";
  $("#inst-from").textContent = inst.previous || "";
  $("#inst-same-v").textContent = inst.previous || "";
  $("#inst-newer-v").textContent = inst.previous || "";

  const main = $("#btn-install");
  const open = $("#btn-open-installed");
  if (inst.previous && !inst.same && !inst.newer) main.textContent = "Mettre à jour";
  if (inst.same || inst.newer) {
    open.hidden = false;
    open.classList.add("primary");
    main.classList.remove("primary");
    main.textContent = inst.newer ? "Installer quand même cette version" : "Réinstaller";
    open.parentElement.prepend(open);
  }
  if (inst.legacy) $("#inst-update h1").textContent = "Bienvenue dans Eclipse";
}

$("#btn-install").onclick = async () => {
  const b = $("#btn-install");
  const label = b.textContent;
  b.disabled = true;
  b.textContent = state.install && state.install.previous ? "Mise à jour…" : "Installation…";
  try {
    await api("POST", "/api/install", { desktop: $("#opt-desktop").checked });
    $("#install-msg").textContent = "C'est fait ! Eclipse s'ouvre…";
  } catch (e) {
    b.disabled = false;
    b.textContent = label;
    $("#install-msg").textContent = e.message + (e.detail ? " (" + e.detail + ")" : "");
  }
};
$("#btn-open-installed").onclick = () => api("POST", "/api/launch-installed").catch((e) => ($("#install-msg").textContent = e.message));

// ---------- tabs ----------
function moveThumb() {
  const active = $(".tab.active");
  const tabs = $(".tabs");
  if (!active || !tabs || !tabs.offsetParent) return;
  tabs.style.setProperty("--x", active.offsetLeft + "px");
  tabs.style.setProperty("--w", active.offsetWidth + "px");
}
window.addEventListener("resize", moveThumb);

for (const t of $$(".tab")) {
  t.onclick = () => {
    currentTab = t.dataset.tab;
    for (const x of $$(".tab")) x.classList.toggle("active", x === t);
    for (const p of $$(".pane")) p.hidden = p.id !== "pane-" + t.dataset.tab;
    moveThumb();
    if (currentTab === "games") loadGames();
    if (currentTab === "devices") loadDevices();
    if (currentTab === "moonlight") loadMoonlight();
    if (currentTab === "files") loadFiles();
    if (currentTab === "versions") loadVersions(true);
  };
}

// =====================================================================
// Versions (Linux): install the new version, or go back to an older one
// =====================================================================
let versions = null;
let versionsTimer = null;
let restarting = false;
let watchingUpdate = false;

async function loadVersions(fresh) {
  clearTimeout(versionsTimer);
  let v;
  try {
    v = await api("GET", "/api/versions" + (fresh ? "?fresh=1" : ""));
  } catch (e) {
    if (restarting) versionsTimer = setTimeout(() => loadVersions(false), 1000);
    return;
  }
  versions = v;
  if (!v.supported) return;
  const tab = $(".tab[data-tab=versions]");
  if (tab.hidden) {
    tab.hidden = false;
    requestAnimationFrame(moveThumb);
  }
  renderVersions();
  if (v.busy || v.phase === "restart") versionsTimer = setTimeout(() => loadVersions(false), 500);
  else versionsTimer = setTimeout(() => loadVersions(false), 6 * 3600 * 1000);
}

// The notes of a version are short Markdown: **bold**, "- " lists.
function notesHtml(md) {
  const inline = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/`(.+?)`/g, "<code>$1</code>");
  let html = "", list = false;
  for (const raw of String(md || "").split(/\r?\n/)) {
    const line = raw.trim();
    if (/^[-*] /.test(line)) {
      if (!list) { html += "<ul>"; list = true; }
      html += "<li>" + inline(line.slice(2)) + "</li>";
      continue;
    }
    if (list) { html += "</ul>"; list = false; }
    if (line) html += "<p>" + inline(line) + "</p>";
  }
  return html + (list ? "</ul>" : "");
}

function frDate(iso) {
  const d = new Date(iso);
  return isNaN(d) ? "" : d.toLocaleDateString("fr-FR", { day: "numeric", month: "long", year: "numeric" });
}

function renderVersions() {
  const v = versions;
  if (!v || !v.supported) return;
  // Home banner and tab dot when a newer version exists
  const newer = v.newest && !v.busy;
  $("#update-banner").hidden = !newer;
  $("#ver-dot").hidden = !newer;
  if (newer) $("#ub-title").textContent = `Eclipse ${v.newest} est disponible`;

  const prog = $("#ver-progress");
  prog.hidden = !(v.busy || v.phase === "restart");
  if (!prog.hidden) {
    const known = v.percent > 0;
    $("#ver-msg").textContent = (v.auto && v.target ? `Mise à jour automatique vers Eclipse ${v.target} · ` : "") + (v.message || "Préparation…");
    $("#ver-pct").textContent = Math.round(v.percent) + " %";
    $("#ver-pct").classList.toggle("dim", !known);
    $("#ver-bar").classList.toggle("indet", !known || v.phase === "restart");
    $("#ver-bar div").style.width = (known ? v.percent : 0) + "%";
    $("#ver-detail").innerHTML = v.detail ? esc(v.detail) : "&nbsp;";
    if (v.phase === "restart") restarting = true;
  }
  $("#ver-err").textContent = v.installError || v.error || "";

  const list = v.versions || [];
  if (!list.length) {
    $("#versions").innerHTML = v.checked || v.error
      ? `<li class="empty">${icon("i-tag")}<span>Aucune version publiée pour l'instant.<br>Tu as Eclipse ${esc(v.current)}.</span></li>`
      : `<li class="empty"><span class="spin"></span><span>Recherche des versions…</span></li>`;
    return;
  }
  $("#versions").innerHTML = list.map((x) => {
    const badge = x.installed ? `<span class="ver-badge on">Installée</span>` : x.newer ? `<span class="ver-badge new">Nouvelle</span>` : "";
    const btn = x.installed ? "" : `<button class="btn ${x.newer ? "primary" : ""}" data-ver="${esc(x.version)}" ${v.busy ? "disabled" : ""}>${icon(x.newer ? "i-down" : "i-refresh")}${x.newer ? "Mettre à jour" : "Revenir à cette version"}</button>`;
    return `<li><div class="tile">${icon("i-tag")}</div>
      <div class="txt"><strong>Eclipse ${esc(x.version)} ${badge}</strong><span>${esc(frDate(x.date))}</span>
        <div class="ver-notes">${notesHtml(x.notes)}</div></div>${btn}</li>`;
  }).join("");
}

$("#versions").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-ver]");
  if (!b || !versions || versions.busy) return;
  const ver = b.dataset.ver;
  const x = (versions.versions || []).find((y) => y.version === ver);
  if (x && !x.newer && !confirm(`Revenir à Eclipse ${ver} ? Tes réglages et tes appareils sont gardés.`)) return;
  b.disabled = true;
  try {
    await api("POST", "/api/versions/install", { version: ver });
  } catch (err) {
    toast(err.message, true);
  }
  setTimeout(() => loadVersions(false), 300);
});
$("#ver-refresh").onclick = () => loadVersions(true);
$("#ub-go").onclick = () => $(".tab[data-tab=versions]").click();

for (const b of $$("[data-open]")) b.onclick = () => api("POST", "/api/open", { target: b.dataset.open });
for (const b of $$("[data-close]")) b.onclick = () => closeSheet(b.dataset.close);

function closeSheet(id) {
  $("#" + id).hidden = true;
  if (id === "pair") stopPairing();
  if (id === "ml-sheet") stopMlPairing();
}
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") for (const s of $$(".sheet")) if (!s.hidden) closeSheet(s.id);
});
for (const s of $$(".sheet")) s.addEventListener("mousedown", (e) => { if (e.target === s) closeSheet(s.id); });

// ---------- settings ----------
$("#btn-settings").onclick = async () => {
  $("#settings").hidden = false;
  try {
    const lg = await api("GET", "/api/advanced");
    $("#adv-user").textContent = lg.user || "—";
    $("#adv-pass").textContent = lg.pass || "—";
  } catch (e) {
    /* ignore */
  }
};

// ---------- games (this PC) ----------
async function loadGames() {
  const ul = $("#games");
  $("#btn-add-game").disabled = !state.running;
  if (!state.running) {
    ul.innerHTML = stoppedNotice();
    return;
  }
  try {
    const { apps } = await api("GET", "/api/apps");
    if (!apps.length) {
      ul.innerHTML = `<li class="empty">Aucun jeu pour l'instant.<br>Ajoute le premier avec le bouton « Ajouter ».</li>`;
      return;
    }
    ul.innerHTML = apps
      .map((a) => {
        const desktop = !a.cmd;
        const name = a.name === "Desktop" ? "Bureau" : a.name;
        const sub = desktop ? "Tout l'écran du PC" : a.cmd.replace(/^"|"$/g, "");
        return `<li><div class="tile">${icon(desktop ? "i-desktop" : "i-game")}</div>
          <div class="txt"><strong>${esc(name)}</strong><span title="${esc(sub)}">${esc(sub)}</span></div>
          <button class="icon-btn" data-del="${a.index}" data-name="${esc(name)}" title="Retirer">${icon("i-x")}</button></li>`;
      })
      .join("");
    for (const b of $$("[data-del]")) {
      b.onclick = async () => {
        if (!confirm(`Retirer « ${b.dataset.name} » de la liste des jeux ?`)) return;
        try {
          await api("DELETE", "/api/apps/" + b.dataset.del);
          toast("Jeu retiré");
          loadGames();
        } catch (e) {
          toast(e.message, true);
        }
      };
    }
  } catch (e) {
    ul.innerHTML = `<li class="empty">${esc(e.message)}</li>`;
  }
}

let picked = null;
$("#btn-add-game").onclick = async () => {
  const b = $("#btn-add-game");
  b.disabled = true;
  try {
    const r = await api("POST", "/api/apps/pick");
    if (r.cancelled) return;
    picked = r.path;
    $("#add-name").value = r.name;
    $("#add-path").textContent = r.path;
    $("#add-row").hidden = false;
    $("#add-name").focus();
    $("#add-name").select();
  } catch (e) {
    toast(e.message, true);
  } finally {
    b.disabled = !state.running;
  }
};
$("#add-cancel").onclick = () => ($("#add-row").hidden = true);
$("#add-name").onkeydown = (e) => e.key === "Enter" && $("#add-ok").click();
$("#add-ok").onclick = async () => {
  try {
    await api("POST", "/api/apps", { name: $("#add-name").value, path: picked });
    $("#add-row").hidden = true;
    toast("Ajouté ! Il apparaît sur tes appareils.");
    loadGames();
  } catch (e) {
    toast(e.message, true);
  }
};

// ---------- devices ----------
async function loadDevices() {
  const ul = $("#devices");
  $("#btn-connect-2").disabled = !state.running;
  if (!state.running) {
    ul.innerHTML = stoppedNotice();
    return;
  }
  try {
    const { devices } = await api("GET", "/api/devices");
    if (!devices.length) {
      ul.innerHTML = `<li class="empty">Aucun appareil connecté pour l'instant.</li>`;
      return;
    }
    ul.innerHTML = devices
      .map(
        (d) => `<li><div class="tile">${icon("i-phone")}</div>
          <div class="txt"><strong>${esc(d.name || "Appareil")}</strong><span>Autorisé</span></div>
          <button class="btn" data-unpair="${esc(d.uuid)}" data-name="${esc(d.name || "cet appareil")}">Retirer</button></li>`
      )
      .join("");
    for (const b of $$("[data-unpair]")) {
      b.onclick = async () => {
        if (!confirm(`Retirer « ${b.dataset.name} » ? Il faudra le reconnecter pour jouer.`)) return;
        try {
          await api("POST", "/api/devices/remove", { uuid: b.dataset.unpair });
          toast("Appareil retiré");
          loadDevices();
        } catch (e) {
          toast(e.message, true);
        }
      };
    }
  } catch (e) {
    ul.innerHTML = `<li class="empty">${esc(e.message)}</li>`;
  }
}

// ---------- connecting a device to this PC ----------
// The Eclipse phone app scans a QR code (Eclipse answers its request by
// itself). Moonlight and older apps show a 4-digit code that is typed here.
let pairTimer = null;
let qrTimer = null;
let pairing = null; // request whose code is being typed
let pairView = "qr"; // what the window shows when no request asks for a code: qr, app or code
let qrSupported = true;
let qrRev = -1;
const pinInputs = $$("#pin input");
const PSTEPS = ["p-qr", "p-app", "p-wait", "p-pin", "p-done"];

function pairStep(id) {
  for (const s of PSTEPS) $("#" + s).hidden = s !== id;
}
function pairOpen() {
  return !$("#pair").hidden;
}
function baseStep() {
  if (pairView === "app") return "p-app";
  return pairView === "code" || !qrSupported ? "p-wait" : "p-qr";
}
function showBase() {
  if (!pairing && $("#p-done").hidden) pairStep(baseStep());
}

function openPairing(view) {
  view = view === "app" ? "app" : "qr";
  if (view === "qr" && !state.running) return;
  pairing = null;
  pairView = view;
  qrSupported = true;
  qrRev = -1;
  $("#qr-box").classList.add("loading");
  $("#qr-img").removeAttribute("src");
  setQrStatus("waiting");
  $("#p-to-qr").hidden = false;
  $("#pair").hidden = false;
  pairStep(baseStep());
  if (view === "app") loadAppQr();
  if (state.running) {
    watchPairing();
    startQr();
  }
}
$("#btn-connect").onclick = () => openPairing("qr");
$("#btn-connect-2").onclick = () => openPairing("qr");
$("#btn-get-app").onclick = () => openPairing("app");

$("#p-to-app").onclick = () => {
  pairView = "app";
  showBase();
  loadAppQr();
};
$("#p-app-back").onclick = () => {
  if (!state.running) return closeSheet("pair");
  pairView = "qr";
  showBase();
};
$("#p-to-code").onclick = () => {
  pairView = "code";
  showBase();
};
$("#p-to-qr").onclick = () => {
  pairView = "qr";
  showBase();
};

function stopPairing() {
  clearTimeout(pairTimer);
  clearTimeout(qrTimer);
  pairTimer = qrTimer = null;
  api("POST", "/api/qr/stop").catch(() => {});
}

// ---- QR code for the Eclipse app ----
async function startQr() {
  clearTimeout(qrTimer);
  let retry = 1000;
  try {
    const r = await api("POST", "/api/qr/start");
    if (!pairOpen()) {
      api("POST", "/api/qr/stop").catch(() => {});
      return;
    }
    if (!r.supported) {
      // Sunshine too old to list requests: only the 4-digit code works.
      qrSupported = false;
      $("#p-to-qr").hidden = true;
      showBase();
      return;
    }
    showQr(r);
  } catch (e) {
    setQrStatus("failed", e.message);
    retry = 3000;
  }
  qrTimer = setTimeout(pollQr, retry);
}

async function pollQr() {
  clearTimeout(qrTimer);
  if (!pairOpen() || !qrSupported) return;
  let r = null;
  try {
    r = await api("GET", "/api/qr/status?rev=" + qrRev);
  } catch (e) {
    /* backend busy: ask again */
  }
  if (!pairOpen()) return;
  if (r && !r.active) return startQr(); // code expired (window hidden a long time…): new code
  if (r) showQr(r);
  if (pairOpen() && $("#p-done").hidden) qrTimer = setTimeout(pollQr, 1000);
}

function showQr(r) {
  if (r.svg && r.rev !== qrRev) {
    qrRev = r.rev;
    $("#qr-img").src = r.svg;
    $("#qr-box").classList.remove("loading");
  }
  if (r.status === "done") return paired(r.device, r.phoneLogin);
  setQrStatus(r.status, r.error, r.device);
}

function setQrStatus(status, error, device) {
  const el = $("#qr-status");
  if (status === "pairing") {
    el.className = "qr-status busy";
    el.innerHTML = `<span class="spin"></span>Connexion de « ${esc(device || "ton téléphone")} »…`;
  } else if (status === "failed" && error) {
    el.className = "qr-status bad";
    el.textContent = error;
  } else {
    el.className = "qr-status";
    el.innerHTML = `<span class="spin"></span>En attente du téléphone…`;
  }
}

let appQrLoaded = false;
async function loadAppQr() {
  if (appQrLoaded) return;
  try {
    const r = await api("GET", "/api/qr/app");
    $("#app-img").src = r.svg;
    $("#app-box").classList.remove("loading");
    appQrLoaded = true;
  } catch (e) {
    toast(e.message, true);
  }
}

function paired(name, phoneLogin) {
  clearTimeout(pairTimer);
  clearTimeout(qrTimer);
  pairing = null;
  // The phone's Tailscale sign-in page opened in the browser (once).
  $("#p-done-ts").hidden = !phoneLogin;
  $("#p-done-text").textContent = name
    ? `« ${name} » peut maintenant jouer aux jeux de ce PC.`
    : "Tu peux maintenant jouer aux jeux de ce PC depuis ton appareil.";
  pairStep("p-done");
  api("POST", "/api/qr/stop").catch(() => {});
  loadDevices();
}

// ---- requests that need their code typed here ----
async function watchPairing() {
  clearTimeout(pairTimer);
  if (!pairOpen() || !$("#p-done").hidden) return;
  try {
    const r = await api("GET", "/api/pending");
    if (!r.supported) {
      // Old Sunshine: no list of requests, the code is typed directly.
      qrSupported = false;
      if (!pairing && pairView !== "app") askPin({ id: "", name: "" });
      return;
    }
    const reqs = r.pairings;
    const busy = $("#pin").classList.contains("busy");
    if (reqs.length && !pairing) {
      askPin(reqs[reqs.length - 1]);
    } else if (pairing && !busy && !reqs.some((p) => p.id === pairing.id)) {
      pairing = null; // the device gave up
      showBase();
    }
  } catch (e) {
    /* keep waiting */
  }
  if (pairOpen()) pairTimer = setTimeout(watchPairing, 1200);
}

function askPin(p) {
  pairing = p;
  $("#p-who").textContent = p.name ? `« ${p.name} » veut se connecter` : "Un appareil veut se connecter";
  $("#p-err").textContent = "";
  $("#p-refuse").hidden = !p.id;
  pairStep("p-pin");
  resetPin();
  if (!p.id) {
    $("#p-who").textContent = "Tape le code";
    $("#p-wait").hidden = false;
    $("#p-to-qr").hidden = true;
  }
}

$("#p-refuse").onclick = async () => {
  if (!pairing || !pairing.id) return;
  const id = pairing.id;
  try {
    await api("POST", "/api/pending/cancel", { id });
  } catch (e) {
    toast(e.message, true);
  }
  pairing = null;
  showBase();
};

function resetPin() {
  for (const i of pinInputs) i.value = "";
  $("#pin").classList.remove("busy");
  setTimeout(() => !$("#p-pin").hidden && pinInputs[0].focus(), 50);
}

pinInputs.forEach((inp, idx) => {
  inp.addEventListener("input", () => {
    inp.value = inp.value.replace(/\D/g, "").slice(-1);
    if (inp.value && idx < 3) pinInputs[idx + 1].focus();
    const code = pinInputs.map((i) => i.value).join("");
    if (code.length === 4) submitPin(code);
  });
  inp.addEventListener("keydown", (e) => {
    if (e.key === "Backspace" && !inp.value && idx > 0) pinInputs[idx - 1].focus();
  });
  inp.addEventListener("paste", (e) => {
    const d = (e.clipboardData.getData("text") || "").replace(/\D/g, "").slice(0, 4);
    if (d.length === 4) {
      e.preventDefault();
      d.split("").forEach((c, i) => (pinInputs[i].value = c));
      submitPin(d);
    }
  });
});

async function submitPin(code) {
  const box = $("#pin");
  if (box.classList.contains("busy")) return;
  box.classList.add("busy");
  $("#p-err").textContent = "";
  const req = pairing || { id: "", name: "" };
  let ok = false;
  let msg = "";
  try {
    ok = (await api("POST", "/api/pair", { id: req.id, pin: code, name: req.name || "" })).ok;
    if (!ok) msg = "Code incorrect.";
  } catch (e) {
    msg = e.message + (e.detail ? " (" + e.detail + ")" : "");
  }
  if (!pairOpen()) return;
  if (ok) return paired(req.name);
  if (req.id) {
    // Sunshine closes a request once a code was given: the device has to ask again.
    pairing = null;
    box.classList.remove("busy");
    showBase();
    toast(msg + " Recommence la connexion depuis ton appareil.", true, 6000);
    return;
  }
  box.classList.remove("busy", "shake");
  void box.offsetWidth;
  box.classList.add("shake");
  $("#p-err").textContent = msg === "Code incorrect." ? "Code incorrect. Réessaie avec le code affiché." : msg;
  resetPin();
}

// =====================================================================
// Playing away from home (Tailscale)
// =====================================================================
let remote = null;
let remoteTimer = null;
let remoteBusy = false;

async function loadRemote() {
  clearTimeout(remoteTimer);
  try {
    remote = await api("GET", "/api/remote");
    renderRemote();
  } catch (e) {
    /* backend busy */
  }
  const fast = remote && (remote.busy || remote.state === "starting");
  remoteTimer = setTimeout(loadRemote, fast ? 1000 : currentTab === "home" ? 5000 : 15000);
}

function renderRemote() {
  const r = remote;
  if (!r) return;
  const sub = $("#rm-sub"), body = $("#rm-body");
  const on = r.state === "running" && !r.busy;
  $("#rm-pill").hidden = !on;
  let html = "";
  if (r.busy && r.phase === "install") {
    const known = r.percent > 0;
    sub.textContent = "Installation de Tailscale sur ce PC";
    html = `<div><div class="rm-prog-head"><span>${esc(r.message || "Préparation…")}</span><b class="pct-sm ${known ? "" : "dim"}">${Math.round(r.percent)} %</b></div>
      <div class="bar ${known ? "" : "indet"}"><div style="width:${known ? r.percent : 0}%"></div></div>
      <p class="small detail">${r.detail ? esc(r.detail) : "&nbsp;"}</p></div>`;
  } else if (r.busy && r.phase === "login") {
    sub.textContent = "Connexion à ton compte Tailscale";
    html = r.loginUrl
      ? `<div class="rm-login">${r.loginQr ? `<img alt="QR code de connexion" src="${r.loginQr}">` : ""}
          <div><p>Une page vient de s'ouvrir dans le navigateur : <b>connecte-toi</b> (avec ton compte Google, par exemple), puis reviens ici.</p>
          <p class="small">Tu peux aussi scanner ce code avec ton téléphone pour te connecter dessus.</p></div></div>
          <div class="rm-row"><button class="btn primary" data-rm="open-login">${icon("i-open")}Rouvrir la page</button><button class="btn" data-rm="cancel">Annuler</button></div>`
      : `<p><span class="spin"></span>Préparation de la connexion…</p>`;
  } else if (r.busy) {
    sub.textContent = r.message || "Démarrage de Tailscale…";
    html = `<p><span class="spin"></span>${esc(r.message || "Démarrage de Tailscale…")}</p>`;
  } else if (on) {
    sub.innerHTML = `Ce PC est joignable partout${r.ip ? ` · <span class="sel">${esc(r.ip)}</span>` : ""}`;
    html = `<ol class="rm-steps">
        <li>Installe ou mets à jour <b>Eclipse</b> sur ton téléphone.</li>
        <li>Scanne une fois le QR code d'Eclipse. La première fois, une page Tailscale s'ouvre ici : clique <b>Connect</b>.</li>
        <li>C'est tout : ton téléphone trouve ce PC partout, en Wi-Fi comme en 4G.</li>
      </ol>
      <div class="rm-row"><button class="btn primary" data-rm="qr">${icon("i-qr")}Afficher le QR code</button>
        <button class="link" data-rm="relogin">Changer de compte</button><button class="link" data-rm="disable">Mettre en pause</button></div>`;
    if (r.expiryDays !== undefined) {
      html += `<p class="rm-note">Par sécurité, Tailscale reconnectera ce PC dans ${Math.max(0, r.expiryDays)} jours. Pour éviter ça : dans la console Tailscale, menu « … » de ce PC → <b>Disable key expiry</b>. <button class="link" data-rm="admin">Ouvrir la console</button></p>`;
    }
  } else {
    const texts = {
      absent: "Avec Tailscale (gratuit, intégré à Eclipse), ton téléphone retrouve ce PC partout, même en 4G. Un compte Google suffit.",
      needslogin: "Tailscale est installé : il reste à te connecter à ton compte.",
      stopped: "Tailscale est en pause sur ce PC.",
      starting: "Tailscale démarre…",
      approval: "Ce PC attend d'être approuvé dans la console Tailscale.",
      error: "Tailscale ne répond pas" + (r.detail ? " (" + r.detail + ")" : "") + ".",
    };
    sub.textContent = texts[r.state] || texts.absent;
    const label = r.state === "absent" ? "Activer" : r.state === "needslogin" ? "Me connecter" : r.state === "approval" ? "Ouvrir la console" : "Activer";
    html = `${r.error ? `<p class="err">${esc(r.error)}</p>` : ""}
      <div class="rm-row"><button class="btn primary" data-rm="${r.state === "approval" ? "admin" : "enable"}" ${r.state === "starting" ? "disabled" : ""}>${icon("i-globe")}${r.error ? "Réessayer" : label}</button></div>`;
  }
  if (body.dataset.html !== html) {
    body.innerHTML = html;
    body.dataset.html = html;
  }
}

$("#rm-body").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-rm]");
  if (!b || remoteBusy) return;
  const what = b.dataset.rm;
  if (what === "qr") return openPairing("qr");
  if (what === "open-login") return api("POST", "/api/remote/open", { target: "login" }).catch(() => {});
  if (what === "admin") return api("POST", "/api/remote/open", { target: "admin" }).catch(() => {});
  if (what === "disable" && !confirm("Mettre en pause Tailscale ? Ton téléphone ne pourra plus joindre ce PC en dehors de son Wi-Fi.")) return;
  if (what === "relogin" && !confirm("Se déconnecter de ce compte Tailscale pour en utiliser un autre ?")) return;
  remoteBusy = true;
  b.disabled = true;
  try {
    await api("POST", "/api/remote/" + what);
  } catch (err) {
    toast(err.message + (err.detail ? " (" + err.detail + ")" : ""), true);
  }
  remoteBusy = false;
  setTimeout(loadRemote, 300);
});

// =====================================================================
// Moonlight tab: play here the games of another PC
// =====================================================================
let mlTimer = null;
let mlInstalled = false;

async function loadMoonlight() {
  clearTimeout(mlTimer);
  let st;
  try {
    st = await api("GET", "/api/ml/status");
  } catch (e) {
    return;
  }
  const wasInstalled = mlInstalled;
  mlInstalled = st.installed && !st.installing;
  $("#ml-install").hidden = mlInstalled;
  $("#ml-actions").hidden = !mlInstalled;
  $("#ml-hosts").hidden = !mlInstalled;

  if (!mlInstalled) {
    const busy = st.installing;
    $("#ml-install-btn").hidden = busy;
    $("#ml-progress").hidden = !busy;
    $("#ml-err").textContent = st.error ? "L'installation a échoué : " + st.error : "";
    if (st.error) $("#ml-install-btn").innerHTML = icon("i-refresh") + "Réessayer";
    if (busy) {
      const known = st.percent >= 0;
      $("#ml-msg").textContent = st.message || "…";
      $("#ml-pct").textContent = Math.round(known ? st.percent : 0) + " %";
      $("#ml-pct").classList.toggle("dim", !known);
      $("#ml-bar").classList.toggle("indet", !known);
      $("#ml-bar").firstElementChild.style.width = (known ? st.percent : 0) + "%";
      $("#ml-detail").innerHTML = st.detail ? esc(st.detail) : "&nbsp;";
      mlTimer = setTimeout(loadMoonlight, 350);
    }
    return;
  }
  if (!wasInstalled && st.percent >= 100) toast("Moonlight est installé !");
  loadHosts();
}

$("#ml-install-btn").onclick = async () => {
  $("#ml-err").textContent = "";
  $("#ml-install-btn").hidden = true;
  $("#ml-progress").hidden = false;
  $("#ml-bar").firstElementChild.style.width = "0%";
  try {
    await api("POST", "/api/ml/install");
  } catch (e) {
    toast(e.message, true);
  }
  setTimeout(loadMoonlight, 200);
};

$("#ml-open").onclick = () => api("POST", "/api/ml/open").catch((e) => toast(e.message, true));

async function loadHosts() {
  const ul = $("#ml-hosts");
  let hosts;
  try {
    hosts = (await api("GET", "/api/ml/hosts")).hosts;
  } catch (e) {
    ul.innerHTML = `<li class="panel empty">${esc(e.message)}</li>`;
    return;
  }
  if (!hosts.length) {
    ul.innerHTML = `<li class="panel empty">${icon("i-desktop")}<span>Aucun PC pour l'instant.<br>Ajoute le PC sur lequel tes jeux sont installés.</span>
      <button class="btn primary" data-ml-add>${icon("i-plus")}Ajouter un PC</button></li>`;
    return;
  }
  ul.innerHTML = hosts
    .map((h, i) => `<li class="panel pc" data-i="${i}">
        <div class="pc-head"><div class="tile">${icon("i-desktop")}</div>
          <div class="txt"><strong>${esc(h.name || h.address)}</strong>
            <span><i class="dot ${h.online ? "" : "off"}"></i>${h.online ? "En ligne" : "Hors ligne"} · ${esc(h.address)}</span></div>
          <button class="icon-btn" data-ml-remove="${esc(h.address)}" data-name="${esc(h.name || h.address)}" title="Retirer">${icon("i-x")}</button>
        </div>
        <div class="games" data-games="${esc(h.address)}">${h.online ? `<span class="note"><span class="spin"></span>Chargement des jeux…</span>` : `<span class="note">Allume ce PC (et Eclipse dessus) pour voir ses jeux.</span>`}</div>
      </li>`)
    .join("");
  hosts.forEach((h) => h.online && loadHostGames(h));
}

async function loadHostGames(h) {
  const box = document.querySelector(`[data-games="${CSS.escape(h.address)}"]`);
  if (!box) return;
  let r;
  try {
    r = await api("GET", "/api/ml/games?address=" + encodeURIComponent(h.address));
  } catch (e) {
    r = { state: "error", error: e.message };
  }
  if (r.state === "ok") {
    box.innerHTML = r.games.length
      ? r.games.map((g) => `<button class="game" data-play="${esc(g)}" data-addr="${esc(h.address)}">${icon("i-play")}${esc(g === "Desktop" ? "Bureau" : g)}</button>`).join("")
      : `<span class="note">Aucun jeu sur ce PC.</span>`;
  } else if (r.state === "notpaired") {
    box.innerHTML = `<span class="note">Pas encore connecté à ce PC. <button class="btn" data-ml-pair="${esc(h.address)}">Connecter</button></span>`;
  } else if (r.state === "offline") {
    box.innerHTML = `<span class="note">Ce PC ne répond pas. Vérifie qu'il est allumé et que Sunshine tourne.</span>`;
  } else {
    box.innerHTML = `<span class="note">${esc(r.error || "Erreur")}</span>`;
  }
}

document.addEventListener("click", async (e) => {
  const play = e.target.closest("[data-play]");
  if (play) {
    play.classList.add("launching");
    try {
      await api("POST", "/api/ml/stream", { address: play.dataset.addr, game: play.dataset.play });
      toast(`Lancement de « ${play.textContent.trim()} »…`);
    } catch (err) {
      toast(err.message, true);
    }
    setTimeout(() => play.classList.remove("launching"), 2500);
    return;
  }
  const rm = e.target.closest("[data-ml-remove]");
  if (rm) {
    if (!confirm(`Retirer « ${rm.dataset.name} » de la liste ?`)) return;
    await api("POST", "/api/ml/remove", { address: rm.dataset.mlRemove }).catch((err) => toast(err.message, true));
    loadHosts();
    return;
  }
  if (e.target.closest("[data-ml-add]")) return openMlSheet();
  const pr = e.target.closest("[data-ml-pair]");
  if (pr) {
    openMlSheet(true);
    startMlPairing(pr.dataset.mlPair);
  }
});

// ---- adding a PC ----
let mlPairTimer = null;
let mlLastAddr = "";

function mlStep(id) {
  for (const s of ["m-find", "m-pin", "m-done", "m-fail"]) $("#" + s).hidden = s !== id;
}

$("#ml-add").onclick = () => openMlSheet();

async function openMlSheet(skipScan) {
  mlStep("m-find");
  $("#m-err").textContent = "";
  $("#m-addr").value = "";
  $("#ml-sheet").hidden = false;
  if (skipScan) return;
  const ul = $("#m-found");
  ul.innerHTML = `<li class="scanning"><span class="spin"></span>Recherche sur ton Wi-Fi…</li>`;
  try {
    const { pcs } = await api("GET", "/api/ml/scan");
    if ($("#ml-sheet").hidden) return;
    ul.innerHTML = pcs.length
      ? pcs.map((p) => `<li class="${p.saved ? "disabled" : ""}" data-pick="${esc(p.address)}">
          <div class="tile">${icon("i-desktop")}</div>
          <div class="txt"><strong>${esc(p.name)}</strong><span>${esc(p.address)}</span></div>
          ${p.saved ? `<span class="tag">Déjà ajouté</span>` : icon("i-arrow", "ico")}</li>`).join("")
      : `<li class="none">Aucun PC trouvé. Entre son adresse ci-dessous.</li>`;
  } catch (e) {
    ul.innerHTML = `<li class="none">${esc(e.message)}</li>`;
  }
}

$("#m-found").addEventListener("click", (e) => {
  const li = e.target.closest("[data-pick]");
  if (li && !li.classList.contains("disabled")) startMlPairing(li.dataset.pick);
});
$("#m-go").onclick = () => {
  const a = $("#m-addr").value.trim();
  if (!a) {
    $("#m-err").textContent = "Entre l'adresse du PC.";
    return;
  }
  startMlPairing(a);
};
$("#m-addr").onkeydown = (e) => e.key === "Enter" && $("#m-go").click();
$("#m-retry").onclick = () => startMlPairing(mlLastAddr);

async function startMlPairing(addr) {
  mlLastAddr = addr;
  $("#m-err").textContent = "";
  try {
    const job = await api("POST", "/api/ml/pair", { address: addr });
    $("#m-name").textContent = job.name;
    $("#m-name2").textContent = job.name;
    $("#m-name3").textContent = job.name;
    $("#m-code").innerHTML = job.pin.split("").map((d) => `<span>${d}</span>`).join("");
    $("#m-status").innerHTML = `<span class="spin"></span>En attente de l'autre PC…`;
    mlStep("m-pin");
    watchMlPairing();
  } catch (e) {
    mlStep("m-find");
    $("#m-err").textContent = e.message + (e.detail ? " (" + e.detail + ")" : "");
  }
}

async function watchMlPairing() {
  clearTimeout(mlPairTimer);
  if ($("#ml-sheet").hidden) return;
  try {
    const j = await api("GET", "/api/ml/pair");
    if (j.status === "checking") $("#m-status").innerHTML = `<span class="spin"></span>Vérification…`;
    if (j.status === "success") {
      mlStep("m-done");
      loadHosts();
      return;
    }
    if (j.status === "failed") {
      $("#m-failtext").textContent = j.error || "La connexion a échoué.";
      mlStep("m-fail");
      return;
    }
  } catch (e) {
    /* keep waiting */
  }
  mlPairTimer = setTimeout(watchMlPairing, 1000);
}

function stopMlPairing() {
  clearTimeout(mlPairTimer);
  if (!$("#m-pin").hidden) api("POST", "/api/ml/pair/cancel").catch(() => {});
}

// ---------- go ----------
document.addEventListener("contextmenu", (e) => {
  if (!e.target.closest("input, code, #ip-line, .help")) e.preventDefault();
});
poll();
document.addEventListener("visibilitychange", () => { if (!document.hidden) poll(); });


// =====================================================================
// Files: the phones' files here; this PC's files from a browser
// =====================================================================
let filesUI = null;
let filesTimer = null;

const pcFilesAdapter = {
  empty: "Aucun téléphone pour l'instant. Sur ton téléphone, ouvre Eclipse, onglet Fichiers, et active « Partager les fichiers de ce téléphone ».",
  devices: () => api("GET", "/api/x/devices").then((d) => d.devices || []),
  roots: (dev) => api("GET", `/api/x/${dev.id}/roots`),
  list: (dev, path) => api("GET", `/api/x/${dev.id}/list?path=${encodeURIComponent(path)}`),
  sep: () => "/",
  download(dev, path, entry, ui) {
    const id = "dl" + Date.now() + Math.random();
    ui.transfer({ id, name: entry.name, dir: "down", pct: -1, state: "run" });
    api("POST", `/api/x/${dev.id}/fetch`, { path }).then((r) => {
      ui.transfer({ id, name: r.name, dir: "down", state: "done", doneText: "Enregistré dans Téléchargements › Eclipse",
        action: { label: "Afficher", run: () => api("POST", "/api/files/show", { path: r.path }) } });
    }, (e) => ui.transfer({ id, name: entry.name, dir: "down", state: "error", error: e.message }));
  },
  upload(dev, dir, files, ui) {
    return EclipseFiles.uploadAll(files, ui, (f, pct) =>
      EclipseFiles.xhrUpload(`/api/x/${dev.id}/put?dir=${encodeURIComponent(dir)}&name=${encodeURIComponent(f.name)}`, f, { "X-Token": token }, pct)
    ).then(() => ui.refresh(dev, dir));
  },
};

async function loadFiles() {
  clearTimeout(filesTimer);
  if (!filesUI) {
    filesUI = new EclipseFiles.Browser($("#fx-pc"), pcFilesAdapter, { toast, alwaysDevices: true, downloadLabel: "Télécharger sur ce PC" });
  }
  try {
    renderFilesState(await api("GET", "/api/files/state"));
  } catch (e) { /* shown next time */ }
  await filesUI.start();
  if (currentTab === "files") filesTimer = setTimeout(refreshFiles, 5000);
}

// Every few seconds: phones appearing or leaving.
async function refreshFiles() {
  if (currentTab !== "files") return;
  try {
    const st = await api("GET", "/api/files/state");
    renderFilesState(st);
    const ids = (st.phones || []).map((p) => p.id + (p.online ? "+" : "-")).join(",");
    const now = (filesUI.devs || []).map((p) => p.id + (p.online ? "+" : "-")).join(",");
    if (ids !== now) await filesUI.start();
  } catch (e) { /* later */ }
  filesTimer = setTimeout(refreshFiles, 5000);
}

function renderFilesState(st) {
  $("#fw-on").checked = !!st.web;
  $("#fb-on").checked = !!st.background;
  $("#fw-body").hidden = !st.web;
  $("#fw-sub").textContent = st.web
    ? "Ouvre ces fichiers depuis n'importe quel ordinateur ou téléphone."
    : "Désactivé : seuls tes téléphones avec Eclipse ont accès aux fichiers.";
  $("#fw-code").textContent = st.code || "—";
  const ul = $("#fw-addrs");
  ul.innerHTML = "";
  (st.addresses || []).forEach((a, i) => {
    const li = document.createElement("li");
    const ts = /^http:\/\/100\./.test(a);
    li.innerHTML = `<code></code><span class="tag">${ts ? "partout (Tailscale)" : "même Wi-Fi"}</span><button class="btn">Copier</button>`;
    li.querySelector("code").textContent = a;
    li.querySelector("button").onclick = () => {
      navigator.clipboard.writeText(a).then(() => toast("Adresse copiée"), () => toast(a));
    };
    if (i < 3) ul.appendChild(li);
  });
  let err = "";
  if (st.error) err = "Le partage de fichiers ne peut pas démarrer : le port " + st.port + " est déjà utilisé.";
  else if (st.certError) err = "Les téléphones ne peuvent pas encore se connecter : Sunshine n'est pas prêt.";
  $("#fw-err").textContent = err;
}

$("#fw-on").onchange = async (e) => {
  try { renderFilesState(await api("POST", "/api/files/web", { on: e.target.checked })); }
  catch (err) { toast(err.message, true); }
};
$("#fw-new").onclick = async () => {
  if (!confirm("Créer un nouveau code ? Les navigateurs déjà connectés devront entrer le nouveau.")) return;
  try { renderFilesState(await api("POST", "/api/files/web", { newCode: true })); toast("Nouveau code créé"); }
  catch (err) { toast(err.message, true); }
};
$("#fb-on").onchange = async (e) => {
  try {
    renderFilesState(await api("POST", "/api/files/background", { on: e.target.checked }));
    toast(e.target.checked ? "Eclipse restera disponible en arrière-plan" : "Eclipse se fermera avec sa fenêtre");
  } catch (err) { toast(err.message, true); }
};
