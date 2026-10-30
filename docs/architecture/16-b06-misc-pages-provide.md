## B06 杂项只读页与 Provide 只读协议：最终契约与旧入口覆盖表（路线图 B06，MACC-30）

> **状态：联合独立验收通过。** 本文件是 B06 的**最终契约**：第 2–5 节是对外契约（路径、方法、参数、字段、格式、状态码、开关、默认值），第 6 节是旧参数/路由 → Java 入口 → 测试的覆盖表，第 7 节是与旧版的差异，第 8 节是队长最终裁定，第 9 节是最终树的验证，第 10 节是回滚。开工时的契约稿（`de890ba`）已被本文取代。
> 未部署、未切流量。只读批次：不写库、不登记业务写入、零结构变更、不改 B02/B07 护栏，服务端不发起出站请求（包括 DNS 查询）。
> 依据：`09-roadmap.md` 第 3.2 节（第 148、150 行）、第 4.2 节（W1/W2）、第 5.3 节 B06 行、第 5.5 节 ④；MACC-30 任务描述；`15-b05-readonly-pages.md`（B05 最终契约：单条 RSS、各模块 `ajax_detail/ajax_show/ajax_search`、必要的 `show/search` 页已在 B05，本批直接复用，不重复注册路由）。旧代码行号以当前仓库为准。
> 证据标注沿用 09 方案：**【代码】**仓库代码；**【黄金】**PHP 黄金样本实测；**【待核实】**依赖线上配置、线上模板或数据。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 分支 | `agent/claude/macc-30`（只用普通提交，没有 reset/rebase/amend/force） |
| 开工基线（已验收 main，含 B05 合并） | `2313076`（merge MACC-29），包含 B05 固定交付 `967941951bc52664b54a001bf96a946fbd75929e`；平台基线提交 `3c2348d` |
| 前端基线 | 已验收前端 `3100239`（MACC-54，经 B05 整合）；后端提交没有修改 `static/site/`、`jte/site/components/`、`jte/site/layout/`。整合任务 MACC-55 只在 `site.css` 末尾追加三页的最小规则（第 5 节） |
| 提交 | `de890ba` 契约稿 → `8004bca` 客户端 IP、整行读取、Provide 与黄金 → `1afe59e` site 杂项页与 ajax → 文档与验证（最终 SHA/tree 以交付评论为准） |

### 1. 范围、分工与文件归属

| 部分 | 旧入口 | 本批 | 负责 |
|---|---|---|---|
| A 客户端 IP 组件（D2 ④） | `mac_get_client_ip`（`application/common.php:950-990`） | `common.clientip`（第 2 节）；Provide 白名单首先使用，B08/B09/B11 复用（02 规范第 2 节） | C |
| B Provide 只读协议 | `api/controller/Provide.php` 全部 7 个操作 | 新模块 `openapi`（03 草稿第 1 节预留）：`vod/art/actor/role/website` 的 `ac=list/videolist/detail`、JSON/XML、`closed`、`cachetime`、`charge` 白名单；`comment`、`index` 与旧版一样输出空响应（第 3 节） | C |
| C 地图、站点级 RSS 与搜索引擎地图 | `index/controller/Map.php`、`Rss.php` | `map/index`；`rss/index` 与 `baidu/google/so/sogou/bing/sm`（第 4.1、4.2 节） | C；样式 K |
| D 自定义标签页、帮助、版权、公告 | `Label.php`、`Help.php`、`Vod.php:74-78` | 第 4.3–4.6 节 | C；样式 K |
| E 二维码、桌面快捷方式 | `Qrcode.php`、`Ajax::desktop`（`Ajax.php:123-150`） | 本地生成 PNG；`.url` 下载（第 4.7、4.8 节） | C |
| F 联想词、`ajax/data` | `Ajax::suggest`（`:88-121`）、`Ajax::data`（`:21-86`） | 旧 JSON 同形（第 4.9、4.10 节）。路线图第 5.3 节 B11 行也列了 `ajax/suggest`：它不写库，本批承接，B11 不再重复 | C |
| 不在本批 | `Ajax::hits/digg/score/referer`（写计数）、`ajax/pwd`（B05 改为 POST + 签名票据）、`verify_check`（B11）、Provide `detail_inc_hits` 计数（B11）、`gbook`/`comment` 页面与写入（B11）、手机模板/繁体（B12） | 这些 `ajax/*` 操作在 Java 中 404（交还 PHP） | — |

**数据归属**：旧表由 `content` 读取——新增 `content.application.LegacyRowReadService`（`infrastructure.LegacyRowMapper`，SQL 在 `mapper/content/LegacyRowMapper.xml`）提供旧协议需要的"整行"读取；`openapi` 与 `site` 只调用它，自己没有 Mapper。
**文件归属**：后端拥有 `openapi`、`common.clientip`、`common.web.LegacyJson`、`content` 的整行读取、`site` 中新增的控制器/视图模型与新增页面模板（`jte/site/pages/map.jte`、`help.jte`、`copyright.jte`、`jte/site/fragments/mapTree.jte`）。共享的 `components/`、`layout/`、`static/site/site.css`、`trial.js` 与组件签名**本批不修改**；新页面只用既有 class（`notice`、`detail-content`）与第 5 节列出的新语义 class；三页样式由整合任务 MACC-55 按既有样式最小补齐（第 5 节）。

### 2. 客户端 IP 组件（D2 ④，`common.clientip`）

#### 2.1 部署参数（环境变量，不新增站点配置项）

| 参数 | 环境变量 | 默认 | 取值 |
|---|---|---|---|
| `maccms.client-ip.trusted-proxies` | `MACCMS_TRUSTED_PROXIES` | 空（不信任任何代理） | 逗号分隔的 IPv4/IPv6 地址或 CIDR（如 `127.0.0.1/32,10.0.0.0/8,::1/128`）；项前后空白忽略；不能解析、前缀长度越界、主机位非零（如 `10.0.0.1/8`）、超过 64 项 → **启动失败**，信息只含参数名与项序号 |
| `maccms.client-ip.header` | `MACCMS_CLIENT_IP_HEADER` | `X-Forwarded-For` | `X-Forwarded-For`（列表语义）或单值头 `X-Real-IP`、`CF-Connecting-IP`、`True-Client-IP`、`Ali-CDN-Real-IP`（不区分大小写）；其他值启动失败 |

#### 2.2 解析规则（`ClientIpResolver.resolve`）

1. **对端** = 连接的对端地址（Servlet `getRemoteAddr()`）。对端不能解析为 IP 字面量时结果为 `0.0.0.0`（与旧版"无合法 IP"时相同）。
2. 对端**不在**可信网段内（含未配置可信代理）→ 结果就是对端；任何请求头都不看。`CF-Connecting-IP`、`Ali-CDN-Real-IP`、`Client-IP`、`Proxy-User`、`X-Real-IP`、`X-Forwarded-For`、`Forwarded`、`True-Client-IP` 全部忽略。
3. 对端在可信网段内，只看配置的那一个头：
   - 没有该头 → 对端（代理没有声明转发，请求就来自代理所在主机）。
   - **`X-Forwarded-For`（列表）**：同名头出现多次时按出现顺序以逗号连接；按逗号切分、每项去掉前后空白；**从右往左**逐项检查：是可信代理就继续向左；遇到第一个不是可信代理的合法地址，它就是客户端；全部是可信代理时取最左项。**最多检查最右边 32 项**，更左边的内容（客户端可以随意填写）既不检查也不计入长度，所以在左边填充任何内容都改变不了结果。
   - **单值头**：恰好出现一次且整值（去首尾空白）是合法地址 → 采用。
   - **头给不出客户端 → `0.0.0.0`（未知），不退回对端或任何可信代理的地址**：检查到不能解析的项（空项、端口、方括号、域名、`unknown` 等）、检查满 32 项仍全是可信代理、单值头出现多次或不合法。可信代理的地址可能就在白名单里（旧版内置 `127.0.0.1`），退回它等于让访问者用畸形或超长的头冒充白名单。
4. 地址规范化（`IpAddress`）：IPv4 为点分十进制（每段 0–255、不允许前导 0）；IPv6 按 RFC 5952 小写压缩形式；IPv4 映射的 IPv6（`::ffff:1.2.3.4`）归一为 IPv4；区域标识（`%eth0`）、方括号、端口不合法。**只做字面量解析，从不做 DNS 查询**（不调用 `InetAddress.getByName` 处理非字面量）。
5. 组件不记录任何地址；启动时只记录"可信代理项数与头名"。
6. `0.0.0.0`/`::` 不代表任何客户端：Provide 白名单对它一律拒绝，即使 `auth` 里写了它。B08/B09/B11 复用本组件时，同样不能把"未知"当成某个具体访问者放行。

**无可信代理时的局限（部署说明）**：部署在 CDN/反向代理后面而未配置 `MACCMS_TRUSTED_PROXIES` 时，所有请求的客户端 IP 都是代理地址；Provide 白名单只能按代理地址判断（等于对所有经代理的访问者一起放行或拒绝）。把宽网段（如 `0.0.0.0/0`）设为可信会让任何人伪造 `X-Forwarded-For`，只能填写自己的代理出口网段。环境变量样例与说明见 `config/maccms-backend.env.example`。

### 3. Provide 只读协议（`openapi` 模块）

#### 3.1 路由与开关

| 项 | 契约 |
|---|---|
| 路径 | `/api.php/provide/<操作>`，`<操作>` ∈ `vod art actor role website comment index`（只认小写，其他 404）；路径按 PATH_INFO 解码一次（不是合法 UTF-8 → 400），去掉末尾后缀 `.html/.htm/.shtm/.shtml/.xml`（不区分大小写，`application/config.php:86`）与首尾 `/` 后按 `/` 切分；操作名之后是成对的路径参数（ThinkPHP `(\w+)\|([^\|]+)` 规则，`/api.php/provide/vod/at/xml/ac/list/`） |
| 参数优先级 | GET < POST（表单）< 路径参数（`thinkphp/library/think/Request.php:658`）；同名参数取最后一个值；参数名含 `[`（PHP 数组参数）→ 400 |
| 方法 | GET、HEAD、POST |
| 模块开关 | `maccms.openapi.enabled`（`MACCMS_OPENAPI_ENABLED`，只接受 true/false，默认 false）：关闭时不注册 Provide 路由（交还 PHP，本地 404） |
| 旧配置 | 启动时经 `LegacyConfigService` 读一次 `extra/maccms.php` 的 `api.vod/art/actor/role/website` 与 `upload.protocol`（改了要重启）；未配置旧配置目录时全部模块 `closed`；值不受支持（第 3.4 节）时**启动失败**，信息只含键名 |
| 站点关闭 | 旧 `api/Base` 的 `site_status==0` 分支为空（`application/api/controller/Base.php:14-17`）：Provide 不受站点关闭影响，新版相同 |

#### 3.2 判定顺序、状态码与响应头

| 顺序 | 条件 | 新版响应 |
|---|---|---|
| 0 | `comment`、`index`（旧方法体为空，`Provide.php:16-19`、`:780-783`） | **200 空 body**，不看开关与白名单 |
| 1 | `status != 1`（`Provide.php:23-26` 等） | **200** `closed`；不查库 |
| 2 | `charge == 1` 且客户端 IP（第 2 节）不在白名单（`:28-38`、`:785-804`） | **403** `域名未授权`；不查库 |
| 3 | 参数不合法（第 3.3 节）、路径解码失败 | **400** `参数错误`（不回显输入） |
| 4 | 查询超时、连接故障、包含匹配并发上限 | **503** `服务暂时不可用`，`Retry-After: 5` |
| 5 | 正常 | **200** JSON 或 XML |

- Content-Type：JSON `application/json;charset=utf-8`，XML `text/xml;charset=utf-8`，其余文本 `text/plain;charset=utf-8`（Tomcat 输出不带空格）；全部带 `X-Content-Type-Options: nosniff`、`Content-Length`。
- Cache-Control：`cachetime > 0` 时 `public, max-age=N`（`charge=1` 时 `private, max-age=N`），其余与全部错误/`closed`/拒绝为 `no-store`。新版**不做服务端结果缓存**（D-B06-4）。

**白名单**（`charge=1`）：列表 = `127.0.0.1` + `auth` 按 `#` 切分、去首尾空白后的每一个 IP 字面量（IPv4 或 IPv6，规范化后比较）。旧版对不是 IP 形态的项调用 `gethostbyname`（`:793-795`，DNS 查询）；新版**不解析域名**，域名项与带端口的项（`mac_string_is_ip` 接受 `1.2.3.4:80`）永不命中，启动时日志只记录"忽略 N 项"。

#### 3.3 参数（值先按旧 `input('','','trim,urldecode')` 处理：先去首尾空白，再 `urldecode` 一次，`Provide.php:13`；解码结果不是合法 UTF-8 → 400）

| 参数 | 适用 | 旧语义（【代码】行号，【黄金】已对照） | 新版 |
|---|---|---|---|
| `ac` | 全部 | vod：`videolist`/`detail` → 整行字段（`:127-129`），其他（含缺失）→ 列表字段 + 分类表 `class`；art/actor/role/website 只认 `detail` | 相同 |
| `at` | vod | `xml` → XML（`:133-137`），其他 → JSON；其余模块只有 JSON | 相同 |
| `ids` | 全部 | `!empty` 时 `<主键> IN (explode(','))`，各项按字符串比较（MySQL 隐式转换，`1,abc` 只命中 1） | 相同（各项作字符串绑定）；项数 ≤ 1000、每项 ≤ 64 字符，超出 400 |
| `t` | vod/art/actor/website | `!empty` 且 `typefilter` 为空或 `strpos(typefilter, t) !== false`（**子串**）时 `type_id = t`；vod 中它覆盖 typefilter 的 IN（`:52-56`） | 相同（子串判定原样保留，`typefilter='21'` 时 `t=2` 也生效）；role 表没有 `type_id`，旧版 SQL 报错（500）→ 新版 400 |
| `typefilter`（配置） | — | 只有 vod 用它限定行（`:48-50`）；其余模块只影响 `t` 与 `class` | 相同 |
| `isend` | vod | 出现即生效：`vod_isend = (isend == 1 ? 1 : 0)`（`:58-60`） | PHP 8 宽松比较（`1.0`、`01` 为 1；`1abc`、空串为 0） |
| `h` | 全部 | vod：`!empty(h)` 时下限 `strtotime('-h hours')`；其余：`!empty(intval(h))` 时下限为该时刻**当日 0 点**；上限为明天 0 点（`Asia/Shanghai`，`application/config.php:42` 的 `PRC`）；`time > 下限 AND time < 上限` | vod：恰好 `0` 或空不筛选，否则必须是十进制数字（≤ 9 位，`00` 为 0 小时），非数字 400；其余：`intval` 为 0（含 `abc`）不筛选，否则必须是数字，`5abc` 之类 400 |
| `wd` | 全部 | `!empty` 时 `<名称> LIKE %wd%`（不转义 `%`、`_`，`:70-72`） | 按字面值匹配（`ESCAPE '!'`，与 B05 相同）；≤ 64 码点、不含控制字符；受执行时限与并发许可，不受搜索开关（D-B06-6） |
| `year` | vod | `!empty` 时：4 字节为 `intval` 单值；9 字节为两端 `(int)substr`（必要时交换）夹在 1900..今年+3 后展开；其他长度为 `IN ('')`（`:74-95`） | 逐项相同（含"其他长度/展开为空时 `IN ('')`"），按 UTF-8 字节数判断 |
| `from` | vod | 配置 `api.vod.from` 为空且参数长度 > 2 字节时代替配置；按逗号去重、去掉 `''`/`'0'` 后 `vod_play_from LIKE %trim(x)%` 的 OR（`:96-111`）；输出时只保留这些播放组（`:194-243`、`vod_url_deal` `:155-177`） | 条件与输出筛选相同；LIKE 按字面值；项数 ≤ 20，超出 400 |
| `pg` | 全部 | vod：`empty` 时为 1，否则原样参与 `LIMIT`；其余：`intval` 为 0 时为 1 | 十进制数字（`0` 为 1；JSON 的 `page` 保留原字符串，如 `"2"`、`"02"`；默认时为整数 1）；> 1000000 或非数字 400（旧版非数字在 PHP 8 下 500） |
| `pagesize` | vod | `!empty` 且 `> 0` 时 `min((int), 100)`，否则配置值（`:118-121`） | 十进制数字；正数时 `min(n, 100)`（JSON `limit` 为整数），`0` 为配置值（JSON `limit` 保留配置的字符串/整数类型），非数字 400 |
| `sort_direction` | vod | 恰好 `asc` 时升序，否则降序（`:123-124`） | 相同 |
| 其他键 | — | 忽略 | 忽略 |

#### 3.4 旧配置的取值规则（`ProvideSettings`）

| 键 | 规则 |
|---|---|
| `status`、`charge`、`detail_inc_hits` | `== 1`：整数、布尔、空串或 1–3 位数字字符串；其他启动失败 |
| `pagesize` | 1..1000 的整数或数字字符串；空串按 20；其他启动失败（旧版 0 会除零出错） |
| `imgurl` | 未设置、空串，或 `http://`、`https://`、`//` 开头且不含空白与控制字符 |
| `typefilter` | 空，或逗号分隔的分类 id |
| `datafilter` | 旧版是原样拼进 WHERE 的 SQL（`:112-114`、`Vod.php:37-48`）。新版**不执行配置中的 SQL**，只接受空串或 `<前缀>_<列> <运算符> <整数>` 以 `AND` 连接（不区分大小写，最多 16 个），列 ∈ `status/level/lock`（vod 另有 `isend/copyright/plot`），运算符 ∈ `= != <> > >= < <=`；仓库值 ` vod_status=1` 等都在其中。其他写法启动失败。**无论 datafilter 写了什么，行范围都另限"已审核 + 可见分类"**（第 3.6 节）；角色与旧 `Role::listData` 一样不使用 datafilter（`Role.php:37-47`） |
| `cachetime` | 空或 0..9999999 的整数 |
| `from` | 空，或不含控制字符（≤ 1024） |
| `auth` | 见第 3.2 节 |

#### 3.5 输出格式

- **JSON**：PHP `json_encode(..., JSON_UNESCAPED_UNICODE)` 的等价编码（`common.web.LegacyJson`：`/` 写成 `\/`、控制字符 `\u00XX`、U+2028/2029 转义、非 ASCII 原样、整数值的浮点不带小数）后对整段输出执行 `mac_filter_tags`（`application/common.php:2898-2915`：依次把 `{:`、`<script`、`<iframe`、`<frameset`、`<object`、`onerror` 不区分大小写替换为 `*`，作用于整段 JSON，包括键与值）。外层 `code,msg,page,pagecount,limit,total,list[,class]`；`page`/`limit` 保留旧版的字符串/整数类型，`pagecount = ceil(total/limit)`。逐字节以 PHP 黄金为准（`ProvideGoldenMySqlIT`）。
- **列表字段**：vod `vod_id,vod_name,type_id,type_name,vod_en,vod_time,vod_remarks,vod_play_from`（`vod_play_from` 的 `$$$` 换成 `,`；有 `from` 时只列出其中存在的组）；art `art_id,art_name,type_id,type_name,art_en,art_time,art_author,art_from,art_remarks,art_pic`；actor `actor_id,actor_name,type_id,type_name,actor_en,actor_area,actor_time,actor_alias,actor_sex,actor_pic`；role `role_id,role_name,role_rid,role_en,role_actor,role_time,role_pic` + `douban_id,vod_name,vod_director`；website `website_id,website_name,type_id,type_name,website_en,website_time,website_area,website_lang,website_pic`。
- **整行字段**（vod 的 `videolist/detail`、其余模块的 `detail`）：旧版 `SELECT *`。新版明确列出该表全部列（`install.sql` 的顺序，`LegacyTable.fullColumns()`，由一次性库的 `information_schema` 核对），**去掉访问密码列** `vod_pwd, vod_pwd_url, vod_pwd_play, vod_pwd_play_url, vod_pwd_down, vod_pwd_down_url`、`art_pwd, art_pwd_url`（D-B06-3）；`type_name` 追加在最后；vod 有 `from` 时 `vod_play_from` 以 `,`、`vod_play_url/server/note` 以 `$$$` 只保留这些组（`:194-223`）。角色的 `douban_id/vod_name/vod_director` 追加在最后。
- **时间**：`<前缀>_time` 按 `Asia/Shanghai` 格式化为 `Y-m-d H:i:s`；其他时间列为原始整数（与旧版相同）。
- **图片**：`<前缀>_pic` 的 `mac:`（所有出现处）换成协议（`imgurl` 以 `https` 开头时 `https:`，否则 `upload.protocol` + `:`，`:806-816`）；其他非空、非 `http`、非 `//` 开头的值前面加 `imgurl`（`:187-192`）。Provide 面向服务器端采集程序，**不适用 W2**（W2 只约束页面让浏览器访问的地址）。
- **分类表 `class`**（列表模式）：vod JSON 为全部模型的可见分类（旧版此处不按 `type_mid` 过滤，`:251-261`），XML 只有 `type_mid=1`（`:323-334`）；art/actor/website 按各自模型；role 为空数组。按 `type_id` 升序；`typefilter` 非空时只含其中的分类。
- **XML**（vod，`vod_xml` `:267-342`）：元素顺序、CDATA 位置、`<dt>`（有 `from` 时为原样的 from 字符串）、`<dl>` 中 `<dd flag>`（`vod_url_deal`：from 非空时只保留等于它或是它子串的组，播放组比地址组多出两个以上时多出的组不输出）与旧版逐字节相同；差异见第 7 节（转义）。
- **`detail_inc_hits`**：旧版 `ac=detail` 且 `ids` 为单个整数时 `vod_hits + 1`（`:143-150`）。本批**不写计数**（只读批次），开启时启动日志提示；B11 补齐（第 7 节临时差异）。

#### 3.6 数据范围与受限内容

- 行范围：`<前缀>_status = 1` **且**所属分类可见（`CategoryCatalog`：分类启用、游客有列表权限位、上级链可见；与 content API 相同）；专题、角色没有分类，只看状态。旧版只由 `datafilter` 决定。
- 角色的 `douban_id/vod_name/vod_director` 只在关联视频已审核且分类可见时给出，否则为 `null`（旧版取任意状态的视频，`Role.php:49-61`）。
- 受访问密码保护的内容：`vod_pwd` 非空的行，只在详情页出现的 `vod_content`、`vod_plot_name`、`vod_plot_detail`（分集剧情，旧剧情页同样要求详情权限）以及 `vod_play_url`、`vod_down_url` 为空串；`vod_pwd_play` 非空时 `vod_play_url` 为空串（XML `<dl>` 各组 CDATA 为空）；`vod_pwd_down` 非空时 `vod_down_url` 为空串；`art_pwd` 非空时 `art_content`、`art_title`、`art_note`（分页正文、页标题、页备注，都只在详情页出现）为空串。列表也展示的字段（名称、简介、`vod_plot` 标记等）不变。密码列本身从不读出（SQL 中只有 `CHAR_LENGTH(...) > 0`）。积分、会员组权限不改变 Provide 输出（与旧版相同，D-B06-3）。
- 数据库错误不回显：只有第 3.2 节的固定文本。

### 4. 杂项页与 ajax 接口（`site` 模块）

所有入口受 `maccms.site.enabled`；站点关闭时 503 维护页（B05 起的 `SiteStatusInterceptor`）。URL 形态与 B05 相同（默认 `/index.php/<控制器>/<操作>[/键/值…].html` 及 `rewrite`/`suffix_hide` 变体，`rewrite` 关闭时去掉 `/index.php` 的形态 404；canonical 与站内链接只由 base-url 生成，子目录部署时以 base-url 的路径为前缀，应用仍挂在根路径）。旧版地图、RSS、帮助、版权、标签页的内容全部由模板决定，仓库模板缺失（D9-a），新版页面结构由后端定义（D-B06-7），所需数据与旧模板的数据来源相同。HTML 页面：严格 CSP（`script-src 'none'`）、`Cache-Control: no-cache`（与 B05 相同，生产 TTL 未定）、`nosniff`。

#### 4.1 地图 `map/index`（`Map.php:12-15`）

- 规范地址 `map/index.html`；`map`、`map/`、`map.html`、`map/index`、`map/index/` 只作输入（canonical 指向规范地址）；兼容模式 `/index.php?s=map/index` 同样承接。任何路径参数或查询串 404。
- 内容：可见的视频/文章/演员/网址分类树（各自链接到分类页）+ 专题、演员、角色、网址首页与 7 种 RSS 链接；不列内容条目。

#### 4.2 站点级 RSS 与搜索引擎地图 `rss/<格式>`（`Rss.php:13-53`）

| 规范地址 | 格式 | 条目 |
|---|---|---|
| `rss/index.xml` | RSS 2.0（`application/rss+xml;charset=UTF-8`） | 最近更新的 50 个视频（`VideoListQuery` 按时间倒序）：`title`、`link`（详情页绝对地址）、`description`（`vod_blurb`，为空时 `vod_remarks`，纯文本）、`pubDate`（RFC 1123）、`guid isPermaLink="true"` |
| `rss/baidu.xml`、`google`、`so`、`sogou`、`bing`、`sm` | Sitemap 0.9（`urlset`，`application/xml;charset=UTF-8`） | 站点首页（`daily`，`1.0`）+ 最近更新的 1000 个视频详情页（`lastmod` 为 `Asia/Shanghai` 的 `Y-m-d`，`weekly`，`0.8`） |

- 旧地址形态 `url($model, $param, 'xml')`（`common.php:1782-1793`）：规范地址以 `.xml` 结尾；`.html`、无后缀、`/` 结尾与单段 `rss`、`rss.xml` 只作输入；`.xml` 只用于这 7 个地址。范围：已审核 + 可见分类。文本 XML 转义，XML 1.0 不允许的字符删除。`Cache-Control: no-cache`、`nosniff`。参数（如 `page`）404。`view.rss=2`（静态）属于 B16。

#### 4.3 自定义标签页 `label/<文件>`（`Label.php:9-25`）

- 旧版：`<文件>` 取自操作名（`file` 参数覆盖），文件 `template/<模板>/label/<文件>.html` 不存在或含 `/` → `error('非法请求')`；存在时**按模板语言执行**。
- 新版：**不读取、不解析、不执行任何模板文件**。当前没有内置标签页（模板来源 D9-a 未定），所有 `label/*` 返回 **404 固定页"非法请求"**（`no-store`）——旧版在本仓库同样全部报错（模板目录只有一个空文件）。模板转换批次在这里登记随代码评审发布的内置页面即可。

#### 4.4 帮助 `help/index`（`Help.php:13-18`）

- 规范地址 `help/index.html`（`help`、`help.html` 等只作输入）；固定结构：站名、站内搜索说明、视频搜索/地图/RSS 链接，不回显任何参数；查询串参数 404。

#### 4.5 版权提示 `vod/copyright`（`Vod.php:74-78`）

- 规范地址 `vod/copyright/id/<n>.html`；与详情页相同的存在/审核/分类可见/游客详情权限判定（不存在 → 404），不看 `vod_copyright` 标记（旧版同样不看）。显示视频名与旧配置 `app.copyright_notice`（没有旧配置或为空时为仓库值"该视频由于版权限制，暂不提供播放。"，`maccms.php:74`）。视频需要访问密码或详情因版权不展示（`copyright_status=2`）时仍显示提示页，但标题为固定的"版权提示"，不输出视频名。200、`noindex`。
- 详情页 `copyright_status=2` 与播放页 3/4 的版权处理已在 B05/既有详情中实现，本批不改。

#### 4.6 公告

旧代码没有独立的公告入口（没有 Notice 控制器、路由、配置项）；旧站的"公告"由模板以自定义标签页或文章分类实现（D9-a）。新版**不新增入口**：自定义标签页按第 4.3 节、文章分类按 B05 的 `art/type` 承接。

#### 4.7 二维码 `qrcode/index`（`Qrcode.php:14-24`）

- 参数 `url`（ThinkPHP `input()`：GET < POST < 路径参数，原值，不 trim/urldecode）。
- 校验：`QrcodeImages.isLegacyValidUrl` 对齐 PHP `filter_var($url, FILTER_VALIDATE_URL)`（FILTER_SANITIZE_URL 会删除的字符出现即不合法；必须有 scheme；http/https 必须有合法主机名或 IPv6；没有主机时只有 mailto/news/file 合法；userinfo 字符集），由 60 个 PHP 8.0.30/7.3.33 黄金向量锁定（两版结果相同）；另限 ≤ 1024 字节。
- 合法：本地生成（ZXing core 3.5.3 编码矩阵 + 自写 1 位灰度 PNG，不经 `javax.imageio`），纠错 M、每模块 10 像素、静区 2 模块；`image/png`、`Cache-Control: max-age=86400, public`、`nosniff`。不合法：**200 空 body**（`text/plain;charset=UTF-8`，`no-store`）。

#### 4.8 桌面快捷方式 `ajax/desktop`（`Ajax.php:123-150`）

- 参数 `name`、`url`（`mac_param_url`：`htmlspecialchars(urldecode(trim()))`，PHP 7.3/8.0 默认 `ENT_COMPAT`）；`name` 为空或 `0` 时用站名与站点地址（旧版 `"http://" . site_url`，新版为 base-url 去掉末尾 `/`）；`url` 不以 `http` 开头时加 `http://`。
- 正文与旧版**逐字节相同**（`[InternetShortcut]` 起，CRLF 换行、每行前 8 个空格；`&` 已被转成 `&amp;`，与旧版相同）。`application/octet-stream`、`no-store`、`nosniff`；`Content-Disposition: attachment; filename="<ASCII 兜底>.url"; filename*=UTF-8''<百分号编码>.url`（RFC 6266/5987）。
- `name`/`url` 含 CR、LF 等控制字符（或 U+2028/2029）、解码后不是合法 UTF-8 → **400**，不输出文件。

#### 4.9 联想词 `ajax/suggest`（`Ajax.php:88-121`）

- 开关：旧 `app.search != '1'`（启动时读旧配置，没有旧配置按仓库值 1）**或** `maccms.site.search.enabled` 关闭 **或** `maccms.content.search.enabled` 关闭时，输出 `{"code":999,"msg":"联想搜索功能关闭中"}`（200、`no-store`）。默认三者有两个关闭，所以默认关闭。
- 参数：`mid`（intval）∈ `1 2 3 8 9 11`，`wd` 非空，否则 `{"code":1001,"msg":"参数错误"}`；`limit`（intval）< 1 时 20，**新版上限 50**；`wd` 超过 64 码点或含控制字符 → 1001。
- 查询：`<前缀>_name LIKE %wd% OR <前缀>_en LIKE %wd%`（wd 为 htmlspecialchars 后的值，按字面值匹配），按 `<前缀>_id desc`；只含已审核 + 可见分类；受并发许可与执行时限（503 → `{"code":503,"msg":"服务暂时不可用"}`、HTTP 503、`Retry-After: 5`）。
- 输出（旧 JSON 同形）：`{"code":1,"msg":"数据列表","page":1,"pagecount":…,"limit":…,"total":…,"list":[{"id","name","en","pic"}],"url":…}`；专题沿用旧 `Topic::listData` 的行为：返回整行、以 topic_id 为键，另加 `pic`；角色每项另有 `"data":null`（旧 `Role::listData` 附加的关联视频，字段中没有 role_rid）。`pic` 经 `ImageUrlNormalizer`（W2 关闭时外部图片为空串）；`url` 为 Java 承接的查询串形式 `<base 路径>index.php/<模块>/search.html?wd=mac_wd`（旧版为路径参数形式 `…/search/wd/mac_wd.html`，前端脚本同样替换 `mac_wd`）。`application/json;charset=UTF-8`、`no-store`。

#### 4.10 `ajax/data`（`Ajax.php:21-86`）

- 参数（`mac_param_url`，路径参数被查询串覆盖）：`mid` ∈ `1 2 3 8 9 11`（否则 `{"code":1001,"msg":"参数错误"}`）；`limit` ∈ `10 20 30`，否则 10；`page` 1..20，否则 1；`tid`（只用于 mid 1/2）：分类存在时顶级分类取其直接子分类（不含本身；没有子分类时为旧版的 `IN ('')`，即 0），否则取本身，条件 `type_id IN … OR type_id_1 IN …`（`:42-50`）。
- 查询：已审核 + 可见分类，`<前缀>_time desc`（并列按主键）。
- 输出（旧 JSON 同形）：整行字段（去掉访问密码列、`<前缀>_time_hits`、`<前缀>_time_make`，视频另去掉 8 个播放/下载列），旧 `listData` 附加的 `type`（分类整行，`type_extend` 经 json_decode/encode，顶级分类带 `childids`，子分类带上级的 `type_1`）与 `type_1`（上级分类）照旧；角色另有 `data`（关联视频整行与其 `type`/`type_1`，只在已审核且分类可见时给出，与视频一样不含播放/下载列，否则 `null`）；`<前缀>_time`、`_time_add` 格式化为 `Y-m-d H:i:s`；追加 `detail_link`（站内详情地址，含 base-url 路径前缀）与 `<前缀>_pic/_pic_thumb/_pic_slide`（经 `ImageUrlNormalizer`，缺少的键追加在末尾，空值为空串，W2 关闭时外部图片为空串）；专题以 topic_id 为键（列表为空时为 `[]`）。会员组 `group` 不输出。`application/json;charset=UTF-8`、`Cache-Control: no-cache`。
- 旧版在 PHP 8 下 mid=1/2 恒为 500（先把 `vod_time` 格式化成字符串，再拼详情地址时 `date('Y', 字符串)` 抛类型错误，`common.php:1881`）；PHP 7.3 只告警并输出。新版按 7.3 的输出对齐（`AjaxGoldenMySqlIT`）。

### 5. 页面结构与样式（MACC-55 整合）

后端交付页面结构、语义 class 与状态。按用户 2026-10-17 的指示（前台沿用现有统一极简样式，不做新设计，不再安排独立页面美化任务），三页样式不再交给前端单独实施，由 Claude 在整合时按现有 `site.css` 做最小处理：DOM、class、路由、数据、组件、布局与 `trial.js` 都不变，只在 `site.css` 末尾追加 "B06（MACC-55）" 一段——

- 页面根 `min-width: 0`，`h1` 与其他内容页一致（`--site-font-size-xl`、行高 1.3、上下 `--site-space-3`）；地图分区标题 `h2.map-section__title` 与搜索页结果标题一致（`--site-font-size-lg`），保持 h1 > h2 的层级；
- 标题、地图链接、版权提示与返回链接 `overflow-wrap: anywhere`。依据：未加这段时，把标题与链接换成 120 个无空格字符后四种宽度都横向溢出（+1360～+2256px，本地实测），加后为 0；正常合成数据下加前加后都不溢出；
- 正文与提示框沿用 `detail-content`、`notice`；不新增颜色、字体、图片、动画或断点。`SiteStylesheetTest.b06MiscPagesWrapLongText` 静态兜底这几条规则。

站点头部的分类导航与搜索表单（`site-nav`、`site-search`，`layout/page.jte`）在全站都没有专门样式，三页与 B05 各页一致，本任务不改。

| 页面 | 根元素与区块 | 状态 |
|---|---|---|
| 地图 `map/index` | `section.map-page` > `h1.map-page__title` + `section.map-section[aria-labelledby=map-vod]`（每模型一个：`map-vod`、`map-art`、`map-actor`、`map-website`，标题 `h2.map-section__title#map-vod` 等）> `ul.map-tree` > `li.map-tree__item` > `a` + 嵌套 `ul.map-tree`；末尾 `section.map-section[aria-labelledby=map-links]` 的 `ul.map-links` > `li.map-links__item` > `a` | 某模型没有可见分类时该 section 不输出 |
| 帮助 `help/index` | `section.help-page` > `h1.help-page__title` + `div.detail-content`（段落与 `ul.help-page__links`） | — |
| 版权 `vod/copyright` | `section.copyright-page` > `h1.copyright-page__title`（视频名或"版权提示"）+ `p.notice.copyright-notice` + `p.copyright-page__back` > `a`（返回首页） | — |
| 标签页 404 | 既有错误页（`error.jte`），标题"非法请求" | — |

所有页面根元素带 `data-site-page`（`map`、`help`、`copyright`）。最终整合验收覆盖：320/360/768/1280 无横向溢出、键盘 Tab 次序与可见焦点、`prefers-reduced-motion`。

### 6. 覆盖表：旧参数/路由 → Java 入口 → 测试

| 旧入口 / 参数 | Java 入口 | 测试 |
|---|---|---|
| `mac_get_client_ip`（伪造头、可信代理、无代理、IPv4/IPv6、多跳、多个头、无效头、配置校验） | `common.clientip.ClientIpResolver`、`IpAddress`、`IpNetwork` | `ClientIpResolverTest`（单元）、`ProvideRealHttpTest`（真实 HTTP，可信代理 127.0.0.1：放行/拒绝/伪造六种头/多跳/多个头/无效项/超过 32 跳与超长头/左侧填充/IPv6/HEAD）、`ProvideRealHttpNoProxyTest`（真实 HTTP，应用只监听 `::1`、不配可信代理：任何头都不能冒充白名单） |
| Provide 路由、后缀、路径参数、参数优先级、`comment/index`、开关、`closed`、503 | `openapi.api.ProvideController` → `openapi.application.ProvideService` | `ProvideApiTest`（MockMvc，查询参数语义逐项断言）、`ProvideGoldenMySqlIT` |
| Provide 参数 `ac at ids t isend h wd year from pg pagesize sort_direction`、`typefilter`、`imgurl`、`upload.protocol`、`cachetime`、`charge`、`auth`、`datafilter` | 同上 + `content.application.LegacyRowReadService`（`LegacyRowMapper.xml`） | `ProvideGoldenMySqlIT`（142 个 api 用例：95 个逐字节相同，47 个按第 7 节差异断言）、`ProvideApiTest`、`ProvideSettingsTest`、`ProvideGoldenVersionsTest`（PHP 8.0 与 7.3 黄金一致性） |
| Provide 受限内容（不可见分类、未审核、访问密码、XML 特殊字符、角色关联视频） | 同上 | `ProvideGoldenMySqlIT` 的 `diff.*`（phase-diff.sql；详情密码视频与文章带分集剧情、分页标题与备注）、`LegacyRowProtectTest`、`ReadOnlyGuardrailsTest.legacyRowMapperNeverSelectsPasswords`、`ProvideGoldenMySqlIT.fullColumnListsMatchInformationSchema` |
| `map/index`（含单段、兼容模式） | `site.web.SiteMiscController` → `MiscPages` | `SiteMiscPagesMvcTest`、`b06_site_check.py` |
| `rss/index`、`baidu/google/so/sogou/bing/sm` | 同上 → `SiteFeeds` | `SiteMiscPagesMvcTest`（XML 可解析、转义、命名空间、条目数）、`b06_site_check.py`（8 组部署） |
| `label/<file>` | `site.web.SiteAjaxController` | `SiteMiscPagesMvcTest`、`b06_site_check.py` |
| `help/index`、`vod/copyright` | `SiteMiscController` | `SiteMiscPagesMvcTest`、`b06_site_check.py` |
| `qrcode/index` | `SiteAjaxController` → `QrcodeImages` | `QrcodeImagesTest`（PHP 8.0/7.3 各 60 个 URL 向量、与 phpqrcode PNG 解码对照）、`SiteAjaxMvcTest`、`AjaxGoldenMySqlIT` |
| `ajax/desktop` | `SiteAjaxController` | `SiteAjaxMvcTest`（逐字节、注入 400、文件名编码）、`AjaxGoldenMySqlIT`（5 个 PHP 黄金逐字节） |
| `ajax/suggest` | `SiteAjaxController` → `AjaxPages` → `LegacyRowReadService` | `SiteAjaxMvcTest`（关闭态）、`SiteAjaxSuggestMvcTest`（打开态、子目录前缀、上限、专题/角色、503）、`AjaxGoldenMySqlIT` |
| `ajax/data` | 同上 | `SiteAjaxMvcTest`（参数归一、tid 范围、JSON 形态）、`SiteAjaxExternalImagesMvcTest`（W2 关）、`AjaxGoldenMySqlIT`（PHP 7.3 黄金） |
| `ajax/hits/digg/score/referer/pwd/verify_check` | 不承接（404） | `SiteAjaxMvcTest.otherAjaxActionsAndRewriteFormsAre404` |

PHP 黄金样本：`src/test/php/provide_golden.php` 以 php-cgi 执行 `legacy_http_child.php`，跑**完整的 ThinkPHP 路由与旧控制器**（入口常量与 `api.php`/`index.php` 相同，配置目录指向临时副本与一次性库，不改任何旧 PHP 文件），记录 184 个用例的状态码、响应头与正文字节：`src/test/resources/openapi/provide/php80-golden.json`（PHP 8.0.30）、`php73-golden.json`（PHP 7.3.33）。两版除时钟相关、PNG 压缩与 ajax/data 的 PHP 8 类型错误外逐字节相同（`ProvideGoldenVersionsTest`）。二维码 URL 向量：`src/test/php/qrcode_url_golden.php` → `src/test/resources/site/qrcode/php{80,73}-url-vectors.json`。

### 7. 与旧版的差异

| 方面 | 旧 PHP | 新 |
|---|---|---|
| 客户端 IP | 依次信任 CF-Connecting-IP、Ali-CDN-Real-IP、Client-IP、Proxy-User、X-Real-IP，伪造即可冒充白名单；IPv6 对端为 `0.0.0.0` | 只信连接对端；可信代理网段内才采用一个配置的转发头（第 2 节），头给不出客户端时为 `0.0.0.0`（白名单拒绝）；IPv6 正常解析 |
| Provide 白名单的域名项 | `gethostbyname` 解析（DNS） | 不解析，永不命中（启动日志提示项数） |
| Provide 白名单拒绝 | 200 `域名未授权` | 403 同一文本（D-B06-5） |
| Provide/ajax 的 MIME | `text/html`（`api.php:9`、`index.php:9`） | JSON `application/json`、XML `text/xml`、文本 `text/plain`，均 `nosniff`（D-B06-1）；快捷方式为 `application/octet-stream`（旧版声明了但被 ThinkPHP 覆盖成 `text/html`） |
| Provide 行范围 | 只由 `datafilter` 决定（为空时含未审核；不看分类可见性）；角色忽略 datafilter | 始终"已审核 + 可见分类"（D-B06-2） |
| Provide `datafilter` | 任意 SQL | 白名单语法，否则启动失败 |
| Provide `total`（art/actor/website/role） | 不带 datafilter（`Art.php:50-53` 等），未审核行也计入，与列表不一致 | 与列表同一条件（`pagecount` 随之变化） |
| 访问密码 | 整行输出密码列与受保护的正文/地址 | 不输出密码列；受保护的正文、分集剧情、文章分页标题/备注与地址为空串（D-B06-3） |
| 角色关联视频 | 任意状态 | 已审核且分类可见，否则 `null` |
| `cachetime` | 服务端缓存整段输出（期间返回旧数据） | 不做服务端缓存，转为 HTTP 缓存头（`charge=1` 时 private）（D-B06-4） |
| `wd`/`from`/联想词的 LIKE | 不转义 `%`、`_`（`wd=%` 命中全部） | 按字面值匹配 |
| 非法参数 | SQL 错误或 PHP 8 类型错误（500，可能回显 SQL）；`h`/`pg` 非数字按 strtotime/intval 处理 | 400 固定文本；见第 3.3 节逐项规则 |
| Provide XML | 不转义（`&`、`<`、`]]>` 会产生不合法的 XML） | 非 CDATA 文本与属性转义，CDATA 中 `]]>` 拆开，非法控制字符删除；合法数据的输出逐字节相同 |
| `detail_inc_hits` | `ac=detail&ids=单个整数` 时 `vod_hits+1` | **本批不写计数（临时差异，B11 补齐）** |
| 地图、RSS、帮助、版权页的结构 | 模板决定（仓库模板缺失，本仓库中全部报"模板文件不存在"） | 固定结构（第 4.1–4.5 节，D-B06-7） |
| 站点级 RSS 的地址 | `rss/index.xml` 等（动态页） | 相同；另接受 `.html`、无后缀与单段形态作输入 |
| 自定义标签页 | 执行模板文件 | 不执行任何模板；无内置标签页时一律 404"非法请求" |
| 版权页 | 显示模板（含视频信息） | 需要访问密码或详情因版权不展示时不输出视频名；`copyright_notice` 按文本转义 |
| 二维码 PNG | phpqrcode | ZXing 生成，字节不同；解码文本与尺寸相同；输入另限 1024 字节；不合法时 MIME 为 `text/plain`（旧 `text/html`） |
| 快捷方式 | 名称、地址中的换行会注入快捷方式的行；文件名原始字节写进响应头（MSIE 时 urlencode） | 控制字符 400；RFC 5987 文件名；默认地址为 base-url |
| 联想词开关 | 只看 `app.search` | 另要求 `MACCMS_SITE_SEARCH_ENABLED` 与 `MACCMS_CONTENT_SEARCH_ENABLED`（默认关闭） |
| 联想词范围与上限 | 不看状态与分类；`limit` 无上限 | 已审核 + 可见分类；`limit` ≤ 50；`wd` ≤ 64 码点 |
| 联想词 `url` | `…/search/wd/mac_wd.html`（路径参数形式，Java 不承接） | `…/search.html?wd=mac_wd`（查询串形式） |
| ajax/data 的视频与文章 | PHP 8 下恒为 500；PHP 7.3 输出 | 按 7.3 的输出 |
| ajax/data 的范围与字段 | 不看分类可见性；输出密码列、会员组 `group`；角色 `data` 含播放/下载与密码列 | 已审核 + 可见分类；不输出密码列与 `group`；角色 `data` 与视频一样去掉播放/下载列 |
| ajax 图片 | `mac_url_img`：空值为站点根路径 `/`；协议相对地址原样 | `ImageUrlNormalizer`（06 文档）：空值为空串；协议相对地址按 base-url 补全协议；W2 关闭时外部图片为空串 |
| 其他 `ajax/*` 操作 | 写计数、校验密码、验证码 | 不在本批（404，交还 PHP） |

B05 及以前登记的差异（可见分类、`type_id=0`、密码字符串 `"0"` 视为有密码等）保留。

### 8. 队长最终裁定（2026-10-17）

以下决定在 B06 联合独立验收中确认；第 7 节保留与旧 PHP 的具体差异。

| 编号 | 最终决定 |
|---|---|
| D-B06-1 | JSON 使用 `application/json`，Provide XML 使用 `text/xml`，均加 `nosniff`。 |
| D-B06-2 | 始终只输出“已审核 + 可见分类”；datafilter 仅接受白名单语法，不执行配置 SQL。 |
| D-B06-3 | 不输出密码列；访问密码保护的正文、分集剧情、文章分页标题/备注与地址置空。积分与会员组权限不额外改变 Provide 输出，由 charge 白名单控制协议访问。 |
| D-B06-4 | cachetime 转为 HTTP 缓存头；charge=1 时为 private，不引入服务端缓存。 |
| D-B06-5 | 白名单拒绝返回 403 与原文“域名未授权”；域名项不进行 DNS 解析。 |
| D-B06-6 | Provide wd/from 使用字面值匹配、执行时限与并发护栏，不绑定搜索开关。低选择性与深分页成本继续登记为限制。 |
| D-B06-7 | 地图/RSS/帮助/版权页采用第 4 节固定结构，复用现有样式并只做必要适配。当前快照没有标签模板，label/* 的 404“非法请求”是已验证行为；后续模板批次收到模板来源后再确定具体标签页，不执行不可信模板。 |
| D-B06-8 | ajax/data 的视频与文章按 PHP 7.3 输出对齐，PHP 8 的旧类型错误不保留。 |
| D-B06-9 | 联想词共用搜索开关，默认关闭；limit 上限 50。 |

分页零值补充：pg=0 已有 vod.pg0 黄金与 ProvideApiTest，归一为第一页。pg=00 是不同输入：旧视频控制器的 empty('00') 为 false，计算负偏移，独立使用一次性库运行 PHP 7.3/8.0 均返回 500；Java 负偏移护栏拒绝并返回 400。这属于第 7 节非法参数差异，不能宣称由 vod.pg0 覆盖。

### 9. 验证（最终树）

所有数据访问只用一次性库 `maccms_it_<时间>_<随机>`（创建前校验库名、导入前确认无表，结束时删除并查 `information_schema.schemata` 确认不存在），不连接 `maccms_dev` 或任何已有库；浏览器只访问本机应用与本地反向代理，所有非本机请求在浏览器内拦截并记录。最终计数以交付评论中对最终提交的记录为准。

| 检查 | 方式 | 证据 |
|---|---|---|
| 单元/契约/MockMvc/真实 HTTP | `./mvnw clean verify` | 交付评论 |
| MySQL 集成与黄金对照 | `./mvnw verify -Pmysql-it`（含 `ProvideGoldenMySqlIT`、`AjaxGoldenMySqlIT` 与既有 IT） | 交付评论 |
| PHP 黄金 | `provide_golden.php`（PHP 8.0.30、7.3.33 各一份）、`qrcode_url_golden.php`（两版） | 资源文件 + 上列测试 |
| 真实 HTTP + 浏览器 | `python src/test/browser/b06_site_check.py --out docs/architecture/16-b06-site-check`：已打包应用 × W1/W2 = 00/01/10/11 × 根路径 / 子目录 `/sub/` 共 8 组；Chromium（Playwright）；根路径 11 另查 320/360/768/1280 横向溢出（另做长文本压力：浏览器内把 `main` 中的标题、链接、提示换成 120 个无空格字符后再量）、键盘 Tab 与可见焦点、reduced-motion（`reduce` 下三页没有任何元素带动画或非零过渡，否则记失败） | `16-b06-site-check/summary.json`、`screenshots/` |
| 中规模测量 | `python src/test/perf/b06_perf_run.py --out docs/architecture/16-b06-perf-raw`（30 万行，真实 HTTP，实际 SQL + EXPLAIN） | `16-b06-perf-report.md`、`16-b06-perf-raw/` |

`b06_site_check.py` 每组检查：地图/帮助/版权页的状态码、MIME、`no-cache`、严格 CSP、`nosniff`、canonical 与站内链接前缀（子目录时以 `/sub/` 开头）、页面无外部主机；7 种 RSS/地图 XML 可解析、MIME、`loc` 为 base-url 下的绝对地址；标签页 404；二维码 PNG 与缓存头、非法 URL 空响应；快捷方式 MIME、`filename*`、正文首行、注入 400；ajax/data 的 JSON、`detail_link` 前缀、外链图片只在 W2 打开时出现、不含密码与播放列；联想词 `url` 前缀与 `no-store`；Provide JSON/XML/`closed`/HEAD、缓存头、Provide 图片地址不受 W2 影响；浏览器无 CSP 违规、无外部请求。

性能限制（`16-b06-perf-report.md` 第 0 节）：30 万行时无条件列表约 1.0–1.4 s（全表 COUNT + filesort），深分页与低选择性条件接近 3 s 时限，联想词超过时限返回 503；不做 DDL，保留默认关闭与护栏，调优方向需裁定。

### 10. 回滚

不写库、无数据迁移、零 DDL；回滚即普通 `git revert` 本批提交（不改写历史）。回滚点：已验收 main `2313076`；契约稿 `de890ba`；Provide 提交 `8004bca`；site 提交 `1afe59e`；后端固定交付 `39804d9`（MACC-55 三页样式整合之前）。运行时：`MACCMS_OPENAPI_ENABLED=false`（默认）时不注册 Provide 路由；`MACCMS_SITE_ENABLED=false` 时不注册任何 site 页面；`MACCMS_SITE_SEARCH_ENABLED`/`MACCMS_CONTENT_SEARCH_ENABLED` 关闭时联想词输出关闭提示；`MACCMS_TRUSTED_PROXIES` 留空即回到"只信连接对端"。
