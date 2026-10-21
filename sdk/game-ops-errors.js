// 게임 운영 플랫폼 · 게임 오류 보내기 (결정 37)
// 게임 HTML 에 넣는다:
//   <script src="game-ops-errors.js"></script>
//   <script>GameOps.init({ endpoint: "https://<운영 서버 주소>", game: "jelly-panic", version: "1.2.3", key: "" });</script>
// 하는 일
//   1. 잡히지 않은 오류(window.onerror · unhandledrejection)를 운영 서버로 보낸다. 같은 오류는 1분에 한 번, 전체는 1분에 10번까지.
//   2. GameOps.context("3웨이브") 로 지금 장면을 적어 두면 오류와 함께 간다.
//   3. GameOps.diagnostic() 은 플레이어가 디스코드 제보에 붙여 넣을 한 줄을 만든다(서버 주소가 없어도 됨):
//      [게임진단 v1.2.3 · Chrome 130 · iPhone · 오류: TypeError: x is undefined]
//      GameOps.copyDiagnostic() 은 그 줄을 클립보드에 복사한다(게임 메뉴의 「제보용 정보 복사」 버튼에 연결).
// 개인 정보는 보내지 않는다: 오류 글·호출 위치·버전·브라우저/기기 이름·장면만.
(function (w) {
  "use strict";
  var cfg = { endpoint: "", game: "", version: "", key: "" };
  var scene = "";
  var last = null;              // 마지막 오류 글
  var sentAt = {};              // 오류 글 → 마지막으로 보낸 때
  var windowStart = 0, sentInWindow = 0;

  function device() {
    var ua = (w.navigator && w.navigator.userAgent) || "";
    var b = /Edg\/(\d+)/.exec(ua) ? "Edge " + RegExp.$1
      : /Chrome\/(\d+)/.exec(ua) ? "Chrome " + RegExp.$1
      : /Firefox\/(\d+)/.exec(ua) ? "Firefox " + RegExp.$1
      : /Version\/(\d+).*Safari/.exec(ua) ? "Safari " + RegExp.$1 : "브라우저";
    var d = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android"
      : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : "";
    return d ? b + " · " + d : b;
  }

  function cut(s, n) { s = String(s == null ? "" : s); return s.length > n ? s.slice(0, n - 1) + "…" : s; }

  function send(message, stack) {
    last = cut(message, 200);
    if (!cfg.endpoint || !cfg.game) return;
    var now = Date.now();
    if (now - windowStart > 60000) { windowStart = now; sentInWindow = 0; }
    if (sentInWindow >= 10 || (sentAt[last] && now - sentAt[last] < 60000)) return;
    sentAt[last] = now; sentInWindow++;
    var body = JSON.stringify({ message: cut(message, 500), stack: cut(stack, 4000), version: cfg.version,
      device: device(), context: cut(scene, 300) });
    var url = cfg.endpoint.replace(/\/+$/, "") + "/api/games/" + encodeURIComponent(cfg.game) + "/errors"
      + (cfg.key ? "?key=" + encodeURIComponent(cfg.key) : "");
    try {
      // text/plain 으로 보내 브라우저가 사전 요청(OPTIONS)을 하지 않게 한다
      if (w.navigator && w.navigator.sendBeacon) w.navigator.sendBeacon(url, new Blob([body], { type: "text/plain" }));
      else w.fetch(url, { method: "POST", body: body, keepalive: true, headers: { "Content-Type": "text/plain" } });
    } catch (e) { /* 오류 보내기가 게임을 멈추게 하지 않는다 */ }
  }

  var GameOps = {
    init: function (o) {
      for (var k in o) if (Object.prototype.hasOwnProperty.call(cfg, k)) cfg[k] = o[k] == null ? "" : String(o[k]);
      w.addEventListener("error", function (e) {
        send(e.message || "오류", e.error && e.error.stack ? e.error.stack : (e.filename || "") + ":" + (e.lineno || 0));
      });
      w.addEventListener("unhandledrejection", function (e) {
        var r = e.reason || {};
        send(r.message || String(r), r.stack || "");
      });
      return GameOps;
    },
    context: function (s) { scene = String(s || ""); },
    report: function (err) { send(err && err.message ? err.message : String(err), err && err.stack ? err.stack : ""); },
    diagnostic: function () {
      var parts = ["게임진단"];
      var head = (cfg.version ? "v" + cfg.version : "") ;
      var s = "[" + parts[0] + (head ? " " + head : "") + " · " + device();
      if (last) s += " · 오류: " + cut(last, 120).replace(/[\[\]\n]/g, " ");
      return s + "]";
    },
    copyDiagnostic: function () {
      var t = GameOps.diagnostic();
      if (w.navigator && w.navigator.clipboard) return w.navigator.clipboard.writeText(t).then(function () { return t; });
      return Promise.resolve(t);
    }
  };
  w.GameOps = GameOps;
})(window);
