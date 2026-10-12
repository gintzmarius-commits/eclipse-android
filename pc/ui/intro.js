/* Eclipse 3 — opening animation and living background (PC window, browser
   page, phone app). Loaded first; no optional chaining (old WebViews). */
(function () {
  "use strict";
  var html = document.documentElement;
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Modest phones: no second blur behind the glass (see intro.css).
  var ua = navigator.userAgent || "";
  if (/Android/.test(ua) && ((navigator.deviceMemory && navigator.deviceMemory <= 3) ||
      (navigator.hardwareConcurrency && navigator.hardwareConcurrency <= 4))) {
    html.classList.add("ec-lite");
  }

  // Each light of the background gets its own circle: size, speed, direction.
  function rnd(a, b) { return a + Math.random() * (b - a); }
  function setupBackdrop() {
    var bd = document.querySelector(".backdrop");
    if (!bd) return;
    while (bd.children.length < 4) bd.appendChild(document.createElement("i"));
    Array.prototype.forEach.call(bd.children, function (b) {
      var d = rnd(26, 46), d2 = rnd(9, 17);
      b.style.setProperty("--ec-r", rnd(3, 8).toFixed(1) + "vmax");
      b.style.setProperty("--ec-d", d.toFixed(1) + "s");
      b.style.setProperty("--ec-d2", d2.toFixed(1) + "s");
      b.style.setProperty("--ec-dir", Math.random() < 0.5 ? "normal" : "reverse");
      b.style.setProperty("--ec-delay", (-rnd(0, d)).toFixed(1) + "s");
      b.style.setProperty("--ec-delay2", (-rnd(0, d2)).toFixed(1) + "s");
      b.style.setProperty("--ec-wx", rnd(-4, 4).toFixed(1) + "vmax");
      b.style.setProperty("--ec-wy", rnd(-4, 4).toFixed(1) + "vmax");
      b.style.setProperty("--ec-s", rnd(1.05, 1.22).toFixed(2));
    });
  }

  // Nothing moves while the window or app is not on screen.
  function paused(p) { html.classList.toggle("ec-paused", !!p); }
  document.addEventListener("visibilitychange", function () { paused(document.hidden); });
  window.eclipsePaused = paused;

  // The opening: tap to skip; removed once over.
  function setupIntro() {
    var intro = document.getElementById("intro");
    if (!intro) return;
    var done = false;
    function end() {
      if (done) return;
      done = true;
      if (intro.parentNode) intro.parentNode.removeChild(intro);
      setTimeout(function () { document.body.classList.remove("ec-intro"); }, 250);
    }
    if (reduce) { end(); return; }
    intro.addEventListener("click", function () {
      intro.style.animation = "ec-intro-out .3s ease-in forwards";
      Array.prototype.forEach.call(document.body.children, function (c) { c.style.animationDelay = "0s"; });
      setTimeout(end, 650);
    });
    intro.addEventListener("animationend", function (e) { if (e.target === intro) end(); });
    setTimeout(end, 2600); // in any case
  }

  function start() { setupBackdrop(); setupIntro(); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
  else start();
})();
