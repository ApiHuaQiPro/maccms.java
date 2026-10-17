## 11 content 只读补全：多值字段解码与其余内容类型（MACC-27 / 路线图 B03）

> 状态：已实施，待验收。依据：`09-roadmap.md` 第 5.3 节 B03 行（交付物："播放串、下载串、分集剧情、文章分页等多值字段的编解码……；
> 文章、专题、演员、角色、网址、友链、剧情的只读服务与 API（列表、详情、可见性）"；验收："03 第 4.2 节列的边界向量全部与 PHP 一致；
> 各类型列表的过滤与排序按各模型 listCacheData 对齐，ID 序列与 PHP 黄金样本一致"）。
> 零结构变更：只读 `mac_*`，不建表、不改列，不写任何表，不新增配置项，不发起出站请求。路径相对仓库根目录。

### 1. 交付范围

| 项 | 内容 | 位置 |
|---|---|---|
| 多值字段解码 | 播放/下载分组（mac_play_list）、单组地址串（mac_play_list_one）、文章分页（mac_art_list）、分集剧情（mac_plot_list）、截图（mac_screenshot_list） | `content.application.LegacyMultiValue`、`PhpValues` |
| 契约 1.2.0 | 15 个新 GET 路径、22 个新 schema、7 个新错误码；1.1.0 已有内容不变 | `docs/contracts/content-read.openapi.yaml` |
| 只读服务 | `ArticleReadService`、`TopicReadService`、`ActorReadService`、`RoleReadService`、`WebsiteReadService`、`LinkReadService`、`VideoPlotReadService`；参数类型 `ContentListQuery`、`ContentFilter`、`ContentKind` | `content.application` |
| 已发布只读类型 | 16 个 record（见 02 规范第 2 节） | `content.application.view` |
| 数据访问 | `ArtMapper`、`TopicMapper`、`ActorMapper`、`RoleMapper`、`WebsiteMapper`、`LinkMapper`、`VodPlotMapper`；`TypeMapper.selectEnabledTypes(mid)`；`VodMapper` 增加 3 条语句（专题关联视频、角色所属视频） | `content.infrastructure`、`resources/mapper/content/` |
| 黄金样本 | PHP 生成脚本 2 个、向量/样本 2 份、共享合成数据 1 份 | `backend/src/test/php/`、`backend/src/test/resources/content/` |

**不在本批**（第 7 节）：播放/下载地址的对外接口与 `player_aaaa`（B05，需要 B02 的旧配置读取与 W2 放行参数）、页面（B05）、
包含匹配类筛选参数与其余模板参数、编码（写入）方向（B13）。

### 2. 依赖与模块边界

- 新代码全部在 `content` 模块内，遵守 02 规范：api → application → infrastructure；infrastructure 不引用 application
  （新增的排序键 `SortColumn` 放在 infrastructure，由 application 的 `ContentKind` 白名单映射，不新增 MACC-4 那样的例外）。
- 分类可见性与视频完全相同（`CategoryCatalog`），只是按模型取分类：文章 type_mid=2、演员 8、网址 11（`ContentCatalogs`）。
  后台为这三类内容选分类时只列出对应 type_mid 的分类（`application/admin/view/art/info.html:24`、`actor/info.html:24`、`website/info.html:25`）。
- 数据库连接故障、服务端执行时限超时一律经 `ContentReadService.read` 归为 503，与 1.1.0 相同；日志只有类型链。
- 专题"按标签关联内容"是全表包含匹配，复用视频搜索的开关与并发许可（`ContentReadService.tryAcquireSearchPermit`）。

### 3. 多值字段解码（`LegacyMultiValue`）

逐条复刻 `application/common.php` 的旧函数（行号以当前仓库为准）：

| 旧函数 | 位置 | 规则要点 |
|---|---|---|
| `mac_art_list` | `common.php:1294-1318` | 页数 = art_content 按 `$$$` 的组数；标题 art_title、备注 art_note 按位置对齐 |
| `mac_plot_list` | `common.php:1320-1341` | 条数 = vod_plot_name 按 `$$$` 的组数；详情按位置对齐 |
| `mac_play_list` | `common.php:1346-1407` | from/url/server/note 各按 `$$$` 分组；只保留配置 `status == '1'`（PHP 宽松比较）的组；前台且 `app.player_sort=1`（`MAC_PLAYER_SORT`，`application/common/behavior/Init.php:55`）时按配置 sort 降序（PHP SORT_REGULAR），相同 sort 按 sid 升序；调用 mac_play_list_one 时**不带**服务器前缀 |
| `mac_screenshot_list` | `common.php:1415-1432` | 按 `#` 拆分；没有 `$` 或地址为空时 name 为序号、url 为整段 |
| `mac_play_list_one` | `common.php:1434-1455` | 按 `#` 拆分，跳过空段；地址为空时集名为"第N集"（`application/lang/zh-cn.php:84-85`，zh-tw 相同）、地址为标题；第三段为空时来源取组来源；nid 为段位置（空段占位，可能不连续） |

共同的 PHP 语义：`empty()` 把 `""` 与 `"0"` 都当作空（整串为 "0" 时不拆分，段为 "0" 时跳过）；`explode` 保留首尾空段；
PHP 中取不到的数组元素（null）一律输出为 `""`，取不到的配置项为空 Map。播放器配置由调用方传入（`Map<编码, Map<键, 值>>`，值为 PHP 标量转字符串），
本批不读取 `application/extra/vodplayer.php` 等文件——那是 B02 的 `legacyconfig` 职责。

**黄金向量**：`backend/src/test/php/content_multivalue_golden.php` 用合成输入调用上述旧函数（MAC_PLAYER_SORT 为 1、0 各一个子进程），
输出 `backend/src/test/resources/content/multivalue/php-vectors.json`。覆盖 03 草稿第 4.2 节列出的全部边界：空标题、缺少 `$`、server 前缀
（三参数调用）、播放器未启用时被过滤、按 sort 排序（含并列、数字/非数字/缺失 sort、整数配置值）、结尾多出 `#`、含 CRLF；另有空段占位、"0" 段与 "0" 地址、
第三段来源、多余的 `$` 段、组数不齐、下载器配置、HTML 实体与 Unicode。PHP 7.3、8.0、8.2 输出逐字相同。
`LegacyMultiValueGoldenTest` 逐条比对（86 项）。

重新生成（仓库根目录，不需要数据库）：

```bash
php backend/src/test/php/content_multivalue_golden.php > backend/src/test/resources/content/multivalue/php-vectors.json
```

### 4. 只读接口（契约 1.2.0）

#### 4.1 路径

| 路径 | 旧入口 |
|---|---|
| `GET /api/v1/videos/{id}/plots` | `plot/detail`（`application/index/controller/Plot.php:38-42`） |
| `GET /api/v1/article-categories`、`/actor-categories`、`/website-categories` | 各类型分类页的分类数据（`Type::listCacheData` 的 mid） |
| `GET /api/v1/articles`、`/articles/{id}` | `art/type`、`art/show`、`art/detail`（`application/index/controller/Art.php`） |
| `GET /api/v1/topics`、`/topics/{id}` | `topic/index`、`topic/detail`（`Topic.php`） |
| `GET /api/v1/actors`、`/actors/{id}` | `actor/index`、`actor/type`、`actor/show`、`actor/detail`（`Actor.php`） |
| `GET /api/v1/roles`、`/roles/{id}` | `role/index`、`role/show`、`role/detail`、视频页的角色列表（`mac_label_vod_role`，`common.php:2735-2743`） |
| `GET /api/v1/websites`、`/websites/{id}` | `website/type`、`website/show`、`website/detail`（`Website.php`） |
| `GET /api/v1/links` | 友链标签（`Link::listCacheData`） |

所有响应字段必填、不出现 null；列表的分页、页大小上限与视频列表相同（page 1..1000、pageSize 1..100、默认 20，旧 num 默认 20）。

#### 4.2 列表的过滤与排序（对齐各模型 listCacheData）

| 类型 | 固定条件 | 排序白名单（旧 by，默认 time desc） | 精确筛选参数（旧参数） |
|---|---|---|---|
| 文章 | art_status=1（`Art.php:229`） | time、time_add、id、score、hits、hits_day、hits_week、hits_month、up、down、level（`Art.php:370`） | categoryId（type，`:271-286`）、level（`:230-235`）、letter（`:252-258`） |
| 专题 | topic_status=1（`Topic.php:147`） | 同上（`Topic.php:235`） | level（`:148-150`）、letter（`:158-164`） |
| 演员 | actor_status=1（`Actor.php:179`） | 同上（`Actor.php:304`，不含 in） | categoryId（`:216-232`）、level、letter、sex（`:194-196`）、area（`:276-278`）、starSign（`:279-281`）、blood（`:282-284`） |
| 角色 | role_status=1（`Role.php:153`） | 同上（`Role.php:247`） | level、letter、videoId（rid，`:174-176`） |
| 网址 | website_status=1（`Website.php:233`） | 同上 + referer、referer_day、referer_week、referer_month（`Website.php:393`，不含 in） | categoryId（`:267-283`）、level、letter、area（`:363-365`）、lang（`:366-368`） |
| 友链 | 无（全部行） | id（默认）、sort；方向默认 **asc**（`Link.php:53-58`） | type=font/pic → link_type 0/1（`:59-62`） |

- 文章、演员、网址的列表只包含所属分类可见的行（与视频列表相同）；categoryId 包含该分类与可见的直接子分类（旧 type 条件：type_id 或 type_pid 命中）。
- 排序后追加主键同方向排序（旧版没有，并列时顺序不确定）。
- 参数读取方式与 1.0.0 既有参数相同：多次出现时以逗号连接后校验，所以文本参数重复 → 400"不支持多个取值"；非法 UTF-8 → 500（同 1.0.0）。
- 文本参数（sex、area、lang、starSign、blood）与 1.1.0 的 area/lang 规则相同（`VideoFilter.text`），长度上限取列宽。
- level 为 1..9 单值；letter 为单个 ASCII 字母/数字或 `0-9`（旧代码 `substr($letter,0,1)=='0' && substr($letter,2,1)=='9'` 时展开为 10 个数字）。
- 所有列表语句带 `MAX_EXECUTION_TIME(3000)`（超时 → 503）；本批没有包含匹配的列表参数，所以列表不受搜索开关与并发上限影响。

#### 4.3 详情的可见性

| 类型 | 判定顺序（旧代码） | 新接口 |
|---|---|---|
| 文章 | art_id + art_status=1（`common.php:2693-2713`）→ 分类可见 → 游客详情权限位 2（`index/controller/Base.php:92-100、118-121`）→ 游客访问需积分的文章显示扣积分确认页（`Base.php:105-112、123-140`）→ art_pwd 非空显示密码页（`Art.php:56-61`） | 404 `ARTICLE_NOT_FOUND` → 403 `ARTICLE_POINTS_REQUIRED` → 403 `ARTICLE_PASSWORD_REQUIRED`；`maccms.content.enforce-guest-popedom=false`（对应 user.status=0，`Base.php:115-117`）时不检查权限位与积分 |
| 演员、网址 | 状态 → 分类可见 → 游客详情权限位 2（`application/common/controller/All.php:196-234`、`:265-303`）；两表没有积分与密码列 | 404 `ACTOR_NOT_FOUND` / `WEBSITE_NOT_FOUND` |
| 专题 | topic_id + topic_status=1（`common.php:2677-2692`），无权限检查 | 404 `TOPIC_NOT_FOUND` |
| 角色 | role_id + role_status=1（`common.php:2657-2676`），无权限检查 | 404 `ROLE_NOT_FOUND` |
| 分集剧情 | 与视频详情相同（`ContentReadService#getVideo`） | 404 `VIDEO_NOT_FOUND`、403 `VIDEO_PASSWORD_REQUIRED` / `VIDEO_COPYRIGHT_RESTRICTED` |

访问密码与积分只以 SQL 计算的布尔值出现（`CHAR_LENGTH(art_pwd) > 0`、`art_points > 0 OR art_points_detail > 0`），值本身从不读入内存；
`ReadOnlyGuardrailsTest.libraryMappersNeverSelectInternalColumns` 静态检查。

#### 4.4 专题的关联内容

对应旧 `Topic::infoData`（`application/common/model/Topic.php:291-334`），依次拼接、重复项保留：

1. `topic_rel_vod` / `topic_rel_art` 中的 ID：已审核，按更新时间倒序，各最多 999 条。旧版把原文交给 `IN`，ThinkPHP 按逗号拆开并以整数绑定
   （`thinkphp/library/think/db/Builder.php:404-425`）；新版同样按逗号拆分，每项跳过前导空白取前导数字（`" 101"` → 101、`"5x"` → 5、`"abc"` 跳过），
   黄金样本已验证 `" 101"` 在旧版中命中。
2. `topic_tag` 按逗号拆开（mac_like_arr，`common.php:1284-1292`，保留空项）后在 vod_tag / art_tag 中包含匹配：已审核，同样排序与上限。
   **只在 `maccms.content.search.enabled=true` 时执行**，并与视频搜索共用并发上限（拿不到许可 → 503）。

新增：关联的视频/文章还须所属分类可见。

### 5. 与旧 PHP 的差异

| # | 方面 | 旧 PHP | 新接口 | 原因 |
|---|---|---|---|---|
| 1 | 列表的分类可见性 | 文章/演员/网址列表不按分类状态与游客列表权限过滤（只有开启 popedom_filter 时过滤，`Art.php:347-357` 等） | 只列出所属分类可见的行；type_id 为 0 或分类不存在的行不出现 | 与视频列表（01 文档差异 #3）一致，避免列出点进去就无权限的内容 |
| 2 | 并列排序 | 无主键排序，顺序不确定 | 追加主键同方向排序 | 分页稳定 |
| 3 | 多值筛选 | level/area/lang/starsign/blood 支持逗号多值，level 支持 all | 单值；逗号 → 400；all → 400 | 与 1.1.0 视频筛选一致 |
| 4 | 未实现的列表参数 | ids、tid、wd、name、tag、class、rnd、in、timeadd、hits* 范围、not、rel、typenot 等 | 被忽略（未声明参数） | 见第 7 节 |
| 5 | 友链 type 取值 | font/pic 以外的值被忽略（列出全部） | 400 | 契约明确取值 |
| 6 | 文章积分判断 | 按 `user.art_points_type`（仓库 '1'，`application/extra/maccms.php:122`）只看 art_points 或 art_points_detail 之一，游客且积分>0 显示扣积分确认页 | 两列任一大于 0 即 403 `ARTICLE_POINTS_REQUIRED` | 本批不读取旧配置（B02），取更严格的一侧；B02 接入后可按配置收窄 |
| 7 | 无权限/需密码/需积分 | HTML 提示页、密码页、确认页 | 404 / 403 + 固定错误码，不返回任何字段 | API 契约；与视频详情一致 |
| 8 | 分集剧情页的密码与版权 | plot/detail 只检查权限，不检查 vod_pwd 与版权（`Plot.php:38-42`） | 与视频详情相同，有密码或版权限制时 403 | 剧情页展示的是同一视频的内容，取更严格的一侧 |
| 9 | 角色所属视频 | 不看视频状态（`Role.php:41-51、296-303`） | 只在视频已审核且分类可见时给出摘要，否则 id=0 | 不暴露隐藏视频的名称 |
| 10 | 专题关联内容 | 只要求已审核 | 另要求分类可见；标签关联只在搜索开关打开时执行；标签中的 `%`、`_` 按字面匹配 | 与视频可见性一致；全表包含匹配受成本控制（02 规范第 3 节） |
| 11 | 专题扩展字段 topic_extend | infoData 中解析为数组 | 不输出 | 后台从不写入该列（`application/admin` 中无引用），值恒为默认 |
| 12 | 文章分页 | 详情页按 `page` 参数显示一页，越界时取最后一页（`common.php:2708`） | 一次返回全部分页 | 分页显示属于页面（B05） |
| 13 | 集名缺省文案 | 随语言包（zh-cn/zh-tw 相同为"第N集"） | 固定"第N集" | 仓库只有这两个语言包 |
| 14 | 数据缓存 | cache_core 开启时缓存列表与详情 | 无缓存 | 与 1.0.0 相同 |
| 15 | 登录态 | 会员按所在组权限 | 一律按游客 | 与 1.0.0 相同（会员在 B08/B09） |
| 16 | 访问密码为 `"0"` | PHP `!empty("0")` 为假，视为无密码（`Art.php:58`） | 视为有密码（403，列表标记 detailPasswordRequired=true） | 与视频的 vod_pwd 处理一致（01 文档差异 #5），偏保守 |
| 17 | 专题关联 ID 的非常规写法 | ThinkPHP 以字符串绑定，MySQL 按数值转换：`"1.9"` 不命中、`"1e2"` 命中 100、`"+5"` 命中 5 | 只取前导数字：`"1.9"` → 1、`"1e2"` → 1、`"+5"` 跳过 | 后台录入的是逗号分隔的整数，黄金样本覆盖了 `" 101"`、`"abc"`、`"5x"`；其余写法视为脏数据，记为已知差异 |
| 18 | 播放器 sort 数字与非数字混用 | 比较不满足传递性，结果依赖 PHP 的排序算法 | 稳定插入排序，不报错，顺序可能与旧版不同 | 配置正常（纯数字）时两者相同；解码函数本批无对外调用方（B05） |

### 6. PHP 黄金样本与验证

- **共享数据** `backend/src/test/resources/content/library/seed.sql`：9 个分类（视频/文章/演员/网址）、游客权限、6 条视频（专题关联、角色、剧情）、
  文章/专题/演员/角色/网址各 12 行（id 1..10 已审核、每个排序列取值互不相同、level 依次为 0..9；id 11、12 未审核且排序值最大）、8 条友链。
  只用合成数据，不含真实域名或凭证。
- **PHP 样本** `backend/src/test/php/content_library_golden.php`：新建一次性库 `maccms_it_<时间>_<随机>`，导入 `install.sql` 与 seed.sql，
  调用旧模型 `listCacheData`（paging=no，num=999）与 `infoData`，结束后删除该库与临时缓存目录。输出
  `backend/src/test/resources/content/library/php-golden.json`：174 个列表用例（每类型默认排序、全部排序键 × 两个方向、level、letter 大小写与 0-9、
  各类型专有筛选）、4 个专题的关联内容、文章分页/截图、网址截图、4 个视频的分集剧情。每个用例记录对应的新接口请求。
- **Java 对照** `LibraryMySqlIT`（`-Pmysql-it`）：在自己的一次性库导入同一份数据，另加只对新版可见范围有影响的行（不可见/停用分类下的文章、演员、网址、视频，
  并让专题 1 引用它们），逐条请求并断言 ID 序列与 total 和旧版逐项相同，每个用例另比对第 2 页（每页 3 条）的切片；全部请求前后所有表 CHECKSUM 不变。
  之后追加需要密码/积分的文章验证 403 与列表标记。
- **HTTP 契约** `LibraryApiContractTest`（60 项，Mapper 替身）：每个响应按 OpenAPI 严格校验；参数校验顺序与错误字段、可见性判定顺序、503 响应体逐字一致与日志不含敏感标记。
  `TopicTagRelationTest`：标签关联的开关、模式转义、并发许可耗尽 503 与许可归还、关联 ID 解析。

重新生成 PHP 样本（仓库根目录；账号只经环境变量传入，只建删一次性库）：

```bash
export MACCMS_IT_ADMIN_URL='jdbc:mysql://127.0.0.1:3306/' MACCMS_IT_ADMIN_USERNAME=<本机账号> MACCMS_IT_ADMIN_PASSWORD=<密码>
php backend/src/test/php/content_library_golden.php > backend/src/test/resources/content/library/php-golden.json
```

### 7. 推迟的项目

| 项 | 原因 | 建议批次 |
|---|---|---|
| 播放/下载分组的对外接口、`player_aaaa`、`playerconfig.js` | 需要旧配置读取（vodplayer/voddowner/vodserver，B02）与 W2 外部地址放行参数；解码函数已就绪 | B05 |
| 包含匹配参数 wd、tag、class、name（文章） | 全表 LIKE，须按 02 规范第 3 节先定开关、时限、并发与测量（视频的同类参数用了整个 MACC-16～19） | 与 B04 的筛选框架一起，或单列批次 |
| ids、tid、not、rel、typenot、timeadd/timehits/time、hits*/referer* 范围、start、rnd、in、level=all、逗号多值 | 多为模板标签参数（模板来源 D9 待定），请求参数中可到达的 ids/tid/rnd 需与 B04 统一设计 | B04/B05 |
| 编码（写入）方向：把分组拼回 `$$$`/`#` 串 | 只在后台保存时使用 | B13 |
| 文章积分按 art_points_type 精确判断 | 需要旧配置读取 | B02 之后 |

### 8. 风险与遗留

- 线上配置未知：`user.status`、`art_points_type`、`app.player_sort` 等取值待核实（B02 接入旧配置后复核）。
- 线上数据可能有旧版能容忍、新版拒绝的形态（如 type_id=0 的演员会从列表消失，差异 #1）；B21 用导出数据对比时需关注。
- 黄金样本只覆盖合成数据的已知形态；PHP 7.3/8.0/8.2 对多值解码输出相同，列表样本在 PHP 8.0 + MySQL 5.7.44 上生成。
