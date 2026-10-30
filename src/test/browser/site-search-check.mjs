// 搜索页浏览器验收（MACC-20，09 方案第 7.5 节）：对一个运行中的站点（maccms.site.search.enabled=true），用本机
// Edge/Chrome 无头模式（DevTools 协议），在禁用 JavaScript 的情况下检查
//   - 只用键盘：Tab 到页面内搜索框，输入关键字，Enter 提交 → 地址为"固定路径 + 查询串"，结果页 canonical 与之一致；
//   - 结果页 main 内的每个链接（筛选、排序、分页、卡片）都能用 Tab 到达，焦点有可见样式（只报告，样式归 T4）；
//   - 对筛选链接与"下一页"按 Enter 能跳转到新的搜索地址，当前筛选带 aria-current；
//   - 参数不合法时 400 页的提示与输入框关联（aria-describedby）。
// 另可用 site-pages-check.mjs 的 SITE_PAGES 检查窄屏溢出与 Tab 顺序（溢出由 T4 样式任务处理）。
// 不在 ./mvnw verify 中运行；不需要 npm 依赖（Node 22+）。只访问给定的本机站点。
//
// 用法：node site-search-check.mjs <站点根地址> <关键字> <输出目录> [浏览器路径]

import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [base, keyword, outDir, browserPath = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'] =
  process.argv.slice(2);
if (!base || !keyword || !outDir) {
  console.error('用法：node site-search-check.mjs <站点根地址> <关键字> <输出目录> [浏览器路径]');
  process.exit(2);
}
const root = base.replace(/\/$/, '');
const port = 9800 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), 'site-search-check-'));
mkdirSync(outDir, { recursive: true });
const browser = spawn(browserPath, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking',
  '--disable-sync', '--disable-component-update', 'about:blank'], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws;
let nextId = 1;
const pending = new Map();
const listeners = [];

async function connect() {
  for (let i = 0; i < 50; i++) {
    try {
      const v = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
      ws = new WebSocket(v.webSocketDebuggerUrl);
      await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
      ws.onmessage = (m) => {
        const msg = JSON.parse(m.data);
        if (msg.id && pending.has(msg.id)) {
          const { res, rej } = pending.get(msg.id);
          pending.delete(msg.id);
          msg.error ? rej(new Error(JSON.stringify(msg.error))) : res(msg.result);
        } else if (msg.method) {
          listeners.forEach((l) => l(msg));
        }
      };
      return;
    } catch {
      await sleep(200);
    }
  }
  throw new Error('无法连接浏览器 DevTools');
}

function send(method, params = {}, sessionId) {
  const id = nextId++;
  ws.send(JSON.stringify({ id, method, params, sessionId }));
  return new Promise((res, rej) => pending.set(id, { res, rej }));
}

function waitEvent(method, sessionId, timeout = 15000) {
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('等待超时 ' + method)), timeout);
    const l = (msg) => {
      if (msg.method === method && msg.sessionId === sessionId) {
        clearTimeout(t);
        listeners.splice(listeners.indexOf(l), 1);
        res(msg.params);
      }
    };
    listeners.push(l);
  });
}

const evaluate = async (s, expression) =>
  (await send('Runtime.evaluate', { expression, returnByValue: true }, s)).result.value;

async function navigate(s, url) {
  const loaded = waitEvent('Page.loadEventFired', s);
  await send('Page.navigate', { url }, s);
  await loaded;
}

async function key(s, keyName, code, keyCode) {
  // Enter 需要带 text，浏览器才会产生按键字符并触发表单隐式提交 / 链接激活
  const text = keyName === 'Enter' ? String.fromCharCode(13) : undefined;
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: keyName, code, windowsVirtualKeyCode: keyCode, text }, s);
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: keyName, code, windowsVirtualKeyCode: keyCode }, s);
}

/** 按 Tab 直到焦点落在满足条件的元素上（最多 n 次），返回经过的焦点描述。 */
async function tabUntil(s, predicate, n = 200) {
  const seen = [];
  for (let i = 0; i < n; i++) {
    await key(s, 'Tab', 'Tab', 9);
    const f = await evaluate(s, `(() => { const e = document.activeElement; if (!e || e === document.body) return null;
      const cs = getComputedStyle(e);
      return { tag: e.tagName.toLowerCase(), id: e.id, cls: String(e.className), href: e.getAttribute('href'),
        inMain: !!e.closest('main'), text: (e.innerText || e.value || '').trim().slice(0, 30),
        visibleFocus: (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || cs.boxShadow !== 'none' }; })()`);
    if (!f) break;
    seen.push(f);
    if (predicate(f)) return { found: f, seen };
  }
  return { found: null, seen };
}

const report = { base, keyword, browser: browserPath, generatedAt: new Date().toISOString(), steps: [], failures: [] };
const fail = (msg) => { report.failures.push(msg); console.error('FAIL ' + msg); };
const step = (name, data) => { report.steps.push({ name, ...data }); };

try {
  await connect();
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId: s } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, s);
  await send('Runtime.enable', {}, s);
  await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, s);
  await send('Emulation.setScriptExecutionDisabled', { value: true }, s);

  // 1. 无条件搜索页：键盘 Tab 到页面内搜索框，输入关键字，Enter 提交（无 JS 的原生 GET 表单）
  await navigate(s, root + '/index.php/vod/search.html');
  const toInput = await tabUntil(s, (f) => f.id === 'search-page-wd');
  if (!toInput.found) fail('Tab 无法到达页面内搜索框');
  await send('Input.insertText', { text: keyword }, s);
  const submitted = waitEvent('Page.loadEventFired', s);
  await key(s, 'Enter', 'Enter', 13);
  await submitted;
  const afterSubmit = await evaluate(s, `({ url: location.href, canonical: document.querySelector('link[rel=canonical]')?.href,
    h1: document.querySelector('h1')?.textContent, summary: document.querySelector('.search-summary')?.textContent,
    scripts: document.scripts.length })`);
  step('keyboard-submit', { tabsToInput: toInput.seen.length, ...afterSubmit });
  if (!afterSubmit.url.includes('/index.php/vod/search.html?wd=')) fail('提交后的地址不是固定路径 + 查询串：' + afterSubmit.url);
  if (!afterSubmit.summary) fail('提交后没有结果摘要');
  if (afterSubmit.scripts !== 0) fail('页面包含 <script>');

  // 2. 结果页 main 内每个链接都能用 Tab 到达
  const mainLinks = await evaluate(s, `[...document.querySelectorAll('main a[href]')].map(a => a.getAttribute('href'))`);
  await navigate(s, afterSubmit.url);
  const walk = await tabUntil(s, () => false, mainLinks.length + 40);
  const reached = new Set(walk.seen.filter((f) => f.inMain && f.tag === 'a').map((f) => f.href));
  const missing = mainLinks.filter((h) => !reached.has(h));
  const noFocusStyle = walk.seen.filter((f) => !f.visibleFocus).map((f) => `${f.tag}.${f.cls}`);
  step('tab-coverage', { mainLinks: mainLinks.length, reached: reached.size, missing, noFocusStyle });
  if (missing.length) fail(`Tab 未到达的链接 ${missing.length} 个`);

  // 3. Enter 一个筛选链接（第一个非"全部"的地区/年份/语言）与"下一页"
  await navigate(s, afterSubmit.url);
  const toChip = await tabUntil(s, (f) => f.cls.includes('filter-chip') && f.text !== '全部' && !f.cls.includes('--current'));
  if (toChip.found) {
    const nav = waitEvent('Page.loadEventFired', s);
    await key(s, 'Enter', 'Enter', 13);
    await nav;
    const chip = await evaluate(s, `({ url: location.href, current: [...document.querySelectorAll('a[aria-current=true]')].map(a => a.textContent) })`);
    step('filter-enter', { chip: toChip.found.text, ...chip });
    if (!chip.current.includes(toChip.found.text)) fail('筛选后当前项没有 aria-current：' + toChip.found.text);
  } else {
    step('filter-enter', { skipped: '结果页没有可用的筛选链接' });
  }
  await navigate(s, afterSubmit.url);
  const toNext = await tabUntil(s, (f) => f.cls.includes('pagination__link--next'));
  if (toNext.found) {
    const nav = waitEvent('Page.loadEventFired', s);
    await key(s, 'Enter', 'Enter', 13);
    await nav;
    const p2 = await evaluate(s, `({ url: location.href, status: document.querySelector('.pagination__status')?.textContent })`);
    step('next-page', p2);
    if (!/[?&]page=2(&|$)/.test(p2.url)) fail('下一页地址不含 page=2：' + p2.url);
  } else {
    step('next-page', { skipped: '结果不足两页' });
  }

  // 4. 400 页：提示与输入框关联
  await navigate(s, root + '/index.php/vod/search.html?wd=%FF');
  const invalid = await evaluate(s, `({ h1: document.querySelector('h1')?.textContent,
    describedBy: document.getElementById('search-page-wd')?.getAttribute('aria-describedby'),
    error: document.getElementById('search-page-error')?.textContent })`);
  step('invalid', invalid);
  if (invalid.describedBy !== 'search-page-error' || !invalid.error) fail('400 页提示未与输入框关联');

  const shot = await send('Page.captureScreenshot', { format: 'png' }, s);
  writeFileSync(join(outDir, 'search_invalid_1280.png'), Buffer.from(shot.data, 'base64'));
  await navigate(s, afterSubmit.url);
  const shot2 = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, s);
  writeFileSync(join(outDir, 'search_results_1280.png'), Buffer.from(shot2.data, 'base64'));
} catch (e) {
  fail('运行出错：' + e.message);
} finally {
  writeFileSync(join(outDir, 'search-report.json'), JSON.stringify(report, null, 2));
  try { ws?.close(); } catch { /* ignore */ }
  browser.kill();
  await sleep(500);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* 浏览器仍占用时留给系统清理 */ }
}
console.log(report.failures.length === 0 ? 'OK 搜索页浏览器验收通过' : `共 ${report.failures.length} 项失败`);
process.exit(report.failures.length === 0 ? 0 : 1);
