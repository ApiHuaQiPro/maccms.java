## B13 后台内容管理：视频、分类、文章、专题、演员、角色、网址、友链、评论留言、附件、图片与上传（路线图 B13，MACC-37）

> **状态：开发完成（2026-10-25），补证完成（2026-10-26：D-B09-9 PHP 实跑黄金、缩略图与水印黄金、浏览器走查，第 9 节），待独立验收。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B13 行（第 156 行）、第 5.3 节 B13 验收行（第 395 行：逐列一致、整行覆盖计数字段与旧版一致、查重不用临时表、并发编辑、回滚演练、D-B09-9）、第 4.1 节 E02（第 187 行）与 E08（第 193 行）、第 5.3 节写入方矩阵（第 421-426 行）；
> - 03 草稿第 3.1 节（后台表单整行覆盖计数字段）；B07 `12-write-infrastructure.md`（写 SQL 规范、MyISAM 非事务边界、并发与回滚演练工具）；B11 `20-b11-comment-gbook-counters.md`（内容计数与视频搜索缓存）；B12 `21-b12-admin-framework.md`（后台入口、入参、会话、`admin_auth`、页面与脚本策略）；B09 `18-b09-member-account.md` D-B09-9（头像登记附件延后到本批）；
> - 用户决定（2026-10-17）：后台复用旧后台 HTML 结构与静态资产，转成 jte，不做新视觉设计；用户决定（2026-10-16，D1）：E02、E08 只做本地版本。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `backend/…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | 平台基线 `99ce3fe`（树与已验收 main `1cc5a2c` 相同，即 B12 验收后的路线图更新） |
| 分支 | `agent/claude/macc-37`（本任务工作区；只用普通提交，不 reset、rebase、amend、force，不自行合并 main） |
| 提交 | 见交付评论（最终 SHA 以评论为准） |
| 补证（2026-10-26） | 分支 `agent/claude/macc-23`（MACC-23 定时唤醒的工作区）：合并提交 `30b052f` 的树取自交付版 `8564745`、保留该轮起点 `4e13952` 为祖先；其后 `0559f2f`（D-B09-9 黄金）、`002e1fc`（平台代提交的未提交改动：浏览器走查发现的三处修正与缩略图、水印黄金）与本节文档、清理提交。B13 功能代码以 `8564745` 为基线，补证只改第 7.10、8.1 节所列文件 |

### 1. 范围

本批在 B12 的分派里接上 64 个动作：auth.php 中归 B13 的 54 个权限码，加 10 个旧控制器里有、auth.php 没列的公开动作（`annex/info`、`images/data`、`images/del`、`type/move`、`vod/iplot`、`vod/updatetoday`、`art/updatetoday`、`website/updatetoday`、`upload/index`、`upload/test`）。分派表见 `backend/src/main/java/com/maccms/backend/admin/web/AdminContentActions.java` 的 `handle`。

| 控制器（旧 `application/admin/controller/*.php`） | 动作 |
|---|---|
| `Index` | `select`（内容批量操作的公共弹窗，`Index.php:199-251`） |
| `Link` | `index` `info`（GET 表单 / POST 保存） `del` `batch` |
| `Type` | `index` `info`（带表单令牌） `del` `field` `batch` `extend` `move` |
| `Topic` `Actor` `Role` | `data` `info` `del` `field` |
| `Website` `Art` | `data` `info` `del`（含查重删除） `field` `batch` `updatetoday` |
| `Vod` | `data` `info` `iplot` `del`（含查重删除） `field` `batch` `updatetoday` |
| `Comment` `Gbook` | `data` `info`（编辑、回复） `del`（含清空 `all=1`） `field`（审核状态） |
| `Annex` | `data` `file` `info` `del` `check` `init` |
| `Images` | `data`（旧版空方法） `opt` `del` `sync`（E02 本地版，D-B13-2） |
| `Upload` | `upload`（只存本地，E08 本地版，D-B13-2） `index`（上传弹窗页） `test`（临时目录写入测试） |
| 会员（B09 延后项 D-B09-9） | 前台头像上传成功后登记 `mac_annex`（第 7.10 节） |

| 不在本批（归属） | 说明 |
|---|---|
| 远程图片下载 E02 的在线部分 | D1 排除；`images/sync` 不下载、库内地址不变（D-B13-2）；采集时的图片同步在 B19 |
| 远程存储 E08 的在线部分 | D1 排除；上传一律落 `upload/`，`upload.mode` 的配置读写与非本地模式的固定提示在 B15 |
| 静态页生成与删除、列表"浏览"列的已生成判断以外的静态页逻辑 | B16（D-B13-22） |
| 采集（资源站、规则、入库） | B17～B20 |
| 播放器、下载器、服务器组的配置管理 | B15（本批只读这些配置） |
| `topic/batch`、`link/field` | auth.php 里的死链（旧控制器没有方法），新旧都是 404（21 文档第 3 节） |

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| `content.application` | 各后台服务 `LinkAdminService` `TypeAdminService` `TopicAdminService` `ActorAdminService` `RoleAdminService` `WebsiteAdminService` `ArtAdminService` `VodAdminService` `AnnexAdminService` `AdminUploadService`；公共部分 `ContentAdminSupport`（列表、字段修改、查重删除、updateToday）、`ContentBatch`（批量处理）、`ContentSaveSteps`（saveData 共有步骤）；入参与取值 `PhpForm` `AdminRowValues` `AdminTable` `AdminIds` `AdminPaging`；旧配置 `AdminConfig` `AdminPlayers`；PHP 语义 `PhpText` `LegacyPinyin` `PhpLoose` `PhpNumbers` `PhpRand` `PhpJson`；图片 `LegacyImages`；`VodSearchCache.appendNewVod`（新增视频时追加搜索结果）；装配 `ContentAdminConfiguration`、聚合 `ContentAdminServices`；`view/`：`AdminRow` `AdminRowPage` `AdminResult` `AdminMessage` `AdminProgress` `AdminTypeNode` `AnnexDirectory` `UploadReply` |
| `content.infrastructure` | 只读 `ContentAdminMapper`（`mapper/content/ContentAdminMapper.xml`）、筛选条件 `AdminCriteria`；写 `ContentAdminWriteMapper`（`mapper-write/content/ContentAdminWriteMapper.xml`）、`ContentAdminWrites`（数据错误 → `AdminDataException`）、`AdminInsert`；`UploadStore`（旧站 `upload/` 目录）；`VodSearchMapper`/`VodSearchWriteMapper` 的新增语句 |
| `comment.application` | `CommentAdminService`、`CommentAdminConfiguration` |
| `comment.infrastructure` | `CommentAdminMapper`（`mapper/comment/CommentAdminMapper.xml`）、`CommentAdminCriteria`、`CommentAdminWriteMapper`（`mapper-write/comment/CommentAdminWriteMapper.xml`）、`CommentAdminWrites`、`CommentAdminInsert`、`CommentDataException` |
| `admin.web` | `AdminContentActions`（全部 B13 动作）；`AdminController` 在 B12 分派的 `default` 分支先交给它，返回 null 时仍按 B12 的"由后续批次实现"/404；multipart 正文里的普通字段按字节转成 urlencoded 再解析（上传的 `thumb`、`flag` 等）；`view/content/`：`ContentListView` `ContentFormView` `LinkListView` `LinkInfoView` `TypeListView` `TypeInfoView` `SelectView` `AnnexFileView` `ImgConfig` `BrowserText` `RawOutput` `FrontLinks` `ViewEq` |
| `member.application`（D-B09-9） | `PortraitAnnex`（函数接口）；`MemberAccountService.registerAnnex`；`MemberAccountConfiguration` 经 `ObjectProvider<AnnexAdminService>` 取附件服务，取不到时不登记 |

- **注册条件**：`ContentAdminConfiguration`、`CommentAdminConfiguration` 带 `@ConditionalOnWriteEnabled`，`ContentAdminWrites`、`CommentAdminWrites` 同样只在可写数据源打开时注册。后台本身要求可写数据源（21 文档第 7 节），所以生产上总是齐全；单元测试的替身上下文里没有这些 Bean 时，`AdminConfiguration.adminContentActions` 拿到 null，B13 动作按"由 B13 批次提供"处理（`AdminMvcTest` 仍断言这一点）。
- **边界**（`backend/src/test/java/com/maccms/backend/architecture/ModuleBoundaryTest.java`）：admin 仍不得引用 site，唯一例外是 `ADMIN_SITE` 列出的 4 个纯计算类型 `site.application.LegacyUrlBuilder`、`LegacyUrlStyle`、`LegacyPage`、`SiteRoute`（第 8.3 节）；admin.web 不直接用 Mapper；member 使用 `content.application.AnnexAdminService` 属于"其他业务模块只能用 content.application 与 .view"的一般规则。代码注释里"只供 admin 模块调用（ModuleBoundaryTest 锁定）"的说法比实际规则强：边界测试并不阻止其他模块调用这些服务（member 就在用）。
- **部署参数**：`maccms.upload.dir`（旧站根目录下 `upload/` 的本地路径；为空时上传返回失败、删除内容不删文件）。环境变量 `MACCMS_UPLOAD_DIR`（`application.yml`、`backend/config/maccms-backend.env.example` 已登记）。它与 B09 的 `maccms.member.upload-dir`（`MACCMS_MEMBER_UPLOAD_DIR`）是两个参数，必须指向同一个目录。上传大小上限取 `spring.servlet.multipart.max-file-size`，超过时按"未找到上传的文件"。

### 3. 表与列

| 表 | 本批写入（登记批次） | 用途 |
|---|---|---|
| `mac_vod` `mac_art` `mac_topic` `mac_actor` `mac_role` `mac_website` | INSERT、DELETE（B13）；UPDATE 沿用 B11 | 新增、整行覆盖、字段修改、批量处理、查重删除、转移分类 |
| `mac_link` `mac_type` `mac_annex` | INSERT、UPDATE、DELETE（B13） | 友链、分类、附件的增删改；附件登记 |
| `mac_comment` | DELETE（B13）；UPDATE 沿用 B11 | 删除与清空；编辑、审核 |
| `mac_gbook` | UPDATE、DELETE（B13） | 编辑、回复、审核；删除与清空 |
| `mac_vod_search` | INSERT、UPDATE 沿用 B11 | 新增视频时追加到已有搜索结果 |

- **列目录** `AdminTable`：11 张表（上表除 `mac_vod_search`）的列名、顺序、类型、长度、有无符号逐列照抄 `application/install/sql/install.sql`，是 `allowField(true)` 过滤与取值检查的依据（第 4.2 节）。`AdminTableSchemaTest` 解析 install.sql 逐列比对（列名、顺序、类型、长度、有无符号），并检查两个写 Mapper XML 里每张表的新增、整行覆盖、按主键列表修改都列出了全部列（路线图风险"表单字段多，遗漏字段导致数据被清空"的护栏）。
- **登记** `WriteRegistry`（`backend/src/main/java/com/maccms/backend/common/write/WriteRegistry.java:67-90`）：新增 24 项，登记表共 51 项（`WriteGuardTest`、`WriteRegistryTest` 锁定；`member` 写 `mac_annex` 未登记、也不需要——头像登记经 content 的服务写入）。
- 零结构变更：没有 DDL、没有新表新列、不建临时表（旧版查重用 MyISAM 临时表，D-B13-7）。

### 4. 入参与文本函数

#### 4.1 `PhpForm`：旧 `input()` / `input('post.')`

- web 层按 B12 的规则（`AdminParams`：按字节解析、`filterExp`、`名[]`/`名[键]` 数组）解析后转成 `PhpForm` 交给内容模块：按出现顺序的参数表，值是字节串或数组（保留键）。服务在副本上照旧模型的步骤增删改（`$data['x'] = …`、`unset`），最后只把表中有的列写库。
- 每个动作取哪一路参数与旧控制器相同：专题、演员、角色、网址、文章、视频、附件、分集剧情的保存只取 POST；友链、评论、留言的保存与全部列表、删除、字段、批量用合并后的 `input()`；分类保存只取 POST 并先过表单令牌（缺失"非法请求"，不一致或已用"请不要重复提交表单"，与管理员表单共用会话里的令牌）。
- PHP 判断原样实现：`empty()`、`isset`、宽松比较 `PhpLoose`（`'1.0' == 1`）、`intval`、`in_array` 宽松/严格两种。

#### 4.2 `AdminRowValues`、`allowField` 与 `ContentSaveSteps`

- **取值规则**（`AdminRowValues`）：只取 `$data` 中表里有的列，按出现顺序；数值列的空串写 0（ThinkPHP 以整数绑定）；其余原样交给 MySQL 按列类型转换（`'1.5'` → 2、`' 7'` → 7，与旧版经 PDO 的结果相同）。旧版在 MySQL 严格模式下会报错（整页 500）的取值先被拒绝并返回"参数错误"：数组、PHP null（缺失的数组项、不存在的分类）、非法 UTF-8、4 字节字符（表是 3 字节 utf8）、超长字符串（CHAR/VARCHAR 按字符、TEXT 按字节）；数值列的非法值由 MySQL 报错，写入方把 1048/1264/1265/1292/1364/1366/1406 归为"参数错误"（D-B13-3）。
- **整行覆盖**：编辑时把提交的全部表列（含点击、顶踩、评分等计数列）写回，与旧版 `allowField(true)->update($data)` 相同。这是**与旧版一致**的行为（路线图验收、03 草稿第 3.1 节），不是差异。
- **共有步骤**（`ContentSaveSteps`，调用方按各模型原来的顺序）：`type_id_1` 取分类的上级（分类不存在 → "参数错误"）；拼音为空时由名称生成（`LegacyPinyin`，字典为仓库 `static/data/pinyin.dat`）；首字母 `strtoupper(substr(拼音,0,1))`；正文图片地址的协议换成 `mac:`（`upload.protocol`）；简介为空时取 `mac_substring(strip_tags(正文), 100)`（按 PHP 7.3，D-B13-5）；`uptime==1` 时写更新时间；`uptag==1` 时标签为空串（仓库的 `mac_get_tag` 已去掉外部调用、恒返回 false，`application/common.php:945-948`）；列出的字段 `mac_filter_xss`；视频另按 `VodValidate::formatDataBeforeDb` 的 `mb_substr` 长度裁剪（`application/common/validate/Vod.php:26-74`）。
- **文本函数黄金**：`backend/src/test/php/content_text_golden.php` 截取 `application/common.php` 里用到的函数源码执行，在 PHP 8.0.30 与 7.3.33 上各生成 1604 个用例（手写 + 固定种子随机串）→ `backend/src/test/resources/content/admin/text-php{80,73}-golden.json`；`PhpTextGoldenTest` 逐字节比对 `strip_tags`、`htmlspecialchars`、`mac_filter_xss`、`mb_substr`(10/100/255)、两种简介截断、拼音与首字母。两版只有 `mac_substring` 不同（PHP 8 把 ord 64-127 的字符换成空格），新版按 7.3，PHP 8 的差异单独断言。

#### 4.3 `AdminIds`

- 表单页的 `input('id')`：按 PHP `intval`，小于 1 时为空表单。
- 编辑时的主键（`!empty($data['x_id'])`）：必须是十进制数字（可带首尾空格），否则"参数错误"。
- 删除、修改的 `ids`（逗号串或数组）：每项必须是十进制数字，最多 1000 项，否则"参数错误"（同 D-B12-10，本批记 D-B13-4）。附件删除例外：`ids` 可以是文件名，编号按 MySQL 把文字比作整数的规则取（`12abc` 当 12），与旧版 `annex_id|annex_file in` 相同。
- 批量修改里"数组参数第 k 项"：没有这一项时是 null（写库前"参数错误"）；参数是字符串而不是数组时"参数错误"（旧版取到第 k 个字符）。

#### 4.4 `AdminConfig`、`AdminPlayers`

- `AdminConfig` 每次调用都经 `LegacyConfigService` 读 `extra/maccms.php`（按修改时间热加载），等同旧版每个请求读 `$GLOBALS['config']`；没有配置目录、读取失败或没有这一项时为空串（失败只记异常类型）。用到的键：`upload.*`（协议、水印、缩略图、`mode`、`remoteurl`、`img_key/img_api`）、`view.*_detail`、`rewrite.*`、`app.pathinfo_depr`、`app.vod_extend_*`/`art_extend_class`、`site.install_dir`、`app.vod_search_optimise`（经 B11 的 `VodSearchOptimise`）。与 B12 的 `AdminSettings`（启动时读一次）不同。
- `AdminPlayers` 同样每次读 `extra/vodplayer.php`、`voddowner.php`、`vodserver.php`；选项列表按旧 `mac_multisort($list,'sort',SORT_DESC,'status','1')`（`application/common.php:1492-1504`）。

### 5. 只读查询

- `ContentAdminMapper.xml`（748 行）与 `CommentAdminMapper.xml` 走只读连接池；每张表一个 resultMap，**全部列按文字取回**（`javaType="string"`：整数为十进制、decimal 保留小数位，与旧模板输出的文字相同，不受 `TINYINT(1)` 映射影响），页面与保存逻辑都按 PHP 字符串处理。
- 筛选条件 `AdminCriteria`/`CommentAdminCriteria`：每个字段对应 XML 里写死列名的一个条件，为 null 时不加；比较值是旧版交给 ThinkPHP 的原值（由 MySQL 按列类型比较）；`LIKE` 关键字由 `PhpText.legacyLike` 转写成 `ESCAPE '!'` 下的同义模式（`%`、`_` 照旧是通配符）。排序列只取白名单。
- `ReadOnlyGuardrailsTest` 的"读 Mapper 只能有 SELECT"检查按 `"LOCK "` 关键字拒绝锁语句，所以锁定条件写成 `vod_lock=#{c.locked}`、`art_lock=`、`website_lock=`（列名与 `=` 之间不留空格，XML 中有注释说明；属性名也避开为 `locked`）。
- 同序时补上主键使顺序确定（分类 `type_sort` 后补 `type_id`、专题 `topic_time` 后补 `topic_id`、视频与网址同序按编号升序；updateToday 按主键），旧版由 MySQL 决定（D-B13-20）。
- 查重列表与查重删除的同名组：`SELECT MIN(id), name … GROUP BY name HAVING COUNT(name) > 1`，按编号分页取完（D-B13-7）。

### 6. 写入

- 全部 SQL 在 `mapper-write/` 下，经 `WriteDatabase.mapper()`（B07）：新增、按主键整行覆盖（`<if test="c.containsKey(…)">` 逐列写出，只写给出的列）、按主键列表修改与删除、`moveVodType`/`moveArtType`、查重删除 `deleteXxxRepeat`（`name = ? AND id <> 保留编号`）、评论/留言清空（`WHERE id > 0`，同旧版 `all=1`）、附件按文件名删除。单表、单语句。
- **数据错误**：`ContentAdminWrites.run`、`CommentAdminWrites` 把严格模式的数据错误换成 `AdminDataException`/`CommentDataException`（不带原始信息），由 `AdminWrites.guard` 归为旧版 `error()` 的"参数错误"（D-B13-3）；其余异常原样抛出（500）。
- **附件登记**改为一条条件插入（`ContentAdminWriteMapper.xml` 的 `registerAnnex`）：

  ```sql
  INSERT INTO mac_annex (annex_time, annex_file, annex_size, annex_type)
  SELECT …, …, …, … FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM mac_annex AS a WHERE a.annex_file = …)
  ```

  `mac_annex` 是 MyISAM，整句持表锁，并发登记同一文件只插入一行；`AnnexAdminService.register` 以匹配行数报告"是否新登记"（上传成功时只有新登记了原图才继续登记缩略图）。旧版是"先 `infoData` 再 `saveData`"（`application/common/model/Upload.php:264-270`），并发时会登记多行（D-B13-1）。`annex_size` 按 MySQL 整数列取整（上传传入 KB 两位小数，如 `12.5` → 13，与旧版同）。
- **查重不建临时表**（D-B13-7）：旧版打开查重列表第 1 页时 `DROP/CREATE TABLE mac_tmpvod … ENGINE=MyISAM` 再按它删除（`admin/controller/Vod.php:103-105,459`；文章 `Art.php:51-53,241`；网址 `Website.php:50-52,240`）。新版列表用子查询，删除时按**当前**数据取全部同名组再逐组删除；`retain=max` 时删各组的最小编号（同旧版：同名超过两条时一次只删一条）。
- **没有事务**（MyISAM，12 文档第 4.3 节）。多语句的操作与部分失败的含义：

| 操作 | 语句顺序 | 中途失败时 |
|---|---|---|
| 内容删除（视频、文章、专题、演员、网址、附件） | 先删 `upload/` 下的图片文件，再 `DELETE` 行 | 文件已删、行还在（同旧版顺序；角色旧版先删行再查，图片从未被删，新版同样不删） |
| 友链批量、分类批量 | 逐行 `saveData` | 某行失败立即返回该提示，之前的行已保存（同旧版） |
| 字段修改带 `start/end`（随机值） | 每个编号一条 `UPDATE` | 前面的编号已改 |
| 查重删除 | 每组一条 `DELETE` | 前面的组已删；重试是安全的（按当前数据重算） |
| 批量处理（`ContentBatch`） | 每页逐行 `UPDATE`；删除时先删文件再按条件 `DELETE` | 已处理的页与行已生效；页面按旧版逐页推进，可从当前页重新开始 |
| 分类转移 | 每个原分类一条 `UPDATE`（视频或文章） | 前面的分类已转移 |
| 新增视频 | `INSERT` 后逐行 `UPDATE mac_vod_search` 追加编号 | 视频已保存，部分搜索结果未追加（下次搜索缓存过期后重算） |
| 上传 | 写文件 → 水印重写 → 缩略图 → 登记附件 | 文件已在磁盘、未登记附件（可用"附件初始化"补登记） |
| 附件检查、初始化 | 每个文件一条 `DELETE`/条件插入 | 已处理的文件已生效；重复执行安全（条件插入） |
| 头像登记附件（D-B09-9） | 头像写入与 `user_portrait` 更新成功后登记 | 登记失败只记日志，不影响上传结果（旧版不检查 `saveData` 的返回值） |

### 7. 各模块行为

#### 7.1 分类（`admin/controller/Type.php` → `common/model/Type.php`；`TypeAdminService`）

- 旧版的 `getCache('type_list'/'type_tree')` 新版每次从库里读（表很小；等同旧版每次保存后 `setCache`，`Type.php:283,305,324`）。
- 列表：`type_sort asc` 树形，附视频、文章、演员、网址的数据数（按 `type_id_1、type_id` 分组累加，后面的表覆盖前面的值，照旧）。
- 保存（`Type.php:233-285`）：名称必填 → `type_extend` 非空时 `json_encode` → 拼音 → `mac_filter_xss` → 有编号整行覆盖，否则新增。批量修改：`ids[]` 每项与 `type_name_<id>` 等组成一行，名称为空用 `lang('unknown')`；缺模板参数时是 null（"参数错误"，旧版 500）。
- 删除：视频分类查视频，其余（含演员、网址分类）查文章（旧版如此），有数据时"删除失败：<名称>还有<n>条数据…"。
- 扩展字典 `extend`：分类为空时依次取上级、旧配置（文章 `app.art_extend_class`，其余 `app.vod_extend_*`），逐项按逗号拆分；没有 `id` 输出空页面。
- 转移 `move`：目标按编号或拼音取，改视频/文章的 `type_id`、`type_id_1`；提示取语言包 `model/type/move_ok`（旧语言包这一条的文字是"转移失败"，照旧，`Type.php:347`）。

#### 7.2 视频（`admin/controller/Vod.php` → `common/model/Vod.php`；`VodAdminService`、`AdminPlayers`）

- 列表 `data`：分类（`type_id|type_id_1`）、推荐、状态、版权、完结、锁定、资源状态、地区、语言、剧情、播放地址为空、积分、图片（1 空、2 远程、3 同步失败 `#err`）、周期、关键字（名称/主演/副标题，`mac_filter_xss(urldecode())`）、播放器/下载器/服务器组；排序白名单；`repeat` 时查重。
- 保存（`Vod.php:626-733`）：名称、分类必填 → 共有步骤 → 截图、播放组与下载组（`vod_play_from[]` 非空时四个数组各以 `$$$` 连接，地址去换行、回车换 `#`；有组无地址 → "参数错误"，D-B13-3）→ `formatDataBeforeDb` → 有编号整行覆盖；新增时清空剧情、写添加时间，开启前台搜索优化（`app.vod_search_optimise` 含 `frontend`）时调用 `VodSearchCache.appendNewVod`：取命中次数最多的 50000 行，每行的每个字段在新视频里不区分大小写包含该词时把编号追加到这一行（`Vod.php:724-726`、`VodSearch.php:97-128`；新版每次读候选行、不做附带清理，D-B13-14）。
- 表单页：播放组、下载组按 `mac_play_list` 拆开（只列配置里启用的播放器/下载器），分集剧情按 `mac_plot_list`；选项来自 `AdminPlayers`。地址文本框的 id 用组序号（D-B13-10）。"查询资料"按钮不再请求外部站点（D-B13-15）。
- 分集剧情 `iplot`（`Vod.php:735-770` 的 `savePlot`）：先验证名称与分类；`vod_plot_name[]` 非空时剧情标记 1、名称与详情以 `$$$` 连接，否则清空；按提交的字段整行写；没有这条视频时"获取失败"（D-B13-9）。
- 删除：先删 `vod_pic`/`vod_pic_thumb`/`vod_pic_slide` 在 `upload/` 内的文件再删行；不删静态页（D-B13-22）。字段修改与批量见第 7.7 节；`updateToday` 返回今天（PRC 零点后）更新的编号或分类，去重以逗号连接。

#### 7.3 文章（`Art.php` → `common/model/Art.php`；`ArtAdminService`）

- 列表同视频的简化版（分类、推荐、状态、锁定、图片、关键字 `mac_filter_xss`），`repeat` 查重。
- 保存（`Art.php:444-547` 的 `saveData`）：分页正文 `art_content[]` 非空时把 `art_content[]`、`art_title[]`、`art_note[]` 各以 `$$$` 连接（不是数组 → "参数错误"）；正文图片协议换 `mac:`；图片为空时取正文第一张图；简介为空时取去掉 `$$$` 后的正文前 100 字。
- 删除、字段、批量（从最后一页往前）、`updateToday` 同视频。

#### 7.4 专题（`Topic.php` → `common/model/Topic.php`；`TopicAdminService`）

- 列表：`status` 严格等于 `'0'`/`'1'` 时筛选，`topic_time` 倒序。字段只能改 `topic_status`、`topic_level`，缺 `val` 时"参数错误"。
- 保存照 `Topic.php:342-427` 的步骤与顺序。新增时表单不带的 TEXT 列在严格模式下没有默认值，旧版 500，新版"参数错误"（黄金 `topic-save/new`，D-B13-3）——即严格模式的 MySQL 上旧后台本来就无法用该表单新增专题。
- 删除：先删三张图在 `upload/` 内的文件，再删行；旧版另删静态页（`Topic.php:449-450`），新版不删（D-B13-22）。

#### 7.5 演员与角色（`Actor.php`、`Role.php`；`ActorAdminService`、`RoleAdminService`）

- 演员：列表按更新时间倒序（页面上的"排序"下拉框旧控制器不读，照旧）；保存照 `common/model/Actor.php:377-472`；删除先删 `actor_pic` 再删行。
- 角色：列表可按所属视频 `rid` 筛选；表单按 `role_rid` 读视频显示；保存（`common/model/Role.php:311-365`）名称、演员必填，没有 xss 过滤与简介；删除时旧版先删行再查这些行删图片（`Role.php:367-376`），所以图片从未被删掉——新版同样不删文件。

#### 7.6 网址（`Website.php` → `common/model/Website.php`；`WebsiteAdminService`）

- 列表：分类、推荐、状态、锁定、图片、关键字（`htmlspecialchars(urldecode())`）；`repeat` 非空时只列同名超过一条的。
- 保存：控制器先把 `website_content` 里的 `协议:` 换成 `mac:`（不论是否在图片地址里，照旧），再 `saveData`（`Website.php:469-544`）；截图字段去换行、回车换 `#`。
- 删除先删 `website_pic`；批量按页码顺序（视频、文章是从最后一页往前）；解锁提示键旧代码写成 `ublock`（语言包没有，输出键名原文，照旧）。

#### 7.7 批量处理（`ContentBatch`；旧 `Vod/Art/Website::batch`）

- 没有任何参数时显示筛选表单（`vod/batch.jte` 等）；否则按筛选条件逐页处理，每页最多 `limit`（默认 100）条，`mac_echo` 每条的结果，3 秒后跳到下一页（参数原样带上、页码 +1，首页补 `total`、`page_count`）。`limit` 非数字或负数时"参数错误"（旧版除零或取负数报错，D-B13-18）。
- 勾选项：推荐、状态、锁定、人气区间（视频 `!= ''`、文章与网址 `!empty()`，照旧不一致）、视频另有积分、版权、删播放组/下载组（`ck_del=2/3` 时必须选播放器/下载器，`Vod.php:150-160`）；`ck_del=1` 一次删完全部符合条件的行（先删图片）。
- 处理完的提示：视频、网址 `multi_opt_ok`，文章 `multi_set_ok`（照旧）。

#### 7.8 友情链接（`Link.php` → `common/model/Link.php`；`LinkAdminService`）

- 列表：`link_name LIKE '%' . htmlspecialchars(urldecode(wd)) . '%'`，`link_id` 倒序。保存（`Link.php:99-120`）：名称、网址必填，写 `link_time`，新增另写 `link_add_time`。批量：`ids[]` 与同位置的 `link_name[] link_sort[] link_url[] link_type[] link_logo[]` 组成一行；没有 `ids[]` 时"参数错误"（D-B13-4）。
- 旧表单的"类型"下拉框把 selected 判断写成未定义的 `$vo`（`application/admin/view/link/info.html:34-35`，应为 `$info`），`null eq 0` 为真，编辑图片链接时也总是选中"文字链接"。**新版照旧**（`link/info.jte`、`link-info.js` 注释）：保存时若不改下拉框，图片链接会被存回文字链接——这是旧版缺陷，按"行为兼容"保留。已定（2026-10-26，MACC-23）：保持旧行为，不修。

#### 7.9 评论与留言（`Comment.php`、`Gbook.php` → `common/model/Comment.php`、`Gbook.php`；`CommentAdminService`）

- 评论列表：状态（严格 `'0'/'1'`）、模块（1/2/3）、用户、举报（1 未举报，其他已举报）、关键字（昵称或内容），编号倒序；每条另取所属内容（视频、文章、专题、演员、角色、网址的整行；取不到显示"删除数据"）。旧版另取的回复与头像页面不用，新版不读。
- 留言列表：状态、类型（1 留言、2 报错）、已回复、用户、关键字。
- 保存（`input()`）：评论昵称、内容、模块、对象必填；留言昵称、内容必填，回复非空时写回复时间；`mac_filter_xss`；有编号整行覆盖，否则新增。删除：`all == 1` 清空（`Comment.php:77-97`），否则按 `ids`；审核状态只接受 `comment_status`/`gbook_status`。
- 取值规则与 content 相同（复用 `AdminTable.COMMENT/GBOOK`、`AdminRowValues`）。

#### 7.10 附件、图片与上传（`AnnexAdminService`、`AdminUploadService`、`LegacyImages`、`UploadStore`、D-B09-9）

- **`UploadStore`**：根目录为 `maccms.upload.dir`；库里的地址是相对站点根的 `upload/…`，只处理规范化后仍在根目录内的路径（拒绝 `\`、`:`、NUL；D-B13-8）；列目录不含点开头的项、按字节序。
- **附件**（`admin/controller/Annex.php`）：列表按类型、文件名关键字，`annex_time` 倒序（不加 `mid` 条件，D-B13-11）；保存文件名必填；删除按编号或文件名，连同 `upload/` 下的文件（含 `../` 的不删文件，同 `common/model/Annex.php:83-85`）；目录浏览 `file` 以 `@` 作分隔，含 `..@` 时"非法请求"，只列 `upload/` 之内（D-B13-8）；检查 `check` 每页 500 条从最后一页往前，文件已不存在的登记按文件名删除（D-B13-12）；初始化 `init` 依次处理 `actor art topic type vod website actor role`（演员两次，照旧），把图片列与正文里 `/upload` 开头且存在的图片补登记（D-B13-13）。
- **图片**（`admin/controller/Images.php`）：`opt` 显示选项页；`del` 删除 `ids[]` 里以 `./upload` 开头且只有一个 `./` 的文件，总是"删除成功"（`ids` 不是数组时"参数错误"，D-B13-18）；`sync` 不下载（D-B13-2）；`data` 为空页面（旧版空方法）。
- **上传**（`common/model/Upload.php:38-286`）：编辑器（`from`：Ueditor/Umeditor/Ckeditor/Kindeditor/Tinymce）按各自格式返回，`action=config` 输出 `static/ueditor/config.json` 去注释后的 JSON；目录 `upload/<flag>/<Ymd>-<n>/`（目录里名称含点的文件超过 999 个换下一个，最多到 100），文件名为随机 32 位十六进制加原扩展名；按扩展名分图片、文件、媒体；以 `<?php` 开头的文件拒绝；开头与结尾各 512 字节的十六进制匹配脚本标记时拒绝（检查通过才写盘，D-B13-16）；`flag` 只接受 `[A-Za-z0-9_-]{1,64}`（D-B13-8）；`flag=user`（会员头像）不进本服务：会员表单的上传（不带编辑器 `from`）由 B14 的会员服务处理（23 文档第 7.7 节），编辑器带 `flag=user` 的请求照旧拒绝（D-B13-17）；图片按配置加文字水印（字体为 `watermark_font`，为空时用旧版自带的 `static/font/test.ttf`）、生成缩略图（`thumb_size` 各尺寸，缩略图也加水印、位置 9）；最后把 `upload` 开头的文件登记为附件，新登记了原图时再登记第一张缩略图。`upload.mode` 不论取值都存本地（D-B13-2）。`imgdata`（data URL）照旧不加扩展名，旧版用 `file_put_contents` 写入、不建目录，当天目录还不存在时"文件上传失败！"，新版照旧（黄金 `upload/imgdata-new-dir`；有文件上传建出目录后成功，`upload/imgdata-ok`）。
- **上传弹窗页与写入测试**：`upload/index` 照旧模板只输出表单片段（不带后台外壳），`path`、`id` 写进隐藏字段（转义，D-B13-23）；旧模板不闭合 body 标签，jte 要求闭合，末尾补上（唯一的结构适配）。`upload/test` 在系统临时目录建 `Tux` 前缀的临时文件并输出语言包 `admin/upload/test_write_ok` + "：" + 路径（旧语言包这个键写了两次，生效的是后一条"写入失败，请检查临时文件目录权限"，照旧）。
- **`LegacyImages`**：think-image 的 `thumb()`（六种缩略方式）与 `text()`（九个位置）的几何计算照旧，像素由 Java2D 生成，JPEG 质量 80；与 GD 的输出**不保证逐字节相同**；读不了的图片返回 null（旧版异常被吞掉，余下尺寸不再生成）。水印按 `imagettfbbox` 的字形外框定位（`x = minx`、`y = |miny|`、宽高取整到整像素，绘制坐标取整，同 `imagettftext`）；补证时由黄金对照改正，原先按字体行高与步进宽度定位。
- **多个缩略图尺寸是链式缩出来的**（补证时由黄金查出并改正）：旧 `common/model/Image::makethumb` 只打开一次原图，同一个 think\Image 对象依次 `thumb()->save()`，开水印时改成打开刚存的缩略图加水印，所以后面的尺寸从前一张缩略图（开水印时是加过水印的那张）再缩：`120x80,300x300` 按等比例缩放时两张都是 120×67。`AdminUploadService` 原先每个尺寸都从原图缩，现照旧链式缩放。
- **D-B09-9 头像登记附件**：前台头像写入并更新 `user_portrait` 成功后，`MemberAccountService.registerAnnex` 经 `PortraitAnnex` → `AnnexAdminService.register` 登记 `upload/user/<id%10>/<id>.jpg`：类型照旧 `Upload.php:118-119、144-152`——`imgdata` 时取 data URL 里的图片类型，文件上传按扩展名分 `image`/`file`/`media`（扩展名属于"文件"或"媒体"类而内容是图片时，旧版照样生成头像、登记为 `file`/`media`；补证时由 PHP 黄金查出，原先一律写 `image`，已改正，`PortraitImages.annexType`）；大小按新版重新编码后的 JPEG（KB 两位小数，写库取整）；同一文件已登记时不重复；可写数据源关闭或没有附件服务时不登记，登记失败只记日志。测试：**`PortraitAnnexGoldenMySqlIT` 与 PHP 实跑黄金逐列对照**（`backend/src/test/php/portrait_annex_golden.php` + `portrait_annex_child.php`：php-cgi 跑完整的 `index.php/user/portrait`，含 think-image 缩略图、`UPDATE mac_user`、`Annex::infoData/saveData`；PHP 8.0 与 7.3 各 17 个场景 18 步）：`mac_annex` 除 `annex_size` 外逐列、`mac_user` 全部列、`upload/` 路径与 PHP 相同，新版 `annex_size` 等于新版 JPEG 的 KB；有意差异只有 B09 已登记的两类（PHP 8 上 think-image 的 GIF 解码器致命错误按 PHP 7.3 的结果，D-B09-14；扩展名合法而内容不是图片时旧版 500、新版"文件上传失败！"，两边都不落盘不写库）。另有 `MemberAccountGoldenMySqlIT` ⑥（文件、类型、大小）与 `AdminContentGoldenMySqlIT` ②（同名不重复、整数列取整）。

### 8. 页面：复用旧后台结构

#### 8.1 jte 页面

`backend/src/main/jte-admin/admin/pages/` 下 30 个页面与 2 个片段，逐个转自旧模板，DOM、类名、`lay-*`、`data-*`、表单字段不变，`static/layui/**`、`admin_style.css`、`admin_common.js`、ueditor 原样复用：

| 新模板 | 旧模板（`application/admin/view/`） |
|---|---|
| `link/index、info` `type/index、info` `topic/index、info` `actor/index、info` `role/index、info` `comment/index、info` `gbook/index、info` | 同名 `.html` |
| `website/index、info、batch` `art/index、info、batch` `vod/index、info、batch、iplot` | 同名 `.html` |
| `annex/index、info、file、init` `images/opt` | 同名 `.html` |
| `select.jte` | `public/select_type|level|hits|status|lock|state|copyright.html`（7 个，D-B13-6） |
| `partials/editor.jte` | `public/editor.html`（仓库只有 ueditor，旧逻辑在 `static/editor/<编辑器>` 不存在时也用 ueditor，`public/editor.html:4-11`） |
| `partials/typeOptions.jte` | 各列表、表单里的分类下拉框 |
| `upload/index` | 同名 `.html`（表单片段；末尾补上闭合的 body 标签） |

- 内联脚本逐行移到 `backend/src/main/resources/static/admin-assets/`（本批新增 15 个：`actor-info` `art-info` `batch-form` `content-verify` `editor` `link-info` `progress` `role-info` `select-return` `topic-info` `type-info` `vod-index` `vod-info` `vod-iplot` `website-info`），服务端写入的地址与语言包文字改由 script 标签的 `data-*` 提供；拼进 HTML 的选项、扩展项、截图地址先转义（旧版原样拼接；显示不变，D-B13-19）。`static/admin-assets/` 按 B12 的决定由后端维护。
- **浏览器走查查出、补证时改正的两处运行适配**（不改旧文件，只在新版的加载层处理）：① ueditor 的 `render` 用 `javascript:` 地址的 iframe 写入编辑区文档、文档里的内联脚本再调用 `editor._setup(document)`，两者都被后台的内容安全策略（21 文档第 8.2 节）拦截，编辑区空白、编辑器永不就绪、保存时取不到正文；`admin-assets/editor.js` 在 `render` 建这个 iframe 时去掉 `javascript:` 地址，取出它要写的同一份文档（去掉那段内联脚本）自行写入并调用 `_setup`，与原脚本做的事相同；② 取色器 `jquery.jscolor.js` 读取的 `static/images/jscolor/` 图片原先没有打包，`pom.xml` 补上这一目录（按旧路径提供）。
- `AdminTemplatePolicyTest` 以 B12 的后台策略重新编译全部模板（禁止内联事件、`$unsafe`、外部主机等）；本批只放宽一处：`style="${条件 ? "字面量" : null}"` 这类两个分支都是不含输出的字面量的写法（旧模板 `{if …}style="display:none"{/if}`，`STYLE_CHOICE`）。

#### 8.2 `BrowserText` / `RawOutput`：旧版原样输出的值

旧模板（ThinkPHP 默认不转义）原样输出库里的文字，浏览器会把其中的字符引用解码一次（如库里存的 `HD&amp;蓝光` 显示为 `HD&蓝光`，表单提交回去的也是解码后的值）。新版先按浏览器规则（jsoup `Parser.unescapeEntities`，属性值与文本略有不同）算出浏览器看到的值，再由模板转义输出：显示与提交的值与旧版相同，文字里的标签不再被当作标签解析（D-B13-19）。`RawOutput.v/t` 用于表单行的 `{$info.x}`；列表、`ImgConfig`（图片地址里的 `&amp;`）同理。

#### 8.3 `FrontLinks`：指向前台的链接

列表里的"浏览"、分类链接等对应旧 `mac_url_type`、`mac_url_*_detail`：由站点模块的纯函数 `LegacyUrlBuilder` 按旧配置 `rewrite.status/suffix_hide/route_status` 与 `app.pathinfo_depr` 的地址形态生成，前面加部署前缀（旧 `MAC_PATH`）。只支持数字编号的地址；不支持的组合按仓库默认形态并告警；静态页地址在 B16。这是 admin 引用 site 的唯一例外（第 2 节 `ADMIN_SITE`）。

#### 8.4 逐页推进的输出（`AdminProgress`、`progress.js`）

批量处理、附件检查与初始化、图片同步提示按旧 `mac_echo`（每段后接 `<br>`）与 `mac_jump`（3 秒后跳到下一页）输出。旧代码写死的 HTML（样式段、`<font color=…>`、`&nbsp;`）原样，数据（名称、文件名）转义；跳转的内联脚本改为 `progress.js` 读 `data-url`、`data-seconds`，"如果您的浏览器没有自动跳转，请点击这里"链接照旧；中途 `error()` 时在已输出的内容后接错误页（Ajax 为 JSON）。

**模板与功能之间的缺口**（按用户规则登记）：视频表单"查询资料"按钮保留但只提示"请求失败"（D-B13-15）；图片同步页保留、只给固定提示（D-B13-2）。旧模板 `upload/index.html` 不闭合 body 标签，jte 版补上（第 7.10 节）。

### 9. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金（PHP 8.0.30 与 7.3.33） | `backend/src/test/php/admin_content_golden.php` + `admin_content_child.php`：一次性库、php-cgi 跑完整的 `_admin.php` + ThinkPHP + 控制器，每个场景写入自己的行与临时站点根目录 `upload/` 文件；记录每个请求的状态码、头、正文与请求后变化的表的全部行、`upload/` 文件清单。两版各 57 个场景、351 个请求步骤（含本轮补的上传成功路径：图片、文档、编辑器上传、`imgdata` 新目录失败与成功、上传弹窗页、写入测试） → `backend/src/test/resources/admin/content/php{80,73}-golden.json` |
| 黄金复放（真实 MySQL） | `AdminContentGoldenMySqlIT.scenariosMatchPhpGolden`（`./mvnw verify -Pmysql-it -Dit.test=AdminContentGoldenMySqlIT`）：按场景的旧配置各启动一个打开可写数据源与后台的应用上下文，每步把时钟拨到 PHP 请求开始的秒，比较状态码、Location、JSON 字节、页面结构（`AdminContentHtml`，PHP 与新版同一套取法）、请求后 12 张表的全部行与 `upload/` 文件；两版共 **702 步**，除 `JAVA`/`JAVA_PATCH`/`JAVA_NO_WRITE` 逐条断言的有意差异（D-B13-3、-4、-9、-10）外 0 处不同。上传文件名两边各自随机（旧 `md5(microtime(true))`），按响应里出现的顺序把 PHP 的名称对应到新版的名称后再比较正文、行与文件；上传结果里的 `ctime` 与表里的时间列同样按请求时间窗比较；`upload/test` 输出里的临时文件路径因机器而异，只比到"："为止 |
| 并发 ② | 同一测试类 Order 2～6：12 个管理员同时保存同一视频的不同表单 → 全部成功、库里恰好是其中一份完整表单；后台保存覆盖计数（打开表单后的 5 次点击被表单值覆盖），1 次保存与 15 次并发点击 → 点击数落在 [55, 70]、无出错；8 个并发同名新增全部保存且被查重列出，6 个并发查重删除全部成功、每个名称只剩最小编号；16 个并发登记同一文件 → 只 1 行、只 1 次报告新登记（`annex_size` 12.5 → 13），16 个不同文件互不影响；友链、评论、留言各 8 个、共 24 个并发删除同一批编号 → 全部成功、只删这些行 |
| 回滚演练 ③ | Order 7：快照 12 张表 → 经各服务做 14 次 B13 写入（友链增改删、分类字段、视频增改字段删、文章编辑、评论与留言的修改和删除、附件登记）→ `DmlRestore` 以 15 条 DML 恢复 → 逐行 diff 为空 → 表结构指纹与导入 install.sql 时相同 |
| 一次性库 | `maccms_it_<yyyyMMddHHmmss>_<8 位十六进制>`（名称先校验），结束时删库并确认不存在；不连 `maccms_dev` |
| 文本函数 | `PhpTextGoldenTest`（两版各 1604 个用例，第 4.2 节） |
| 护栏 | `WriteGuardTest`（登记表 51 项与写入方矩阵）、`WriteRegistryTest`（B13 的允许与不允许项，含 `member` 不能写 `mac_annex`）、`ReadOnlyGuardrailsTest`（新的读 Mapper 只有 SELECT）、`ModuleBoundaryTest`（`ADMIN_SITE`）、`AdminTemplatePolicyTest`（全部后台模板与 `admin-assets`）、`SchemaGuardTest`、`OutboundNetworkGuardTest` |
| 列清单 | `AdminTableSchemaTest`（第 3 节） |
| 头像登记附件（D-B09-9） | `PortraitAnnexGoldenMySqlIT`：PHP 8.0 与 7.3 实跑黄金 `backend/src/test/resources/member/portrait-annex-php{80,73}-golden.json`，两版共 36 步、登记 18 行，`mac_annex` 除 `annex_size` 外逐列一致（第 7.10 节）；`MemberAccountGoldenMySqlIT` ⑥ |
| 缩略图与水印（几何） | `backend/src/test/php/legacy_images_golden.php` 直接调用 `vendor/` 的 think-image 与旧 `Image::makethumb`（PHP 8.0 与 7.3）→ `backend/src/test/resources/content/admin/legacy-images-php{80,73}-golden.json`。`LegacyImagesGoldenTest`：180 个缩略图（PNG/JPEG × 5 种源尺寸 × 3 种目标尺寸 × 六种方式）宽高与 3×3 网格类别逐个相同；18 个水印（九个位置 × 两种尺寸）红色像素外接矩形横向与宽高完全相同、上下两排纵向差 1 像素（容差 1，字形由 FreeType 与 Java2D 各自栅格化）。`AdminUploadThumbGoldenTest`：经完整的 `AdminUploadService.upload`，12 组多尺寸 × 方式 × 水印开关的每张缩略图宽高与网格同 `makethumb` |
| 浏览器走查 | `backend/src/test/browser/b13_admin_content_check.py`（Playwright 本机 Chromium 无头）：一次性库 `maccms_it_*`、临时旧配置（开缩略图与水印）、临时站点根 `upload/`、本机 jar 与只服务本次的 nginx（自带前缀与配置，不动共用的 `D:/Tools/nginx`）。47 项全部通过：登录与框架页；视频列表、添加弹层（layui 下拉、ueditor 渲染）、上传选图（地址与缩略图地址回填、原图与缩略图都登记附件）、保存、`j-select` 批量改等级、编辑、查重与"保留小ID"删除、勾选删除（图片文件随之删除）；文章 ueditor 输入正文保存；友链"类型"下拉框照旧缺陷；分类树与编辑（表单令牌）；评论审核、留言回复；附件列表、目录逐级进入与返回、附件检查逐页推进（1104 条登记跨到第 2 页以后）并删掉文件已不存在的登记、附件初始化逐表推进与表内翻页（1101 行）、补登记不重复；视频批量处理逐页推进到完成；含脚本标记的图片被拒绝且不落盘不登记；图片同步合法 `tab` 固定提示且不改库；全程内容安全策略违规、页面脚本错误、控制台错误、对外部主机的请求、4xx/5xx 响应均为 0（补证版 jar 上重跑）。走查查出的三处问题（ueditor、取色器图片、链式缩略图）已改正（第 7.10、8.1 节） |
| 随 B13 更新的已有 IT | `AdminGoldenMySqlIT`（B12）：去掉 `auth-editor/vod/data` 的"501 由 B13 提供"预期，改为与 PHP 黄金比较（视频列表页，通过）；`WriteInfrastructureMySqlIT`：生产登记表的断言加上 B13 的 content/comment 项 |
| 仍为 B12 的测试 | `AdminMvcTest`（替身上下文没有内容服务时 B13 动作为"由 B13 批次提供"）、`AdminPermissionsGoldenTest`（B13 权限码的批次归属）——本批未改 |
| 测试总数 | 见交付评论（补证版的 `./mvnw verify -Pmysql-it` 结果）。计数口径：Maven 控制台的 `Tests run` 合计与 surefire XML 的 `<testcase>` 个数相同（如交付版 2639）；各 XML 文件 `tests` 属性的合计少 1（2638），因为 `SiteSearchSwitchMvcTest` 的两个 `@Nested` 类各有一个同名方法 `searchControllerIsNotRegistered`，surefire 合并进同一个 XML 时 `tests` 属性按方法名计为 8（实际 9 个 `<testcase>`）——两种口径跑的是同一组测试 |

未覆盖或只间接覆盖的（第 12 节）：水印与缩略图的像素（只比几何）；上传的脚本标记拒绝（D-B13-16）、`images/sync` 的合法 `tab`、附件检查与初始化的多页只有浏览器走查，没有 PHP 黄金对照（旧版拒绝后留下文件，无法直接对照）。

### 10. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B13-1 | 附件登记"先按文件名查、没有再插入"（`Upload.php:264-270`、`Annex.php:335-345`），并发登记同一文件得到多行 | 一条条件插入，只登记一次（第 6 节） | 并发正确性 |
| D-B13-2 | `images/sync` 下载远程图片并改写库内地址（`Images.php:45` 起，经 `Image::down_load`）；上传在 `upload.mode` 为 2～5 时转存到 upyun/qiniu/ftp/weibo 并返回远程地址（`Upload.php:229-249`） | `images/sync`：`tab` 不在六类里"参数错误"，否则输出固定提示"远程图片同步未开放：新系统只存本地、不下载外部图片，库内的图片地址保持不变。"，不读写库；上传不看 `upload.mode`，一律存 `upload/` 并返回本地地址 | 用户决定 D1（E02、E08 只做本地） |
| D-B13-3 | MySQL 严格模式拒绝的取值（超长、4 字节字符、非数字、NULL、缺默认值的 TEXT 列、不存在的分类）整页 500；PHP 8 下有播放组无地址 500，PHP 7.3 照常保存为空地址 | 写库前或由数据错误码归为"参数错误"，不写库 | 非法参数 |
| D-B13-4 | `ids` 交给 MySQL 隐式转换（`abc` 当 0、"删除成功"）；友链批量没有 `ids[]` 时以未定义的 `$res` 输出 `code=1、msg=null` | 每项必须是十进制数字、最多 1000 项；没有 `ids[]` → "参数错误" | 非法参数（同 D-B12-10） |
| D-B13-5 | 简介截断 `mac_substring` 在 PHP 8 下把 ord 64-127 的字符换成空格 | 按 PHP 7.3 的结果 | 不复刻版本缺陷（14 文档第 6 节已记录） |
| D-B13-6 | `index/select` 把 `tpl` 直接拼进模板路径（不存在时报错） | 只接受旧版的 7 个 `select_*` 模板；`tab`、`col`、`url` 为数组时"参数错误" | 加固 |
| D-B13-7 | 查重第 1 页 `DROP/CREATE TABLE mac_tmp*`（MyISAM 临时表）再按它列出与删除 | 子查询；删除按当前数据逐组进行 | 零结构变更（路线图验收） |
| D-B13-8 | 上传的 `flag` 直接拼进路径（`../` 可写到目录外）；`annex/file` 的 `@upload@..` 列出站点根目录；删除内容图片按 `./upload` 前缀判断，`upload/../x` 可删到目录外 | `flag` 只接受字母、数字、`_`、`-`；只列、只删规范化后仍在 `upload/` 之内的文件 | 安全加固 |
| D-B13-9 | 分集剧情页没有这条视频时取 null 的下标报错（500） | "获取失败" | 非法参数 |
| D-B13-10 | 视频表单内层服务器组循环覆盖了 `$key`，播放/下载地址文本框 id 写成"前缀 + 最后一个服务器组"（多组时重复） | id 为组序号 | 模板缺陷（DOM 钩子唯一） |
| D-B13-11 | 附件列表 `mid` 非空时加 `annex_mid` 条件（`Annex.php:23-25`），表里没有这一列，SQL 报错 | 不加这个条件 | 缺陷 |
| D-B13-12 | 附件检查第 2 页起引用未定义的 `$where`（只在 `Annex.php:193-201` 里赋值，第 211 行使用），报错 500 | 每页都按全部登记行处理 | 缺陷 |
| D-B13-13 | 附件初始化读表里没有的列时报错（500）；`insertAll($insert)` 写在逐行循环里（`Annex.php:345`），同一页先登记的图片被反复再插入 | 只取表里有的列；每个文件只登记一次（条件插入） | 缺陷 |
| D-B13-14 | 新增视频时搜索结果候选行缓存 1 小时或 1 天，缓存失效时顺带 `clearOldResult()`（`VodSearch.php:103-110`） | 每次新增都读候选行，不做附带清理（过期清理在 B12 的"清理缓存"） | 无缓存驱动 |
| D-B13-15 | 视频表单"查询资料"以 JSONP 请求 `api.maccms.la`（`vod/info.html:714-720`） | 不发请求；校验豆瓣编号后直接提示"请求失败" | 外部调用不在白名单（路线图第 4 节） |
| D-B13-16 | 上传先落盘再做脚本标记检查，危险文件留在磁盘上（`Upload.php:153,162-175`）；PHP 文件由 finfo 判定 `text/x-php` | 检查通过才写盘；PHP 文件按"去掉前导空白后以 `<?php` 开头"判定（与 finfo 不完全相同【待核实边界样本】） | 安全加固 |
| D-B13-17 | 后台上传 `flag=user` 按会员头像处理（`Upload.php:79-88,188-213`），写 `upload/user/<user_id%10>/<user_id>.jpg` 并登记附件；`$GLOBALS['user']` 在后台不存在，`UPDATE mac_user` 不命中（PHP 实跑黄金，23 文档第 7.7 节） | **2026-10-26 起（B14 返工，MACC-38）**：会员表单的"上传图片"（不带编辑器 `from`）由 B14 的会员服务实现，行为与差异见 23 文档第 7.7 节、D-B14-10；本服务只对编辑器带 `flag=user` 的请求拒绝（"文件上传失败！"，旧版编辑器界面没有这种请求）。B13 交付时"后台一律拒绝"的处理只是临时状态，不是永久删除 | 旧功能补齐（队长 2026-10-26 验收意见） |
| D-B13-18 | `images/del` 的 `ids` 不是数组时 foreach 报错；批量 `limit` 非数字或负数除零或报错；数组参数被 `explode` 报错 | "参数错误" | 非法参数 |
| D-B13-19 | 页面与逐页输出里原样输出库里的文字；内联脚本把选项、扩展项、截图地址原样拼进 HTML | 按浏览器看到的值转义输出（第 8.2 节）；脚本外移、拼接前转义；显示与提交的值不变 | 加固（同 D-B12-5、-17） |
| D-B13-20 | 同序时顺序由 MySQL 决定；updateToday 的 `column()` 不排序 | 补主键使顺序确定；updateToday 按主键 | 确定性 |
| D-B13-21 | `page`、`limit` 原值写进分页脚本，每页条数不限 | 按 `intval`，每页最多 1000，页码超过一百万返回空页 | 加固（同 D-B12-11） |
| D-B13-22 | 删除视频、专题等时 `view.*_detail == 2` 另删静态页文件（`Vod.php:772-806`、`Topic.php:449-450`） | 不删（新系统没有静态页） | 静态页在 B16 |
| D-B13-23 | `upload/index` 把 `path`、`id` 原样写进隐藏字段的 value（含双引号时属性被截断）；`upload/test` 用 `tempnam` 建的临时文件留在临时目录里 | 转义输出；临时文件输出后删除 | 加固 |
| — | 编辑时整行覆盖含计数列，打开表单后的前台点击被覆盖 | 相同 | **与旧版一致**（路线图验收、03 草稿第 3.1 节） |
| — | 友链表单"类型"下拉框总选中"文字链接"（`$vo` 缺陷） | 相同 | 行为兼容（第 7.8 节） |

### 11. 回滚

- **代码**：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到上一 main 快照再跑 `./mvnw verify`。不涉及表结构、数据迁移或配置格式。
- **数据**：写入只有对现有列的 DML（第 3 节），回滚演练（第 9 节 ③）证明全部可用 DML 恢复；没有临时表、没有 DDL。
- **文件不在 DML 范围内**：上传的文件、缩略图与水印重写、删除内容时删掉的图片、附件删除与"图片删除"删掉的文件，都无法从数据库恢复。**切换与回滚前先备份 `upload/`**（含 B09 的头像目录，它们是同一个目录）。新版写入的地址、附件行与旧版格式相同，切回 PHP 后照常读取。

### 12. 风险与遗留

| 项 | 说明 |
|---|---|
| MyISAM 没有事务 | 第 6 节所列多语句操作可能部分生效（先删文件后删行、逐行批量、逐组查重删除、新增视频后追加搜索结果）；与旧版相同，重试大多安全 |
| 整行覆盖丢并发计数 | 打开表单到保存之间的前台点击、顶踩、评分被表单值覆盖；按路线图决定与旧版一致，未改 |
| 图片像素差异 | `LegacyImages` 用 Java2D：缩略图几何与 think-image 逐个相同，水印位置纵向可差 1 像素（第 9 节）；像素与 GD 不保证相同，水印字体渲染、JPEG 压缩结果会有差别 |
| 只有浏览器走查的路径 | 上传的脚本标记拒绝、`images/sync` 合法 `tab`、附件检查多页、附件初始化多页：浏览器走查通过（第 9 节），没有 PHP 黄金对照 |
| 浏览器走查的范围 | 只在本机无头 Chromium 上走了第 9 节所列流程；专题、演员、角色、网址、分集剧情页只有黄金的页面结构对照，没有逐页点过；没有在其他浏览器上走查 |
| 两个上传目录参数 | `maccms.upload.dir` 与 `maccms.member.upload-dir` 必须指向同一目录，配错时头像与附件不在一处 |
| 友链类型下拉框 | 旧缺陷照旧：不改下拉框保存会把图片链接改回文字链接；已定保持旧行为（2026-10-26，MACC-23），编辑图片链接时需手动选回"图片链接" |
| 旧配置每次调用都读 | `AdminConfig`、`AdminPlayers` 每次调用读文件（按修改时间缓存）；读失败时按空值处理并告警，表单的默认值会随之变化 |
