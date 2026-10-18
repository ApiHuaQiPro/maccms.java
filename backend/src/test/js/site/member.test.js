/*
 * member.js 行为测试（MACC-33 K3）。Node 内置 node:test，无外部依赖。
 * 用最小假 DOM/窗口对象注入脚本，模拟后端输出的会员页挂点。
 * 运行：node --test backend/src/test/js/ （在仓库根目录）
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const SRC = fs.readFileSync(
  path.join(__dirname, '..', '..', '..', 'main', 'resources', 'static', 'site', 'member.js'),
  'utf8');

const FAIL_MESSAGE = '请求失败，请稍后重试。';

/** 假 FormData：接受假表单元素，记录来源；instanceof 判定用真实构造器名。 */
class FakeFormData {
  constructor(form) {
    this.form = form;
  }
}

class FakeURLSearchParams {
  constructor(data) {
    this.data = data;
    this.entries = [];
  }

  append(name, value) {
    this.entries.push([name, value]);
  }
}

class FakeEl {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attributes = {};
    this.parentNode = null;
    this.textContent = '';
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this.listeners = {};
  }

  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }

  hasAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name);
  }

  removeAttribute(name) {
    delete this.attributes[name];
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  appendChild(child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
  }

  fire(type, event) {
    for (const fn of this.listeners[type] || []) fn(event);
  }

  matchesAttr(name, value) {
    return this.getAttribute(name) === value;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const out = [];
    const want = parseSelector(selector);
    const walk = (node) => {
      for (const child of node.children) {
        if (want(child)) out.push(child);
        walk(child);
      }
    };
    walk(this);
    return out;
  }
}

/** 支持本脚本用到的最小选择器：tag、[attr]、[attr="v"]、tag[attr]、img[attr]。 */
function parseSelector(selector) {
  const m = /^(?:(\w+))?(?:\[([\w-]+)(?:="([^"]*)")?\])?$/.exec(selector.trim());
  if (!m) throw new Error('unsupported selector in fake DOM: ' + selector);
  const [, tag, attr, value] = m;
  return (el) => {
    if (tag && el.tagName !== tag.toUpperCase()) return false;
    if (attr) {
      if (!el.hasAttribute(attr)) return false;
      if (value !== undefined && el.getAttribute(attr) !== value) return false;
    }
    return true;
  };
}

class FakeDocument {
  constructor() {
    this.children = [];
    this.listeners = {};
    this.readyState = 'loading'; // 阻止脚本底部 boot() 自动执行
    this.documentElement = new FakeEl('html');
  }

  addEventListener(type, fn) {
    (this.listeners[type] = this.listeners[type] || []).push(fn);
  }

  fire(type, event) {
    for (const fn of this.listeners[type] || []) fn(event);
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  querySelectorAll(selector) {
    const want = parseSelector(selector);
    const out = [];
    const walk = (node) => {
      for (const child of node.children) {
        if (want(child)) out.push(child);
        walk(child);
      }
    };
    walk(this);
    return out;
  }
}

class FakeWindow {
  constructor() {
    this.fetchCalls = [];
    this.fetchHandler = null;
    this.assignedUrls = [];
    this.FormData = FakeFormData;
    this.URLSearchParams = FakeURLSearchParams;
    this.location = {
      assign: (url) => {
        this.assignedUrls.push(url);
      }
    };
  }

  fetch(url, options) {
    this.fetchCalls.push({ url, options });
    return this.fetchHandler(url, options);
  }
}

/** 加载脚本并返回 { macMember, doc, win }；脚本以注入的假 window/document 运行。 */
function load() {
  const doc = new FakeDocument();
  const win = new FakeWindow();
  const factory = new Function('window', 'document', `${SRC}\n;return window.MacMember;`);
  const macMember = factory(win, doc);
  assert.ok(macMember, '脚本应暴露 MacMember');
  return { macMember, doc, win };
}

/** 造一个契约第 8.2 节的登录表单（含验证码）。 */
function makeLoginForm(doc, opts = {}) {
  const form = new FakeEl('form');
  form.setAttribute('method', 'post');
  form.setAttribute('action', '/index.php/user/login.html');
  form.setAttribute('data-member-form', opts.kind || 'login');
  if (opts.successUrl !== null) {
    form.setAttribute('data-success-url', opts.successUrl || '/index.php/user/index.html');
  }
  const message = new FakeEl('p');
  message.setAttribute('data-member-message', '');
  message.setAttribute('hidden', '');
  message.hidden = true;
  form.appendChild(message);
  if (opts.captcha !== false) {
    const verify = new FakeEl('input');
    verify.setAttribute('name', 'verify');
    verify.value = 'abcd';
    form.appendChild(verify);
    const button = new FakeEl('button');
    button.setAttribute('data-captcha-refresh', '');
    const img = new FakeEl('img');
    img.setAttribute('src', '/index.php/verify/index.html');
    img.setAttribute('data-captcha-src', '/index.php/verify/index.html');
    button.appendChild(img);
    form.appendChild(button);
  }
  const submit = new FakeEl('button');
  submit.setAttribute('type', 'submit');
  form.appendChild(submit);
  doc.children.push(form);
  return { form, message, submit };
}

function jsonResponse(body) {
  return Promise.resolve({ json: () => Promise.resolve(body) });
}

test('提交：POST 到 form.action，带 X-Requested-With 与 same-origin，urlencoded 正文', async () => {
  const { macMember, doc, win } = load();
  const { form } = makeLoginForm(doc);
  win.fetchHandler = () => jsonResponse({ code: 1001, msg: 'x' });
  macMember.boot();
  await macMember.submit(form);
  assert.equal(win.fetchCalls.length, 1);
  const call = win.fetchCalls[0];
  assert.equal(call.url, '/index.php/user/login.html');
  assert.equal(call.options.method, 'POST');
  assert.equal(call.options.credentials, 'same-origin');
  assert.equal(call.options.headers['X-Requested-With'], 'XMLHttpRequest');
  assert.ok(call.options.body instanceof FakeURLSearchParams, '非 portrait 表单应 urlencoded');
});

test('成功且有 data-success-url：显示 msg、data-state=success、跳转到 success-url', async () => {
  const { macMember, doc, win } = load();
  const { form, message } = makeLoginForm(doc);
  win.fetchHandler = () => jsonResponse({ code: 1, msg: '登录成功' });
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  assert.equal(message.textContent, '登录成功');
  assert.equal(message.hidden, false, '消息框应取消 hidden');
  assert.equal(form.getAttribute('data-state'), 'success');
  assert.deepEqual(win.assignedUrls, ['/index.php/user/index.html']);
});

test('失败：显示 msg、data-state=error、刷新验证码并清空 verify 输入', async () => {
  const { macMember, doc, win } = load();
  const { form, message } = makeLoginForm(doc);
  const verify = form.querySelector('[name="verify"]');
  const img = form.querySelector('img');
  const before = img.getAttribute('src');
  win.fetchHandler = () => jsonResponse({ code: 1002, msg: '验证码错误' });
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  assert.equal(message.textContent, '验证码错误');
  assert.equal(form.getAttribute('data-state'), 'error');
  assert.equal(verify.value, '', '验证码输入应清空');
  const after = img.getAttribute('src');
  assert.notEqual(after, before, '验证码图片应刷新');
  assert.ok(after.startsWith('/index.php/verify/index.html?r='), '刷新应加 ?r= 随机数');
});

test('成功但不跳转（无 data-success-url）：同样刷新验证码（猜错即作废）', async () => {
  const { macMember, doc, win } = load();
  const { form } = makeLoginForm(doc, { kind: 'findpass', successUrl: null });
  const img = form.querySelector('img');
  const before = img.getAttribute('src');
  win.fetchHandler = () => jsonResponse({ code: 1, msg: '密码找回成功成功' });
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  assert.equal(form.getAttribute('data-state'), 'success');
  assert.equal(win.assignedUrls.length, 0, '无 success-url 不应跳转');
  assert.notEqual(img.getAttribute('src'), before, '成功不跳转也应刷新验证码');
});

test('网络错误：显示固定文案"请求失败，请稍后重试。"、data-state=error', async () => {
  const { macMember, doc, win } = load();
  const { form, message } = makeLoginForm(doc);
  win.fetchHandler = () => Promise.reject(new Error('network down'));
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(message.textContent, FAIL_MESSAGE);
  assert.equal(form.getAttribute('data-state'), 'error');
});

test('响应不是 JSON（json() 抛错）：同样按固定文案处理', async () => {
  const { macMember, doc, win } = load();
  const { form, message } = makeLoginForm(doc);
  win.fetchHandler = () => Promise.resolve({ json: () => Promise.reject(new Error('not json')) });
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(message.textContent, FAIL_MESSAGE);
});

test('请求期间禁用提交按钮，结束后恢复', async () => {
  const { macMember, doc, win } = load();
  const { form, submit } = makeLoginForm(doc);
  let resolveFetch;
  win.fetchHandler = () => new Promise((resolve) => {
    resolveFetch = () => resolve({ json: () => jsonResponse({ code: 1, msg: 'ok' }) });
  });
  macMember.boot();
  const pending = macMember.submit(form);
  assert.equal(submit.disabled, true, '请求期间应禁用');
  assert.equal(form.getAttribute('data-state'), 'pending');
  resolveFetch();
  await pending;
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(submit.disabled, false, '结束后应恢复');
});

test('portrait 表单用 FormData（multipart），成功时更新 img[data-member-portrait]', async () => {
  const { macMember, doc, win } = load();
  const { form } = makeLoginForm(doc, { kind: 'portrait', successUrl: null, captcha: false });
  const avatar = new FakeEl('img');
  avatar.setAttribute('data-member-portrait', '');
  avatar.setAttribute('src', '/static/images/touxiang.png');
  doc.children.push(avatar);
  win.fetchHandler = () => jsonResponse({ code: 1, msg: '文件上传成功！', file: '/upload/user/1/11.jpg?1234' });
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  assert.ok(win.fetchCalls[0].options.body instanceof FakeFormData, 'portrait 应用 multipart');
  assert.equal(avatar.getAttribute('src'), '/upload/user/1/11.jpg?1234');
});

test('portrait 成功但 file 为站外/协议相对地址：不更新头像', async () => {
  const { macMember, doc, win } = load();
  const { form } = makeLoginForm(doc, { kind: 'portrait', successUrl: null, captcha: false });
  const avatar = new FakeEl('img');
  avatar.setAttribute('data-member-portrait', '');
  avatar.setAttribute('src', '/static/images/touxiang.png');
  doc.children.push(avatar);
  for (const bad of ['//evil.example/x.jpg', 'https://evil.example/x.jpg', 'x.jpg', '']) {
    win.fetchHandler = () => jsonResponse({ code: 1, msg: 'ok', file: bad });
    await macMember.submit(form);
    await Promise.resolve();
    assert.equal(avatar.getAttribute('src'), '/static/images/touxiang.png', `应拒绝 ${JSON.stringify(bad)}`);
  }
});

test('点击 [data-captcha-refresh]：img src 设为 data-captcha-src + ?r= 随机数', () => {
  const { macMember, doc } = load();
  const { form } = makeLoginForm(doc);
  const button = form.querySelector('[data-captcha-refresh]');
  const img = form.querySelector('img');
  macMember.boot();
  doc.fire('click', { target: button });
  const src = img.getAttribute('src');
  assert.ok(src.startsWith('/index.php/verify/index.html?r='), `实际 src: ${src}`);
  assert.ok(src.length > '/index.php/verify/index.html?r='.length, 'r 参数非空');
});

test('点击验证码图片本身（冒泡到按钮）：同样刷新', () => {
  const { macMember, doc } = load();
  const { form } = makeLoginForm(doc);
  const img = form.querySelector('img');
  img.parentNode = form.querySelector('[data-captcha-refresh]');
  const before = img.getAttribute('src');
  macMember.boot();
  doc.fire('click', { target: img });
  assert.notEqual(img.getAttribute('src'), before);
});

test('无验证码表单失败时不报错', async () => {
  const { macMember, doc, win } = load();
  const { form, message } = makeLoginForm(doc, { captcha: false });
  win.fetchHandler = () => jsonResponse({ code: 0, msg: '原密码错误' });
  macMember.boot();
  await macMember.submit(form);
  await Promise.resolve();
  assert.equal(message.textContent, '原密码错误');
  assert.equal(form.getAttribute('data-state'), 'error');
});

/** 造一个契约第 8.2 节"发送验证码"区（findpass_msg 页的重置表单内）。 */
function makeSendForm(doc) {
  const form = new FakeEl('form');
  form.setAttribute('action', '/index.php/user/findpass_reset.html');
  form.setAttribute('data-member-form', 'findpass_reset');
  const ac = new FakeEl('input');
  ac.setAttribute('name', 'ac');
  ac.value = 'email';
  form.appendChild(ac);
  const to = new FakeEl('input');
  to.setAttribute('name', 'to');
  to.value = 'a@b.test';
  form.appendChild(to);
  const button = new FakeEl('button');
  button.setAttribute('type', 'button');
  button.setAttribute('data-member-send', '');
  button.setAttribute('data-send-url', '/index.php/user/findpass_msg.html');
  form.appendChild(button);
  const message = new FakeEl('p');
  message.setAttribute('data-member-message', '');
  message.hidden = true;
  form.appendChild(message);
  doc.children.push(form);
  return { form, button, message };
}

test('点击 [data-member-send]：取同表单 ac、to，urlencoded POST 到 data-send-url，显示 msg', async () => {
  const { macMember, doc, win } = load();
  const { form, button, message } = makeSendForm(doc);
  let resolveFetch;
  win.fetchHandler = () => new Promise((resolve) => {
    resolveFetch = () => resolve({ json: () => Promise.resolve({ code: 0, msg: '验证码发送失败：未配置邮箱发送服务' }) });
  });
  macMember.boot();
  doc.fire('click', { target: button });
  assert.equal(win.fetchCalls.length, 1);
  const call = win.fetchCalls[0];
  assert.equal(call.url, '/index.php/user/findpass_msg.html');
  assert.equal(call.options.method, 'POST');
  assert.equal(call.options.credentials, 'same-origin');
  assert.equal(call.options.headers['X-Requested-With'], 'XMLHttpRequest');
  assert.ok(call.options.body instanceof FakeURLSearchParams);
  assert.deepEqual(call.options.body.entries, [['ac', 'email'], ['to', 'a@b.test']]);
  assert.equal(button.disabled, true, '请求期间禁用发送按钮');
  resolveFetch();
  await new Promise((r) => setImmediate(r));
  assert.equal(message.textContent, '验证码发送失败：未配置邮箱发送服务');
  assert.equal(message.hidden, false);
  assert.equal(button.disabled, false);
  assert.equal(form.getAttribute('data-state'), null, '发送验证码不改变表单状态');
});

test('[data-member-send] 网络错误：显示固定文案', async () => {
  const { macMember, doc, win } = load();
  const { button, message } = makeSendForm(doc);
  win.fetchHandler = () => Promise.reject(new Error('down'));
  macMember.boot();
  await macMember.send(button);
  assert.equal(message.textContent, FAIL_MESSAGE);
});

test('源码不使用 innerHTML、不访问外部域名', () => {
  assert.equal(/innerHTML/.test(SRC), false, '不得使用 innerHTML');
  assert.equal(/https?:\/\//.test(SRC), false, '不得出现外部地址字面量');
  assert.equal(/document\.cookie/.test(SRC), false, '不得读写 cookie');
});
