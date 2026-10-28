## B16 静态生成与定时任务（路线图 B16，MACC-40）

> **状态：交付待验收。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B16 行、第 5.3 节 B16 验收行（生成的文件与 PHP 一致、runtime 回写格式与 PHP 一致、D2 ③、删除本批生成的文件）、第 5.5 节 ③、写入方矩阵；
> - B05/B06 前台页面（`15-b05-readonly-pages.md`、`16-b06-misc-pages-provide.md`）、B07 写入基础（`12-write-infrastructure.md`）、B12 后台框架（`21-b12-admin-framework.md`）、B15 配置写回与旧站文件（`24-b15-admin-system.md` 第 3 节，D-B15-18 重启生效）；
> - 用户决定（2026-10-17）：后台复用旧后台 HTML 结构与静态资产，转成 jte，不做新视觉设计。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `backend/…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | 平台基线 `1f25421`（树与 main `4ad7b83` 相同） |
| 分支 | `agent/claude/macc-40`，只用普通提交 |
| 回滚点 | main `4ad7b83` |
| 第二次合入 main | B17～B20 合入 main 后，分支先后合入 main `e0e7ef6`（`0df7dc8`）与 `2b895db`（`c572bad`），再接上定时任务的 `cj`（B20）与 `collect`（D-B19-15），见第 7.3 节；此时合入的回滚点为 main `2b895db` |

### 1. 范围

| 旧代码 | 本批实现 |
|---|---|
| `application/admin/controller/Make.php` | `opt`（生成静态管理页）、`make`（按 `ac` 分派）、`index`（含 `?ac2=wap`）、`map`、`rss`、`type`、`topic_index`、`topic_info`、`info`、`label` |
| `application/api/controller/Timming.php` | `index`：部署级鉴权（D2 ③）、时间表判断、runtime 回写、任务分派（`make`、`cache`、`cj`；`collect` 不提供；`urlsend` 输出"未执行"） |
| `application/common.php` | `mac_url` 静态分支（:1732-2428）、`reset_html_filename`（:2802-2824）、`mac_play_list` 的来源/集数（:1346-1452）、`mac_art_list` 的分页数（:1294-1318） |

| 不在本批（归属） | 说明 |
|---|---|
| 采集、规则采集任务的执行 | B17～B20（定时任务里输出"由后续批次提供，本次未执行"） |
| 推送任务 | B28（同上） |
| 删除内容时删静态页、后台列表"浏览"列的静态判断 | 维持 D-B13-22（新系统不删静态页），见第 12 节 |
| 前台页面里的链接改为静态地址 | 未做，见 D-B16-1（需队长确认） |

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| **`staticgen`（新模块）** `application` | `StaticPaths`（静态地址与文件名，第 3 节）、`MakeService`（生成动作，第 5 节）、`StaticFiles`（写入、登记、回滚，第 6 节）、`EpisodeCounts`、`TimmingSchedule`、`TimmingService`、`TimmingTasks`（第 7 节）、`MakeOutput` |
| `staticgen.web` | `TimmingController`（`/api.php/timming/**`，`maccms.timming.enabled=true` 时注册）、`TimmingAccess`（D2 ③） |
| `staticgen.config` | `StaticgenConfiguration`、`TimmingProperties` |
| `site` | 新接口 `site.application.StaticPageSource`；实现 `site.web.InternalPageRenderer`（第 4 节）；`LegacyUrlBuilder.label`；`SiteLegacyConfig` 不再因 `view.*=2` 拒绝启动（D-B16-1） |
| `content` | `StaticSourceService`（`application`）+ 只读 `StaticSourceMapper`（`mapper/content/StaticSourceMapper.xml`）；已生成时间回写沿用 B13 的 `ContentAdminWrites.updateByIds` |
| `legacyconfig` | `LegacySiteFiles` 新增静态页的写入/删除/空目录清理（`isStaticPageName` 白名单） |
| `common.clientip` | `IpAddress.isLoopback`、`ClientIpResolver.trusts` |
| `admin.web` | `AdminMakeActions`、`view/MakeOptView`、`jte-admin/admin/pages/make/opt.jte`、`static/admin-assets/make-opt.js` |

依赖方向：`staticgen` → `site.application`、`content.application(.view)`、`legacyconfig.application`、`admin.application.PlayerAdminService`（定时任务的"清理缓存"）、`common`；`admin.web` → `staticgen.application`。`site` 不引用 `staticgen`。`ModuleBoundaryTest` 不需要新增例外。

### 3. 静态地址与文件名（`StaticPaths`）

- `mac_url` 的静态分支逐字照旧：规则以 `/` 结尾补 `index`；占位符 `{id} {en} {page} {type_id} {type_en} {type_pid} {type_pen} {md5} {year} {month} {day} {sid} {nid}` 按旧版每个分支的位置表替换（数组 `str_replace`，缺位为空串），所以旧版的怪现象照旧：分类规则里的 `{type_en}`、`{type_pid}` 取不到值（分类行没有 `type`/`type_1` 键），`art/type`、`topic/detail` 只在规则含 `{md5}` 时补 md5；`//` → `/`；以 `/index` 结尾去掉 `index`，否则没有 `.` 时补 `.<suffix>`；不以 `/` 开头时加 `MAC_PATH`（固定 `/`）。
- 播放/下载页：模式 2 为 `<规则>.<suffix>?<id>-<sid>-<nid>`，模式 3 为 `<规则><sp><sid><sp><nid>`，模式 4 为 `<规则><sp><sid><sp>1.<suffix>?…`。
- `reset_html_filename`：以 `/` 结尾补 `index`；整串没有 `.` 时补后缀；去掉 `?` 之后；`./` + 路径并两次 `//` → `/`。新版返回去掉 `./` 的相对路径。
- 固定文件名：`index.html`、`wap_index.html`、`map.html`、`rss/<ac2>[<sp><页>].xml`、`label/<名>`。
- 只实现静态分支：`view` 不是静态模式（分类/详情/专题须为 2，播放/下载 2～4）或规则为空时，调用方按旧提示"浏览模式非静态，无法生成"停止（旧版 view 为 3 以上时会把动态地址当文件名写出，新版不写）。
- **黄金向量**：`backend/src/test/php/b16_static_timming_golden.php` 在 PHP 8.0.30 上执行仓库内的 `mac_url_*` 与 `reset_html_filename`，3 组配置（仓库规则 + 模式 2；含全部占位符 + 分页符 `-` + 后缀 `htm` + 模式 3；以 `/` 开头/结尾的规则 + `{page}` + 后缀 `shtml` + 模式 4）× 24 个用例 = 72 条地址与文件名（`backend/src/test/resources/staticgen/b16-php80-golden.json`）；PHP 7.3.33 的结果相同（除说明行外逐字节一致）。`StaticPathsGoldenTest` 逐条比较。

### 4. 页面内容（`StaticPageSource` / `InternalPageRenderer`）

- 旧版 `buildHtml` 用模板标签渲染一遍页面写进文件。新版前台是 jte 页面，生成器对该页面的**动态地址**（`LegacyUrlBuilder`，随 URL 形态配置）做一次**进程内游客 GET**：经 Spring MVC 的处理器映射找到前台控制器，依次执行其拦截器（关站检查、会员接线）与控制器，取得正文写进文件。所以静态文件与浏览器访问动态地址得到的页面**逐字节相同**（`InternalPageRendererTest`：首页、分类、详情、地图、两种 RSS；`StaticTimmingMySqlIT`：真实库上的视频详情、文章两页、分类、首页）。
- 请求是全新的游客请求：没有 cookie、会话、查询串与转发头，UA 固定（手机首页用手机 UA，按 `SiteVariants` 的选择）；属性表独立；结束后恢复外层请求（后台或定时任务）的上下文。外层请求的属性、cookie、会话不受影响（有测试）。
- 控制器抛出的业务异常不交给异常处理器：按其状态（404、503 等）报告"生成失败"，不写文件。

### 5. 生成动作（`MakeService`）

逐个照旧 `Make.php`：

| 动作 | 要点 |
|---|---|
| `index`/`map` | 写 `index.html`（`ac2=wap` 为 `wap_index.html`）/`map.html`，不检查 `view`（旧版也不检查） |
| `rss` | `ac2` 限 `index/baidu/google/so/sogou/bing/sm`，否则"参数错误"；`ps` 页（缺省 1）——新版只生成第 1 页（D-B16-6） |
| `type` | `view.<tab>_type < 2` → "浏览模式非静态，无法生成"；`ids` 为空且 `ac2=day` 时取当天有内容的分类；条数 `type_id|type_id_1 = 分类` 且已审核；每页 20 条（D-B16-2）；后台每批 `app.makesize` 页、结尾按旧参数（`num/start/page_count/page_size/data_count`）跳下一批；定时任务入口每个分类生成前 `makesize` 页后输出"定时任务完毕，本次每个分类生成N个列表页面，避免网站卡死！" |
| `topic_index` | 每批 1 页（旧版写死 `makesize=1`） |
| `topic_info` | 逐个已审核专题生成详情页；回写所有给定编号的 `topic_time_make`；`ref=1` 跳回来源页 |
| `info` | 按分类（`type_id`）或编号列表（`ids`），`ac2=day` 加"当天"、`ac2=nomake` 加 `*_time_make < *_time`，不加审核条件（旧版 :601 把审核条件清掉了，照旧）；`*_time desc`；后台每批 `makesize` 条，定时任务入口 999 条；视频：`view.vod_detail==2` 写详情，播放/下载页按模式 2/3/4（模式 3 的集数按播放器配置里 `status=='1'` 的来源，`EpisodeCounts`；旧版按"1..可用来源数"遍历 sid，来源不连续时后面的来源不生成，照旧）；文章：按 `$$$` 分页逐页生成；回写 `vod_time_make`/`art_time_make` |
| `label` | 名称含 `./`（反斜线先换成 `/`）→ "参数错误2"；逐个写 `label/<名>`（新版没有标签页，一律"生成失败"，D-B16-5） |

- 输出照旧（`echo`/`mac_echo`/`echoLink` 的写死标记原样，`<font color=>` 等），名称与地址做 HTML 转义。
- 已生成时间回写：`UPDATE mac_vod|mac_art|mac_topic SET <tab>_time_make = now WHERE <tab>_id IN (…)`，沿用 B13 已登记的 content / UPDATE（B11 登记，`ContentAdminWriteMapper.update*ByIds`），只写这一列（`StaticTimmingMySqlIT` 逐列比对）。可写数据源关闭时不写，输出"可写数据源未开启，未记录生成时间"（D-B16-8）。

### 6. 文件写入、登记与回滚（`StaticFiles`）

- 写入经 `LegacySiteFiles.replaceStaticPage`：站点根目录（`maccms.legacy-config.dir` 的上一级）内的合法相对路径；扩展名 `html/htm/shtml/xml`；顶层目录不能是 `application thinkphp vendor extend addons static template upload runtime backend install cgi-bin`；任何一段不以 `.` 开头；不覆盖根目录的 `crossdomain.xml`；同目录临时文件 + 原子替换（B15 `AtomicFiles`）。不满足时"生成失败（文件名不在允许的范围内）"（D-B16-4）。
- 每写一个文件，相对路径追加到登记文件 `application/data/backup/static-pages/generated.txt`（B15 已开放的区域）。
- **回滚**（路线图"删除本批生成的文件"）：`StaticFiles.cleanup()` 按登记逐个删除文件、删除因此变空的目录（不越过站点根目录），最后删除登记文件；登记行不在白名单内的不处理并列出。也可以在部署层直接按登记文件删除（每行一个相对路径）。演练：`MakeServiceTest.cleanupDeletesExactlyTheGeneratedFilesAndEmptyDirectories`（不碰无关文件与旧入口）、`StaticTimmingMySqlIT` 第 3 步（站点根目录回到生成前）。
- 注意：生成前已存在、被本批覆盖的同名文件（例如 PHP 以前生成的静态页）也在登记里，回滚会删除它们；需要保留时在开启生成前备份站点根目录的静态目录。

### 7. 定时任务（`api.php/timming/index`）

#### 7.1 部署级鉴权（D2 ③）

| 部署参数（环境变量） | 默认 | 说明 |
|---|---|---|
| `maccms.timming.enabled`（`MACCMS_TIMMING_ENABLED`） | `false` | 关闭时不注册 `/api.php/timming/**` |
| `maccms.timming.token`（`MACCMS_TIMMING_TOKEN`） | 空 | 外部触发口令；空表示不接受口令。设置时至少 32 个可见 ASCII 字符，否则启动失败（信息不含取值） |

放行条件（`TimmingAccess`，二者之一）：

1. **口令**：请求头 `X-Maccms-Timming-Token` 与口令相同（SHA-256 摘要定长比较）。查询串里的口令不认（会进访问日志）。
2. **本机**：客户端地址按 B06 的 `ClientIpResolver`（可信代理规则）解析为回环地址（`127.0.0.0/8`、`::1`）；请求带有转发头（`Forwarded`、`X-Forwarded-For`、`X-Real-IP`、`CF-Connecting-IP` 等）时，连接对端还必须是已登记的可信代理——否则（本机 nginx 转发了公网请求但没登记为可信代理）不算本机。

未通过：403，固定提示"定时任务入口只接受本机请求或携带部署口令的请求，未执行任何任务"，不读任务表、不执行、不回写 `timming.php`（含 `enforce=1`）。口令只以摘要保存，不出现在输出与日志中（`TimmingControllerMvcTest` 抓取日志断言）。响应 `no-store`、`nosniff`、`noindex`，CSP 只放行内联样式。

部署建议：本机 cron 直接请求应用端口，如 `curl -s http://127.0.0.1:18280/api.php/timming/index`；外部 cron 服务带上请求头 `X-Maccms-Timming-Token`。

#### 7.2 执行判断

`status == '1' && (empty(runtime) || (旧"周-时" != 现"周-时" && strpos(weeks, 现周) !== false && strpos(hours, 现时) !== false) || enforce == '1')`，周 `date('w')`、时 `date('H')`，时区 PRC；`strpos` 是子串（`hours='109'` 含 `09`，照旧）；`name` 参数按宽松比较只处理同名任务；参数按旧 `input('get.', '', 'trim,urldecode')` 先 trim 再 urldecode。逐个输出"任务：%s，状态：%s，上次执行时间：%s---跳过/执行"，第一个到点的任务执行后结束（一次请求最多一个）。黄金：20 组任务/时刻/enforce（含从未执行、关闭、同一小时、上周同一小时、周/时不在表内、子串、周日 23 点、零点、空 weeks、整数/带空格的 status、字符串 runtime、enforce=2），`TimmingGoldenTest` 与 PHP 逐条一致。

#### 7.3 任务

| `file` | 新版 |
|---|---|
| `make` | `parse_str(param)` 后交给 `MakeService`（定时任务入口） |
| `cache` | 同后台"清理缓存"：重写 `playerconfig.js` 的缓存段、清理过期的视频搜索缓存；输出"清理缓存成功" |
| `cj` | `parse_str(param)` 后取 `id`、`page`，做旧 `Cj::col_all` 的一轮（B20 的 `CjColAll`，与后台 `cj/col_all` 同一实现，28 文档第 6.2 节）：采集一页网址、一批内容、导入这一批，页面来自本地 HTML 目录，不发请求；没有节点时"获取失败"、未配置本地 HTML 目录时提示，都不写库（D-B16-16）；采集模块未启用（后台关闭）时"规则采集不可用" |
| `collect` | 不提供（2026-10-28 用户决定，27 文档 D-B19-15）：输出"该功能未在新系统中提供"，不执行任何采集（D-B16-17） |
| `urlsend` | 输出"推送任务由后续批次（B28）提供，本次未执行" |
| 其他 | 输出"参数错误"（旧版调用不存在的方法而报错） |

#### 7.4 runtime 回写

到点的任务在执行前把 `runtime` 改成当前时间（整数）写回 `extra/timming.php`：`mac_arr2file` 格式（B15 `VarExportWriter`），在该文件的进程内锁里基于当前内容只改这一项（`LegacyConfigService.update`，原子写；进程外同时修改 → 冲突，按写入失败处理）。写入失败输出"配置文件写入失败，请重试!"，不执行任务。黄金：从仓库 `timming.php` 出发依次回写两项，结果与 PHP `mac_arr2file` 逐字节相同（`TimmingGoldenTest`）。

### 8. 后台页面

- `make/opt`：旧 `admin/view/make/opt.html` 原样转成 `jte-admin/admin/pages/make/opt.jte`（下拉框、按钮、文字、类名不变）；按钮的 `onclick="post('…')"` 改为 `data-make` 属性，末尾内联脚本移到 `static/admin-assets/make-opt.js`（逻辑相同，`curUrl` 经 `data-cur-url`）。分类、当天分类、专题来自 `StaticSourceService`；自定义页列表为空（D-B16-5）。
- `make/make` 等：输出同旧版逐行 `echo`；结尾的 `mac_jump` 改为 `progress.js` 的 `data-*`（同 B13 批量处理，22 文档第 8.4 节）；`error()` 接着输出跳转提示页；`ref=1` 只跳回本后台同源的来源页，否则回到生成页。
- 权限沿用 B12 的 `make/*` 登记（`AdminPermissions`，菜单编号 81～8107），本批起不再返回"由 B16 批次提供"。

### 9. 测试与验证

| 测试 | 内容 |
|---|---|
| `StaticPathsGoldenTest` | 72 条静态地址与文件名对 PHP 黄金；非静态模式与空规则拒绝 |
| `TimmingGoldenTest` | 20 组执行判断对 PHP 黄金；runtime 回写与 PHP `mac_arr2file` 逐字节相同；任务被删除时不写 |
| `TimmingControllerMvcTest` | D2 ③：远端无口令（含 `enforce=1`、IPv6、查询串口令、错误口令）、本机对端 + 转发头 → 403 且 `timming.php` 逐字节不变；本机请求执行第一个到点的任务并回写、同一小时再请求全部跳过且不回写；口令放行、口令不出现在输出与日志；口令配置校验；`urldecode` |
| `InternalPageRendererTest` | 进程内渲染与浏览器访问逐字节相同；外层请求不受影响；404 报状态 |
| `MakeServiceTest` | 首页/手机首页；分类页分批与跳转参数（后台）、每个分类前 makesize 页（定时任务）；非静态模式；详情 + 模式 3 播放页与已生成时间回写；页面失败与禁写文件名不写；`nomake` 在不能回写时停下；回滚只删本批文件与空目录；前台未启用 |
| `SiteLegacyConfigTest` | `view.*=2` 接受、非数字仍拒绝 |
| `AdminCjMySqlIT.colAllRoundFromAdminAndTimmingTask`（`-Pmysql-it`） | 后台 `cj/col_all` 与定时任务 `cj` 各对一个节点做一轮：输出逐字节相同（只差节点内容），各导入 3 条视频、内容记为已发布、写采集时间；再执行一轮没有待采内容；没有节点时"获取失败"、`collect` 固定"该功能未在新系统中提供"，都不写库 |
| `StaticTimmingMySqlIT`（`-Pmysql-it`） | 一次性库 + 临时站点根目录 + 真实 HTTP：未鉴权请求不改任何东西；本机触发 4 个 `make` 任务，文件与动态页面逐字节相同，`*_time_make` 只改这一列；回滚后站点根目录回到生成前；表结构不变 |

运行结果见交付评论（`./mvnw verify`、`./mvnw verify -Pmysql-it`）。

### 10. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B16-1 | `view.*` 为 2 时前台页面里的链接指向静态文件 | 前台启动不再因 `view.index/vod_type/vod_detail=2` 失败（B02 起的拒绝取消）；静态文件按旧路径写出、由部署层直接提供；**前台页面里的链接仍是动态地址**，静态文件的 canonical 也指向动态地址 | 把链接改成静态地址需要改 B05 的全部链接生成（并取得 `en`、时间、上级分类等占位符的数据），超出本批；**请队长确认**是否另开批次 |
| D-B16-2 | 分类页、专题首页的页数按模板里分页标签的 `num` | 每页 20 条（新版页面的固定条数） | 新版没有 MacCMS 标签 |
| D-B16-3 | 页面不存在、未审核时照样渲染出错误内容写进文件 | 页面状态不是 200（404、503 关站、前台未启用）时输出"生成失败"、不写文件 | 不写错误页 |
| D-B16-4 | `buildHtml` 不限制文件位置 | 扩展名、顶层目录、`.` 开头的段、`crossdomain.xml` 白名单（第 6 节） | `path.*` 规则可被写成任意路径 |
| D-B16-5 | 自定义页列出模板 `label/` 目录的文件并渲染 | 列表为空；指定名称时"生成失败" | 新版没有标签页（16 文档第 4.3 节） |
| D-B16-6 | RSS 生成 `ps` 页 | 只生成第 1 页，其余输出"生成失败（新版 RSS 不分页）" | 新版 RSS 不分页 |
| D-B16-7 | 播放页模式 2/4 的单个文件靠旧模板脚本读 `?id-sid-nid` 切换集数 | 文件内容是第 1 集（模式 4 为各来源第 1 集）的动态播放页 | 新版播放页不读这个查询串 |
| D-B16-8 | 总是回写 `*_time_make` | 可写数据源关闭时不回写并提示；"未生成"只做一批即停（否则永远是同一批） | 新系统默认只读 |
| D-B16-9 | 输出不转义；`ref=1` 跳回任意来源；`mac_jump` 内联脚本 | 名称与地址转义；只跳回同源；`progress.js` | 后台 CSP 与加固 |
| D-B16-10 | 定时任务入口无鉴权，`enforce=1` 任何人可用 | 默认关闭；本机或口令（第 7.1 节）；后台定时任务列表的"测试"链接从浏览器打开会被拒绝（除非在本机） | D2 ③ |
| D-B16-11 | runtime 回写把请求开始时读到的整张表写回 | 锁内基于当前内容只改该任务 | 不覆盖并发修改 |
| D-B16-12 | `urlsend` 立即执行；`cache` 输出跳转页；未知任务报错 | `urlsend` 输出"未执行"（runtime 照写）；`cache` 输出一行提示；未知任务"参数错误" | 推送在 B28 |
| D-B16-13 | 关站时照样生成 | 关站时前台返回 503，生成失败 | 生成经前台处理链 |
| D-B16-14 | 手机首页无条件用手机模板目录 | 按 `SiteVariants` 对手机 UA 的选择（`mob_status=0` 时与桌面相同） | 新版的手机模板选择只有这一处 |
| D-B16-15 | 当天分类按表的物理顺序去重 | 按主键顺序去重 | MyISAM 物理顺序不可依赖；只影响生成顺序 |
| D-B16-16 | `cj` 任务的节点不存在时 `$this->error()` 输出错误跳转页并结束整个请求 | 只输出一行"获取失败"，runtime 照写；未配置本地 HTML 目录时同样只输出提示 | 一次请求最多执行一个任务（第 7.2 节），结果相同；输出不再是跳转页 |
| D-B16-17 | `collect` 任务连资源站采集 | 固定输出"该功能未在新系统中提供"，runtime 照写 | 2026-10-28 用户决定删除（D-B19-15，与本地文件源方针 D1/E01 冲突） |

### 11. 回滚

- **代码**：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到合入前的 main（第 0 节）。定时任务入口默认关闭；后台默认关闭（B12）。
- **文件**：按第 6 节删除本批生成的文件（`StaticFiles.cleanup()` 或按登记文件删除）；`timming.php` 的 runtime 回写与 PHP 格式相同，切回 PHP 后照常读取（不需要恢复）。
- **数据**：只改 `vod_time_make`、`art_time_make`、`topic_time_make` 三列（旧版同样会改）；回滚不需要恢复，也可按需用 DML 置回生成前的值。没有表结构变化。

### 12. 风险与遗留

| 项 | 说明 |
|---|---|
| D-B16-1 链接仍是动态地址 | 开启静态模式后，静态文件只在被直接访问（或部署层把动态地址改写到静态文件）时起作用；需要队长决定是否另开批次让前台输出静态链接 |
| 大量文件写入的磁盘与耗时 | 后台每批 `makesize` 条、定时任务入口每次最多 999 条（同旧版）；每个页面一次进程内渲染，没有额外缓存 |
| 删除内容时不删静态页 | 维持 D-B13-22；删除后静态文件仍可被直接访问，需要时手工删除或重新生成 |
| 采集/推送任务 | 定时任务里只输出"未执行"；B17～B20、B28 接入时替换 `TimmingTasks` 对应分支 |
| 配置修改后重启生效 | 生成用的 `view`/`path`/`makesize` 每次请求重读；前台 URL 形态与页面设置仍按 D-B15-18 重启生效 |
