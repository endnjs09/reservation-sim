// 화면 확인 도구 (W4·W5): Windows의 Chrome을 headless로 띄워 CDP로 조작한다.
// 사용: node scripts/ui-check.mjs <steps.json> [outDir]
//   steps.json: { "url": "...", "width": 1920, "height": 1080, "steps": [ {"wait": ms} | {"shot": "name"} | {"click": "css"} |
//                 {"key": "Escape"} | {"eval": "js", "save": "name"} | {"resize": [w, h]} | {"waitFor": "js 조건", "timeout": ms} ] }
// 결과: outDir/<name>.png, outDir/report.json (콘솔 오류, 외부 요청, eval 결과)
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync, mkdtempSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

const [specPath, outDir = "ui-check-out"] = process.argv.slice(2);
const spec = JSON.parse(readFileSync(specPath, "utf8"));
mkdirSync(outDir, { recursive: true });
const chrome = process.env.CHROME || "C:/Program Files/Google/Chrome/Application/chrome.exe";
const port = 9300 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), "ui-check-"));
const proc = spawn(chrome, ["--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--no-first-run", "--hide-scrollbars",
  `--window-size=${spec.width || 1920},${spec.height || 1080}`, "about:blank"], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let target;
for (let i = 0; i < 50 && !target; i++) {
  try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === "page"); } catch { await sleep(200); }
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
let seq = 0; const pending = new Map(); const report = { console: [], external: [], failed: [], evals: {}, shots: [] };
ws.addEventListener("message", (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); return; }
  if (msg.method === "Runtime.consoleAPICalled" && ["error", "warning"].includes(msg.params.type)) report.console.push(msg.params.args.map((a) => a.value ?? a.description).join(" "));
  if (msg.method === "Runtime.exceptionThrown") report.console.push(`exception: ${msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text}`);
  if (msg.method === "Network.requestWillBeSent") { const u = msg.params.request.url; if (!/^(https?:\/\/(localhost|127\.0\.0\.1)[:/]|data:|about:)/.test(u)) report.external.push(u); }
  if (msg.method === "Network.loadingFailed" && !msg.params.canceled) report.failed.push(msg.params.errorText);
});
const send = (method, params = {}) => new Promise((resolve) => { const id = ++seq; pending.set(id, resolve); ws.send(JSON.stringify({ id, method, params })); });
const evaluate = async (expr) => (await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value;
await send("Runtime.enable"); await send("Network.enable"); await send("Page.enable");
const size = async (w, h) => send("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile: false });
await size(spec.width || 1920, spec.height || 1080);
await send("Page.navigate", { url: spec.url });
await sleep(1500);
for (const step of spec.steps) {
  if (step.wait) await sleep(step.wait);
  if (step.resize) { await size(...step.resize); await sleep(400); }
  if (step.waitFor) { const until = Date.now() + (step.timeout || 60000); while (Date.now() < until && !(await evaluate(step.waitFor))) await sleep(500); }
  if (step.click) await evaluate(`(() => { const n = document.querySelector(${JSON.stringify(step.click)}); if (!n) return false; n.click(); return true; })()`);
  if (step.key) for (const type of ["keyDown", "keyUp"]) await send("Input.dispatchKeyEvent", { type, key: step.key, code: step.key, windowsVirtualKeyCode: step.key === "Escape" ? 27 : 0 });
  if (step.eval) report.evals[step.save || step.eval.slice(0, 40)] = await evaluate(step.eval);
  if (step.shot) {
    const shot = await send("Page.captureScreenshot", { format: "png" });
    writeFileSync(join(outDir, `${step.shot}.png`), Buffer.from(shot.result.data, "base64"));
    report.shots.push(step.shot);
  }
}
writeFileSync(join(outDir, "report.json"), JSON.stringify(report, null, 2));
ws.close(); proc.kill();
console.log(JSON.stringify({ console: report.console.length, external: report.external.length, failed: report.failed.length, shots: report.shots.length }));
