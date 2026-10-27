## B18 本地文件源采集解析与预览（路线图 B18，MACC-42）

> **状态：交付待验收。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B18 行、第 5.3 节 B18 验收行（同一份文件经 PHP 与 Java 解析得到的字段一致；JSON 路径的取数方式在本批文档说明；畸形文件、超大文件被拒绝；回滚为"删除本地缓存"）、E01 行（2026-10-16 用户决定：只做本地版本，不发起出站请求）、第 1.2 节（零结构变更）；
> - B17 `25-b17-collect-config.md`（分类绑定键 `CollectBindings`、`bind.php` 只经 `CollectBindService`、采集批次不发请求）、B12 `21-b12-admin-framework.md`（后台入口、页面与脚本策略）；
> - 用户决定（2026-10-17）：后台复用旧后台 HTML 结构与静态资产，转成 jte，不做新视觉设计。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `backend/…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | main `94e5f7b`（含 B17 的 `9779fcd`、`94e5f7b`；平台基线提交 `38a70e6` 的树与它相同） |
| 分支 | `agent/claude/macc-42`，只用普通提交，不 reset、rebase、amend、force，不自行合并 main |
| 回滚点 | main `94e5f7b05d8bcf74cc631950f15ebde4603e19e2` |
| 提交 | 见交付评论 |

### 1. 范围

| 旧代码 | 本批实现 |
|---|---|
| `common/model/Collect.php` 的 `vod`（`:85-102`）、`vod_xml`（`:140-291`，含 `vod_xml_replace` `:125-138`）、`vod_json`（`:293-385`）、`art_json`（`:1051-1124`）、`actor_json`（`:1379-1451`）、`role_json`（`:1671-1723`）、`website_json`（`:1970-2042`）、`comment_json`（`:2260-2312`） | 同样的解析，内容来自本地文件（第 3 节） |
| 各 `{type}_data` 循环开头的跳过条件（`:452-460`、`:1150-1158`、`:1474-1482`、`:1746-1751`、`:2066-2074`、`:2335-2340`） | 预览时提前给出"入库时会被跳过"的原因（第 3.4 节）；入库本身在 B19 |
| `admin/controller/Collect.php` 的 `api`（`:140-171`）与 `vod/art/actor/role/website` 的 `ac=list` 分支 | `collect/api?ac=list` 预览页；新增 `ac=localload`、`ac=localclear`（第 5 节） |
| `admin/view/collect/{vod,art,actor,role,website}.html` | `backend/src/main/jte-admin/admin/pages/collect/api.jte` + `static/admin-assets/collect-api.js`（第 6 节） |

| 不在本批 | 归属 |
|---|---|
| `collect/api` 的其他 `ac`（`cj`、`cjsel`、`cjday`、`cjall` 等，即入库）与断点续采 `collect/load` | B19（断点只在入库时记下，`AdminPermissions.ACTION_BATCH` 的 `collect/load` 由 B18 改为 B19，D-B18-9） |
| 写库、`mac_vod_search`、图片同步 | B19 |
| 在线抓取资源站 | 不实施（E01） |

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| `collect.application` | `ProvideParser`（解析、绑定换算、入库预检）、`ProvideDocument`（与绑定无关、可缓存的解析结果）、`ProvideKind`（种类与 `mid` 分派）、`ProvideJson`（PHP `json_decode` 等价解码 + 缓存编码）、`ProvideXml`（SimpleXML 子集，拒绝 DOCTYPE）、`ProvideValues`（PHP 值语义）、`CollectPreviewService`（载入、预览、清除）；`CollectAdminServices` 增加 `preview` |
| `collect.infrastructure` | `FileProvidePreviewStore`（预览缓存文件）、`DirectoryProvideSourceFiles`（本地目录）、`CollectLocalProperties`（`maccms.collect.local.*`）；在 `CollectInfrastructureConfiguration` 里装配（可写数据源打开时，同 B17） |
| `admin.web` | `AdminCollectActions` 处理 `collect/api`；视图 `view/collect/CollectApiView` |

- **不写库、不联网**：上表 B18 的类不引用任何 Mapper、写入、JDBC 或网络类型（`CollectPreviewGuardTest`）；分类名经 B17 的 `TypeNames`（content 的分类缓存，只读），绑定经 `CollectBindService.bindings()`（读 `bind.php`），过滤词经 `LegacyConfigService` 读 `maccms.php`。`OutboundNetworkGuardTest`、`WriteRegistry` 不变。
- `ModuleBoundaryTest`：infrastructure 不引用本模块 application，所以缓存与目录两个类不实现 application 的接口，由 `CollectPreviewService` 直接使用（application → infrastructure 是允许的方向）；admin 只用 `CollectPreviewService` 的嵌套记录（`Preview`、`TypeBinding`、`PreviewRow`、`LocalFile`、`LoadOutcome`）与 `ProvideKind`。

### 3. 解析

`ProvideParser.parse(种类, collect_type, ac, param, 文件内容)` 得到 `ProvideDocument`；`ProvideParser.bind(文档, cjflag, bind.php, url, lang)` 得到与旧方法返回值同构的数组（`code/msg/page/type/data`，键的类型与顺序、整数与浮点都相同）。分成两步是为了缓存解析结果、而绑定按每次预览时的 `bind.php` 换算（旧版每次重新请求资源站，绑定当然是最新的）。

公共步骤（与旧版相同）：内容为空串或 `"0"` → `1001 get_html_err`；先按字节做 `mac_filter_tags`（`application/common.php:2898`，`{:` `<script` `<iframe` `<frameset` `<object` `onerror` 按 ASCII 不区分大小写换成 `*`）。视频按 `collect_type`：`== '1'` XML，`== '2'` JSON，其他先 JSON、失败再 XML（数字串按数值比较）。

#### 3.1 JSON（全部种类）

- 解码同 PHP `json_decode($s, true)`（`ProvideJson`）：只认 RFC 8259，空白只有 4 种（BOM 不是空白）；字符串必须是合法 UTF-8、不能含原始控制字符、`\u` 代理项必须成对；超出 int64 的整数是浮点；对象键按 PHP 数组规则规范化（`"123"` 是整数键，`"05"`、`"-0"` 不是），重复键保留第一次的位置、取最后一次的值；容器最多 511 层（PHP 默认深度 512，黄金用例 `vod-json-depth-ok/over` 定出边界）。
- `!$json`（空数组、0、false、空串、null）→ `1002 json_err`。
- **取数方式**（路线图要求在本批文档说明）：`page` ← `$json['page']`，`pagecount` ← `pagecount`，`pagesize` ← `limit`，`recordcount` ← `total`，都原样（缺失为 null）；`data` ← `foreach($json['list'] as $key=>$v)`，**保留 list 的键**（list 是对象时键是它的键）；视频、文章、演员、网址的每行 `type_id` 换算绑定（第 3.4 节），视频另把非空的 `dl` 按 `键 → vod_play_from`、`值 → vod_play_url` 写四个播放字段（`server` 为 `no`，`note` 为空，`$$$` 连接；`dl` 不是数组时四个字段为空串）；`type` ← `ac == 'list'` 时的 `$json['class']` 各项的 `type_id`、`type_name`（否则空数组）。角色、评论没有 `type`，行原样。`msg` 视频为 `json`，其他为 `ok`。
- 浮点转字符串（绑定键、`dl` 的值、过滤词匹配）按 PHP `precision=14`（`1.0E+20`、`0.3`），`ProvideRejectionTest.phpFloatToStringUsesPrecision14` 的期望值取自 PHP 8.0。

#### 3.2 XML（只有视频）

- `ProvideXml` 用 JDK StAX 解析成树，按 SimpleXML 取值：`$e->name` 是第一个**无前缀**的同名子元素（默认命名空间也算，带前缀的不算），`(string)$e` 是直接子节点里文本与 CDATA 相接（不含注释、处理指令、子元素），属性只认无前缀的；`empty($xml)` 为真的条件是根元素没有无前缀属性、无前缀子元素、非 CDATA 文本（空白也算）、注释或处理指令（黄金用例 `vod-xml-*-root`）。元素最多 257 层（libxml 不带 `XML_PARSE_HUGE` 的上限，黄金用例 `vod-xml-deep-ok/over`）。
- 失败或为空时，与旧版一样把没有 CDATA 的 `<pic>…</pic>` 包上 CDATA 再试一次（正则 `/<pic>(.*?)<\/pic>/is` 按字节，替换只认小写 `<pic>`）；仍失败 → `1002 xml_err`。
- 每个 `list->video` 得到固定顺序的 23 个字段（`type_id` 在最前，`vod_type` 为 null、`vod_status` 1、`vod_total` 0、`vod_isend` 按 `vod_serial` 的真假）；`dl->dd` 有内容时按 `flag` 与 `vod_xml_replace` 写播放组，否则 `vod_play_from` 取 `dt`；`base64_decode(param)` 含 `ct=1` 时写进下载的四个字段（非严格 base64，同 B17 的 `base64Decode`）。`page` 取 `list` 的四个属性（缺失为空串）；`type` 取 `class->ty` 的 `id` 属性与文字。

#### 3.3 拒绝

| 输入 | 结果 | 依据 |
|---|---|---|
| 含 DOCTYPE 的 XML（内部实体、外部实体、外部 DTD、参数实体、实体膨胀） | `1002 xml_err：不接受含 DOCTYPE 的 XML`，不读本地文件、不连接 DTD 地址 | D-B18-4；`ProvideRejectionTest` 四个用例（本机监听端口无连接、标记文件内容不出现、膨胀在 2 秒内拒绝） |
| 格式错误的 JSON/XML、空内容、超深嵌套 | 同旧版的 `1002`/`1001` | 黄金 19 + 3 个失败用例；`deepNestingFailsWithoutStackOverflow`（10 万层不栈溢出） |
| JSON 顶层是非空标量；数据行或分类行是字符串；换算绑定的种类里数据行是数字或 `true`；无穷大的数 | `1002 json_err`（附原因） | D-B18-5 |
| 超过 `max-file-bytes`（默认 10,240,000 字节）的文件；上传超过 `spring.servlet.multipart.max-file-size`（默认 2MB） | "文件超过大小上限"/"文件超过上传上限"，不解析、不缓存 | D-B18-6；`CollectPreviewServiceTest`、IT |
| 本地目录：名字含路径分隔符或控制字符、以点开头、扩展名不是 `.json/.xml/.txt`、子目录、符号链接、目录外的路径 | "文件不存在" | `localDirectoryOnlyServesPlainFilesDirectlyInside` |

#### 3.4 绑定换算与入库预检

- 数据行 `type_id`：`$bind_list[cjflag . '_' . (string)远程编号] > 0` 时为绑定的值，否则 0（远程编号按 PHP 字符串转换：`1.0` → `1`，null → 空串，`true` → `1`）。分类列表的绑定状态同旧控制器：`intval(...) > 0` 时 `isbind`，分类名取本站分类，没有时 `lang('unknown_type')`（`CollectBindings.localTypeId`）。
- 入库预检（`ProvideParser.precheck`）：视频、文章、网址 `type_id == 0` → `type_err`，名称 `empty()` → `name_err`，名称含过滤词 → `name_in_filter_err`；演员第二条为名称或性别为空 → `odel/collect/actor_data_require`（旧语言包键少了 m，照旧，显示的就是这个键）；角色、评论按各自的必填字段；过滤词为 `maccms.php` 的 `collect.<种类>.filter` 按逗号拆开、去掉空串与 `"0"`（`mac_array_filter`）。

### 4. 本地文件源与缓存

| 参数（环境变量） | 默认 | 说明 |
|---|---|---|
| `maccms.collect.local.source-dir`（`MACCMS_COLLECT_SOURCE_DIR`） | 空 | 放 Provide 文件的目录；空时只能上传 |
| `maccms.collect.local.cache-dir`（`MACCMS_COLLECT_CACHE_DIR`） | 系统临时目录下的 `maccms-collect-preview` | 预览缓存；不要指向旧站目录 |
| `maccms.collect.local.cache-ttl`（`MACCMS_COLLECT_CACHE_TTL`） | `PT24H` | 缓存有效期 |
| `maccms.collect.local.max-file-bytes`（`MACCMS_COLLECT_MAX_FILE_BYTES`） | 10240000 | 每个文件的上限（超过 64MB 的设置按默认） |
| `maccms.collect.local.max-entries`（`MACCMS_COLLECT_CACHE_MAX_ENTRIES`） | 200 | 缓存最多保留的项数 |

- **4.1 本地目录**：只列出、读取目录下直接存放的普通文件（`NOFOLLOW_LINKS`），名字规则见第 3.3 节；先看大小再读，读时再按上限截止。
- **4.2 缓存**：一项一个 `<cjflag>-<摘要>.json`（`cjflag` 不是 32 位小写十六进制时取它的摘要；摘要覆盖种类、`collect_type`、`param`），内容是解析结果（`ProvideJson.encode`，与解码往返不变，黄金测试逐例核对）；先写临时文件再原子改名。过期项在下一次载入、清除或读取时删除，项数超过上限时删最旧的；读到损坏或对不上来源的项当作没有并删除。只处理名字符合规则的文件，目录里的其他文件不碰。不写库、不写旧站目录、不写本地文件目录。

### 5. 后台动作（`collect/api`，权限仍是 `collect/api`）

| 请求 | 行为 |
|---|---|
| `GET ?ac=list&cjflag&cjurl&mid&type&param&t&wd&page&limit`（`pg` 非空时当作 `page`） | 有缓存：预览页；没有：只显示"本地文件"一栏。`mid` 按旧版分派（`''`/1 视频、2 文章、8 演员、9 角色、11 网址），其他值"参数错误"（D-B18-7） |
| `POST ?ac=localload`（multipart `file`，或表单字段 `local` = 本地目录里的文件名） | 解析成功 → 写缓存，`success("载入成功，共 N 条")` 跳回预览；失败 → `error(原因)`，不写缓存 |
| `POST ?ac=localclear` | 删除这个来源的缓存，`success(lang('clear_ok'))` |
| 其他 `ac`（入库）、`collect/load` | 501"由 B19 批次提供" |

预览页的列表按 `t`（远程分类编号相等）、`wd`（名称包含）在本地筛选，按 `page`/`limit` 分页（默认 20，`limit` 不在 1～1000 时取默认，页码越界取最后一页）（D-B18-2）。

### 6. 页面

- 旧 `collect/{vod,art,actor,role,website}.html` 只有绑定弹窗的 `tab`、表头、各列字段与绑定回调的写法不同，新版合成一个 `api.jte`，差别由 `CollectApiView` 按种类给出；结构、类名、按钮、分页（`laypage`）、搜索、分类筛选、绑定弹窗（`index/select?tab=…&tpl=select_type&url=collect/bind`）照旧。旧内联脚本逐行移到 `collect-api.js`，地址、分页数字与语言包文字经 `data-*` 传入（B12 页面脚本策略，`AdminTemplatePolicyTest`）。
- 新增（新系统必需的最小改动）：页首"本地文件"一栏（当前文件与载入时间、上传框、本地目录下拉框、"载入""清除预览缓存"两个按钮、上限说明）；名称后用旧版的红字样式 `<span class="red">[原因]</span>` 显示入库预检不通过的原因（D-B18-3）。
- 输出转义按 D-B13-19 的同一规则；`mac_day` 的红色日期照旧（数字时间按北京时间格式化）。
- 旧 `actor.html` 绑定回调未绑定一支的字符串少一个引号，整段脚本报错；新版按其他模板的写法输出（D-B18-8）。

### 7. PHP 与 Java 的字段一致（黄金）

- `backend/src/test/php/collect_provide_golden.php` 在 PHP 8.0.30 与 7.3.33 上**直接调用旧 `Collect.php` 的解析方法**，不改旧代码、不连库、不联网：这些方法在命名空间 `app\common\model` 里以非限定名调用 `mac_curl_get()`、`model()`、`config()`、`lang()`，PHP 先找当前命名空间的函数，所以脚本在同一命名空间定义同名函数——`mac_curl_get()` 返回用例的文件内容、`config('bind')` 返回用例的绑定、`model('Type')` 返回空分类缓存、`lang()` 返回键；模型用 `newInstanceWithoutConstructor()` 创建。路线图说的 `vod_xml($param, $html)` 在这份生产代码里会被 `mac_curl_get` 覆盖掉 `$html`，所以 JSON 与 XML 两条路径都用上面的办法取数。
- 56 个用例（成功 34、空内容 3、解析失败 19；视频 JSON 23、XML 21、空内容 2、自动判断 3、其他种类 7，含 GBK 编码、命名空间、CDATA 与混合内容、`<pic>` 回退、深度边界、键规范化、大整数与各类浮点、`dl` 的各种值）。两版结果相同。
- `ProvideParserGoldenTest`：新版对同一输入的 `parse` + `bind` 结果逐值相同（带类型、保持顺序的比较），每行的入库预检相同，缓存编码往返后仍相同；失败用例比较 `code` 与语言包键（旧版的 `msg` 里还拼了请求地址）。`page.url` 不比较（旧版是请求地址，新版是文件名）。

### 8. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP/Java 字段一致 | `ProvideParserGoldenTest`（PHP 8.0、7.3 各 56 例） |
| 拒绝 | `ProvideRejectionTest`（XXE 四种写法 + 自动判断路径、实体膨胀、未声明实体与 XInclude、10 万层嵌套、新版另拒绝的 JSON 形态、缓存编码往返、PHP 浮点字符串、`mid` 分派） |
| 载入、预览、缓存 | `CollectPreviewServiceTest`（绑定随 `bind.php` 变化、预检、本地筛选与分页、失败不缓存、超大文件、目录规则与符号链接、缓存只在缓存目录、过期与清除、项数上限、损坏的缓存项、16 次并发载入只留一项完整的缓存） |
| 不写库、不联网（静态） | `CollectPreviewGuardTest` |
| 端到端（真实 MySQL 一次性库） | `AdminCollectPreviewMySqlIT`：经后台入口走完打开 → 上传 → 预览（绑定、预检、筛选、分页）→ 本地目录载入 → 拒绝路径穿越、XXE、超大、畸形 → 文章预览 → 入库按钮与 `collect/load` 为 B19 → 清除；**登录后取全库快照，结束时逐行相同**；XXE 的外部 DTD 指向本机监听端口，整个过程无连接；`bind.php` 与本地目录不变；清除后缓存目录没有缓存项；表结构指纹与导入时相同。另经真实 Tomcat 上传（MockMvc 不经过 multipart 解析）与 3MB 上传被"超过上传上限"拒绝 |
| 护栏 | `ModuleBoundaryTest`、`AdminTemplatePolicyTest`、`OutboundNetworkGuardTest`、`WriteGuardTest`、`WriteRegistryTest`、`SchemaGuardTest`、`ReadOnlyGuardrailsTest` 不变且通过 |
| 测试总数 | 见交付评论（`./mvnw verify` 与 `./mvnw verify -Pmysql-it` 在最终树上的结果） |

### 9. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B18-1 | 按采集地址请求资源站（`mac_curl_get`），`t/pg/h/ids/wd` 交给资源站；`page.url` 与错误消息里是请求地址 | 只解析上传或本地目录的文件，不发请求；`page.url` 是文件名，错误消息不含地址；`h`、`ids` 不起作用 | E01（只做本地版本） |
| D-B18-2 | 资源站按 `t`、`wd`、`pg` 返回一页 | 本地按 `t`（远程分类编号）、`wd`（名称包含）筛选，按 `page`/`limit` 分页（默认 20） | 本地文件就是全部数据 |
| D-B18-3 | 预览页不提示 | 名称后红字显示入库时会被跳过的原因（与 `{type}_data` 的条件相同） | 路线图"按过滤规则筛选" |
| D-B18-4 | libxml 解析 DOCTYPE（PHP 8 默认不加载外部实体，内部实体照常展开） | 含 DOCTYPE 一律拒绝 | 安全（XXE、实体膨胀） |
| D-B18-5 | 顶层非空标量得到全是 null 的分页；字符串或标量数据行在 PHP 8 上报错（500）；`1e400` 得到 INF | "JSON 格式不正确"并附原因 | 健壮性 |
| D-B18-6 | 没有大小上限（libxml 单个文本节点 10,000,000 字节的上限新版没有照搬） | 每个文件 `max-file-bytes`（默认 10,240,000）；上传另受 multipart 上限（默认 2MB）约束，大文件放本地目录 | 资源边界 |
| D-B18-7 | `mid` 不是 ''/1/2/8/9/11 时没有输出（空白页） | "参数错误" | 健壮性 |
| D-B18-8 | 五个模板；`actor.html` 的绑定回调脚本有语法错误 | 一个模板、一个脚本，按种类给出差别；回调按其他模板的写法 | 页面脚本策略 |
| D-B18-9 | `collect/load` 跳到缓存的断点地址（断点在入库时记下） | 归 B19（"由 B19 批次提供"） | 本批没有入库，也就没有断点 |
| D-B18-10 | 每次预览都重新请求 | 解析结果缓存在本机（有效期 24 小时），绑定按每次预览时的 `bind.php` 换算 | 本地文件只需解析一次 |
| — | 绑定值与 0 的比较 | 按 PHP 8 的宽松比较（`bind.php` 里的值由 `intval` 写入，PHP 7/8 结果相同） | 行为兼容 |

### 10. 回滚

- **代码**：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到 `94e5f7b` 的行为（`collect/api` 恢复为"由 B18 批次提供"，`collect/load` 恢复为 B18）。后台默认关闭，不涉及表结构、数据迁移。
- **数据**：本批不写库。
- **文件**：删除预览缓存目录（`maccms.collect.local.cache-dir`，默认系统临时目录下的 `maccms-collect-preview`）即可；本地文件目录与旧站目录本批只读。

### 11. 风险与遗留

| 项 | 说明 |
|---|---|
| 资源站协议的非标准变体 | 路线图标注【待核实】。本批按这份生产代码的解析规则逐字段对齐；没有接触真实资源站，无法核实线上数据里有哪些变体。黄金覆盖了代码能处理的形态与边界 |
| 上传上限 | 上传受 `spring.servlet.multipart.max-file-size`（全局，默认 2MB，B09 头像沿用旧版 2M）约束；本批不改全局值，大文件放本地目录。若需要更大的上传，部署时调整该参数（会同时影响其他上传） |
| 缓存是本机文件 | 多实例部署时各实例的缓存不共享，载入与预览要落在同一实例；缓存目录要有写权限，且不要指向旧站目录 |
| B19 的入口 | 预览页的"采集选中/当天/所有"按钮与 `collect/load` 在 B19 交付前返回批次提示；`CollectPreviewService.Preview.result()` 是与旧方法返回值同构的完整结果，供 B19 入库沿用 |
| 评论 | 旧版 `comment_json` 没有后台入口；本批只实现解析与预检（黄金覆盖），没有页面 |
