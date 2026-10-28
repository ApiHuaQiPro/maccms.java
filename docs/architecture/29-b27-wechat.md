## B27：微信公众号入站接口（MACC-50）

依据：路线图 `09-roadmap.md` 第 4.1 节 E12、第 5.4 节 B27 行；B23 的外部调用规则（`26-b23-integration-foundation.md`）；B06 的 api.php 路由规则（`16-b06-misc-pages-provide.md` 第 3.1 节）；D-B15-18（配置改了重启生效）。

### 1. 为什么 B27 先做

B24～B29 的依赖（B23 及各自的前置批次）都已合并。B24、B25、B26、B28、B29 需要前台配合（C + K），并且要发外部请求；B28 的 SSRF 方案、D-B23-a（生产默认出站模式）还没定。B27 只处理入站请求，不发任何外部请求，不登记 `integration` 子包，也不涉及页面和模板。所以它不受这两项待定事项影响，本机就能用 PHP 黄金对比独立验收。

### 2. 范围

旧版 `api.php/wechat`：`application/api/controller/Wechat.php:14-28`、`application/common/util/WechatPublic.php`。

| 旧版行为 | 位置 | 新版 |
|---|---|---|
| `weixin.status == 0` 时输出 `closed` | `Wechat.php:17-20` | 相同 |
| 查询串带 `echostr` 时做接入验证：签名通过输出 `htmlspecialchars(strip_tags(echostr), ENT_QUOTES)`，不通过输出空串 | `WechatPublic.php:12-33` | 相同；签名比较见 D-B27-2 |
| 其他请求读 `php://input`；为空（含 `"0"`）输出 `other msg` | `:35-37`、`:69-72` | 相同 |
| XML 解析失败、缺 `MsgType`：`unknow msg type: ` | `:39-41`、`:63-65` | 相同；带 DOCTYPE 的正文见 D-B27-3 |
| 文本消息：4 组关键词 → 视频搜索 → 没找到；`msgtype=1` 回复文本（最多 7 条），否则回复图文（1 条）；`bofang` 为 2 给搜索页、大于 0 给播放页、其余给详情页 | `:85-171` | 相同；搜索见 D-B27-5 |
| 事件：关注（`guanzhu`）、取消关注、菜单点击（内容为空）、其他事件 | `:173-196` | 相同 |
| 链接消息 | `:79-83` | 相同 |
| 图片、语音、位置、视频消息：调用不存在的方法，整页 500 | `:46-57` | 见 D-B27-4 |
| 消息回复不校验签名 | — | 相同（路线图 E12 已记录） |

只读：不写任何表，不改表结构，不发出站请求，不改旧 PHP，不涉及后台页面、前台模板和样式。

### 3. 接口与装配

- **路由**：`/api.php/wechat`、`/api.php/wechat/index`，可带 `.html/.htm/.shtm/.shtml/.xml` 后缀，后面的路径参数忽略（旧控制器不读）；GET、HEAD、POST。其他操作（如 `/api.php/wechat/other`、大写的 `Index`）返回 404。路径解码规则同 Provide（`WechatController`）。
- **请求**：先读原始正文（上限 1 MiB，超过返回 413），查询串按 PHP `$_GET` 解析（`PhpInput.parseQuery`）。不经 `getParameterMap`，所以表单类型的正文也原样交给消息解析，与旧版读 `php://input` 一致。
- **响应**：`text/html; charset=utf-8`，正文与旧版逐字节相同（例外见第 5 节）。另加 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`（D-B27-7）。
- **装配**：放在 `openapi` 模块，和 Provide 一样受 `maccms.openapi.enabled` 控制（`OpenApiConfiguration`）。
  - 设置了 `maccms.legacy-config.dir` 时，启动时读一次 `extra/maccms.php` 的 `weixin`、`rewrite.*`、`app.pathinfo_depr`、`upload.protocol/img_key/img_api`（`WechatSettings`），改了要重启（D-B15-18）。
  - 没设置时接口关闭，输出 `closed`。
  - `WechatSettings.toString()` 不含 `token`。
  - 没有直接用 B23 的 `ThirdPartyConfig`：这个接口还要读 `rewrite`、`upload` 两组，测试也要像 Provide 那样按场景重放多组旧配置（`WechatSettingsSource`）。`token` 只用于本地计算签名，不进日志，也不随请求发出。
- **链接**：用 site 模块的 `LegacyUrlBuilder`。它是纯计算类，跨模块引用 `site.application` 符合 `ModuleBoundaryTest`。前缀为 `sousuo + "/"`，因为旧站 `MAC_PATH` 固定为 `/`（11 文档第 4.4 节）。如果 `rewrite` 组合不受支持，按仓库默认形态生成，启动时告警。

### 4. 实现要点

#### 4.1 接入验证（`WechatService.valid`）

签名按 `sha1(implode(sort([token, timestamp, nonce])))` 计算，旧版和新版都一样。`signature`、`timestamp`、`nonce` 缺任一项，或其中有数组参数，都判为不通过。`echostr` 是数组时输出空串。

#### 4.2 宽松比较

旧代码按 PHP 7.3 的宽松比较读配置（`WechatPhp`）：

- `status == 0`：`"abc"`、`""` 视为 0，所以关闭；`"1abc"` 视为 1。
- `bofang == 2`、`bofang > 0`、`msgtype != 1`：取字符串前导数字部分的数值。
- `gjcN <> ''`、`strstr`：按字节。
- 关键词按 1→4 的顺序取第一个命中的。

#### 4.3 消息 XML（`WechatMessage`）

字段值取根元素下第一个同名子元素的直接文本，文本节点和 CDATA 段按顺序拼接，等价于 `simplexml_load_string(..., LIBXML_NOCDATA)` 再按 `(string)` 取值。

`MsgType` 和 `Content` 按 PHP `trim`；`Event`、`FromUserName`、`ToUserName` 原样使用。

解析器的安全设置：

- 禁止 DTD（`disallow-doctype-decl`）。
- 关闭外部通用实体、外部参数实体和外部 DTD 加载。
- `ACCESS_EXTERNAL_DTD`、`ACCESS_EXTERNAL_SCHEMA` 设为空。
- 关闭 XInclude，打开 `FEATURE_SECURE_PROCESSING`。
- 实体解析器一律拒绝。

#### 4.4 视频搜索（`WechatService.search`）

等价旧 `Vod::listCacheData(['wd'=>$content,'num'=>1|7])`。走 api 入口时，旧版不加会员组的分类过滤（只有 index 入口加）。

查询分两步：

1. 先用 `ContentReadService.listVideos` 按关键字找出视频编号，按 `vod_time` 倒序取前 num 条。条件与 content API 的 `keyword` 相同：名称、英文名、副名包含匹配；受搜索开关、并发许可和执行时限约束；`vod_search_optimise` 照常生效。关键字为空时不加条件，列出最新的视频。
2. 再用 `LegacyRowReadService` 按编号取整行（`vod_name/vod_remarks/vod_pic/vod_content`），按第一步的顺序输出。

回复里各项的取法：

- 图片：以 `http` 或 `mac:` 开头的地址按 `mac_url_img` 处理（`mac:` 换成协议、`img_key`/`img_api`、`mac_filter_xss`、`&amp;` 还原）；其余地址前面加 `sousuo + "/"`，与旧版相同。
- 简介：`mac_substring(strip_tags(vod_content), 20)`，按 PHP 7.3 的结果（`PhpText`；D-B13-5）。

#### 4.5 回复模板

回复模板和 `transmitText`、`transmitNews` 的空白一致（行首缩进分别是 12、8 个空格）。`CreateTime` 是当前秒数。标题、链接等字段原样放进 CDATA，和旧版一样不转义。

### 5. 与旧版的差异

| 编号 | 旧版 | 新版 | 理由 |
|---|---|---|---|
| D-B27-1 | 模板里的字面换行跟旧文件的换行一致：本机 `autocrlf=true` 检出是 CRLF，仓库里存的是 LF | 用 LF（仓库保存的形式） | 生产文件的换行无法从仓库确定（同 MACC-56 方案 A 的处理）。微信按 XML 解析，换行不影响结果。黄金对比时把 CRLF 换成 LF 再比较 |
| D-B27-2 | 签名用 `==` 比较：两边都是数字串（如 `0e…` 魔术哈希）时按数值比较。排序只用 `sort()` 的默认规则：时间戳和随机数都是数字串时按数值排序，与微信文档的字典序在位数不同时结果不同（如 10 位时间戳与 9 位随机数） | 逐字节比较。旧版排序和字典序算出的签名都接受 | 去掉魔术哈希绕过；微信按字典序计算签名，旧版在部分请求上会把合法签名判错。新版接受的签名都必须由持有 token 的一方算出 |
| D-B27-3 | 正文带 DOCTYPE 时，内部实体被展开（`&e;` → 关键词回复），外部实体被丢弃成空串后继续处理（外部实体加载已用 `libxml_disable_entity_loader` 关闭） | 带 DOCTYPE 的正文一律按解析失败处理，输出 `unknow msg type: ` | 不展开任何实体（XXE、实体膨胀）。微信推送的消息不带 DOCTYPE |
| D-B27-4 | 图片、语音、位置、视频消息调用不存在的方法，整页 500，页面上显示异常信息 | 500，正文为空 | 不在响应里暴露类名和方法名。微信得到的结果和旧版一样（服务出错） |
| D-B27-5 | `vod_name\|vod_en\|vod_sub LIKE '%wd%'`，`%`、`_` 是通配符；不按分类可见性过滤；不受搜索开关限制 | 1. 按字面匹配；2. 只在可见分类中查（与 content API 相同，游客会员组）；3. 视频设了访问密码时简介为空（`vod_content` 不读出）；4. **`maccms.content.search.enabled` 关闭（默认关闭）时，除空关键字外都回复"没找到"**；关键字超过 64 个字符或含不允许的字符时同样回复"没找到"；数据库不可用或搜索并发用尽时返回 503 | 复用 content 模块的成本护栏与访问规则，不另写 SQL。上线前如果要让公众号能搜到片子，需要按 09 方案第 6.5 节确认并打开搜索开关 |
| D-B27-6 | `bofang=2` 时，搜索页链接直接由 `mac_url` 生成 | 关键字生成不了合法路径（如 `suffix_hide=1` 时的 `..`）时，退回不带关键字的搜索页 | `LegacyUrlBuilder` 拒绝不规范路径 |
| D-B27-7 | 响应头只有 `Content-Type`；正文超长不限 | 另加 `no-store`、`nosniff`、CSP `default-src 'none'`；正文超过 1 MiB 返回 413 | 回复会回显请求里的字段，内容类型是 `text/html`，旧版在浏览器里存在反射型脚本注入的可能。CSP 不改变正文，但能阻止脚本执行 |
| D-B27-8 | 每次请求读配置 | 启动时读一次，改了重启 | D-B15-18 |
| D-B27-9 | `vod_time` 相同时顺序不确定 | 再按编号倒序（content 模块的统一规则） | 02 规范第 3 节 |

另外，新版 `Content-Type` 参数前统一有一个空格。旧版用 `exit` 结束的分支没有空格，Tomcat 会规范化，B19 已有同样的记录。

### 6. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金 | `backend/src/test/php/wechat_golden.php`：经 `legacy_http_child.php`（api 入口）用 php-cgi 跑完整的 ThinkPHP 路由、`Wechat::index` 和 `WechatPublic`，旧代码不改。库是一次性库 `maccms_it_*`（3 个分类、12 部视频）。**12 个场景、58 步**：关闭；接入验证（正确、三种路径写法、`echostr` 含标签和引号、空 `echostr`、签名错误、缺参数、签名大写、POST 带 `echostr`、token 为空）；空正文；关键词 1～4（含 trim、多个命中取第一个、空关键词跳过）；搜索命中名称、英文名、副名；搜索未命中、空关键字、`%`；关注、取消关注、菜单点击、VIEW 事件、空欢迎语；链接；未知类型；图片、语音、位置、视频；缺 `MsgType`；非 CDATA 节点和 `&amp;`；拆开的 CDATA；不合法 XML；非 XML；内部实体；外部实体；参数实体；表单类型正文；`msgtype=1`；`bofang` 为 1 和 2（图文、文本）；`sousuo` 带 https；`rewrite` 开启。每步记录状态码、Content-Type、正文、请求前后时间，以及 `mac_vod` 是否不变。输出 `backend/src/test/resources/openapi/wechat/php73-golden.json`、`php80-golden.json`。两份除 `CreateTime` 外完全相同。外部实体文件里的标记在两份黄金中都没有出现 |
| 新版复放 | `WechatGoldenMySqlIT.repliesMatchPhpGoldenAndNothingIsWritten`（`-Pmysql-it`）：用真实 HTTP 启动应用，打开 openapi 和搜索，按场景重放旧配置。50 步的状态码与正文和 PHP 7.3、8.0 逐字节一致（CRLF 换成 LF；`CreateTime` 须落在新版请求的时间窗内）。8 步是登记差异，逐条断言新版行为：D-B27-3 三步、D-B27-4 四步、D-B27-5 一步。全部请求前后所有表的 `CHECKSUM TABLE` 不变；表结构不变。变异检查：把文本回复的序号从 `k + 1` 改成 `k + 2`，该测试在 3 个场景、两个 PHP 版本上都报出差异 |
| 路由与上限 | `WechatGoldenMySqlIT.routesAndBodyLimit`：`other`、`Index`、`wechatx` 返回 404；`/wechat/`、`/wechat.html`、`/wechat/index/a/b/` 正常；超过 1 MiB 返回 413；表单类型的 XML 正文照常解析 |
| 单元测试 | `WechatServiceTest`（10 个）：两种排序的签名都接受；签名大写、`0e1`、缺参数、数组参数都不通过；`echostr` 的 strip_tags 和转义；关闭、空正文、`"0"`、不合法 XML；关键词回复不查库；搜索被拒时回复"没找到"，并核对查询条件；503 向上抛出；四种消息返回 500 空正文；`mac_url_img`；配置的宽松比较、`sousuo` 补协议、`toString` 不含 token。`WechatMessageTest`（3 个）：SimpleXML 取值规则；外部实体、参数实体、外部 DTD、内部实体、实体膨胀一律拒绝；不合法 XML 和空正文 |
| 既有测试 | `ProvideApiTest`（WebMvc 切片）加一个 `ContentReadService` 模拟 Bean：`OpenApiConfiguration` 现在也装配公众号服务，没有改 Provide 的任何断言 |
| 护栏 | `ModuleBoundaryTest`、`OutboundNetworkGuardTest`（没有新增出站 API，也没有登记子包）、B23 测试期出站护栏（全量 verify） |

公众号测试号联调：账号还没有到位，记为"待联调"，转入 B21 清单（第 4.3 节 ②）。联调要核对：接入验证能否通过（含 D-B27-2 的字典序）；关注回复、关键词回复、搜索回复在微信客户端里的显示。

### 7. 已知风险

- **消息回复不校验签名**：与旧版相同（路线图第 4.1 节 E12、第 9 节 B27 行），任何人都可以伪造消息，借这个接口查询站内公开的视频名称、简介和链接。这些内容前台本来就公开，接口也不写库。如果要校验 `msg_signature` 或加白名单，属于功能调整，需要队长决定。
- 关键字搜索依赖 `maccms.content.search.enabled`（D-B27-5）。默认关闭时，公众号只能回复关键词、关注语和"没找到"。
- 回复把标题、链接等配置原样放进 CDATA，配置里含 `]]>` 时 XML 会被截断，与旧版相同。

### 8. 回滚

本批只新增 `openapi` 模块里的类和测试资源，没有改表，没有改配置项。回滚就是在 main 上撤销本批的合并提交，回滚点见路线图 B27 行。回滚后 `/api.php/wechat` 回到旧 PHP 处理（切换方案 13 文档）。
