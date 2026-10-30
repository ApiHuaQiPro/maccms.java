## B05 前台只读页补全：最终契约与旧参数覆盖表（路线图 B05，MACC-29）

> **状态：已实施，独立验收（固定交付 `f111945`）退回的 4 项已在返工中修正（第 13 节），待整批复验。** 本文件是 B05 的**最终契约**：第 2–7 节是实现说明与旧参数 → Java 内部入口 → 测试的覆盖表，第 9 节是生效的队长裁定，第 10 节是最终树验证方式，第 11 节是与旧版的差异，第 12 节是回滚。提交裁定时的推荐值、开工时的拟定方案等历史提议**已作废**，只在附录 A 中记录，不是契约。
> 未部署、未切流量。只读批次：不写库、不登记业务写入、零结构变更、不改 B02/B07 护栏，服务端不发起出站请求。
> 依据：`09-roadmap.md` 第 4.2 节（W1/W2）、第 5.3 节 B05 行；MACC-29 任务描述（含"补充验收""补充标签查询能力""补充 SSR 完整性范围""启动决定""恢复决定""独立验收返工决定"）；`14-video-list-filter-completion.md` 第 2、5.2 节（Q1/Q2/Q3）；`10-site-search-page.md`；`11-content-read-completion.md`。旧代码行号以当前仓库为准。
> 证据标注沿用 09 方案：**【代码】**仓库代码；**【黄金】**PHP 黄金样本实测；**【待核实】**依赖线上配置、线上模板或数据。
> 本文件只由后端维护；前端文档在 `docs/frontend/`（第 8 节）。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 分支 | `agent/claude/macc-29`（只用普通提交与普通 merge，没有 reset/rebase/amend/force） |
| 已验收 main（B04 合并） | `a0876b8595c800b3a87c9537a70e693335e475c6` |
| 原运行起点（平台基线） | `6dd12f50ca385fe801bacc5bdac6f7bb17acad5c` |
| 首次运行保存提交 | `b46009b78d3f2402567c354955da088016ca8b0c` |
| 契约稿提交 | `6f5e2afaae97e788feebbc3fb937d77565e38959` |
| 第二、三次运行保存提交（额度中断） | `5bc49c8c39a07b5f3cab4f066d9294cc795d85c4`、`5f1479530ea1ad3149246f942e1e4d3204fa1ef5` |
| 清理与补齐 | `cb938c4`（移除临时文件与误入的 worktree gitlink）、`955d767`（兼容模式 `s`、`ajax` 接受并忽略） |
| 前端整合 | `d4262ea`：普通 merge MACC-54 的固定提交 `31002393ee117943b88f9945866f315ed2fb955b`；**整合前**的提交是 `955d767` |
| 整合后 | `af32178`（Logo 按 W2 过滤）、`6da84e4`（`vod/show` 与位置参数）、`482fab2`（模块筛选/搜索页）、`f111945`（文档与真实 HTTP/浏览器检查） |
| 独立验收固定交付 | `f1119452f002a18e5d35f44c7247f6afbc12855d`（tree `71c89253b40d7ba5d23680df35c6f339b96b1509`）：退回返工，也是**本次返工运行的起点** |
| 返工提交 | `b628af0` 起（第 13 节），最终 SHA/tree 以 MACC-29 交付评论为准 |

### 1. 范围与分工

| 部分 | 内容 | 状态 | 负责 |
|---|---|---|---|
| A 内部标签查询能力 | 旧模板标签专属条件 `isend/timeadd/timehits/time/hits*/not/rel/typenot/type/tv/weekday/plot/start/level 多值`（第 2 节） | 已实现 | C |
| B SSR 搜索补全 | B04 新键、`tid`、`by=rnd`、逐键零值归一、兼容模式 `s`、`ajax` 接受并忽略（第 3 节） | 已实现 | C（K 定样式） |
| B' 位置参数路由与 `vod/show` | `vodsearch/<14 位>`、`vodshow/<12 位>`、`vod/show` 分类筛选页（第 3.6 节） | 已实现 | C |
| C 文章积分判定 | 按 `user.art_points_type` 取列（第 4 节） | 已实现 | C |
| D 内容 SSR 页 | 文章（含分页）、专题、演员、角色、网址、剧情的列表与详情（第 5 节） | 已实现 | C 页面模板与视图模型；K 样式（MACC-54） |
| E 播放 / 下载 / 密码 / 试看 | `vod/play`、`vod/player`、`vod/down`、`vod/downer`、`playerconfig.js`、`player_aaaa`、W1/W2 四种组合（第 6 节） | 已实现 | C；K 样式与试看脚本（MACC-54） |
| F 模块筛选/搜索页、`ajax_*` 与 `rss` | 各模块 `show`（含 `role/show`）/`search` 页、`ajax_detail/ajax_show/ajax_search` HTML 片段、单条 `rss`（第 7 节） | 已实现（返工补齐 `role/show` 与旧 URL 条件/排序） | C |
| G 手机模板与语言包 | `mob_status` 手机模板切换、`zh-cn`/`zh-tw` 输出 | 不在本批：D-B05-8 归 B12（MACC-36），本批登记差异；B12 已接线（选择语义、手机 Logo、`Vary: User-Agent`、`lang()` 文字按语言包，`21-b12-admin-framework.md` 第 12 节） | C（B12）；外壳可选的手机样式类由队长与 K 另定 |

共享文件（`src/main/jte/site/components/`、`src/main/resources/static/site/`、`jte/site/layout/` 与组件 `@param` 签名）：后端没有修改；`static/site/` 的变更全部来自 MACC-54 的固定提交（普通 merge）。新页面沿用既有 class（`filter-group`/`filter-chip`、`content-grid`/`content-card`、`pagination`、`search-random-notice`）。

### 2. 内部标签查询能力（已实现）

#### 2.1 入口与护栏

- 入口：`content.application.VideoLegacyConditions`（不可变 record + builder），作为 `VideoListQuery.legacy` 交给 `ContentReadService.listVideos`。公开 API 的 `VideoListQuery.parse` 一律为 `VideoLegacyConditions.NONE`，所以**不是新的前台 URL 输入**；API 对 `isend=1`、`timeadd=…` 等参数名仍按未声明参数忽略（B04 已有测试）。
- 旧标签属性字符串 → 条件的换算：`LegacyTagAttributes`（`type/typenot/not/level/rel/isend/plot/start/tv`）、`LegacyRange.parseLegacy`（点击数范围）、`PhpIntval`（PHP `intval`）。新版不解析旧模板语法（模板来源 D9 未定），这些换算只把旧属性的语义固定下来，供页面与将来的模板转换给出等价条件。
- SQL：`VodMapper.xml` 的 `legacyConditions` 片段追加在 1.3.0 条件之后，count 与分页 `<include>` 同一片段；列名与运算符写死在 XML（点击数范围按 `LegacyRange.Op` 枚举名 `<choose>`），值只经 `#{}`；`legacy == null` 时 SQL 文本与 1.3.0 逐字相同。
- 成本护栏：包含匹配（`weekday`、文本形式的 `rel`）与 `keyword` 等共用搜索开关（默认关，关闭时 400）、并发许可（满时 503）与 `MAX_EXECUTION_TIME(3000)`；所有列表项数 ≤ 100（`MAX_ITEMS`），文本 ≤ 64 码点，`start` ≤ 100000；始终限定在可见分类内（1.0.0 起的可见性不放宽）。中规模实测见 `15-b05-perf-report.md` 第 10 节（经实际服务与 Mapper 执行，不是照抄 XML）。

#### 2.2 旧属性 → Java 内部入口 → 测试

旧代码均为 `application/common/model/Vod.php`（`listCacheData`，:108-567）。"黄金"列是 `src/test/resources/content/vodtag/php-golden.json`（PHP 8.0.30）与 `php73-golden.json`（PHP 7.3.33）中的用例前缀，由 `src/test/php/vod_tag_golden.php` 在一次性库上调用旧 `listCacheData` 生成，`VideoTagGoldenMySqlIT` 在真实 MySQL 上逐条比对 ID 序列与 total（两份共 186 × 2 个用例；旧代码报错的用例断言新版构造时拒绝）。

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

### 3. SSR 搜索补全（已实现）

#### 3.1 查询串键（`LegacyUrlParser.parseSearch` / `parseVodList`）

旧 `mac_param_url`（`application/common.php:1560-1606`）读出的键逐项核定如下。

| 旧键 | 旧读取方式 | 旧在 `vod/search` 数据中的作用 | Java（最终） |
|---|---|---|---|
| `wd area lang year class level by order page` | 见 14 文档 2.2 | 条件 | 既有（MACC-20） |
| `actor director tag letter state version name ids` | 同上 | 条件（`Vod.php:174-247`） | 承接，交给 `VideoFilter` 同一套规则 |
| `tid` | `intval` | `type_id = tid OR type_id_1 = tid` | 承接（第 3.5 节） |
| `by=rnd` | 白名单 | 随机取页（`Vod.php:500-537`） | 承接（第 3.4 节） |
| `s` | ThinkPHP 兼容模式（`thinkphp/library/think/Request.php:403-406`），改变路由而不是条件 | — | 入口路径（`/index.php?s=…`）上 `s` 解码后是搜索页路径时由 Java 按搜索页同一规则承接（参数不合法时 400 搜索错误页）；指向本批其他页面时交给承接该页面的 Controller；指向其他页面、或出现在搜索/筛选页路径上时交还 PHP（本地 404）（`955d767`） |
| `ajax` | `intval` | 不进 `listCacheData`；只供模板使用（仓库无模板）【代码】 | D-B05-6：接受并忽略——仍检查重复键与编码，不影响查询、不进链接/canonical/隐藏字段 |
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

只有归一后仍有条件才查库；全部归一为空时显示无条件页（不查库），与 MACC-20 相同。模块筛选/搜索页的逐键归一见第 7.3 节（各模块读的键不同，不套用视频的规则）。

#### 3.3 筛选区、隐藏字段与链接键序（与前端 K1 已定）

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

  候选值去重、保持出现顺序、每组最多 30 个，跳过不能回读的值（`''`、`'0'`、首尾空白、不满足 content 规则、超出链接预算）。点候选值换成该单值并回到第 1 页，其余条件保留。随机排序时输出 `p.search-random-notice.notice`、不输出分页。样式由 MACC-54（K1）实施。

#### 3.4 随机排序（`by=rnd`）

按契约 1.3.0 的同一算法（`VideoListQuery.random`，14 文档第 4 节）：`sort` 固定 `hits_week desc`；算法一（计数 ≤ 2000）以随机页覆盖请求页（响应的页码可能大于请求的），算法二（计数 > 2000）用全表 MIN/MAX 取 1000 个候选下标、用请求的页。页面**不显示分页**（每次取页不同），越界不判 404（结果为空时显示"没有找到相关视频"，200）；"随机"作为排序组的当前项；换成其他排序时去掉 `rnd`。随机源 `VideoRandom` 可替换，集成测试按 PHP 黄金样本给出随机页/候选。【测试】`SiteSearchB05PagesMvcTest.randomSort*`、`VideoListGoldenMySqlIT` 的 `rnd.*`（阈值 2000/2001、稀疏 ID、候选全不命中）、`ModuleSearchGoldenMySqlIT` 的 `plot.search.by=rnd*`（经剧情页的同一路径，含阈值两侧）。

#### 3.5 `tid`（队长裁定 Q2）

- 内部条件 `(type_id = ? OR type_id_1 = ?)`，叠加已接受的可见分类与发布状态限制（结果只含可见分类中的行），**不映射为 `categoryId`**，公开 API 不加别名。
- 与旧版的覆盖关系：旧 `$where['type_id']` 来自 `type`/`typenot`（标签），`tid` 写入的是独立的 `$where['type_id|type_id_1']`（`Vod.php:356-358`），所以与 `categoryId`、`types` 同时存在时取 AND（`VideoTagGoldenMySqlIT` 的 `tid.current-*` 用例）。
- 冗余列不一致（`type_id_1` 与分类表的上级不同）、停用分类、多级分类：按旧 OR 条件比较 ID 集合，`VideoTagGoldenMySqlIT.ssrSearchTidMatchesLegacyAcrossFollowedPages` 以真实 HTTP 沿"下一页"链接取完所有页，ID 序列与 total 与旧版一致。

#### 3.6 位置参数路由与 `vod/show`（D-B05-7）

`LegacyUrlParser.parseVodList` 统一承接 `vod/search`（固定路径 + 查询串、兼容模式 `s`）、`vod/ajax_search`、`vodsearch/<14 位>`、`vod/show/id/<分类>[/键/值…]`、`vod/ajax_show/…` 与 `vodshow/<12 位>`。旧路由（`application/route.php:321,331`）的位置顺序见 14 文档第 2.3 节（`LegacyVodUrlMappingTest` 锁定两份黄金样本的逐位映射）。

- 位置/路径形式先按 PATH_INFO 解码一次，按 `-` 切分（多出的段并入第 1 位，与旧版 `wd=a-b` 相同），空段为未传；查询串覆盖同名值（旧 `array_merge($input, $_REQUEST)`），之后与搜索页同一套归一与校验。位置参数地址只作为**输入**被认可，**canonical 指向查询串形式**（Java 只生成一种链接，不 301）；不依赖路由别名配置（B02 对 `route_status` 为真仍启动失败）。年份区间不能写在位置参数地址中（旧版同样不行）。
- `vod/show` 是独立入口 `SiteRoute.VodShow`：第 1 槽为分类，条件为"分类本身 + 直接子分类"（即 API `categoryId` 的语义，**不是** `tid` 的冗余列 OR），叠加可见性/发布约束与旧 `app.show` 开关（`SiteFeatures`，仓库值关闭）；不接受 `tid`（400）；规范地址 `vod/show/id/<分类>.html?<条件>`（`LegacyUrlBuilder.vodShowLink`），筛选、排序、分页、表单都保留分类；`vod/search` 不新增 `type` 键，API `categoryId` 不变。`vodshow` 多于 12 段、分类缺失/前导 0/越界不认（404）。
- 【测试】`LegacyUrlParserVodListTest`（PHP 8.0/7.3 黄金 `urls[vodsearch.*]`、`urls[vodshow.*]` 共 58 条逐条与查询串形式对照、路径参数、四种 URL 形态往返）、`SiteVodListPagesMvcTest`、`SiteVodListClosedMvcTest`，真实 HTTP 见第 10 节。

#### 3.7 链接预算

B05 的新键共用 MACC-20 的查询串预算 1209 字符（`LegacyUrlBuilder.MAX_SEARCH_LINK_QUERY`，含最长的 `page=1000`）；超出预算的条件组合在 `SearchFilters`/`ModuleFilters` 构造时拒绝，页面 400 且不回显输入，所以生成的任何链接都在 `SafeUrl` 上限内。【测试】`combinationsBeyondTheLinkBudgetAre400`、`overBudgetCombinationIs400WithoutEcho`、`randomConditionsWithNewKeysRoundTrip`、`LegacyUrlParserModuleListTest.generatedLinksParseBackToTheSameConditionsInEverySiteStyle`（固定种子随机条件 × 四种形态的生成与回读不变式）。

### 4. 文章积分判定（已实现）

- 旧：`check_user_popedom`（`application/index/controller/Base.php:108-112`）中 `user.art_points_type == '1'` 时取 `art_points`（整篇），否则取 `art_points_detail`（按页）；游客（`group_id < 3`）且积分 > 0 时显示扣积分确认页（:123-140）；`user.status=0` 时不检查（:115-117）。
- 新：`maccms.content.art-points-type` / `MACCMS_CONTENT_ART_POINTS_TYPE`，只接受 `0`/`1`，留空按仓库值 `1`（`application/extra/maccms.php:122`）；其他值启动失败且不回显。积分列仍只以 SQL 布尔值读出（`art_points > 0`、`art_points_detail > 0`），值不入内存。
- 判定顺序与错误码不变：404 `ARTICLE_NOT_FOUND`（不存在/未审核/分类不可见/无详情权限）→ 403 `ARTICLE_POINTS_REQUIRED` → 403 `ARTICLE_PASSWORD_REQUIRED`。【测试】`ArticlePointsTypeTest`（类型 × 两列组合 × 密码的判定顺序、`user.status=0`、默认值、非法值不回显）、`LibraryMySqlIT`。
- 取代 B03 暂时的"两列任一大于 0 即拒绝"（11 文档第 5 节差异 #6 已更新）。部署时按旧 `user.art_points_type` 设置该环境变量【待核实线上值】。

### 5. 内容 SSR 页（已实现）

#### 5.1 页面与路由

全部只认旧默认 URL 形态（`/index.php/<模块>/<动作>/id/<n>[/page/<n>].html` 及 `rewrite`/`suffix_hide` 变体，与三页相同的解析规则：数字 id、无前导 0、不认查询串、非规范形态只输出 canonical）。别名、`-` 分隔符、拼音/编码 id 仍不支持（B02 启动检查不变）。

| 页面 | 旧入口 | 数据（已发布服务，契约 1.2.0） | 404 / 403 |
|---|---|---|---|
| 文章分类列表 | `art/type`（`Art.php:17-21`） | `ArticleReadService` 列表（categoryId，time desc，20） | 分类不可见 404 |
| 文章详情 | `art/detail`（`:52-60`）+ `page` | `ArticleReadService` 详情（一次返回全部分页，SSR 只渲染当前页） | 404；积分 403、密码 403（第 4、6.4 节） |
| 专题首页 / 详情 | `topic/index`、`topic/detail` | `TopicReadService` | 404 |
| 演员首页 / 分类 / 详情 | `actor/index`、`actor/type`、`actor/detail` | `ActorReadService` | 404 |
| 角色首页 / 详情、视频角色页 | `role/index`、`role/detail`、`vod/role` | `RoleReadService` | 404 |
| 网址分类 / 详情 | `website/type`、`website/detail` | `WebsiteReadService` | 404 |
| 分集剧情 | `vod/plot`（`vodplot/<id>-<page?>`）、`plot/detail` | `VideoPlotReadService` | 与视频详情相同；密码/版权按 6.4 |
| 各模块筛选页、搜索页与 `ajax_*`、单条 `rss` | 见第 7 节 | | |

实现：`SiteLibraryController` + `LibraryPages`（列表、详情、单条 RSS、`ajax_detail`）。【测试】`SiteLibraryPagesMvcTest`、`SiteAjaxFragmentsMvcTest`，真实 HTTP 与浏览器见第 10 节。

#### 5.2 文章分页（补充验收）

- 旧：`mac_label_art_detail` 取 `page` 参数（`intval`，<1 当 1），越界取最后一页（`application/common.php:2708`）；`mac_page_param($info['art_page_total'], 1, page, url)` 生成分页（`application/common/controller/All.php:373-376`）。
- 新：页面只渲染当前页的标题、备注与正文（`ArticleContentPageDto` 的第 page 项），不把全部正文同时输出；分页复用 `pagination`（上一页/下一页 + "第 n / N 页"）。规范地址：第 1 页为不带 `page` 的地址，第 n 页为 `/page/n`；越界（page > 总页数）与旧版相同显示最后一页（200），canonical 指向最后一页。`page=0`/前导 0/非数字按三页的解析规则为 404（与 MACC-14 一致的差异）。剧情分集的 `page` 同样越界取最后一集。
- 【测试】首/中/末页、越界、长文章（页数上限 1000）、只有一页时不显示分页、分页链接往返（`SiteLibraryPagesMvcTest`）。

#### 5.3 富文本与文本字段（补充验收）

- 原始不可信 HTML：`art_content`（每页）、`topic_content`、`actor_content`、`role_content`、`website_content`、剧情 `detail`，一律经 `RichTextSanitizer` 得到 `SafeHtml`，只放在元素内容位置（07 文档、MACC-14 规则）；缓存键包含类型、id、页码、更新时间、原文长度与哈希（`RichTextCache`）。
- 文本字段（名称、副标题、备注、标签、演员等）经 `LegacyTextEntities.decodeOnce` 解码一次后由 jte 转义。
- 【测试】脚本、事件属性、`javascript:`/`data:` 等危险 URL、`style`、合法富文本（段落、列表、链接、图片、表格）保留；每种内容类型至少一组（`SiteRichTextTemplateTest`、`SiteLibraryPagesMvcTest`）。

#### 5.4 视图模型与 class

页面模板在 `jte/site/pages/`（后端），视图模型在 `site.web.view`（不可变 record）。按 K2 的分工结论：**不新增 `contentCard` 组件**，内容卡片由页面内联 `content-grid`/`content-card` 并复用 `pagination`；页面根元素的语义 class 为 `article-page`、`topic-page`、`actor-page`、`role-page`、`website-page`、`plot-page`，正文容器沿用 `detail-content`，截图列表 `screenshot-list`。样式由 MACC-54 在 `site.css` 实施。

### 6. 播放 / 下载 / 密码 / 试看（已实现，D-B05-1～4）

#### 6.1 旧行为（【代码】`application/index/controller/Vod.php:81-123`、`application/common/controller/All.php:442-540`、`Base.php:92-231`、`Ajax.php:400-458`）

| 入口 | 判定顺序 | 输出 |
|---|---|---|
| `vod/play`（`vodplay/<id>-<sid>-<nid>`） | 视频存在且审核（否则错误页）→ 试看时长 = `vod_trysee > 0 ? vod_trysee : user.trysee` → `check_user_popedom(type, 3)`：无播放权限且（无试看权限或时长为 0）→ 无权限；游客、无播放权限、有试看权限、时长 > 0 → 试看（3002）；游客且积分 > 0 → 扣积分确认页（3003）→ 组 `player_aaaa` → 试看、播放密码未验证或 `vod_copyright=1 && copyright_status=4` 时只输出指向 `vod/player` 的 iframe，否则内联 `player_aaaa` + `playerconfig.js` + `player.js` → `vod_copyright=1 && copyright_status=3` 时显示版权页 | 播放页 |
| `vod/player`（iframe 内页） | 同上取数据，权限检查 `check_user_popedom(type, 5)` 不中止 → `copyright_status=4` 版权页 → `vod_pwd_play` 非空且 session `1-4-<id>` 未验证 → 播放密码页 → 播放器页 | 播放器 |
| `vod/down` / `vod/downer` | `check_user_popedom(type, 4)`：无下载权限 → 错误；游客且积分 > 0 → 4001 → 下载页；`downer` 另判 `vod_pwd_down` 与 session `1-5-<id>`；不检查版权 | 下载页 / 下载地址页 |
| `ajax/pwd`（POST/GET 均可） | `mid∈{1,2}`、`type∈{1,4,5}`、id 与 pwd 非空 → session 已验证 → 5 秒频率限制 → 按 id 取视频/文章（**不检查审核状态**）→ `!=` 宽松比较密码 → 写 session | JSON |

`player_aaaa` 字段（:476-530）：`flag`、`encrypt`（`app.encrypt` 0/1/2：原样 / `escape` / base64）、`trysee`、`points`、`link`、`link_next`、`link_pre`、`vod_data`、`url`、`url_next`（`upload` 开头时加站点根路径）、`from`、`server`、`note`、`id`、`sid`、`nid`。试看的计时与结束由旧模板脚本实现（仓库无模板，`static/js/player.js` 不读 `trysee`）。

#### 6.2 页面结构与策略

- **播放页 `vod/play`**：站点外壳、严格策略（`script-src 'none'`，与三页相同）。面包屑、标题、当前集、播放源分组与剧集列表（`LegacyMultiValue.playList`，按 `app.player_sort` 排序与启用状态过滤）、上一集/下一集链接，以及指向同源播放器内页的 `<iframe>`。剧集链接、iframe 地址都是 `SafeUrl`；**播放页本身不含播放地址**。
- **播放器内页 `vod/player`**：专用策略 `PlayerFramePolicy`（D-B05-1），只限这一类页面：`script-src 'self' 'nonce-<每个响应 128 位随机>'`（不加 `unsafe-inline`、不用 `strict-dynamic`，旧 `player.js` 用 `document.write` 写出的同源 `static/player/<来源>.js` 靠 `'self'` 放行），`style-src 'self' 'unsafe-inline'`（只对样式放宽）；W1 打开时只加入本页实际用到的解析地址、`prestrain`/`buffer` 的来源（`frame-src`），来源为 `iva` 时加入 `*.videojj.com`（既定功能例外）；W2 打开时只加入本集外部地址的来源（`frame-src`/`media-src`/`connect-src`/`img-src`），**从不加入 `script-src`**。`player_aaaa` 只经安全 JSON 序列化（`PhpJson`，`<`、`>`、`&`、U+2028/2029 转义）放进带 nonce 的脚本；`static/js/player.js`、`static/player/*`（含 `iva`）原样复用。试看挂点 `div#player-stage.player-stage[data-trial-minutes]` 与结束提示 `p#trial-message.trial-message[role=status]`，脚本 `/static/site/trial.js`（MACC-54）。
- **下载页 `vod/down`**（严格策略）：分组与条目列表；**下载地址页 `vod/downer`**：地址以普通链接输出（`SafeUrl`，W2 关闭时外部地址不输出）。
- **`playerconfig.js`**：旧路径 `/static/js/playerconfig.js`（`PlayerConfigController`），内容在启动时由旧配置（`vodplayer.php`、`voddowner.php`、`vodserver.php`、`maccms.php` 的 `play.*`，B02 读取）生成；W1 打开时与旧后台生成的文件逐字节相同（`PlayerSettingsGoldenTest`），关闭时不含外部解析、缓冲与预加载地址；没有旧配置时 404；不读写该文件（写回归 B15）。

#### 6.3 W1 / W2 四种组合（路线图 4.2 节）

W1 与 W2 **互相独立**，各自只消除自己负责的外部地址：

| 组合 | 播放器配置与 `player_aaaa` | 外部播放/下载地址与外链图片 | CSP 与响应 |
|---|---|---|---|
| W1 开、W2 开（生产默认，按旧版） | `parse`、`prestrain`、`buffer` 按旧版输出，`ps=1` 的来源由浏览器用解析地址打开，`iva` 加载外部脚本；与 PHP 黄金样本逐字段一致 | 按旧版输出 | 只放行本页实际用到的来源 |
| W1 关、W2 开 | 外部解析地址、外部 `prestrain`/`buffer` 不输出（置空）；`ps=1` 的来源与 `iva` 在播放器内页显示固定提示，不加载 iframe/外部脚本 | 照常输出 | 没有解析/预加载/iva 主机 |
| W1 开、W2 关 | 照常 | `url`/`url_next` 中的外部地址为空串，播放器内页、下载地址页显示固定提示；外部 Logo/图片不输出（本地占位） | 没有媒体/下载/外链图片主机 |
| W1 关、W2 关 | 同 W1 关 | 同 W2 关 | **页面、`playerconfig.js` 与 CSP 中没有任何外部来源** |

固定文案（队长定稿）：W1 关闭"该播放源需要外部解析服务，本站当前未开放。"；W2 关闭"该地址位于外部站点，本站当前未开放外部播放。"/"该地址位于外部站点，本站当前未开放外部下载。"。"外部"按 `maccms.site.base-url` 的主机判定（B02 规则）。两项验收分别进行：页面自身静态资源检查（引用的 `static/` 资源都存在且同源）与参数四态检查（第 10 节，根路径与子目录各 4 组）。

#### 6.4 授权、密码、版权与试看（游客）

| 情况 | 旧 | 新（最终） |
|---|---|---|
| 视频不存在/未审核/分类不可见，`sid`/`nid` 不在分组中 | 错误页 | 404 |
| 游客无播放（位 3）/下载（位 4）权限且不能试看 | 错误跳 `user/index` | 通过存在性/发布/可见性检查后 **403 固定提示页**、`no-store`（D-B05-2）；不输出受限地址或内部字段，不声称登录一定能解决 |
| 需积分（`vod_points_play`/`vod_points_down`/`vod_points`，按 `user.vod_points_type` 取列） | 扣积分确认页 | 403 固定提示页、`no-store`，不显示积分数（购买归 B10） |
| 试看（游客有位 5、无位 3、时长 > 0） | 播放器页照常给出地址，由模板计时 | 播放器内页照常输出 `player_aaaa` 与 `data-trial-minutes`（分钟，0 不计时），`trial.js` 到时停止本地媒体并移除挂载内容；这是浏览器计时边界，**不是服务端授权屏障**，不绕过密码/版权检查 |
| 播放/下载密码（`vod_pwd_play`/`vod_pwd_down`）、文章密码 | 密码页 → `ajax/pwd` 写 session | 密码页（GET，无脚本表单）→ **POST** 到同一地址；密码只在 SQL 中比较（`BINARY` 逐字节，值不读入内存）；通过后 303 并发放**签名票据 cookie**（`PasswordTickets`，D-B05-3）：`v1.<到期>.<凭据>.<HMAC>`，绑定用途（播放/下载/文章）与内容 id，凭据是当前密码的加盐摘要（改密码后旧票据立即失效），**服务端有效期 2 小时**（`Max-Age` 同值，过期以服务端为准）、`HttpOnly`、`SameSite=Lax`、`Path` 为站点根、HTTPS 时 `Secure`；部署密钥只来自环境变量 `MACCMS_SITE_PASSWORD_SECRET`（32–256 个可打印 ASCII 字符），未配置时密码页保持 403 并说明"暂不支持在线验证" |
| 密码校验频率 | session 5 秒 | 进程内固定窗口（`PasswordAttempts`）：同一"用途 + 内容"每 60 秒 10 次、全部合计每秒 30 次，超限 429、不查库；不记录 IP、会话或密码 |
| 版权（`vod_copyright=1`） | `copyright_status=3` 播放页、`=4` 播放器内页显示版权页 | 只在实际命中的入口 **403 固定提示页**、`no-store`（D-B05-4）：3 对 `vod/play`、4 对 `vod/player`；下载入口与旧版一样不检查版权；既有公开详情/API 的状态码不变 |
| `vod/player` 内页的权限 | 只做不中止的检查（会把地址交给无权限游客） | 按播放页同样判定，不输出受限地址（差异，第 11 节） |

公开元数据不变：`VideoDetailDto` 等 API 不新增播放/下载地址、密码、积分字段；`player_aaaa` 只出现在播放器内页，且只在权限、密码、版权检查都通过（或试看）之后。【测试】`PlaybackGoldenMySqlIT`（PHP 播放黄金：权限/积分/试看/密码/版权判定与 `player_aaaa` 逐字段）、`SitePlaybackPagesMvcTest`、`PlaybackPrimitivesTest`（签名票据、限流、专用 CSP），浏览器见第 10 节。

### 7. 模块筛选/搜索页、`ajax_*` 与 `rss`（已实现，D-B05-5/6；返工第 1、2 项）

#### 7.1 `ajax_*` 与 `rss`

- 旧 `*/ajax_detail`、`*/ajax_show`、`*/ajax_search` 返回模板片段（`label_fetch('<模块>/ajax_*')`），受 `app.ajax_page`（`Base.php:45-51`）。新版按 D-B05-5 **全部实现**为与整页同一视图模型、同一过滤的 HTML 片段（无外壳、无脚本、无表单，`no-store`），片段中的分页链接指向完整页面；仓库没有旧模板，DOM 结构不对齐（差异）。路线图第 5.3 节 B05 行的"JSON"已更正为 HTML 片段。
- 单条 RSS：`vod/rss`、`art/rss`、`topic/rss`、`actor/rss`、`role/rss`、`website/rss`、`plot/rss` 输出 `application/rss+xml`（UTF-8），一个 `channel` + 一个 `item`（标题、链接、简介纯文本、`pubDate`），文本做 XML 转义，正文不输出（`RssDocument`）。站点级 `rss/index`、`rss/baidu` 等归 B06。

#### 7.2 入口、开关与链接

| 旧入口（`Base.php` 开关） | Java 入口 | 查询 |
|---|---|---|
| `vod/ajax_search`、`vod/ajax_show` | `SiteSearchController`、`SiteVodShowController`，片段 `site/ajax/videoList` | 与 `vod/search`、`vod/show` 相同（第 3 节） |
| `art/search`、`art/show`、`ajax_*`（`Art.php:23-53`） | `SiteModuleListController` → `ModuleListPages` → `LibrarySearchService.articles(LibraryListQuery)` | 第 7.3 节 |
| `topic/search`、`ajax_search`（`Topic.php:18-33`） | 同上 → `topics` | 同上 |
| `actor/search`、`actor/show`、`ajax_*`（`Actor.php:18-60`） | 同上 → `actors`；未给分类的筛选页取第一个可见演员分类（`Actor.php:24-35`） | 同上 |
| `role/search`、`role/show`、`ajax_*`（`Role.php:18-48`） | 同上 → `roles`；**`role/show` 没有分类** | 同上 |
| `website/search`、`website/show`、`ajax_*`（`Website.php:23-53`） | 同上 → `websites` | 同上 |
| `plot/search`、`ajax_search`（`Plot.php:20-35`） | 同上 → `ContentReadService.listVideos` + 旧 `plot=1` | 经旧视频标签链，与 `vod/search` 同一组键（第 7.3 节） |
| `topic/show`、`plot/show` | — | 旧控制器没有该操作，404 |

- **`role/show`、`role/ajax_show`**（返工第 1 项）：旧 `Role::show` 先 `check_show`，再调用 `label_role()`（`All.php:236-245`：`mac_param_url` → `mac_filter_words` → `mac_search_len_check` → `assign('param')`），**没有 `label_type`**；模板中的角色列表以 `paging=yes` 调 `Role::listCacheData`，从 URL 读 `rid/ids/level/letter/wd/by/order`。新版：`ModuleListPage.ROLE_SHOW`/`ROLE_AJAX_SHOW`，受 `app.show`（ajax 另需 `app.ajax_page`），**不依赖分类**；无条件时列出全部已审核角色（筛选页无条件也查询，与搜索页不同）；规范地址 `role/show.html?<条件>`，也接受 `role/show/<键>/<值>….html` 路径参数形式（只作输入）；`id` 不是角色的键（400）。
- **开关**：搜索页随 `maccms.site.search.enabled`（旧 `app.search`）；筛选页随旧 `app.show`（`SiteFeatures.show`，仓库值关闭）；ajax 入口另需旧 `app.ajax_page`（仓库值关闭）。关闭时 404，不查库。验证码见第 7.4 节。
- **成本护栏**：包含匹配（`wd/name/tag/class`）经 `ContentReadService.guardedRead` 与视频列表共用 `maccms.content.search.enabled`（关 → 查库前 400）、并发许可（满 → 503）与 Mapper 的 `MAX_EXECUTION_TIME(3000)`；每页 20 条，越界页 404（随机排序除外）。公开 API 不新增参数（`ContentListQuery`/`ContentFilter` 不变；SSR 条件只在 `LibraryListQuery`/`LibraryMatch`）。
- **链接**：规范地址 `<模块>/search.html?<条件>`、`<模块>/show/id/<分类>.html?<条件>`、`role/show.html?<条件>`（`LegacyUrlBuilder.moduleListLink`，键按 mac_url 排序、PHP urlencode；ajax 入口的分页链接指向完整页面）；查询串白名单 `SiteQueries` 另加 `sex/starsign/blood/rid/id`。筛选组：首字母、已设置的其他条件（"全部 + 当前值"）、排序（时间/人气/评分；其余 URL 可达的旧排序只作为当前项出现，如"随机""名单顺序""周来路"）；随机排序不显示分页，输出 `search-random-notice`。

#### 7.3 旧参数 → 归一 → 模型条件/排序 → Java 内部入口 → 黄金测试（返工第 2 项）

逐模块按旧调用链核定，不套用视频或公开 API 1.2.0 的单值规则。读取链：URL → `mac_param_url`（`common.php:1560-1606`）→ 模型 `listCacheData` 的 `paging=yes` 分支（只在非空时覆盖）→ `where`/`order`。"黄金"列是 `src/test/resources/site/module-search/php-golden.json` 的用例 id 前缀（第 7.5 节）。

| 键 | 旧链（逐键） | 适用模块 | 旧条件 | Java 内部入口 | 黄金用例（举例） |
|---|---|---|---|---|---|
| `wd` | `htmlspecialchars(urldecode(trim))` → `!empty` | 全部 | 文章 `art_name`（+`search_art_rule` 仓库值 `art_en|art_sub`）、专题 `name|en|sub`、演员/角色/网址 `name|en` 的 `LIKE %wd%` | `LibraryMatch.keyword` → `keywordPatterns`（`ESCAPE '!'`，含 htmlspecialchars 变体）；受开关/许可/时限 | `*.search.wd=*`、`role.show.wd=*` |
| `name` | 同上 | 文章、网址 | 文章 `mac_like_arr` 的 LIKE OR；网址 `website_name IN explode` | `namePatterns` / `names` | `art.search.name=*`、`website.search.name=*` |
| `tag`、`class` | 同上 | 文章、专题（`class`→`topic_type`）、网址 | `mac_like_arr` 的 LIKE OR，空项 `%%` | `tagPatterns`、`classPatterns` | `art.search.tag=*`、`topic.search.class=*` |
| `ids` | 同上；`all` 不筛选 | 全部 | `<pk> IN explode`（空项按 0） | `LibraryMatch.ids` → `ids` | `*.search.ids=*` |
| `id` | 专题 paging 分支 `ids = intval($param['id'])`，`ids` 设置时被覆盖 | 专题 | `topic_id IN (intval)` | `LibraryMatch.id`（intval 非 0，负数照常比较） | `topic.search.id=2`、`id=2&ids=3`、`id=-2`、`id=abc&letter=X` |
| `tid` | `mac_param_url` 的 `intval` | 文章 | `type_id|type_id_1 = tid` | `LibraryMatch.tid` → `(type_id = ? OR type_id_1 = ?)` | `art.search.tid=21/25/-21/21abc` |
| `rid` | `intval` | 角色 | `role_rid = rid` | `LibraryMatch.rid` → `videoId`（负数照常比较） | `role.search.rid=101/-1/101abc` |
| `level` | **`mac_param_url` 先 `intval`**（`common.php:1577`），模型中再 `explode` | 全部 | `<表>_level IN (intval)`：URL 只有单值；`1,2`→1、`2abc`→2、`all`/`0`/`abc`→未设置；负数、越界照常比较（无结果） | `LibraryMatch.level` → `legacyLevel`（任意非 0 整数，不套 1..9） | `art.search.level=1%2C2`、`level=all&wd=*`、`level=-1&wd=*`、`level=10&wd=*`、`art.show25.level=all` |
| `letter` | `htmlspecialchars(trim)`（不 urldecode）→ `!empty` | 全部 | 首字节 `0` 且第 3 字节 `9` 时为 0..9，否则 `IN explode`（保留空项；全角逗号不是分隔符） | `LibraryMatch.letter` → `VideoFilter.letters()` | `art.search.letter=X%2CC`、`C%2C`、`0-9`、`0%2C9`、`X%EF%BC%8CC`、`x` |
| `sex` | `htmlspecialchars(urldecode(trim))` | 演员 | `actor_sex = sex`（不拆分） | `LibraryMatch.sex` | `actor.search.sex=*` |
| `area` | 同上 | 演员、网址 | `IN explode`（保留空项） | `LibraryMatch.area` → `areas` | `actor.search.area=*`、`website.search.area=*` |
| `lang` | 同上 | 网址 | `IN explode` | `langs` | `website.search.lang=*` |
| `starsign`、`blood` | 同上 | 演员 | `IN explode` | `starSigns`、`bloods` | `actor.search.starsign=*`、`blood=A%2C` |
| `by` | `htmlspecialchars(urldecode(trim))`；不在白名单时旧版回落 `time` | 全部 | 白名单：通用 `id time time_add score hits hits_day hits_week hits_month up down level rnd`；演员另有 `in`（`name` 只来自模板，URL 下等同 `time`）；网址另有 `in`（有 `name` 时 `FIND_IN_SET(website_name, name)` 升序、忽略 `order`，否则 `time`）与 `referer referer_day referer_week referer_month` | `LibraryListQuery.of`：排序列 `SortColumn`（网址按名单为 `NAME_ORDER`）；不在该模型白名单中 → 400（差异） | `*.by=score`、`by=hits_week&order=asc`、`actor.search.by=in*`、`website.search.by=in*`、`by=referer*` |
| `by=rnd` | 同上 | 全部 | `countData` → 随机页 `mt_rand(1, floor(c/num)+1)`（忽略请求页）→ `hits_week desc` 取该页；**只有这一种算法** | `LibrarySearchService`：`VideoRandom.pageBetweenOneAnd(c/20+1)`，追加主键倒序 | `art.search.by=rnd*`、`role.search.by=rnd&letter=R`（c=40，随机页 2/3，第 3 页为空）、`letter=R%2CS`（c=45）、`letter=Q`（c=0）、`role.show.by=rnd*` |
| `order` | 同上；非 `asc/desc` 时旧版回落 `desc` | 全部 | 方向 | `LibraryListQuery.of`（非 asc/desc → 400，差异） | `*.order=asc` |
| `page` | `intval`，<1 当 1 | 全部 | 页码 | 1..1000、无前导 0（既有） | 多页用例沿"下一页"取完 |
| 剧情的全部键 | `Plot::search` → 模板标签 `plot=1` → `Vod::listCacheData` 的 paging 分支（`Vod.php:167-247`） | 剧情 | 与 `vod/search` 同一组键：`wd area lang year class level by order actor director tag letter state version name ids tid`（`isend` 经 `mac_param_url` 读不到，400）；`by=rnd` 两种算法与 2000 阈值 | `ModuleFilters.searchFilters()` → `SearchFilters`（视频搜索页同一套归一与校验）+ `VideoLegacyConditions.plot(1)`（+ `tid`） | `plot.search.tid=1`、`name=*`、`letter=X%2CH`、`by=hits*`、`by=rnd*`（含 `plot-bulk` 阶段 c=2000 算法一、c=2001 算法二候选重放、请求页 2、c=0） |

成本护栏不因这些条件放宽：所有文本值经 `VideoFilter.text` 的同一规则（禁用字符、首尾空白、码点上限：`letter/sex/starsign/blood/name/tag` 64、`area` 20、`lang` 10、`wd` 64、`class` 32），逗号多值项数 ≤ 100（实际受码点上限约束），链接预算 1209；包含匹配受开关、并发许可、时限与 503；模块条件只追加在各 Mapper 的 `criteriaConditions`/`publishedMatching` 之后，公开 API 不设置时 SQL 文本不变。【测试】`LibraryMatchTest`（逐键语义、各模块只接受自己的键、排序白名单）、`LibrarySearchServiceTest`（随机页范围、忽略请求页、空页、c=0）、`LegacyUrlParserModuleListTest`（逐键归一、400/404 分界、`role/show`、剧情的视频键、四种形态往返）、`SiteModuleListPagesMvcTest`、`ModuleSearchGoldenMySqlIT`。

#### 7.4 验证码与搜索频率（临时处理与后续归属）

| 旧机制 | 旧代码 | B05 的临时处理（不永久取消、不能被绕过） | 后续归属与接线验收 |
|---|---|---|---|
| `show_verify=1`（筛选页验证码；旧 ajax_show 不验证） | `Base.php:29-43` | 筛选页与 `ajax_show` 一律关闭（404，`SiteFeatures.show`；环境变量 `maccms.site.features.show=true` 也不能打开） | 本地验证码与会话：B08；`ajax/verify_check`：B11。接线后按旧行为：未验证时显示验证页、通过后放行，ajax 入口不验证 |
| `search_verify=1`（搜索页验证码；旧 ajax 入口不验证） | `Base.php:63-70` | 视频与各模块的搜索页（含兼容模式 `s`）关闭（404，`SiteFeatures.searchPageClosed`）；`ajax_search` 照常 | 同上 |
| `search_timespan`（同一会话两次搜索的间隔，仓库值 3 秒；只在第 1 页检查） | `Base.php:59-62`、`mac_get_time_span`（`common.php:306-316`，session 计时） | 新版没有会话，不做该限制；由默认关闭的搜索开关、并发许可、执行时限与 503 约束成本（第 11 节差异） | **B08 已接线**（17 文档第 2.5 节）：按旧语义只在第 1 页按会话计时，过快时 429 |

【测试】`SiteFeaturesTest`。手机模板与繁体语言包（D-B05-8）：B05 用响应式单模板与简体固定文案并登记差异，手机模板选择与 zh-cn/zh-tw 输出归 B12（MACC-36），不是永久取消。B12 已完成接线（21 文档第 12.3 节）：每个请求按旧 `Init.php` 选择手机/站群（`SiteVariants`），手机访问显示 `site_waplogo`、`mob_status` 为 1/2 时页面带 `Vary: User-Agent`，旧版 `lang()` 的文字（错误页、提示页、JSON `msg`）按 `app.lang` 输出；页面固定文案、canonical 与站内链接不随手机或语言变化（`SiteVariantPagesTest`）。外壳、组件与 `static/site/` 未改，签名见 21 文档第 12.4 节。

#### 7.5 PHP 黄金样本

`src/test/php/module_search_golden.php` 在一次性库（`install.sql` + `site/module-search/seed.sql`，主用例之后再导入 `phase-plot-bulk.sql`）上以 `paging=yes` 调旧 `listCacheData`（条件经 `mac_param_url` 读取，`search_len` 按 Init.php 取 10），记录 ID 序列、total 与新版规范地址；随机用例用 `mt_srand` 固定种子（算法一选出使随机页恰为指定页的种子，算法二重放 `array_rand` 的候选下标）。**124 例，PHP 8.0.30 与 7.3.33 结果逐项相同**。`ModuleSearchGoldenMySqlIT` 以真实 HTTP 请求每例地址：沿"下一页"取完所有页比对 ID 序列，canonical 等于旧逐键归一后的规范地址，"共 N 条"等于旧 total 且与各页合计一致，ajax 片段与第一页相同；随机用例经可替换随机源给出黄金样本的随机页/候选，并核对上限 `floor(c/20)+1` 或候选范围。1 例为登记差异（`art.search.wd=_`：旧版 LIKE 不转义 `_ %`）。

### 8. 前端（Kimi）配合

K1–K5 由 MACC-54 交付，队长阶段审阅通过后以普通 merge `d4262ea` 整合固定提交 `3100239`。K5 中"W2 关闭时外链 Logo 已由后端过滤"的结论与当时固定树不符，已在 `docs/frontend/03-b05-pages-ui.md` 第 7 节更正；后端 `SitePages` 按 W2 过滤外站 Logo（站内路径与 base-url 同主机地址照常输出）。返工没有修改 `static/site/`、`components/`、`layout/`；`role/show` 与随机提示沿用已有 class。

### 9. 生效的队长裁定（2026-10-17）

| 编号 | 裁定（生效） | 落实 |
|---|---|---|
| D-B05-1 | 专用策略只用于同源 `vod/player` 内页，逐响应 nonce、安全 JSON；外层与其余页面保持严格策略；W1 只放行解析/iva 所需来源，W2 不打开 `script-src` | 第 6.2 节，`PlayerFramePolicy` |
| D-B05-2 | 通过存在性/发布/可见性检查后，无播放/下载权限 403 固定提示、no-store；不存在等仍 404 | 第 6.4 节 |
| D-B05-3 | 签名 cookie，部署密钥只走环境变量、无内置密钥；绑定内容/用途、服务端有效期、当前密码；只接受 POST；限流；日志不泄露 | 第 6.4 节，`PasswordTickets`、`PasswordAttempts` |
| D-B05-4 | 实际命中的版权限制 403、no-store；按入口触发（3 对 play、4 对 player，下载不查）；既有详情/API 状态码不变 | 第 6.4 节 |
| D-B05-5 | **ajax 为 HTML 片段；B05 保留各模块 `ajax_detail/ajax_show/ajax_search` 的完整范围，连同必要的只读 `show/search` 页面**（不批准"仅 ajax_detail"） | 第 7 节（含返工补齐的 `role/show`） |
| D-B05-6 | 搜索页 `ajax` 按旧 intval 接受并忽略，不进链接/canonical/隐藏字段；重复键、编码、长度限制保持 | 第 3.1 节 |
| D-B05-7 | 独立 `vod/show` 入口，第 1 槽为分类本身 + 直接子分类，不换成 tid 的 OR；canonical 用 show 入口；不向 `vod/search` 加 `type` | 第 3.6 节 |
| D-B05-8 | 响应式单模板、简体文案并列差异；手机模板与 zh-cn/zh-tw 归 B12（MACC-36），不是永久取消。B12 已接线（21 文档第 12 节；剩余差异见 D-B12-L3） | 第 7.4 节、第 11 节 |

### 10. 验证（最终树）

所有检查只用一次性库 `maccms_it_<时间>_<随机>`（创建前校验库名，结束时删除并查 `information_schema.schemata` 确认不存在），不连接 `maccms_dev` 或任何已有库；浏览器只访问本机应用与本地反向代理，所有非本机请求在浏览器内拦截并记录。最终计数以 MACC-29 交付评论中对最终提交的记录为准（本文件随最终提交一起变更，不在此重复会过期的数字）。

| 检查 | 方式 | 证据 |
|---|---|---|
| 单元/契约/MockMvc | `./mvnw clean verify` | 交付评论 |
| MySQL 集成与黄金向量 | `./mvnw verify -Pmysql-it`：`ModuleSearchGoldenMySqlIT`（第 7.5 节，124 例）、`PlaybackGoldenMySqlIT`、`VideoTagGoldenMySqlIT`、`VideoListGoldenMySqlIT`、`SiteSearchMySqlIT`、`LibraryMySqlIT` 等 | 交付评论 |
| PHP 黄金向量 | `module_search_golden.php`（PHP 8.0.30 与 7.3.33 逐项相同）、`vod_play_golden.php`、`b05_url_golden.php`、`vod_tag_golden.php`、`content/vodlist` 的 `urls[vodsearch.*/vodshow.*]` | 资源文件 + 上列测试 |
| 前端试看脚本 | `node --test src/test/js/site/trial.test.js`（MACC-54；Node 24 不接受目录参数） | 交付评论 |
| 真实 HTTP + 浏览器 | `python src/test/browser/b05_site_check.py --out docs/architecture/15-b05-site-check`：已打包应用 × W1/W2 = 00/01/10/11 × 根路径 / 子目录 `/sub/`（本地反向代理）共 8 组；Chromium（Playwright） | `15-b05-site-check/summary.json`、`screenshots/` |
| 中规模合成库测量 | `B05SsrPerfRun`（最终树、真实 SSR HTTP 与实际内部标签服务/Mapper，记录生成的 SQL/绑定、EXPLAIN、耗时、状态与清理） | `15-b05-perf-report.md` 第 10 节、`15-b05-perf-raw/ssr-*` |

`b05_site_check.py` 每组检查：页面/入口的状态码、`no-store`、CSP、站名、Logo、站内链接前缀（子目录时以 `/sub/` 开头）、外部主机按 W1/W2 各自出现（第 6.3 节）；播放/下载密码（错误 403 不回显，正确 303 + `HttpOnly; SameSite=Lax` 票据，带票据 200）；播放器内页在专用 CSP 下运行旧 `player.js`、整轮无 CSP 违规；W2 关时外部播放地址显示固定提示；试看到期后挂载区媒体移除并显示 `#trial-message`；根路径 W1/W2=11 另查 320/360/768/1280 横向溢出、键盘 Tab 次序与可见焦点、`prefers-reduced-motion`。返工后另含 `role/show` 与模块随机页。

### 11. 与旧版的差异

| 方面 | 旧 PHP | 新 |
|---|---|---|
| 标签范围运算符 | ThinkPHP 全部表达式（含 `exp` 原样 SQL、`like`） | 只接受比较/区间/IN 白名单与整数值，其余拒绝 |
| 标签列表项数 | 不限 | ≤ 100；文本 ≤ 64 码点 |
| 标签包含匹配（`weekday`、文本 `rel`） | 无开关 | 受搜索开关（默认关）、并发许可与时限 |
| 可见范围 | `popedom_filter` 才过滤 | 始终只含可见分类（`type` 中不可见的项只是不命中；`typenot` 在可见分类内取差集） |
| SSR `isend` 等未用键 | 忽略 | 400（`ajax` 按 D-B05-6 接受并忽略） |
| 兼容模式 `s` | 任何路径上都改写路由 | 入口路径上指向本批页面时由 Java 承接；指向其他页面、或出现在搜索/筛选页路径上时交还 PHP（本地 404） |
| `vodsearch`/`vodshow` 位置参数 | `route_status=1` 时可用、生成器输出位置形式 | 只作为输入被认可，canonical 指向查询串形式；`vodshow` 多于 12 段不认 |
| `vod/show` 分类条件 | 由模板标签决定（通常 `type=current`） | 固定为分类本身 + 直接子分类（D-B05-7）；不接受 `tid` |
| 模块搜索 LIKE | 不转义 `_ %`（`wd=_` 命中全部） | 按字面值匹配（与视频相同） |
| 模块排序/方向不在旧白名单中 | 回落为 `time`/`desc` | 400（与视频搜索页相同）；白名单内的 `rnd`、`in`、`referer*` 照旧承接 |
| 模块随机排序 | 每次随机页，没有主键次序 | 同一算法；并列时追加主键倒序；页面不显示分页 |
| 网址 `by=in` 的名单 | 去掉引号/反斜线后拼入 `orderRaw` | 只去掉反斜线并绑定参数；并列时追加主键升序 |
| 模块 `level` | URL 先 `intval`（多值取首段、`all` 为不筛选），模板标签可多值 | URL 语义相同；模板标签的多值/`all` 不是 URL 输入（视频的标签能力见第 2 节） |
| 模块筛选页 `role/show` | `label_role` + 模板列表 | 提供（返工第 1 项），无分类 |
| 验证码（`show_verify`、`search_verify`） | 有 | 接线前临时关闭对应页面（第 7.4 节，B08/B11） |
| 搜索频率（`search_timespan`） | 会话计时，仓库值 3 秒 | B08 起按会话计时（第 1 页，过快 429，17 文档第 2.5 节） |
| ajax 片段结构 | 模板 `*/ajax_*` | 与整页相同视图的结果片段（无外壳、无脚本）；仓库无旧模板，DOM 不对齐 |
| 手机模板、繁体语言包（D-B05-8） | `mob_status` 切换、zh-tw | 响应式单模板；B12 起手机访问换手机 Logo 并带 `Vary: User-Agent`，`lang()` 的文字按语言包，模板固定文案不切换（21 文档第 12 节，D-B12-L3） |
| 试看 | 模板脚本计时 | `trial.js` 浏览器计时（不是服务端授权屏障，与旧版相同把地址交给浏览器） |
| `vod/player` 内页权限 | 不中止的检查，会给出地址 | 与播放页同样判定，不输出受限地址 |
| 访问密码状态 | session，`!=` 宽松比较，不看审核状态 | 签名票据（2 小时、绑定当前密码），`BINARY` 比较，只认已审核内容；按内容限流（同一内容的大量错误尝试会让其他访客在窗口内也被拒绝） |
| SSR 二次解码、`htmlspecialchars` | 有 | 只解码一次（10 文档第 6 节，不变） |
| SSR 随机排序 | 每页可翻 | 不显示分页 |
| SSR 新键超长 | 按 `search_len` 截断（仓库值空时 10） | 超出码点上限或链接预算 400；码点上限内的值不截断 |
| 文章积分 | 按 `art_points_type` | 相同（取代 B03 的"任一列"） |

B03 已登记的差异（可见分类、`type_id=0`、密码字符串 `"0"` 视为有密码等）保留，B21 在授权的一次性合成数据中复核。性能限制（30 万行时部分低选择性条件超过 3 s 时限返回 503）见 `15-b05-perf-report.md` 第 0、10 节，保留默认关闭与护栏，调优归属见该报告。

### 12. 回滚

不写库、无数据迁移、零 DDL；回滚即普通 `git revert` 本批提交（不改写历史）。回滚点：已验收 `main` `a0876b8`；独立验收的固定交付 `f111945`（返工前）；本批开工前的分支状态 `5f14795`；**前端整合前 `955d767`**（整合本身可单独 `git revert -m 1 d4262ea`）。运行时可逐项关闭：`MACCMS_SITE_ENABLED`、`MACCMS_SITE_SEARCH_ENABLED`（关闭时不注册对应路由），`MACCMS_SITE_SHOW`、`MACCMS_SITE_AJAX_PAGE`（筛选页、ajax 入口 404；留空按旧配置，仓库值关闭；旧 `show_verify`/`search_verify=1` 时对应页面临时关闭，不能被打开），`MACCMS_BROWSER_EXTERNAL_W1/W2`，未配置 `MACCMS_SITE_PASSWORD_SECRET` 时密码页只提示不支持在线验证。`MACCMS_CONTENT_ART_POINTS_TYPE` 留空按仓库值 1。

### 13. 独立验收返工（2026-10-17）

| 项 | 问题 | 修正 |
|---|---|---|
| 1 | `role/show`、`role/ajax_show` 以"缺少角色分类、旧 `label_type`"为由移除 | 旧 `Role::show` 调 `label_role()`，没有 `label_type`。恢复为不依赖分类的筛选页与片段（第 7.2 节），受 `app.show`/`ajax_page`；黄金样本 `role.show*`（含 48 行三页、随机页）以真实 HTTP 比对 ID 序列、canonical、分页回读 |
| 2 | 模块 SSR 查询沿用公开 API 1.2.0 的单值限制，拒绝 `rnd`，多值 `letter` 等被拒 | 新增 SSR 内部查询 `LibraryListQuery`/`LibraryMatch`/`LibrarySlice`，逐模块按旧调用链承接（第 7.3 节）；剧情经旧视频标签链读取与 `vod/search` 同一组键；随机行为用可替换随机源对照 PHP 黄金（含阈值 2000/2001、候选重放、空页、c=0）；公开 API 不变 |
| 3 | 15 文档的契约、覆盖表与实现不一致 | 本文件整体改为最终契约：第 3.1 节 `s`/`ajax`、第 3.6 节 `vod/show`、第 6.3 节 W1/W2 独立四态、第 6.4 节 403/签名票据、第 7 节完整范围、第 9 节生效裁定、第 11 节差异、第 12 节回滚点（整合前为 `955d767`）；历史提议移入附录 A。验证码按 B08/B11 临时关闭且不能被绕过（第 7.4 节，`SiteFeatures` 同步修正） |
| 4 | 中规模证据缺最终入口；30 万行报告的超时被误读的风险 | 在最终树上以真实 SSR HTTP 与实际内部标签服务/Mapper 测量（`15-b05-perf-report.md` 第 10 节），记录生成的 SQL/绑定、EXPLAIN、耗时、状态与清理；如实保留超时/503 作为性能限制，不加索引/DDL、不改开关默认值 |

### 附录 A：作废的历史提议（仅作记录，不是契约）

以下是开工时（`6f5e2af`）提交裁定前的拟定方案，已被第 9 节的裁定与第 3–7 节的实现取代：

- 位置参数 `vodshow` 第 1 段"映射为 `tid`"或"映射到搜索页"的两种拟定：被 D-B05-7 否决，改为独立 `vod/show` 入口。
- 游客无权限"404（与详情页一致）"：被 D-B05-2 否决，改为 403 固定提示。
- 密码验证的"会话 cookie"：被 D-B05-3 的签名票据取代（服务端有效期、绑定当前密码）。
- 版权限制"维持 503 交 PHP"：被 D-B05-4 否决。
- `ajax_*` "只做 `ajax_detail`，`ajax_show/ajax_search` 随 show/search 遗留"：被 D-B05-5 否决，B05 承担完整范围。
- 搜索页 `ajax` "按未知参数 400"：被 D-B05-6 取代。
- "W1/W2 关闭时页面没有任何外部主机"的笼统表述：两开关独立，只有两者都关时才要求完全没有外部来源（第 6.3 节）。
- 内容卡片"由前端建立 `components/contentCard.jte`"：K2 结论为页面内联。
- 模块筛选/搜索页"排序白名单不含 rnd、letter/level 单值、`role/show` 不提供"（`f111945`）：被独立验收退回，见第 13 节。
