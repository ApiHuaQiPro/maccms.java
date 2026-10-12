## site 富文本白名单过滤与实体处理（MACC-12）

> **状态：过滤器、实体解码器与黄金向量已实现，尚未接入页面。** 只用于 site 展示；不改 content 正式 API、OpenAPI、SQL、数据库、旧 PHP。首页/分类/详情页另行任务。
> 依据：`04-site-template-spike.md` 第 3.4、7、9 节（默认转义不是富文本过滤；实体处理须先确认字段编码约定），`06-image-url-normalizer.md`（图片地址），契约 `VideoDetail.content`（"前端渲染前必须做 HTML 白名单过滤"）。

### 1. 结构

| 位置 | 内容 |
|---|---|
| `backend/src/main/java/com/maccms/backend/site/application/RichTextSanitizer.java` | 富文本过滤（纯函数），结果 `Result(SafeHtml html, Outcome outcome, Set<Finding> findings)` |
| `.../site/application/SafeHtml.java` | 过滤结果类型：构造器包内可见，只有过滤器能创建；`toString()` 不输出内容 |
| `.../site/application/LegacyTextEntities.java` | 普通文本字段的字符引用解码（只解一次、只认带分号的引用） |
| `backend/src/test/resources/site/rich-text/rich-text-vectors.json` | 富文本黄金向量 95 条 |
| `backend/src/test/resources/site/rich-text/entity-vectors.json`、`entity_golden.php` | 普通文本实体黄金向量 29 条与 PHP 生成/核对脚本 |
| `backend/src/test/java/.../site/application/RichTextSanitizerGoldenTest.java`、`RichTextSanitizerTest.java`、`LegacyTextEntitiesTest.java` | 向量、不变量、边界、随机输入、日志 |
| `backend/src/test/java/.../site/SiteRichTextTemplateTest.java`、`backend/src/test/jte-richtext*/` | 与 jte 严格站点策略的配合 |

依赖：`org.jsoup:jsoup` 1.23.2 从 `test` 改为编译作用域（此前已固定用于测试）。`ModuleBoundaryTest` 新增：site 只能用 jsoup 的 nodes/parser/safety 等包，禁止 `org.jsoup.Jsoup`、`Connection`、`helper.HttpConnection` 与通配导入（它们带网络功能）。

### 2. 库选型

| | jsoup 1.23.2（选用） | OWASP Java HTML Sanitizer 20261011.1 |
|---|---|---|
| 许可证【资料：POM】 | MIT | Apache-2.0 / BSD-2-Clause 双许可 |
| 运行时传递依赖【资料：POM】 | 无（re2j 为 optional，jspecify 为 provided） | 无（Guava 已移除） |
| JDK / Boot 基线 | Java 8+；本工程 JDK 21、Boot 4.1.1 编译测试通过【实测】 | Java 8+【资料】，未接入 |
| 解析方式 | 完整 HTML5 树构建算法（与浏览器一致：字符引用、错误嵌套、foster parenting） | 自有词法分析 + 标签配平，不是 HTML5 树构建 |
| 发布节奏【资料：maven-metadata】 | 已在本工程使用并固定 | 2026-09-21～10-11 间发布 5 个版本，最新版发布于本任务当天 |
| 本工程现状 | 已固定在 pom（测试用），无新增下载 | 新依赖 |

同一组 22 个恶意/边界输入、等价白名单下的对比（`backend/target` 下的一次性程序，未提交）【实测】：

- 两者都删除 `<script>`、`<style>`、`<iframe>`、svg/math 内容、事件属性、`style` 属性、注释，错误嵌套的修复结果相同。
- **两者都放行协议相对地址** `<a href="//evil.test/x">`（OWASP 视为相对地址；jsoup Safelist 不设协议时不检查）。所以无论选哪个，链接与图片都必须再经本项目的 `SafeUrl` / `ImageUrlNormalizer`。
- jsoup Safelist 不设协议时原样保留 `javascript:`；本实现不依赖 Safelist 的协议检查，所有 URL 属性由第 3.3 节的规则逐个校验。
- OWASP 会把属性里的 `=` 编码成 `&#61;`、自闭合写成 `<img ... />`；jsoup 输出更接近原文。

选 jsoup：解析与浏览器一致（旧版正文是原样输出、由浏览器解析的，见第 4 节），已在工程中固定版本，不新增依赖；白名单之外的 URL 规则两者都要自己做。

### 3. 过滤规则（`RichTextSanitizer`）

纯函数：不记录日志、不访问文件/网络/数据库；同一输入同一输出；对任何输入不抛异常（null 视为空）；线程安全。过滤在渲染前完成，失败只会让结果为空，不会产生半页输出。

#### 3.1 流程

1. 空/超长：null、空串、只有空白 → `EMPTY`；超过 100 000 个 UTF-16 单元 → `TOO_LONG`，整段不输出（**不截断**，截断会切开标签或实体）。旧库 `vod_content` 是 MEDIUMTEXT（`application/install/sql/install.sql:756`），列宽不构成限制，上限按第 3.5 节的耗时确定。
2. jsoup 以 `<body>` 为上下文按 HTML5 片段规则解析。字符引用在这一步**按浏览器规则解码一次**。
3. 在解析得到的树上逐个元素判断（一次遍历，`mark`）。整段删除（连同内容）：`script style template noscript noembed noframes xmp plaintext textarea title iframe frame frameset object embed applet param portal fencedframe form input button select option optgroup datalist output fieldset legend label keygen isindex svg math audio video source track canvas map area link meta base head dialog slot`。
4. 同一遍历中校验属性取值与地址（3.3 节）。被拒绝的元素只**改名为标记**（整段删除的先清空子节点），不在兄弟列表里逐个删除：jsoup 每次删除/去标签都要重排其后的兄弟节点，几万个兄弟元素时是平方级（实测 47 500 个未闭合 `<a>` 要 12 秒，改后 0.1 秒）。
5. jsoup `Cleaner` 按白名单一次复制出新文档：标记与白名单外的元素去掉标签、保留文字和子元素（如 `font`、`center`、自定义元素；整段删除的已无子节点），白名单外属性、注释、CDATA、处理指令一律丢弃。
6. 文字与属性值中的控制字符（制表、换行、换页、回车除外）与不成对的代理项替换为 U+FFFD。
7. 序列化：`prettyPrint=false`、UTF-8、`EscapeMode.base`；文本转义 `& < >` 与 `&nbsp;`，属性值一律双引号并转义 `& " < >`；中文原样输出；`<pre>` 第一个文字以换行开头时按 HTML 序列化规范多写一个换行。
8. **稳定化**：按浏览器规则重新解析输出、再序列化，直到不再变化（最多 3 次），再确认只含白名单元素/属性、`href`/`src` 都能通过 `SafeUrl`、没有注释。去掉标签后白名单元素可能落在 HTML 不允许的位置（如 `<table>` 下直接是 `<i>`，浏览器会把它移到表格前），这一步保证浏览器得到的 DOM 就是校验过的结构（`Finding.STRUCTURE_NORMALIZED`）。不收敛或检查不通过 → `UNSTABLE`，整段不输出（防御性分支；修复 `<pre>` 换行问题后，3 000 条测试内随机输入与 200 000 条一次性随机输入中均未出现；表格嵌套超过 jsoup 的 512 层深度上限时会出现，有测试）。
9. 输出超过 500 000 个字符 → `TOO_LONG`。HTML5 的格式元素重建会放大输出（`<p><i>x` 重复时约 5 倍）。

#### 3.2 白名单

| 元素 | 允许的属性 |
|---|---|
| `p br hr div span b strong i em u s strike del ins sub sup small big blockquote q cite pre code h1–h6 ul ol li dl dt dd table caption thead tbody tfoot tr` | 无 |
| `th`、`td` | `colspan`、`rowspan`：`[1-9][0-9]{0,2}` |
| `a` | `href`（3.3）、`title`（≤ 500 字符）、`rel`（只由过滤器写入） |
| `img` | `src`（3.3）、`alt`/`title`（≤ 500 字符）、`width`/`height`：`[1-9][0-9]{0,3}` |

不允许：所有 `on*` 事件属性、`style`、`class`、`id`、`target`、`srcset`、`usemap`、`lang`、`dir`、`data-*` 等其他属性；CSS 一律不支持。

#### 3.3 地址

- **链接 `a[href]`**：去掉首尾 C0 控制字符与空格（WHATWG URL 规则）后必须通过 `SafeUrl`（http/https 绝对地址且有主机名、无用户信息，或以单个 `/` 开头的站内路径；中间出现空白/控制字符/反斜线/引号/尖括号即拒绝）。不通过 → 去掉 `<a>` 标签、保留文字（`LINK_REJECTED`）。因此 `javascript:`（含大小写、`java\tscript:`、`&#106;`、`&colon;` 等混淆）、`vbscript:`、`data:`、`//host`、`/\host`、`\\host`、`ftp:`、`mailto:`、相对地址 `detail.html`、锚点 `#x` 都被拒绝。绝对地址统一 `rel="nofollow noopener noreferrer"`，站内路径不带 `rel`。
- **图片 `img[src]`**：去掉首尾空白；带 scheme 时只接受 `http://`、`https://`、`mac:` 前缀（区分大小写，与 `mac_url_img` 的分支一致），`javascript:`、`data:`、大写 `HTTPS://` 等直接拒绝；然后交给 `ImageUrlNormalizer.normalize`（`mac:` → upload.protocol、相对路径拼站点路径、img_key → img_api、协议相对地址按 base-url 协议补全、`SafeUrl` 校验）。不通过 → **整张图片删除**（`IMAGE_REJECTED`）。
- **只解码一次**：jsoup 给出的属性值已解码过一次，而 `ImageUrlNormalizer` 的输入约定是"放进属性前的库内字符串"、会自己再解码一次。所以先把 `&` 还原为 `&amp;` 再交给它（`asLegacyAttributeText`），`<img src="/a.jpg?x=1&amp;amp;y=2">` 得到 `/a.jpg?x=1&amp;y=2`（与浏览器从旧输出得到的地址相同），不会变成 `x=1&y=2`。
- 输出中不会出现协议相对地址：链接里的被拒绝；图片里的按 base-url 协议补全为绝对地址（与 `vod_pic` 规则一致，`06` 文档第 2 节）。**这一点需队长确认**：如要求正文图片也拒绝协议相对地址，只需在 `checkImage` 中多拒绝一种前缀。

#### 3.4 结果模型

- `Outcome`：`SANITIZED`（结果可能为空，如输入只有脚本）、`EMPTY`、`TOO_LONG`（输入或输出超限）、`UNSTABLE`。后三者 `html` 为 `SafeHtml.EMPTY`；结果只有空白时也为 `SafeHtml.EMPTY`。
- `Finding`（只用于统计/诊断，不含原文）：`DANGEROUS_ELEMENT_REMOVED`、`ELEMENT_UNWRAPPED`、`ATTRIBUTE_REMOVED`、`LINK_REJECTED`、`IMAGE_REJECTED`、`NON_CONTENT_REMOVED`、`STRUCTURE_NORMALIZED`。`ATTRIBUTE_REMOVED` 只计保留下来的元素上删掉的属性；去掉标签或整段删除的元素，属性随之丢弃，不另计。片段中 `<body onload>` 之类的属性由解析器按 HTML5 规则直接忽略，不计入 findings。
- 过滤器与结果都不写日志；`SafeHtml.toString()` 只给长度。页面任务若要记录，只能记录 `Outcome`/`Finding`，不得记录原文、cookie、凭证或 SQL 参数。
- 不是授权、观看密码、版权或播放处理；这些仍按 `05-site-skeleton.md` 第 7 节由页面任务处理。

#### 3.5 耗时（本机 JDK 21，单线程；预热后每种输入 5 次，取中位数/最大值；一次性程序测得，未提交）

| 输入（接近 100 000 字符上限） | 中位数 / 最大 | 其中 jsoup 解析一次 | 结果 |
|---|---|---|---|
| 纯文字、大量 `&amp;`、`<pre>` 加换行 | 8～27 ms / ≤ 41 ms | ≤ 11 ms | SANITIZED |
| 33 000 个未闭合 `<a>`、4 500 个被拒图片、5 800 个 `<script>`、3 900 个被拒链接、5 800 个 `<font>` | 27～67 ms / ≤ 180 ms | ≤ 48 ms | SANITIZED（有测试，上限 2 s） |
| `<div>` 嵌套、`<i><table>` 交错、未闭合 `<b>`、`<li>` | 280～602 ms / ≤ 856 ms | 72～336 ms | SANITIZED |
| `<p><i>x` 重复 | 221 ms / 257 ms | 33 ms | TOO_LONG（输出超限） |
| `<table><td>` 嵌套 | 671 ms / 830 ms | 451 ms | UNSTABLE |

最坏情况不到 1 s/次，主要花在 jsoup 自身的解析与稳定化时的重新解析（正常内容最多解析两次）。详情页接入时应缓存过滤结果（按 vod_id + vod_time），不在每次请求时重算；是否需要更低的上限随页面任务按真实样本决定。

### 4. 实体处理与字段编码约定

#### 4.1 旧版约定（依据）

| 事实 | 依据 |
|---|---|
| 前台模板 `{$var}` 编译为 `<?php echo $var; ?>`，不转义；`default_filter` 为空 | 【代码】`thinkphp/library/think/Template.php:784`、`application/config.php:46`。前台模板文件不在仓库中（`AGENTS.md`"仓库不完整"），各字段在模板里是否另加过滤器**待核实** |
| `vod_content` 原样存库：后台富文本编辑器提交（保存时把图片的 `protocol:` 换回 `mac:`），采集写入资源站的 `des` | 【代码】`application/common/model/Vod.php:651-661`、`application/common/model/Collect.php:231`、后台编辑 `application/admin/view/vod/info.html:377`（`mac_url_content_img`） |
| 采集的非正文字段只 `strip_tags`，不转义 | 【代码】`application/common/model/Collect.php:465-469` |
| 分类等字段保存时经 `mac_filter_xss`（`htmlspecialchars(strip_tags(), ENT_QUOTES)`）存为已转义文本 | 【代码】`application/common/model/Type.php:248-269`、`application/common.php:1222-1225` |
| 评论/留言保存时 `htmlentities`，读出时 `mac_restore_htmlfilter` | 【代码】`application/common/model/Comment.php:44`、`Gbook.php:34`；不在本任务范围 |

结论：不论库内值是原样文本还是 `mac_filter_xss` 转义过的，旧前台都把它原样输出，由**浏览器解码一次**后显示。新版要显示同样的文字，就要在视图模型里解码一次、再交给模板按上下文转义；不解码会把 `Tom &amp; Jerry` 显示成字面 `&amp;`（`04` 报告第 3.4 节），解码两次会把字面 `&lt;` 变成 `<`。

#### 4.2 富文本

正文按 HTML 解析，字符引用由 jsoup 在解析时按浏览器规则解码一次（含文本中不带分号的旧命名引用 `&copy`；属性中后接 `=` 的不解码），序列化时重新转义。显示效果与旧版一致：`&amp;lt;` 仍显示 `&lt;`，`&foo;` 显示 `&foo;`，编码过的 `&lt;script&gt;` 只是文字。已知差异：`&#0;` 被 jsoup 解成 U+0000 后按正文规则忽略（浏览器显示一个 U+FFFD）；解码出的控制字符替换为 U+FFFD。

#### 4.3 普通文本（`LegacyTextEntities.decodeOnce`）

- 只解码**带分号**的完整引用：`&name;`（HTML5 命名引用表，取自 jsoup）、`&#NN;`、`&#xHH;`；0、代理区、超出 U+10FFFF → U+FFFD；0x80～0x9F 按 windows-1252 重映射；解码出的控制字符（制表、换行、换页、回车除外）→ U+FFFD。
- 不带分号的写法（`&copy`、`&amp`、`&#65`）与其他 `&` 原样保留：浏览器会解码其中一部分，新版不解，避免把 `a=1&times=2` 这类合法字面文本改成 `a=1×=2`。这与 PHP `html_entity_decode` 一致。
- 只解一次，结果不再解码；不删除、不解释标签（`<b>` 作为文字，由模板转义后显示为字面文字）。
- **适用字段**（页面任务按此接入，接入时用真实样本复核）：旧模板原样输出的文本字段——`vod_name`、`vod_sub`、`vod_actor`、`vod_director`、`vod_writer`、`vod_blurb`、`vod_remarks`、`vod_area`、`vod_lang`、`vod_year`、`vod_class`、`vod_tag`，以及 `type_name`、`type_title`、`type_key`、`type_des` 等 `mac_filter_xss` 存库的分类字段。
- **不适用**：URL 与图片字段（由 `SafeUrl`/`ImageUrlNormalizer` 处理）、`vod_content` 等富文本（由过滤器解析）、已经解码过的值、站点配置（`maccms.site.*`，按纯文本配置）、新版 API 的 JSON 输出（契约要求原样返回库内值，本任务不改）。

#### 4.4 黄金向量来源

- `entity-vectors.json`：29 条合成输入。`php_filter_xss`、`php_plain`（`trim(strip_tags())`）、`php_decode`（`html_entity_decode(ENT_QUOTES|ENT_HTML5)`）由 `entity_golden.php` 从 `application/common.php` 原样取出 `mac_filter_xss` 执行生成，PHP 7.2.34 / 7.3.33 / 8.0.30 / 8.2.32 结果一致【实测】。Java 断言：`decodeOnce(input) == expect`；expect 与 PHP、浏览器参照（jsoup `Parser.unescapeEntities`，`&#0;`/代理区按规范修正为 U+FFFD）的关系等于 `compat`（SAME 22（含空串）、PHP_ONLY 3、BROWSER_ONLY 3、NEITHER 1）；**往返**：对每条向量 `decodeOnce(php_filter_xss) == php_plain`，即经旧 `mac_filter_xss` 存库的值解码一次恰好还原录入文本，双重编码（`&amp;lt;` → `&lt;`）、字面 `&lt;`、非法实体都不被多解或改写。
- `rich-text-vectors.json`：95 条合成输入，分 8 类：恶意标签 16、事件属性 5、URL 协议混淆 20、图片 15、CSS/嵌入 7、属性转义 8、实体边界 11、结构 13（含随机输入发现的 foster parenting 与 pre 换行回归）。expect 为逐字比较的完整输出（预期值先手写，与实现不一致处逐条分析：4 条是对 HTML5 解析行为的预期有误，已在 note 中写明）；每条另断言安全不变量与稳定性（第 6 节）。

### 5. 与模板、图片、URL 任务的接口边界

- 模板中富文本只能这样输出：页面视图模型持有 `SafeHtml`，在 `site.web.view` 中包成 `gg.jte.Content`（`output -> output.writeContent(safeHtml.html())`），放在**元素内容位置**（如 `<div class="rich-text">${body}</div>`）。适配类随详情页任务实现；`SiteRichTextTemplateTest` 用与正式模板相同的严格站点策略编译测试模板，验证整页输出、过滤结果原样嵌入不被二次转义、标题仍按文本转义。
- 误用时：`Content` 放进属性里，jte 3.2.4 会按属性上下文转义它的输出（实测，不能跳出属性），显示为字面 HTML；放进 `<script>`/`<style>` 已被严格策略禁止。
- `SiteGuardrailsTest` 仍禁止模板引用 `vod_content`、`.content()`、`$unsafe{}`、`@raw`；页面任务只放开"`SafeHtml` 适配后的 Content"，不放开原始字段。
- 图片：过滤器只调用 `ImageUrlNormalizer.normalize`，不修改 `SafeUrl`、`ImageUrlNormalizer`、`SiteSettings`；二者共用同一份 upload 配置（绑定方式随页面任务）。
- 链接：只用 `SafeUrl.parse`；站内链接由 `LegacyUrlBuilder` 生成的规则不受影响（正文中的站内链接按原样校验，不改写）。

### 6. 测试（`./mvnw verify` 中执行，不需要数据库）

| 测试 | 内容 |
|---|---|
| `RichTextSanitizerGoldenTest` | 95 条向量逐字比较输出与 findings；每条输出用 jsoup 按浏览器规则重新解析，断言只含白名单元素/属性、无 `on*`/`style`/注释/脚本数据、`href`/`src` 能通过 `SafeUrl`、外链 `rel` 正确、无控制字符与不成对代理项；再过滤一次不变且无改动；重新解析再序列化不变。8 类与 7 种 Finding 均有覆盖（测试检查）。img_api 前缀与旧 `mac_url_img` 一样不是幂等的（`/img.php?url=https://cdn…` 仍命中 img_key），稳定性检查用去掉 img_key 的同一配置，另有测试固定这一行为 |
| `RichTextSanitizerTest` | null/空/全角空白；恰好上限与超出 1 个字符；9 000 层嵌套不栈溢出；大量兄弟元素被删除时不超过 2 秒；输出超限；超深表格嵌套按 `UNSTABLE` 不输出；不成对代理项 → U+FFFD 且可严格 UTF-8 编码；3 000 条固定种子随机输入（含 `<pre>`、表格、各种危险片段）不抛异常、满足不变量、稳定；200 次并行调用结果一致；合成标记不出现在日志与 `toString()`；`SafeHtml` 无公开构造器；稳定化的失败分支；pom 固定 jsoup 1.23.2 且没有其他过滤库 |
| `LegacyTextEntitiesTest` | 29 条实体向量（含往返）；只解一层；超长/未知名称；`&#0;`～`&#160;` 解码结果不含控制字符 |
| `SiteRichTextTemplateTest` | 第 5 节的模板配合与属性误用 |
| `ModuleBoundaryTest` | site 禁止 jsoup 网络 API（含合成违规样本自检） |

另做过一次性随机测试（未提交）：4 个种子共 200 000 条输入，0 条违反安全不变量、0 条不幂等、0 条 `UNSTABLE`，377 条触发结构调整。这组随机测试先后发现并修复了两个问题：去掉标签后的 foster parenting（第 3.1 节第 8 步）与 `<pre>` 开头换行不收敛。另尝试用 Codex CLI 做独立安全评审，因其内容安全策略中断、未给出结论；它的探测中发现的 12 秒耗时输入已在第 3.1 节第 4 步修复。

**未覆盖**：没有可用的脱敏真实 `vod_content` 样本，向量全部为合成值；真实数据中的编辑器产物（大量 `style`/`class`、`<font>`、表格布局、外链图片域名分布）、实体分布、超长正文比例都未知，接入详情页前应用脱敏样本跑一遍并统计 Finding；未用真实浏览器做 DOM 对比（以 jsoup 的 HTML5 解析为参照）；性能只有第 3.5 节的单次测量，没有并发压测。

### 7. 与旧版的差异

| 场景 | 旧版 | 新版 |
|---|---|---|
| 正文中的脚本、事件属性、`style`、iframe/object/embed、表单、svg/math、`javascript:`/`data:` 链接与图片 | 原样输出（XSS） | 删除或改为文字 |
| CSS（`style` 属性/标签）、`class`、`id`、`target` | 生效 | 删除，正文按站点样式显示 |
| 白名单外元素（`font`、`center`、自定义元素） | 生效 | 去掉标签，保留文字 |
| 相对地址链接（`detail.html`、`#x`）、协议相对链接 | 可点击 | 改为普通文字 |
| 相对路径正文图片（`upload/x.jpg`） | 浏览器按页面地址解析（通常是错误地址） | 按 `mac_url_img` 拼站点路径 |
| 协议相对正文图片 | 浏览器按页面协议补全 | 按 base-url 协议补全（同 3.3 节，待确认） |
| 正文图片 img_key | `mac_url_content_img` 只匹配 `[\w/\-:.]*` 字符的地址并在全文 `str_replace` | 每个 `src` 单独按 `mac_url_img` 规则处理 |
| 正文文字里的 `mac:` | `mac_url_content_img` 全文替换（`imac:` 会变成 `imhttp:`） | 只处理图片地址，文字不变 |
| 超过 100 000 字符的正文，或过滤后超过 500 000 字符 | 输出 | 不输出（`TOO_LONG`） |
| 外链 | 原样 | 加 `rel="nofollow noopener noreferrer"` |
| 普通文本中不带分号的引用（`&copy`、`&#65`） | 浏览器解码 | 原样显示 |
| 普通文本中的标签（`<b>`） | 浏览器当作标签 | 显示为字面文字（采集数据已 `strip_tags`，后台录入的才可能有） |
| 解码出的控制字符、`&#0;` | 浏览器显示控制字符/U+FFFD | U+FFFD / 不显示 |

### 8. 风险与遗留

- 3.3 节协议相对图片的处理需要队长确认。
- 页面任务：`SafeHtml` → `Content` 适配类、详情页视图模型、upload 配置绑定、按 4.3 节为文本字段接入 `decodeOnce`、用脱敏真实样本复核并决定是否需要放宽白名单（如 `style` 中的颜色/对齐，需要单独的 CSS 白名单评审）。
- jsoup 升级时须重跑全部向量与随机测试；序列化细节（转义字符集、`<pre>` 换行）变化会改变输出字节。
- 正文图片较多时，`ImageUrlNormalizer` 对每个地址执行后台配置的 `img_key` 正则；该正则由管理员配置（MACC-11 校验了语法，未限制回溯），与旧版 PCRE 相比没有回溯上限。正文中的图片数量远多于 `vod_pic`，建议页面任务评审是否限制 `img_key` 的写法。
- `SafeUrl` 接受端口号超出 65535 的地址（如 `http://x.test:99999/`，`java.net.URI` 不检查范围）；浏览器视为无效地址、不会跳转，无安全影响，未改动（不属于本任务文件）。
