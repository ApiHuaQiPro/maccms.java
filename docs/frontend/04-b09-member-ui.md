# B09 会员账号页面前端说明与检查记录（MACC-33，K1–K4）

> 前端交付：会员账号页面（登录/注册/资料/头像/找回/跳转提示/片段）的样式与表单交互。
> 契约：`docs/architecture/18-b09-member-account.md` 第 8 节（8.2 DOM 钩子、8.3 行为契约）。
> 页面结构、文案、视图模型、CSP 由后端负责；本文只覆盖前端部分。

## 1. 交付物

| 文件 | 内容 |
|---|---|
| `backend/src/main/resources/static/site/site.css`（末尾「B09（MACC-33）」注释段） | 8.2 节全部 class 的样式 |
| `backend/src/main/resources/static/site/member.js`（新文件） | 8.3 节表单交互 |
| `backend/src/test/js/site/member.test.js`（新文件） | `node --test`，无外部依赖 |
| 本文档 | 说明与检查记录 |

## 2. 样式设计（K1）

不新增视觉语言，全部沿用 site.css 既有 token（`--site-*`）与卡片/notice/分页既有模式：

- `member-page` 居中窄栏（表单页 26rem，会员中心/资料页 40rem 放宽），`min-width: 0` 防溢出。
- `member-form` 用 `surface + border + radius + shadow` 的卡片外观（同 video-card/search-filters 一类）；`member-fragment`（ajax 片段）去掉卡片装饰，因为无外壳。
- 验证码字段 `member-form__field--captcha` 用 flex 横向排列输入框 + 图片按钮，320px 下 wrap 换行；图片固定 128×40（契约属性值），`max-width: none` 覆盖全局 `img` 规则。
- 结果消息 `member-form__message` 按表单上的 `data-state` 区分：pending 灰、error 红边红字（`--site-color-danger`）、success 蓝边；`[hidden]` 时不占位。
- 提交按钮沿用密码页/搜索页的主按钮样式（link 色底、白字），`[disabled]` 降低透明度并 `cursor: wait`。
- 会员中心 `member-profile` 复用 `metadata-list` 的网格模式（10rem 键列 + 1fr 值列，30rem 断点回退单列，防窄屏溢出——与队长在 MACC-14 实测确认的同一防护）。
- `member-nav`、`member-links` 复用 filter-chip 式边框链接；`jump-page` 沿用 notice 外观做居中提示卡。
- 可访问性：所有可点元素有 `:focus-visible` 焦点环（`--site-focus-ring`）；新增过渡只在 `@media (prefers-reduced-motion: no-preference)` 内，且只做颜色/透明度变化。
- 无外部资源、无 `@import`/`@font-face`/外链字体，符合 `SiteStylesheetTest` 静态护栏（本轮实测 11/11 通过）。

## 3. 交互行为（K2，`member.js`）

按契约 8.3 节逐条实现：

1. 拦截每个 `form[data-member-form]` 的 submit：`fetch(form.action, {method: 'POST', credentials: 'same-origin', headers: {'X-Requested-With': 'XMLHttpRequest'}, body})`；`portrait` 用 `FormData`（multipart），其余用 `URLSearchParams(FormData)`（UTF-8 urlencoded）。请求期间禁用提交按钮、`data-state="pending"`。
2. 响应按 JSON 解析，`code === 1` 为成功：`[data-member-message]` 用 `textContent` 显示 `msg` 并取消 hidden；有 `data-success-url` 时 `location.assign` 跳转；`portrait` 成功时把 `img[data-member-portrait]` 的 src 换成响应 `file`——只接受以单个 `/` 开头的站内路径，`//…`（协议相对）与绝对 URL 一律不换。
3. 失败（`code !== 1`）：显示 `msg`、`data-state="error"`，刷新验证码并清空 `verify` 输入（新版猜错一次即作废）；成功但不跳转时同样刷新。
4. 网络错误或响应不是 JSON：固定文案「请求失败，请稍后重试。」。
5. 点击 `[data-captcha-refresh]`（含点击图片冒泡）：img 的 src 设为 `data-captcha-src + '?r=' + Math.random()`。
6. 点击 `[data-member-send]`（注册页的手机/邮箱验证区、邮箱/手机找回页）：取所在表单的 `ac`、`to`，按第 1 条的方式（urlencoded）POST 到 `data-send-url`，在该表单的 `[data-member-message]` 显示 `msg`，不改变表单的 `data-state`；请求期间禁用该按钮。（契约修订后的第 6 条，前端交付时依据的 `3008228` 契约稿还没有这一条；整合时由后端补上，见第 4.3 节。）
7. 约束自查：不读写 cookie、不引入依赖、不访问外部域名；测试里有源码级断言（不出现 `innerHTML`/`document.cookie`/`https?://` 字面量）。

## 4. 验证结果

### 4.1 Node 行为测试（K3）

在仓库根目录执行（Node v24.18.0）：

```
node --test backend/src/test/js/site/*.test.js
```

整合后实测输出：`tests 23 / pass 23 / fail 0`（member.test.js 15 项 + trial.test.js 8 项）。前端交付时为 21 项（member 13 项），整合时补了发送验证码的 2 项。

member.test.js 覆盖：发送验证码（取同表单 `ac`/`to`、urlencoded POST 到 `data-send-url`、显示 `msg`、按钮禁用与恢复、网络错误固定文案）、fetch 参数（method/headers/credentials/正文类型）、成功跳转、失败刷新验证码+清空输入、成功不跳转也刷新、网络错误固定文案、非 JSON 响应、提交按钮禁用/恢复、portrait multipart 与头像更新、站外/协议相对 file 拒绝（`//evil.example`、`https://…`、相对路径、空串 4 例）、点击按钮与图片冒泡刷新、无验证码表单失败、源码约束断言。

### 4.2 样式静态护栏

```
mvnw test -Dtest=SiteStylesheetTest
```

实测：`Tests run: 11, Failures: 0, Errors: 0`（30rem 断点、focus-visible、reduced-motion、无外部资源、无 BOM 等均通过）。

### 4.3 真实页面检查（整合后实测，2026-10-18）

前端交付时真实页面还不可用，本节原为"待整合验证"。下面是 `4f15676` 以普通 merge 并入 `agent/claude/macc-33` 之后，由后端在打包 jar 上实测的结果；前端交付报告本身不作为验收依据。

**环境**：`./mvnw package` 得到的 jar，一次性库 `maccms_it_b09ui_<时间>_<随机>`（导入 `install.sql` + `initdata.sql`），可写数据源打开，Playwright 1.60（Chromium 148）。两个实例：

- 根路径：`http://127.0.0.1:18291/`，旧配置用仓库 `application/`（登录、注册不要验证码），用来走成功流程。
- 子目录：base-url `http://127.0.0.1:18292/sub/`，前面是单独起的 nginx（只为本次检查）。`/sub/` 反代到实例根路径，`/sub/static/` 指向旧站 `static/`，`/sub/upload/` 指向头像目录。旧配置是仓库副本，只把 `login_verify`、`reg_verify` 改成 1，用来走验证码流程。

**宽度（320 / 360 / 768 / 1280）**：每个宽度检查 9 个页面状态：登录、注册、找回、带验证码的登录（子目录）、跳转提示页（未登录访问会员中心）、会员中心、资料、头像、资料页出错后。

| 检查 | 结果 |
|---|---|
| 横向溢出（`scrollWidth` 不超过视口，`main` 内没有元素越出视口左右边界） | 36/36 通过 |
| 样式生效（表单为卡片块，会员中心 `member-profile` 为网格）、`member.js` 在 CSP 下执行 | 通过 |
| 可点目标最小高度（输入框、按钮、验证码按钮、链接） | 32px（会员中心导航 36px），大于 WCAG 2.2 AA 的 24px |
| 截图人工核对 | 320/360 为单列；验证码图在输入框下换行，不错位；错误消息为红边红字；1280 下验证码与输入框同一行，会员中心头像与资料并排 |

**纯键盘（320 与 1280）**：

- 登录页 Tab 顺序：跳到正文 → 站点名 → 搜索框 → 搜索按钮 → 登录/会员中心 → 用户名 → 密码 → 登录按钮，与 DOM 顺序一致。每个停留点都匹配 `:focus-visible`，并且有焦点环（outline 或 box-shadow）。
- 在输入框里按 Enter 提交：先 `pending`（按钮禁用），然后 `error`。消息框是 `role="status"`、`aria-live="polite"`，显示"获取用户信息失败"，按钮恢复可用。改正密码后按 Enter，跳转到会员中心。
- 会员中心用 Tab 走到"修改头像"，按 Enter 进入；在文件框上按 Space 打开文件选择，选好后 Tab 到"上传"，按 Enter：`pending` → `success`，头像地址换成新文件。
- 子目录带验证码的登录页：Tab 到验证码按钮，按 Enter 刷新一次，按 Space 再刷新一次，地址依次是 `/sub/index.php/verify/index.html?r=…`。

**reduced-motion**：带验证码的登录页（1280）。`no-preference` 下 `.member-form__submit`、`.member-captcha` 的过渡是 0.12s；`reduce` 下所有探测元素（输入框、按钮、验证码按钮、链接、消息框）都是 `transition-duration: 0s`、`animation-name: none`。

**表单状态与接线**（1280，`MutationObserver` 记录每次 `data-state` 变化）：

| 流程 | 结果 |
|---|---|
| 注册：两次密码不一致 → 改正 | `pending` → `error`，消息"密码与确认密码不一致"，按钮恢复；改正后 `pending` → `success`，跳转到 `data-success-url`（会员中心），收到 6 个会员 cookie |
| 资料：当前密码错 → 正确 | `error` 显示"原密码错误"；然后 `success` 显示"保存成功!"，留在本页（没有 `data-success-url`） |
| 头像（根路径） | `success`，"文件上传成功！"，`img[data-member-portrait]` 从 `/static/images/touxiang.png` 换成 `/upload/user/<n>/<id>.jpg?<随机>` |
| 头像（子目录） | 请求发到 `/sub/index.php/user/portrait.html`。新地址 `/sub/upload/user/…`，经 nginx 实际加载，尺寸 100×100。默认头像 `/sub/static/images/touxiang.png` 也能加载 |
| WebP 头像（MACC-23 裁定方案 B 实施后重测，18 文档第 14.5 节） | 动图 WebP：`success`，"文件上传成功！"，头像换成 100×100 JPEG（第一帧，蓝），根路径与子目录都经 nginx 加载；截断的 WebP：`error`，"文件上传失败！"，头像不变。`accept` 含 `image/webp` |
| 找回（总要验证码）填错验证码 | `error`，"验证码错误"。验证码图换成 `…/verify/index.html?r=…` 并重新加载成功，`verify` 输入被清空 |
| 子目录登录填错验证码 | 请求发到 `/sub/index.php/user/login.html`，`error`，验证码刷新为 `/sub/index.php/verify/index.html?r=…` |
| 退出（子目录会员中心"退出登录"） | 跳到 `/sub/index.php/user/login.html`，会员 cookie 全部删除；再访问会员中心得到跳转提示页"未登录"（`role="alert"`） |
| CSP（根路径 6 页、子目录 3 页） | 每页恰好一个 `<script>`，它的 `nonce` 等于响应头 `script-src 'nonce-…'` 中的值。没有内联脚本，没有 `on*` 事件属性，`Cache-Control: no-store`。浏览器没有报 CSP 违规，也没有页面错误 |

根路径实例直接访问 jar 时，`/static/images/touxiang.png` 和 `/upload/…` 返回 404，这是预期的：这两个路径由部署层 nginx 提供，子目录实例上已经验证过。除此之外没有出现 4xx/5xx。

整合时的改动：

- `member.js` 补上契约修订后的第 6 条（发送验证码），`member.test.js` 增加 2 项。
- 其余前端文件没有改动；没有发现需要改样式的问题。

## 5. 边界

- 前端交付未改动：`jte/site/pages/`、`jte/site/ajax/`、视图模型、`layout/`、`components/`、`trial.js`、既有 CSS 段（只在末尾追加）。
- 可写数据源关闭时的 503 维护提示页、CSP nonce 脚本引入均由后端负责。
