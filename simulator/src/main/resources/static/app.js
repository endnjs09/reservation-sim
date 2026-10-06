"use strict";
// 실시간 화면 (SPEC 13·14.1). 실제 tick(/api/stream)과 저장 기록만 표시한다. 가짜 시뮬레이션·데이터 없음.
const $ = (id) => document.getElementById(id);
const fmt = new Intl.NumberFormat("ko-KR");
const num = (v) => (v === null || v === undefined || !Number.isFinite(Number(v)) ? "–" : fmt.format(Math.round(Number(v))));
const dec = (v, d = 1) => (v === null || v === undefined || !Number.isFinite(Number(v)) ? "–" : Number(v).toFixed(d));
const clone = (v) => structuredClone(v);
const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");

const PHASES = ["OPEN", "RUSH", "RESALE", "SOLD_OUT", "REOPEN", "ENDED"];
const PHASE_NAMES = { OPEN: "오픈", RUSH: "선점 폭주", RESALE: "취켓팅", SOLD_OUT: "매진", REOPEN: "취소표 오픈", ENDED: "판매 종료" };
const STATUS_TEXT = { RUNNING: "실행 중", COMPLETED: "완료", STOPPED: "중지됨", FAILED: "실패", STOPPING: "중지 중" };
const STRATEGIES = { conditional: "조건부 UPDATE", pessimistic: "비관적 잠금", optimistic: "낙관적 잠금", naive: "단순 조회 후 갱신" };
const LEVEL_COLOR = { ok: "#3DDC97", warn: "#F5A524", bad: "#FF5D5D" };
const GRADE_COLORS = {
  VIP: { bd: "#D9B23A", bg: "rgba(201,162,39,0.3)", fg: "#F3DA8A" },
  S: { bd: "#5B8BEB", bg: "rgba(76,123,217,0.3)", fg: "#B9CCFA" },
  A: { bd: "#45B583", bg: "rgba(59,163,116,0.3)", fg: "#A9E4C8" },
  B: { bd: "#6E7A91", bg: "rgba(130,142,165,0.3)", fg: "#D5DBE6" },
};

// ───────── 상태 ─────────
let defaults = null, draft = null, busy = false;
let latest = null;              // 마지막으로 받은 tick
let lastRendered = null;        // 마지막으로 그린 tick (창 크기가 바뀌면 다시 그린다)
let frozen = false;             // 화면 고정: 표시만 멈춤
let streamOk = false;
let selectedResultRun = null;
let runKey = null;              // 히스토리를 나누는 실행 ID
const history = { rps: [], p95: [], err: [], pool: [], srvP95: [] };
let lastServerAt = null;
let notifyRate = 0, lastNotify = null;
let prevSeatMap = "";
const releasedUntil = new Map();
const eventLog = [];            // 최신이 앞
const seenEvents = new Set();
let detailType = null, detailOpener = null, logFilter = "all", logPage = 0;
let countdownTarget = null;     // { ms: 남은 실제 ms, at: 받은 시각 performance.now(), scale }

// ───────── 공통 ─────────
async function api(path, method = "GET", body) {
  const res = await fetch(`/api${path}`, { method, headers: body === undefined ? {} : { "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const value = text ? JSON.parse(text) : {};
  if (!res.ok) throw new Error(value.message || `HTTP ${res.status}`);
  return value;
}
function notice(message = "", error = false) { $("notice").textContent = message; $("notice").classList.toggle("error", error); }
const getPath = (o, p) => p.split(".").reduce((v, k) => v?.[k], o);
function setPath(o, p, v) { const ks = p.split("."); let t = o; for (const k of ks.slice(0, -1)) t = t[k]; t[ks.at(-1)] = v; }
const mmss = (sec) => { if (!Number.isFinite(sec) || sec < 0) sec = 0; const s = Math.floor(sec); return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`; };
function durationLabel(seconds) {
  if (!Number.isFinite(seconds)) return "—";
  if (seconds < 60) return `${Number(seconds.toFixed(2))}초`;
  const m = Math.floor(seconds / 60), r = Math.round(seconds % 60);
  return `${m}분${r ? ` ${r}초` : ""}`;
}
const levelOf = (lv) => (lv === "bad" || lv === "warn" ? lv : "ok");
const worse = (a, b) => ({ ok: 0, warn: 1, bad: 2 }[levelOf(a)] >= { ok: 0, warn: 1, bad: 2 }[levelOf(b)] ? levelOf(a) : levelOf(b));
function setLevel(node, lv) { node.classList.remove("lv-ok", "lv-warn", "lv-bad"); node.classList.add(`lv-${levelOf(lv)}`); }
function text(id, value) { const n = $(id); if (n && n.textContent !== String(value)) n.textContent = value; }

// ───────── 화면 맞춤: 창에 맞춰 늘어나는 배치, 여백·스크롤 없음 (docs/DECISION_CLAUDE.md) ─────────
// 고정: 헤더·지표 카드·하단 띠·설정 패널 폭. 구조도는 700×670 좌표를 남는 높이에 맞춰 비율 유지(폭 = 700 × 높이 ÷ 670),
// DB가 남은 폭 전부. 구조도 비율이 0.8 아래로 내려가야 하는 창에서만 화면 전체를 축소한다.
const STRUCT_W = 700, STRUCT_H = 670, STRUCT_MIN = 0.8, DB_MIN_W = 760, PANEL_W = 360, MAIN_PAD_X = 48;
let fixedH = 0;          // .main에서 구조도·DB 줄을 뺀 높이 (실시간 화면이 보일 때 잰다)
let structScale = 1, stageScale = 1;
function layout() {
  const stage = $("stage"), w = window.innerWidth, h = window.innerHeight;
  Object.assign(stage.style, { width: `${w}px`, height: `${h}px`, transform: "" });
  const main = document.querySelector("#live-view .main"), viz = $("viz");
  if (main.clientHeight) fixedH = main.clientHeight - viz.clientHeight;
  // 기준 최소 창: 구조도 비율이 0.8이 되는 크기
  const minW = PANEL_W + MAIN_PAD_X + STRUCT_W * STRUCT_MIN + DB_MIN_W, minH = fixedH + STRUCT_H * STRUCT_MIN;
  stageScale = Math.min(1, w / minW, h / minH);
  if (stageScale < 1) Object.assign(stage.style, { width: `${w / stageScale}px`, height: `${h / stageScale}px`, transform: `scale(${stageScale})` });
  layoutStructure();
}
function layoutStructure() {
  const viz = $("viz"), H = viz.clientHeight, W = viz.clientWidth;
  if (!H) return; // 숨겨진 상태: 실시간 화면으로 돌아올 때 다시 계산
  structScale = Math.max(STRUCT_MIN, Math.min(H / STRUCT_H, (W - DB_MIN_W) / STRUCT_W));
  $("structure").style.width = `${STRUCT_W * structScale}px`;
  Object.assign($("structure-inner").style, { top: `${Math.max(0, (H - STRUCT_H * structScale) / 2)}px`, transform: `scale(${structScale})` });
  // 요청 점 canvas: 화면에 보이는 크기만큼 해상도를 맞춰 흐려지지 않게
  const px = structScale * stageScale * (window.devicePixelRatio || 1);
  canvas.width = Math.round(STRUCT_W * px); canvas.height = Math.round(STRUCT_H * px);
  canvas.style.width = `${STRUCT_W}px`; canvas.style.height = `${STRUCT_H}px`;
  ctx.setTransform(px, 0, 0, px, 0, 0);
}
let relayoutPending = false;
addEventListener("resize", () => {
  if (relayoutPending) return; relayoutPending = true;
  requestAnimationFrame(() => {
    relayoutPending = false; layout();
    seatLayoutKey = ""; if (lastRendered) render(lastRendered);
    window.resultsView?.relayout();
  });
});

// ───────── 설정 패널 ─────────
let cfgTab = "basic", personaKey = "normal";
const openGroup = { basic: 0, scenario: 0, advanced: 0 };
function timeHint(path) {
  const seconds = Number(getPath(draft, path)), scale = draft.timeScale || 1;
  return `${durationLabel(seconds)} · 실제 ${durationLabel(seconds / scale)}`;
}
/** 한 줄: 이름(+시간 힌트) | 입력들 */
function row(parent, label, inputs, opts = {}) {
  const line = el("div", "cfg-row");
  const lab = el("span", "cfg-label");
  const first = inputs[0];
  const id = `cfg-${first.path.replaceAll(".", "-")}`;
  const name = el("label", "", label); name.htmlFor = id; lab.append(name);
  if (opts.time) { const h = el("span", "cfg-hint"); h.dataset.timePath = first.path; lab.append(h); }
  const box = el("span", `cfg-inputs${inputs.length > 1 ? ` n${inputs.length}` : ""}`);
  inputs.forEach((spec, i) => {
    if (i > 0 && spec.sep) box.append(el("span", "muted", spec.sep));
    let input;
    if (spec.choices) { input = el("select"); for (const [v, t] of spec.choices) { const o = el("option", "", t); o.value = v; input.append(o); } input.value = getPath(draft, spec.path) ?? ""; }
    else {
      input = el("input"); input.type = spec.type || "number";
      if (input.type === "checkbox") input.checked = !!getPath(draft, spec.path);
      else {
        if (input.type === "number") { input.min = spec.min ?? 0; input.step = spec.step ?? 1; if (spec.max !== undefined) input.max = spec.max; input.required = true; }
        if (spec.wide) input.classList.add("wide");
        input.value = getPath(draft, spec.path) ?? "";
      }
    }
    input.id = i === 0 ? id : `${id}-${i}`;
    if (i > 0) input.setAttribute("aria-label", `${label} ${spec.aria || i + 1}`);
    input.dataset.path = spec.path;
    input.addEventListener("input", () => {
      const v = input.type === "checkbox" ? input.checked : input.type === "number" ? input.valueAsNumber : spec.numeric ? Number(input.value) : input.value;
      setPath(draft, spec.path, v);
      if (spec.onChange) spec.onChange(v);
      updateTimeHints(); updateIdlePreview();
    });
    box.append(input);
  });
  line.append(lab, box);
  parent.append(line);
}
function note(parent, textValue) { parent.append(el("p", "cfg-note", textValue)); }
function actions(parent, items) {
  const box = el("div", "cfg-actions");
  for (const [label, disabled, work] of items) {
    const b = el("button", "", label); b.type = "button"; b.disabled = disabled; b.dataset.fixedDisabled = String(disabled);
    b.onclick = () => { work(); renderFields(); };
    box.append(b);
  }
  parent.append(box);
}
const TIME = { time: true };
const groups = {
  basic: [
    ["좌석 · 사용자", (g) => {
      row(g, "좌석 행", [{ path: "rows", min: 1, max: 26, onChange: adjustGradeRows }]);
      row(g, "좌석 열", [{ path: "cols", min: 1 }]);
      row(g, "가상 사용자 수", [{ path: "users", min: 1 }]);
    }],
    ["등급 · 가격 (행 수 합 = 좌석 행)", (g) => {
      draft.grades.forEach((grade, i) => row(g, `${grade.name} 행 · 가격(원)`, [{ path: `grades.${i}.rows` }, { path: `grades.${i}.price`, aria: "가격" }]));
    }],
    ["도착 분포 (비율 % · 시작~종료 초)", (g) => {
      draft.arrival.forEach((_, i) => row(g, `${i + 1}구간`, [{ path: `arrival.${i}.percent`, max: 100, aria: "비율" }, { path: `arrival.${i}.fromSec`, step: "any", aria: "시작" }, { path: `arrival.${i}.toSec`, step: "any", sep: "~", aria: "종료" }]));
      actions(g, [["구간 추가", draft.arrival.length >= 5, () => draft.arrival.push({ percent: 0, fromSec: 0, toSec: 0 })],
        ["마지막 삭제", draft.arrival.length <= 1, () => draft.arrival.pop()]]);
    }],
    ["판매 · 실행 시간", (g) => {
      row(g, "판매 시간 (초)", [{ path: "saleDurationSec", min: 1 }], TIME);
      row(g, "실행 제한 (초)", [{ path: "timeLimitSec", min: 1 }], TIME);
      note(g, "실행 제한은 판매 종료 뒤 결제·입금 마무리까지 포함합니다.");
    }],
  ],
  scenario: [
    ["대기열", (g) => {
      row(g, "안에 최대 인원", [{ path: "maxActive", min: 1 }]);
      row(g, "초당 입장 상한", [{ path: "admitPerSec", min: 1 }]);
      row(g, "입장 유효시간 (초)", [{ path: "admissionTtlSec", min: 1 }], TIME);
      row(g, "busy 상한 추가 (초)", [{ path: "busyMaxExtraSec", min: 1 }], TIME);
      row(g, "매진 시 대기열 닫기", [{ path: "closeQueueOnSoldOut", type: "checkbox" }]);
    }],
    ["선점 · 결제 · 취소표", (g) => {
      row(g, "선점 TTL (초)", [{ path: "holdTtlSec", min: 1 }], TIME);
      row(g, "카드 / 입금 대기 (%)", [{ path: "paymentMix.card", max: 100 }, { path: "paymentMix.deposit", max: 100, sep: "/", aria: "입금 대기" }]);
      row(g, "입금 기한 (초)", [{ path: "depositDeadlineSec", min: 1 }], TIME);
      row(g, "미입금 비율 (0~1)", [{ path: "depositNoPayRate", max: 1, step: "any" }]);
      row(g, "반환 대기 → 오픈 (초)", [{ path: "returnDelaySec", min: 1 }], TIME);
      row(g, "취소표 오픈 표시 (초)", [{ path: "reopenWindowSec", min: 1 }], TIME);
      row(g, "예매 후 취소율 (0~1)", [{ path: "cancelAfterPurchaseRate", max: 1, step: "any" }]);
    }],
    ["이탈 성향", (g) => {
      row(g, "비율 C / P / H (%)", [{ path: "churnMix.casual", max: 100 }, { path: "churnMix.persistent", max: 100, aria: "Persistent" }, { path: "churnMix.hardcore", max: 100, aria: "Hardcore" }]);
      row(g, "재방문 C / P / H", [{ path: "revisitProb.casual", max: 1, step: "any" }, { path: "revisitProb.persistent", max: 1, step: "any", aria: "Persistent" }, { path: "revisitProb.hardcore", max: 1, step: "any", aria: "Hardcore" }]);
      row(g, "Casual 이탈 (초)", [{ path: "casualLeaveSec.min", step: "any" }, { path: "casualLeaveSec.max", step: "any", sep: "~", aria: "최대" }]);
      row(g, "Persistent 반감기 (초)", [{ path: "persistentHalfLifeSec", min: 0.001, step: "any" }], TIME);
      // 대기 이탈: 대기 중 순번 조회마다 판단 (hardcore는 떠나지 않음). 위 세 줄은 입장 후 잔여석 없음일 때
      row(g, "대기 이탈", [{ path: "queueAbandonEnabled", type: "checkbox" }]);
      row(g, "대기 반감기 C / P (초)", [{ path: "queueHalfLifeSec.casual", min: 0.001, step: "any" }, { path: "queueHalfLifeSec.persistent", min: 0.001, step: "any", aria: "Persistent" }]);
      row(g, "줄 멈춤 창 (초) / 최소 진행", [{ path: "queueStallWindowSec", min: 0.001, step: "any" }, { path: "queueStallMinProgress", max: 1, step: "any", sep: "/", aria: "최소 진행 비율" }]);
    }],
  ],
  advanced: [
    ["관람객 속도", (g) => {
      row(g, "비율 빠름/일반/느림 (%)", [{ path: "personaMix.fast", max: 100 }, { path: "personaMix.normal", max: 100, aria: "일반" }, { path: "personaMix.slow", max: 100, aria: "느림" }]);
      const pick = el("div", "persona-pick");
      for (const [k, t] of [["fast", "빠름"], ["normal", "일반"], ["slow", "느림"]]) {
        const b = el("button", "", t); b.type = "button"; b.setAttribute("aria-pressed", String(k === personaKey));
        b.onclick = () => { personaKey = k; renderFields(); };
        pick.append(b);
      }
      g.append(pick);
      const p = personaKey;
      row(g, "좌석 고르기 (초)", [{ path: `personas.${p}.selectSec.min`, step: "any" }, { path: `personas.${p}.selectSec.max`, step: "any", sep: "~", aria: "최대" }]);
      row(g, "가격·수령 단계 (초)", [{ path: `priceStepSec.${p}.min`, step: "any" }, { path: `priceStepSec.${p}.max`, step: "any", sep: "~", aria: "최대" }]);
      row(g, "카드 인증 (초)", [{ path: `personas.${p}.authSec.min`, step: "any" }, { path: `personas.${p}.authSec.max`, step: "any", sep: "~", aria: "최대" }]);
      row(g, "새로고침 간격 (초)", [{ path: `personas.${p}.refreshSec`, min: 0.001, step: "any" }]);
    }],
    ["매수 · 연석", (g) => {
      row(g, "1인 최대 매수", [{ path: "maxSeatsPerUser", min: 1 }]);
      row(g, "1/2/3/4매 (%)", [1, 2, 3, 4].map((n) => ({ path: `ticketCountMix.${n}`, max: 100, aria: `${n}매` })));
      row(g, "연석 고집 (0~1)", [{ path: "adjacentRequiredRate", max: 1, step: "any" }]);
    }],
    ["결제 · 장애", (g) => {
      row(g, "결제 포기 (0~1)", [{ path: "abandonRate", max: 1, step: "any" }]);
      row(g, "카드 인증 실패 (0~1)", [{ path: "authFailureRate", max: 1, step: "any" }]);
      row(g, "승인 거절 (0~1)", [{ path: "declineRate", max: 1, step: "any" }]);
      row(g, "승인 타임아웃 (0~1)", [{ path: "timeoutRate", max: 1, step: "any" }]);
      row(g, "승인 지연 (실제 ms)", [{ path: "confirmMinMs" }, { path: "confirmMaxMs", sep: "~", aria: "최대" }]);
      row(g, "승인 복구 기준 (초)", [{ path: "confirmDeadlineSec", min: 1 }], TIME);
    }],
    ["서버 전략", (g) => {
      row(g, "동시성 전략", [{ path: "strategy", choices: Object.entries(STRATEGIES) }]);
      row(g, "DB 방어선", [{ path: "dbBackstop", type: "checkbox" }]);
      // 좌석 조회 캐시: 응답 전체를 모든 사용자가 공유 (선점·결제는 늘 DB 조건부)
      row(g, "좌석 조회 캐시", [{ path: "seatsCacheSec", numeric: true, choices: [[0, "끔"], [1, "1초"]] }]);
      row(g, "좌석 새로고침 제한", [{ path: "seatsRateLimitEnabled", type: "checkbox" }]);
      row(g, "새로고침 최소 간격 (초)", [{ path: "seatsMinIntervalSec", min: 0.001, step: "any" }], TIME);
      row(g, "랜덤 시드", [{ path: "seed", min: "-9223372036854775808" }]);
    }],
    ["계측 임계치", (g) => {
      row(g, "p95 경고 / SLO (ms)", [{ path: "thresholds.p95WarnMs", step: "any" }, { path: "thresholds.p95SloMs", step: "any", sep: "/", aria: "SLO" }]);
      row(g, "에러율 경고 / 나쁨 (%)", [{ path: "thresholds.errWarnPct", max: 100, step: "any" }, { path: "thresholds.errBadPct", max: 100, step: "any", sep: "/", aria: "나쁨" }]);
      row(g, "풀 경고 / 포화 (%)", [{ path: "thresholds.poolWarnPct", max: 100, step: "any" }, { path: "thresholds.poolBadPct", max: 100, step: "any", sep: "/", aria: "포화" }]);
    }],
    ["접속", (g) => {
      row(g, "예약 서버", [{ path: "targets.server", type: "url", wide: true }]);
      row(g, "대기열 서버", [{ path: "targets.queue", type: "url", wide: true }]);
      row(g, "모의 결제사", [{ path: "targets.pg", type: "url", wide: true }]);
      row(g, "요청 timeout (실제 ms)", [{ path: "requestTimeoutMs", min: 1 }]);
    }],
  ],
};
function adjustGradeRows(rows) {
  if (!Number.isInteger(rows) || rows < 1 || rows > 26) return;
  let remaining = rows;
  draft.grades.forEach((grade, i) => { grade.rows = i === draft.grades.length - 1 ? remaining : Math.min(grade.rows, remaining); remaining -= grade.rows; });
}
function renderFields() {
  const form = $("cfg-form");
  form.replaceChildren();
  for (const [tab, list] of Object.entries(groups)) {
    const panel = el("div", "cfg-panel"); panel.id = `cfg-${tab}`; panel.setAttribute("role", "tabpanel"); panel.hidden = tab !== cfgTab;
    list.forEach(([title, build], i) => {
      const d = el("details", "cfg-group"); d.name = `group-${tab}`; d.open = openGroup[tab] === i;
      d.addEventListener("toggle", () => { if (d.open) openGroup[tab] = i; });
      d.append(el("summary", "", title));
      const body = el("div", "cfg-body"); build(body); d.append(body);
      panel.append(d);
    });
    form.append(panel);
  }
  for (const b of document.querySelectorAll(".cfg-tabs [data-tab]")) { b.setAttribute("aria-selected", String(b.dataset.tab === cfgTab)); b.tabIndex = b.dataset.tab === cfgTab ? 0 : -1; }
  $("run-label").value = draft.label || "";
  updateTimeHints(); lockControls();
}
function updateTimeHints() {
  if (!draft) return;
  document.querySelectorAll("[data-time-path]").forEach((n) => { n.textContent = timeHint(n.dataset.timePath); });
  const scale = draft.timeScale || 1;
  for (const b of document.querySelectorAll("[data-speed]")) b.setAttribute("aria-pressed", String(Number(b.dataset.speed) === scale));
  $("speed-note").textContent = scale === 1 ? "1× 실제 시간 · 부하 측정·전략 비교에 사용" : "부하 측정·전략 비교는 1×로 하세요";
  $("speed-note").classList.toggle("warn", scale !== 1);
}
for (const b of document.querySelectorAll(".cfg-tabs [data-tab]")) {
  b.addEventListener("click", () => { cfgTab = b.dataset.tab; renderFields(); });
  b.addEventListener("keydown", (e) => {
    const tabs = ["basic", "scenario", "advanced"], i = tabs.indexOf(b.dataset.tab);
    const next = e.key === "ArrowRight" ? (i + 1) % 3 : e.key === "ArrowLeft" ? (i + 2) % 3 : null;
    if (next !== null) { e.preventDefault(); cfgTab = tabs[next]; renderFields(); document.querySelector(`.cfg-tabs [data-tab="${cfgTab}"]`).focus(); }
  });
}
for (const b of document.querySelectorAll("[data-speed]")) b.addEventListener("click", () => { if (isActive() || busy) return; draft.timeScale = Number(b.dataset.speed); updateTimeHints(); updateIdlePreview(); });
$("run-label").addEventListener("input", () => { draft.label = $("run-label").value; });

const isActive = () => !!latest?.running;
function lockControls() {
  const locked = isActive() || busy;
  for (const n of $("cfg-form").querySelectorAll("input, select, .cfg-actions button")) n.disabled = locked || n.dataset.fixedDisabled === "true";
  for (const id of ["run-label", "btn-reset", "preset-select", "preset-load", "preset-name", "preset-save"]) $(id).disabled = locked;
  for (const b of document.querySelectorAll("[data-speed]")) b.disabled = locked;
  $("btn-start").disabled = locked;
  $("btn-stop").disabled = !isActive() || busy || latest?.status === "STOPPING";
  $("btn-freeze").disabled = !latest?.id;
}
async function action(work) {
  if (busy) return;
  busy = true; lockControls(); notice();
  try { await work(); } catch (e) { notice(e.message, true); } finally { busy = false; lockControls(); }
}
$("btn-start").onclick = () => {
  const form = $("cfg-form");
  const invalid = form.querySelector("input:invalid");
  if (invalid) {
    const panel = invalid.closest(".cfg-panel"); cfgTab = panel.id.replace("cfg-", "");
    const group = invalid.closest("details"); const index = [...panel.children].indexOf(group); openGroup[cfgTab] = index;
    renderFields(); notice("입력값을 확인하세요.", true); return;
  }
  action(async () => {
    draft.label = $("run-label").value.trim();
    const result = await api("/runs", "POST", draft);
    notice(`시작: ${result.id}. 대상 서버를 초기화합니다.`);
    resetRunVisuals(result.id);
    latest = { ...(latest || {}), id: result.id, running: result.running, status: result.status, config: result.config };
  });
};
$("btn-stop").onclick = () => action(async () => { await api("/runs/current/stop", "POST", {}); notice("실행 중지 요청. 남은 사용자는 incomplete로 저장합니다."); });
$("btn-reset").onclick = () => { if (isActive() || busy) return; draft = clone(defaults); renderFields(); updateIdlePreview(); notice("설정을 기본값으로 되돌렸습니다."); };
$("btn-freeze").onclick = () => {
  frozen = !frozen;
  $("btn-freeze").setAttribute("aria-pressed", String(frozen));
  $("btn-freeze").textContent = frozen ? "화면 고정 해제" : "화면 고정";
  $("frozen-banner").hidden = !frozen;
  if (!frozen && latest) render(latest);
};
async function loadPresets() {
  const names = await api("/presets"), select = $("preset-select"), keep = select.value;
  select.replaceChildren(el("option", "", "프리셋 선택")); select.firstChild.value = "";
  for (const n of names) { const o = el("option", "", n); o.value = n; select.append(o); }
  select.value = names.includes(keep) ? keep : "";
}
$("preset-load").onclick = () => action(async () => {
  const name = $("preset-select").value; if (!name) throw Error("불러올 프리셋을 선택하세요.");
  draft = await api(`/presets/${encodeURIComponent(name)}`); renderFields(); updateIdlePreview(); notice(`${name} 프리셋을 불러왔습니다.`);
});
$("preset-save").onclick = () => action(async () => {
  const name = $("preset-name").value.trim(); if (!name) throw Error("저장할 이름을 입력하세요.");
  await api(`/presets/${encodeURIComponent(name)}`, "PUT", draft); await loadPresets(); $("preset-select").value = name; notice("프리셋을 저장했습니다.");
});

// ───────── 화면 전환 ─────────
function showView(name) {
  const live = name === "live";
  $("live-view").hidden = !live; $("results-view").hidden = live;
  for (const id of ["tab-live", "tab-live-2"]) $(id).setAttribute("aria-selected", String(live));
  for (const id of ["tab-results", "tab-results-2"]) $(id).setAttribute("aria-selected", String(!live));
  if (live) { layout(); seatLayoutKey = ""; if (lastRendered) render(lastRendered); } // 숨겨진 동안 바뀐 창 크기 반영
  if (!live && window.resultsView) window.resultsView.show(selectedResultRun);
}
for (const id of ["tab-live", "tab-live-2"]) $(id).onclick = () => showView("live");
for (const id of ["tab-results", "tab-results-2"]) $(id).onclick = () => showView("results");
$("show-result").onclick = () => { selectedResultRun = latest?.id || null; showView("results"); };

// ───────── 단계 스텝퍼 ─────────
const stepNodes = PHASES.map((p, i) => {
  const li = el("li"); li.append(el("span", "n", String(i + 1)), el("span", "", PHASE_NAMES[p]));
  if (i < PHASES.length - 1) li.append(el("span", "tie"));
  $("stepper").append(li); return li;
});

// ───────── 카드 막대 ─────────
function makeBars(id, n) { const box = $(id); return Array.from({ length: n }, () => { const b = el("i"); box.append(b); return b; }); }
const cardBars = { rps: makeBars("bars-rps", 60), p95: makeBars("bars-p95", 60), err: makeBars("bars-err", 60), pool: makeBars("bars-pool", 60) };
const srvBars = makeBars("srv-bars", 40);
function paintBars(bars, values, max, height) {
  const offset = bars.length - values.length;
  bars.forEach((b, i) => {
    const v = values[i - offset];
    if (v === undefined || v === null) { b.style.height = "1px"; b.style.opacity = "0.15"; return; }
    b.style.height = `${Math.max(1, Math.round(height * Math.min(1, v / Math.max(1e-9, max))))}px`;
    b.style.opacity = String(0.3 + 0.7 * (i / (bars.length - 1)));
  });
}
const poolSlots = [];
function ensurePoolSlots(max) {
  const n = Math.min(60, Math.max(1, max || 20));
  if (poolSlots.length === n) return;
  $("pool-slots").replaceChildren(); poolSlots.length = 0;
  for (let i = 0; i < n; i++) { const s = el("i"); $("pool-slots").append(s); poolSlots.push(s); }
}

// ───────── 구조도: 연결선 (목업 좌표) ─────────
// 0 사용자↔대기열, 1 예약→DB, 2 예약→결제사, 3 사용자→결제사, 4 사용자→예약, 5 예약→대기열
const EDGES = [
  [[150, 490], [150, 400]],
  [[650, 200], [700, 200]],
  [[525, 320], [525, 500]],
  [[300, 600], [400, 600]],
  [[300, 560], [350, 560], [350, 260], [400, 260]],
  [[400, 140], [300, 140]],
];
const EDGE_LABEL_POS = [[162, 446], [652, 166], [535, 400], [304, 612], [362, 380], [306, 112]];
const edgeLen = EDGES.map((pts) => pts.slice(1).reduce((L, p, i) => L + Math.abs(p[0] - pts[i][0]) + Math.abs(p[1] - pts[i][1]), 0));
const edgeNodes = EDGES.map((pts) => pts.slice(1).map(() => { const d = el("div", "edge"); $("edges").append(d); return d; }));
const edgeLabels = EDGE_LABEL_POS.map(([x, y]) => { const d = el("div", "edge-label"); d.style.left = `${x}px`; d.style.top = `${y}px`; $("edge-labels").append(d); return d; });
const thickness = (rps) => Math.round(3 + Math.min(11, Math.log2(1 + Math.max(0, rps || 0)) * 1.4));
function paintEdge(i, rps, color) {
  const pts = EDGES[i], th = thickness(rps), hh = th / 2;
  edgeNodes[i].forEach((d, s) => {
    const a = pts[s], b = pts[s + 1];
    if (a[1] === b[1]) Object.assign(d.style, { left: `${Math.min(a[0], b[0]) - (s > 0 ? hh : 0)}px`, top: `${a[1] - hh}px`, width: `${Math.abs(b[0] - a[0]) + (s > 0 ? hh : 0)}px`, height: `${th}px` });
    else Object.assign(d.style, { left: `${a[0] - hh}px`, top: `${Math.min(a[1], b[1]) - hh}px`, width: `${th}px`, height: `${Math.abs(b[1] - a[1]) + th}px` });
    d.style.background = color;
  });
}
function pointAt(i, f) {
  const pts = EDGES[i]; let d = Math.max(0, Math.min(1, f)) * edgeLen[i];
  for (let s = 1; s < pts.length; s++) {
    const a = pts[s - 1], b = pts[s], l = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]);
    if (d <= l || s === pts.length - 1) { const r = l ? d / l : 0; return [a[0] + (b[0] - a[0]) * r, a[1] + (b[1] - a[1]) * r]; }
    d -= l;
  }
  return pts[0];
}

// ───────── 요청 점 (canvas + rAF) ─────────
const canvas = $("flow-canvas"), ctx = canvas.getContext("2d");
let streams = [];        // { route: [[edge, dir]...], color, size, rate }
const dots = [];
const SPEED = 380;       // px/s (구조도 좌표)
const PER_EDGE_MAX = 40;
let lastFrame = performance.now();
function frame(now) {
  const dt = Math.min(0.1, (now - lastFrame) / 1000); lastFrame = now;
  ctx.clearRect(0, 0, STRUCT_W, STRUCT_H);
  const animate = !reducedMotion.matches && !frozen && isActive();
  if (!animate) { dots.length = 0; requestAnimationFrame(frame); return; }
  const alive = new Map();
  for (const d of dots) alive.set(d.route[d.leg][0], (alive.get(d.route[d.leg][0]) || 0) + 1);
  for (const s of streams) {
    s.acc = (s.acc || 0) + s.rate * dt;
    while (s.acc >= 1) {
      s.acc -= 1;
      const e = s.route[0][0];
      if ((alive.get(e) || 0) >= PER_EDGE_MAX) { s.acc = 0; break; }
      alive.set(e, (alive.get(e) || 0) + 1);
      dots.push({ route: s.route, leg: 0, d: -Math.random() * 30, color: s.color, size: s.size });
    }
  }
  for (let i = dots.length - 1; i >= 0; i--) {
    const p = dots[i]; p.d += SPEED * dt;
    const [e, dir] = p.route[p.leg];
    if (p.d > edgeLen[e]) { if (p.leg + 1 < p.route.length) { p.leg++; p.d = 0; } else { dots.splice(i, 1); continue; } }
    if (p.d < 0) continue;
    const [edge, direction] = p.route[p.leg];
    const f = p.d / edgeLen[edge];
    const [x, y] = pointAt(edge, direction > 0 ? f : 1 - f);
    ctx.beginPath(); ctx.arc(x, y, p.size / 2, 0, Math.PI * 2);
    ctx.fillStyle = p.color; ctx.shadowColor = p.color; ctx.shadowBlur = 8; ctx.fill();
  }
  ctx.shadowBlur = 0;
  requestAnimationFrame(frame);
}
layout();
requestAnimationFrame(frame);
/** 초당 점 수 = rps × 0.35 (선마다 최대 40개 동시). 점 수는 rps에 비례한다. */
const dotRate = (rps) => Math.max(0, rps || 0) * 0.35;

// ───────── 좌석 맵 ─────────
let seatLayoutKey = "", seatNodes = [];
function gradeOfRow(cfg, r) { let acc = 0; for (const g of cfg.grades || []) { acc += g.rows; if (r < acc) return g.name; } return "B"; }
function buildSeatMap(cfg) {
  // 칸 크기는 DB 영역 크기에 맞춘다. 영역이 커지면 상한(96×52)까지 키우고 남는 폭은 가운데 정렬
  const area = $("seat-area"), W = area.clientWidth - 46, H = area.clientHeight - 28;
  const key = JSON.stringify([cfg.rows, cfg.cols, cfg.grades, W, H]);
  if (key === seatLayoutKey) return;
  if (H <= 0 || W <= 0) return; // 숨겨진 상태: 보일 때 다시 계산
  seatLayoutKey = key;
  const map = $("seat-map"); map.replaceChildren(); seatNodes = [];
  const rows = cfg.rows, cols = cfg.cols, CW_MAX = 96, CH_MAX = 52;
  const groupBreaks = new Set(); { let acc = 0; for (const g of cfg.grades.slice(0, -1)) { acc += g.rows; groupBreaks.add(acc); } }
  let gap = 6, groupGap = 8;
  let cw = Math.min(CW_MAX, Math.floor((W - gap * (cols - 1)) / cols));
  let ch = Math.min(CH_MAX, Math.floor((H - gap * (rows - 1) - groupGap * groupBreaks.size) / rows));
  if (cw < 28 || ch < 18) { gap = 2; groupGap = 4; cw = Math.min(CW_MAX, Math.floor((W - gap * (cols - 1)) / cols)); ch = Math.min(CH_MAX, Math.floor((H - gap * (rows - 1) - groupGap * groupBreaks.size) / rows)); }
  cw = Math.max(2, cw); ch = Math.max(2, ch);
  const tiny = cw < 28 || ch < 18;
  const mapW = cols * cw + (cols - 1) * gap, x0 = Math.floor(Math.max(0, W - mapW) / 2);
  const mapH = rows * ch + (rows - 1) * gap + groupGap * groupBreaks.size, y0 = Math.floor(Math.max(0, H - mapH) / 2);
  Object.assign($("stage-strip").style, { left: `${x0 + 46}px`, width: `${mapW}px`, top: `${y0}px` });
  let y = y0 + 26; let lastGrade = null;
  for (let r = 0; r < rows; r++) {
    if (groupBreaks.has(r)) y += groupGap;
    const grade = gradeOfRow(cfg, r), gc = GRADE_COLORS[grade] || GRADE_COLORS.B;
    if (grade !== lastGrade) {
      const lab = el("span", "grade-label", grade); lab.style.left = `${x0}px`; lab.style.top = `${y + Math.max(0, ch / 2 - 10)}px`; lab.style.color = gc.bd; map.append(lab); lastGrade = grade;
    }
    for (let c = 0; c < cols; c++) {
      const s = el("span", `seat${tiny ? " tiny" : ""}`);
      Object.assign(s.style, { left: `${x0 + 46 + c * (cw + gap)}px`, top: `${y}px`, width: `${cw}px`, height: `${ch}px` });
      const label = String.fromCharCode(65 + r) + (c + 1);
      const t = el("span", "", tiny ? "" : label); const rem = el("i", "rem");
      s.append(t, rem); map.append(s);
      seatNodes.push({ node: s, text: t, rem, label, grade: gc, w: cw, state: "", ring: null });
    }
    y += ch + gap;
  }
}
function paintSeats(cfg, server, live) {
  buildSeatMap(cfg);
  const map = server.seatMap || "", now = performance.now();
  const holdTotal = (cfg.holdTtlSec * 1000) / (cfg.timeScale || 1), depTotal = (cfg.depositDeadlineSec * 1000) / (cfg.timeScale || 1);
  if (live && prevSeatMap && map.length === prevSeatMap.length)
    for (let i = 0; i < map.length; i++) if (map[i] === "A" && prevSeatMap[i] !== "A") releasedUntil.set(i, now + 1600);
  if (live) prevSeatMap = map;
  const conflicts = server.seatConflicts || {}, held = server.heldRemainingMs || {}, dep = server.depositRemainingMs || {};
  seatNodes.forEach((s, i) => {
    const st = map[i] || "A", id = String(i + 1);
    const conflict = live ? Number(conflicts[id] || 0) : 0, released = live && st === "A" && (releasedUntil.get(i) || 0) > now;
    const cls = `seat${s.node.classList.contains("tiny") ? " tiny" : ""} st-${st}${conflict ? " conflict" : ""}${released && !conflict ? " released" : ""}`;
    if (s.node.className !== cls) s.node.className = cls;
    if (st === "A" && !conflict && !released) Object.assign(s.node.style, { background: s.grade.bg, borderColor: s.grade.bd, color: s.grade.fg });
    else Object.assign(s.node.style, { background: "", borderColor: "", color: "" });
    const label = conflict ? `×${conflict}` : s.label;
    if (!s.node.classList.contains("tiny") && s.text.textContent !== label) s.text.textContent = label;
    let ratio = 0;
    if (st === "H" && held[id] !== undefined) ratio = held[id] / holdTotal;
    if (st === "D" && dep[id] !== undefined) ratio = dep[id] / depTotal;
    s.rem.style.width = `${Math.max(0, Math.min(1, ratio)) * Math.max(0, s.w - (s.node.classList.contains("tiny") ? 4 : 12))}px`;
    if (conflict >= 3 && !s.ring) { s.ring = el("span", "ring"); s.node.append(s.ring); }
    if (conflict < 3 && s.ring) { s.ring.remove(); s.ring = null; }
  });
}

// ───────── tick 처리 ─────────
function resetRunVisuals(id) {
  runKey = id; for (const k of Object.keys(history)) history[k].length = 0;
  lastServerAt = null; lastNotify = null; notifyRate = 0; prevSeatMap = ""; releasedUntil.clear();
  eventLog.length = 0; seenEvents.clear(); logPage = 0;
}
function categorize(message) {
  if (/만료|반환|취소표|예매 취소|미입금/.test(message)) return ["ret", "반환", "#3DDC97"];
  if (/예매 성공|결제|입금|카드|인증/.test(message)) return ["pay", "결제", "#4C8DFF"];
  if (/충돌/.test(message)) return ["hold", "선점", "#FF5D5D"];
  if (/선점/.test(message)) return ["hold", "선점", "#F5A524"];
  return ["sys", "시스템", "#B0B8C7"];
}
// eventHistory = 시뮬레이터가 보관한 마지막 80건(오래된 것 → 최신). 실행이 끝난 뒤·화면을 새로 연 뒤에도 같은 목록을 다시 만든다.
// 오래된 것부터 앞에 끼워 넣어 최신이 맨 앞에 온다. 목록은 새 실행을 시작할 때만 비운다 (resetRunVisuals).
function collectEvents(tick) {
  for (const line of tick.eventHistory || tick.recentEvents || []) {
    if (seenEvents.has(line)) continue;
    seenEvents.add(line);
    const m = /^(\d+):(\d+(?:\.\d+)?)\s+(.*)$/.exec(line);
    // 시뮬레이터가 남기는 시각은 실제 경과다. 헤더와 같은 시뮬레이션 시각으로 바꾼다.
    const scale = tick.timeScale || tick.config?.timeScale || 1;
    const time = m ? mmss((Number(m[1]) * 60 + Number(m[2])) * scale) : "--:--", message = m ? m[3] : line;
    const [cat, catName, color] = categorize(message);
    eventLog.unshift({ time, text: message, cat, catName, color, line });
  }
  if (eventLog.length > 80) eventLog.length = 80;
  if (seenEvents.size > 2000) { const keep = new Set(eventLog.map((e) => e.line)); seenEvents.clear(); keep.forEach((k) => seenEvents.add(k)); }
}
function receive(tick) {
  if (tick.id && tick.id !== runKey) resetRunVisuals(tick.id);
  const server = tick.server || {};
  if (tick.running && server.at && server.at !== lastServerAt) {
    const sig = tick.signals || {};
    const push = (k, v) => { history[k].push(v ?? null); if (history[k].length > 60) history[k].shift(); };
    push("rps", sig.rps); push("p95", sig.p95); push("err", sig.errPct); push("pool", sig.poolPct); push("srvP95", sig.p95);
    const sent = server.notifier?.sent;
    if (Number.isFinite(sent)) {
      const at = Date.parse(server.at);
      if (lastNotify && at > lastNotify.at) notifyRate = Math.max(0, (sent - lastNotify.sent) / ((at - lastNotify.at) / 1000));
      lastNotify = { sent, at };
    }
    lastServerAt = server.at;
  }
  if (tick.id) collectEvents(tick); // 끝난 실행도 (보관된 목록으로) 유지
  latest = tick;
  lockControls();
  if (!frozen) render(tick);
}

// ───────── 그리기 ─────────
function currentConfig(tick) { return tick?.id && tick.config ? tick.config : draft; }
function perSecond(count, windowMs) { return (Number(count) || 0) / (Math.max(1, Number(windowMs) || 1000) / 1000); }
function render(tick) {
  lastRendered = tick;
  const live = !!tick?.id;
  const cfg = currentConfig(tick) || draft;
  if (!cfg) return;
  const server = (live && tick.server) || {}, qs = (live && tick.queueServer) || {}, qst = (live && tick.queueStats) || {};
  const sig = (live && tick.signals) || {}, levels = sig.levels || {}, users = (live && tick.users) || {}, outcomes = (live && tick.outcomes) || {};
  const phase = live ? server.phase : null;
  const scale = cfg.timeScale || 1;

  // 헤더
  const running = !!tick?.running;
  $("live-dot").classList.toggle("on", running);
  // 엔진은 끝났고 남은 선점·결제 정리를 기다리는 중: status는 RUNNING이지만 "정리 중"으로 표시
  const finalizing = live && !!tick.finalizing;
  text("live-text", finalizing ? "정리 중" : live ? STATUS_TEXT[tick.status] || tick.status || "대기" : "대기");
  const simSec = live ? Number(tick.simElapsedSec ?? server.simElapsedSec ?? 0) : 0;
  text("live-clock", `${mmss(Math.min(simSec, cfg.saleDurationSec))} / ${mmss(cfg.saleDurationSec)}`);
  text("live-speed", `${scale}×`);
  const lost = running ? Object.entries({ server: "예약 서버", queue: "대기열 서버", mockPg: "모의 결제사" }).filter(([k]) => { const c = tick.connections?.[k]; return c && (c.ok ?? c.connected) === false; }).map(([, n]) => n) : [];
  $("conn-warning").hidden = !lost.length; $("conn-warning").textContent = lost.length ? `계측 끊김: ${lost.join(", ")}` : "";
  const finished = live && !running && ["COMPLETED", "STOPPED", "FAILED"].includes(tick.status);
  $("show-result").hidden = !finished;
  const pi = PHASES.indexOf(phase);
  stepNodes.forEach((li, i) => { li.className = i === pi ? `cur${phase === "SOLD_OUT" ? " soldout" : phase === "REOPEN" ? " reopen" : ""}` : i < pi ? "past" : ""; });

  // 카드 4개
  const total = server.total || {}, classes = total.errorClasses || {}, win = server.windowMs;
  const s409 = perSecond((classes.conflict || 0) + (classes.notPayable || 0), win), s403 = perSecond(classes.key, win), s5xx = perSecond(classes.server, win), s429 = perSecond(classes.rateLimited, win);
  const qStatus = qs.endpoints?.["queue.status"] || {};
  const cards = [
    ["rps", sig.rps, null, `대기열 폴링 ${num(qStatus.rps)} rps 별도`, Math.max(1, ...history.rps.filter(Number.isFinite))],
    ["p95", sig.p95, levels.p95, `p50 ${dec(sig.p50)} · p99 ${dec(sig.p99)} ms`, Math.max(cfg.thresholds?.p95SloMs || 300, ...history.p95.filter(Number.isFinite))],
    ["err", sig.errPct, levels.err, `409 ${num(s409)}/s · 403 ${num(s403)}/s · 5xx ${num(s5xx)} · 429 ${num(s429)}/s`, 100],
    ["pool", sig.poolPct, levels.pool, `대기 ${num(sig.poolPending)} · 락 대기 ${num(sig.lockWaits)}`, 100],
  ];
  // 정리 중·완료·중지·실패에는 숫자 카드를 비운다: 마지막 1초 값(판매 종료 409 등)이 멈춘 채 빨갛게 남지 않게. 결과는 실행 결과 탭에서
  if (finalizing || finished) for (const c of cards) { c[1] = null; c[2] = null; c[3] = finalizing ? "실행 끝 · 남은 선점·결제 정리 중" : "실행 끝 · 결과는 실행 결과 탭에서"; }
  for (const [k, v, lv, sub, max] of cards) {
    text(`v-${k}`, k === "p95" ? dec(v) : num(v));
    text(`s-${k}`, sub);
    const card = $(`card-${k}`), color = lv ? LEVEL_COLOR[levelOf(lv)] : "#EEF1F6";
    if (lv) setLevel(card, lv); else setLevel(card, "ok");
    $(`v-${k}`).style.color = live && v !== undefined && v !== null ? color : "#EEF1F6";
    $(`bars-${k}`).style.color = color;
    paintBars(cardBars[k], history[k], max, 90);
  }

  // 대기열 서버
  const waiting = qs.waiting ?? qst.WAITING ?? 0;
  text("q-waiting", num(waiting)); text("q-active", num(qs.active ?? qst.ADMITTED ?? 0)); text("q-max", num(cfg.maxActive));
  text("q-admit", num(qs.admittedThisTick ?? 0));
  const qp95 = qStatus.latency?.p95;
  setLevel($("node-queue"), qp95 === null || qp95 === undefined || qp95 < 20 ? "ok" : qp95 < 100 ? "warn" : "bad");
  const badge = { OPEN: ["정상", "#3DDC97", "#2E5E4A"], RUSH: ["정상", "#3DDC97", "#2E5E4A"], RESALE: ["취켓팅", "#F5A524", "#5E4A20"], SOLD_OUT: ["매진", "#FF8A8A", "#5E2A2A"], REOPEN: ["취소표 오픈", "#3DDC97", "#2E5E4A"], ENDED: ["종료", "#B0B8C7", "#3A465D"] }[phase] || ["대기", "#B0B8C7", "#3A465D"];
  text("q-badge", badge[0]); $("q-badge").style.color = badge[1]; $("q-badge").style.borderColor = badge[2];
  paintQueueDots(waiting, tick.usersByChurn);

  // 예약 서버
  const srvLv = worse(levels.p95, levels.pool);
  setLevel($("node-server"), live ? srvLv : "ok"); $("node-server").classList.toggle("pulse", live && srvLv === "bad");
  text("srv-state", live ? { ok: "여유", warn: "바쁨", bad: "과부하" }[srvLv] : "대기");
  $("srv-state").style.color = live ? LEVEL_COLOR[srvLv] : "";
  text("srv-p95", dec(sig.p95)); $("srv-p95").style.color = live && sig.p95 != null ? LEVEL_COLOR[levelOf(levels.p95)] : "";
  text("srv-inflight", num(server.inflightTotal ?? 0)); text("srv-rps", num(sig.rps ?? 0));
  $("srv-bars").style.color = LEVEL_COLOR[levelOf(levels.p95)];
  paintBars(srvBars, history.srvP95.slice(-40), Math.max(cfg.thresholds?.p95SloMs || 300, ...history.srvP95.filter(Number.isFinite)), 34);

  // 사용자
  const ug = userCells(tick, cfg, live);
  text("u-total", ug.stopped ? `${num(cfg.users)}명 · 중단 ${num(ug.stopped)}` : `${num(cfg.users)}명`);
  text("u-arriving", num(ug.arriving)); text("u-waiting", num(ug.waiting)); text("u-inside", num(ug.inside));
  text("u-revisit", num(ug.revisitWait)); text("u-left", num(ug.left)); text("u-bought", num(ug.bought));

  // 모의 결제사
  // 8.4: 요청 0인 창의 백분위는 null — 그 초에 승인 요청이 없으면 누적 값으로 채우지 않고 "–"
  const pgWin = server.pg?.confirm || {};
  const pgP95 = pgWin.p95;
  text("pg-p95", pgP95 == null ? "–" : num(pgP95));
  text("pg-auth", num(tick?.pg?.authInflight ?? 0)); text("pg-fail", num(tick?.pg?.failed ?? 0));
  setLevel($("node-pg"), (server.pg?.timeouts || 0) > 0 ? "bad" : pgP95 != null && pgP95 > cfg.confirmMaxMs ? "warn" : "ok");

  // DB
  const pool = server.pool || {}, poolMax = pool.max || 20;
  setLevel($("node-db"), live ? levels.pool : "ok"); $("node-db").classList.toggle("pulse", live && levelOf(levels.pool) === "bad");
  text("db-pool", num(pool.active ?? 0)); text("db-pool-max", `/${num(poolMax)}`);
  text("db-pending", num(pool.pending ?? 0)); text("db-locks", num(server.db?.lockWaits ?? 0));
  $("db-pending").style.color = (pool.pending || 0) > 0 ? "#FF5D5D" : ""; $("db-locks").style.color = (server.db?.lockWaits || 0) >= 5 ? "#FF5D5D" : "";
  ensurePoolSlots(poolMax);
  const hot = levelOf(levels.pool) === "bad";
  poolSlots.forEach((s, i) => { s.className = i < (pool.active || 0) ? (hot ? "hot" : "on") : ""; });
  const seats = { A: 0, H: 0, D: 0, R: 0, S: 0 };
  const seatMap = server.seatMap || "A".repeat(cfg.rows * cfg.cols);
  for (const ch of seatMap) seats[ch] = (seats[ch] || 0) + 1;
  text("seat-a", num(seats.A)); text("seat-h", num(seats.H)); text("seat-d", num(seats.D)); text("seat-r", num(seats.R)); text("seat-s", num(seats.S));
  if (live && server.releaseAt && server.at) countdownTarget = { ms: Date.parse(server.releaseAt) - Date.parse(server.at), at: performance.now(), scale };
  else countdownTarget = null;
  $("db-soldout").hidden = !(phase === "SOLD_OUT" && !countdownTarget);
  paintCountdown();
  paintSeats(cfg, live ? server : { seatMap }, live && running);

  // 연결선·라벨·요청 점
  const ep = server.endpoints || {}, rps = (k) => ep[k]?.rps || 0;
  // 예약 서버→DB: 좌석 조회는 실제로 DB를 읽은 수(캐시 hit·429 제외, seatsCache.dbReadsRps). 없는 기록(이전 서버)은 seats rps
  const seatsDb = Number.isFinite(server.seatsCache?.dbReadsRps) ? server.seatsCache.dbReadsRps : rps("seats");
  const dbRps = seatsDb + rps("holds") + rps("deposit") + rps("depositPay");
  const pgRps = server.pg?.endpoints?.confirm?.rps || 0;
  const authRate = (tick?.pg?.authInflight || 0) / 2;
  const errLv = levelOf(levels.err);
  const userColor = errLv === "bad" ? "#B5505C" : errLv === "warn" ? "#9C6A5A" : "#5F7099";
  const rates = [qStatus.rps || 0, dbRps, pgRps, authRate, sig.rps || 0, notifyRate];
  const colors = ["#5F7099", "#5F7099", "#4C6FB8", "#7A64C8", userColor, "#3E9C75"];
  // 끝난 실행은 마지막 수치를 카드·라벨에 남기되, 선 두께는 트래픽이 없으므로 기본 두께로 그린다.
  rates.forEach((r, i) => paintEdge(i, running ? r : 0, colors[i]));
  const labels = [
    [`${num(qStatus.rps || 0)} rps 폴링`, "#B0B8C7"], [`${num(dbRps)}/s`, "#B0B8C7"], [`승인 ${num(pgRps)}/s`, "#7FA8FF"], ["카드 인증", "#B9A4FF"],
    [`${num(sig.rps || 0)} rps · 에러 ${num(sig.errPct || 0)}%`, errLv === "ok" ? "#B0B8C7" : "#FF8A8A"], ["자리 반납", "#5FD3A2"],
  ];
  labels.forEach(([t, c], i) => { edgeLabels[i].textContent = t; edgeLabels[i].style.color = c; });
  const enter = qs.endpoints?.["queue.enter"]?.rps || 0;
  streams = !running ? [] : [
    { route: [[0, 1]], color: "#C3C9D4", size: 7, rate: dotRate(enter) },
    { route: [[0, 1]], color: "#6B7488", size: 5, rate: dotRate(qStatus.rps) },
    { route: [[0, -1]], color: "#EEF1F6", size: 7, rate: dotRate(qs.admittedThisTick) },
    { route: [[4, 1], [1, 1]], color: "#9AA3B2", size: 6, rate: dotRate(rps("seats")) },
    { route: [[4, 1], [1, 1]], color: "#F5A524", size: 8, rate: dotRate(rps("holds")) },
    { route: [[4, -1]], color: "#FF5D5D", size: 8, rate: dotRate(s409) },
    { route: [[4, -1]], color: "#B54A4A", size: 5, rate: dotRate(s403) },
    { route: [[5, 1]], color: "#3DDC97", size: 7, rate: dotRate(notifyRate) },
    { route: [[3, 1]], color: "#9B7BFF", size: 8, rate: dotRate(tick.clientRps?.auth) },
    { route: [[2, 1]], color: "#4C8DFF", size: 8, rate: dotRate(pgRps) },
  ];

  // 하단 띠
  const top = eventLog[0];
  $("log-dot").style.background = top ? top.color : "#3A465D";
  text("log-time", top ? top.time : "--:--"); text("log-text", top ? top.text : "아직 이벤트 없음"); text("log-count", num(eventLog.length));
  const c = counterValues(tick);
  text("c-returns", num(c.returns)); text("c-reopen", `${num(c.reopen)}회`); text("c-conflicts", num(c.conflicts));

  if (detailType && detailType !== "custom") renderDetail();
}
function paintCountdown() {
  const box = $("db-countdown");
  if (!countdownTarget) { box.hidden = true; return; }
  const remainingReal = countdownTarget.ms - (performance.now() - countdownTarget.at);
  box.hidden = remainingReal <= 0;
  text("db-countdown-v", mmss((remainingReal / 1000) * countdownTarget.scale));
}
setInterval(() => { if (!frozen) paintCountdown(); }, 250);

const qDots = [];
function paintQueueDots(waiting, byChurn) {
  const n = Math.min(187, Math.ceil((waiting || 0) / 8));
  while (qDots.length < n) { const d = el("i"); $("q-dots").append(d); qDots.push(d); }
  const w = { c: byChurn?.casual?.waiting || 0, p: byChurn?.persistent?.waiting || 0, h: byChurn?.hardcore?.waiting || 0 };
  const sum = w.c + w.p + w.h || 1, fc = w.c / sum, fp = w.p / sum;
  qDots.forEach((d, i) => {
    if (i >= n) { d.style.display = "none"; return; }
    const u = ((i * 37) % 100) / 100;
    Object.assign(d.style, { display: "block", left: `${(i % 17) * 15}px`, top: `${Math.floor(i / 17) * 13}px`, background: u < fc ? "#6B7488" : u < fc + fp ? "#C9A227" : "#FF7A45", opacity: String(0.6 + 0.4 * (1 - i / 200)) });
  });
}
/** 사용자 노드 칸 = 시뮬레이터의 userGroups (사용자마다 정확히 한 칸, 합 = users). 실행 전에는 모두 도착 전. */
function userCells(tick, cfg, live) {
  const g = live ? tick?.userGroups || {} : { arriving: cfg.users };
  const v = (k) => Number(g[k]) || 0;
  return { arriving: v("arriving"), waiting: v("waiting"), inside: v("inside"), revisitWait: v("revisitWait"), left: v("left"), bought: v("bought"), stopped: v("stopped") };
}
function counterValues(tick) {
  const ev = tick?.events || {}, out = tick?.outcomes || {};
  return {
    immediate: ev.immediateReturnsSeen || 0, cancels: out.canceledAfterPurchase || 0, returns: (ev.immediateReturnsSeen || 0) + (out.canceledAfterPurchase || 0),
    reopen: ev.reopenSeen || 0, conflicts: ev.conflicts || 0,
    bought: Number(tick?.userGroups?.bought) || 0, cardFail: (ev.authFailed || 0) + (ev.declined || 0),
  };
}
function updateIdlePreview() { if (!latest?.id && !frozen && draft) render(latest || {}); }

// ───────── 상세 창 ─────────
const DETAIL_TITLES = {
  queue: ["대기열 서버", "별도 프로세스 · 순번과 입장키 발급"], server: ["예약 서버", "입장키 서명만 확인 · 대기열 서버 호출 없음"],
  db: ["DB · Postgres", "커넥션 풀 · 락 · 좌석"], pg: ["모의 결제사", "카드 인증은 사용자가 직접, 승인은 예약 서버가 요청"],
  users: ["사용자", "도착 · 입장 · 이탈 · 성향 구성"], err: ["에러", "예약 서버가 돌려준 실패 응답 (1초 창)"],
  log: ["실시간 이벤트", "최근 80건 · 실행은 계속됨"], counters: ["누적 카운터", "시작부터 누적 · 실행은 계속됨"],
};
/** 결과 화면 등에서 쓰는 상세 창: build(body, filters)가 내용을 채운다. tick마다 다시 그리지 않는다. */
let customBuild = null;
function openModal(title, sub, build, opener) {
  detailType = "custom"; customBuild = build; detailOpener = opener || document.activeElement;
  $("modal").hidden = false; $("modal-backdrop").hidden = false;
  text("modal-title", title); text("modal-sub", sub || "");
  $("modal-filters").hidden = true; $("modal-filters").replaceChildren(); $("modal-body").replaceChildren();
  build($("modal-body"), $("modal-filters"));
  $("modal-close").focus();
}
window.openModal = openModal;
window.closeModal = () => closeDetail();
function openDetail(type, opener) {
  detailType = type; detailOpener = opener || document.activeElement; logPage = 0;
  $("modal").hidden = false; $("modal-backdrop").hidden = false;
  renderDetail(); $("modal-close").focus();
}
function closeDetail() {
  if (!detailType) return;
  detailType = null; $("modal").hidden = true; $("modal-backdrop").hidden = true;
  if (detailOpener && document.contains(detailOpener)) detailOpener.focus();
}
for (const n of document.querySelectorAll("[data-detail]")) n.addEventListener("click", () => openDetail(n.dataset.detail, n));
$("modal-close").onclick = closeDetail; $("modal-backdrop").onclick = closeDetail;
addEventListener("keydown", (e) => { if (e.key === "Escape") closeDetail(); });
const card = (k, v, color, d) => ({ k, v, color, d });
function cardsNode(items, cls = "dcards") {
  const box = el("div", cls);
  for (const it of items) {
    const n = el("div", "dcard"); n.append(el("span", "k", it.k));
    const v = el("span", "v", it.v); if (it.color) v.style.color = it.color; n.append(v);
    if (it.d) n.append(el("span", "d", it.d));
    box.append(n);
  }
  return box;
}
function tableNode(head, rows) {
  const t = el("table", "dtable"), tr = el("tr");
  for (const h of head) tr.append(el("th", "", h));
  t.append(tr);
  for (const r of rows) { const row = el("tr"); for (const v of r) row.append(el("td", "", v)); t.append(row); }
  return t;
}
function churnBar(label, part) {
  const box = el("div", "dcard"); const sum = part.c + part.p + part.h;
  box.append(el("span", "k", `${label} · ${num(sum)}명`));
  const bar = el("div", "churn-bar");
  for (const [v, c] of [[part.c, "#6B7488"], [part.p, "#C9A227"], [part.h, "#FF7A45"]]) { const i = el("i"); i.style.width = `${sum ? (v / sum) * 100 : 0}%`; i.style.background = c; bar.append(i); }
  box.append(bar, el("span", "d", sum ? `Casual ${Math.round((part.c / sum) * 100)}% · Persistent ${Math.round((part.p / sum) * 100)}% · Hardcore ${Math.round((part.h / sum) * 100)}%` : "인원 없음"));
  return box;
}
const ago = (at, base) => (at && base ? `${dec((Date.parse(base) - Date.parse(at)) / 1000)}초 전` : "–");
function renderDetail() {
  if (detailType === "custom") return;
  const tick = latest || {}, cfg = currentConfig(tick) || draft, live = !!tick.id;
  const server = (live && tick.server) || {}, qs = (live && tick.queueServer) || {}, qst = (live && tick.queueStats) || {};
  const sig = (live && tick.signals) || {}, levels = sig.levels || {}, users = (live && tick.users) || {}, out = (live && tick.outcomes) || {};
  const [title, sub] = DETAIL_TITLES[detailType] || ["", ""];
  text("modal-title", title); text("modal-sub", sub);
  const body = $("modal-body"), filters = $("modal-filters");
  body.replaceChildren(); filters.hidden = true;
  const classes = server.total?.errorClasses || {}, win = server.windowMs;
  const qStatus = qs.endpoints?.["queue.status"] || {};
  if (detailType === "queue") {
    body.append(cardsNode([
      card("대기 인원", `${num(qs.waiting ?? qst.WAITING)}명`), card("입장 중 / 정원", `${num(qs.active ?? qst.ADMITTED)} / ${num(cfg.maxActive)}`),
      card("입장키 발급", `${num(qs.admittedThisTick)}/s`, null, `초당 입장 상한 ${num(cfg.admitPerSec)} (시뮬레이션 기준)`),
      card("순번 응답", `${num(qStatus.rps)} rps · p95 ${dec(qStatus.latency?.p95)} ms`, null, "대기자 폴링. 예약 서버에는 닿지 않음"),
      card("자리 반납", `${dec(notifyRate)}/s`, "#3DDC97", "예약 서버가 보내는 자리 알림 (선점·해제·완료)"),
      card("busy", num(qs.busy ?? qst.busy), null, "진행 중 예약이 있어 만료하지 않는 자리"),
      card("만료 미룸 중", num(qs.slotsBusyOverTtl ?? qst.slotsBusyOverTtl), null, "결제 중 TTL을 넘긴 busy 자리 — 정상"),
      card("busy 상한 강제 만료 (누적)", num(qs.expiredByBusyCap ?? 0), (qs.expiredByBusyCap || 0) > 0 ? "#F5A524" : null, "0이 아니면 알림 유실 가능성 (I16)"),
    ]));
  } else if (detailType === "server") {
    const ep = server.endpoints || {}, keyPass = Math.max(0, (sig.rps || 0) - perSecond(classes.key, win));
    body.append(cardsNode([
      card("응답 시간", `p50 ${dec(sig.p50)} · p95 ${dec(sig.p95)} · p99 ${dec(sig.p99)} ms`, LEVEL_COLOR[levelOf(levels.p95)]),
      card("처리 중 요청", num(server.inflightTotal)),
      card("입장키", `통과 ${num(keyPass)}/s · 403 ${num(perSecond(classes.key, win))}/s`, null, "서명·runEpoch·만료만 확인"),
      card("스레드", `${num(server.http?.threadsBusy)} / ${server.http?.threadsMax == null ? "가상" : num(server.http.threadsMax)}`),
      card("힙 · GC", `${num(server.jvm?.heapUsedMb)} / ${num(server.jvm?.heapMaxMb)} MB · GC ${num(server.jvm?.gcPauseMs)} ms`),
      card("알림 큐", `대기 ${num(server.notifier?.queued)} · 재시도 ${num(server.notifier?.retried)} · 유실 ${num(server.notifier?.dropped)}`, (server.notifier?.dropped || 0) > 0 ? "#F5A524" : null),
    ], "dcards three"));
    const names = { seats: "좌석 조회", holds: "선점", release: "선점 취소", checkout: "주문", confirm: "카드 승인", deposit: "입금 대기", depositPay: "입금", cancel: "예매 취소", reservation: "예약 조회" };
    body.append(tableNode(["엔드포인트", "rps", "p95 ms", "처리 중", "오류"], Object.entries(names).filter(([k]) => ep[k]).map(([k, n]) => [n, dec(ep[k].rps), dec(ep[k].latency?.p95), num(ep[k].inflight), Object.entries(ep[k].errorClasses || {}).filter(([, v]) => v).map(([c, v]) => `${c} ${v}`).join(" · ") || "–"])));
    const sch = server.schedulers || {};
    body.append(tableNode(["스케줄러", "마지막 실행", "처리"], [["선점 만료", ago(sch.expiry?.lastRunAt, server.at), num(sch.expiry?.expired)], ["결제 복구", ago(sch.recovery?.lastRunAt, server.at), num(sch.recovery?.recovered)], ["입금 마감", ago(sch.depositExpiry?.lastRunAt, server.at), num(sch.depositExpiry?.expired)], ["취소표 오픈", ago(sch.reopen?.lastRunAt, server.at), num(sch.reopen?.reopened)]]));
  } else if (detailType === "db") {
    const pool = server.pool || {}, seats = { A: 0, H: 0, D: 0, R: 0, S: 0 };
    for (const ch of server.seatMap || "") seats[ch]++;
    body.append(cardsNode([
      card("커넥션 풀", `${num(pool.active)} / ${num(pool.max)}`, LEVEL_COLOR[levelOf(levels.pool)]), card("풀 대기", num(pool.pending), (pool.pending || 0) > 0 ? "#FF5D5D" : null, "커넥션을 못 받고 기다리는 요청"),
      card("커넥션 획득 p95", `${dec(pool.acquireMs?.p95)} ms`, null, `최대 ${dec(pool.acquireMs?.max)} ms · 타임아웃 ${num(pool.timeouts)}`),
      card("락 대기", `${num(server.db?.lockWaits)}개 · 최대 ${num(server.db?.lockWaitMaxMs)} ms`, (server.db?.lockWaits || 0) >= 5 ? "#FF5D5D" : null, "같은 좌석 행 잠금 경합"),
      card("충돌", `${num(perSecond(classes.conflict, win))}/s`, "#FF5D5D", "선점 경쟁에서 진 요청 (409)"),
      card("좌석 조회 → DB", `${num(server.seatsCache?.dbReadsRps)}/s · 캐시 hit ${num(server.seatsCache?.hitsRps)}/s · 429 ${num(server.seatsCache?.rateLimitedRps)}/s`, null, `캐시 ${server.seatsCache?.cacheSec ? `${server.seatsCache.cacheSec}초` : "끔"} · 새로고침 제한 ${server.seatsCache?.rateLimitEnabled ? `${server.seatsCache.minIntervalSec}초` : "끔"}`),
      card("좌석", `빈 ${num(seats.A)} · 선점 ${num(seats.H)} · 입금 ${num(seats.D)} · 반환 ${num(seats.R)} · 판매 ${num(seats.S)}`),
    ]));
  } else if (detailType === "pg") {
    const w = server.pg?.confirm || {}, cum = tick.mockPg?.confirmLatency || {};

    body.append(cardsNode([
      card("카드 인증 중", num(tick.pg?.authInflight), "#9B7BFF", "사용자 → 결제사 직접"), card("승인 요청", `${dec(server.pg?.endpoints?.confirm?.rps)}/s`, "#4C8DFF", "예약 서버 → 결제사"),
      card("승인 응답 (최근 1초)", w.p95 == null ? "–" : `p50 ${num(w.p50)} · p95 ${num(w.p95)} · p99 ${num(w.p99)} ms`, "#4C8DFF", "예약 서버 측. 그 초에 승인 요청이 없으면 –"),
      card("승인 응답 (누적)", cum.p95 == null ? "–" : `p50 ${num(cum.p50)} · p95 ${num(cum.p95)} · p99 ${num(cum.p99)} ms`, "#7FA8FF", "결제사 측, reset 이후 전체"),
      card("결제 실패", num(tick.pg?.failed), "#FF8A8A", "인증 실패 + 승인 거절"), card("타임아웃", `${num(server.pg?.timeouts)} (결제사 기록 ${num(tick.mockPg?.timedOut)})`),
      card("결제 수단", `카드 ${num(cfg.paymentMix?.card)} · 입금 대기 ${num(cfg.paymentMix?.deposit)} %`, "#B0B8C7", `승인 지연 설정 ${num(cfg.confirmMinMs)}~${num(cfg.confirmMaxMs)} ms`),
    ]));
  } else if (detailType === "users") {
    const ug = userCells(tick, cfg, live);
    body.append(cardsNode([
      card("도착 전", num(ug.arriving)), card("대기", num(ug.waiting), null, "대기열에서 입장 차례를 기다림"),
      card("입장", num(ug.inside), null, "좌석 조회·선점·결제·입금 대기 중"),
      card("재방문 대기", num(ug.revisitWait), "#22B8CF", "좌석 없이 떠나거나 대기 중 이탈해 취소표 오픈을 기다림 (판매 종료 때 이탈로 확정)"),
      card("이탈", num(ug.left), "#B0B8C7", "포기·매진 퇴장·결제 포기 (결과 확정)"),
      card("예매 완료", num(ug.bought), "#4C8DFF", "카드 확정 + 입금 완료 (예매 후 취소 대기 포함)"),
    ], "dcards three"));
    if (ug.stopped) body.append(el("p", "cfg-note", `중단 ${num(ug.stopped)}명: 실행 중지·시간 제한·오류로 끝난 사용자 (incomplete·error)`));
    const by = tick.usersByChurn || {};
    const part = (fn) => ({ c: fn(by.casual || {}), p: fn(by.persistent || {}), h: fn(by.hardcore || {}) });
    body.append(churnBar("대기 중 성향", part((u) => u.waiting || 0)));
    body.append(churnBar("입장 중 성향", part((u) => (u.admitted_browsing || 0) + (u.holding || 0) + (u.authenticating || 0) + (u.confirming || 0) + (u.pending_deposit || 0))));
    body.append(churnBar("재방문 대기 성향", part((u) => u.departed || 0)));
  } else if (detailType === "err") {
    const ps = (k) => `${dec(perSecond(classes[k], win))}/s`;
    body.append(cardsNode([
      card("에러율", `${dec(sig.errPct)}%`, LEVEL_COLOR[levelOf(levels.err)], "non-2xx ÷ 전체 (예약 서버 1초 창). 429 새로고침 제한은 분자·분모 모두 제외"), card("실패율", `${dec(sig.failPct)}%`, null, "클라이언트 측 5xx + timeout + transport. 409는 경쟁 결과, 429는 새로고침 제한이라 제외"),
      card("conflict (409)", ps("conflict"), "#FF5D5D", "좌석·사용자 충돌"), card("notPayable (409)", ps("notPayable"), null, "결제 불가·판매 종료"),
      card("key (403)", ps("key"), "#FF8A8A", "입장키 만료·회수·무효"), card("declined (402)", ps("declined")),
      card("client (4xx)", ps("client")), card("server (5xx)", ps("server"), (classes.server || 0) > 0 ? "#FF5D5D" : null),
      card("rateLimited (429)", ps("rateLimited"), null, "좌석 조회 새로고침 제한. 에러율에 넣지 않음"),
    ], "dcards three"));
  } else if (detailType === "counters") {
    const c = counterValues(tick), g = tick.seatsByGrade || {};
    body.append(cardsNode([
      card("즉시 반환", num(c.immediate), "#3DDC97", "선점 만료·결제 실패로 바로 풀린 것을 사용자가 본 횟수"), card("예매 취소", num(c.cancels), "#3DDC97", "판매 후 취소해서 바로 풀린 예매"),
      card("취소표 오픈", `${num(c.reopen)}회`, "#22B8CF", "미입금 좌석을 모았다가 한꺼번에 푼 횟수"), card("등급별 판매", Object.entries(g).map(([k, v]) => `${k} ${v}`).join(" · ") || "–", "#C3C9D4"),
      card("충돌 누적", num(c.conflicts), "#FF5D5D", "선점 경쟁에서 진 요청 (409)"),
      card("예매 완료", num(c.bought), "#4C8DFF", "카드 확정 + 입금 완료"), card("카드 결제 실패", num(c.cardFail), "#FF5D5D", "인증 실패 + 승인 거절"),
    ]));
  } else if (detailType === "log") {
    filters.hidden = false; filters.replaceChildren();
    for (const [id, name] of [["all", "전체"], ["hold", "선점"], ["ret", "반환"], ["pay", "결제"], ["sys", "시스템"]]) {
      const b = el("button", "", name); b.type = "button"; b.setAttribute("aria-pressed", String(id === logFilter));
      b.onclick = () => { logFilter = id; logPage = 0; renderDetail(); };
      filters.append(b);
    }
    const list = eventLog.filter((e) => logFilter === "all" || e.cat === logFilter);
    const pages = Math.max(1, Math.ceil(list.length / 20)); logPage = Math.min(logPage, pages - 1);
    const box = el("div", "log-list");
    if (!list.length) box.append(el("p", "muted", "이벤트가 없습니다."));
    for (const e of list.slice(logPage * 20, logPage * 20 + 20)) {
      const r = el("div", "log-row"), dot = el("i", "dot"); dot.style.background = e.color;
      const cat = el("span", "c", e.catName); cat.style.color = e.color;
      r.append(dot, el("span", "t", e.time), cat, el("span", "x", e.text)); box.append(r);
    }
    body.append(box);
    const nav = el("div", "cfg-actions");
    const prev = el("button", "", "‹ 최신 쪽"), next = el("button", "", "이전 기록 ›");
    prev.type = next.type = "button"; prev.disabled = logPage === 0; next.disabled = logPage >= pages - 1;
    prev.onclick = () => { logPage--; renderDetail(); }; next.onclick = () => { logPage++; renderDetail(); };
    nav.append(prev, el("span", "muted", `${logPage + 1} / ${pages} 쪽 · ${num(list.length)}건`), next); body.append(nav);
  }
}

// ───────── 시작 ─────────
async function initialize() {
  busy = true; lockControls();
  try {
    const [base, current] = await Promise.all([api("/defaults"), api("/runs/current")]);
    defaults = base; draft = clone(current.config || base);
    renderFields();
    latest = current.id ? current : null;
    render(latest || {});
    await loadPresets();
    const source = new EventSource("/api/stream");
    source.addEventListener("tick", (e) => { try { streamOk = true; receive(JSON.parse(e.data)); } catch (err) { notice(`스트림을 읽지 못했습니다: ${err.message}`, true); } });
    source.onerror = () => { streamOk = false; notice("실시간 스트림 연결 끊김 · 자동 재연결 중", true); };
    source.onopen = () => { if (!streamOk) notice(""); };
  } catch (e) {
    notice(`초기화 실패: ${e.message}`, true);
  } finally { busy = false; lockControls(); }
}
initialize();
