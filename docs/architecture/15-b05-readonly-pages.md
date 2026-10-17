## B05 前台只读页补全：契约稿与旧参数覆盖表（路线图 B05，MACC-29）

> **状态：已实施（2026-10-17 恢复运行），队长裁定 D-B05-1～8 已落实（第 9 节），前端 K1–K5（MACC-54 固定提交 `3100239`）已普通 merge；待整批验收。第 2–7 节为实现说明与覆盖表，第 10 节为最终树验证，第 11 节为与旧版的差异。** 未部署、未切流量。只读批次：不写库、不登记业务写入、零结构变更、不改 B02/B07 护栏，服务端不发起出站请求。
> 依据：`09-roadmap.md` 第 4.2 节（W1/W2）、第 5.3 节 B05 行；MACC-29 任务描述（含"补充验收""补充标签查询能力""补充 SSR 完整性范围""启动决定""恢复决定"）；`14-video-list-filter-completion.md` 第 2、5.2 节（Q1/Q2/Q3）；`10-site-search-page.md`；`11-content-read-completion.md`。旧代码行号以当前仓库为准。
> 证据标注沿用 09 方案：**【代码】**仓库代码；**【黄金】**PHP 黄金样本实测；**【待核实】**依赖线上配置、线上模板或数据。
> 本文件只由后端维护；第 8 节列出需要前端（Kimi）确认或实施的部分，前端文档仍在 `docs/frontend/`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 分支 | `agent/claude/macc-29` |
| 原运行起点（平台基线） | `6dd12f50ca385fe801bacc5bdac6f7bb17acad5c` |
| 首次运行保存提交（含部分实现） | `b46009b78d3f2402567c354955da088016ca8b0c`（父 `6dd12f5`） |
| 已验收 main（B04 合并） | `a0876b8595c800b3a87c9537a70e693335e475c6`，是 `6dd12f5` 的祖先 |

| 第二、三次运行保存提交（额度中断） | `5bc49c8c39a07b5f3cab4f066d9294cc795d85c4`、`5f1479530ea1ad3149246f942e1e4d3204fa1ef5`（本次运行起点，tree `6b1adabe…`） |
| 契约稿提交 | `6f5e2afaae97e788feebbc3fb937d77565e38959` |
| 前端整合 | `d4262ea`：普通 merge MACC-54 的干净固定提交 `31002393ee117943b88f9945866f315ed2fb955b`（tree `fe61b6f…`），不含 `agent/kimi/macc-54` 顶端 `75f204d` 的临时文件 |

恢复运行在 `b46009b` 上以普通提交继续，不 reset/rebase/amend/force。保存树中的 `.agent_tmp/`（首次运行的 6 个临时编辑脚本）以普通删除提交移除；
本次运行以普通提交 `cb938c4` 移除中断运行混入的 `.agent_edit.py`、`.agent_*.json`、`.agent_verify*.log` 与误入的 worktree gitlink
（只从索引移除，没有触碰实际的其他工作树），并在 `.gitignore` 中忽略 `.agent_*`、`.claude/worktrees/`。中断时只写入临时脚本、未执行的
控制器/测试修改经逐条审阅后以 `955d767` 落地并补测试（没有盲跑临时脚本）。

### 1. 范围与分工

| 部分 | 内容 | 状态 | 负责 |
|---|---|---|---|
| A 内部标签查询能力 | 旧模板标签专属条件 `isend/timeadd/timehits/time/hits*/not/rel/typenot/type/tv/weekday/plot/start/level 多值`（第 2 节） | 已实现 | C |
| B SSR 搜索补全 | B04 新键、`tid`、`by=rnd`、逐键零值归一（第 3.1–3.5 节） | 已实现；筛选区与隐藏字段为契约稿（第 3.3 节，待前端确认样式） | C（K 定样式） |
| B' 位置参数路由 | `vodsearch/<14 位>`、`vodshow/<12 位>`、`vod/show` 分类筛选页（第 3.6 节） | 已实现 | C |
| C 文章积分判定 | 按 `user.art_points_type` 取列（第 4 节） | 已实现 | C |
| D 内容 SSR 页 | 文章（含分页）、专题、演员、角色、网址、剧情的列表与详情（第 5 节） | 已实现 | C 页面模板与视图模型；K 样式（MACC-54） |
| E 播放 / 下载 / 密码 / 试看 | `vod/play`、`vod/player`、`vod/down`、`vod/downer`、`playerconfig.js`、`player_aaaa`、W1/W2 两态（第 6 节） | 已实现（D-B05-1～4） | C；K 样式与试看脚本（MACC-54） |
| F `ajax_*` 与 `rss` | 各类型 `ajax_detail/ajax_show/ajax_search`、模块 `show`/`search` 页与单条 `rss`（第 7 节） | 已实现（D-B05-5/6） | C |
| G 手机模板与语言包 | 路线图第 3 节把 `mob_status` 手机模板切换与 `application/lang/zh-cn.php`、`zh-tw.php` 列为 B05、B12 共有（第 7 节末） | 只登记差异（D-B05-8），手机模板与 zh-cn/zh-tw 输出归 B12（MACC-36） | — |

共享文件（前后端都会改动、须先协调再改）：`backend/src/main/jte/site/components/`、`backend/src/main/resources/static/site/`（前端维护，02 规范第 7 节）；`jte/site/layout/`（外壳）；以及组件 `@param` 签名。后端没有修改共享文件：`static/site/` 的变更全部来自 MACC-54 的固定提交（普通 merge），`components/`、`layout/` 与 `@param` 签名未改；新页面沿用既有 class（`filter-group`/`filter-chip`、`content-grid`/`content-card`、`pagination`）。

### 2. 内部标签查询能力（已实现）

#### 2.1 入口与护栏

- 入口：`content.application.VideoLegacyConditions`（不可变 record + builder），作为 `VideoListQuery.legacy` 交给 `ContentReadService.listVideos`。公开 API 的 `VideoListQuery.parse` 一律为 `VideoLegacyConditions.NONE`，所以**不是新的前台 URL 输入**；API 对 `isend=1`、`timeadd=…` 等参数名仍按未声明参数忽略（B04 已有测试）。
- 旧标签属性字符串 → 条件的换算：`LegacyTagAttributes`（`type/typenot/not/level/rel/isend/plot/start/tv`）、`LegacyRange.parseLegacy`（点击数范围）、`PhpIntval`（PHP `intval`）。新版不解析旧模板语法（模板来源 D9 未定），这些换算只把旧属性的语义固定下来，供页面与将来的模板转换给出等价条件。
- SQL：`VodMapper.xml` 的 `legacyConditions` 片段追加在 1.3.0 条件之后，count 与分页 `<include>` 同一片段；列名与运算符写死在 XML（点击数范围按 `LegacyRange.Op` 枚举名 `<choose>`），值只经 `#{}`；`legacy == null` 时 SQL 文本与 1.3.0 逐字相同。
- 成本护栏：包含匹配（`weekday`、文本形式的 `rel`）与 `keyword` 等共用搜索开关（默认关，关闭时 400）、并发许可（满时 503）与 `MAX_EXECUTION_TIME(3000)`；所有列表项数 ≤ 100（`MAX_ITEMS`），文本 ≤ 64 码点，`start` ≤ 100000；始终限定在可见分类内（1.0.0 起的可见性不放宽）。

#### 2.2 旧属性 → Java 内部入口 → 测试

旧代码均为 `application/common/model/Vod.php`（`listCacheData`，:108-567）。"黄金"列是 `backend/src/test/resources/content/vodtag/php-golden.json`（PHP 8.0.30）与 `php73-golden.json`（PHP 7.3.33）中的用例前缀，由 `backend/src/test/php/vod_tag_golden.php` 在一次性库上调用旧 `listCacheData` 生成，`VideoTagGoldenMySqlIT` 在真实 MySQL 上逐条比对 ID 序列与 total（两份共 186 × 2 个用例；旧代码报错的用例断言新版构造时拒绝）。

| 旧属性 | 旧代码 | Java 入口 | 语义要点 | 黄金用例 / 单元测试 |
|---|---|---|---|---|
| `isend` | :147、:400-402 | `VideoLegacyConditions.isEnd` ← `LegacyTagAttributes.zeroOrOne` | `in_array($v,['0','1'])` 宽松比较：`0`、`1` 都是有效的显式筛选；PHP 8 下 `" 1"`、`"01"`、`"1.0"` 等于 1，PHP 7.3 的差异逐条列出 | `isend.*`（8）；`LegacyTagValuesGoldenTest.zeroOrOneMatchesPhp8AndListsThePhp73Difference` |
| `plot` | :484-486 | `plot` ← 同上 | 同上 | `plot.*`（4） |
| `tv` | :315-317 | `tvs` ← `textList` | `vod_tv IN (explode(','))`，保留空项 | `tv.*`（5） |
| `weekday` | :311-314 | `weekday` | `mac_like_arr` 包含匹配 OR，空项为 `%%`；受搜索开关/许可/时限 | `weekday.*`（4） |
| `timeadd` / `timehits` / `time` | :318-329 | `addedAfter` / `hitsTimeAfter` / `updatedAfter`（Unix 秒） | 旧值为 `intval(strtotime(v))`，列 `> ?`；换算 `strtotime` 由调用方完成（黄金样本记录了 4 个 `strtotime` 向量），`strtotime` 失败时旧版为 `> 0` | `timeadd.*`、`timehits.*`、`time.*`（各 4） |
| `hits` / `hitsday` / `hitsweek` / `hitsmonth` | :363-394 | `hits` / `hitsDay` / `hitsWeek` / `hitsMonth` ← `LegacyRange.parseLegacy` | "运算符 值"两段；只接受 `eq neq gt egt lt elt between notbetween in notin` 及其符号写法（不区分大小写），值为整数；`exp`、`like`、只有一段等旧写法拒绝（旧版 `exp` 是原样 SQL） | `hits*.*`（各 17）；`rangesAcceptOnlyWhitelistedOperatorsAndIntegers`、`unsupportedRangesAreRejected` |
| `not` | :278-280 | `notIds` ← `intList` | `vod_id NOT IN`，各项 intval | `not.*`（6） |
| `rel` | :281-288 | 数字时 `relIds`，否则 `rel`（文本） ← `LegacyTagAttributes.rel` | 全为数字字符串时 `vod_id IN`；否则 `vod_rel_vod` 包含匹配（受开关） | `rel.*`（6）；`relIsIdsOnlyWhenEveryItemIsNumeric` |
| `type` | :337-351 | `types` ← `LegacyTagAttributes.types` | 每项本身 + 直接子分类；`all`/空/`0` 不筛选；`current` 为当前分类；PHP 宽松比较的数字字符串（`06`、`6.0`、` 6`）命中，其余项忽略 | `type.*`（15）；`typesUseLooseNumericComparison` |
| `typenot` | :352-354 | `typeNot` | 覆盖 `type` 与 `categoryId`（同一个 `$where['type_id']`） | `typenot.*`（5）、`override.type-typenot` |
| `level`（多值、`all`） | :285-290 | `levels` ← `LegacyTagAttributes.levels` | `all` 为 1..9；与 `VideoFilter.level` 是同一变量，只能设一个 | `level.*`（6） |
| `start` | :134-136、:157-159 | `start` | `intval(abs())`；偏移 = 每页 ×（页 − 1）+（start > 1 ? start − 1 : start），total 不变 | `start.*`（5）；`startIsAbsoluteValueAndOffsetSkipsOneOnlyAboveOne` |
| `num`、`order`、`by` | :134、:500-537 | `VideoListQuery.pageSize/sort/descending` | 既有能力 | `num.3`、`order.hits_month-asc` |
| `ids` / `not` / `rel` 覆盖 | :273-284 | `ContentReadService.criteria` | 同一个 `$where['vod_id']`：数字 rel 覆盖 not，二者覆盖 ids | `override.*`（5） |
| 组合 | — | — | | `combo.isend-level-tv`、`combo.hits-time` |
| `half` | :565 | 无（不影响查询） | 只供模板把一页拆成两半 | — |
| `tid`（URL） | :180-182、:356-358；`common.php:1569` | `VideoLegacyConditions.tid` | 见第 3.5 节 | `tid.*`（33，`kind=url`） |

PHP `intval` 规则（`PhpIntval`）：51 个向量在两个 PHP 版本上一致（`LegacyTagValuesGoldenTest.intvalMatchesPhpForEveryVectorOnBothVersions`）。

### 3. SSR 搜索补全

#### 3.1 查询串键（`LegacyUrlParser.parseSearch`）

旧 `mac_param_url`（`application/common.php:1560-1606`）读出的键逐项核定如下；"Java"列是 B05 之后的行为。

| 旧键 | 旧读取方式 | 旧在 `vod/search` 数据中的作用 | Java |
|---|---|---|---|
| `wd area lang year class level by order page` | 见 14 文档 2.2 | 条件 | 既有（MACC-20） |
| `actor director tag letter state version name ids` | 同上 | 条件（`Vod.php:174-247`） | **承接**，交给 `VideoFilter` 同一套规则 |
| `tid` | `intval` | `type_id = tid OR type_id_1 = tid` | **承接**（第 3.5 节） |
| `by=rnd` | 白名单 | 随机取页（`Vod.php:500-537`） | **承接**（第 3.4 节） |
| `s` | ThinkPHP 兼容模式（`thinkphp/library/think/Request.php:403-406`），改变路由而不是条件 | — | 仍交 PHP（本地 404）；**待实施**：与三页一样按兼容模式解析，`s` 解析为搜索路径时按同一规则处理（第 3.6 节一并实施） |
| `ajax` | `intval` | 不进 `listCacheData`；只供模板使用（仓库无模板）【代码】 | 400（未知参数），**待裁定 D-B05-6**：改为"接受并忽略、不进链接" |
| `isend` | `mac_param_url` 不读 | 读不到（`Vod.php:244` 检查的是 `$param`） | 400（未知参数）；标签能力见第 2 节 |
| `id mid rid pid sid nid uid score limit en file url type sex blood starsign domain` | 读取 | `vod/search` 不使用 | 400（未知参数），与 MACC-20 相同 |

键不解码、逐字比较；重复键 400；值只解码一次；查询串 > 2048 交 PHP（10 文档第 2 节不变）。

#### 3.2 逐键零值归一（队长裁定 Q2）

按旧逐键调用链判断，不用全局 intval。API 的字面 `0` 不变。

| 键 | 旧链 | SSR 归一（结果为"未传"时不筛选、不进链接） | 测试 |
|---|---|---|---|
| 文本键 `wd area lang class letter state version actor director tag name ids by order` | `trim` → `!empty()` | 去首尾 W 后为 `''` 或恰为 `'0'` → 未传；`00`、`0,`、`,0`、`0 0` 是字面值，交给 content 校验 | `LegacyUrlParserSearchB05Test.perKeyZeroNormalization` |
| `year` | `trim` → `strlen` 4/9 → `intval` 每段 → `!empty()`（`Vod.php:183-201`） | `''`、`'0'`、`0000`、`0000-0000`（两端 intval 都为 0）→ 未传；`0000-2020`、`0005` 照常 | 同上；`SiteSearchB05PagesMvcTest.zeroLikeValuesMeanNoConditionAndNoQuery` |
| `level` | `intval` → `!empty()` | intval 为 0（`0`、`00`、`abc`、`''`）→ 未传；其余 intval 结果交给 content（1..9，越界 400）；`level=0,1` 的 intval 为 0 → 未传 | 同上 |
| `tid` | `intval` → `!empty()` | intval 为 0（`0`、`abc`、`''`、`0x6`）→ 未传；**负数照常筛选**（无结果）；`01`→1、`6abc`→6、`1,2`→1、`1e1`→10、`+6`→6 | `tidFollowsPhpIntvalVectors`（读黄金 `tid.*`） |
| `page` | `intval`，<1 当 1 | 既有：`''` 未传，否则 1..1000、无前导 0，其余 400 | 既有 |

只有归一后仍有条件才查库；全部归一为空时显示无条件页（不查库），与 MACC-20 相同。

#### 3.3 筛选区、隐藏字段与链接键序（**前端契约稿**）

- **链接键序**：`actor, area, by, class, director, ids, lang, letter, level, name, order, page, state, tag, tid, version, wd, year`（按键排序，与 `mac_url` 一致，`application/common.php:1732-1746`；`LegacyUrlBuilder.SEARCH_LINK_KEYS`）。只输出已设置的键，`page` 只在 > 1 时输出，值用 PHP `urlencode`（逗号编码为 `%2C`）。canonical 只认这一顺序。
- **隐藏字段**：表单（`name="wd"`）提交时保留除 `wd`、`page` 外的全部当前条件，顺序与链接键序相同，值为解码后的原值（如 `ids` 为 `1,,2`）；随机排序保留 `by=rnd`。
- **筛选组**（顺序固定，每组第一个为"全部"，当前项 `aria-current="true"` + `filter-chip--current`，组标题 `id="search-filter-<键>"`，列表 `aria-labelledby` 指向它）：

| 顺序 | 键 | 组标题 | 候选值 | 出现条件 |
|---|---|---|---|---|
| 1 | `area` | 地区 | 可见分类 `type_extend.area` 并集 | 有候选或已设置 |
| 2 | `lang` | 语言 | `type_extend.lang` | 同上 |
| 3 | `year` | 年份 | `type_extend.year` | 同上 |
| 4 | `letter` | 首字母 | 固定 `A`–`Z`、`0-9` | 始终 |
| 5 | `state` | 状态 | `type_extend.state` | 有候选或已设置 |
| 6 | `version` | 版本 | `type_extend.version` | 同上 |
| 7 | `class` | 类型 | `type_extend.class` | 仅包含匹配开关打开 |
| 8 | `actor` | 主演 | `type_extend.star` | 同上 |
| 9 | `director` | 导演 | `type_extend.director` | 同上 |
| 10 | `tag` | 标签 | 无 | 仅已设置（"全部" + 当前值，便于去掉） |
| 11 | `name` | 片名 | 无 | 仅已设置 |
| 12 | `ids` | 指定视频 | 无 | 仅已设置 |
| 13 | `tid` | 分类 | 无（显示可见分类名，不可见/不存在时显示"分类 N"） | 仅已设置 |
| 14 | `by` | 排序 | 时间、人气、评分；随机排序时另有"随机"为当前项 | 始终 |

  候选值去重、保持出现顺序、每组最多 30 个，跳过不能回读的值（`''`、`'0'`、首尾空白、不满足 content 规则、超出链接预算）。点候选值换成该单值并回到第 1 页，其余条件保留。
- **需要前端确认**：14 组在窄屏下的排列（首字母组 28 项）、"仅已设置"组与普通组的视觉区分、随机排序无分页时的提示样式。后端不改 `site.css`。

#### 3.4 随机排序（`by=rnd`）

按契约 1.3.0 的同一算法（`VideoListQuery.random`，14 文档第 4 节）：`sort` 固定 `hits_week desc`；算法一以随机页覆盖请求页（响应的页码可能大于请求的），算法二用请求的页。页面**不显示分页**（每次取页不同，上一页/下一页没有意义），越界不判 404（结果为空时显示"没有找到相关视频"，200）；"随机"作为排序组的当前项；换成其他排序时去掉 `rnd`。【测试】`SiteSearchB05PagesMvcTest.randomSort*`。

#### 3.5 `tid`（队长裁定 Q2）

- 内部条件 `(type_id = ? OR type_id_1 = ?)`，叠加已接受的可见分类与发布状态限制（结果只含可见分类中的行），**不映射为 `categoryId`**，公开 API 不加别名。
- 与旧版的覆盖关系：旧 `$where['type_id']` 来自 `type`/`typenot`（标签），`tid` 写入的是独立的 `$where['type_id|type_id_1']`（`Vod.php:356-358`），所以与 `categoryId`、`types` 同时存在时取 AND（`VideoTagGoldenMySqlIT` 的 `tid.current-*` 用例）。
- 冗余列不一致（`type_id_1` 与分类表的上级不同）、停用分类、多级分类：按旧 OR 条件比较 ID 集合，`VideoTagGoldenMySqlIT.ssrSearchTidMatchesLegacyAcrossFollowedPages` 以真实 HTTP 沿"下一页"链接取完所有页，ID 序列与 total 与旧版一致。

#### 3.6 位置参数路由与 `vod/show`（已实现，D-B05-7）

旧路由（`application/route.php:321,331`，只在 `rewrite.route_status=1` 时生效）与位置顺序见 14 文档第 2.3 节（`LegacyVodUrlMappingTest` 已锁定两份黄金样本的逐位映射）。拟定：

- 解析器在固定搜索路径之外认 `/index.php/vodsearch/<…>.html`（`rewrite` 时另加 `/vodsearch/<…>.html`；`suffix_hide` 时无后缀）：按 `-` 切成 14 段，多出的段并入第 1 位（与旧版 `wd=a-b` 相同），空段为未传，每段只解码一次后按第 3.1、3.2 节同一规则处理；带查询串时查询串覆盖同名位置（旧 `array_merge($input, $_REQUEST)`）。结果是同一个 `SiteRoute.VodSearch`，**canonical 指向查询串形式**（Java 只生成一种链接，不 301）。
- `vodshow/<id>-…`（12 段，第 1 段为分类）：旧 `vod/show` 是分类筛选页（`Vod.php:23-28`，`check_show` 受 `app.show` 开关，仓库值为关闭【待核实】）。拟定映射到同一个搜索页：第 1 段按 `tid` 处理（intval，0 为未传）——**与旧 `vod/show` 的分类条件不完全相同**（旧 show 的列表条件由模板标签决定，通常是 `type="current"`，即本身 + 直接子分类），所以列入 **D-B05-7** 待裁定：① 映射为 `tid`（OR 冗余列）；② 映射为"当前分类"语义（`types=[id]`）；③ 实现独立的筛选页。推荐 ②，canonical 仍指向搜索页的查询串形式需另加键（如 `type`，不在旧 `vod/search` 键中），因此 ② 实际需要独立页面地址——队长裁定后再定链接形态。
- 不依赖路由别名配置（新版不生成别名形态、B02 对旧配置 `route_status` 为真时仍启动失败），所以位置参数地址只作为**输入**被认可。年份区间不能写在位置参数地址中（旧版同样不行，14 文档 2.3）。
- 测试：每一位的映射与空位、`-` 冲突、查询串覆盖、编码一次、与查询串形式的链接往返（解析结果相同、canonical 相同）、真实 HTTP。

**实施（按 D-B05-7）**：`LegacyUrlParser.parseVodList` 统一承接 `vod/search`（固定路径 + 查询串、兼容模式 `s`）、`vod/ajax_search`、
`vodsearch/<14 位>`、`vod/show/id/<分类>[/键/值…]`、`vod/ajax_show/…` 与 `vodshow/<12 位>`。位置/路径形式先按 PATH_INFO 解码一次，
查询串覆盖同名值（旧 `array_merge($input, $_REQUEST)`），之后与搜索页同一套归一与校验。`vod/show` 是独立入口 `SiteRoute.VodShow`：
第 1 槽为分类，条件为"分类本身 + 直接子分类"（即 API `categoryId`，**不是** `tid` 的冗余列 OR），叠加可见性/发布约束与旧 `app.show`
开关（默认关，`SiteFeatures`）；不接受 `tid`（400）；规范地址 `vod/show/id/<分类>.html?<条件>`（`LegacyUrlBuilder.vodShowLink`），
筛选、排序、分页、表单都保留分类；`vod/search` 不新增 `type` 键，API `categoryId` 不变。`vodshow` 多于 12 段、分类缺失/前导 0/越界不认（404）。
【测试】`LegacyUrlParserVodListTest`（PHP 8.0/7.3 黄金 `urls[vodsearch.*]`、`urls[vodshow.*]` 共 58 条逐条与查询串形式对照、路径参数、
四种 URL 形态往返）、`SiteVodListPagesMvcTest`、`SiteVodListClosedMvcTest`，真实 HTTP 见第 10 节。

#### 3.7 链接预算

B05 的新键共用 MACC-20 的查询串预算 1209 字符（`LegacyUrlBuilder.MAX_SEARCH_LINK_QUERY`，含最长的 `page=1000`）；超出预算的条件组合在 `SearchFilters` 构造时拒绝，搜索页 400 且不回显输入，所以生成的任何链接都在 `SafeUrl` 上限内。【测试】`combinationsBeyondTheLinkBudgetAre400`、`overBudgetCombinationIs400WithoutEcho`、`randomConditionsWithNewKeysRoundTrip`（固定种子随机条件 × 四种形态的生成与回读不变式）。

### 4. 文章积分判定（已实现）

- 旧：`check_user_popedom`（`application/index/controller/Base.php:108-112`）中 `user.art_points_type == '1'` 时取 `art_points`（整篇），否则取 `art_points_detail`（按页）；游客（`group_id < 3`）且积分 > 0 时显示扣积分确认页（:123-140）；`user.status=0` 时不检查（:115-117）。
- 新：`maccms.content.art-points-type` / `MACCMS_CONTENT_ART_POINTS_TYPE`，只接受 `0`/`1`，留空按仓库值 `1`（`application/extra/maccms.php:122`）；其他值启动失败且不回显。积分列仍只以 SQL 布尔值读出（`art_points > 0`、`art_points_detail > 0`），值不入内存。
- 判定顺序与错误码不变：404 `ARTICLE_NOT_FOUND`（不存在/未审核/分类不可见/无详情权限）→ 403 `ARTICLE_POINTS_REQUIRED` → 403 `ARTICLE_PASSWORD_REQUIRED`。【测试】`ArticlePointsTypeTest`（类型 × 两列组合 × 密码的判定顺序、`user.status=0`、默认值、非法值不回显）、`LibraryMySqlIT`。
- 取代 B03 暂时的"两列任一大于 0 即拒绝"（11 文档第 5 节差异 #6 更新为按配置）。旧配置目录接入：content 不读旧配置文件（02 规范第 2 节），部署时按旧 `user.art_points_type` 设置该环境变量【待核实线上值】。

### 5. 内容 SSR 页（已实现）

#### 5.1 页面与路由

全部只认旧默认 URL 形态（`/index.php/<模块>/<动作>/id/<n>[/page/<n>].html` 及 `rewrite`/`suffix_hide` 变体，与三页相同的解析规则：数字 id、无前导 0、不认查询串、非规范形态只输出 canonical）。别名、`-` 分隔符、拼音/编码 id 仍不支持（B02 启动检查不变）。

| 页面 | 旧入口 | 数据（已发布服务，契约 1.2.0） | 404 / 403 |
|---|---|---|---|
| 文章分类列表 | `art/type`（`Art.php:17-21`） | `ArticleReadService` 列表（categoryId，time desc，20） | 分类不可见 404 |
| 文章详情 | `art/detail`（`:52-60`）+ `page` | `ArticleReadService` 详情（一次返回全部分页，SSR 只渲染当前页） | 404；积分/密码见 6.4 |
| 专题首页 / 详情 | `topic/index`、`topic/detail` | `TopicReadService` | 404 |
| 演员首页 / 分类 / 详情 | `actor/index`、`actor/type`、`actor/detail` | `ActorReadService` | 404 |
| 角色首页 / 详情、视频角色页 | `role/index`、`role/detail`、`vod/role` | `RoleReadService` | 404 |
| 网址分类 / 详情 | `website/type`、`website/detail` | `WebsiteReadService` | 404 |
| 分集剧情 | `vod/plot`（`vodplot/<id>-<page?>`）、`plot/detail` | `VideoPlotReadService` | 与视频详情相同；密码/版权按 6.4 |

`*/show`（筛选页，受 `app.show`）、`*/search`（文章/演员/专题/角色/网址/剧情的搜索）按 D-B05-5 在本批实现，见第 7.2 节。
实现：`SiteLibraryController` + `LibraryPages`（列表、详情、单条 RSS、`ajax_detail`），文章分页与富文本见 5.2、5.3。【测试】
`SiteLibraryPagesMvcTest`、`SiteAjaxFragmentsMvcTest`，真实 HTTP 与浏览器见第 10 节。

#### 5.2 文章分页（补充验收）

- 旧：`mac_label_art_detail` 取 `page` 参数（`intval`，<1 当 1），越界取最后一页（`application/common.php:2708`）；`mac_page_param($info['art_page_total'], 1, page, url)` 生成分页（`application/common/controller/All.php:373-376`）。
- 新：页面只渲染当前页的标题、备注与正文（`ArticleContentPageDto` 的第 page 项），不把全部正文同时输出；分页组件复用 `pagination`（上一页/下一页 + "第 n / N 页"）。规范地址：第 1 页为不带 `page` 的地址，第 n 页为 `/page/n`；越界（page > 总页数）——旧版显示最后一页（200），**新版拟显示最后一页并以 canonical 指向最后一页**（保持旧版 200 行为，不 404）。`page=0`/前导 0/非数字：按三页的解析规则为 404（与 MACC-14 一致的差异）。
- 测试：首/中/末页、越界、长文章（总页数 1000 以上时分页链接上限与三页相同）、只有一页时不显示分页、分页链接往返。

#### 5.3 富文本与文本字段（补充验收）

- 原始不可信 HTML：`art_content`（每页）、`topic_content`、`actor_content`、`role_content`、`website_content`、剧情 `detail`，一律经 `RichTextSanitizer` 得到 `SafeHtml`，只放在元素内容位置（07 文档、MACC-14 规则）；缓存键包含类型、id、页码、更新时间、原文长度与哈希。
- 文本字段（名称、副标题、备注、标签、演员等）经 `LegacyTextEntities.decodeOnce` 按字段清单解码一次后由 jte 转义；字段清单在实施时按 07 文档第 4 节补齐并写入本节。
- 测试：脚本、事件属性、`javascript:`/`data:` 等危险 URL、`style`、合法富文本（段落、列表、链接、图片、表格）保留；每种内容类型至少一组。

#### 5.4 视图模型与 class（前端契约稿）

页面模板在 `jte/site/pages/`（后端），视图模型在 `site.web.view`（不可变 record）。拟新增的语义 class（BEM 子类另列于实施提交）：`article-page`、`article-pager`、`topic-page`、`topic-list`、`actor-page`、`role-page`、`website-page`、`plot-page`、`plot-list`、`content-grid`、`content-card`（演员/专题/网址等的卡片）、`detail-content`（既有，正文容器）、`screenshot-list`。**需要前端确认**：`content-card` 是否作为新组件（`components/contentCard.jte`，由前端建立并确定 `@param`），还是由页面内联；样式由前端在 `site.css` 实施。

### 6. 播放 / 下载 / 密码 / 试看（已实现，D-B05-1～4）

#### 6.1 旧行为（【代码】`application/index/controller/Vod.php:81-123`、`application/common/controller/All.php:442-540`、`Base.php:92-231`、`Ajax.php:400-458`）

| 入口 | 判定顺序 | 输出 |
|---|---|---|
| `vod/play`（`vodplay/<id>-<sid>-<nid>`） | 视频存在且审核（否则错误页）→ 试看时长 = `vod_trysee > 0 ? vod_trysee : user.trysee` → `check_user_popedom(type, 3)`：无播放权限且（无试看权限或时长为 0）→ 无权限；游客、无播放权限、有试看权限、时长 > 0 → 试看（3002）；游客且积分 > 0 → 扣积分确认页（3003）→ 失败且非试看时出确认页/错误页 → 组 `player_aaaa` → 试看、播放密码未验证或 `vod_copyright=1 && copyright_status=4` 时只输出指向 `vod/player` 的 iframe，否则内联 `player_aaaa` + `playerconfig.js` + `player.js` → `vod_copyright=1 && copyright_status=3` 时显示版权页 | 播放页 |
| `vod/player`（iframe 内页） | 同上取数据，权限检查 `check_user_popedom(type, 5)` 不中止 → `copyright_status=4` 版权页 → `vod_pwd_play` 非空且 session `1-4-<id>` 未验证 → 播放密码页 → 播放器页 | 播放器 |
| `vod/down` / `vod/downer` | `check_user_popedom(type, 4)`：无下载权限 → 错误；游客且积分 > 0 → 4001 → 下载页；`downer` 另判 `vod_pwd_down` 与 session `1-5-<id>` | 下载页 / 下载地址页 |
| `ajax/pwd`（POST/GET 均可） | `mid∈{1,2}`、`type∈{1,4,5}`、id 与 pwd 非空 → session 已验证 → 5 秒频率限制 → 按 id 取视频/文章（**不检查审核状态**）→ `!=` 宽松比较密码 → 写 session | JSON |

`player_aaaa` 字段（:476-530）：`flag`、`encrypt`（`app.encrypt` 0/1/2：原样 / `escape` / base64）、`trysee`、`points`、`link`（`{sid}`/`{nid}` 占位的本页地址模板）、`link_next`、`link_pre`、`vod_data`（`vod_name/vod_actor/vod_director/vod_class`）、`url`、`url_next`（`upload` 开头时加站点根路径）、`from`（集的来源优先于组来源）、`server`、`note`、`id`、`sid`、`nid`。试看的计时与结束由模板脚本实现（`static/js/player.js` 不读 `trysee`，仓库无模板）【待核实】。

#### 6.2 拟定结构

- **播放页 `vod/play`**（严格策略，与三页相同：无脚本、CSP `script-src 'none'`）：面包屑、标题、当前集、播放源分组与剧集列表（`LegacyMultiValue.playList`，按 `app.player_sort` 排序与启用状态过滤，播放器配置来自 B02 的旧配置读取）、上一集/下一集链接，以及一个 `<iframe>` 指向同源的播放器页。剧集链接、iframe 地址都是 `SafeUrl`。**播放页本身不含任何播放地址**。
- **播放器页 `vod/player`**（专用策略，只在这一类页面放开脚本）：按旧版原样输出 `player_aaaa`（JSON，`<`、`>`、`&`、U+2028/2029 转义为 `\uXXXX`）、`static/js/playerconfig.js`（按旧配置生成，见 6.3）与 `static/js/player.js`；`static/player/*`（含 `iva`）原样复用。CSP 拟为 `default-src 'none'; script-src 'self' 'nonce-<每次请求随机>'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; frame-src 'self'; connect-src 'self'; frame-ancestors 'self'; base-uri 'none'; form-action 'self'`，并按开关放开：W1 打开时 `frame-src`/`script-src` 增加解析地址与 `iva` 所需主机（来自配置，不是通配），W2 打开时 `media-src`/`img-src`/`connect-src`/`frame-src` 增加 `https: http:`（外部播放地址的主机来自采集数据、无法预先列举）。`player.js` 用 `document.write` 与内联 `<style>`，所以该页不能沿用严格策略——**这是对 02 规范第 4 节模板策略的例外，须队长裁定（D-B05-1）**。
- **下载页 `vod/down`**（严格策略）：分组与条目列表；**下载地址页 `vod/downer`**：不需要脚本，地址以普通链接输出（`SafeUrl`，W2 关闭时外部地址不输出，见 6.5）。
- **`playerconfig.js`**：按旧格式（`MacPlayerConfig={…}` + `player_list`/`downer_list`/`server_list`）由 `application/extra/vodplayer.php`、`voddowner.php`、`vodserver.php` 与 `maccms.php` 的 `play.*` 生成（B02 `LegacyConfigService` 读取），与 PHP 黄金样本逐字段一致（W1 验收项）。以静态资源路径 `/static/js/playerconfig.js` 提供，内容在启动时生成、只读。

#### 6.3 W1 / W2 两态（路线图 4.2 节）

| 开关 | 打开（默认，按旧版） | 关闭 |
|---|---|---|
| W1 | `playerconfig.js` 与 `player_aaaa` 按旧版输出 `parse`、`prestrain`、`buffer`；`ps=1` 的来源由浏览器用解析地址打开；`iva` 加载外部脚本 | 页面与 `playerconfig.js` 不出现外部解析地址；`ps=1` 的来源在播放器页显示固定本地提示（不加载 iframe）；`prestrain`/`buffer` 为外部地址时置空；`iva` 不加载外部脚本（该来源显示同一提示） |
| W2 | `player_aaaa.url/url_next`、下载地址按旧版输出外部地址 | 外部播放/下载地址不输出（`url`/`url_next` 为空串），播放器页、下载地址页显示固定本地提示 |

拟定固定文案（**需队长定稿**）：W1 关闭"该播放源需要外部解析服务，本站当前未开放。"；W2 关闭"该地址位于外部站点，本站当前未开放外部播放。"/"……外部下载。"。"外部"按 `maccms.site.base-url` 的主机判定（B02 规则）。两态验收：自身静态资源检查（页面引用的 `static/` 资源都存在且同源）与参数两态检查分别进行，关闭态断言页面、`playerconfig.js`、响应头中没有任何外部主机。

#### 6.4 授权、密码与试看（游客）

| 情况 | 旧 | 新（拟定） |
|---|---|---|
| 视频不存在/未审核/分类不可见 | 错误页 | 404 |
| 游客无播放（位 3）/下载（位 4）权限且无试看 | 错误跳 `user/index` | 404（与详情页无权限一致）——或 403 提示页，**D-B05-2** |
| 需积分（`vod_points_play`/`vod_points_down`/`vod_points`，按 `user.vod_points_type` 取列） | 扣积分确认页 | 固定提示页"需要登录并使用积分观看"，不显示积分数（购买归 B10）；**HTTP 403**，`no-store` |
| 试看（游客有位 5、无位 3、时长 > 0） | 播放器页照常给出地址，由模板计时 | 播放器页照常输出 `player_aaaa`（含 `trysee`），**与旧版相同把地址交给浏览器**；试看计时由前端脚本实现（静态资源，K）【待核实旧模板】 |
| 播放/下载密码（`vod_pwd_play`/`vod_pwd_down`） | 密码页 → `ajax/pwd` 写 session | 密码页（GET，无脚本表单）→ **POST** 到同一地址；密码只以 SQL 比较（`vod_pwd_play = ?`，**值不读入内存**）；验证通过后发放签名 cookie（HMAC，键为 `<mid>-<type>-<id>`，会话 cookie、`HttpOnly`、`SameSite=Lax`、`Path` 为站点根），**D-B05-3**：需要一个部署密钥（环境变量，未配置时密码页显示"暂不支持在线验证"并保持 403） |
| 版权 `copyright_status=3/4` | 版权页 | 403 固定提示页（与详情的 503 交 PHP 不同，**D-B05-4**：播放/下载页一并改为提示页还是维持 503） |
| 频率限制 | session 5 秒 | 进程内按"视频 + 类型"计数的固定窗口限流（不记录 IP 与密码），超限 429 |

公开元数据不变：`VideoDetailDto` 等 API 不新增播放/下载地址、密码、积分字段；`player_aaaa` 只出现在播放器页，且只在权限、密码、版权检查都通过（或试看）之后。

### 7. `ajax_*`、模块筛选/搜索页与 `rss`（已实现，D-B05-5/6）

- 旧 `*/ajax_detail`、`*/ajax_show`、`*/ajax_search`：返回模板片段（`label_fetch('<模块>/ajax_*')`），受 `app.ajax_page` 开关（`Base.php:45-51`）；仓库没有这些模板，片段结构无从对齐【待核实】。拟定：只实现 `ajax_detail`（各类型）为详情页主体的 HTML 片段（同一视图模型与过滤，`no-store`），`ajax_show`/`ajax_search` 随 `*/show`、`*/search` 归入遗留。**D-B05-5**：是否接受该范围。
- 路线图第 5.3 节 B05 行写作"`ajax_*` JSON"，而旧 `ajax_*` 是 HTML 片段（上一条）；以 D-B05-5 的裁定为准，裁定后同步修改路线图。
- 旧 `vod/rss`、`art/rss`、`topic/rss`、`actor/rss`、`role/rss`、`website/rss`、`plot/rss`：单条内容的 RSS（模板缺失）。拟定：`application/rss+xml`（UTF-8），一个 `channel` + 一个 `item`（标题、链接、描述 = 简介纯文本、`pubDate` = 更新时间），文本做 XML 转义，正文不输出。站点级 `rss/index`、`rss/baidu` 等归 B06。
- 手机模板与语言包：新页面是响应式单模板（MACC-14 差异），`mob_status` 没有独立模板可切换；语言包只有 zh-cn/zh-tw 且新页面文案为简体中文固定文案。拟定 B05 只登记差异、不实现切换，繁体输出随 B12（**D-B05-8**）。

#### 7.2 模块筛选页（`show`）与搜索页（`search`）及 ajax 片段（D-B05-5）

| 旧入口（Base.php 开关） | Java 入口 | 条件（旧参数 → 内部条件） | 测试 |
|---|---|---|---|
| `vod/ajax_search`、`vod/ajax_show`（`check_ajax` + `check_search`/`check_show`） | `SiteSearchController`、`SiteVodShowController`，片段模板 `site/ajax/videoList` | 与 `vod/search`、`vod/show` 相同 | `SiteVodListPagesMvcTest` |
| `art/search`、`art/show`、`ajax_*`（Art.php:23-53） | `SiteModuleListController` → `ModuleListPages` → `LibrarySearchService.articles` | `wd`→`art_name/art_en/art_sub LIKE`（`search_art_rule` 仓库值）；`name/tag/class`→LIKE（mac_like_arr）；`ids`→IN；`tid`→`type_id = tid OR type_id_1 = tid`；`level/letter`；`by/order` | `ModuleSearchGoldenMySqlIT`（PHP 黄金 20 例）、`LegacyUrlParserModuleListTest`、`SiteModuleListPagesMvcTest`、`LibraryMatchTest` |
| `topic/search`、`ajax_search`（Topic.php:18-33） | 同上 → `topics` | `wd`→`topic_name/en/sub`；`tag`→`topic_tag`；`class`→`topic_type`；`ids`；`level/letter` | 同上（6 例） |
| `actor/search`、`actor/show`、`ajax_*`（Actor.php:18-60） | 同上 → `actors` | `wd`→`actor_name/en`（不含别名）；`sex/area/starsign/blood` 精确；`ids`；未给分类的筛选页取第一个可见演员分类 | 同上（10 例） |
| `role/search`、`ajax_search`（Role.php:33-48） | 同上 → `roles` | `wd`→`role_name/en`；`rid`→`role_rid`；`ids` | 同上（4 例） |
| `website/search`、`website/show`、`ajax_*`（Website.php:23-53） | 同上 → `websites` | `wd`→`website_name/en`；`name`→`website_name IN`（精确）；`tag/class`→LIKE；`area/lang`；`ids` | 同上（9 例） |
| `plot/search`、`ajax_search`（Plot.php:20-35） | 同上 → `ContentReadService.listVideos` + 旧 `plot=1` | 视频的 `wd/level/letter/ids` 与排序 | 同上（3 例） |
| `topic/show`、`plot/show` | — | 旧控制器没有该操作 | 404 |
| `role/show`、`role/ajax_show`（Role.php:18-31） | — | 角色没有分类体系，旧 `label_type` 只能取到其他模块的分类；新版不提供（404，差异） | `LegacyUrlParserModuleListTest` |

- **开关**：搜索页随 `maccms.site.search.enabled`（旧 `app.search`）；筛选页随旧 `app.show`（`SiteFeatures.show`，默认关，`show_verify=1` 按关闭）；ajax 入口另需旧 `app.ajax_page`（默认关）。关闭时 404，不查库。
- **成本护栏**：包含匹配（`wd/name/tag/class`）经 `ContentReadService.guardedRead` 与视频列表共用 `maccms.content.search.enabled`（关 → 查库前 400）、并发许可（满 → 503）与 Mapper 的 `MAX_EXECUTION_TIME(3000)`；每页 20 条，越界页 404。公开 API 不新增参数（`ContentListQuery`/`ContentFilter` 不变，新条件只在 `LibraryMatch`）。
- **链接**：规范地址 `<模块>/search.html?<条件>`、`<模块>/show/id/<分类>.html?<条件>`（`LegacyUrlBuilder.moduleListLink`，键按 mac_url 排序、PHP urlencode；ajax 入口的分页链接指向完整页面）；查询串白名单 `SiteQueries` 另加 `sex/starsign/blood/rid`。
- **PHP 黄金**：`backend/src/test/php/module_search_golden.php` 在一次性库（`install.sql` + `site/module-search/seed.sql`）上以 `paging=yes` 调旧 `listCacheData`（条件经 `mac_param_url` 读取，`search_len` 按 Init.php 取 10）；PHP 8.0.30 与 7.3.33 结果逐项相同，52 例。`ModuleSearchGoldenMySqlIT` 以真实 HTTP 请求每例的规范地址，比对卡片 ID 序列、canonical、"共 N 条"与 ajax 片段：51 例相同，1 例为登记差异（`art.search.wd=_`：旧版 LIKE 不转义 `_ %`）。

### 8. 需要前端（Kimi）配合的事项

**已完成**：K1–K5 由 MACC-54 交付，队长阶段审阅通过后以普通 merge `d4262ea` 整合固定提交 `3100239`。K5 中"W2 关闭时外链 Logo 已由后端过滤"的结论与当时固定树不符，
已在 `docs/frontend/03-b05-pages-ui.md` 第 7 节更正；后端 `SitePages` 按 W2 过滤外站 Logo（站内路径与 base-url 同主机地址照常输出），测试见第 10 节。下表为原始分工记录。

| 事项 | 内容 | 涉及文件 |
|---|---|---|
| K1 搜索筛选区样式 | 第 3.3 节 14 组的窄屏排列、"仅已设置"组、随机排序提示 | `static/site/site.css` |
| K2 内容页组件 | `content-card` 是否作为组件及其 `@param`；文章分页与剧情列表样式 | `jte/site/components/`、`site.css` |
| K3 播放/下载页布局 | 播放页（剧集列表、上一集/下一集、iframe 容器的宽高比）、下载页、密码页与提示页样式；窄屏与键盘可达 | `site.css` |
| K4 试看计时脚本 | 若 D-B05-1 采纳"播放器页专用策略"，试看计时的静态脚本（同源、无外部依赖） | `static/site/`（新文件） |
| K5 外壳 | 播放页是否在外壳中加"播放"导航；Logo 输出时经 W2 判断 | `jte/site/layout/` |

在队长安排分工之前，后端不修改上述文件；后端负责页面模板、视图模型与全部验收。

### 9. 队长裁定（2026-10-17）与落实

裁定原文见 MACC-29 评论（D-B05-1～8）。落实：D-B05-1 播放器内页专用 CSP（逐响应 nonce，`PlayerFramePolicy`），外层 vod/play 与其余页面仍是严格策略；
D-B05-2 通过存在性/发布/可见性检查后无权限 403 固定提示、no-store；D-B05-3 签名 cookie（`PasswordTickets`，密钥只经环境配置，未配置时 403 说明不支持在线验证，
HttpOnly/SameSite=Lax/站点路径/HTTPS 下 Secure，绑定内容与用途、服务端有效期、当前密码摘要）；D-B05-4 版权按入口（3 对 play、4 对 player）403；
D-B05-5 第 7.2 节；D-B05-6 `ajax` 接受并忽略；D-B05-7 第 3.6 节；D-B05-8 只登记差异（第 11 节），承接见 MACC-36。下表为提交裁定时的推荐值。

| 编号 | 事项 | 推荐 |
|---|---|---|
| D-B05-1 | 播放器页放开脚本（专用 CSP + nonce，只限 `vod/player` 一类页面；`SiteTemplatePolicyTest` 对该类模板单列规则） | 采纳；其余页面维持 `script-src 'none'` |
| D-B05-2 | 游客无播放/下载权限：404 还是 403 提示页 | 403 提示页（"需要登录"），不泄露原因以外的信息 |
| D-B05-3 | 密码验证状态：签名 cookie（需新增部署密钥环境变量）或无状态（每次 POST 后直接渲染、不记住） | 签名 cookie；未配置密钥时不提供在线验证 |
| D-B05-4 | 版权限制的播放/下载页：403 提示页或维持 503 交 PHP | 403 提示页 |
| D-B05-5 | `ajax_*` 只做 `ajax_detail`，`ajax_show/ajax_search` 随 show/search 遗留 | 采纳 |
| D-B05-6 | 搜索页 `ajax` 参数：400 或接受并忽略 | 接受并忽略（旧版不影响数据），不进链接与 canonical |
| D-B05-7 | `vodshow` 位置参数的分类语义（第 3.6 节） | 映射为"当前分类"语义并新建独立筛选页地址；若不新建页面则映射为 `tid` 并登记差异 |
| D-B05-8 | 手机模板切换与语言包（第 7 节末） | B05 只登记差异（响应式单模板、简体固定文案），繁体随 B12 |

### 10. 验证（最终树）

所有检查只用一次性库 `maccms_it_<时间>_<随机>`（创建前校验库名，结束时删除并查 `information_schema.schemata` 确认不存在），不连接 `maccms_dev` 或任何已有库；
浏览器只访问本机应用与本地反向代理，所有非本机请求在浏览器内拦截并记录，不访问外部站点。`./mvnw verify` 与 `./mvnw verify -Pmysql-it` 的最终计数
以 MACC-29 交付评论中对最终提交的记录为准（本文件随最终提交一起变更，不在此重复会过期的数字）。

| 检查 | 方式 | 证据 |
|---|---|---|
| 单元/契约/MockMvc | `./mvnw verify` | 交付评论 |
| MySQL 集成与黄金向量 | `./mvnw verify -Pmysql-it`：`PlaybackGoldenMySqlIT`（PHP 播放黄金：权限/积分/试看/密码/版权判定与 `player_aaaa` 逐字段）、`ModuleSearchGoldenMySqlIT`（第 7.2 节，52 例）、`VideoTagGoldenMySqlIT`、`SiteSearchMySqlIT` 等 | 交付评论 |
| PHP 黄金向量 | `vod_play_golden.php`（`site/play/php-golden.json`）、`b05_url_golden.php`（`site/url-golden/b05-vectors.json`）、`module_search_golden.php`（PHP 8.0.30 与 7.3.33 逐项相同）、`content/vodlist` 的 `urls[vodsearch.*/vodshow.*]` | 资源文件 + 上列测试 |
| 前端试看脚本 | `node --test backend/src/test/js/`（MACC-54） | 8/8 |
| 真实 HTTP + 浏览器 | `python backend/src/test/browser/b05_site_check.py --out docs/architecture/15-b05-site-check`：已打包应用 × W1/W2 = 00/01/10/11 × 根路径 / 子目录 `/sub/`（本地反向代理）共 8 组；Chromium（Playwright 1.60） | `15-b05-site-check/summary.json`、`screenshots/` |

`b05_site_check.py` 每组检查（结果：8 组全部 0 项失败）：

- 33 个页面/入口的状态码、`no-store`、CSP、站名、Logo、站内链接前缀（子目录时全部以 `/sub/` 开头；数据中存的站内绝对路径按旧版原样输出，单独记录）、
  外部主机按开关出现（W1 关：`playerconfig.js`、CSP 中没有解析/预加载主机；W2 关：没有媒体/下载/外链 Logo 主机；**00 时所有响应与 CSP 中没有外部来源**；
  富文本中的普通超链接是导航目标，单独记录）；
- 播放/下载密码：错误密码 403 且不回显；正确密码 303 + `HttpOnly; SameSite=Lax` 签名票据，带票据访问 200；
- 浏览器：播放器内页在专用 CSP 下运行旧 `player.js`（`MacPlayer`、`player_aaaa` 存在，DPlayer 渲染），整轮没有 CSP 违规；外部请求只在对应开关打开时出现
  （00 与子目录 00 为空）；W2 关时外部播放地址显示固定提示而不输出地址；试看：时钟快进到 `data-trial-minutes` 之后，挂载区的媒体从 3 个变为 0，
  `#trial-message` 显示固定文案（浏览器计时边界，不是服务端授权屏障）；
- 根路径 W1/W2=11 另查：11 个页面在 320/360/768/1280 宽度横向溢出 0px（截图 16 张）；6 个页面键盘 Tab 第一个是"跳到主要内容"、可聚焦元素都有可见焦点；
  `prefers-reduced-motion: reduce` 时卡片过渡为 `0s`（no-preference 为 `0.12s`）。

观察（不属后端，留给前端）：页眉分类导航 `site-nav` 在 `main`（`a0876b8`）起就没有样式规则，窄屏下为默认列表（截图可见）；本批没有改 `static/site/`。

### 11. 与旧版的差异

| 方面 | 旧 PHP | 新 |
|---|---|---|
| 标签范围运算符 | ThinkPHP 全部表达式（含 `exp` 原样 SQL、`like`） | 只接受比较/区间/IN 白名单与整数值，其余拒绝 |
| 标签列表项数 | 不限 | ≤ 100；文本 ≤ 64 码点 |
| 标签包含匹配（`weekday`、文本 `rel`） | 无开关 | 受搜索开关（默认关）、并发许可与时限 |
| 可见范围 | `popedom_filter` 才过滤 | 始终只含可见分类（`type` 中不可见的项只是不命中；`typenot` 在可见分类内取差集） |
| SSR `isend` 等未用键 | 忽略 | 400（`ajax` 按 D-B05-6 接受并忽略） |
| 兼容模式 `s` | 任何路径上都改写路由 | 入口路径上指向本批页面时由 Java 承接；`s` 指向其他页面、或出现在搜索/筛选页路径上指向别处时交还 PHP（本地 404） |
| `vodsearch`/`vodshow` 位置参数 | `route_status=1` 时可用、生成器输出位置形式 | 只作为输入被认可，canonical 指向查询串形式；`vodshow` 多于 12 段不认 |
| `vod/show` 分类条件 | 由模板标签决定（通常 `type=current`） | 固定为分类本身 + 直接子分类（D-B05-7）；不接受 `tid` |
| 模块搜索 LIKE | 不转义 `_ %`（`wd=_` 命中全部） | 按字面值匹配（与视频相同） |
| 模块搜索 `by=rnd`、多值 `letter`/`level` | 支持 | 400（排序白名单不含 rnd；letter/level 沿用契约 1.2.0 单值规则） |
| 模块 `role/show` | 取其他模块的分类渲染 | 不提供（404） |
| 搜索频率限制、验证码（`search_timespan`、`search_verify`、`show_verify`） | 有 | 不提供（`show_verify=1` 时筛选页按关闭） |
| ajax 片段结构 | 模板 `*/ajax_*` | 与整页相同视图的结果片段（无外壳、无脚本）；仓库无旧模板，DOM 不对齐 |
| 手机模板、繁体语言包（D-B05-8） | `mob_status` 切换、zh-tw | 响应式单模板、简体固定文案；归 B12（MACC-36） |
| 试看 | 模板脚本计时 | `trial.js` 浏览器计时（不是服务端授权屏障，与旧版相同把地址交给浏览器） |
| SSR 二次解码、`htmlspecialchars` | 有 | 只解码一次（10 文档第 6 节，不变） |
| SSR 随机排序 | 每页可翻 | 不显示分页 |
| SSR 新键超长 | 按 `search_len` 截断 | 超出码点上限或链接预算 400 |
| 文章积分 | 按 `art_points_type` | 相同（取代 B03 的"任一列"） |

B03 已登记的差异（可见分类、`type_id=0`、密码字符串 `"0"` 视为有密码等）保留，B21 在授权的一次性合成数据中复核。

### 12. 回滚

不写库、无数据迁移、零 DDL；回滚即普通 `git revert` 本批提交（不改写历史）。回滚点：已验收 `main` `a0876b8`；本次运行前的分支状态 `5f14795`；
前端整合前 `af32178`（整合本身可单独 `git revert -m 1 d4262ea`）。运行时可逐项关闭：`MACCMS_SITE_ENABLED`、`MACCMS_SITE_SEARCH_ENABLED`（关闭时不注册对应路由），
`MACCMS_SITE_SHOW`、`MACCMS_SITE_AJAX_PAGE`（筛选页、ajax 入口 404；留空按旧配置，仓库值关闭），`MACCMS_BROWSER_EXTERNAL_W1/W2`，
未配置 `MACCMS_SITE_PASSWORD_SECRET` 时密码页只提示不支持在线验证。`MACCMS_CONTENT_ART_POINTS_TYPE` 留空按仓库值 1。
