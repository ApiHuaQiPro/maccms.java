/*
 * trial.js 行为测试（MACC-54 K4）。Node 内置 node:test，无外部依赖。
 * 用最小假 DOM/窗口对象注入脚本，模拟后端输出的播放器内页挂点。
 * 运行：node --test backend/src/test/js/ （在仓库根目录）
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const SRC = fs.readFileSync(
  path.join(__dirname, '..', '..', '..', 'main', 'resources', 'static', 'site', 'trial.js'),
  'utf8');

const END_MESSAGE = '试看已结束，请登录后继续观看。';

class FakeEl {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attributes = {};
    this.parentNode = null;
    this.textContent = '';
    this.hidden = false;
    this.pauseCount = 0;
  }

  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }

  hasAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) || this.hidden;
  }

  removeAttribute(name) {
    delete this.attributes[name];
    if (name === 'hidden') this.hidden = false;
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  removeChild(child) {
    this.children = this.children.filter((c) => c !== child);
    child.parentNode = null;
    return child;
  }

  pause() {
    this.pauseCount += 1;
  }

  querySelectorAll(selector) {
    const tags = selector.split(',').map((s) => s.trim().toUpperCase());
    const out = [];
    const walk = (node) => {
      for (const child of node.children) {
        if (tags.includes(child.tagName)) out.push(child);
        walk(child);
      }
    };
    walk(this);
    return out;
  }
}

class FakeDocument {
  constructor() {
    this.byId = {};
    this.listeners = {};
    this.hidden = false;
    this.readyState = 'loading'; // 阻止脚本底部 boot() 自动执行
  }

  getElementById(id) {
    return this.byId[id] || null;
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  fire(type) {
    for (const fn of this.listeners[type] || []) fn();
  }
}

class FakeWindow {
  constructor() {
    this.timers = new Map();
    this.nextId = 1;
  }

  setInterval(fn) {
    const id = this.nextId++;
    this.timers.set(id, fn);
    return id;
  }

  clearInterval(id) {
    this.timers.delete(id);
  }

  tick() {
    for (const fn of [...this.timers.values()]) fn();
  }

  get timerCount() {
    return this.timers.size;
  }
}

/** 加载脚本并返回 { macTrial, doc, win }；脚本以注入的假 window/document 运行。 */
function load() {
  const doc = new FakeDocument();
  const win = new FakeWindow();
  const factory = new Function('window', 'document', `${SRC}\n;return window.MacTrial;`);
  const macTrial = factory(win, doc);
  assert.ok(macTrial && typeof macTrial.start === 'function', '脚本应暴露 MacTrial.start');
  return { macTrial, doc, win };
}

/** 造一个播放器内页：stage（含 iframe+video+audio+object+embed）与 message。 */
function makePlayerPage(doc, minutes) {
  const stage = new FakeEl('div');
  stage.setAttribute('id', 'player-stage');
  if (minutes !== null && minutes !== undefined) {
    stage.setAttribute('data-trial-minutes', String(minutes));
  }
  const mount = new FakeEl('div');
  for (const tag of ['iframe', 'video', 'audio', 'object', 'embed']) {
    mount.appendChild(new FakeEl(tag));
  }
  stage.appendChild(mount);
  const message = new FakeEl('p');
  message.setAttribute('id', 'trial-message');
  message.hidden = true;
  doc.byId['player-stage'] = stage;
  doc.byId['trial-message'] = message;
  return { stage, mount, message };
}

/** 临时替换 Date.now，测试结束恢复。 */
function freezeTime(t, start) {
  let now = start;
  const real = Date.now;
  Date.now = () => now;
  t.after(() => {
    Date.now = real;
  });
  return {
    advance(ms) {
      now += ms;
    }
  };
}

test('data-trial-minutes=0 不计时，不注册定时器，不动页面', () => {
  const { macTrial, doc, win } = load();
  const { mount, message } = makePlayerPage(doc, 0);
  const handle = macTrial.start(doc, win);
  assert.equal(handle, null);
  assert.equal(win.timerCount, 0);
  assert.equal(mount.children.length, 5, '不得移除挂载内容');
  assert.equal(message.textContent, '');
});

test('缺少 player-stage 时不报错、不注册定时器', () => {
  const { macTrial, doc, win } = load();
  const handle = macTrial.start(doc, win);
  assert.equal(handle, null);
  assert.equal(win.timerCount, 0);
});

test('data-trial-minutes 非法值（非数字）按不计时处理', () => {
  const { macTrial, doc, win } = load();
  makePlayerPage(doc, 'abc');
  assert.equal(macTrial.start(doc, win), null);
  assert.equal(win.timerCount, 0);
});

test('正值计时：到期前不移除、不写提示', (t) => {
  const clock = freezeTime(t, 1_000_000);
  const { macTrial, doc, win } = load();
  const { mount, message } = makePlayerPage(doc, 5);
  const handle = macTrial.start(doc, win);
  assert.ok(handle, '应返回句柄');
  assert.equal(win.timerCount, 1);
  clock.advance(4 * 60 * 1000); // 4 分钟，未到 5 分钟
  win.tick();
  assert.equal(handle.hasEnded(), false);
  assert.equal(mount.children.length, 5);
  assert.equal(message.textContent, '');
});

test('到期：暂停 video/audio、移除全部媒体与 iframe/object/embed、显示固定文案并清定时器', (t) => {
  const clock = freezeTime(t, 1_000_000);
  const { macTrial, doc, win } = load();
  const { stage, mount, message } = makePlayerPage(doc, 5);
  const video = mount.children.find((c) => c.tagName === 'VIDEO');
  const audio = mount.children.find((c) => c.tagName === 'AUDIO');
  macTrial.start(doc, win);
  clock.advance(5 * 60 * 1000 + 1); // 越过期限
  win.tick();
  assert.equal(video.pauseCount, 1, 'video 应被 pause 一次');
  assert.equal(audio.pauseCount, 1, 'audio 应被 pause 一次');
  assert.equal(mount.children.length, 0, 'iframe/object/embed/video/audio 应全部移除');
  assert.equal(stage.querySelectorAll('iframe, object, embed, video, audio').length, 0);
  assert.equal(message.textContent, END_MESSAGE, '提示文案须与契约逐字一致');
  assert.equal(message.hidden, false, '提示应取消 hidden');
  assert.equal(win.timerCount, 0, '结束后应清除定时器');
});

test('后台标签页：定时器被节流时，回前台按经过时间立即结束', (t) => {
  const clock = freezeTime(t, 1_000_000);
  const { macTrial, doc, win } = load();
  const { mount, message } = makePlayerPage(doc, 5);
  const handle = macTrial.start(doc, win);
  // 进入后台：定时器一直不触发（节流），时间照常流逝
  doc.hidden = true;
  doc.fire('visibilitychange');
  assert.equal(handle.hasEnded(), false, '后台中不触发检查');
  clock.advance(10 * 60 * 1000); // 后台停留 10 分钟，远超过 5 分钟试看
  assert.equal(handle.hasEnded(), false, '节流期间 win.tick 未被调用，仍未结束');
  // 回到前台：visibilitychange 触发按经过时间的复核
  doc.hidden = false;
  doc.fire('visibilitychange');
  assert.equal(handle.hasEnded(), true, '回前台应立即按经过时间结束');
  assert.equal(mount.children.length, 0);
  assert.equal(message.textContent, END_MESSAGE);
});

test('结束幂等：多次触发不重复移除、不重复暂停', (t) => {
  const clock = freezeTime(t, 1_000_000);
  const { macTrial, doc, win } = load();
  const { mount } = makePlayerPage(doc, 1);
  const video = mount.children.find((c) => c.tagName === 'VIDEO');
  const handle = macTrial.start(doc, win);
  clock.advance(61 * 1000);
  win.tick();
  win.tick();
  handle.endNow();
  assert.equal(video.pauseCount, 1, 'pause 只应调用一次');
  assert.equal(handle.hasEnded(), true);
});

test('只移除挂载区内的媒体，挂载区外的 video 不受影响', (t) => {
  const clock = freezeTime(t, 1_000_000);
  const { macTrial, doc, win } = load();
  const { mount } = makePlayerPage(doc, 1);
  const outside = new FakeEl('video');
  doc.byId['player-stage'].parentNode = null;
  const body = new FakeEl('body');
  body.appendChild(doc.byId['player-stage']);
  body.appendChild(outside);
  macTrial.start(doc, win);
  clock.advance(61 * 1000);
  win.tick();
  assert.equal(mount.children.length, 0);
  assert.equal(body.children.includes(outside), true, '挂载区外的 video 应保留');
  assert.equal(outside.pauseCount, 0);
});
