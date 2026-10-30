// 三页浏览器验收（MACC-14，03 方案 3.6）：对一个运行中的站点，用本机 Edge/Chrome 无头模式（DevTools 协议）检查
//   - 375/768/1280/1440/1920 px 宽度下无横向滚动（document.documentElement.scrollWidth <= 设备宽度）；
//   - 键盘：第一个 Tab 落在"跳到主要内容"且可见，Enter 跳到 #main；之后的 Tab 顺序与 DOM 顺序一致，焦点有可见样式；
//   - 禁用 JavaScript 后页面内容、导航、分页仍在（页面本身没有脚本）；
//   - 记录 375 px 下导航/分页/卡片链接的点击区域尺寸（只报告，不判失败：样式由 MACC-15 负责）；
//   - 输出 375 与 1280 px 截图和 report.json。
// 不在 ./mvnw verify 中运行；不需要 npm 依赖（Node 22+ 自带 WebSocket 与 fetch）。
//
// 用法：node site-pages-check.mjs <站点根地址> <输出目录> [浏览器路径]
//   例：node site-pages-check.mjs http://127.0.0.1:18281 ./browser-out "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
// 页面路径可用环境变量 SITE_PAGES 覆盖（逗号分隔），默认首页、分类第 1/2 页、详情。
// 只访问给定的本机站点，不访问外部网络；临时浏览器配置目录在结束时删除。

import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [base, outDir, browserPath = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'] = process.argv.slice(2);
if (!base || !outDir) {
  console.error('用法：node site-pages-check.mjs <站点根地址> <输出目录> [浏览器路径]');
  process.exit(2);
}
const pages = (process.env.SITE_PAGES || '/,/index.php/vod/type/id/1.html,/index.php/vod/type/id/1/page/2.html,/index.php/vod/detail/id/1.html')
  .split(',');
const widths = [375, 768, 1280, 1440, 1920];
const port = 9300 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), 'site-check-'));
mkdirSync(outDir, { recursive: true });

const browser = spawn(browserPath, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking',
  '--disable-sync', '--disable-component-update', '--hide-scrollbars', 'about:blank'], { stdio: 'ignore' });

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

async function evaluate(s, expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true }, s);
  return r.result.value;
}

async function navigate(s, url) {
  const loaded = waitEvent('Page.loadEventFired', s);
  const nav = await send('Page.navigate', { url }, s);
  if (nav.errorText) throw new Error('导航失败 ' + url + ' ' + nav.errorText);
  await loaded;
}

async function key(s, keyName, code, keyCode, shift = false) {
  const modifiers = shift ? 8 : 0;
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: keyName, code, windowsVirtualKeyCode: keyCode, modifiers }, s);
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: keyName, code, windowsVirtualKeyCode: keyCode, modifiers }, s);
}

const FOCUS_INFO = `(() => {
  const e = document.activeElement;
  if (!e || e === document.body) return null;
  const cs = getComputedStyle(e);
  const r = e.getBoundingClientRect();
  const all = [...document.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')]
    .filter(x => !x.disabled && x.tabIndex >= 0 && x.getClientRects().length > 0);
  return {
    tag: e.tagName.toLowerCase(), cls: e.className, text: (e.innerText || e.value || e.getAttribute('aria-label') || '').trim().slice(0, 40),
    domIndex: all.indexOf(e),
    visibleFocus: (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || cs.boxShadow !== 'none',
    inViewport: r.bottom > 0 && r.top < innerHeight && r.width > 0 && r.height > 0
  };
})()`;

const report = { base, browser: browserPath, generatedAt: new Date().toISOString(), pages: [], failures: [] };
const fail = (msg) => { report.failures.push(msg); console.error('FAIL ' + msg); };

try {
  await connect();
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId: s } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, s);
  await send('Runtime.enable', {}, s);

  for (const path of pages) {
    const url = base.replace(/\/$/, '') + path;
    const entry = { path, widths: {}, keyboard: [], noJs: null, targets375: [] };
    report.pages.push(entry);
    const slug = path.replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'home';

    for (const w of widths) {
      await send('Emulation.setDeviceMetricsOverride', { width: w, height: 900, deviceScaleFactor: 1, mobile: w < 768 }, s);
      await navigate(s, url);
      const m = await evaluate(s, `({ scrollWidth: document.documentElement.scrollWidth, innerWidth: innerWidth,
        h1: document.querySelectorAll('h1').length, status: document.querySelector('[data-site-page]')?.dataset.sitePage })`);
      entry.widths[w] = m;
      // 与设备宽度比较：移动模式下内容过宽时浏览器会把布局视口（innerWidth）一起撑大，不能用 innerWidth 判断
      if (m.scrollWidth > w) fail(`${path} @${w}px 横向滚动 scrollWidth=${m.scrollWidth} > ${w}`);
      if (w === 375 || w === 1280) {
        const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true }, s);
        writeFileSync(join(outDir, `${slug}_${w}.png`), Buffer.from(shot.data, 'base64'));
      }
      if (w === 375) {
        entry.targets375 = await evaluate(s, `[...document.querySelectorAll('.site-nav a, .pagination a, .video-card__link, .category-grid a, .breadcrumb a, .site-search button, .site-login')]
          .map(e => { const r = e.getBoundingClientRect(); return { sel: e.className, w: Math.round(r.width), h: Math.round(r.height) }; })
          .filter(t => t.w < 44 || t.h < 44)`);
      }
    }

    // 键盘：1280 px
    await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false }, s);
    await navigate(s, url);
    await key(s, 'Tab', 'Tab', 9);
    const first = await evaluate(s, FOCUS_INFO);
    entry.keyboard.push(first);
    if (!first || !String(first.cls).includes('skip-link')) fail(`${path} 第一个 Tab 不是跳到主要内容`);
    else if (!first.inViewport || !first.visibleFocus) fail(`${path} 跳到主要内容获得焦点时不可见或无焦点样式`);
    await key(s, 'Enter', 'Enter', 13);
    await sleep(100);
    const hash = await evaluate(s, 'location.hash');
    if (hash !== '#main') fail(`${path} 跳到主要内容后 hash=${hash}`);
    await navigate(s, url);
    let last = -1;
    for (let i = 0; i < 25; i++) {
      await key(s, 'Tab', 'Tab', 9);
      const f = await evaluate(s, FOCUS_INFO);
      if (!f) break;
      entry.keyboard.push(f);
      if (f.domIndex <= last) fail(`${path} Tab 顺序与 DOM 顺序不一致：第 ${i + 1} 次落在 ${f.tag}.${f.cls}`);
      if (!f.visibleFocus) fail(`${path} 焦点不可见：${f.tag}.${f.cls}`);
      last = f.domIndex;
    }

    // 禁用 JavaScript
    await send('Emulation.setScriptExecutionDisabled', { value: true }, s);
    await navigate(s, url);
    entry.noJs = await evaluate(s, `({ main: !!document.querySelector('main#main'), links: document.querySelectorAll('main a[href]').length,
      scripts: document.scripts.length })`).catch(() => null);
    await send('Emulation.setScriptExecutionDisabled', { value: false }, s);
    // 禁用脚本后 Runtime.evaluate 仍可执行（DevTools 求值不受页面脚本开关影响）；页面本身应没有 <script>
    if (entry.noJs && entry.noJs.scripts !== 0) fail(`${path} 页面包含 <script>`);
    if (entry.noJs && !entry.noJs.main) fail(`${path} 无 JS 时缺少主要内容`);
  }
} catch (e) {
  fail('运行出错：' + e.message);
} finally {
  writeFileSync(join(outDir, 'report.json'), JSON.stringify(report, null, 2));
  try { ws?.close(); } catch { /* ignore */ }
  browser.kill();
  await sleep(500);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* 浏览器仍占用时留给系统清理 */ }
}
console.log(report.failures.length === 0 ? 'OK 浏览器验收通过' : `共 ${report.failures.length} 项失败`);
process.exit(report.failures.length === 0 ? 0 : 1);
