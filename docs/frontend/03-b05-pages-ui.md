# B05 前台只读页：前端样式与试看脚本说明（MACC-54，K1–K5）

> 配套契约：`docs/architecture/15-b05-readonly-pages.md` 第 3.3、5.4、6、8 节（后端维护）。
> 本文档由前端（Kimi）维护，只覆盖前端分工：样式、`trial.js` 与外壳复核；页面 jte、视图模型、CSP 由后端（Claude）实现。

## 1. 文件与所有权

| 文件 | 内容 | 备注 |
|---|---|---|
| `backend/src/main/resources/static/site/site.css` | K1–K3 全部样式（追加在文件尾部"B05"注释段） | 前端独占目录 |
| `backend/src/main/resources/static/site/trial.js` | K4 试看计时脚本 | 同源、无外部依赖 |
| `backend/src/test/js/site/trial.test.js` | K4 行为测试（Node 内置 `node:test`，无外部依赖） | 前端静态资源测试 |
| 本文档 | 实施说明与 K5 复核记录 | — |

未改动：`jte/site/components/`、`jte/site/layout/`、任何页面 jte、任何 Java、既有组件 `@param`。`content-card` 按队长裁定由后端在页面内联，本文档固定其可用子类样式（见第 3 节）。

## 2. K1 搜索筛选区样式

- 结构沿用既有 `filter-group` / `filter-chip` / `aria-current="true"`，**未新增任何 DOM modifier**；"仅已设置"组与普通组同结构，按裁定不做视觉区分。
- 14 组窄屏排列：沿用既有 `filter-group` 在 ≤30rem 时退化为单列（组标题独占一行、候选区整行换行）；候选区为 flex 换行，首字母组 28 项自然折行。
- `filter-chip` 改为 `inline-flex` + `min-height: 2rem`：触摸目标加高，长文本（多值如 `1,,2`、长片名）经 `overflow-wrap: anywhere` 折行；"全部/当前"沿用 `filter-chip--current` 与 `aria-current` 样式。
- 随机排序提示：`p.search-random-notice.notice`（后端输出），新增 `.search-random-notice` 间距；随机排序时后端不渲染分页组件，CSS 不涉及。
- 验证：fixture `search.html`（14 组、28 首字母、长文本、多值）与 `search-rnd.html`，见第 6 节。

## 3. K2 内容页样式

- 新增 class 样式：`article-page`、`article-pager`（外层仅间距，分页复用既有 `pagination` 组件，不改组件）、`topic-page`、`topic-list`、`actor-page`、`role-page`、`website-page`、`plot-page`、`plot-list`、`content-grid`、`content-card`、`screenshot-list`；`detail-content` 沿用既有规则（富文本图片限宽、表格可横向滚动）。
- `content-card`（页面内联卡片）支持子类：`content-card__image`（海报比例 2/3，`object-fit: cover`）、`content-card__title`（两行截断 + 任意折行，长标题可用）、`content-card__meta`（次要信息折行）；另提供 `content-card__placeholder` 供无图时占位（可选使用）。
- 空列表：沿用 `.notice` / `.search-empty` 外观，可单独成段。
- 验证：fixture `article.html`（富文本图片/表格 + 文章分页）、`lists.html`（卡片有图/无图、超长标题、空列表、剧情列表、截图列表）。

## 4. K3 播放 / 下载 / 密码 / 提示页样式

- `player-page`：标题与容器；`player-frame` 为 16:9 响应式比例容器（`aspect-ratio`），`iframe` 绝对填充、`border:0`（`title` 与同源地址由后端输出）。
- `player-sources`：来源分组标题与间距；`player-episodes`：剧集小按钮网格，最小触摸目标 2.5rem，超过约 16rem 高度内部纵向滚动（200 集不撑破页面），当前集 `aria-current="true"` 高亮；上一集/下一集链接最小高度 2rem。
- `download-page` / `download-list`：条目卡片式列表，超长下载地址任意折行。
- `password-page`：原生表单（无客户端密码处理），输入框与按钮样式同搜索表单体系，限宽 26rem。
- `access-notice`：并入既有 `.notice` 规则（403 无权限/积分/版权等固定提示页）。
- 键盘与动效：新增交互元素均有 `:focus-visible` 轮廓（实测量 2px solid）；不引入位移动画，reduced-motion 下无差异（沿用既有 `no-preference` 媒体查询约定）。
- 验证：fixture `player.html`（200 集）、`download.html`（超长地址）、`password.html`、`notice.html`。

## 5. K4 试看计时脚本（`trial.js`）

- 挂点：`div#player-stage.player-stage[data-trial-minutes]`（挂载区）与 `p#trial-message.trial-message[role=status]`（结束提示）。`data-trial-minutes` 为后端授权得出的非负整数分钟；`0`、缺失或非法值一律不计时、不动页面。
- 计时以 `Date.now()` 经过时间判定（期限 = 起始 + 分钟数），每秒 `setInterval` 复核；另监听 `visibilitychange`，回到前台立即复核——后台标签页定时器节流不会延长试看。
- 到期：对挂载区内 `video/audio` 先 `pause()`（不支持则忽略），再移除挂载区中的 `iframe/object/embed/video/audio`，最后在 `#trial-message` 写入固定文案「试看已结束，请登录后继续观看。」并取消 `hidden`（若后端输出时带 `hidden`）。结束幂等；只处理挂载区内部，不碰页面其余媒体。
- 边界如实声明：脚本不能防止刷新、禁用脚本或提取地址；不访问跨域 iframe 的 DOM（直接移除元素）；不授予任何服务端权限（授权/密码/W1/W2 始终由后端决定）。
- 测试：`backend/src/test/js/site/trial.test.js`（Node 内置 runner + 最小假 DOM 注入，无外部依赖），覆盖：0 不计时、缺挂点、非法值、到期前不动、到期移除 + 文案逐字 + 清定时器、后台恢复立即结束、幂等、只移除挂载区内媒体。运行：`node --test backend/src/test/js/site/trial.test.js`（8/8 通过，MACC-54 当轮实测）。

## 6. 验证方式与结果（当轮实测）

合成 HTML fixture（`.qwen/tmp/macc-54/fixtures/`，仅本地验证、不入库；按契约 class 手写模拟，**不等于后端真实 SSR 页面**，真实 SSR/HTTP 与 W1/W2 四组合检查由 Claude 整合后负责）：

- 无头 Chrome（本机 Chrome，CDP 直连，`Emulation.setDeviceMetricsOverride`）对 8 个 fixture 在 320/360/768/1280px 下实测：`documentElement.scrollWidth ≤ clientWidth` 全部成立，无页面横向溢出（32/32 PASS）。
- 键盘：CDP `Input.dispatchKeyEvent` Tab 遍历，filter-chip、剧集链接、密码输入框、下载链接聚焦时 `outline: 2px solid` 生效（4/4 PASS）。
- `./mvnw verify`：BUILD SUCCESS，surefire 1912 个测试 0 失败（含 `SiteStylesheetTest`、`SiteTemplatePolicyTest` 对静态资源与模板策略的护栏）。
- `git diff --check` 无输出；交付文件 UTF-8 无 BOM、无 CRLF、`trial.js` 无外链（脚本检查通过）。

## 7. K5 外壳复核记录（只读复核，未改 layout）

复核对象：`jte/site/layout/page.jte` 与 `site.web.view.PageChrome`（代码阅读 + fixture 键盘遍历）：

- **Logo**：`chrome.logo()` 为 `Optional<SafeUrl>`，由后端 `SiteSettings` 经 `SafeUrl.parse` 过滤（W2 关闭时外链图片地址不输出由后端保证，属 `SiteExternalImages*Test` 范围）；模板仅在存在时输出 `<img class="site-brand__logo" alt="">` 并始终带站点名文本，装饰图 alt 为空符合无障碍惯例。✅
- **导航**：`chrome.nav()` 为视频分类，无全局"播放"导航项；按裁定不新增。✅
- **键盘顺序**：skip-link → 品牌 → 分类导航 → 搜索表单 → 登录链接 → 主内容 → 页脚，DOM 顺序即焦点顺序，fixture Tab 遍历验证可达性与焦点可见。✅
- **品牌样式**：`.site-brand` / `.site-brand__logo` / `.site-brand__name` 既有规则正常，未改 layout 签名。✅

## 8. 与旧版差异、未验证项

- 旧版模板缺失（仓库 `template/` 为空），无法对照旧视觉；本批样式为 token 体系下的新实现，不声称还原旧站外观。
- 旧版试看计时由模板脚本实现（仓库无模板，【待核实】旧模板行为）；本脚本只实现契约挂点语义。
- 未验证：后端真实 SSR 页面（页面 jte 属后端，本批后端尚未输出播放/内容页）、真实播放器（`player.js`/iva）下的试看交互、W1/W2 四组合——均随 Claude 整合验收。
- fixture 的 CSS 经文件相对路径引用，与站点同源部署时的缓存/压缩行为未测。
