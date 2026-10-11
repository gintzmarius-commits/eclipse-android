"use strict";

// Eclipse for Android — interface. The Android app (EclipseActivity) exposes
// window.EclipseNative and sends events through window.ec.on(event, data).

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const icon = (id, cls = "") => `<svg class="${cls}"><use href="#${id}"/></svg>`;
const N = window.EclipseNative || demoNative(); // demo mode outside the app (previews)

let toastTimer;
function toast(msg, bad = false, ms = 3200) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = "toast" + (bad ? " bad" : "");
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}

// ---------------------------------------------------------------------
// State
// ---------------------------------------------------------------------
let pcs = [];          // from the app
let current = null;    // uuid of the PC whose games are shown
let games = {};        // uuid -> {apps, running, error}
let pairing = null;    // {uuid, name} while a pairing runs
let menuPc = null;
let ts = { installed: false, vpn: false }; // Tailscale app on this phone
let tun = { available: false }; // Tailscale built into Eclipse
let tunLoginAsked = false;
const tunOn = () => tun.available && tun.state === "running";

const byUuid = (u) => pcs.find((p) => p.uuid === u);
const stateOf = (p) => (p.state === "ONLINE" ? "online" : p.state === "OFFLINE" ? "offline" : "wait");

window.ec = {
  insets(t, r, b, l) {
    const s = document.documentElement.style;
    s.setProperty("--st", t + "px");
    s.setProperty("--sr", r + "px");
    s.setProperty("--sb", b + "px");
    s.setProperty("--sl", l + "px");
  },
  on(event, data) {
    try {
      handlers[event] && handlers[event](data);
    } catch (e) {
      console.error(e);
    }
  },
};

const handlers = {
  computers(list) {
    pcs = list.sort((a, b) => a.name.localeCompare(b.name));
    renderPcs();
    if (current) renderGamesHeader();
  },
  apps(ev) {
    games[ev.uuid] = ev;
    if (ev.uuid === current) renderGames();
  },
  art(ev) {
    const g = games[ev.uuid];
    if (g && g.apps) {
      const a = g.apps.find((x) => x.id === ev.id);
      if (a) a.art = ev.art;
    }
    const img = document.querySelector(`.game[data-id="${ev.id}"][data-uuid="${CSS.escape(ev.uuid)}"] .art`);
    if (img && !img.querySelector("img")) {
      img.insertAdjacentHTML("afterbegin", `<img alt="" src="${ev.art}">`);
      const ph = img.querySelector("svg");
      if (ph) ph.remove();
    }
  },
  paired(ev) {
    if (!pairing || ev.uuid !== pairing.uuid) return;
    if (ev.ok) {
      $("#p-name3").textContent = pairing.name;
      pairStep("p-done");
      const uuid = pairing.uuid;
      pairing = null;
      setTimeout(() => {
        closeSheet("s-pair", true);
        openGames(uuid);
      }, 1300);
    } else {
      $("#p-err").textContent = ev.error || "La connexion a échoué.";
      pairStep("p-fail");
    }
  },
  added(ev) {
    $("#a-go").disabled = false;
    $("#a-go").textContent = "Ajouter";
    if (ev.ok) {
      closeSheet("s-add");
      toast("PC ajouté !");
    } else {
      $("#a-err").textContent = ev.error || "Impossible d'ajouter ce PC.";
    }
  },
  resume() {
    if (current) N.apps(current);
  },
  qr(ev) {
    qrEvent(ev);
  },
  tunnel(ev) {
    const was = JSON.stringify(tun);
    tun = ev;
    if (tunOn()) tunLoginAsked = false;
    if (was !== JSON.stringify(tun)) {
      renderPcs();
      if (!$("#s-remote").hidden) renderRemote();
      setRemoteSub();
    }
  },
  diag(text) {
    $("#diag-text").textContent = text;
  },
  tailscale(ev) {
    const changed = ev.installed !== ts.installed || ev.vpn !== ts.vpn;
    ts = ev;
    if (changed) {
      renderPcs();
      if (current) renderGamesHeader();
      if (!$("#s-remote").hidden) renderRemote();
    }
    setRemoteSub();
  },
};

// ---------------------------------------------------------------------
// PC list
// ---------------------------------------------------------------------
function statusText(p) {
  const st = stateOf(p);
  if (st === "offline") return `<i class="dot off"></i>Hors ligne`;
  if (st === "wait") return `<i class="dot wait"></i>Recherche…`;
  if (!p.paired) return `<i class="dot wait"></i>À connecter`;
  return `<i class="dot"></i>${p.running ? "En jeu" : "En ligne"}`;
}

function renderPcs() {
  $("#searching").hidden = pcs.length > 0;
  $("#pcs").innerHTML = pcs
    .map((p) => {
      const st = stateOf(p);
      let action = icon("i-chev", "chev");
      if (st === "online" && !p.paired) action = `<button class="btn primary pill-sm" data-pair="${esc(p.uuid)}">Connecter</button>`;
      if (st === "offline" && p.canWake) action = `<button class="btn pill-sm" data-wake="${esc(p.uuid)}">${icon("i-power")}Allumer</button>`;
      if (st === "offline" && p.remote && (tun.available ? !tunOn() : !ts.vpn)) action = `<button class="btn pill-sm" data-remote="${esc(p.uuid)}">${icon("i-globe")}${tun.available ? "Connexion" : "Tailscale"}</button>`;
      return `<li class="pc" data-open="${esc(p.uuid)}">
        <div class="tile">${icon("i-desktop")}</div>
        <div class="txt"><strong>${esc(p.name)}</strong><span>${statusText(p)}${p.address ? " · " + esc(p.address) : ""}</span></div>
        ${action}
        <button class="icon-btn" data-menu="${esc(p.uuid)}" aria-label="Options">${icon("i-more")}</button>
      </li>`;
    })
    .join("");
}

$("#pcs").addEventListener("click", (e) => {
  const t = e.target.closest("[data-pair],[data-wake],[data-remote],[data-menu],[data-open]");
  if (!t) return;
  if (t.dataset.remote) return openRemote(t.dataset.remote);
  if (t.dataset.pair) return openConnect(t.dataset.pair);
  if (t.dataset.wake) return N.wake(t.dataset.wake);
  if (t.dataset.menu) return openMenu(t.dataset.menu);
  const p = byUuid(t.dataset.open);
  if (!p) return;
  const st = stateOf(p);
  if (st === "offline" && p.remote) return openRemote(p.uuid);
  if (st === "offline") return toast(p.canWake ? "Ce PC est éteint. Touche « Allumer »." : "Ce PC est éteint ou n'est pas sur ce Wi-Fi.");
  if (st === "wait") return toast("Connexion au PC en cours, patiente un instant…");
  if (!p.paired) return openConnect(p.uuid);
  openGames(p.uuid);
});

// ---------------------------------------------------------------------
// Games of one PC
// ---------------------------------------------------------------------
function openGames(uuid) {
  current = uuid;
  $("#v-pcs").hidden = true;
  $("#v-games").hidden = false;
  $("#back").hidden = false;
  $("#brand").hidden = true;
  $("#title-sm").hidden = false;
  renderGamesHeader();
  if (!games[uuid]) $("#games").innerHTML = `<p class="note"><span class="spin"></span>Chargement des jeux…</p>`;
  else renderGames();
  N.apps(uuid);
  $(".content").scrollTop = 0;
}

function closeGames() {
  current = null;
  $("#v-games").hidden = true;
  $("#v-pcs").hidden = false;
  $("#back").hidden = true;
  $("#brand").hidden = false;
  $("#title-sm").hidden = true;
}
$("#back").onclick = closeGames;

function renderGamesHeader() {
  const p = byUuid(current);
  if (!p) return;
  $("#title-sm").textContent = p.name;
  $("#g-name").textContent = p.name;
  $("#g-status").innerHTML = statusText(p) + (p.address ? " · " + esc(p.address) : "");
  const g = games[current];
  const runId = p.running || (g && g.running) || 0;
  const runApp = g && g.apps && g.apps.find((a) => a.id === runId);
  $("#g-running").hidden = !runId;
  $("#g-running-name").textContent = runApp ? displayName(runApp.name) : "Un jeu";
}

const displayName = (n) => (n === "Desktop" ? "Bureau" : n);

function renderGames() {
  const g = games[current];
  const box = $("#games");
  renderGamesHeader();
  if (!g) return;
  if (g.error) {
    box.innerHTML = `<p class="note">${g.error === "offline" ? "Ce PC ne répond pas. Vérifie qu'il est allumé et qu'Eclipse tourne dessus." : esc(g.error)}</p>`;
    return;
  }
  if (!g.apps.length) {
    box.innerHTML = `<p class="note">Aucun jeu sur ce PC. Ajoute-en depuis Eclipse sur ton PC (onglet Jeux).</p>`;
    return;
  }
  const p = byUuid(current);
  const runId = (p && p.running) || g.running || 0;
  box.innerHTML = g.apps
    .map((a) => `<button class="game" data-id="${a.id}" data-uuid="${esc(current)}">
        <div class="art">${a.art ? `<img alt="" src="${a.art}">` : icon(a.name === "Desktop" ? "i-desktop" : "i-game")}
          ${a.id === runId ? `<span class="badge">En cours</span>` : ""}</div>
        <span class="name">${esc(displayName(a.name))}</span>
      </button>`)
    .join("");
}

$("#games").addEventListener("click", (e) => {
  const b = e.target.closest(".game");
  if (!b) return;
  b.querySelector(".art").style.opacity = ".6";
  setTimeout(() => (b.querySelector(".art").style.opacity = ""), 1500);
  N.play(current, parseInt(b.dataset.id, 10));
});
$("#g-resume").onclick = () => {
  const p = byUuid(current);
  const g = games[current];
  const id = (p && p.running) || (g && g.running);
  if (id) N.play(current, id);
};
$("#g-quit").onclick = () => {
  if (confirm("Quitter le jeu en cours sur le PC ?")) N.quit(current);
};

// ---------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------
function pairStep(id) {
  for (const s of ["p-wait", "p-done", "p-fail"]) $("#" + s).hidden = s !== id;
}

function startPairing(uuid) {
  const p = byUuid(uuid);
  if (!p) return;
  if (stateOf(p) !== "online") return toast("Ce PC n'est pas joignable pour l'instant.");
  pairing = { uuid, name: p.name };
  $("#p-name").textContent = p.name;
  $("#p-name2").textContent = p.name;
  const pin = N.pair(uuid);
  $("#p-code").innerHTML = String(pin).split("").map((d) => `<span>${esc(d)}</span>`).join("");
  pairStep("p-wait");
  openSheet("s-pair");
}
$("#p-retry").onclick = () => pairing && startPairing(pairing.uuid);

// ---------------------------------------------------------------------
// QR code of Eclipse for PC (the recommended way to connect)
// ---------------------------------------------------------------------
let qrHintTimer = null;
let qrDoneUuid = null;

function scanQr() {
  closeSheet("s-connect");
  N.scanQr();
}
$("#btn-scan").onclick = scanQr;
$("#c-scan").onclick = scanQr;
$("#q-retry").onclick = () => {
  closeSheet("s-qr");
  N.scanQr();
};
$("#q-cancel").onclick = () => closeSheet("s-qr");

function openConnect(uuid) {
  const p = byUuid(uuid);
  if (!p) return;
  $("#c-name").textContent = p.name;
  $("#c-name2").textContent = p.name;
  $("#c-code").onclick = () => {
    closeSheet("s-connect");
    startPairing(uuid);
  };
  openSheet("s-connect");
}

function qrStep(id) {
  for (const s of ["q-busy", "q-done", "q-fail"]) $("#" + s).hidden = s !== id;
}

function stopQrHint() {
  clearTimeout(qrHintTimer);
  qrHintTimer = null;
  $("#q-hint").hidden = true;
}

// hintAfter: show the "is the window still open on the PC?" hint after
// that many ms (the timer keeps running from one step to the next).
function qrBusy(title, text, hintAfter) {
  $("#q-title").textContent = title;
  $("#q-text").textContent = text || "";
  $("#q-text").hidden = !text;
  if (!hintAfter) stopQrHint();
  else if (!qrHintTimer) qrHintTimer = setTimeout(() => ($("#q-hint").hidden = false), hintAfter);
  qrStep("q-busy");
  openSheet("s-qr");
}

function qrEvent(ev) {
  const name = ev.name || "ton PC";
  switch (ev.stage) {
    case "preparing":
      return qrBusy("Préparation du scanner…", "La première fois, Google Play installe le scanner. Ça prend quelques secondes.");
    case "cancelled":
      if (!$("#q-busy").hidden) closeSheet("s-qr", true);
      return;
    case "search":
      return qrBusy(`Recherche de ${name}…`, "Le téléphone doit être sur le même Wi-Fi que le PC.");
    case "pair":
      return qrBusy(`Connexion à ${name}…`, "Eclipse sur le PC accepte le téléphone tout seul.", 20000);
    case "busy":
      return qrBusy(`Connexion à ${name}…`, "Une autre demande de connexion attend sur le PC. Nouvel essai dans un instant…", 20000);
    case "done":
      stopQrHint();
      $("#q-name").textContent = name;
      qrStep("q-done");
      openSheet("s-qr");
      qrDoneUuid = ev.uuid;
      if (ev.remote && (ev.tunnel ? ev.tunnelState !== "running" : !ev.tailscale)) {
        // Away from home needs Tailscale: say it now, while at home.
        $("#q-remote").hidden = false;
        const acct = `<b>${esc(ev.account || "le même compte que sur le PC")}</b>`;
        $("#q-remote p").innerHTML = ev.tunnel
          ? `Connecte Eclipse à Tailscale, une seule fois, avec ${acct}. Pas besoin de l'appli Tailscale.`
          : `Installe <b>Tailscale</b> (gratuit) et connecte-toi avec ${acct}.`;
        $("#q-ts").textContent = ev.tunnel ? "Se connecter à Tailscale" : "Installer Tailscale";
        $("#q-ts").dataset.tunnel = ev.tunnel ? "1" : "";
        $("#q-later").onclick = () => {
          closeSheet("s-qr", true);
          if (ev.uuid) openGames(ev.uuid);
        };
        return;
      }
      $("#q-remote").hidden = true;
      setTimeout(() => {
        if ($("#q-done").hidden || qrDoneUuid !== ev.uuid) return;
        closeSheet("s-qr", true);
        if (ev.uuid) openGames(ev.uuid);
      }, 1400);
      return;
    case "error":
      stopQrHint();
      $("#q-err").textContent = ev.error || "La connexion a échoué.";
      $("#q-fix").hidden = ev.fix !== "tailscale" && ev.fix !== "tunnel";
      $("#q-fix").dataset.fix = ev.fix || "";
      $("#q-fix").lastChild.textContent = ev.fix === "tunnel" ? "Connexion Tailscale" : ev.installed === false ? "Installer Tailscale" : "Ouvrir Tailscale";
      qrStep("q-fail");
      openSheet("s-qr");
      return;
  }
}

// ---------------------------------------------------------------------
// Playing away from home (Tailscale)
// ---------------------------------------------------------------------
let remotePc = null;

function openRemote(uuid) {
  remotePc = uuid || null;
  renderRemote();
  openSheet("s-remote");
  N.tailscale();
}

function renderRemote() {
  const p = remotePc ? byUuid(remotePc) : pcs.find((x) => x.remote);
  const account = p && p.account ? p.account : "";
  const acc = account ? `<b>${esc(account)}</b>` : "le même compte que sur le PC";
  let steps = [];
  $("#r-out").hidden = true;
  $("#r-go").disabled = false;
  if (tun.available) {
    const st = tun.state || "stopped";
    if (st === "running") {
      $("#r-text").textContent = "Eclipse est connecté à Tailscale : tes PC sont joignables partout, même en 4G.";
      steps = [`Compte : <b>${esc(tun.account || "?")}</b>`];
      if (account && tun.account && account.toLowerCase() !== tun.account.toLowerCase()) {
        steps.push(`<span class="warn">Ton PC utilise un autre compte (<b>${esc(account)}</b>) : déconnecte-toi puis reconnecte-toi avec celui-là.</span>`);
      }
      if (!p) steps.push("Scanne une fois le QR code d'Eclipse sur ton PC, à la maison.");
      $("#r-go").textContent = "C'est bon";
      $("#r-out").hidden = false;
    } else if (st === "starting" || (tunLoginAsked && !tun.error)) {
      $("#r-text").innerHTML = tunLoginAsked && st !== "starting"
        ? "La page de connexion Tailscale s'ouvre dans le navigateur. Connecte-toi, puis reviens ici."
        : `<span class="spin"></span>Connexion au réseau privé…`;
      $("#r-go").textContent = "Rouvrir la page de connexion";
      $("#r-go").disabled = st === "starting";
    } else if (st === "approval") {
      $("#r-text").textContent = "Ce téléphone attend d'être approuvé dans la console Tailscale (login.tailscale.com).";
      $("#r-go").textContent = "Réessayer";
    } else {
      $("#r-text").textContent = tun.error ? tun.error : "Eclipse a Tailscale intégré : pas besoin d'installer l'appli Tailscale. Il suffit de te connecter, une seule fois.";
      steps = ["Touche <b>Se connecter</b> : la page Tailscale s'ouvre.", `Connecte-toi avec ${acc} (par exemple « Sign in with Google »).`, "Reviens dans Eclipse : ton PC devient joignable partout."];
      $("#r-go").textContent = "Se connecter";
    }
    $("#r-steps").innerHTML = steps.map((s) => `<li>${s}</li>`).join("");
    $("#r-foot").textContent = st === "running" && tun.ip ? "Adresse de ce téléphone dans le réseau privé : " + tun.ip : "";
    return;
  }
  if (tun.failed) {
    $("#r-text").textContent = "Le Tailscale intégré à Eclipse ne fonctionne pas sur ce téléphone. Tu peux utiliser l'appli Tailscale à la place :";
    steps = [`Installe <b>Tailscale</b> et connecte-toi avec ${acc}.`, "Active-le, puis reviens dans Eclipse.", "Pour aider à corriger le problème : Réglages → Rapport technique."];
  } else if (!p) {
    $("#r-text").textContent = "Avec Tailscale (gratuit), ton téléphone retrouve ton PC partout, même en 4G.";
    steps = ["Sur le PC, dans Eclipse, active <b>Jouer hors de chez toi</b>.", "Sur ce téléphone, installe <b>Tailscale</b> et connecte-toi avec le même compte.", "À la maison, scanne une fois le QR code d'Eclipse."];
  } else if (!ts.installed) {
    $("#r-text").textContent = `Pour jouer à ${p.name} hors de chez toi, il faut l'appli Tailscale (gratuite) sur ce téléphone.`;
    steps = ["Installe <b>Tailscale</b>.", `Ouvre-la et connecte-toi avec ${acc}.`, "Reviens dans Eclipse : le PC apparaît en ligne."];
  } else if (!ts.vpn) {
    $("#r-text").textContent = "Tailscale est installé mais pas activé.";
    steps = ["Ouvre <b>Tailscale</b>.", `Active-le (connecté avec ${acc}).`, "Reviens dans Eclipse : le PC apparaît en ligne."];
  } else {
    $("#r-text").textContent = "Tailscale est actif sur ce téléphone.";
    steps = p ? [`Vérifie que ${esc(p.name)} est allumé, avec Eclipse et Tailscale activés.`, `Les deux appareils doivent utiliser ${acc}.`] : [];
  }
  $("#r-steps").innerHTML = steps.map((s) => `<li>${s}</li>`).join("");
  $("#r-go").textContent = ts.installed ? "Ouvrir Tailscale" : "Installer Tailscale";
  $("#r-foot").textContent = ts.installed && !ts.vpn ? "Astuce : laisse Tailscale activé, il consomme très peu de batterie." : "";
}

function setRemoteSub() {
  $("#set-remote-sub").textContent = tun.available
    ? (tunOn() ? "Connecté à Tailscale" + (tun.account ? " · " + tun.account : "") : "Tailscale intégré, pas encore connecté")
    : !ts.installed ? "Avec Tailscale, même en 4G" : ts.vpn ? "Tailscale est actif" : "Tailscale est installé, pas actif";
}

function tunnelLogin() {
  tunLoginAsked = true;
  N.tunnelLogin();
  if (!$("#s-remote").hidden) renderRemote();
}

$("#r-go").onclick = () => {
  if (!tun.available) return N.openTailscale();
  if (tunOn()) return closeSheet("s-remote");
  tunnelLogin();
};
$("#r-out").onclick = () => {
  if (confirm("Déconnecter Eclipse de ce compte Tailscale ? Ton PC ne sera plus joignable hors de chez toi.")) N.tunnelLogout();
};
$("#q-ts").onclick = () => {
  if ($("#q-ts").dataset.tunnel) {
    closeSheet("s-qr", true);
    openRemote(qrDoneUuid);
    return tunnelLogin();
  }
  N.openTailscale();
};
$("#q-fix").onclick = () => {
  if ($("#q-fix").dataset.fix === "tunnel") {
    closeSheet("s-qr", true);
    openRemote(null);
    if (!tunOn()) tunnelLogin();
    return;
  }
  N.openTailscale();
};
$("#set-remote").onclick = () => {
  closeSheet("s-settings");
  openRemote(null);
};

// ---------------------------------------------------------------------
// Add a PC, PC menu, settings
// ---------------------------------------------------------------------
$("#btn-add").onclick = () => {
  $("#a-err").textContent = "";
  $("#a-addr").value = "";
  openSheet("s-add");
  setTimeout(() => $("#a-addr").focus(), 250);
};
$("#a-go").onclick = () => {
  const v = $("#a-addr").value.trim();
  if (!v) {
    $("#a-err").textContent = "Entre l'adresse du PC.";
    return;
  }
  $("#a-err").textContent = "";
  $("#a-go").disabled = true;
  $("#a-go").innerHTML = `<span class="spin"></span>Recherche du PC…`;
  N.addComputer(v);
};
$("#a-addr").addEventListener("keydown", (e) => e.key === "Enter" && $("#a-go").click());

function openMenu(uuid) {
  const p = byUuid(uuid);
  if (!p) return;
  menuPc = uuid;
  $("#m-name").textContent = p.name;
  $("#m-addr").textContent = p.address || "";
  $("#m-wake").hidden = !(p.canWake && stateOf(p) !== "online");
  $("#m-pair").hidden = !(stateOf(p) === "online" && !p.paired);
  openSheet("s-pc");
}
$("#m-wake").onclick = () => { closeSheet("s-pc"); N.wake(menuPc); };
$("#m-pair").onclick = () => { closeSheet("s-pc"); openConnect(menuPc); };
$("#m-remove").onclick = () => {
  const p = byUuid(menuPc);
  if (!confirm(`Retirer « ${p ? p.name : "ce PC"} » ? Il faudra le reconnecter pour jouer.`)) return;
  closeSheet("s-pc");
  N.remove(menuPc);
  if (current === menuPc) closeGames();
};

$("#btn-settings").onclick = () => openSheet("s-settings");
$("#set-stream").onclick = () => { closeSheet("s-settings"); N.openSettings(); };
$("#set-classic").onclick = () => { closeSheet("s-settings"); N.openClassic(); };
$("#set-diag").onclick = () => {
  closeSheet("s-settings");
  $("#diag-text").textContent = "Préparation du rapport…";
  openSheet("s-diag");
  N.diagnostic();
};
$("#d-copy").onclick = () => {
  N.copy($("#diag-text").textContent);
  toast("Rapport copié : colle-le dans ton message.");
};
$("#ver").textContent = "Eclipse " + N.version();

// ---------------------------------------------------------------------
// Sheets and back button
// ---------------------------------------------------------------------
function openSheet(id) {
  $("#" + id).hidden = false;
}
function closeSheet(id, quiet) {
  const s = $("#" + id);
  const wasOpen = !s.hidden;
  s.hidden = true;
  if (id === "s-pair" && pairing) {
    pairing = null;
    if (wasOpen && !quiet) N.cancelPairing();
  }
  if (id === "s-qr") {
    stopQrHint();
    qrDoneUuid = null;
    // Closing while it still works stops it (the scan itself is over).
    if (wasOpen && !quiet && !$("#q-busy").hidden) N.cancelPairing();
  }
}
for (const b of $$("[data-close]")) b.onclick = () => closeSheet(b.dataset.close);
for (const s of $$(".sheet")) s.addEventListener("click", (e) => { if (e.target === s) closeSheet(s.id); });

// Called by the Android back button: true when the page handled it.
window.eclipseBack = () => {
  const open = $$(".sheet").find((s) => !s.hidden);
  if (open) {
    closeSheet(open.id);
    return true;
  }
  if (current) {
    closeGames();
    return true;
  }
  return false;
};

renderPcs();
N.refresh();
N.tailscale();

// ---------------------------------------------------------------------
// Demo data, only when this page is opened outside the app
// ---------------------------------------------------------------------
function demoNative() {
  const later = (f, ms = 400) => setTimeout(f, ms);
  const list = [
    { uuid: "a", name: "Lenovo", state: "ONLINE", paired: true, address: "192.168.1.20", running: 2, canWake: true },
    { uuid: "b", name: "PC-SALON", state: "ONLINE", paired: false, address: "192.168.1.31", running: 0, canWake: true },
    { uuid: "c", name: "PC-BUREAU", state: "OFFLINE", paired: true, address: "192.168.1.44", running: 0, canWake: true, remote: true, account: "marius@gmail.com" },
  ];
  const art = (c1, c2) => "data:image/svg+xml," + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs><rect width="300" height="400" fill="url(#g)"/></svg>`);
  return {
    version: () => "2.0.0 (aperçu)",
    refresh: () => (location.search.includes("empty") ? null : later(() => window.ec.on("computers", list), 600)),
    apps: (u) => later(() => window.ec.on("apps", { uuid: u, running: 2, apps: [
      { id: 1, name: "Desktop" }, { id: 2, name: "Cyberpunk 2077", art: art("#ff6a3d", "#5b2a86") },
      { id: 3, name: "Hades II", art: art("#7a1f2b", "#f2b544") }, { id: 4, name: "Rocket League", art: art("#1e6fd9", "#13c3d1") },
      { id: 5, name: "Steam Big Picture" }, { id: 6, name: "Minecraft", art: art("#3f8f3a", "#8bd06a") } ] })),
    pair: (u) => { later(() => window.ec.on("paired", { uuid: u, ok: true }), 4000); return "4827"; },
    scanQr: () => {
      const fail = location.search.includes("qrfail");
      later(() => window.ec.on("qr", { stage: "search", name: "PC-SALON" }), 300);
      later(() => window.ec.on("qr", { stage: "pair", name: "PC-SALON", uuid: "b" }), 1500);
      later(() => window.ec.on("qr", fail
        ? { stage: "error", name: "PC-SALON", error: "Ce QR code n'est plus valable. Rescanne le QR code affiché sur le PC." }
        : { stage: "done", name: "PC-SALON", uuid: "b", remote: true, account: "marius@gmail.com", tailscale: false, tunnel: true, tunnelState: window.__tun || "needslogin" }), 3500);
    },
    openTailscale: () => toast("Ouverture de Tailscale…"),
    tailscale: () => {
      later(() => window.ec.on("tailscale", { installed: location.search.includes("ts"), vpn: false }), 100);
      later(() => window.ec.on("tunnel", location.search.includes("tunfail") ? { available: false, failed: true }
        : { available: !location.search.includes("notun"), state: window.__tun || "needslogin" }), 150);
    },
    tunnelLogin: () => {
      later(() => window.ec.on("tunnel", { available: true, state: "needslogin", authUrl: "https://login.tailscale.com/a/x" }), 300);
      later(() => { window.__tun = "running"; window.ec.on("tunnel", { available: true, state: "running", ip: "100.88.1.2", account: "marius@gmail.com" }); }, 2500);
    },
    tunnelLogout: () => later(() => { window.__tun = "needslogin"; window.ec.on("tunnel", { available: true, state: "needslogin" }); }, 300),
    cancelPairing: () => {},
    addComputer: () => later(() => window.ec.on("added", { ok: false, error: "Aucun PC ne répond à cette adresse." }), 900),
    play: () => toast("Lancement…"), quit: () => toast("Jeu quitté"), wake: () => toast("Réveil envoyé"),
    remove: () => {}, openSettings: () => {}, openClassic: () => {}, toast: (m) => toast(m),
    diagnostic: () => later(() => window.ec.on("diag", "Eclipse 2.2.1 (aperçu)\nAndroid 14 (API 34), Google Pixel 7, arm64-v8a\nTailscale intégré : oui\nAucun arrêt anormal récent.\n")),
    copy: () => {},
  };
}
