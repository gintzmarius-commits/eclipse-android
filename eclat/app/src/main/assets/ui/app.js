// Éclat — the interface. Talks to MainActivity through window.EclatNative
// (call(id, method, args) → eclat.resolve(id, result)) and receives its
// events through eclat.on(event, data).
'use strict';

const $ = id => document.getElementById(id);
const native = window.EclatNative;
const handlers = {};
const waiting = {};
let seq = 0;

function call(method, args = {}) {
  if (!native) return Promise.resolve(demo(method, args));
  return new Promise(resolve => {
    const id = 'c' + (++seq);
    waiting[id] = resolve;
    native.call(id, method, JSON.stringify(args));
  });
}

window.eclat = {
  resolve(id, r) { const f = waiting[id]; delete waiting[id]; if (f) f(r); },
  on(ev, data) { if (handlers[ev]) handlers[ev](data); },
  insets(t, b, l, r) {
    const s = document.documentElement.style;
    s.setProperty('--st', t + 'px'); s.setProperty('--sb', b + 'px');
    s.setProperty('--sl', l + 'px'); s.setProperty('--sr', r + 'px');
  },
};

// ------------------------------------------------------------------
// Small helpers

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = encodeURIComponent;

function fmtSize(n) {
  if (n == null || n < 0) return '';
  if (n < 1024) return n + ' o';
  const u = ['Ko', 'Mo', 'Go', 'To'];
  let v = n / 1024, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toLocaleString('fr-FR', { maximumFractionDigits: v < 10 ? 1 : 0 }) + ' ' + u[i];
}
function fmtDur(ms) {
  if (!ms || ms < 0) return '';
  const s = Math.round(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
}
function fmtDate(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' }) + ' à '
    + new Date(ms).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}
function monthOf(ms) {
  const d = new Date(ms || 0);
  const now = new Date();
  const label = d.toLocaleDateString('fr-FR', { month: 'long', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
  return { key: d.getFullYear() * 12 + d.getMonth(), label };
}
function thumbUrl(it, size = 320) {
  if (!native) return it.demo || '';
  return `/thumb?u=${enc(it.uri)}&m=${enc(it.mime || '')}&s=${size}`;
}
function imgUrl(it) {
  if (!native) return it.demoBig || it.demo || '';
  return `/img?u=${enc(it.uri)}&m=${enc(it.mime || '')}`;
}
const isImage = it => (it.mime || '').startsWith('image/');
const isVideo = it => (it.mime || '').startsWith('video/');
const isAudio = it => (it.mime || '').startsWith('audio/');
const icon = (id, cls = '') => `<svg class="${cls}"><use href="#${id}"/></svg>`;
function kindIcon(it) {
  if (it.dir) return 'i-folder-f';
  return { image: 'i-photo', video: 'i-video', audio: 'i-music', pdf: 'i-doc', text: 'i-doc', archive: 'i-zip', apk: 'i-android' }[it.kind || kindOf(it.mime)] || 'i-file';
}
function kindOf(mime = '') {
  return mime.startsWith('image/') ? 'image' : mime.startsWith('video/') ? 'video' : mime.startsWith('audio/') ? 'audio' : 'file';
}

let toastTimer;
function toast(msg, bad = false, ms = 3000) {
  document.querySelectorAll('.toast').forEach(t => t.remove());
  const t = document.createElement('div');
  t.className = 'toast' + (bad ? ' bad' : '');
  t.textContent = msg;
  document.body.appendChild(t);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.remove(), ms);
}

/** Tap and long press on an element (a scroll cancels both). */
function press(el, onTap, onLong) {
  let timer = null, x = 0, y = 0, long = false, down = false;
  el.addEventListener('pointerdown', e => {
    if (e.button > 0) return;
    down = true; long = false; x = e.clientX; y = e.clientY;
    clearTimeout(timer);
    timer = setTimeout(() => { if (down) { long = true; down = false; onLong && onLong(e); } }, 480);
  });
  el.addEventListener('pointermove', e => {
    if (down && Math.hypot(e.clientX - x, e.clientY - y) > 9) { down = false; clearTimeout(timer); }
  });
  const cancel = () => { down = false; clearTimeout(timer); };
  el.addEventListener('pointercancel', cancel);
  el.addEventListener('pointerleave', cancel);
  el.addEventListener('pointerup', () => {
    clearTimeout(timer);
    if (down && !long) onTap && onTap();
    down = false;
  });
  el.addEventListener('contextmenu', e => e.preventDefault());
}

// ------------------------------------------------------------------
// State, tabs

const S = {
  status: {},
  tab: 'photos',
  loaded: {},
  photos: { bucket: '', items: [], offset: 0, done: false, loading: false, lastMonth: null, grid: null },
  videos: [],
  music: [],
  files: { path: '', home: '', items: [] },
};

function showTab(tab) {
  S.tab = tab;
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
  const needMedia = tab !== 'files';
  const blocked = needMedia && !S.status.media && !(tab === 'music' && S.status.music);
  $('v-perm').hidden = !blocked;
  for (const v of ['photos', 'videos', 'music', 'files']) $('v-' + v).hidden = blocked || v !== tab;
  $('content').scrollTop = 0;
  if (!blocked && !S.loaded[tab]) load(tab);
}

function load(tab) {
  S.loaded[tab] = true;
  if (tab === 'photos') { loadAlbums(); resetPhotos(); }
  if (tab === 'videos') loadVideos();
  if (tab === 'music') loadMusic();
  if (tab === 'files') openFolder(S.files.path || '');
}

document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => {
  if (S.tab === b.dataset.tab) { $('content').scrollTo({ top: 0, behavior: 'smooth' }); return; }
  showTab(b.dataset.tab);
}));

// ------------------------------------------------------------------
// Photos

async function loadAlbums() {
  const albums = await call('albums');
  if (!Array.isArray(albums)) return;
  const box = $('albums');
  const total = albums.reduce((n, a) => n + a.count, 0);
  const chip = (id, name, count) => `<button class="chip${S.photos.bucket === id ? ' on' : ''}" data-id="${esc(id)}">${esc(name)} <small>${count}</small></button>`;
  box.innerHTML = chip('', 'Tout', total) + albums.map(a => chip(a.id, a.name === 'Camera' ? 'Appareil photo' : a.name === 'Screenshots' ? "Captures d'écran" : a.name, a.count)).join('');
  box.querySelectorAll('.chip').forEach(c => c.addEventListener('click', () => {
    S.photos.bucket = c.dataset.id;
    box.querySelectorAll('.chip').forEach(x => x.classList.toggle('on', x === c));
    resetPhotos();
  }));
  $('ph-count').textContent = total ? total.toLocaleString('fr-FR') + ' éléments' : '';
}

function resetPhotos() {
  Object.assign(S.photos, { items: [], offset: 0, done: false, loading: false, lastMonth: null, grid: null });
  $('photos').innerHTML = '';
  morePhotos();
}

async function morePhotos() {
  const P = S.photos;
  if (P.loading || P.done) return;
  P.loading = true;
  $('ph-more-load').hidden = false;
  const bucket = P.bucket;
  const list = await call('media', { kind: 'all', bucket, offset: P.offset, limit: 240 });
  if (bucket !== P.bucket) return;
  P.loading = false;
  $('ph-more-load').hidden = true;
  if (!Array.isArray(list)) return;
  P.offset += list.length;
  if (list.length < 240) P.done = true;
  const start = P.items.length;
  P.items.push(...list);
  renderPhotos(list, start);
  if (!P.items.length) $('photos').innerHTML = `<div class="panel note"><div class="big-ic">${icon('i-photo')}</div><strong>Aucune photo ici</strong></div>`;
}

function renderPhotos(list, start) {
  const P = S.photos;
  const box = $('photos');
  list.forEach((it, i) => {
    const m = monthOf(it.date);
    if (m.key !== P.lastMonth) {
      P.lastMonth = m.key;
      const h = document.createElement('div');
      h.className = 'month'; h.textContent = m.label;
      box.appendChild(h);
      P.grid = document.createElement('div');
      P.grid.className = 'grid';
      box.appendChild(P.grid);
    }
    P.grid.appendChild(cell(it, start + i, () => P.items));
  });
  observeEnd(box);
}

function cell(it, index, listFn) {
  const b = document.createElement('button');
  b.className = 'cell';
  b.innerHTML = `<img loading="lazy" decoding="async" alt="">` + (isVideo(it) ? `<span class="dur">${icon('i-play')}${fmtDur(it.duration)}</span>` : '');
  const img = b.firstChild;
  img.onload = () => img.classList.add('in');
  img.src = thumbUrl(it, 288);
  press(b, () => openViewer(listFn(), index), () => openMenu(it));
  return b;
}

let endObserver;
function observeEnd(box) {
  if (!('IntersectionObserver' in window)) return;
  if (!endObserver) {
    endObserver = new IntersectionObserver(es => {
      if (es.some(e => e.isIntersecting)) morePhotos();
    }, { root: $('content'), rootMargin: '1200px 0px' });
  }
  endObserver.disconnect();
  const last = box.lastElementChild && box.lastElementChild.lastElementChild;
  if (last) endObserver.observe(last);
}

// ------------------------------------------------------------------
// Videos

async function loadVideos() {
  const list = await call('media', { kind: 'video', offset: 0, limit: 2000 });
  S.videos = Array.isArray(list) ? list : [];
  const box = $('videos');
  box.innerHTML = '';
  $('vi-count').textContent = S.videos.length ? S.videos.length + ' vidéo' + (S.videos.length > 1 ? 's' : '') : '';
  if (!S.videos.length) {
    box.innerHTML = `<div class="panel note" style="grid-column:1/-1"><div class="big-ic">${icon('i-video')}</div><strong>Aucune vidéo</strong></div>`;
    return;
  }
  S.videos.forEach((it, i) => {
    const w = document.createElement('div');
    w.className = 'vitem';
    // a tap plays it right away, with the next ones after it
    const c2 = document.createElement('button');
    c2.className = 'cell';
    c2.innerHTML = `<img loading="lazy" decoding="async" alt=""><span class="dur">${icon('i-play')}${fmtDur(it.duration)}</span>`;
    const img = c2.firstChild;
    img.onload = () => img.classList.add('in');
    img.src = thumbUrl(it, 480);
    press(c2, () => play(S.videos, i), () => openMenu(it));
    w.appendChild(c2);
    const n = document.createElement('div');
    n.className = 'vname'; n.textContent = it.name.replace(/\.[^.]+$/, '');
    w.appendChild(n);
    box.appendChild(w);
  });
}

function play(list, index) {
  call('play', { items: list.map(x => ({ uri: x.uri, name: x.name, title: x.title || (x.name || '').replace(/\.[^.]+$/, ''), artist: x.artist || '', mime: x.mime, size: x.size })), index });
}

// ------------------------------------------------------------------
// Music

async function loadMusic() {
  const list = await call('music');
  S.music = Array.isArray(list) ? list : [];
  const box = $('music');
  box.innerHTML = '';
  $('mu-head').hidden = !S.music.length;
  $('mu-count').textContent = S.music.length ? S.music.length + ' titre' + (S.music.length > 1 ? 's' : '') : '';
  if (!S.music.length) {
    box.innerHTML = `<div class="panel note"><div class="big-ic">${icon('i-music')}</div><strong>Aucune musique</strong><p class="muted small">Les fichiers audio de ton téléphone apparaissent ici.</p></div>`;
    return;
  }
  const frag = document.createDocumentFragment();
  S.music.forEach((it, i) => {
    const row = document.createElement('div');
    row.className = 'item';
    row.innerHTML = `<div class="ic audio">${icon('i-music')}<img loading="lazy" alt=""></div>
      <div class="txt"><strong>${esc(it.title)}</strong><span>${esc([it.artist, it.album].filter(Boolean).join(' · ') || fmtDur(it.duration))}</span></div>
      <span class="small muted">${fmtDur(it.duration)}</span>`;
    const img = row.querySelector('img');
    img.onload = () => img.classList.add('in');
    img.onerror = () => img.remove();
    img.src = thumbUrl(it, 128);
    press(row, () => play(S.music, i), () => openMenu(it));
    frag.appendChild(row);
  });
  box.appendChild(frag);
}
$('mu-play').onclick = () => play(S.music, 0);
$('mu-shuffle').onclick = () => {
  const l = S.music.slice();
  for (let i = l.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [l[i], l[j]] = [l[j], l[i]]; }
  play(l, 0);
};

// ------------------------------------------------------------------
// Files

async function loadPlaces() {
  const places = await call('places');
  if (!Array.isArray(places)) return;
  const box = $('places');
  box.innerHTML = places.map(p => `<button class="chip" data-path="${esc(p.path)}">${esc(p.name)}</button>`).join('');
  box.querySelectorAll('.chip').forEach(c => c.addEventListener('click', () => openFolder(c.dataset.path)));
}

async function openFolder(path) {
  const r = await call('list', { path });
  if (!r || r.error) { toast("Impossible d'ouvrir ce dossier", true); return; }
  S.files = r;
  $('fi-perm').hidden = !!S.status.allFiles;
  document.querySelectorAll('#places .chip').forEach(c => c.classList.toggle('on', c.dataset.path === r.path));
  // breadcrumbs from the phone's storage
  const rel = r.path.startsWith(r.home) ? r.path.slice(r.home.length) : r.path;
  const parts = rel.split('/').filter(Boolean);
  let acc = r.path.startsWith(r.home) ? r.home : '';
  const crumbs = [`<button data-path="${esc(r.home)}">Stockage</button>`];
  for (const p of parts) { acc += '/' + p; crumbs.push(icon('i-chev') + `<button data-path="${esc(acc)}">${esc(p)}</button>`); }
  $('crumbs').innerHTML = crumbs.join('');
  $('crumbs').querySelectorAll('button').forEach(b => b.onclick = () => openFolder(b.dataset.path));
  $('crumbs').scrollLeft = 1e6;
  const box = $('files');
  box.innerHTML = '';
  if (!r.readable) {
    box.innerHTML = `<div class="panel note"><div class="big-ic">${icon('i-lock')}</div><strong>Dossier inaccessible</strong><p class="muted small">Android ne laisse pas Éclat lire ce dossier.</p></div>`;
    return;
  }
  if (!r.items.length) {
    box.innerHTML = `<div class="panel note"><div class="big-ic">${icon('i-folder')}</div><strong>Dossier vide</strong></div>`;
    return;
  }
  const frag = document.createDocumentFragment();
  r.items.forEach(it => {
    const row = document.createElement('div');
    row.className = 'item';
    const k = it.dir ? 'folder' : it.kind;
    const meta = it.dir ? (it.count >= 0 ? it.count + ' élément' + (it.count > 1 ? 's' : '') : 'Dossier')
      : fmtSize(it.size) + ' · ' + new Date(it.date).toLocaleDateString('fr-FR');
    row.innerHTML = `<div class="ic ${k}">${icon(kindIcon(it))}${(k === 'image' || k === 'video') ? '<img loading="lazy" alt="">' : ''}</div>
      <div class="txt"><strong>${esc(it.name)}</strong><span>${esc(meta)}</span></div>${it.dir ? icon('i-chev', 'chev') : ''}`;
    const img = row.querySelector('img');
    if (img) { img.onload = () => img.classList.add('in'); img.onerror = () => img.remove(); img.src = thumbUrl(it, 128); }
    press(row, () => openFileItem(it), it.dir ? null : () => openMenu(it));
    frag.appendChild(row);
  });
  box.appendChild(frag);
  $('content').scrollTop = 0;
}

function openFileItem(it) {
  if (it.dir) { openFolder(it.path); return; }
  const files = S.files.items.filter(x => !x.dir);
  if (it.kind === 'image') {
    const imgs = files.filter(x => x.kind === 'image');
    openViewer(imgs, imgs.indexOf(it));
  } else if (it.kind === 'video' || it.kind === 'audio') {
    const same = files.filter(x => x.kind === it.kind);
    play(same, same.indexOf(it));
  } else {
    call('open', { uri: it.uri, mime: it.mime, name: it.name });
  }
}

$('fi-allow').onclick = async () => {
  const st = await call('askAllFiles');
  if (st) applyStatus(st);
  openFolder(S.files.path);
};

// ------------------------------------------------------------------
// Long press menu, details

let menuItem = null;
function openMenu(it) {
  menuItem = it;
  $('m-name').textContent = it.name || it.title;
  $('m-meta').textContent = [fmtSize(it.size), it.duration ? fmtDur(it.duration) : '', it.date ? new Date(it.date).toLocaleDateString('fr-FR') : ''].filter(Boolean).join(' · ');
  const ic = $('m-ic');
  ic.className = 'ic ' + (it.kind || kindOf(it.mime));
  ic.innerHTML = icon(kindIcon(it)) + ((isImage(it) || isVideo(it) || isAudio(it)) ? '<img alt="" hidden>' : '');
  const img = ic.querySelector('img');
  if (img) { img.onload = () => { img.hidden = false; }; img.src = thumbUrl(it, 160); }
  openSheet('s-menu');
}
$('m-open').onclick = () => {
  closeSheet('s-menu');
  const it = menuItem;
  if (isImage(it)) openViewer([it], 0);
  else if (isVideo(it) || isAudio(it)) play([it], 0);
  else call('open', { uri: it.uri, mime: it.mime, name: it.name });
};
$('m-nfc').onclick = () => { closeSheet('s-menu', true); openSend(menuItem, false); };
$('m-share').onclick = () => { closeSheet('s-menu'); call('shareAndroid', { uri: menuItem.uri, mime: menuItem.mime }); };
$('m-with').onclick = () => { closeSheet('s-menu'); call('openWith', { uri: menuItem.uri, mime: menuItem.mime }); };
$('m-info').onclick = () => { closeSheet('s-menu', true); showInfo(menuItem); };

async function showInfo(it) {
  $('i-name').textContent = it.name || it.title;
  const d = (await call('details', { uri: it.uri })) || {};
  const rows = [
    ['Type', it.mime],
    ['Taille', fmtSize(d.size ?? it.size)],
    ['Date', fmtDate(it.date || d.date)],
    ['Dimensions', d.res || (it.w ? it.w + ' × ' + it.h : '')],
    ['Durée', fmtDur(it.duration)],
    ['Artiste', it.artist], ['Album', it.album],
    ['Emplacement', d.path || it.path],
  ].filter(r => r[1]);
  $('i-kv').innerHTML = rows.map(r => `<dt>${esc(r[0])}</dt><dd>${esc(r[1])}</dd>`).join('');
  openSheet('s-info');
}

function openSheet(id) { $(id).hidden = false; }
function closeSheet(id) { $(id).hidden = true; }
document.querySelectorAll('.sheet').forEach(s => s.addEventListener('click', e => { if (e.target === s) closeSheet(s.id); }));
document.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', () => closeSheet(b.dataset.close)));

// ------------------------------------------------------------------
// Photo viewer: swipe sideways for the next one, down to close,
// UP TO SEND BY NFC. Pinch or double tap to zoom.

const V = { list: [], index: 0, slides: [], s: 1, tx: 0, ty: 0, bare: false };
const viewer = $('viewer'), stage = $('stage');

function openViewer(list, index) {
  if (!list.length) return;
  V.list = list; V.index = Math.max(0, index);
  viewer.hidden = false;
  viewer.style.setProperty('--bg', 1);
  viewer.classList.remove('bare');
  buildSlides();
  let seen = 0;
  try { seen = +localStorage.getItem('swipeHint') || 0; } catch (e) { }
  $('vw-hint').hidden = seen >= 3;
  if (seen < 3) { try { localStorage.setItem('swipeHint', seen + 1); } catch (e) { } }
}

function closeViewer() {
  viewer.hidden = true;
  stage.innerHTML = '';
  V.slides = [];
}

function slideFor(it) {
  const d = document.createElement('div');
  d.className = 'slide';
  if (!it) return d;
  const img = document.createElement('img');
  img.decoding = 'async';
  img.alt = '';
  // the small preview first, the whole photo right after
  img.src = thumbUrl(it, 512);
  if (isImage(it)) {
    const big = new Image();
    big.onload = () => { img.src = big.src; };
    big.src = imgUrl(it);
  }
  d.appendChild(img);
  if (isVideo(it)) {
    const p = document.createElement('div');
    p.className = 'play-big';
    p.innerHTML = icon('i-play');
    d.appendChild(p);
  }
  return d;
}

function buildSlides() {
  stage.innerHTML = '';
  V.slides = [-1, 0, 1].map(o => {
    const s = slideFor(V.list[V.index + o]);
    stage.appendChild(s);
    return s;
  });
  resetZoom();
  layoutSlides(0);
  const it = V.list[V.index];
  $('vw-name').textContent = it.name || '';
  $('vw-date').textContent = it.date ? fmtDate(it.date) : (V.list.length > 1 ? (V.index + 1) + ' / ' + V.list.length : '');
}

function layoutSlides(dx, anim) {
  const W = stage.clientWidth + 16;
  V.slides.forEach((s, i) => {
    s.style.transition = anim ? 'transform .28s cubic-bezier(.2,.9,.3,1)' : 'none';
    s.style.transform = `translateX(${(i - 1) * W + dx}px)`;
  });
}

function curImg() { return V.slides[1] && V.slides[1].querySelector('img'); }
function applyZoom(anim) {
  const img = curImg();
  if (!img) return;
  img.style.transformOrigin = '50% 50%';
  img.style.transition = anim ? 'transform .25s cubic-bezier(.2,.9,.3,1)' : 'none';
  img.style.transform = `translate(${V.tx}px, ${V.ty}px) scale(${V.s})`;
}
function resetZoom(anim) { V.s = 1; V.tx = 0; V.ty = 0; applyZoom(anim); }
function clampPan() {
  const img = curImg();
  if (!img) return;
  const mx = Math.max(0, (img.clientWidth * V.s - stage.clientWidth) / 2);
  const my = Math.max(0, (img.clientHeight * V.s - stage.clientHeight) / 2);
  V.tx = Math.max(-mx, Math.min(mx, V.tx));
  V.ty = Math.max(-my, Math.min(my, V.ty));
}

function go(delta) {
  const ni = V.index + delta;
  if (ni < 0 || ni >= V.list.length) { layoutSlides(0, true); return; }
  const W = stage.clientWidth + 16;
  layoutSlides(-delta * W, true);
  setTimeout(() => {
    V.index = ni;
    if (delta > 0) {
      stage.removeChild(V.slides.shift());
      const s = slideFor(V.list[V.index + 1]);
      stage.appendChild(s); V.slides.push(s);
    } else {
      stage.removeChild(V.slides.pop());
      const s = slideFor(V.list[V.index - 1]);
      stage.insertBefore(s, stage.firstChild); V.slides.unshift(s);
    }
    resetZoom();
    layoutSlides(0);
    const it = V.list[V.index];
    $('vw-name').textContent = it.name || '';
    $('vw-date').textContent = it.date ? fmtDate(it.date) : (V.index + 1) + ' / ' + V.list.length;
  }, 280);
}

// gestures
const G = { pts: new Map(), mode: null, x0: 0, y0: 0, t0: 0, s0: 1, tx0: 0, ty0: 0, d0: 0, m0: null, lastTap: 0, tapTimer: null, armed: false, moved: false };
const LIFT = 110;

stage.addEventListener('pointerdown', e => {
  stage.setPointerCapture(e.pointerId);
  G.pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (G.pts.size === 1) {
    G.mode = null; G.moved = false;
    G.x0 = e.clientX; G.y0 = e.clientY; G.t0 = Date.now();
    G.tx0 = V.tx; G.ty0 = V.ty;
  } else if (G.pts.size === 2) {
    const [a, b] = [...G.pts.values()];
    G.mode = 'pinch';
    G.d0 = Math.hypot(a.x - b.x, a.y - b.y);
    G.s0 = V.s; G.tx0 = V.tx; G.ty0 = V.ty;
    G.m0 = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    layoutSlides(0, true);
  }
});

stage.addEventListener('pointermove', e => {
  if (!G.pts.has(e.pointerId)) return;
  G.pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
  const r = stage.getBoundingClientRect();
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  if (G.mode === 'pinch' && G.pts.size >= 2) {
    const [a, b] = [...G.pts.values()];
    const d = Math.hypot(a.x - b.x, a.y - b.y);
    const m = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    const s = Math.max(.8, Math.min(6, G.s0 * d / G.d0));
    // the point between the fingers stays under the fingers
    V.tx = m.x - cx - (G.m0.x - cx - G.tx0) * (s / G.s0);
    V.ty = m.y - cy - (G.m0.y - cy - G.ty0) * (s / G.s0);
    V.s = s;
    applyZoom();
    return;
  }
  const dx = e.clientX - G.x0, dy = e.clientY - G.y0;
  if (!G.moved && Math.hypot(dx, dy) > 10) {
    G.moved = true;
    if (V.s > 1.02) G.mode = 'pan';
    else if (Math.abs(dx) > Math.abs(dy)) G.mode = 'h';
    else G.mode = dy > 0 ? 'down' : 'up';
    if (G.mode === 'up' || G.mode === 'down') viewer.classList.add('bare');
  }
  const H = stage.clientHeight;
  if (G.mode === 'pan') {
    V.tx = G.tx0 + dx; V.ty = G.ty0 + dy;
    applyZoom();
  } else if (G.mode === 'h') {
    const edge = (V.index === 0 && dx > 0) || (V.index === V.list.length - 1 && dx < 0);
    layoutSlides(edge ? dx / 3 : dx);
  } else if (G.mode === 'down') {
    const k = Math.max(0, dy);
    const sc = 1 - Math.min(.35, k / H * .6);
    V.slides[1].style.transition = 'none';
    V.slides[1].style.transform = `translate(${dx}px, ${k}px) scale(${sc})`;
    viewer.style.setProperty('--bg', Math.max(0, 1 - k / (H * .5)));
  } else if (G.mode === 'up') {
    const k = Math.max(0, -dy);
    const sc = 1 - Math.min(.45, k / H * .9);
    V.slides[1].style.transition = 'none';
    V.slides[1].style.transform = `translate(${dx * .3}px, ${-k}px) scale(${sc})`;
    const armed = k > LIFT;
    const lift = $('lift');
    lift.classList.add('show');
    if (armed !== G.armed) {
      G.armed = armed;
      lift.classList.toggle('armed', armed);
      $('lift-text').textContent = armed ? 'Relâche pour envoyer par NFC' : 'Glisse pour envoyer';
      if (armed && navigator.vibrate) navigator.vibrate(15);
    }
  }
});

function endGesture(e) {
  if (!G.pts.has(e.pointerId)) return;
  G.pts.delete(e.pointerId);
  if (G.mode === 'pinch') {
    if (G.pts.size === 0) {
      if (V.s < 1.05) resetZoom(true);
      else { clampPan(); applyZoom(true); }
      G.mode = null;
    } else {
      // one finger left: keep panning from here
      const p = [...G.pts.values()][0];
      G.mode = 'pan'; G.moved = true; G.x0 = p.x; G.y0 = p.y; G.tx0 = V.tx; G.ty0 = V.ty;
    }
    return;
  }
  if (G.pts.size) return;
  const dx = e.clientX - G.x0, dy = e.clientY - G.y0;
  const dt = Math.max(1, Date.now() - G.t0);
  const W = stage.clientWidth, H = stage.clientHeight;
  $('lift').classList.remove('show', 'armed');
  if (!G.moved) { tapAt(e.clientX, e.clientY); return; }
  if (G.mode === 'pan') { clampPan(); applyZoom(true); }
  else if (G.mode === 'h') {
    const fast = Math.abs(dx) / dt > .5;
    if (dx < -W * .22 || (fast && dx < -30)) go(1);
    else if (dx > W * .22 || (fast && dx > 30)) go(-1);
    else layoutSlides(0, true);
  } else if (G.mode === 'down') {
    if (dy > H * .18 || dy / dt > .6) {
      V.slides[1].style.transition = 'transform .22s ease-in, opacity .22s';
      V.slides[1].style.transform = `translate(${dx}px, ${H}px) scale(.6)`;
      viewer.style.setProperty('--bg', 0);
      setTimeout(closeViewer, 200);
    } else springBack();
  } else if (G.mode === 'up') {
    if (G.armed || -dy / dt > .9) {
      // the photo flies away to the top... and the NFC screen takes it
      V.slides[1].style.transition = 'transform .3s cubic-bezier(.4,0,.8,.4), opacity .3s';
      V.slides[1].style.transform = `translate(0, ${-H}px) scale(.35)`;
      V.slides[1].style.opacity = '0';
      const it = V.list[V.index];
      setTimeout(() => {
        V.slides[1].style.opacity = '';
        springBack(true);
        openSend(it, true);
      }, 260);
    } else springBack();
  }
  G.armed = false;
  G.mode = null;
}
stage.addEventListener('pointerup', endGesture);
stage.addEventListener('pointercancel', endGesture);

function springBack(quiet) {
  const s = V.slides[1];
  s.style.transition = quiet ? 'none' : 'transform .3s cubic-bezier(.2,.9,.3,1)';
  layoutSlides(0, !quiet);
  viewer.style.setProperty('--bg', 1);
  viewer.classList.remove('bare');
}

function tapAt(x, y) {
  const now = Date.now();
  if (now - G.lastTap < 280) {
    // double tap: zoom on that point, or back
    clearTimeout(G.tapTimer);
    G.lastTap = 0;
    if (V.s > 1.05) resetZoom(true);
    else {
      const r = stage.getBoundingClientRect();
      const s = 2.6;
      V.s = s;
      V.tx = -(x - r.left - r.width / 2) * (s - 1);
      V.ty = -(y - r.top - r.height / 2) * (s - 1);
      clampPan();
      applyZoom(true);
    }
    return;
  }
  G.lastTap = now;
  G.tapTimer = setTimeout(() => {
    const it = V.list[V.index];
    if (isVideo(it)) { play(V.list.filter(isVideo), V.list.filter(isVideo).indexOf(it)); return; }
    viewer.classList.toggle('bare');
  }, 280);
}

$('vw-back').onclick = closeViewer;
$('vw-nfc').onclick = () => openSend(V.list[V.index], true);
$('vw-share').onclick = () => { const it = V.list[V.index]; call('shareAndroid', { uri: it.uri, mime: it.mime }); };
$('vw-info').onclick = () => showInfo(V.list[V.index]);
$('vw-open').onclick = () => { const it = V.list[V.index]; call('openWith', { uri: it.uri, mime: it.mime }); };

// ------------------------------------------------------------------
// Sending by NFC

const SD = { item: null, phase: '', who: '' };

async function openSend(it, fly) {
  SD.item = it; SD.phase = ''; SD.who = '';
  const box = $('send');
  box.hidden = false;
  box.classList.toggle('fly', !!fly);
  box.classList.remove('live');
  $('sd-name').textContent = it.name || it.title || '';
  const card = $('sd-card');
  card.innerHTML = (isImage(it) || isVideo(it) || isAudio(it)) ? `${icon(kindIcon(it))}<img alt="" hidden><div class="glare"></div>` : `${icon(kindIcon(it))}<div class="glare"></div>`;
  const img = card.querySelector('img');
  if (img) { img.onload = () => { img.hidden = false; }; img.src = thumbUrl(it, 512); }
  const st = await call('status');
  applyStatus(st);
  if (st.nfc === 'none' || !st.hce) {
    sendView('Pas de NFC sur ce téléphone', "Ce téléphone ne peut pas envoyer par NFC. Tu peux quand même partager le fichier autrement.",
      [['Partager autrement…', () => { call('shareAndroid', { uri: it.uri, mime: it.mime }); }, true], ['Fermer', closeSend]]);
    return;
  }
  call('sendScreen', { open: true });
  if (st.nfc === 'off') {
    SD.phase = 'nfc-off';
    sendView('Active le NFC', "Le NFC de ce téléphone est désactivé. Active-le, puis reviens ici.",
      [['Activer le NFC', () => call('nfcSettings'), true], ['Annuler', closeSend]]);
    return;
  }
  startSending();
}

function startSending() {
  sendView('Préparation…', '', [['Annuler', closeSend]]);
  call('nfcSend', { uri: SD.item.uri, name: SD.item.name, mime: SD.item.mime, size: SD.item.size });
}

function closeSend() {
  if ($('send').hidden) return;
  call('nfcCancel');
  call('sendScreen', { open: false });
  $('send').hidden = true;
  SD.item = null;
}
$('sd-close').onclick = closeSend;

function sendView(title, sub, actions, live = false) {
  $('sd-title').textContent = title;
  $('sd-sub').textContent = sub || '';
  $('sd-sub').hidden = !sub;
  $('send').classList.toggle('live', live);
  $('sd-devices').hidden = !live;
  const box = $('sd-actions');
  box.innerHTML = '';
  for (const [label, fn, primary] of actions) {
    const b = document.createElement('button');
    b.className = 'btn wide' + (primary ? ' primary' : '');
    b.textContent = label;
    b.onclick = fn;
    box.appendChild(b);
  }
}

function cardOverlay(html) {
  const card = $('sd-card');
  card.querySelectorAll('.ring,.done').forEach(x => x.remove());
  if (html) card.insertAdjacentHTML('beforeend', html);
}

function ring(p) {
  const c = 2 * Math.PI * 52;
  return `<div class="ring"><svg viewBox="0 0 120 120"><circle cx="60" cy="60" r="52" fill="none" stroke="rgba(255,255,255,.3)" stroke-width="8"/>
    <circle cx="60" cy="60" r="52" fill="none" stroke="#ffc24d" stroke-width="8" stroke-linecap="round" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - p)}"/></svg>${Math.round(p * 100)} %</div>`;
}

handlers.send = s => {
  if (!SD.item || $('send').hidden) return;
  if (s.uri && SD.item.uri && s.uri !== SD.item.uri && s.phase !== 'idle') return;
  const prev = SD.phase;
  SD.phase = s.phase;
  if (s.who) SD.who = s.who;
  const who = SD.who || "l'autre téléphone";
  const iphone = /iPhone|iPad|Mac|navigateur/.test(SD.who) && !s.eclat;
  switch (s.phase) {
    case 'starting':
      cardOverlay(''); sendView('Préparation…', '', [['Annuler', closeSend]]); break;
    case 'hotspot':
      sendView('Création d\'un Wi-Fi pour l\'envoi…', "Pas de Wi-Fi ici : ton téléphone crée un petit réseau pour l'autre téléphone.", [['Annuler', closeSend]]); break;
    case 'ready':
      cardOverlay('');
      sendView('Approche un autre téléphone',
        s.hotspot ? "Dos contre dos, près du NFC. L'autre téléphone Android rejoindra le Wi-Fi de ce téléphone (il demandera la permission)."
          : 'Dos contre dos, en haut du téléphone, là où se trouve le NFC.',
        [['Annuler', closeSend]], true); break;
    case 'tapped':
      sendView('Téléphone détecté !', "Garde l'écran allumé, la demande s'affiche sur l'autre téléphone.", [['Annuler', closeSend]], true); break;
    case 'asked':
      sendView(iphone ? `${who} ouvre le fichier…` : `En attente de ${who}…`,
        iphone ? "Le fichier s'affiche dans Safari sur l'iPhone. Il peut le télécharger." : 'Il doit toucher « Accepter ».', [['Annuler', closeSend]], true); break;
    case 'sending': {
      const p = s.total > 0 ? Math.min(1, s.sent / s.total) : 0;
      cardOverlay(ring(p));
      sendView(`Envoi à ${who}…`, `${fmtSize(s.sent)} sur ${fmtSize(s.total)}`, [['Annuler', closeSend]]); break;
    }
    case 'sent':
      cardOverlay(`<div class="done">${icon('i-check')}</div>`);
      sendView(`Envoyé à ${who}`, '', [['Terminé', closeSend, true], ['Envoyer à un autre téléphone', () => { cardOverlay(''); sendView('Approche un autre téléphone', 'Le même fichier, à quelqu\'un d\'autre.', [['Annuler', closeSend]], true); }]]); break;
    case 'declined':
      cardOverlay('');
      sendView(`${who} a refusé`, 'Tu peux réessayer avec un autre téléphone.', [['Fermer', closeSend]], true); break;
    case 'expired':
      sendView('Envoi arrêté', "Personne n'a pris le fichier en 10 minutes.", [['Recommencer', startSending, true], ['Fermer', closeSend]]); break;
    case 'error':
      sendView("Ça n'a pas marché", s.error || '', [['Réessayer', startSending, true], ['Fermer', closeSend]]); break;
    case 'idle':
      if (prev && !['idle', 'nfc-off', 'sent', 'expired', 'error'].includes(prev)) {
        // stopped from the notification
        $('send').hidden = true; call('sendScreen', { open: false });
      }
      break;
  }
};

handlers.sendFromOutside = it => { closeViewer(); openSend(it, true); };
handlers.show = it => openViewer([it], 0);

// ------------------------------------------------------------------
// Settings, permissions, back

function applyStatus(st) {
  if (!st || st.error) return;
  const before = S.status;
  S.status = st;
  $('ph-partial').hidden = !st.partial;
  $('st-ver').textContent = 'Version ' + (st.version || '') + ' · ' + (st.device || '');
  $('st-nfc-state').textContent = st.nfc === 'none' ? 'Pas de NFC sur ce téléphone' : st.nfc === 'on' ? (st.hce ? 'Activé : prêt pour envoyer et recevoir' : 'Activé (réception seulement)') : 'Désactivé : touche pour l\'activer';
  if (before.media !== undefined && (before.media !== st.media || before.music !== st.music || before.allFiles !== st.allFiles)) {
    S.loaded = {};
    showTab(S.tab);
  }
}

$('btn-perm').onclick = async () => {
  const st = await call('askMedia', { again: true });
  applyStatus(st);
  if (!st.media) {
    toast('Autorise l\'accès dans les réglages d\'Éclat', true);
    $('btn-perm-settings').hidden = false;
  }
  S.loaded = {};
  showTab(S.tab);
};
$('btn-perm-settings').onclick = () => call('appSettings');
$('ph-more').onclick = async () => { applyStatus(await call('askMedia', { again: true })); S.loaded = {}; showTab(S.tab); };
$('btn-settings').onclick = () => openSheet('s-settings');
$('st-nfc').onclick = () => call('nfcSettings');
$('st-perm').onclick = () => call('appSettings');

handlers.resume = st => {
  applyStatus(st);
  // NFC switched on in the settings while the send screen waited for it
  if (SD.item && SD.phase === 'nfc-off' && st.nfc === 'on') startSending();
};

window.eclatBack = () => {
  for (const s of document.querySelectorAll('.sheet')) if (!s.hidden) { closeSheet(s.id); return true; }
  if (!$('send').hidden) { closeSend(); return true; }
  if (!viewer.hidden) { if (V.s > 1.05) resetZoom(true); else closeViewer(); return true; }
  if (S.tab === 'files' && S.files.parent) { openFolder(S.files.parent); return true; }
  if (S.tab !== 'photos') { showTab('photos'); return true; }
  return false;
};

(async function start() {
  const st = await call('status');
  applyStatus(st);
  loadPlaces();
  if (!st.media) {
    const after = await call('askMedia');
    applyStatus(after);
  }
  showTab('photos');
})();

// ------------------------------------------------------------------
// Demo mode (the page opened in a browser, for previews and tests)

function demo(method, a) {
  const D = demo.data || (demo.data = makeDemo());
  switch (method) {
    case 'status': return { media: true, music: true, allFiles: false, nfc: 'on', hce: true, wifi: true, device: 'Pixel 9', version: 'démo' };
    case 'askMedia': case 'askAllFiles': return demo('status');
    case 'albums': return [{ id: 'cam', name: 'Camera', count: 46 }, { id: 'ss', name: 'Screenshots', count: 12 }, { id: 'wa', name: 'WhatsApp Images', count: 30 }];
    case 'media': return a.kind === 'video' ? D.media.filter(isVideo) : D.media.slice(a.offset, a.offset + a.limit);
    case 'music': return D.music;
    case 'places': return ['Stockage', 'Téléchargements', 'Appareil photo', 'Documents', 'Musique', 'Films'].map((n, i) => ({ name: n, path: i ? '/storage/emulated/0/' + n : '/storage/emulated/0' }));
    case 'list': return { path: a.path || '/storage/emulated/0', home: '/storage/emulated/0', parent: a.path && a.path !== '/storage/emulated/0' ? '/storage/emulated/0' : '', readable: true, items: D.files };
    case 'details': return { size: 2345678, path: '/storage/emulated/0/DCIM/Camera/' + (a.uri || '').slice(-10), res: '4032 × 3024' };
    case 'nfcSend': {
      const steps = [['starting', 0], ['ready', 600], ['tapped', 3500], ['asked', 4300, { who: 'Galaxy de Léa', eclat: true }],
        ['sending', 6000, { sent: 1.2e6, total: 4.1e6 }], ['sending', 7000, { sent: 3.1e6, total: 4.1e6 }], ['sent', 8000]];
      for (const [phase, t, extra] of steps) setTimeout(() => handlers.send(Object.assign({ phase, uri: a.uri }, extra || {})), t);
      return true;
    }
    default: return true;
  }
}

function makeDemo() {
  const c = document.createElement('canvas');
  c.width = 240; c.height = 240;
  const g = c.getContext('2d');
  const pic = (h, v) => {
    const gr = g.createLinearGradient(0, 0, 240, 240);
    gr.addColorStop(0, `hsl(${h},70%,${55 + v}%)`); gr.addColorStop(1, `hsl(${(h + 60) % 360},70%,${35 + v}%)`);
    g.fillStyle = gr; g.fillRect(0, 0, 240, 240);
    g.fillStyle = 'rgba(255,255,255,.35)'; g.beginPath(); g.arc(70 + v * 3, 80, 36, 0, 7); g.fill();
    g.fillStyle = 'rgba(0,0,0,.18)'; g.beginPath(); g.moveTo(0, 240); g.lineTo(90, 130); g.lineTo(160, 190); g.lineTo(240, 110); g.lineTo(240, 240); g.fill();
    return c.toDataURL('image/jpeg', .8);
  };
  const now = Date.now();
  const media = [];
  for (let i = 0; i < 60; i++) {
    const video = i % 7 === 3;
    const d = pic((i * 37) % 360, i % 10);
    media.push({ uri: 'content://media/' + i, name: (video ? 'VID_' : 'IMG_') + (20260900 + i) + (video ? '.mp4' : '.jpg'), mime: video ? 'video/mp4' : 'image/jpeg',
      size: 1e6 + i * 91234, date: now - i * 86400e3 * 1.7, kind: video ? 'video' : 'image', duration: video ? 15000 + i * 3000 : 0, demo: d });
  }
  const music = ['Clair de lune', 'Nightcall', 'La Bohème', 'Midnight City', 'Alors on danse', 'Instant Crush'].map((t, i) => ({
    uri: 'content://audio/' + i, title: t, artist: ['Debussy', 'Kavinsky', 'Aznavour', 'M83', 'Stromae', 'Daft Punk'][i], album: '', duration: 180000 + i * 17000, mime: 'audio/mpeg', size: 4e6, kind: 'audio', demo: pic(i * 50, 5) }));
  const files = [{ name: 'Android', dir: true, count: 3, path: '/storage/emulated/0/Android' }, { name: 'DCIM', dir: true, count: 2, path: '/storage/emulated/0/DCIM' },
    { name: 'Download', dir: true, count: 14, path: '/storage/emulated/0/Download' },
    { name: 'Facture octobre.pdf', uri: 'file:///f.pdf', mime: 'application/pdf', kind: 'pdf', size: 230000, date: now },
    { name: 'Vacances.zip', uri: 'file:///v.zip', mime: 'application/zip', kind: 'archive', size: 52e6, date: now - 9e8 },
    { name: 'Coucher de soleil.jpg', uri: 'file:///c.jpg', mime: 'image/jpeg', kind: 'image', size: 3.2e6, date: now - 4e8, demo: media[0].demo }];
  return { media, music, files };
}
