## B19 采集入库与 Receive 入站接口（路线图 B19，MACC-43）

> **状态：增量 1（视频入库核心 + Receive `vod`）交付待验收；增量 2、3 待建（第 1 节）。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B19 行、第 5.3 节 B19 验收行（影子合并对比、并发提交同一条视频不重复、Receive 校验、回滚演练）、E01/E02 行（只做本地版本，不发请求、不下载图片）、第 1.2 节（零结构变更）；
> - B07 `12-write-infrastructure.md`（可写数据源、写入登记、回滚演练工具）、B13 `22-b13-admin-content.md`（`formatDataBeforeDb`、`mac_substring` 的 D-B13-5、严格模式报错的 D-B13-3）、B11 `20-b11-comment-gbook-counters.md`（视频搜索缓存）、B18 `26-b18-collect-preview.md`（解析结果与值的表示）。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `backend/…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | main `b673c71`（含 B18 合并 `39b9902`）；平台基线提交 `0f8ada9` 的树与它相同 |
| 分支 | `agent/claude/macc-23`（本任务的工作分支），只用普通提交 |
| 回滚点 | main `b673c71` |

### 1. 范围与增量

B19 的旧代码是 `common/model/Collect.php` 的六个 `{种类}_data`（约 1 500 行）、`api/controller/Receive.php` 六个入口，以及后台 `collect/api` 的入库动作与断点续采。按种类分三次交付，每次都带影子对比：

| 增量 | 内容 | 状态 |
|---|---|---|
| **1** | 视频入库核心（`vod_data`，`Collect.php:419-1015`）：过滤、`strip_tags`、拼音、`inrule` 查重（含 `vod_search_optimise` 的主演结果编号）、新增、`uprule` a～w 更新、播放组与下载组的追加/替换/`urlrole` 合并去重、`opt`/`filter`/`filter_from`、随机点击顶踩评分、伪原创替换、搜索缓存追加；入库锁；Receive 构造函数三道检查与 `vod`、`index` | **本次交付** |
| 2 | 文章、演员、角色、网址、评论的 `*_data` 与对应的 Receive 入口（`art/actor/role/website/comment`，现在返回 501"由 B19 后续增量提供"） | 待建 |
| 3 | 后台 `collect/api` 的 `cj`/`cjsel`/`cjday`/`cjall`（从 B18 的预览缓存入库，`show=1` 的逐行输出与跳页）与 `collect/load` 断点续采（现在仍是 501"由 B19 批次提供"） | 待建 |

| 不在本批 | 归属 |
|---|---|
| 远程图片下载（`syncImages` 的 `down_load`） | 不实施（E02）：库内图片地址保持提交的值，相当于 `sync_pic_opt` 关闭 |
| 在线抓取资源站 | 不实施（E01） |
| 自定义规则采集 | B20 |

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| `collect.application` | `VodIngest`（一行的入库规则，纯计算，经 `Store` 查重与写入）、`IngestPhp`（旧 PHP 函数：`mac_format_text`、`mac_txt_merge`、`mac_txt_explain`、`mac_rep_pse_syn/rnd`、`mac_array_filter`、`mac_like_arr`、`mac_substring`（含起点）与 PHP 8 的宽松比较、`intval`、`floatval`）、`CollectIngestService`（入库锁、配置读取、`Store` 的实现、列值换算）、`ReceiveService`（Receive 的检查、参数整理、分类名映射、JSON 输出） |
| `collect.infrastructure` | `CollectIngestMapper`（查重，`mapper/collect/CollectIngestMapper.xml`）、`CollectIngestWriteMapper` + `CollectIngestWrites`（`mac_vod` 新增与修改，`mapper-write/collect/CollectIngestWriteMapper.xml`，列清单同 B13） |
| `collect.api` | `ReceiveController`：`/api.php/receive/<操作>`，可写数据源打开时注册 |
| 复用 | content：`LegacyPinyin`、`PhpText`（`strip_tags`、`mac_filter_xss`、`mb_substr`、`urldecode`）、`PhpIntval`、`AdminRowValues`/`AdminTable`（列值规则同 B13）、`VodSearchCache`（主演结果编号与新增视频追加，同 B11/B13）、分类缓存（`type_pid` 与分类名 → 编号）；common：`PhpInput`（按字节解析 GET/POST）、`LegacyJson`、`LegacyLang`；legacyconfig：`maccms.php`、`vodplayer.php`、`voddowner.php` |

- **写入登记**（`WriteRegistry`）：`collect / mac_vod / INSERT、UPDATE / B19`；不登记 DELETE。搜索缓存的写入沿用 content 已登记的 `mac_vod_search`。
- 依赖方向符合 `ModuleBoundaryTest`：collect 只引用 content、common、legacyconfig 的 `application` 公开类型与 common 的支持类。

### 3. 入库规则（`VodIngest`，与 `vod_data` 逐步对应）

1. **跳过**：`type_id == 0` → `type_err`；`vod_name` 为空 → `name_err`；名称含过滤词（`collect.vod.filter`）→ `name_in_filter_err`。
2. **整理**：去掉 `vod_id`；除键名含 `_content` 与 `vod_plot_detail` 外每个字段 `strip_tags`；`type_id_1` 取本站分类的 `type_pid`；`vod_en` 拼音、`vod_letter` 首字母（提交的值不起作用）；`vod_time_add` 不是 10 位时取当前时间，`vod_time_update` 是 10 位时作 `vod_time`；`vod_status` 一律取配置（提交的不起作用，照旧）；整数字段 `intval`、`vod_score` `floatval`；`vod_class` 与 `type_name` 合并（`class_filter` 打开时去掉"片""剧"）；主演、导演、扩展分类、标签 `mac_format_text`；剧情名与详情去掉两端的 `$`；随机点击、顶踩、评分；伪原创（名称、正文随机词、同义词、播放器、地区、语言）；简介为空时取正文 `mac_substring(strip_tags(正文), 100)`。
3. **查重**（`inrule`）：名称 `mac_filter_xss` 后相等，另按 b 分类、c 年份、d 地区、e 语言相等；f 主演 `LIKE` 任一（逗号拆开，不转义通配符）；g 导演相等。f 与 g 同时参与且导演非空时变成 `(导演相等 OR 主演条件)`，开启 `vod_search_optimise` 的 collect 时主演条件换成搜索缓存的结果编号（不超过 1000 个时）。不排序取第一行。
4. **播放组与下载组**：按 `$$$` 拆开，类型为空或不在播放器（下载器）配置里的组整组去掉；播放地址去掉末尾 `#`、下载地址去掉末尾空白；`filter > 0` 时类型出现在 `,filter_from,` 里（子串匹配）的组另记一份。
5. **新增**：`opt == 2` → `not_check_add`；`filter` 为 1 或 2 时只写另记的那份；`formatDataBeforeDb`（列出的字段 `mac_filter_xss` 后按字符数截断）；写库；开启 collect 搜索优化时把新视频追加进已有的搜索结果。
6. **更新**：`uprule` 为空 → `uprule_empty`；锁定 → `data_lock`；`opt == 1` → `not_check_update`；否则 a 播放组、b 下载组（`filter` 为 1 或 3 时用另记的那份；新类型追加，同类型地址不同时按 `urlrole` 替换或合并去重，说明文字逐组累加）、c 连载（都是整数时取较大值）、d～w 各字段（非空且与库里不同才更新，n 扩展分类与库里的合并，j 图片只在库里是 http 开头或为空时更新）。有更新时写 `vod_time` 并 `formatDataBeforeDb`；没有时 `not_need_update`。
7. **结果**：绿色（新增、有更新）为 `code=1`，否则 `1001`；`msg` 是旧版的说明文字。

比较一律按 PHP 8 的宽松比较（数字串按数值，`"1e1" == "10"`），与 B18 相同。

### 4. 入库锁、查重与写入（`CollectIngestService`）

- **入库串行**：每一行的"查重 → 新增或更新"在进程内的公平锁里执行；等锁超过 30 秒时放弃本行（Receive 返回 503 与"入库排队超时，请稍后重试"）。所以同一部视频的并发提交只会新增一次，其余按更新规则处理（`ReceiveGoldenMySqlIT` ②）。旧版没有锁，并发时可能写出重复行（D-B19-2）。
- **查重**走只读连接池（自动提交，读到已提交的写入）；**写入**走可写数据源（B07，自动提交、不开事务）。
- **列值**：只写 `mac_vod` 里有的列（旧版 `fields_strict=false` 静默丢弃其他键，如 `pass`、`type_name`）；值按 PHP 字符串转换后经 B13 的 `AdminRowValues`（数值列空串写 0；超长、非法 UTF-8、4 字节字符拒绝）。MySQL 严格模式下的数据错误（如缺 `vod_content`，见第 8 节）换成"参数错误"，不写库。

### 5. Receive 入站接口（`/api.php/receive/<操作>`）

| 步骤 | 规则 |
|---|---|
| 路由 | 同 Provide（B06）：路径解码一次，去掉 `.html` 等后缀与首尾 `/`，`/键/值` 为路径参数；操作名只认小写；GET/HEAD/POST；POST 只认 urlencoded 正文（上限 2MB，超过 413）。可写数据源关闭时不注册（404） |
| 参数 | ThinkPHP `input('', '', 'trim,urldecode')`：GET → POST → 路径参数合并（后者覆盖前者），每个值先 `trim` 再 `urldecode`（所以 `%2541` 最终是 `A`），值整段是查询表达式关键字（`LIKE`、`not in`、`Null` 等，不区分大小写）时末尾补一个空格（`Request::filterExp`） |
| 检查 | 顺序不变：`interface.status != 1` → 3001；`interface.pass != pass`（宽松比较，`1e15` 等于 `1000000000000000`）→ 3002；`strlen(interface.pass) < 16` → 3003 |
| `vod` | 名称为空 → 2001；`type_id` 与 `type_name` 都为空 → 2002；`type_id` 为空时按 `interface.vodtype` 映射（每行 `本站分类名=接口分类名`，换成本站分类编号，没有该分类时 0）；然后按第 3 节入库一行（`$param = []`） |
| `index` | 空白（200） |
| `art/actor/role/website/comment` | 501"由 B19 后续增量提供"（增量 2） |
| 响应 | `text/html; charset=utf-8`，正文 `{"code":…,"msg":"…"}`（`json_encode(…, JSON_UNESCAPED_UNICODE)`，`LegacyJson`） |

### 6. 配置

不新增配置项。读取的旧配置：`maccms.php` 的 `interface`（`status`、`pass`、`vodtype`）、`collect.vod`（第 3 节各项）、`app.lang`、`app.vod_search_optimise`；`vodplayer.php`、`voddowner.php` 的键。

### 7. PHP 与 Java 的影子对比（黄金）

- `backend/src/test/php/receive_golden.php` 在 PHP 8.0.30 与 7.3.33 上，经 `legacy_http_child.php`（api 入口）以 php-cgi 跑**完整的 ThinkPHP 路由、`Receive` 控制器与旧 `Collect::vod_data`**（不改旧代码），对一次性库 `maccms_it_*` 发出真实请求；每个场景先清空 `mac_vod`、`mac_vod_search` 并复位自增、写入种子行，用自己的配置与运行目录。记录每一步的状态码、Content-Type、正文、两张表的全部行（十六进制）与请求前后的时间。
- **22 个场景、42 步**：接口关闭、密码错误与缺失、密码数值相等、密码太短与为空、名称与分类必填、分类名映射（已映射、未映射、本站没有该分类、`type_id=0`）、缺 `vod_content`、`index`；整行新增（`strip_tags`、拼音、扩展分类合并、主演分隔符、未知播放器与空类型的组、地址末尾 `#`、下载地址末尾空白、剧情 `$$$`、时间字段、提交的 `vod_status`/`vod_letter`/`vod_id` 被忽略、多余字段）；过滤词；伪原创名称；播放组"地址相同 + 新增组"、同类型替换、同组无需更新、没有播放组；`urlrole=1` 合并去重与下载组新增；`uprule` a～w 全部字段（含连载取较大值、库里是本地图片时不更新图片、扩展分类合并）；锁定；`uprule` 为空；无需更新；`inrule` 主演导演混合匹配的四种情况；`inrule` 分类、年份、地区、语言；路径参数与查询串（双重解码）；`trim` 与查询表达式补空格；`<script>` 名称与 iframe/link 播放器；`vod_search_optimise=collect` 的主演结果编号查重与新增后追加；随机点击顶踩评分。
- 两份黄金互相比较：除 PHP 8 的 `mac_substring` 把 ASCII 字符换成空格（简介由纯 ASCII 正文生成的 4 行，B13 已登记为 D-B13-5，新版按 7.3）外完全相同。

### 8. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| 影子合并对比 | `ReceiveGoldenMySqlIT` ①：以真实 HTTP 启动应用，逐场景写入同样的旧配置与种子，发送同样的请求；状态码、正文与 PHP 8.0、7.3 逐字节相同（Content-Type 只差参数前的空格，Tomcat 统一去掉），两张表逐行逐列相同（写入时刻按各自的请求时间窗比较，随机列按旧版范围比较；8.0 的简介按 D-B13-5 取 7.3 的值）；登记差异 `require/no-content` 断言新版行为。变异检查：故意改坏播放组追加，该测试即报出 4 处行差异 |
| 入库排队 | `ReceiveGoldenMySqlIT` ②：16 个并发请求提交同一部新视频，只有 1 个"新加入库"，其余 15 个"播放组(dplayer)，更新ok。"，表里只有 1 行 |
| 回滚演练 | `ReceiveGoldenMySqlIT` ③：快照 → 场景 update-fields 的 3 次更新与 1 次新增 → `DmlRestore` 只用 DML 恢复 → 与快照逐行 diff 为空 → 表结构指纹与导入时相同 |
| 规则分支与旧函数 | `VodIngestTest`：`opt`、`filter` 1/2/3 与 `filter_from` 子串匹配、随机项范围、搜索优化的结果编号与追加、`tag=1`、连载规则与 D-B19-4、数组字段拒绝、旧 PHP 文本函数、`mac_rep_pse_rnd` 的 crc32 选词、Receive 参数整理、分类名映射 |
| 护栏 | `WriteGuardTest`、`WriteRegistryTest`（登记 95 项）、`WriteInfrastructureMySqlIT`（B19 只允许 collect 对 mac_vod 的新增与修改）、`ModuleBoundaryTest`、`ReadOnlyGuardrailsTest`、`OutboundNetworkGuardTest`、`SchemaGuardTest`、`CollectPreviewGuardTest` |

### 9. 风险与遗留

| 项 | 说明 |
|---|---|
| 入库锁是进程内的 | 多实例部署时各实例的锁互不相干；Receive 与后台入库要落在同一实例（与 B18 的预览缓存相同的部署约束）。需要多实例时改成数据库命名锁，届时在 B21 演练 |
| 严格模式 | 旧表 `vod_content` 等 TEXT 列没有默认值，严格模式下缺这些列的新增失败（旧版 500，新版"参数错误"）。生产库的 `sql_mode` 未核实（D9-b）；若生产不是严格模式，旧版会写入空值，新版仍拒绝——B21 演练时核对 |
| 增量 2、3 | 其他五个种类与后台入库按钮、断点续采尚未实现，入口返回 501 |
| 图片同步 | 不下载（E02）；`pic=1` 时旧版会下载失败并保留原地址（本机无网络时的结果与新版相同），能下载时旧版换成本地地址，新版不换 |

### 10. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B19-1 | 图片同步打开时下载远程图片 | 不下载，保持提交的地址 | E02（只做本地版本） |
| D-B19-2 | 查重与写入之间没有锁，并发提交同一部视频可能写出重复行 | 入库锁内串行，只新增一次 | 路线图"入库串行"、验收"并发不重复" |
| D-B19-3 | 数组参数、数组字段（PHP 8 类型错误）、MySQL 严格模式下的数据错误（如缺 `vod_content`）：整页 500 | 不写库，`{"code":1001,"msg":"参数错误"}`；参数含非法 UTF-8 同样处理 | 同 D-B13-3 |
| D-B19-4 | 更新连载时库里的值不是数字串：PHP 8 `floor()` 类型错误（500），PHP 7 当作非整数 | 按 PHP 7 的结果写提交的值 | 健壮性 |
| D-B19-5 | 新增时 `filter` 为 1/2 而没有一组匹配：PHP 8 `join(null)` 类型错误（500），PHP 7 写空串 | 四列写空串 | 健壮性 |
| D-B19-6 | `mac_txt_explain` 用 `html_entity_decode` 解码全部 HTML 实体 | 只解码五个基本实体与数字实体，其他命名实体原样保留 | 伪原创词表里实际只用到这几种；完整实体表收益小 |
| D-B19-7 | `mac_interface_type` 的第一步结果放在缓存里，改配置后要等缓存失效 | 每次按当前配置计算 | 配置改动立即生效 |
| D-B19-8 | Receive 正文没有大小上限 | urlencoded 正文上限 2MB（413） | 资源边界 |
| — | `mac_substring` 在 PHP 8 把 ASCII 字符换成空格 | 按 PHP 7.3 | 沿用 D-B13-5 |
| — | Content-Type `text/html;charset=utf-8` 与 `text/html; charset=utf-8` 两种写法 | 一律 `text/html;charset=utf-8`（Tomcat 输出） | 语义相同 |

### 11. 回滚

- **代码**：本增量以普通提交进入 main；出问题时 `git revert` 这些提交，回到 `b673c71` 的行为（`/api.php/receive/*` 404；`WriteRegistry` 去掉 collect 对 mac_vod 的两项）。不涉及表结构与配置项。
- **数据**：入库只有对现有列的 DML；回滚演练（第 8 节 ③）证明一批入库可以用 `DmlRestore` 只用 DML 恢复。生产切换时按 13 文档在停止写入后取快照。
- **开关**：Receive 本身受旧配置 `interface.status` 控制，关闭即 3001、不写库。
