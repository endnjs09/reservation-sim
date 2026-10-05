"use strict";
// 실행 결과 화면 (SPEC 14.2, 9장). 저장된 실행 기록(/api/runs…)과 비교 API(/api/compare)만 사용한다. 가짜 데이터 없음.
// app.js의 공용 함수($, el, api, num, dec, mmss, STRATEGIES, openModal, closeModal)를 쓴다.
(() => {
  const TAG_COLOR = ["#4C8DFF", "#F5A524"];
  const BAND = {
    OPEN: ["rgba(76,140,255,0.05)", "#7D879A", "오픈"], RUSH: ["rgba(255,93,93,0.10)", "#FF8A8A", "폭주"],
    RESALE: ["rgba(245,165,36,0.07)", "#F5C46A", "취켓팅"], SOLD_OUT: ["rgba(130,142,165,0.08)", "#929CAF", "매진"],
    REOPEN: ["rgba(34,184,207,0.10)", "#5FD6E6", "취소표"], ENDED: ["rgba(130,142,165,0.05)", "#7D879A", "종료"],
  };
  const WARN_TEXT = {
    SCENARIO_DIFFERS: "시나리오가 다름 (fingerprint)", MULTIPLE_VARIABLES: "실험 변수가 2개 이상 다름", TIME_SCALED: "1×가 아닌 실행 포함 — 부하 비교에 쓰지 않음",
    NOT_COMPLETED: "완료되지 않은 실행 포함", OLD_SCHEMA: "구버전 기록 — 요약만 비교", ENV_DIFFERS: "측정 환경이 다름 (CPU·OS·bench)",
    CLOCK_MODEL_DIFFERS: "시계 기준이 다름", ENV_DEGRADED: "측정 중 스왑 사용 (메모리 부족)",
  };
  const ok = (v) => v !== null && v !== undefined && Number.isFinite(Number(v));
  const fmtTime = (iso) => { if (!iso) return "–"; const d = new Date(iso); return `${String(d.getMonth() + 1).padStart(2, "0")}/${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; };
  const STATUS = { COMPLETED: "완료", STOPPED: "중지", FAILED: "실패", RUNNING: "실행 중" };

  let mode = "single";
  let selected = [];          // single: [id], compare: [A, B] (A = 먼저 고른 것)
  let runs = [], listWarnings = [];
  const cache = new Map();    // id → { run, series, invariants, legacy }
  let compare = null;
  let renderToken = 0;

  // ───────── 데이터 ─────────
  async function loadList() {
    const res = await api("/runs");
    runs = res.runs || []; listWarnings = res.warnings || [];
  }
  async function load(id) {
    if (cache.has(id)) return cache.get(id);
    const run = await api(`/runs/${encodeURIComponent(id)}`);
    const legacy = !(Number(run.schemaVersion) >= 5);
    let series = null, invariants = null;
    if (!legacy) {
      const length = Number(run.simDurationSec) || (Number(run.durationMs) / 1000) * (Number(run.timeScale) || 1) || 1200;
      const step = Math.max(1, Math.ceil(length / 300)); // 9.4: 폭 600px 기준 N = ceil(길이 ÷ 300)
      try { series = await api(`/runs/${encodeURIComponent(id)}/timeseries?fields=signals,server,client,phase&step=${step}`); } catch { series = null; }
      try { invariants = await api(`/runs/${encodeURIComponent(id)}/invariants`); } catch { invariants = null; }
    }
    const entry = { run, series, invariants, legacy };
    cache.set(id, entry);
    return entry;
  }
  const invPassed = (inv) => (inv?.results ? inv.results.filter((r) => r.pass).length : null);
  const runTitle = (r) => r.label || r.runId || r.id || "이름 없음";
  const SHORT = { conditional: "조건부", pessimistic: "비관적", optimistic: "낙관적", naive: "단순" };
  /** 범례·표 머리용 짧은 이름: 전략 + run ID 끝 4자리 */
  const shortName = (r) => `${SHORT[r.server?.strategy] || r.server?.strategy || ""} ${(r.runId || "").slice(-4)}`.trim();

  // ───────── 선택 ─────────
  function pick(id) {
    if (!id) return;
    if (mode === "single") selected = [id];
    else if (!selected.includes(id)) { selected = [...selected, id].slice(-2); }
    render();
  }
  function setMode(m) {
    mode = m;
    $("r-mode-single").setAttribute("aria-pressed", String(m === "single"));
    $("r-mode-compare").setAttribute("aria-pressed", String(m === "compare"));
    if (m === "single") selected = selected.slice(-1);
    else if (selected.length < 2) { const other = runs.find((r) => r.runId !== selected[0]); if (other) selected = [other.runId, ...selected].slice(-2); }
    render();
  }
  $("r-mode-single").onclick = () => setMode("single");
  $("r-mode-compare").onclick = () => setMode("compare");

  async function show(runId) {
    // 선택은 목록을 기다리기 전에 바로 반영한다: 연달아 불러도 순서대로 쌓인다 (A = 먼저, B = 나중)
    if (runId) { cache.delete(runId); if (mode === "single") selected = [runId]; else if (!selected.includes(runId)) selected = [...selected, runId].slice(-2); }
    try { await loadList(); } catch (e) { notice(`실행 목록을 읽지 못했습니다: ${e.message}`, true); return; }
    selected = selected.filter((id) => runs.some((r) => r.runId === id));
    if (!selected.length && runs.length) selected = mode === "single" ? [runs[0].runId] : runs.slice(0, 2).map((r) => r.runId).reverse();
    render();
  }

  // ───────── 그리기 ─────────
  async function render() {
    const token = ++renderToken;
    $("r-empty").hidden = runs.length > 0;
    for (const id of ["r-cards", "r-charts"]) $(id).hidden = !runs.length;
    document.querySelector(".r-main").hidden = !runs.length;
    renderChips();
    $("r-pick-hint").textContent = mode === "compare" ? "비교할 실행 2개 선택" : "실행 선택";
    if (!selected.length) return;
    // 불러오는 동안 이전 실행의 관리 단추를 누르지 못하게 한다
    for (const b of $("r-manage").querySelectorAll("button")) b.disabled = true;
    let entries;
    try { entries = await Promise.all(selected.map(load)); } catch (e) { notice(`실행 기록을 읽지 못했습니다: ${e.message}`, true); return; }
    compare = null;
    if (mode === "compare" && selected.length === 2) {
      try { compare = await api(`/compare?a=${encodeURIComponent(selected[0])}&b=${encodeURIComponent(selected[1])}`); } catch (e) { notice(`비교 실패: ${e.message}`, true); }
    }
    if (token !== renderToken) return;
    const cmp = mode === "compare" && entries.length === 2;
    renderHeader(entries, cmp);
    renderCards(entries, cmp);
    renderCharts(entries, cmp);
    renderManage(entries.at(-1), cmp ? 1 : 0);
    renderTable(entries, cmp);
    renderInvariants(entries, cmp);
    renderConditions(entries, cmp);
  }
  function renderChips() {
    const box = $("r-chips"); box.replaceChildren();
    // 14.2: 최근 3개 칩. 고른 실행이 그 안에 없으면 뒤에서부터 바꿔 넣는다 (칩은 3개 유지)
    const recent = runs.slice(0, 3);
    let slot = recent.length - 1;
    for (const id of selected) if (!recent.some((r) => r.runId === id)) { const r = runs.find((x) => x.runId === id); while (slot >= 0 && selected.includes(recent[slot]?.runId)) slot--; if (r && slot >= 0) recent[slot--] = r; }
    for (const r of recent) {
      const i = selected.indexOf(r.runId), on = i >= 0, color = on ? TAG_COLOR[mode === "compare" ? i : 0] : "#3D4A64";
      const b = el("button", `r-chip${on ? " on" : ""}`); b.type = "button"; b.style.borderColor = color;
      const dot = el("i"); dot.style.background = on ? color : "#5B6475";
      const t = el("span", "t"); t.append(el("b", "", `${on && mode === "compare" ? `${"AB"[i]} ` : ""}${runTitle(r)}`), el("small", "", `${r.runId} · ${fmtTime(r.startedAt)}`));
      b.append(dot, t); b.onclick = () => pick(r.runId);
      b.setAttribute("aria-pressed", String(on));
      box.append(b);
    }
  }
  function renderHeader(entries, cmp) {
    const c = entries[0].run.config || {};
    $("r-sub").textContent = `RUN REPORT · ${num(c.users)}명 · ${num((c.rows || 0) * (c.cols || 0))}석 · ${c.timeScale || "?"}×`;
    const warn = $("r-warn"), messages = [];
    if (cmp && compare) {
      for (const w of compare.warnings || []) messages.push(compare.warningNotes?.[w] ? `${WARN_TEXT[w] || w}: ${compare.warningNotes[w]}` : WARN_TEXT[w] || w);
    }
    for (const [i, e] of entries.entries()) {
      const tag = cmp ? `${"AB"[i]} ` : "";
      if (e.legacy) messages.push(`${tag}구버전 기록 · 요약만 (시계열 없음)`);
      else if (!cmp && Number(e.run.timeScale) !== 1) messages.push(`${tag}${e.run.timeScale}× 실행 — 부하 측정·전략 비교는 1×로`);
      if (!cmp && e.run.status && e.run.status !== "COMPLETED") messages.push(`${tag}${STATUS[e.run.status] || e.run.status}${e.run.statusReason ? ` (${e.run.statusReason})` : ""}`);
    }
    if (listWarnings.length) messages.push(`읽지 못한 기록: ${listWarnings.join(", ")}`);
    warn.hidden = !messages.length; warn.textContent = messages.length ? `⚠ ${messages.join(" · ")}` : "";
  }

  // ───────── 요약 카드 6개 ─────────
  function cardValues(entry) {
    const r = entry.run, s = r.signals || {}, inv = entry.invariants;
    return {
      p95Max: s.p95Max, p95Rush: s.p95Rush, rpsMax: s.rpsMax, poolSatSec: s.poolSatSec, soldOutAtSec: s.soldOutAtSec,
      inv: inv?.results ? `${invPassed(inv)}/${inv.results.length}` : null, invRatio: inv?.results ? invPassed(inv) / Math.max(1, inv.results.length) : null,
      seatsSold: r.seatsSold, thresholds: r.thresholds || r.config?.thresholds || {},
    };
  }
  function renderCards(entries, cmp) {
    const vals = entries.map(cardValues), th = vals[0].thresholds;
    const defs = [
      ["최대 p95 응답", "p95Max", "ms", "low", (v) => dec(v), `SLO ${num(th.p95SloMs)}ms`],
      ["폭주 구간 평균 p95", "p95Rush", "ms", "low", (v) => dec(v), "phase = 선점 폭주 평균"],
      ["최대 처리량", "rpsMax", "rps", null, (v) => num(v), "예약 서버 사용자 요청"],
      ["풀 포화 시간", "poolSatSec", "초", "low", (v) => num(v), `풀 사용률 ≥ ${num(th.poolBadPct)}%`],
      ["매진 시각 (잔여 첫 0)", "soldOutAtSec", "", null, (v) => mmss(v), `판매 ${vals.map((v) => num(v.seatsSold)).join(" / ")}석`],
      ["불변식", "inv", "", "high", (v) => v, null],
    ];
    const box = $("r-cards"); box.replaceChildren();
    for (const [label, key, unit, better, f, sub] of defs) {
      const card = el("div", `r-card${cmp ? " two" : ""}`);
      card.append(el("span", "k", label));
      const row = el("div", "vals");
      const nums = vals.map((v) => (key === "inv" ? v.invRatio : v[key]));
      let win = -1;
      if (cmp && better && nums.every(ok) && Number(nums[0]) !== Number(nums[1])) win = (Number(nums[0]) < Number(nums[1])) === (better === "low") ? 0 : 1;
      vals.forEach((v, i) => {
        const raw = v[key];
        const span = el("span", "v");
        if (cmp) { const tag = el("span", "tag", `${"AB"[i]} `); tag.style.color = TAG_COLOR[i]; span.append(tag); }
        const shown = key === "inv" ? (raw ?? "검사 안 함") : ok(raw) ? f(raw) : key === "soldOutAtSec" ? "매진 안 됨" : "–";
        span.append(document.createTextNode(shown));
        if (unit && ok(raw)) span.append(el("span", "u", ` ${unit}`));
        if (key === "inv" && raw === null) { span.style.fontSize = "18px"; span.style.color = "#929CAF"; }
        else span.style.color = i === win ? "#3DDC97" : key === "inv" && v.invRatio === 1 ? "#3DDC97" : key === "inv" && v.invRatio < 1 ? "#FF8A8A" : "#EEF1F6";
        row.append(span);
      });
      card.append(row);
      const subText = key === "inv" ? (vals.every((v) => v.inv === null) ? "외부 검사기 결과 없음" : vals.map((v, i) => `${cmp ? `${"AB"[i]} ` : ""}${v.invRatio === 1 ? "전부 통과" : v.inv ? "실패 있음" : "검사 안 함"}`).join(" · ")) : sub;
      card.append(el("span", "s", subText));
      box.append(card);
    }
  }

  // ───────── 그래프 4개 ─────────
  const CHARTS = [
    { key: "rps", title: "처리량", unit: "rps · 예약 서버", get: (r) => r.signals?.rps, fmt: (v) => `${num(v)} rps` },
    { key: "p95", title: "응답 시간", unit: "ms", get: (r) => r.signals?.p95, sub: (r) => r.signals?.p99, subName: "p99", fmt: (v) => `${dec(v)}ms`, thr: (t) => [[t.p95SloMs, `SLO ${num(t.p95SloMs)}ms`, "#FF8A8A"]] },
    { key: "err", title: "에러율", unit: "% · 연하게 = 409 충돌", get: (r) => r.signals?.errPct, sub: (r) => conflictPct(r), subName: "충돌", fmt: (v) => `${dec(v)}%`, thr: (t) => [[t.errWarnPct, `경고 ${num(t.errWarnPct)}%`, "#F5A524"], [t.errBadPct, `나쁨 ${num(t.errBadPct)}%`, "#FF8A8A"]], fixedMax: 100 },
    { key: "pool", title: "DB 커넥션 풀 사용률", unit: "% · 막대 = 풀 대기", get: (r) => r.signals?.poolPct, bars: (r) => r.signals?.poolPending, fmt: (v) => `${num(v)}%`, thr: (t) => [[t.poolBadPct, `포화 ${num(t.poolBadPct)}%`, "#FF8A8A"]], fixedMax: 100 },
  ];
  function conflictPct(row) {
    const rps = row.signals?.rps, c = row.server?.errorClasses?.conflict;
    return ok(rps) && rps > 0 && ok(c) ? Math.min(100, (c / rps) * 100) : null;
  }
  function niceTicks(max) {
    if (!(max > 0)) return [0, 1];
    const raw = max / 4, mag = 10 ** Math.floor(Math.log10(raw)), step = [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= raw);
    const top = Math.ceil(max / step) * step, ticks = [];
    for (let v = 0; v <= top + 1e-9; v += step) ticks.push(Number(v.toFixed(6)));
    return ticks;
  }
  const SVG = "http://www.w3.org/2000/svg";
  const s = (tag, attrs = {}, textValue) => { const n = document.createElementNS(SVG, tag); for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v); if (textValue !== undefined) n.textContent = textValue; return n; };
  let lastCharts = null;
  function phaseSegments(rows) {
    const segs = [];
    for (const r of rows) { const p = r.phase; if (!segs.length || segs.at(-1).phase !== p) segs.push({ phase: p, from: r.t, to: r.t }); else segs.at(-1).to = r.t; }
    segs.forEach((g, i) => { if (segs[i + 1]) g.to = segs[i + 1].from; });
    return segs;
  }
  function renderCharts(entries, cmp) {
    lastCharts = [entries, cmp];
    const box = $("r-charts"); box.replaceChildren();
    for (const def of CHARTS) {
      const card = el("div", "r-chart"); box.append(card);
      const head = el("div", "r-chart-head"); head.append(el("span", "r-chart-title", def.title), el("span", "muted", def.unit), el("span", "grow"));
      const legend = (color, label, opacity = 1, bar = false) => { const l = el("span", "r-legend"), i = el("i"); i.style.background = color; i.style.opacity = opacity; if (bar) { i.style.height = "8px"; i.style.width = "8px"; } l.append(i, document.createTextNode(label)); head.append(l); };
      entries.forEach((e, i) => {
        const color = TAG_COLOR[cmp ? i : 0];
        if (cmp) legend(color, `${"AB"[i]} ${shortName(e.run)}`);
        else { legend(color, def.key === "p95" ? "p95" : def.key === "err" ? "전체" : def.key === "pool" ? "사용률" : "rps"); if (def.sub) legend(color, def.subName, 0.4); if (def.bars) legend("#FF8A8A", "풀 대기", 0.6, true); }
      });
      card.append(head);
      if (entries.some((e) => !e.series?.length)) { card.append(el("p", "r-nodata", entries.every((e) => e.legacy) ? "구버전 기록 · 시계열 없음" : "시계열이 없습니다")); if (entries.every((e) => !e.series?.length)) continue; }
      const svg = s("svg", { role: "img", "aria-label": `${def.title} 그래프` }); card.append(svg);
      requestAnimationFrame(() => drawChart(svg, card, def, entries, cmp));
    }
  }
  function drawChart(svg, card, def, entries, cmp) {
    const W = svg.clientWidth || 600, H = svg.clientHeight || 330, L = 48, R = 12, T = 22, B = 30, pw = W - L - R, ph = H - T - B;
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    const th = entries[0].run.thresholds || entries[0].run.config?.thresholds || {};
    const all = entries.map((e) => e.series || []);
    const maxT = Math.max(Number(entries[0].run.config?.saleDurationSec) || 0, ...all.flat().map((r) => r.t));
    const values = all.flat().flatMap((r) => [def.get(r), def.sub && !cmp ? def.sub(r) : null]).filter(ok).map(Number);
    const thrVals = def.thr ? def.thr(th).map(([v]) => Number(v)).filter(ok) : [];
    const maxV = def.fixedMax ? Math.min(def.fixedMax, Math.max(10, ...values, ...thrVals) * 1.05) : Math.max(1, ...values, ...thrVals) * 1.08;
    const ticks = niceTicks(maxV), top = ticks.at(-1);
    const x = (t) => L + (t / Math.max(1, maxT)) * pw, y = (v) => T + ph - (Math.min(top, Math.max(0, v)) / top) * ph;
    // 단계 음영 (A 기준) + B 단계 경계 눈금
    for (const g of phaseSegments(all[0])) {
      const [fill, tc, name] = BAND[g.phase] || ["transparent", "#7D879A", g.phase || ""];
      const x0 = x(g.from), w = Math.max(0, x(g.to) - x0);
      svg.append(s("rect", { x: x0, y: T, width: w, height: ph, fill }));
      if (w > 46) svg.append(s("text", { x: x0 + 4, y: T + 14, fill: tc, class: "band" }, name));
    }
    if (cmp && all[1]?.length) for (const g of phaseSegments(all[1]).slice(1)) svg.append(s("line", { x1: x(g.from), x2: x(g.from), y1: T + ph + 2, y2: T + ph + 9, stroke: TAG_COLOR[1], "stroke-width": 2 }));
    // 축
    for (const v of ticks) {
      svg.append(s("line", { x1: L, x2: L + pw, y1: y(v), y2: y(v), stroke: "#2C3648" }));
      svg.append(s("text", { x: L - 6, y: y(v) + 4, "text-anchor": "end" }, num(v)));
    }
    const xStep = maxT >= 900 ? 300 : maxT >= 300 ? 60 : 30; // 14.2: 판매 20분이면 5분 눈금, 짧은 실행은 더 촘촘하게
    for (let t = 0; t <= maxT; t += xStep) svg.append(s("text", { x: Math.min(L + pw - 18, x(t)), y: H - 8, "text-anchor": t === 0 ? "start" : "middle" }, mmss(t)));
    if (def.thr) for (const [v, label, color] of def.thr(th)) if (ok(v)) {
      svg.append(s("line", { x1: L, x2: L + pw, y1: y(v), y2: y(v), stroke: color, "stroke-dasharray": "5 4" }));
      svg.append(s("text", { x: L + pw - 2, y: y(v) - 4, "text-anchor": "end", fill: color }, label));
    }
    // 막대 (풀 대기, 단일 보기)
    if (def.bars && !cmp) {
      const rows = all[0], bmax = Math.max(1, ...rows.map(def.bars).filter(ok)), bw = Math.max(1, pw / Math.max(1, rows.length) - 1);
      for (const r of rows) { const v = def.bars(r); if (ok(v) && v > 0) { const h = (v / bmax) * ph * 0.3; svg.append(s("rect", { x: x(r.t) - bw / 2, y: T + ph - h, width: bw, height: h, fill: "#FF8A8A", opacity: 0.5 })); } }
    }
    // 선
    const path = (rows, get) => { let d = "", pen = false; for (const r of rows) { const v = get(r); if (!ok(v)) { pen = false; continue; } d += `${pen ? "L" : "M"}${x(r.t).toFixed(1)},${y(Number(v)).toFixed(1)}`; pen = true; } return d; };
    const peaks = [];
    entries.forEach((e, i) => {
      const rows = all[i], color = TAG_COLOR[cmp ? i : 0];
      if (def.sub && !cmp) svg.append(s("path", { d: path(rows, def.sub), fill: "none", stroke: color, "stroke-width": 1.5, opacity: 0.4 }));
      svg.append(s("path", { d: path(rows, def.get), fill: "none", stroke: color, "stroke-width": 2.4, "stroke-linejoin": "round" }));
      let best = null; for (const r of rows) { const v = def.get(r); if (ok(v) && (!best || Number(v) > Number(def.get(best)))) best = r; }
      if (best) peaks.push({ x: x(best.t), y: y(Number(def.get(best))), v: def.get(best), color });
    });
    // 최고점 원 + 값 (겹치면 비킴)
    peaks.forEach((p, i) => {
      let ly = Math.max(T + 36, p.y - 10); // 단계 이름 줄(T+14)과 겹치지 않게
      if (i === 1 && Math.abs(ly - peaks[0].ly) < 18 && Math.abs(p.x - peaks[0].x) < 120) ly = peaks[0].ly + 18;
      p.ly = ly;
      svg.append(s("circle", { cx: p.x, cy: p.y, r: 4.5, fill: "#0C1016", stroke: p.color, "stroke-width": 2 }));
      const anchor = p.x > L + pw - 90 ? "end" : "start";
      svg.append(s("text", { x: p.x + (anchor === "end" ? -8 : 8), y: ly, fill: p.color, class: "peak", "text-anchor": anchor }, def.fmt(p.v)));
    });
    // 마우스 오버 안내선 + 클릭 상세
    const guide = s("line", { y1: T, y2: T + ph, stroke: "#C3C9D4", "stroke-width": 1, opacity: 0, "pointer-events": "none" }); svg.append(guide);
    const hit = s("rect", { x: L, y: T, width: pw, height: ph, fill: "transparent" }); svg.append(hit);
    let tip = card.querySelector(".r-tip"); if (!tip) { tip = el("div", "r-tip"); tip.hidden = true; card.append(tip); }
    const nearest = (rows, t) => rows.reduce((b, r) => (!b || Math.abs(r.t - t) < Math.abs(b.t - t) ? r : b), null);
    const at = (evt) => { const rect = svg.getBoundingClientRect(), px = ((evt.clientX - rect.left) / rect.width) * W; return Math.max(0, Math.min(maxT, ((px - L) / pw) * maxT)); };
    hit.addEventListener("mousemove", (evt) => {
      const t = at(evt), rows = all.map((r) => nearest(r, t)), t0 = rows[0]?.t ?? t;
      guide.setAttribute("x1", x(t0)); guide.setAttribute("x2", x(t0)); guide.setAttribute("opacity", 0.6);
      tip.replaceChildren(el("span", "muted", `${mmss(t0)} · ${BAND[rows[0]?.phase]?.[2] || rows[0]?.phase || ""}  `));
      rows.forEach((r, i) => { if (!r) return; const b = el("b", "", ` ${cmp ? "AB"[i] + " " : ""}${ok(def.get(r)) ? def.fmt(def.get(r)) : "–"}`); b.style.color = TAG_COLOR[cmp ? i : 0]; tip.append(b); if (def.sub && !cmp && ok(def.sub(r))) tip.append(el("span", "muted", ` · ${def.subName} ${def.fmt(def.sub(r))}`)); });
      const rect = card.getBoundingClientRect(), scale = rect.width / card.offsetWidth;
      tip.hidden = false; tip.style.left = `${Math.min(card.offsetWidth - 260, (evt.clientX - rect.left) / scale + 12)}px`; tip.style.top = `${(evt.clientY - rect.top) / scale - 36}px`;
    });
    hit.addEventListener("mouseleave", () => { guide.setAttribute("opacity", 0); tip.hidden = true; });
    hit.addEventListener("click", (evt) => openChartDetail(def, entries, cmp, at(evt), all, svg));
  }
  const ENDPOINT_NAMES = { seats: "좌석 조회", holds: "선점", release: "선점 취소", checkout: "주문", confirm: "카드 승인", deposit: "입금 대기", depositPay: "입금", cancel: "예매 취소", reservation: "예약 조회" };
  function openChartDetail(def, entries, cmp, t, all, opener) {
    let source = "server";
    window.openModal(`${def.title} · ${mmss(t)}`, "그 시각의 엔드포인트별 값 (다운샘플 구간)", (body, filters) => {
      const draw = () => {
        body.replaceChildren();
        if (def.key === "p95") {
          filters.hidden = false; filters.replaceChildren();
          for (const [k, n] of [["server", "서버 측"], ["client", "클라이언트 측"]]) { const b = el("button", "", n); b.type = "button"; b.setAttribute("aria-pressed", String(source === k)); b.onclick = () => { source = k; draw(); }; filters.append(b); }
        }
        entries.forEach((e, i) => {
          const rows = all[i]; if (!rows?.length) return;
          const r = rows.reduce((b, x) => (Math.abs(x.t - t) < Math.abs(b.t - t) ? x : b), rows[0]);
          const title = el("p", "r-box-title small", `${cmp ? `${"AB"[i]} ` : ""}${runTitle(e.run)} · ${mmss(r.t)} · ${BAND[r.phase]?.[2] || r.phase || ""}`); title.style.color = TAG_COLOR[cmp ? i : 0]; title.style.margin = "4px 0";
          body.append(title);
          // 시뮬레이터(클라이언트) 엔드포인트 이름은 서버와 다른 것이 있다
          const CLIENT_KEY = { depositPay: "deposit.pay", cancel: "reservation.cancel" };
          const ep = r.server?.endpoints || {}, clientRaw = r.client?.p95 || {}, client = Object.fromEntries(Object.keys(ENDPOINT_NAMES).map((k) => [k, clientRaw[k] ?? clientRaw[CLIENT_KEY[k]]]));
          const keys = Object.keys(ENDPOINT_NAMES).filter((k) => ep[k] || client[k]);
          let head, data;
          if (def.key === "p95" && source === "client") { head = ["엔드포인트", "클라이언트 p95 ms"]; data = keys.map((k) => [ENDPOINT_NAMES[k], dec(client[k])]); }
          else if (def.key === "err") { head = ["엔드포인트", "rps", "오류 (초당)"]; data = keys.map((k) => [ENDPOINT_NAMES[k], dec(ep[k]?.rps), Object.entries(ep[k]?.err || {}).filter(([, v]) => v).map(([c, v]) => `${c} ${dec(v)}`).join(" · ") || "–"]); }
          else if (def.key === "pool") { head = ["항목", "값"]; data = [["풀 사용률", `${num(r.signals?.poolPct)}%`], ["풀 대기", num(r.signals?.poolPending)], ["커넥션 획득 p95", `${dec(r.server?.pool?.acquireP95)} ms`], ["락 대기", `${num(r.server?.db?.lockWaits)} · 최대 ${num(r.server?.db?.lockWaitMaxMs)} ms`], ["스레드 사용률", `${dec(r.signals?.threadsBusyPct)}%`]]; }
          else { head = ["엔드포인트", "rps", "서버 p95 ms", "p99 ms"]; data = keys.map((k) => [ENDPOINT_NAMES[k], dec(ep[k]?.rps), dec(ep[k]?.p95), dec(ep[k]?.p99)]); }
          const tbl = el("table", "dtable"), tr = el("tr"); head.forEach((h) => tr.append(el("th", "", h))); tbl.append(tr);
          for (const d of data) { const row = el("tr"); d.forEach((v) => row.append(el("td", "", v))); tbl.append(row); }
          body.append(tbl);
        });
      };
      draw();
    }, opener);
  }

  // ───────── 오른쪽: 관리 · 표 · 불변식 · 조건 ─────────
  function renderManage(entry, index) {
    const box = $("r-manage"); box.replaceChildren();
    const r = entry.run, id = r.runId || selected.at(-1);
    const name = el("span", "name"), b = el("b", "", `${mode === "compare" ? `${"AB"[index]} ` : ""}${runTitle(r)}`);
    if (mode === "compare") b.style.color = TAG_COLOR[index];
    name.append(b, el("small", "", r.notes ? r.notes : `${id} · ${STATUS[r.status] || r.status || ""} · ${fmtTime(r.startedAt)}`));
    const edit = el("button", "", "이름·메모"), pin = el("button", "", r.pinned ? "📌 고정됨" : "📌 고정"), del = el("button", "", "삭제"), dl = el("button", "", "요약 JSON");
    for (const x of [edit, pin, del, dl]) x.type = "button";
    pin.setAttribute("aria-pressed", String(!!r.pinned));
    pin.title = "고정한 실행은 자동 삭제(보관 50개)에서 빠집니다";
    const legacy = entry.legacy; edit.disabled = pin.disabled = del.disabled = legacy || r.status === "RUNNING";
    edit.onclick = () => window.openModal("이름·메모 수정", id, (body) => {
      const l = el("label", "form-row"), li = el("input"); li.value = r.label || ""; li.maxLength = 200; l.append("실행 이름", li);
      const n = el("label", "form-row"), ni = el("textarea"); ni.value = r.notes || ""; ni.maxLength = 4000; n.append("메모", ni);
      const actions = el("div", "modal-actions"), save = el("button", "primary", "저장"), cancel = el("button", "", "취소"); save.type = cancel.type = "button";
      cancel.onclick = () => window.closeModal();
      save.onclick = async () => { try { await api(`/runs/${id}`, "PATCH", { label: li.value.trim(), notes: ni.value }); refresh(id); window.closeModal(); } catch (e) { notice(e.message, true); } };
      actions.append(cancel, save); body.append(l, n, actions); li.focus();
    }, edit);
    pin.onclick = async () => { try { await api(`/runs/${id}`, "PATCH", { pinned: !r.pinned }); refresh(id); } catch (e) { notice(e.message, true); } };
    del.onclick = () => window.openModal("실행 기록 삭제", id, (body) => {
      body.append(el("p", "", `"${runTitle(r)}" 기록(run.json·요약·시계열·사건·스냅샷)을 지웁니다. 되돌릴 수 없습니다.`));
      if (r.pinned) body.append(el("p", "red-l", "고정된 실행입니다."));
      const actions = el("div", "modal-actions"), yes = el("button", "danger", "삭제"), no = el("button", "", "취소"); yes.type = no.type = "button";
      no.onclick = () => window.closeModal();
      yes.onclick = async () => { try { await api(`/runs/${id}`, "DELETE"); cache.delete(id); selected = selected.filter((x) => x !== id); window.closeModal(); await show(null); } catch (e) { notice(e.message, true); } };
      actions.append(no, yes); body.append(actions); no.focus();
    }, del);
    dl.onclick = async () => {
      try {
        const summary = await api(`/runs/${id}/summary`);
        const url = URL.createObjectURL(new Blob([`${JSON.stringify(summary, null, 2)}\n`], { type: "application/json" }));
        const a = el("a"); a.href = url; a.download = `${id}-summary.json`; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch (e) { notice(`요약을 받지 못했습니다: ${e.message}`, true); }
    };
    box.append(name, edit, pin, dl, del);
  }
  async function refresh(id) { cache.delete(id); await loadList(); render(); }

  const METRIC_LABELS = [
    ["p95Max", "최대 p95", "ms", "low"], ["p99Max", "최대 p99", "ms", "low"], ["p95Rush", "폭주 구간 평균 p95", "ms", "low"], ["sloBreachSec", "SLO 초과 시간", "s", "low"],
    ["errPctRush", "폭주 구간 에러율", "%", "low"], ["failPct", "실패율", "%", "low"], ["conflict", "409 충돌 누적", "건", "low"], ["poolPctMax", "최대 풀 사용률", "%", "low"],
    ["poolSatSec", "풀 포화 시간", "s", "low"], ["lockWaitsMax", "최대 락 대기", "개", "low"], ["rpsMax", "최대 처리량", "rps", null], ["soldOutAtSec", "매진 시각", "s", null],
    ["seatsSold", "판매", "석", null], ["invariants", "불변식 통과 수", "개", "high"],
  ];
  function metricValue(entry, key) {
    const r = entry.run;
    if (key === "invariants") return invPassed(entry.invariants);
    if (key === "seatsSold") return r.seatsSold;
    if (key === "conflict") return r.errorClasses?.conflict;
    return r.signals?.[key];
  }
  const showValue = (key, unit, v) => (!ok(v) ? "–" : key === "soldOutAtSec" ? mmss(v) : `${["p95Max", "p99Max", "p95Rush", "errPctRush", "failPct"].includes(key) ? dec(v) : num(v)}${unit && unit !== "s" ? ` ${unit}` : unit === "s" ? "초" : ""}`);
  function renderTable(entries, cmp) {
    const box = $("r-table"); box.replaceChildren();
    $("r-table-title").textContent = cmp ? "전략 비교" : "요약";
    $("r-table-sub").textContent = cmp ? "초록 = 더 나은 쪽 · 차이 = B 기준 A 대비" : selected[0];
    box.style.gridTemplateColumns = cmp ? "minmax(0,1fr) 104px 104px 70px" : "minmax(0,1fr) 120px";
    const hd = (t, cls = "h", color) => { const d = el("div", cls, t); if (color) d.style.color = color; box.append(d); };
    hd("지표");
    entries.forEach((e, i) => hd(cmp ? `${"AB"[i]} ${shortName(e.run)}` : "값", "h n", cmp ? TAG_COLOR[i] : null));
    if (cmp) hd("차이", "h n");
    const rows = cmp && compare?.metrics ? compare.metrics : METRIC_LABELS.map(([key, label, unit, better]) => ({ key, label, unit, better, a: metricValue(entries[0], key) }));
    for (const m of rows) {
      const [, label, unit] = METRIC_LABELS.find(([k]) => k === m.key) || [m.key, m.label, m.unit];
      hd(label, "");
      const vals = cmp ? [m.a, m.b] : [m.a];
      vals.forEach((v, i) => hd(showValue(m.key, unit, v), `n${cmp && m.winner === "ab"[i] ? " win" : ""}`));
      if (cmp) {
        const d = m.diffPct, worse = ok(d) && m.better && d !== 0 && ((m.better === "low" && d > 0) || (m.better === "high" && d < 0));
        hd(ok(d) ? `${d > 0 ? "+" : ""}${dec(d)}%` : "–", `n${worse ? " worse" : ok(d) && m.better && d !== 0 ? " better" : ""}`);
      }
    }
    $("r-verdict").textContent = cmp ? (compare?.verdict ? `판정: ${compare.verdict}` : "비교 결과를 읽지 못했습니다.") : singleVerdict(entries[0]);
  }
  function singleVerdict(entry) {
    const s = entry.run.signals || {}, th = entry.run.thresholds || {};
    if (entry.legacy) return "구버전 기록 · 요약만 표시합니다.";
    return `최대 p95 ${dec(s.p95Max)}ms (SLO ${num(th.p95SloMs)}ms ${ok(s.p95Max) && s.p95Max >= th.p95SloMs ? "초과" : "이내"}), 풀 포화 ${num(s.poolSatSec)}초. 전략 비교 보기로 다른 실행과 나란히 볼 수 있습니다.`;
  }
  function renderInvariants(entries, cmp) {
    const box = $("r-inv"); box.replaceChildren();
    const summary = $("r-inv-summary");
    const has = entries.map((e) => e.invariants?.results || null);
    summary.textContent = has.map((r, i) => `${cmp ? `${"AB"[i]} ` : ""}${r ? `${r.filter((x) => x.pass).length}/${r.length} 통과` : "검사 안 함"}`).join(" · ");
    summary.style.color = has.every((r) => r && r.every((x) => x.pass)) ? "#3DDC97" : has.some((r) => r && r.some((x) => !x.pass)) ? "#FF8A8A" : "#929CAF";
    const results = has.find(Boolean);
    if (!results) { box.style.display = "block"; box.append(el("p", "r-inv-empty", "검사 결과 없음 · 외부 검사기로 snapshot.json을 검사하세요")); return; }
    box.style.display = "";
    // 비교 시 두 결과를 ID로 합친다. 실패를 위로.
    const ids = [...new Set(has.flatMap((r) => (r || []).map((x) => x.id)))];
    const rows = ids.map((id) => ({ id, items: has.map((r) => r?.find((x) => x.id === id) || null) }));
    rows.sort((a, b) => Number(a.items.every((x) => !x || x.pass)) - Number(b.items.every((x) => !x || x.pass)));
    for (const row of rows) {
      const fail = row.items.some((x) => x && !x.pass);
      const b = el("button"); b.type = "button";
      const m = el("span", "m", fail ? "✗" : "✓"); m.style.color = fail ? "#FF5D5D" : "#3DDC97";
      b.append(m, el("span", "id", row.id), el("span", "x", row.items.map((x, i) => (x && cmp ? `${"AB"[i]}${x.pass ? "✓" : "✗"}` : "")).join(" ") || (row.items[0]?.detail || "")));
      b.onclick = () => window.openModal(`불변식 ${row.id}`, fail ? "실패" : "통과", (body) => {
        row.items.forEach((x, i) => {
          if (!x) return;
          const t = el("p", "r-box-title small", `${cmp ? `${"AB"[i]} · ` : ""}${x.pass ? "통과" : "실패"}`); t.style.color = x.pass ? "#3DDC97" : "#FF5D5D"; body.append(t);
          body.append(el("p", "", x.detail || "(설명 없음)"));
          const pre = el("pre", "dcard"); pre.style.whiteSpace = "pre-wrap"; pre.style.maxHeight = "260px"; pre.style.overflow = "hidden"; pre.style.font = "13px var(--mono)";
          pre.textContent = x.evidence == null ? "evidence 없음" : JSON.stringify(x.evidence, null, 2).slice(0, 3000); body.append(pre);
        });
      }, b);
      box.append(b);
    }
  }
  function conditions(r) {
    const c = r.config || {}, sv = r.server || {};
    return [
      ["사용자", num(c.users)], ["좌석", `${c.rows} × ${c.cols}`], ["정원 · 입장", `${num(c.maxActive)} · ${num(c.admitPerSec)}/s`],
      ["세션", ok(c.admissionTtlSec) ? `${Math.round(c.admissionTtlSec / 60)}분` : "–"], ["배속", `${c.timeScale ?? r.timeScale}×`], ["시드", String(c.seed ?? "–")],
      ["도착 분포", (c.arrival || []).map((a) => a.percent).join("/") || "–"], ["결제사 지연", `${num(c.confirmMinMs)}~${num(c.confirmMaxMs)}ms`],
      ["커넥션 풀", ok(sv.poolMax) ? num(sv.poolMax) : "–"], ["동시성 전략", STRATEGIES[sv.strategy] || sv.strategy || c.strategy || "–"],
    ];
  }
  function renderConditions(entries, cmp) {
    const box = $("r-conds"); box.replaceChildren();
    const lists = entries.map((e) => conditions(e.run));
    let diffs = 0;
    lists[0].forEach(([k, v], i) => {
      const other = cmp ? lists[1][i][1] : v, diff = cmp && other !== v;
      if (diff) diffs++;
      const d = el("div", diff ? "diff" : ""); d.append(el("span", "", k), el("span", "", diff ? `${v} / ${other}` : v)); box.append(d);
    });
    $("r-cond-sub").textContent = cmp ? (diffs ? `다른 건 ${diffs}개` : "조건 같음") : "이 실행의 설정";
  }

  // ───────── 모든 실행 서랍 ─────────
  $("r-all").onclick = () => {
    let query = "", page = 0;
    window.openModal("모든 실행", "이름·전략·상태 검색 · 고르면 바로 표시", (body) => {
      const search = el("input", "drawer-search"); search.placeholder = "검색 (이름, 전략, 상태, run ID)"; search.setAttribute("aria-label", "실행 검색");
      const list = el("div"), nav = el("div", "cfg-actions");
      const draw = () => {
        const q = query.trim().toLowerCase();
        const found = runs.filter((r) => !q || [runTitle(r), r.runId, STRATEGIES[r.strategy] || r.strategy, STATUS[r.status] || r.status].join(" ").toLowerCase().includes(q));
        const pages = Math.max(1, Math.ceil(found.length / 12)); page = Math.min(page, pages - 1);
        list.replaceChildren();
        const head = el("div", "drawer-row"); head.style.color = "#929CAF"; head.append(el("span"), el("span", "", "이름"), el("span", "", "전략"), el("span", "", "시각"), el("span", "", "상태"), el("span", "", "불변식")); list.append(head);
        for (const r of found.slice(page * 12, page * 12 + 12)) {
          const i = selected.indexOf(r.runId);
          const row = el("button", `drawer-row${i >= 0 ? " sel" : ""}`); row.type = "button";
          const dot = el("i"); dot.style.cssText = `width:12px;height:12px;border-radius:3px;background:${i >= 0 ? TAG_COLOR[mode === "compare" ? i : 0] : "#3D4A64"}`;
          row.append(dot, el("b", "", `${r.pinned ? "📌 " : ""}${runTitle(r)}`), el("span", "", STRATEGIES[r.strategy] || r.strategy || "–"), el("span", "mono", `${fmtTime(r.startedAt)} · ${r.timeScale ?? "?"}×`), el("span", "", STATUS[r.status] || r.status || (Number(r.schemaVersion) < 5 ? "구버전" : "–")), el("span", "mono", r.hasInvariants ? `${r.invariantsPassed ?? "?"} 통과` : "–"));
          row.onclick = () => { pick(r.runId); draw(); };
          list.append(row);
        }
        nav.replaceChildren();
        const prev = el("button", "", "‹ 이전"), next = el("button", "", "다음 ›"); prev.type = next.type = "button";
        prev.disabled = page === 0; next.disabled = page >= pages - 1; prev.onclick = () => { page--; draw(); }; next.onclick = () => { page++; draw(); };
        nav.append(prev, el("span", "muted", `${page + 1} / ${pages} 쪽 · ${found.length}건`), next);
      };
      search.addEventListener("input", () => { query = search.value; page = 0; draw(); });
      body.append(search, list, nav); draw(); search.focus();
    }, $("r-all"));
  };

  // 창 크기가 바뀌면 그래프 4개를 영역 크기에 맞춰 다시 그린다
  function relayout() { if (lastCharts && !$("results-view").hidden) renderCharts(...lastCharts); }
  window.resultsView = { show, relayout, state: () => ({ mode, selected: [...selected], cached: [...cache.keys()], token: renderToken }) };
})();
