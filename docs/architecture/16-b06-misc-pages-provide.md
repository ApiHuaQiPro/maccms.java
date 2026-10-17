## B06 杂项只读页与 Provide 只读协议：最终契约与旧入口覆盖表（路线图 B06，MACC-30）

> **状态：契约稿（开工首个提交），实现随后在同一分支逐项落地，本文件随实现更新为最终契约。** 第 2–5 节是对外契约（路径、方法、参数、字段、格式、状态码、开关、默认值），第 6 节是旧参数/路由 → Java 入口 → 测试的覆盖表，第 7 节是与旧版的差异，第 8 节是提请队长裁定的事项（已按"建议"实施，裁定不同再改），第 9、10 节是验证与回滚。
> 未部署、未切流量。只读批次：不写库、不登记业务写入、零结构变更、不改 B02/B07 护栏，服务端不发起出站请求（包括 DNS 查询）。
> 依据：`09-roadmap.md` 第 3.2 节（第 148、150 行）、第 4.2 节（W1/W2）、第 5.3 节 B06 行、第 5.5 节 ④；MACC-30 任务描述；`15-b05-readonly-pages.md`（B05 最终契约：单条 RSS、各模块 `ajax_detail/ajax_show/ajax_search`、必要的 `show/search` 页已在 B05，本批直接复用，不重复注册路由）。旧代码行号以当前仓库为准。
> 证据标注沿用 09 方案：**【代码】**仓库代码；**【黄金】**PHP 黄金样本实测；**【待核实】**依赖线上配置、线上模板或数据。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 分支 | `agent/claude/macc-30`（只用普通提交与普通 merge，不 reset/rebase/amend/force） |
| 开工基线（已验收 main，含 B05 合并） | `2313076`（merge MACC-29），包含 B05 固定交付 `967941951bc52664b54a001bf96a946fbd75929e`；平台基线提交 `3c2348d` |
| 前端基线 | 已验收前端 `3100239`（MACC-54，经 B05 整合） |

### 1. 范围、分工与文件归属

| 部分 | 旧入口 | 本批 | 负责 |
|---|---|---|---|
| A 客户端 IP 组件（D2 ④） | `mac_get_client_ip`（`application/common.php:950-990`） | `common.clientip`：只信连接对端，可信代理网段内才采用转发头（第 2 节）；Provide 白名单首先使用，B08/B09/B11 复用 | C |
| B Provide 只读协议 | `api/controller/Provide.php` 全部 7 个操作 | 新模块 `openapi`（03 草稿第 1 节预留）：`vod/art/actor/role/website` 的 `ac=list/videolist/detail`、JSON/XML、`closed`、`cachetime`、`charge` 白名单；`comment`、`index` 与旧版一样输出空响应（第 3 节） | C |
| C 地图、RSS 各格式 | `index/controller/Map.php`、`Rss.php` | `map/index`；`rss/index` 与 `baidu/google/so/sogou/bing/sm`（第 4.1、4.2 节） | C；地图页样式 K |
| D 自定义标签页、帮助、版权、公告 | `Label.php`、`Help.php`、`Vod.php:74-78`（`vod/copyright`） | 第 4.3–4.6 节 | C；帮助/版权页样式 K |
| E 二维码、桌面快捷方式 | `Qrcode.php`、`Ajax::desktop`（`Ajax.php:123-150`） | 本地生成 PNG；`.url` 下载（第 4.7、4.8 节） | C |
| F 联想词、`ajax/data` | `Ajax::suggest`（`:88-121`）、`Ajax::data`（`:21-86`） | JSON（第 4.9、4.10 节） | C |
| 不在本批 | `Ajax::hits/digg/score/referer`（写计数）、`ajax/pwd`（B05 已由签名票据承接）、`verify_check`（B11）、Provide `detail_inc_hits` 计数（B11）、`gbook`/`comment` 页面与写入（B11）、手机模板/繁体（B12） | — | — |

**文件归属**：后端拥有 `openapi` 全部、`common.clientip`、`content` 新增的整行读取（第 3.6 节）、`site` 中新增的控制器/视图模型与新增页面模板（`jte/site/pages/` 下的新文件）。共享的 `jte/site/components/`、`jte/site/layout/`、`static/site/site.css`、`trial.js` 与组件 `@param` 签名**本批不修改**；新页面只用既有 class（`content-grid`/`content-card`、`notice`、`pagination`、`detail-content`）与第 5 节列出的新语义 class，样式由 Kimi 另行实施（第 5 节）。

### 2. 客户端 IP 组件（D2 ④，`common.clientip`）

#### 2.1 部署参数（环境变量，不新增站点配置项）

| 参数 | 环境变量 | 默认 | 取值 |
|---|---|---|---|
| `maccms.client-ip.trusted-proxies` | `MACCMS_TRUSTED_PROXIES` | 空（不信任任何代理） | 逗号分隔的 IPv4/IPv6 地址或 CIDR（如 `127.0.0.1/32,10.0.0.0/8,::1/128`）；项前后空白忽略；不能解析、前缀长度越界、主机位非零（如 `10.0.0.1/8`）、超过 64 项 → **启动失败**，信息只含参数名与项序号 |
| `maccms.client-ip.header` | `MACCMS_CLIENT_IP_HEADER` | `X-Forwarded-For` | `X-Forwarded-For`（列表语义）或单值头 `X-Real-IP`、`CF-Connecting-IP`、`True-Client-IP`、`Ali-CDN-Real-IP`（不区分大小写）；其他值启动失败 |

#### 2.2 解析规则（`ClientIpResolver.resolve(peer, headers)`）

1. **对端** = 连接的对端地址（Servlet `getRemoteAddr()`）。对端不能解析为 IP 字面量时结果为 `0.0.0.0`（与旧版"无合法 IP"时相同）。
2. 对端**不在**可信网段内（含未配置可信代理）→ 结果就是对端；任何请求头都不看。`CF-Connecting-IP`、`Ali-CDN-Real-IP`、`Client-IP`、`Proxy-User`、`X-Real-IP`、`X-Forwarded-For`、`Forwarded` 等全部忽略。
3. 对端在可信网段内，只看配置的那一个头：
   - **`X-Forwarded-For`（列表）**：同名头出现多次时按出现顺序以逗号连接（RFC 9110 第 5.3 节）；按逗号切分、每项去掉前后空白；**从右往左**逐项检查：是可信代理就继续向左；遇到第一个不是可信代理的合法地址，它就是客户端；遇到不能解析的项（空项、端口、方括号、域名、`unknown`、混入的控制字符等）立即停止，结果取它右边最近的一跳（若它就是最右项则取对端）；全部是可信代理时取最左项。项数超过 32 或头总长超过 2048 字节时整个头视为无效，结果为对端。
   - **单值头**：恰好出现一次且整值（去首尾空白）是合法地址 → 采用；缺失、出现多次、不合法 → 对端。
4. 地址规范化：IPv4 为点分十进制（每段 0–255、不允许前导 0，`01.2.3.4` 不合法）；IPv6 按 RFC 5952 小写压缩形式；IPv4 映射的 IPv6（`::ffff:1.2.3.4`）归一为 IPv4；带区域标识（`%eth0`）不合法。**只做字面量解析，从不做 DNS 查询**（解析器不调用 `InetAddress.getByName` 处理非字面量）。
5. 组件不记录任何地址；日志只在启动时记录"可信代理项数"。

**无可信代理时的局限（须写进部署说明）**：部署在 CDN/反向代理后面而未配置 `MACCMS_TRUSTED_PROXIES` 时，所有请求的客户端 IP 都是代理地址；Provide 白名单只能按代理地址放行（等于对所有经代理的访问者放行或全部拒绝）。配置了错误的可信网段（例如把 `0.0.0.0/0` 设为可信）会让任何人伪造 `X-Forwarded-For`，部署说明必须写明只填自己的代理出口网段。

### 3. Provide 只读协议（`openapi` 模块）

#### 3.1 路由与开关

| 项 | 契约 |
|---|---|
| 路径 | `/api.php/provide/<操作>`，`<操作>` ∈ `vod art actor role website comment index`；允许的形态（与 ThinkPHP 默认 PATH_INFO 一致）：`/api.php/provide/vod`、`/vod/`、`/vod.html`（以及 `.htm/.shtml/.shtm/.xml` 后缀，`application/config.php:86`），后接成对的路径参数 `/<键>/<值>`（如 `/api.php/provide/vod/at/xml/`）；路径参数与查询串同名时**路径参数优先**（`thinkphp/library/think/Request.php:658` 的 `array_merge(…get, route)`） |
| 方法 | GET、HEAD；POST 的表单参数与旧版一样参与（`input()` 合并 POST，Request.php:646-658），优先级 GET < POST < 路径 |
| 模块开关 | 新部署参数 `maccms.openapi.enabled`（`MACCMS_OPENAPI_ENABLED`，默认 `false`）：关闭时不注册任何 Provide 路由（交还 PHP，本地 404）。打开后各操作再按旧配置 `api.<模块>.status` 判定（仓库值全部为 0） |
| 旧配置 | 启动时经 `LegacyConfigService` 读取 `extra/maccms.php` 的 `api.vod/art/actor/role/website`（`status charge auth pagesize imgurl typefilter datafilter cachetime`，vod 另有 `from detail_inc_hits`）与 `upload.protocol`；未配置旧配置目录时全部视为 `status=0`；值不受支持（第 3.4 节）时**启动失败**，信息只含键名 |
| 站点关闭 | 旧 `api/Base` 的 `site_status==0` 分支为空（`application/api/controller/Base.php:14-17`）：Provide 不受站点关闭影响，新版相同 |

#### 3.2 判定顺序与状态码

| 顺序 | 条件（旧代码） | 新版响应 |
|---|---|---|
| 1 | `status != 1`（`Provide.php:23-26` 等） | **200** `closed`（`text/plain; charset=utf-8`，`Cache-Control: no-store`）；不查库 |
| 2 | `charge == 1`：客户端 IP（第 2 节）不在白名单（`Provide.php:28-38`、`:785-804`） | **403** `域名未授权`（`text/plain`，`no-store`）；不查库 |
| 3 | 参数不合法（第 3.3 节） | **400** `参数错误`（`text/plain`，`no-store`，不回显输入） |
| 4 | 查询超时、连接故障、包含匹配并发上限 | **503** `服务暂时不可用`（`text/plain`，`no-store`，`Retry-After: 5`） |
| 5 | 正常 | **200** JSON `application/json; charset=utf-8` 或 XML `text/xml; charset=utf-8`，均带 `X-Content-Type-Options: nosniff`；缓存头见第 3.5 节 |

`comment`、`index`：旧方法体为空（`Provide.php:16-19`、`:780-783`），ThinkPHP 输出空响应 → 新版 **200 空 body**（`text/plain`，`no-store`），不看开关与白名单（旧版同样不看）。

**白名单**（`charge=1`）：列表 = `127.0.0.1` + `auth` 按 `#` 切分、去首尾空白后的每一项。旧版对不是 IP 形态的项调用 `gethostbyname` 再比较（`:793-795`，DNS 查询）；新版**不解析域名**（服务端不出站），这类项与带端口的项（`mac_string_is_ip` 接受 `1.2.3.4:80`，`common.php:49-51`）都永不命中，启动时日志只记录"忽略 N 项"。IP 项按第 2.2 节第 4 条规范化后与客户端 IP 精确比较（IPv6 项可用，旧版 `ip2long` 只能表达 IPv4）。

#### 3.3 参数（值先按旧 `input('','','trim,urldecode')` 处理：去首尾空白后再 `urldecode` 一次，`Provide.php:13`）

| 参数 | 适用 | 旧语义（【代码】行号） | 新版 |
|---|---|---|---|
| `ac` | 全部 | `videolist`/`detail` → 整行字段（`:127-129`）；其他（含缺失）→ 列表字段 + 分类表 `class`（`:247-263`）。art/actor/role/website 只认 `detail` | 相同 |
| `at` | vod | `xml` → XML（`:133-137`），其他 → JSON；其余模块只有 JSON | 相同 |
| `ids` | 全部 | `<主键> IN (explode(','))`，各项作字符串比较（MySQL 隐式转换） | 相同（各项作字符串绑定）；项数 ≤ 1000，超出 400 |
| `t` | vod/art/actor/website | `typefilter` 为空或 `strpos(typefilter, t) !== false`（**子串**）时 `type_id = t`，覆盖 `typefilter` 的 IN（同一个 `$where['type_id']`，`:52-56`） | 相同（子串判定原样保留）；role 表没有 `type_id`，旧版 SQL 报错 → 新版 400 |
| `isend` | vod | 出现即生效：`vod_isend = (isend == 1 ? 1 : 0)`（PHP 宽松比较，`:58-60`） | 按 PHP 8 宽松比较（`PhpValues`），出现但为空 → 0 |
| `h` | 全部 | vod：`!empty(h)`；其余：`!empty(intval(h))`。下限 vod 为 `strtotime('-h hours')`，其余为该时刻**当日 0 点**；上限为明天 0 点（`Asia/Shanghai`，`application/config.php:42` 的 `PRC`）；`time > 下限 AND time < 上限`（`:61-69`、`:376-384` 等） | 只接受十进制数字（≤ 6 位）；`0`（vod 为恰好 `0`）不筛选；非数字 400（差异） |
| `wd` | 全部 | `<名称> LIKE %wd%`（不转义 `%`、`_`，`:70-72`） | 按字面值匹配（`ESCAPE '!'`，与 B05 模块搜索相同，差异）；≤ 64 码点；受 `MAX_EXECUTION_TIME(3000)` 与并发许可约束，**不受搜索开关约束**（Provide 本身默认关闭，见第 8 节 D-B06-6） |
| `year` | vod | 4 字节：`intval` 的单值；9 字节：两端 `(int)substr`，必要时交换，夹在 1900..今年+3 后展开；其他长度：`IN ('')`（`:74-95`） | 逐项相同（含"其他长度时 `IN ('')`"） |
| `from` | vod | 配置 `api.vod.from` 为空且参数长度 > 2 时代替配置；按逗号去重、去空后 `vod_play_from LIKE %x%` 的 OR（`:96-111`）；输出时只保留这些播放组（`:194-243`、`vod_url_deal` `:155-177`） | 条件与输出筛选相同；LIKE 按字面值；项数 ≤ 20 |
| `pg` | 全部 | vod：`empty` 时为 1，否则原样参与 `LIMIT`；其余：`empty(intval(pg))` 时为 1 | 十进制数字；0 或空为 1；> 1000000 或非数字 400（旧版非数字在 PHP 8 下 500） |
| `pagesize` | vod | `> 0` 时 `min((int), 100)`，否则配置 `pagesize`（`:118-121`） | 十进制数字 1..∞ → `min(n, 100)`；`0`/空为配置值；非数字 400 |
| `sort_direction` | vod | `asc`，否则 `desc`（`:123-124`） | 相同 |
| 其他键 | — | 忽略 | 忽略（不进入任何查询） |

#### 3.4 旧配置的取值规则

| 键 | 规则 |
|---|---|
| `status`、`charge`、`detail_inc_hits` | PHP 宽松比较 `== 1`；整数或数字字符串；其他启动失败 |
| `pagesize` | 正整数（1..1000）；空串按 20；其他启动失败（旧版 0 会除零出错） |
| `imgurl` | 原样作为相对图片地址的前缀（`:190-192`）；只接受空串或 `http://`、`https://`、`//` 开头、不含空白与控制字符 |
| `typefilter` | 空，或逗号分隔的整数 |
| `datafilter` | 旧版是原样拼进 WHERE 的 SQL（`:112-114`、`Vod.php:37-48`）。新版**不执行任何配置中的 SQL**，只接受空串或下列语法：`<列> <运算符> <整数>` 用 `AND` 连接（不区分大小写，空白任意），列 ∈ 该模块的 `status/level/lock`（vod 另有 `isend/copyright/plot`），运算符 ∈ `= != <> > >= < <=`；仓库值 `vod_status=1` 等都在其中。其他写法启动失败（信息只含键名）。**无论 datafilter 写了什么，新版都另加"已审核 + 可见分类"（第 3.6 节）** |
| `cachetime` | 空或非负整数 |
| `from` | 空，或逗号分隔、每项不含控制字符 |
| `auth` | 见第 3.2 节白名单 |

#### 3.5 输出格式

- **JSON**（`json_encode(..., JSON_UNESCAPED_UNICODE)` 后整串 `mac_filter_tags`，`:136-138`、`common.php:2898-2915`）：对象键顺序、数值/字符串类型、`\/` 转义、`page`/`limit` 的类型（参数原样字符串或整数）、`pagecount` 的浮点表示、`type_name` 缺失时为 `null` 等，逐字节以 PHP 黄金为准（`ProvideGoldenMySqlIT`）。`mac_filter_tags` 对整串按顺序把 `{:`、`<script`、`<iframe`、`<frameset`、`<object`、`onerror` 不区分大小写替换为 `*`——新版逐字节照做（它会改写正文中的这些子串，也会把 JSON 键或值中出现的这些子串改掉，这是旧协议的一部分）。
- **XML**（vod，`vod_xml` `:267-342`）：元素顺序、CDATA 位置与旧版逐字节相同；旧版不转义文本与属性（`<type>`、`<pic>`、`<ty>` 中的 `<`、`&` 会破坏 XML，CDATA 中的 `]]>` 同样），新版对**非 CDATA 文本与属性值**做 XML 转义、把 CDATA 中的 `]]>` 拆成 `]]]]><![CDATA[>`（差异：只有含这些字符的数据才与旧版字节不同；属性只有 `flag`、`id`、`page` 等，顺序与旧版相同，第 7 节）。
- **列表字段**（`ac` 为其他值）：vod `vod_id,vod_name,type_id,type_name,vod_en,vod_time,vod_remarks,vod_play_from`；art `art_id,art_name,type_id,type_name,art_en,art_time,art_author,art_from,art_remarks,art_pic`；actor `actor_id,actor_name,type_id,type_name,actor_en,actor_area,actor_time,actor_alias,actor_sex,actor_pic`；role `role_id,role_name,role_rid,role_en,role_actor,role_time,role_pic` + `douban_id,vod_name,vod_director`（关联视频）；website `website_id,website_name,type_id,type_name,website_en,website_time,website_area,website_lang,website_pic`。外层 `code,msg,page,pagecount,limit,total,list,class`。
- **整行字段**（`ac=videolist/detail`）：旧版 `SELECT *`。新版明确列出该表全部列（`install.sql` 的列顺序），**去掉密码列**：`vod_pwd, vod_pwd_url, vod_pwd_play, vod_pwd_play_url, vod_pwd_down, vod_pwd_down_url`、`art_pwd, art_pwd_url`（差异，第 8 节 D-B06-3）；`type_name` 追加在最后（与旧版 `$v['type_name']=` 新增键的位置相同）。
- **时间**：`<pre>_time` 按 `Asia/Shanghai` 格式化为 `Y-m-d H:i:s`（`:185` 等）。
- **图片**：`mac:` 前缀换成协议（`imgurl` 以 `https` 开头时 `https:`，否则 `upload.protocol` + `:`，`:806-816`）；其他非 `http`、非 `//` 开头的非空值前面加 `imgurl`（`:187-192`）。Provide 面向服务器端采集程序，**不适用 W2**（W2 只约束页面让浏览器访问的地址）。
- **分类表 `class`**：vod JSON 为全部模型的分类（旧版此处不按 `type_mid` 过滤，`:251-261`），XML 只有 `type_mid=1`（`:323-334`）；art/actor/website 按各自模型；role 为空数组。按 `type_id` 升序（`Type.php:352`）；`typefilter` 非空时只含其中的分类。新版只列可见分类（第 3.6 节）。
- **缓存**：旧 `cachetime > 0` 时按"md5(参数)"缓存整段输出 N 秒（`:40-42`、`:139-141`）。新版**不做服务端结果缓存**（02 规范第 3 节"不新增应用内结果缓存"），改为 HTTP 缓存头：`cachetime > 0` 且 `charge=0` → `Cache-Control: public, max-age=N`；`cachetime > 0` 且 `charge=1` → `private, max-age=N`（避免共享缓存把白名单内容交给任何人）；`cachetime` 为 0/空 → `no-store`。差异见第 8 节 D-B06-4。
- **`detail_inc_hits`**：旧版 `ac=detail` 且 `ids` 为单个整数时 `vod_hits + 1`（`:143-150`）。本批**不写计数**（只读批次），开启时也只读；B11 补齐（第 7 节临时差异）。

#### 3.6 数据范围与受限内容

- 行范围：`<pre>_status = 1` **且**所属分类可见（与 content API 相同的 `CategoryCatalog`：分类启用、游客有列表权限位、上级链可见；role 按关联视频不限分类，只要求 `role_status = 1`）。旧版只由 `datafilter` 决定（仓库值为 `status=1`；role 的 datafilter 在 `Role::listData` 中被忽略，`Role.php:37-47`，未审核角色也会输出）。
- 角色的 `douban_id/vod_name/vod_director` 只在关联视频已审核且分类可见时给出，否则为 `null`（旧版取任意状态的视频，`Role.php:49-61`）。
- 受访问密码保护的内容：`vod_pwd` 非空的行，`vod_content`、`vod_play_url`、`vod_down_url` 输出空串；`vod_pwd_play` 非空时 `vod_play_url` 为空串、XML 的 `<dl>` 中各组 CDATA 为空；`vod_pwd_down` 非空时 `vod_down_url` 为空串；`art_pwd` 非空时 `art_content` 为空串。密码列本身从不读出（只以 `CHAR_LENGTH(...) > 0` 的布尔结果参与）。积分、会员组权限不改变 Provide 输出（与旧版相同；生产需要时用 `charge=1` 白名单，第 8 节 D-B06-3）。
- 内容数据库错误不回显：旧版 SQL 错误输出 ThinkPHP 错误页（含 SQL 时取决于 debug），新版只有第 3.2 节的固定文本。

### 4. 杂项页（`site` 模块）

所有页面受 `maccms.site.enabled`；站点关闭时 503 维护页（B05 起的 `SiteStatusInterceptor`）。URL 形态与 B05 相同（默认 `/index.php/<控制器>/<操作>[/键/值…].html` 及 `rewrite`/`suffix_hide` 变体；canonical 只由 base-url 生成，子目录部署时以 base-url 的路径为前缀）。旧版这些页面的内容全部由模板决定，仓库模板缺失（D9-a），新版的页面结构由后端定义（差异），页面所需数据与旧模板的数据来源相同。

#### 4.1 地图 `map/index`（`Map.php:12-15`）

- 路径：`map/index.html`（canonical）；`map/index`、`map/index/`、`map`、`map.html` 只作输入（canonical 指向前者）。不接受参数（任何路径参数或查询串 404，与三页相同）。
- 内容：可见的视频/文章/演员/网址分类树（各自链接到分类页）+ 专题首页、演员首页、角色首页、网址首页、RSS 各格式链接；不列内容条目（数据量不受控）。严格策略（`script-src 'none'`），`Cache-Control: public, max-age=300`。
- 根元素 `section.map-page`，分组 `section.map-section` + 标题 `h2#map-<模型>`，分类树 `ul.map-tree` / `li.map-tree__item`（嵌套 `ul.map-tree`）。

#### 4.2 RSS 与搜索引擎地图 `rss/<格式>`（`Rss.php:13-53`）

| 路径（canonical） | 格式 | 条目 |
|---|---|---|
| `rss/index.xml` | RSS 2.0（`application/rss+xml; charset=utf-8`） | 最近更新的 50 个视频：`title`、`link`（详情页绝对地址）、`description`（`vod_blurb`，为空时 `vod_remarks`，纯文本）、`pubDate`（RFC 1123，`vod_time`）、`guid isPermaLink="true"` |
| `rss/baidu.xml`、`rss/google.xml`、`rss/so.xml`、`rss/sogou.xml`、`rss/bing.xml`、`rss/sm.xml` | Sitemap 0.9（`urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"`，`application/xml; charset=utf-8`） | 站点首页 + 最近更新的 1000 个视频详情页：`loc`、`lastmod`（`Y-m-d`，`Asia/Shanghai`）、`changefreq`（首页 `daily`，详情 `weekly`）、`priority`（首页 `1.0`，详情 `0.8`） |

- 旧地址形态：`url($model, $param, 'xml')`（`common.php:1782-1793`）→ 默认 `/index.php/rss/index.xml`；`.html`、无后缀、`/` 结尾只作输入。`view.rss=2`（静态）属于 B16。
- 范围：已审核 + 可见分类（与 B05 相同）；按 `vod_time desc, vod_id desc`。文本做 XML 转义，控制字符去掉。`Cache-Control: public, max-age=600`。不接受参数（`page` 等 → 404）。
- 旧模板决定的条目数、字段与是否含文章（D9-a）：【待核实】。新版固定为上表（差异）。

#### 4.3 自定义标签页 `label/<文件>`（`Label.php:9-25`）

- 旧版：`<文件>` 取自操作名（`?file=` 或路径参数 `file` 覆盖），`\` 换成 `/`；文件 `template/<模板>/label/<文件>.html` 不存在或含 `/` → `error('非法请求')`；存在时**按模板语言执行**该文件。
- 新版：**不读取、不解析、不执行任何模板文件**（不可信模板代码）。只认 `[a-z0-9_]{1,32}` 的文件名，且只有后端内置的 jte 标签页（`jte/site/label/<文件>.jte`，随代码评审发布）才输出；当前没有内置标签页（模板来源 D9-a 未定），所以**全部返回 404 固定提示"非法请求"**（旧版在本仓库同样全部报错，因为模板目录只有一个空文件）。模板转换批次（D9-a 答复后）在这里登记标签页即可，不需要改路由。

#### 4.4 帮助 `help/index`（`Help.php:13-18`）

- 路径 `help/index.html`；旧版把 `mac_param_url()` 交给模板，模板未知（D9-a）。新版输出固定结构的帮助页（站点名、站内搜索说明、地图与 RSS 链接），不回显任何参数；查询串参数 404（与三页相同）。根元素 `section.help-page`。

#### 4.5 版权页 `vod/copyright`（`Vod.php:74-78`）

- 路径 `vod/copyright/id/<n>.html`；与详情页相同的存在/审核/分类可见/游客详情权限判定（`label_vod_detail`，不存在 → 404），**不看 `vod_copyright` 标记**（旧版同样不看），显示视频名与旧配置 `app.copyright_notice`（仓库值"该视频由于版权限制，暂不提供播放。"，`maccms.php:74`；新增读取，不新增配置项）。200、`noindex`。根元素 `section.copyright-page`，提示 `p.notice.copyright-notice`。
- 详情页 `copyright_status=2` 时的跳转与播放页 3/4 的版权提示已在 B05/既有详情中实现，本批不改。

#### 4.6 公告

旧代码没有独立的公告入口（没有 Notice 控制器、路由、配置项；`grep 公告` 只在语言包外无结果）。旧站的"公告"由模板以自定义标签页或文章分类实现（D9-a）。新版**不新增入口**：自定义标签页按第 4.3 节、文章分类按 B05 的 `art/type` 承接。

#### 4.7 二维码 `qrcode/index`（`Qrcode.php:14-24`）

- 参数 `url`（查询串或路径参数）；旧版 `filter_var($url, FILTER_VALIDATE_URL)` 通过时输出 PNG（`QR_ECLEVEL_M`、模块 10 像素、边距 2 模块），否则空响应 200。
- 新版：本地生成（ZXing core，纯计算、无网络），纠错 M、每模块 10 像素、静区 2 模块、黑白 PNG；URL 校验按 PHP `FILTER_VALIDATE_URL` 的规则（由 PHP 黄金向量锁定），另加长度 ≤ 1024 字节；不通过 → **200 空 body**（`text/plain`，`no-store`）。通过 → `image/png`，`Cache-Control: public, max-age=86400`，`X-Content-Type-Options: nosniff`。PNG 字节与 phpqrcode 不同（编码器的掩码选择不同），验收按"两边解码出同一文本、图像尺寸按版本一致"（差异）。

#### 4.8 桌面快捷方式 `ajax/desktop`（`Ajax.php:123-150`）

- 参数 `name`、`url`（`mac_param_url`：`htmlspecialchars(urldecode(trim))`）；`name` 为空时用站名与 `"http://" . site_url`（新版用 base-url 的协议与主机）；`url` 不以 `http` 开头时加 `http://`。
- 输出与旧版逐字节相同的 `.url` 正文（`[InternetShortcut]` 起，CRLF 换行、每行前 8 个空格，`Ajax.php:136-141`），`application/octet-stream`。
- 安全：`name`/`url` 含 CR、LF 或其他控制字符 → 400（旧版会把攻击者的行注入快捷方式文件，例如 `IconFile=\\主机\共享`）；`Content-Disposition` 用 RFC 6266/5987（`attachment; filename="<ASCII 兜底>.url"; filename*=UTF-8''<百分号编码>.url`），不把原始字节写进响应头（差异）。`no-store`。

#### 4.9 联想词 `ajax/suggest`（`Ajax.php:88-121`）

- 开关：旧 `app.search != '1'` → `{"code":999,"msg":"联想搜索功能关闭中"}`；新版另要求 `maccms.content.search.enabled`（包含匹配的成本开关，默认关闭）——两者任一关闭都输出这一响应（200）。
- 参数：`mid` ∈ `1 2 3 8 9 11`，`wd` 非空，否则 `{"code":1001,"msg":"参数错误"}`；`limit` 为 intval，< 1 时 20，**新版上限 50**（旧版无上限，差异）。`wd` 与 B05 同一文本规则（≤ 64 码点等），不满足 → 1001。
- 查询：`<pre>_name LIKE %wd% OR <pre>_en LIKE %wd%`，按 `<pre>_id desc`；新版按字面值匹配、只含已审核 + 可见分类（旧版不看状态，差异）；受并发许可与执行时限（503 → `{"code":503,"msg":"服务暂时不可用"}` + HTTP 503）。
- 输出：`{"code":1,"msg":"数据列表","page":1,"pagecount":…,"limit":…,"total":…,"list":[{"id","name","en","pic"}],"url":…}`，`pic` 经 `mac_url_img`（`ImageUrlNormalizer`；W2 关闭时外部图片为空串），`url` 为 `mac_url_search(['wd'=>'mac_wd'], <模块>)` 的站内地址（含 base-url 路径前缀）。`application/json; charset=utf-8`，`no-store`。逐字节以 PHP 黄金为准。

#### 4.10 `ajax/data`（`Ajax.php:21-86`）

- 参数：`mid` ∈ `1 2 3 8 9 11`（否则 `{"code":1001,"msg":"参数错误"}`）；`limit` ∈ `10 20 30`，否则 10；`page` 1..20，否则 1；`tid`（mid 1/2）：分类存在时顶级分类取"本身 + 子分类"，否则本身，`type_id|type_id_1 IN`（`:42-51`）。
- 查询：`<pre>_status = 1`，`<pre>_time desc`；新版另限可见分类（mid 1/2/8/11；专题/角色不分类）。
- 输出：整行字段（旧 `*`，去掉 `<pre>_time_hits`、`<pre>_time_make`，视频另去掉 8 个播放/下载列，`:55-62`），`<pre>_time`、`<pre>_time_add` 格式化为 `Y-m-d H:i:s`，追加 `detail_link`（站内详情地址），`<pre>_pic/_pic_thumb/_pic_slide` 经 `mac_url_img`（W2 关闭时外部图片为空串）。新版另去掉密码列（同第 3.5 节），受密码保护的正文为空串（同第 3.6 节）。JSON 逐字节以 PHP 黄金为准。`application/json; charset=utf-8`，`Cache-Control: public, max-age=60`。

### 5. 前端配合（Kimi）

后端先交付页面结构、语义 class 与状态（本节），再在父任务请队长派 Kimi 完成样式；后端不修改 `site.css`、组件与布局。

| 页面 | 根元素与区块 | 状态 |
|---|---|---|
| 地图 `map/index` | `section.map-page` > `h1` + `section.map-section`（每模型一个，`h2#map-vod` 等）> `ul.map-tree` > `li.map-tree__item` > `a` + 嵌套 `ul.map-tree`；末尾 `section.map-section` 的 `ul.map-links`（专题/演员/角色/网址首页、RSS 各格式） | 某模型没有可见分类时该 section 不输出 |
| 帮助 `help/index` | `section.help-page` > `h1` + `div.detail-content`（段落与 `ul`） | — |
| 版权 `vod/copyright` | `section.copyright-page` > `h1`（视频名）+ `p.notice.copyright-notice` + 返回首页链接 | — |
| 标签页 404 | 既有错误页（`error.jte`），文案"非法请求" | — |

验收（样式交付后）：320/360/768/1280 无横向溢出、键盘 Tab 次序与可见焦点、`prefers-reduced-motion`。

### 6. 覆盖表：旧参数/路由 → Java 入口 → 测试

（实施中逐行补齐测试名；最终版每行都有自动测试或手工记录。）

| 旧入口 / 参数 | Java 入口 | 测试 |
|---|---|---|
| `mac_get_client_ip` | `common.clientip.ClientIpResolver` | `ClientIpResolverTest`（伪造头、可信代理、无代理、IPv4/IPv6、多跳、多个头、无效项）、`ProvideRealHttpTest`（真实 HTTP 拒绝路径） |
| `Provide::vod/art/actor/role/website` 全部参数（第 3.3 节） | `openapi.api.ProvideController` → `openapi.application.ProvideService` → `content.application.LegacyRowReadService` | `ProvideGoldenMySqlIT`（PHP 8.0/7.3 黄金逐字节）、`ProvideQueryTest`、`ProvideSettingsTest`、`ProvideApiTest` |
| `Provide::comment/index` | 同上 | `ProvideApiTest` |
| `closed`、`charge`、`cachetime`、`detail_inc_hits` | 同上 | `ProvideGoldenMySqlIT`、`ProvideApiTest`、`ProvideRealHttpTest` |
| `map/index` | `site.web.SiteMiscController` | `SiteMiscPagesMvcTest`、浏览器检查 |
| `rss/index|baidu|google|so|sogou|bing|sm` | 同上 | `SiteMiscPagesMvcTest`、`SiteFeedMySqlIT` |
| `label/<file>` | 同上 | `SiteMiscPagesMvcTest` |
| `help/index`、`vod/copyright` | 同上 | `SiteMiscPagesMvcTest` |
| `qrcode/index` | `site.web.SiteQrcodeController` | `QrcodeTest`（PHP `FILTER_VALIDATE_URL` 黄金向量、解码对照） |
| `ajax/desktop` | `site.web.SiteAjaxController` | `SiteAjaxMvcTest`、PHP 黄金逐字节 |
| `ajax/suggest`、`ajax/data` | 同上 → `content.application.LegacyRowReadService` | `AjaxGoldenMySqlIT`（PHP 黄金逐字节）、`SiteAjaxMvcTest` |

### 7. 与旧版的差异

（实施中补齐；与第 3–4 节的"差异"标注一一对应。）

### 8. 提请队长裁定（已按"建议"实施）

| 编号 | 事项 | 建议（已实施） | 备选 |
|---|---|---|---|
| D-B06-1 | Provide/ajax 的 MIME | JSON `application/json`、XML `text/xml`，加 `nosniff`（旧版 `text/html`，`api.php:9`；JSON 中的用户数据在浏览器里会被当作 HTML，`mac_filter_tags` 挡不住 `<svg onload>` 等） | 保留 `text/html` |
| D-B06-2 | Provide 行范围 | 始终"已审核 + 可见分类"；`datafilter` 只接受白名单语法，不执行配置中的 SQL | 完全按 `datafilter`（需要执行配置 SQL，违反 SQL 入口护栏） |
| D-B06-3 | 受限内容 | 不输出密码列；受访问密码保护的正文/地址置空；积分与会员组权限不改变输出（旧协议用途就是把地址交给采集方，靠 `charge` 白名单控制） | 再按游客播放权限/积分屏蔽地址 |
| D-B06-4 | `cachetime` | 不做服务端缓存，改为 HTTP 缓存头（`charge=1` 时 `private`） | 进程内有界缓存（需放宽 02 规范第 3 节） |
| D-B06-5 | 白名单拒绝 | 403 + 原文"域名未授权"；域名项不解析 | 200（旧状态码） |
| D-B06-6 | Provide `wd`/`from` 的成本 | 字面值匹配；受执行时限与并发许可，不受搜索开关（否则打开 Provide 后常用的 `from` 采集不可用） | 与搜索开关绑定 |
| D-B06-7 | 地图/RSS/帮助/标签页结构 | 第 4 节固定结构（模板缺失 D9-a） | 等模板来源后再做 |

### 9. 验证（最终树）

（实施后补齐：`./mvnw clean verify`、`./mvnw verify -Pmysql-it`、PHP 黄金再生成（8.0.30 与 7.3.33）、真实 HTTP（根路径与子目录、IP 欺骗/可信代理/IPv6）、浏览器检查（拦截非本机请求）、`git diff --check`、UTF-8/无 BOM。数据库只用自动创建并删除确认不存在的 `maccms_it_*`。）

### 10. 回滚

不写库、无数据迁移、零 DDL；回滚即普通 `git revert` 本批提交。回滚点：已验收 main `2313076`。运行时：`MACCMS_OPENAPI_ENABLED=false`（默认）时不注册 Provide 路由；`MACCMS_SITE_ENABLED=false` 时不注册任何 site 页面。
