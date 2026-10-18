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
6. 约束自查：不读写 cookie、不引入依赖、不访问外部域名；测试里有源码级断言（不出现 `innerHTML`/`document.cookie`/`https?://` 字面量）。

## 4. 验证结果

### 4.1 Node 行为测试（K3）

在仓库根目录执行（Node v24.18.0）：

```
node --test backend/src/test/js/site/*.test.js
```

实测输出：`tests 21 / pass 21 / fail 0`（member.test.js 13 项 + trial.test.js 8 项，未破坏既有测试）。

member.test.js 13 项覆盖：fetch 参数（method/headers/credentials/正文类型）、成功跳转、失败刷新验证码+清空输入、成功不跳转也刷新、网络错误固定文案、非 JSON 响应、提交按钮禁用/恢复、portrait multipart 与头像更新、站外/协议相对 file 拒绝（`//evil.example`、`https://…`、相对路径、空串 4 例）、点击按钮与图片冒泡刷新、无验证码表单失败、源码约束断言。

### 4.2 样式静态护栏

```
mvnw test -Dtest=SiteStylesheetTest
```

实测：`Tests run: 11, Failures: 0, Errors: 0`（30rem 断点、focus-visible、reduced-motion、无外部资源、无 BOM 等均通过）。

### 4.3 宽度与键盘检查（320 / 360 / 768 / 1280、仅键盘）

**待整合验证。** 截至本交付，后端页面模板（`pages/user*.jte`）尚在 Claude 的实现分支上、尚未合入本分支，真实页面不可用，无法做真实浏览器截图检查。设计时已按既有页面的溢出防护模式处理：

- 表单页 26rem ≈ 416px 最大宽度，在 320/360px 视口下自然收缩（`max-width` + `min-width: 0`），验证码行 flex-wrap 换行；
- `member-profile` 在 ≤30rem 断点回退单列（与 `metadata-list` 相同的防护）；
- 键盘可达性：表单控件为原生 input/button，焦点环经 `:focus-visible`；消息框 `role="status"` + `aria-live="polite"`（后端 markup）由脚本只写 `textContent`，屏幕阅读器可读到结果；验证码按钮有 `aria-label`（后端 markup）。

整合验证计划：待后端 jte 合入后，起本地服务对 `user/login`、`user/reg`、`user/info`、`user/portrait`、`user/findpass` 在 320/360/768/1280 四个宽度截图核对无横向滚动条、验证码图不换行错位、Tab 顺序与焦点可见；结果补入本节。

## 4. 边界

- 未改动：`jte/site/pages/`、`jte/site/ajax/`、视图模型、`layout/`、`components/`、`trial.js`、既有 CSS 段（只在末尾追加）。
- 可写数据源关闭时的 503 维护提示页、CSP nonce 脚本引入均由后端负责。
