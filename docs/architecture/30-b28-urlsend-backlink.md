## B28：百度推送与友链回链检测（MACC-51）

依据：路线图 `09-roadmap.md` 第 4.1 节 E09、E11 行，第 4.3 节（外部调用实施规则），第 5.4 节 B28 行；B23 的出站客户端与白名单（`26-b23-integration-foundation.md`）；B15 的推送配置保存（`24-b15-admin-system.md`）；B16 的定时任务（`25-b16-static-timming.md` 第 7 节）。D-B23-a 已定：生产出站默认 `open`（与旧版相同）。

### 1. 范围

| 编号 | 内容 | 旧版 | 新版 |
|---|---|---|---|
| ① | 后台 `urlsend/data`：列出本页待推送的地址 | `admin/controller/Urlsend.php:41-188` | `staticgen.application.UrlsendService#data`，数据 `content.application.UrlsendSourceService` |
| ② | 后台 `urlsend/push`：推送一页，再跳到下一页 | `Urlsend.php:191-225` | `UrlsendService#push`（后台入口）、`admin.web.AdminUrlsendActions` |
| ③ | 定时任务 `urlsend`：一次推完所有页 | `api/controller/Timming.php:92-97` → `Urlsend::push` | `TimmingTasks` 的 `urlsend` 分支 → `UrlsendService#push`（定时任务入口） |
| ④ | 百度普通推送、快速收录推送 | `common/extend/urlsend/Baidu.php`、`Baidufast.php` | `integration.urlsend.BaiduUrlPush`（接口 `integration.application.UrlPush`） |
| ⑤ | 后台友链列表、网址列表的"检测"按钮 `index/check_back_link` | `admin/controller/Index.php:192-196` → `mac_check_back_link`（`application/common.php:432-473`） | `AdminUrlsendActions#checkBackLink`；抓取 `integration.backlink.BacklinkFetcher`（接口 `integration.application.BacklinkPages`） |
| ⑥ | 白名单登记 `integration.urlsend`、`integration.backlink` | — | `IntegrationPackages.REGISTERED` |

不改表结构，不写库（本批只读），不改旧 PHP，不改后台页面（推送页 `urlsend/index` 与友链列表的按钮是 B15、B13 已转换的旧页面，本批只实现它们调用的动作；`urlsend/data`、`urlsend/push` 是旧版 `mac_echo` 逐行输出的页面，没有模板，不需要前端配合）。

### 2. 待推送的地址（①）

#### 2.1 条件

与旧 `data()` 相同（SQL 见 `resources/mapper/content/UrlsendSourceMapper.xml`，每张表的语句逐个写出，不拼接表名）：

| 参数 | 规则 |
|---|---|
| `mid` | 1 视频、2 文章、3 专题、8 演员、9 角色、11 网址（`intval`）；其他值取不到数据 |
| 条件 | `<表>_status = 1`；`ac2=today` 时加 `<表>_time > 今天零点`（北京时间），`range=1` 时改用 `<表>_time_add`；`ids` 非空（不是 `""`、`"0"`）时加 `<表>_id IN (…)` |
| 排序与分页 | `<表>_id` 升序；`page` 按 `intval`，小于 1 为 1；`limit` 按 `intval`，小于 1 为 50 |
| 页数 | `ceil(total / limit)` |

输出与旧版逐字相同：样式一行、`共%s条数据等待推送，分%s页推送，当前第%s页`、每行 `编号、名称&nbsp;<a href="地址">地址</a><br>`；取不到数据时 `没有获取到数据`。

#### 2.2 地址

地址 = `http_type` + `site.site_url` + 详情页路径（`UrlsendLinks`）：

- `http_type`：后台按 `maccms.admin.base-url` 是否为 https；定时任务按本次请求是否为 https（旧版都是本次请求的协议）。
- 动态地址：`LegacyUrlBuilder`（与前台、后台列表链接同一套规则：`rewrite.status`、`rewrite.suffix_hide`、`rewrite.route_status`、`app.pathinfo_depr`）。
- 静态地址（`view.vod_detail`、`art_detail`、`topic_detail` 为 2）：`StaticPaths`（与静态生成同一套规则，含 `{type_en}`、`{type_pen}`、`{md5}` 等占位符）。
- 新版不能生成的形态（D-B28-6）：`rewrite.<模型>_id` 为 1（拼音）或 2（加密编号）、`route_status=1` 配 `-` 分隔符、静态规则为空、演员/角色/网址的静态地址（旧后台的浏览模式页没有这三项）。遇到时输出提示、不推送。

### 3. 百度推送（④）

#### 3.1 请求

| 项 | 普通推送 `baidu` | 快速收录 `baidufast` |
|---|---|---|
| 地址 | `<接口>?site=<http_type+site_url>&token=<urlsend.baidu.token>` | 同左，令牌 `urlsend.baidufast.token`，另加 `&type=daily` |
| 方法、头 | POST，`Content-Type: text/plain`，User-Agent 与旧 curl 相同 | 同左 |
| 请求体 | 本页地址，`\n` 分隔 | 同左 |

`<接口>` 默认 `http://data.zz.baidu.com/urls`，与旧版相同；由 `maccms.integration.urlsend.baidu-endpoint`（`MACCMS_INTEGRATION_URLSEND_BAIDU_ENDPOINT`）注入，必须是不带查询串与凭证的 http/https 地址，否则启动失败。参数照旧直接拼接、不编码。令牌照旧取自旧配置（后台"URL推送"页保存，B15），经 `ThirdPartyConfig.secret` 取出，只出现在请求地址里；出站客户端的日志只记主机。

#### 3.2 结果

按 PHP 7.3 的取值规则（D-B28-1：PHP 8.0 下旧版根本发不出去）：

| 情况 | code | 提示 |
|---|---|---|
| 响应不是有效 JSON，或 `json_decode` 结果为假（含请求失败、超时、响应过大、目标被拒绝） | 101 | `请求失败，请重试` |
| `error` 为真 | 102 | `发生错误：` + `message` |
| 普通推送其余情况 | 1 | `推送成功<success>条；当天剩余可推<remain>条。`（旧版成功时不显示） |
| 快速收录 `remain == 0 且 success > 0`：把前 `success` 条再推一次 | 103 / 104 | `请求失败，请重试2` / `发生错误2：` + `message` |
| 快速收录其余情况 | 1 | `推送<remain>条；剩余可推送<success>条.`（旧版把两个数写反了，照旧） |

任何状态码都按响应体解释（旧 curl 不看状态码）。

#### 3.3 推送流程（②③）

旧 `push()`：推送方式不存在 → `参数错误`（跳转提示页）；取本页 → 推送 → 失败时输出提示并停止；本页是最后一页 → `数据推送完毕`；否则后台跳到 `urlsend/push?…&page=<下一页>`（参数与旧 `http_build_query($this->_param)` 相同，含规范化后的 `page`、`limit`），定时任务在同一个请求里推下一页。

### 4. 友链回链检测（⑤）

- 参数 `url`（`input()`，GET/POST 都可以）；空串或 `"0"` 时与旧版相同返回 `{}`（旧版 `json(json($res))`）。
- 抓取：`BacklinkFetcher` 经出站客户端 GET，任何状态码都取正文、跟随跳转；超时 15/30 秒、响应最大 4 MiB、跳转最多 5 次、只允许 http/https、URL 不得带用户名密码、校验证书（D-B23-1～4）。地址不合法或抓取失败按空页面。**与旧版相同，不额外限制内网地址**（路线图 B28 行，见第 11 节风险）。
- 判定（`AdminUrlsendActions.check`）：按字节查找 `site.site_url`、`site.site_wapurl`，提示两项都列出 `[域名]反向链接正常/异常`，**最终的 code 只看第二项**（手机域名找到 1，找不到 101），与旧版相同。域名为空时按 PHP 7.3（找不到，D-B28-2）。
- 返回 `{"code":…,"msg":"…"}`，不回显抓到的内容。
- 权限：照旧按 `index/check_back_link`（B12 的权限判定）；只有能访问友链/网址管理的管理员能触发。

### 5. 白名单登记（⑥）与出站

`IntegrationPackages.REGISTERED` = `integration.urlsend → B28`、`integration.backlink → B28`（第一批登记）。两个子包只经出站客户端发请求（`OutboundNetworkGuardTest` 静态检查；客户端运行期检查直接调用方）。其他模块只经 `integration.application` 里的 `UrlPush`、`BacklinkPages` 接口使用。

紧急停止所有出站（26 文档第 12 节）：设 `MACCMS_INTEGRATION_OUTBOUND_MODE=loopback-only` 并重启。此时推送按"请求失败"（101）输出、回链检测按抓取失败（101、两项"异常"），都不报错（`BaiduUrlPushTest.outboundDisabledModeFailsWithoutConnecting`、`BacklinkFetcherTest`）。

### 6. 部署参数

| 参数 | 环境变量 | 默认 |
|---|---|---|
| `maccms.integration.urlsend.baidu-endpoint` | `MACCMS_INTEGRATION_URLSEND_BAIDU_ENDPOINT` | `http://data.zz.baidu.com/urls` |

令牌改了以后重启生效（D-B23-5）。超时、响应大小用出站客户端的统一参数，不新增配置项。定时任务入口照旧需要 `maccms.timming.enabled=true` 与 B16 的部署级鉴权。

### 7. 代码位置

| 位置 | 内容 |
|---|---|
| `integration/application/UrlPush.java`、`BacklinkPages.java` | 对其他模块公开的接口 |
| `integration/urlsend/BaiduUrlPush.java`、`PhpJsonResult.java`、`UrlsendConfiguration.java` | 百度推送 |
| `integration/backlink/BacklinkFetcher.java`、`BacklinkConfiguration.java` | 页面抓取 |
| `content/application/UrlsendSourceService.java`、`view/UrlsendItem.java`；`content/infrastructure/UrlsendSourceMapper.java`、`UrlsendItemRow.java`；`resources/mapper/content/UrlsendSourceMapper.xml` | 待推送数据（只读） |
| `staticgen/application/UrlsendService.java`、`UrlsendLinks.java` | 推送流程与地址 |
| `staticgen/application/TimmingTasks.java`、`TimmingService.java`（`TaskRunner.forRequest`）、`staticgen/web/TimmingController.java`、`staticgen/config/StaticgenConfiguration.java` | 定时任务接入 |
| `admin/web/AdminUrlsendActions.java`、`AdminController.java`、`AdminMakeActions.java`（`progressJump` 抽出共用）、`admin/config/AdminConfiguration.java` | 后台动作 |

### 8. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金 | `backend/src/test/php/urlsend_backlink_golden.php` + `urlsend_child.php`：外部管理账号建一次性库 `maccms_it_*`、复制临时站点，以 php-cgi 跑完整的 ThinkPHP 路由与旧控制器（后台与 api 入口），旧代码不改。百度接口的地址在旧代码里写死，子进程设 `http_proxy` 指向本机模拟服务（`php -S 127.0.0.1:随机端口`），curl 以代理方式把请求交给它；友链页面就是模拟服务上的路径。假令牌 `test-only-*`。**15 个场景，每版 64 个请求（51 步比对 + 13 次登录）**：视频全部分 2 页推（跳转）、当天（更新/新增）、指定编号；文章、专题、演员、角色、网址；百度返回 error、HTTP 400、非 JSON、空 500、成功 0 条；快速收录正常、额度用尽重推、重推失败、重推返回 error、error；取不到数据、未知 mid、未知推送方式、limit/page 为 0、直接访问 `urlsend/data`；静态详情页地址（含 `{type_pen}`/`{type_en}`/`{md5}`/以 `/` 结尾）；伪静态 + 隐藏后缀；空令牌；定时任务两页推完、推送失败；回链检测含两项、只含主域名、只含手机域名、都不含、404 页、302 跳转、连接被拒、空/空串 url、`file://` 本地文件、非 ajax；手机域名为空（主域名命中、都不命中、抓取失败）；只有检测权限的管理员（检测通过、推送被拒）。每步记录状态码、Content-Type、正文与模拟服务收到的请求（方法、完整地址、Content-Type、User-Agent、请求体）。输出 `backend/src/test/resources/integration/b28/php73-golden.json`、`php80-golden.json` |
| 新版复放 | `UrlsendBacklinkGoldenMySqlIT`（`-Pmysql-it`）：一次性库同样的种子（`{T}` 换成本机今天零点），本机模拟服务（`LoopbackMockServer`）扮演百度与友链页面，按场景的配置启动应用（百度接口地址指向模拟服务），复放两版黄金：**两版共 102 步全部一致**（推送输出逐字节、回链 JSON 逐字节、跳转提示页比较结构，模拟服务收到的请求逐项相同；有意差异按第 9 节换算后断言）。PHP 8.0 黄金里 31 步是旧版的致命错误，按 7.3 的结果比较（D-B28-1）。定时任务回写 runtime 与 PHP 相同；结束时表结构指纹不变 |
| 推送单元测试 | `BaiduUrlPushTest`（8 个）：请求地址、参数、请求体、Content-Type、User-Agent；令牌不进日志；102、HTTP 400、8 种无效响应为 101、`error` 为 0 不算错误、`message` 为数组；快速收录重推的条数、103、104、`remain` 为 `null`/`"0"` 的松散比较、`success` 为 0 不重推；**超时、响应过大、跳到外部主机都是 101**；**`loopback-only` 模式下默认的百度地址被拒绝、不连接、不抛出**；令牌含空格；接口地址校验 |
| 抓取单元测试 | `BacklinkFetcherTest`（2 个）：任何状态码取正文、跟随跳转；**超时、响应过大、跳转循环、跳到外部主机**、连接被拒、`file://`、`ftp://`、`gopher://`、带凭证、不合法地址、外部 IP、`.invalid` 主机都返回 null，后几种不发请求 |
| 流程单元测试 | `UrlsendServiceTest`（5 个）：后台一页一跳（下一页参数）、最后一页"数据推送完毕"、定时任务推完三页、第二页失败即停、取不到数据不推、未知推送方式"参数错误"、ids/limit/page 越界"参数错误"、拼音编号与演员静态地址不推、`ac2`/`range`/`ids` 的取法与今天零点、`sprintf`、`looseEqualsOne`。`AdminBacklinkCheckTest`（3 个）：code 只看手机域名、空域名、按字节查找 |
| 既有测试 | `OutboundPolicyTest.registryAtB28`（登记表）；`TimmingControllerMvcTest`：WebMvc 切片里没有推送服务，`urlsend` 任务的输出从"由后续批次提供"改为"参数错误"；`OutboundNetworkGuardTest`、`ModuleBoundaryTest`、`SqlEntryGuardTest` 覆盖新代码 |

### 9. 与旧版的差异

| 编号 | 旧版 | 新版 | 理由 |
|---|---|---|---|
| D-B28-1 | **PHP 8.0 下推送根本发不出去**：`mac_curl_post` 设置了 `CURLINFO_CONTENT_LENGTH_UPLOAD`（不是 curl 选项），PHP 8.0 抛 `ValueError`，列表之后是 ThinkPHP 的错误页；取不到数据时 `implode` 的类型错误同样报错页。PHP 7.3 只警告，照常推送 | 按 PHP 7.3 的行为推送 | 推送是保留功能（D1），7.3 是旧版能用的那一版 |
| D-B28-2 | 回链检测在域名为空时：PHP 7.3 `strpos` 空参数返回 false（"异常"），PHP 8.0 返回 0（"正常"，抓取失败也是"正常"）。手机域名为空时最终 code 7.3 总是 101、8.0 总是 1 | 按 PHP 7.3 | 与 D-B28-1 用同一个参考版本；空域名不应判为"找到" |
| D-B28-3 | 取不到数据（没有符合条件的行、未知 mid）时仍把空列表 POST 给百度，再按百度的回复输出 | 只输出"没有获取到数据"，不发请求 | 空推送没有意义，还占百度的额度 |
| D-B28-4 | 名称、地址、百度返回的 `message` 原样输出（XSS） | HTML 转义 | 与 D-B13-19 同一规则；名称不含 `< > & " '` 时与旧版逐字相同 |
| D-B28-5 | `ids` 交给 MySQL 隐式转换；`limit` 不设上限；`page`/`limit` 为非规范数字串（如 `2abc`）时原样参与运算，可能报错 | `ids` 每项须为十进制数字（空项跳过）、`limit` 不超过 10000、`page` 不超过 10 亿，否则输出"参数错误"不推；`page`/`limit` 一律按 `intval` 规范化 | D-B13-4 同一规则；一次推送的数量与内存有界 |
| D-B28-6 | `rewrite.<模型>_id` 为拼音/加密编号、演员/角色/网址的静态地址等形态照常生成地址 | 输出提示，不推送 | 新版的地址生成没有覆盖这些形态（同 B13 后台链接、B05 前台）；宁可不推，也不把错误地址推给搜索引擎 |
| D-B28-7 | 直接访问 `urlsend/data`：列表之后报 `variable type error: array`（控制器返回数组） | 只输出列表 | 去掉错误页 |
| D-B28-8 | curl 带 `Referer: <完整接口地址，含令牌>` | 不带 Referer | 令牌不出现在请求头里 |
| D-B28-9 | `mac_jump` 用内联脚本跳转 | progress.js（同 B16，22 文档第 8.4 节） | 后台内容安全策略不允许内联脚本；跳转地址相同 |
| D-B28-10 | 回链检测用 curl：`file://`、`ftp://` 等都能抓（黄金里 `file://` 读到了本机文件并判为"正常"）；不校验证书；跳转不限次数；响应不限大小 | 只允许 http/https，URL 不得带用户名密码，校验证书，跳转最多 5 次，响应最大 4 MiB，否则按抓取失败 | D-B23-1～4 |
| D-B28-11 | 令牌、站点里有 URL 不允许的字符（空格等）时 curl 照发 | 按请求失败（101），不发请求 | 地址不合法 |
| D-B28-12 | 推送令牌、接口地址每次请求读配置 | 令牌启动时读一次（重启生效）；接口地址是部署参数 | D-B23-5、D-B15-18 |
| D-B28-13 | 后台推送地址的 `http_type` 取本次请求的协议 | 取 `maccms.admin.base-url` 的协议（定时任务仍取本次请求的协议） | 新后台的对外地址是部署参数；B15 推送页显示的站点前缀已是同一口径 |

### 10. 待确认与待联调

- **百度接口暂按旧版用 http（已定，队长 2026-10-28）**：默认 `http://data.zz.baidu.com/urls`，令牌明文经过网络，与旧版相同；接口地址是部署参数 `MACCMS_INTEGRATION_URLSEND_BAIDU_ENDPOINT`，测试指向本机模拟服务。是否改成 https（只改部署参数，不改代码）列入待联调，随真实站点联调在 B21 复核。
- **沙箱联调（待联调，转 B21）**：需要用户提供百度站长的测试站点与令牌（路线图第 4.3 节 ②）。联调要核对：普通推送、快速收录的真实回复字段（`success`、`remain`、`success_daily`、`remain_daily` 等）与旧代码读取的字段是否一致；令牌错误、站点未验证时的 `message`。
- E11 不需要账号，本机模拟页已完成验收。
- 定时任务里推送方式不存在（`ac` 缺失或错误）时，旧版在 api 入口调用 `$this->error()`，输出没有用黄金核对；新版输出"参数错误"一行。

### 11. 风险

- **回链检测会请求管理员录入的任意地址，可能被用来探测内网**（与旧版相同，路线图 B28 行、第 9 节风险表）。只有具备友链/网址权限的管理员能触发；页面只显示"正常/异常"，不回显对方内容；超时、响应大小、跳转次数、协议有上限。"正常/异常"本身仍可作为端口与页面内容的探测信号。如果要拒绝内网地址，属于安全行为差异，按 D2 的方式由用户决定。
- 推送的地址形态有不支持的组合（D-B28-6），线上若用了拼音编号或加密编号，推送会停在提示上；上线前按线上配置核对（D9-b）。
- 百度的回复字段以旧代码为准，没有真实接口核对（第 10 节）。

### 12. 回滚

回滚点：合并前的 main HEAD `111dbfd`（本批开工于 `efa831d`，已合入含 D-B23-a 路线图提交 `24289f0` 的 main `111dbfd`）。本批没有写库、没有改结构、没有写文件（定时任务的 runtime 回写是 B16 原有的）；推送配置仍由 B15 保存。回滚即在 main 上撤销本批的合并提交；撤销后 `urlsend/push`、`urlsend/data`、`index/check_back_link` 回到"由 B28 批次提供"的提示，定时任务的 `urlsend` 回到"本次未执行"。新增的部署参数 `MACCMS_INTEGRATION_URLSEND_BAIDU_ENDPOINT` 可留可删。
