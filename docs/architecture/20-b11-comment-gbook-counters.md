## B11 评论、留言与内容计数写入（路线图 B11，MACC-35）

> **状态：实施完成，待独立验收。** 由 Claude 实施后端与页面接线，做法同 B10：前台沿用现有极简样式，不另做视觉设计，不启用 Kimi。B11 也按此执行需要队长确认，见第 12 节。
> 依据：路线图 `09-roadmap.md` 第 5.3 节的 B11 行与写入方矩阵；B07 `12-write-infrastructure.md`（写 SQL 规范、回滚演练工具）；B08 `17-b08-member-session-captcha.md`（会话、验证码）；B09 `18-b09-member-account.md`（参数的字节语义、会员判定）；B10 `19-b10-member-finance.md`（非 ajax 提示页、写库被拒绝时的返回、`mac_visit` 条件插入）。旧代码位置一律相对仓库根目录。

### 0. 起点、分支与恢复

| 项 | 值 |
|---|---|
| 已验收 main | `6849addec0e53b81626d5e0e0086ff233006b7b5`（B10 验收后的路线图更新） |
| 分支 | `agent/claude/macc-35-0f0a2766c262`（本轮平台分支；只用普通提交，不 reset、rebase、amend、force，不自行合并 main） |
| 平台基线 | `14b676d`：主仓库暂存区的旧快照，**缺 B10**，不能作为代码起点。平台要求保留这个起点，所以第一个提交 `284a12c` 叠在它上面，把树恢复成 `6849add` + B11 的改动 |
| 恢复来源 | 上一轮在保留工作树中的未完成提交 `28b6fa9`：它基于 `6849add`，但不含那一轮的平台基线 `32d8db4e`，平台因此拒收。本轮把它的树原样恢复，只去掉误入的 `.baseline-verify.log` |
| 另一条线 | `agent/claude/macc-35-b11`（`82b2213`，MACC-23 工作树里同一时间并行跑出的另一份 B11 实现，模块叫 `interact`，有契约稿，没有页面和 MySQL 集成测试）。**本批不采用、不合并**，只参考了它对 `SiteSearch*MvcTest` 的同类修改 |

合并到 main 时，相对 `6849add` 的改动只有 B11（`14b676d` 的回退已被 `284a12c` 抵消），可用 `git diff 6849add <交付提交>` 核对。

### 1. 范围

| 在本批 | 不在本批（归属） |
|---|---|
| 评论：`comment/index`（页面，POST 即发表）、`comment/ajax`（列表片段）、`comment/saveData`、`comment/report`（举报）、`comment/digg`（顶踩） | 后台评论/留言管理、审核、回复留言（**B13**） |
| 留言：`gbook/index`（页面，POST 即发表）、`gbook/ajax`、`gbook/report`（报错页）、`gbook/saveData`；`route_status` 打开时的旧路由 `gbook`、`gbook-<页码>` | 采集评论入库（**B19**） |
| `ajax/hits`（含日/周/月重置）、`ajax/digg`（含 mid=4 评论）、`ajax/score`、`ajax/referer`（网址来路计数，加游客来路记录 `mac_visit`） | `updateToday`：旧代码里是只读的后台查询（`application/common/model/Vod.php:829-847`，只被后台控制器调用），不是点击重置。日/周/月的重置在 `ajax/hits`、`ajax/referer` 里，本批已实现；`updateToday` 归 **B13/B16**。路线图 B11 行把它写成"日/周/月点击重置"是笔误（第 10.3 节） |
| `ajax/verify_check`，以及据此恢复 `search_verify`、`show_verify`（结束 B05 起的"临时关闭"） | `ajax/pwd`（**B05** 已按 POST 加签名票据实现）、`ajax/suggest`（**B06** 已实现） |
| Provide `detail_inc_hits`（补上 B06 起的临时差异） | 新增视频或采集时更新搜索缓存（**B13/B19**） |
| `vod_search_optimise` 含 `frontend` 时，站点视频搜索页、筛选页读写 `mac_vod_search` | 后台"清理搜索缓存"（**B12/B15**） |
| 会员中心 `user/comment`、`user/gbook`（我的评论、我的留言） | 播放页上的计数（旧模板缺失，播放页有没有 `.mac_hits` 无从核实，D9-a） |
| 内容详情页的互动区：视频、文章、专题、演员、角色、网址 | |

### 2. 路由与通用规则

地址形态与 B05、B09 相同：`/index.php/<控制器>/<操作>[.html|.htm|.shtm|.shtml|.xml][/键/值…]`。`rewrite` 打开时另认去掉 `/index.php` 的形态。路径按 PATH_INFO 解码一次，操作名不区分大小写（ThinkPHP `url_convert`）。

| 地址 | 方法 | 响应 |
|---|---|---|
| `comment/index` | GET/HEAD；POST 同 `saveData` | 评论页（第 8 节） |
| `comment/ajax` | 任意 | 评论列表片段（无外壳） |
| `comment/saveData` | 任意（旧版不区分） | ajax → JSON；非 ajax → 提示页（D-B11-10） |
| `comment/report`、`comment/digg` | 任意 | ajax 或非 POST → JSON；非 ajax 的 POST → 提示页（新版页面上的无脚本表单，D-B11-10） |
| `gbook/index` | GET/HEAD；POST 同 `saveData` | 留言页 |
| `gbook/ajax` | 任意 | 留言列表片段 |
| `gbook/report` | GET/HEAD | 报错页（留言表单，`gbook_rid` 为被报错的内容编号） |
| `gbook/saveData` | 任意 | 同评论 |
| `ajax/hits`、`ajax/digg`、`ajax/score`、`ajax/referer` | 任意 | ajax 或非 POST → JSON（旧版 `json()`）；非 ajax 的 POST → 提示页 |
| `ajax/verify_check` | 任意 | ajax → JSON；非 ajax → 提示页（旧版非 ajax 时返回数组，500） |
| `user/comment`、`user/gbook` | GET/HEAD | 会员中心列表页（需登录，同 B10） |

- **参数**：评论、留言的提交与列表按 ThinkPHP `input()` 按字节合并（B09 第 3.1 节，`SiteRequests.input`）；`ajax/hits|digg|score|referer` 按旧 `mac_param_url`（`application/common.php:1560-1604`，即 `LegacyParams`）；`verify_check` 按 `input()`。
- **板块关闭**：`comment.status == 0` 时评论控制器的所有操作返回 200 `text/html`，正文 `comment is close`；留言同理，正文 `gbook is close`（旧构造函数里 `echo …; exit;`）。`ajax/digg` 的 mid=4 不属于评论控制器，不受影响。
- **会员**：只认 B08 校验通过的登录态（D-B11-2）。名字按旧版：游客为"游客"；会员用请求里 `user_name` cookie 的原值，库里昵称不为 `empty()` 时用昵称。
- **验证码**：B08 本地验证码，字段 `verify`，默认槽；猜错也作废（D-B08-4）。
- **客户端 IP**：只经 `ClientIpResolver`（D2 ④）取得，写库按 `mac_get_ip_long`（非 IPv4 为 0）。
- **时间**：`time()` 秒；日/周/月边界与"今天零点"按 `PRC`。
- **可写数据源关闭**：发表、举报、顶踩、评分、`type=update` 的点击与来路一律 503（ajax 为 JSON，提示"…功能暂未开放"）。只读的查看照常：不带 type/score 的 `ajax/*`、列表页。
- **JSON**：`application/json; charset=utf-8`，与 PHP `json_encode(…, JSON_UNESCAPED_UNICODE)` 逐字节相同（`LegacyJson`）。
- **cookie**：旧版的"已参与"和频率标记原样保留，值均为 `t`、`path=/`，格式同 PHP 8.0 `setcookie`：
  - `comment_timespan`、`gbook_timespan`：有效期为 `timespan` 秒，`empty(timespan)` 时为会话 cookie；
  - `comment-report-<id>`：有效期为 `timespan` 秒；
  - `comment-digg-<id>`、`<模块>-digg-<id>`、`<模块>-score-<id>`：30 秒。

  这些限制可以被客户端绕过，旧版就是如此。新版不另加服务端限制，路线图要求不加旧版没有的限制。

### 3. 评论与留言的发表

#### 3.1 判断顺序与返回（`application/index/controller/Comment.php:37-117`、`application/common/model/Comment.php:212-244`）

| 顺序 | 条件 | code | msg |
|---|---|---|---|
| 0 | 板块关闭 | — | 纯文本 `comment is close` |
| 1 | `comment.verify == 1` 且验证码不通过 | 1002 | 验证码错误 |
| 2 | `comment.login == 1` 且未登录 | 1003 | 登录后才可以发表留言 |
| 3 | `comment_content` 为 `empty()` | 1004 | 内容不能为空 |
| 4 | 请求带非空的 `comment_timespan` cookie | 1005 | 请不要频繁操作 |
| 5 | `comment_mid` 不是 `1/2/3/8/9/11` 原文（D-B11-4） | 1006 | 模型mid错误 |
| 6 | 名字处理后为空（旧 `validate('Comment')`） | 1001 | 参数错误：昵称必须 |
| 7 | 内容处理后为空（不是合法 UTF-8 等） | 1001 | 参数错误：内容必须 |
| 8 | 写库被严格模式拒绝：名字或内容超长、含 4 字节字符、rid/pid 为负或越界（D-B11-5） | 1002 | 保存失败!： |
| — | 成功 | 1 | 感谢你的留言！（`audit == 1` 时为"谢谢，我们会尽快审核你的留言！"），并写 `comment_timespan` |

留言（`Gbook.php:43-117`）的顺序相同，没有第 5 步。

#### 3.2 写入的列

`comment_mid`（白名单原文）、`comment_rid`、`comment_pid`、`user_id`（会员编号，游客为 0）、`comment_status = audit ? 0 : 1`、`comment_name`、`comment_ip`、`comment_time = 现在`、`comment_content`、`comment_up = comment_down = 0`；`comment_reply`、`comment_report` 固定为 0（D-B11-3）。

留言写 `gbook_rid`（原值交给 MySQL 按 `int unsigned` 换算，同旧版；被严格模式拒绝时为 1002 保存失败!：）、`user_id`、`gbook_status`、`gbook_name`、`gbook_ip`、`gbook_time`、`gbook_content`；`gbook_reply` 为空串，`gbook_reply_time` 固定为 0（D-B11-3）。

#### 3.3 举报与评论顶踩

- `comment/report`（`Comment.php:119-138`）：
  - `intval(id)` 为 0 → 1001 参数错误；
  - 已有 `comment-report-<id>` → 1002 您已参与过了！；
  - 否则 `comment_report + 1`（评论不存在时也返回成功，同旧版），写 cookie，返回 `{"code":1,"msg":"操作成功"}`。
- `comment/digg`（`Comment.php:140-182`）：
  - `intval(id)` 为 0 或 `type` 为空 → 1001；
  - 已有 `comment-digg-<id>` → 1002；
  - `type` 为 `up`/`down` 时对应列 + 1；只要 `type` 非空就写 cookie；
  - 之后读出顶踩数，评论不存在 → 1002 获取失败；
  - 成功返回 `{"code":1,"msg":"操作成功","data":{"comment_up":…,"comment_down":…}}`。

#### 3.4 会员中心

`user/comment`、`user/gbook`：本人（`uid` 等于会员编号）的已审核评论、留言，分页同第 4 节。旧模板缺失，标签按 `uid` 取数是【推断】（D9-a）。

### 4. 列表与文本

#### 4.1 评论（`application/common/model/Comment.php:97-195`）

- **条件**：`comment_status = 1`；`comment_pid = pid`（URL 里 `pid` 为 0 时取顶层）；`rid`、`uid`、`mid` 非 0 时按等于筛选。
- **排序**：`comment_<by> <order>`。`by` 只认 `id/time/up/down`，其他按 time；`order` 不是 `asc` 时按 desc。
- **分页**：每页 `comment.pagesize` 条（≤0 时为 20，新版上限 100）。页码上限 100000，超过时按 100000 处理。
- **回复**：每条评论附带 `comment_pid = 该评论编号 AND comment_status = 1` 的回复，排序同上；新版一页最多带 1000 条回复（D-B11-11）。

#### 4.2 文本处理（`LegacyHtml`，黄金 `text-php80/73-golden.json` 逐字节锁定，PHP 7.3/8.0 结果相同）

- **内容**依次做三步：
  1. `mac_filter_words`：`app.filter_words` 按逗号拆开，按顺序 `str_replace` 成 `***`，区分大小写；
  2. `htmlentities`（`ENT_COMPAT | ENT_HTML401`）：不是合法 UTF-8 时整段为空；
  3. 写库前 `mac_filter_xss`，即 `trim(htmlspecialchars(strip_tags(x), ENT_QUOTES))`。
- **名字**：`htmlentities(trim(x))`，再做 `mac_filter_xss`。
- **显示**：`mac_restore_htmlfilter`，页面再按实体解码一次后转义输出。显示出来的字符与旧模板把还原后的文本直接放进 HTML 时浏览器显示的相同。

#### 4.3 留言（`application/common/model/Gbook.php:309-387`）

`gbook_status = 1`；`rid`、`uid` 非 0 时按等于筛选；`by` 只认 `id/time/reply_time`；每页 `gbook.pagesize` 条。

#### 4.4 查询

只读 SQL 在 `resources/mapper/comment/CommentMapper.xml`，排序列走固定分支，不用 `${}` 拼接。

### 5. 内容计数（`ContentCounterService`、`SiteCounterActions`）

#### 5.1 参数

- **`mid`**：`intval`。`hits`、`score` 的白名单为 `1/2/3/8/9/11`（`CounterTarget`）；`digg` 另有 `4`（评论）。
- **`id`**：按 `mac_param_url` 取文本，`empty()` 时为 1001；写库按 `intval`。cookie 名用原文，与旧版相同。
- **审核状态**：只按编号计数，不看审核状态。旧版对未审核的内容也照常计数。

#### 5.2 `ajax/hits`（`Ajax.php:152-217`）

先读出计数，不存在时为 1002 获取失败。`type == 'update'` 时执行一条原子 UPDATE（`ContentCounterWriteMapper.xml` 的 `hit<表>`）：

```sql
UPDATE mac_vod SET
  vod_hits_month = IF(vod_time_hits >= 本月1日0点 AND vod_time_hits < 下月1日0点, vod_hits_month + 1, 1),
  vod_hits_week  = IF(vod_time_hits >= 本周日0点 AND vod_time_hits <= 本周六23:59:59, vod_hits_week + 1, 1),
  vod_hits_day   = IF(vod_time_hits >= 今天0点 AND vod_time_hits < 明天0点, vod_hits_day + 1, 1),
  vod_hits = vod_hits + 1, vod_time_hits = 现在
WHERE vod_id = ?
```

- **边界**：与旧 `getdate()`、`mktime()` 相同（`HitPeriods`，PRC 无夏令时）。黄金 `hits.update.*` 覆盖从未点击、今天零点、本周内的昨天、本周日零点、上周六、本周六、下周、本月一日零点、上月、去年、未来时刻、计数已到上限与未审核内容。
- **求值顺序**：MySQL 单表 UPDATE 的 SET 从左到右求值，所以判定写在改 `*_time_hits` 之前。
- **响应**：`{"code":1,"msg":"ok","data":{"hits":…,"hits_day":…,"hits_week":…,"hits_month":…}}`，数值是写入后读回的值。

#### 5.3 `ajax/digg`、`ajax/score`

- **digg**（`Ajax.php:302-345`）：
  - `type` 非空时先看 `<模块>-digg-<id>`，已有则 1002；
  - `up`/`down` 时对应列 + 1，并写 30 秒的 cookie；其他 type 不写库；
  - 之后读出顶踩数，不存在时为 1002 获取失败（cookie 照写，同旧版）；
  - mid=4 写 `mac_comment`。
- **score**（`Ajax.php:347-398`）：
  - 先读出评分，不存在时为 1002；
  - `score`（`intval`）非 0 时先看 `<模块>-score-<id>`，再执行一条原子 UPDATE：人数 + 1，总分 + score，平均分 `(新总分 × 20 + 新人数) DIV (新人数 × 2) / 10`，即新总分 / 新人数按一位小数四舍五入（同旧 `number_format(…, 1)`）；
  - 写 30 秒的 cookie；
  - 分值不设范围，负数和大于 10 照收，同旧版。总分为负、平均分超出 `decimal(3,1)`、人数到上限时被严格模式拒绝 → 1002 保存失败!：（D-B11-5）。

#### 5.4 `ajax/referer`（`Ajax.php:219-300`、`application/common/model/Website.php:613-642`）

| 顺序 | 条件 | code |
|---|---|---|
| 1 | `url` 为空 | 1001 |
| 2 | 请求没有 Referer，或 Referer 不含 Host | 1002（msg 为"参数错误"） |
| 3 | `domain` 为空，或 `url` 不含 `domain`（D-B11-12） | 1003 |
| 4 | 找不到 `website_jumpurl` 以 `http://domain` 或 `https://domain` 开头的网址（字面匹配，D-B11-6） | 1002 获取失败 |
| 5 | 本 IP 今天零点之后已有 `refer_visit_num` 条游客来路记录（取值为空时按 1） | 102 每日仅能%s次来路记录（旧版原文含 `%s`） |
| 6 | 来路记录写不进（`visit_ly` 超长、含 4 字节字符） | 103 来路记录失败，请重试 |
| — | 写入来路记录（`user_id=0`、IP、`visit_ly = htmlspecialchars(url)`、时间）；`type == 'update'` 时，匹配的网址按 5.2 的规则各自原子更新来路计数（D-B11-7） | 1 |

来路记录用 B10 的条件插入（`INSERT … SELECT … WHERE 今日计数 < 上限`，`MemberReferralService.siteVisit`），并发时不会超过上限。

#### 5.5 Provide `detail_inc_hits`（`application/api/controller/Provide.php:142-150`）

同时满足以下条件时 `vod_hits + 1`：`ac=detail`；`ids` 非空；`(int)ids == ids`，按 PHP 8 比较（`01`、` 1`、`1.0` 成立，`1,2`、`1abc` 不成立）；`api.vod.detail_inc_hits` 为真。

只加总点击，不看视频是否存在、是否审核，输出来自缓存时同样计数。点击数已到上限时被拒绝，不影响输出（D-B11-8）。PHP 7.3 是宽松比较，`1,2`、`1abc` 也成立；新版按 8.0。

### 6. 视频搜索缓存（`VodSearchCache`，`app.vod_search_optimise` 含 `frontend`）

只用于站点的视频搜索页和筛选页（旧 `Vod::listCacheData`，`application/common/model/Vod.php:404-459`、`VodSearch.php:18-64`）。公开 API 从不触发。

- **查缓存**：wd、name、tag、class、actor、director 中设置了的每一项，按 `md5(词@字段)` 查 `mac_vod_search`。
- **未命中**：扫描 `mac_vod` 中该字段包含这个词的视频编号（不看审核与分类），插入一行，命中次数为 1。新版最多存 1001 个编号（D-B11-9）。
- **命中**：命中次数原子 + 1。
- **合并**：编号不超过 1000 个时，去掉这一项的包含匹配，把编号并入 `vod_id IN (…)`。合并照搬 PHP 数组的 `+` 与 `array_filter` 语义：按**键**合并，不是取并集。所以多个词、多个字段时，结果比真实的并集或交集少。这是旧版的行为，原样保留（D-B11-8 的说明；黄金 `search.*` 锁定）。
- **wd 的字段**：固定为 `vod_name|vod_en|vod_sub`（B04 D1 ①）。
- **不随新增更新**：缓存不随新增视频更新，由后台和采集维护（B13/B19）。可写数据源关闭时不用缓存。
- **并发首次搜索**：同一个词并发首次搜索时只有一个插入成功，其余按命中处理。旧版此时重复键报错（500）。

### 7. 验证码门与 `ajax/verify_check`（`Ajax.php:460-472`、`application/index/controller/Base.php:35-42、63-70`）

- **何时出验证码页**：旧配置 `app.search_verify == 1`（搜索页）或 `app.show_verify == 1`（筛选页），且本会话还没有对应标记时，非 ajax 请求显示验证码页（`pages/verify.jte`，no-store）。ajax 入口不验证，同旧版。
- **提交**：`verify_check` 的 `type` 只认 `search`/`show`，其他为 1001 参数错误；验证码不对为 1002 验证码错误（猜错也作废）；通过后会话写入 `<type>_verify = 1`，返回 `{"code":1,"msg":"ok"}`。
- **返回原页**：非 ajax 的提示页按 `back` 参数给"继续"链接，只认站内规范路径。
- **对 B05 的影响**：B05 的"临时关闭"由此结束（`SiteFeatures.searchNeedsVerify/showNeedsVerify`）。

### 8. 页面与交互

页面不带脚本，全部是普通表单。提交后由服务端返回提示页（`pages/userJump.jte`，与 B09/B10 相同）。

| 页面 | 模板 | 内容 |
|---|---|---|
| 评论页 `comment/index` | `pages/comment.jte`、`interaction/commentList.jte`、`interaction/postForm.jte` | 评论对象的标题与链接、已审核评论（含回复）、分页、发表表单（只有内容框；名字由服务端按第 2 节决定；`verify == 1` 时带验证码；`login == 1` 且未登录时提示去登录）；每条评论有顶、踩、举报三个按钮（`interaction/inlineForm.jte`） |
| 留言页 `gbook/index` | `pages/gbook.jte`、`interaction/gbookList.jte` | 已审核留言与站长回复、分页、发表表单 |
| 报错页 `gbook/report` | `pages/gbookReport.jte` | 留言表单，`gbook_rid` 为被报错的内容编号 |
| 列表片段 `comment/ajax`、`gbook/ajax` | `ajax/comment.jte`、`ajax/gbook.jte` | 无外壳的列表 |
| 验证码页 | `pages/verify.jte` | 验证码图、输入框、提交到 `ajax/verify_check` |
| 会员中心 `user/comment`、`user/gbook` | `pages/userList.jte`（B10 的列表页） | 本人的评论、留言；会员中心导航加"我的评论""我的留言" |
| 内容详情页（视频与文章、专题、演员、角色、网址） | `interaction/panel.jte`，经 `ContentForms.interaction` 放进 `pages/detail.jte`、`pages/libraryDetail.jte` | 互动区：点击计数（1×1 隐藏图片请求 `ajax/hits?type=update`，只在 `maccms.site.hit-counter-enabled=true` 时输出）、顶、踩、1～10 分评分表单、"查看与发表评论"链接 |

**点击计数开关**：`maccms.site.hit-counter-enabled`（环境变量 `MACCMS_SITE_HIT_COUNTER_ENABLED`）。03 方案第 6.3 节要求点击计数受开关控制，本地与测试默认关闭；此前它只接受 `false`，B11 起可以设为 `true`，取值只认 true/false。关闭时详情页不输出计数图片，`ajax/hits` 接口本身照常可用。**上线切换时需要设为 `true`**，否则新版详情页不计点击（旧版一律计数）。

**共享文件**：只改了 `pages/detail.jte` 与 `pages/libraryDetail.jte`，各加一行引用互动区；会员中心导航（`SiteUserController`）加两个链接。没有改 `site.css`、tokens、布局和组件。新模板的类名（`interaction-*`）没有对应的样式规则，按浏览器默认样式显示。需要样式时另行安排，见第 12 节。

### 9. 与旧版的差异

| 编号 | 项 | 旧版 | 新版 |
|---|---|---|---|
| D-B11-1 | （未使用；`ajax/*` 的 `id` 保持旧版写法，按 `intval` 写库，cookie 名用原文） | — | — |
| D-B11-2 | 发表人 | 只看 `cookie('user_id')` 是否非空：伪造 `user_id`、`user_name` cookie，就能以该会员名义发表（黄金 `cs.forged-cookie`、`cs.forged-id-only`） | 只认 B08 校验通过的登录态，否则按游客 |
| D-B11-3 | 客户端提交的系统列 | `allowField(true)` 会收下 `comment_reply`、`comment_report`、`gbook_reply_time`（黄金 `cs.mass-assign`、`gs.mass-assign`） | 不收，固定为 0 |
| D-B11-4 | `comment_mid` | 宽松比较：`01`、`1.0`、` 1` 也通过 | 按字节与白名单原文比较，其他为 1006 |
| D-B11-5 | 严格模式拒绝的写入 | 500，没有写入 | 返回 JSON：发表、举报、顶踩、计数为 `1002 保存失败!：`，来路记录为 `103`；同样没有写入 |
| D-B11-6 | 来路网址匹配 | `LIKE 'http://<domain>%'` 不转义，`%`、`_` 是通配符 | 字面匹配（`ESCAPE '!'`） |
| D-B11-7 | 来路计数匹配到多个网址 | 用第一行算出的新值写回所有匹配行 | 每个匹配的网址按自己的值原子 + 1；响应取第一行（按主键） |
| D-B11-8 | Provide 点击到达上限 | 500，无输出 | 不加，输出照常 |
| D-B11-9 | 搜索缓存未命中时存的编号 | 全部存入 | 最多存 1001 个，`search_result_count` 相应为 1001。超过 1000 个的缓存旧版也不用来筛选，结果相同 |
| D-B11-10 | 非 ajax 的发表、举报、顶踩、计数 POST、`verify_check` | 发表：500，但**已经写入**；举报、顶踩、计数照样返回 JSON；`verify_check` 非 ajax 时为 500 | 写入照常，返回提示页。新版页面是无脚本表单，需要这种承接形态 |
| D-B11-11 | 回复数与页码 | 每条评论的回复不限条数；页码不限 | 一页最多带 1000 条回复；页码上限 100000 |
| D-B11-12 | 来路的空 `domain` | PHP 8 下 `strpos($url, '')` 为 0，通过后匹配任意网址；PHP 7.3 为 1003 | 按 7.3，为 1003 |
| D-B11-13 | 并发计数（路线图要求写明） | 点击、来路、评分都是"先读出，在 PHP 里算好，整列写回"（`Ajax.php:172-203、255-286、375-379`），两个并发请求会读到同一个旧值，只加一次 | **并发时旧版会丢计数，新版不会**：一律单条原子 UPDATE（第 10.2 节）。顶踩与举报旧版就是 `setInc` 单语句，两边相同 |
| D-B11-14 | 并发的来路记录与搜索缓存 | 来路记录先数后插，可以超过每日上限；同一个词并发首次搜索时重复键报错（500） | 条件插入不超限；搜索缓存只插入一行，其余按命中 |
| D-B11-15 | 页面 | 旧模板缺失（D9-a），备份里这些页面都是 500 | 新的极简页面（第 8 节） |
| — | 数组参数 `comment_content[]` | PHP 8 类型错误（500）；PHP 7.3 为"参数错误：内容必须" | 按 7.3（同 D-B10-8） |

### 10. 写库

#### 10.1 登记（`WriteRegistry.REGISTERED`）

| 模块 | 表 | 操作 | 用途 |
|---|---|---|---|
| `comment` | `mac_comment` | INSERT、UPDATE | 发表；举报、顶踩 |
| `comment` | `mac_gbook` | INSERT | 发表 |
| `content` | `mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website` | UPDATE | 点击、顶踩、评分、网址来路、Provide 点击（只改计数列） |
| `content` | `mac_vod_search` | INSERT、UPDATE | 搜索缓存 |
| `member` | `mac_visit` | INSERT | 游客来路记录（沿用 B10 已登记的同一条条件插入，`user_id = 0`） |

`comment` 是新模块，`ModuleBoundaryTest`、`WriteGuardTest` 已相应更新。路线图写入方矩阵的 `mac_visit` 行原来只有 B10、B14，本批补上 B11（第 10.3 节）。

#### 10.2 语句

全部是单表单语句，零 DDL。

- `mapper-write/comment/CommentWriteMapper.xml`：发表评论、举报、顶或踩、发表留言。
- `mapper-write/content/ContentCounterWriteMapper.xml`：每张表的点击、顶踩、评分各一条，另有网址来路和 Provide 点击。
- `mapper-write/content/VodSearchWriteMapper.xml`：插入、命中。

计数一律用原子表达式（D-B11-13）。

#### 10.3 路线图修订

- 写入方矩阵 `mac_visit` 行加 B11（`ajax/referer` 的游客来路记录，复用 B10 的条件插入）。
- B11 行状态改为"已实施，待独立验收"，负责方改为 C（同 B10）。
- B11 行的"`updateToday`（日/周/月点击重置）"：旧 `updateToday` 是只读的后台查询，日/周/月重置在 `ajax/hits`、`ajax/referer` 内，本批已实现；`updateToday` 归 B13/B16。路线图该格加注说明。

### 11. 验证

#### 11.1 黄金样本

- **HTTP 黄金**：`backend/src/test/php/interaction_golden.php` 生成，输出 `resources/interaction/php80-golden.json`、`php73-golden.json`。
  - 每版 291 个 HTTP 用例：经旧 ThinkPHP 路由与 `Comment`、`Gbook`、`Ajax` 控制器，或 api 入口的 `Provide`。
  - 另有 33 个搜索缓存用例（其中 `wd.long` 超过新版 64 字的关键字上限，不复放）：直接调用 `model('Vod')->listCacheData`，旧搜索页模板里的 `{maccms:vod}` 标签就是这样调用的。仓库缺模板，HTTP 走不到。
  - 每个用例记录状态码、Content-Type、Set-Cookie、正文，以及 `mac_comment/gbook/visit/vod_search/user` 的全部行和 6 张内容表的计数列。
  - 旧 PHP 未修改；脚本用一次性库 `maccms_it_*`，跑完删除。
- **文本黄金**：`interaction_text_golden.php` 生成 `text-php80/73-golden.json`，由 `LegacyHtmlGoldenTest` 复放。

#### 11.2 `InteractionGoldenMySqlIT`（`./mvnw verify -Pmysql-it`）

测试新建一次性库，导入 `install.sql`、B06 合成内容和黄金的固定数据，以真实 Tomcat 启动应用，可写数据源打开。各部分按顺序执行：

1. **HTTP 复放**：逐条复放两版 HTTP 黄金，用原始套接字发请求，比较状态码、正文、Set-Cookie 和相关表的行。
   - **Max-Age**：旧版的到期时刻是 `REQUEST_TIME + 有效期`，Max-Age 却是发头时现算的，黄金请求慢时会少几秒。所以未过期 cookie 的 Max-Age 只要落在黄金的 [到期 − after, 到期 − started] 区间内即算一致。
   - **有意差异**：按第 9 节的新版结果断言（`JAVA_JSON`、`JAVA_PAGE`、`CUSTOM_ROWS`）。
   - **不复放的用例**：`cs.ip`、`cs.ipv6`、`gs.ip`、`ref.visit-other-ip` 的客户端 IP 不是本机，原始套接字造不出来；`vc.lowercase` 的码表不是数字。
2. **搜索缓存复放**：两版的 33 个用例，比较结果编号和 `mac_vod_search` 的行。用例之间的缓存行连续，"现在"按整段的时间窗规范化。
3. **并发**：
   - 100 次点击、顶踩各 100 次（顶 60、踩 40）、50 次评分、30 次 Provide 点击，计数全部计入；
   - 40 次来路，两个同前缀的网址各 + 40；
   - 评论举报、顶踩各 50 次；
   - 16 个并发评论全部写入；
   - 同一 IP 16 个并发来路记录只放行 1 个；
   - 同一个词 16 个并发首次搜索，只插入一行，命中次数为 16。
4. **回滚演练**：只用 DML 恢复到导入后的快照，逐行 diff 为空，表结构指纹不变，最后删库。

#### 11.3 结果（2026-10-25，代码与测试同 `962c0e6`，之后只改本文）

运行环境：MySQL 5.7.44，本机管理账号只放在 shell 环境变量里；`MACCMS_IT_PHP_CGI` 为 PHP 8.0.30；仓库根的 `vendor/` 从主仓库临时拷入，跑完已删除。

- **`./mvnw clean verify -Pmysql-it`**：BUILD SUCCESS。单元 2419 个、集成 63 个，0 失败、0 错误、0 跳过。
  - `InteractionGoldenMySqlIT` 5/5：两版 HTTP 各比较 286 例，不一致 0；搜索缓存两版各 32 例；并发；回滚演练 28 条 DML 后逐行一致，表结构指纹不变。
  - B10 `MemberFinanceGoldenMySqlIT` 两版各 146 例照旧通过。
  - 14 个一次性库都是 `verified absent`，跑完后的数据库列表与跑之前相同；日志里没有 `maccms_dev`。
- **`./mvnw verify`**（不设 `MACCMS_IT_*`）：BUILD SUCCESS，2419 个测试，0 失败、0 错误、0 跳过。
- **Node**：`node --test backend/src/test/js/site/member.test.js backend/src/test/js/site/trial.test.js`，24/24（本批没有改脚本）。
- **检查**：`git diff --check 6849add HEAD` 通过；改动的 125 个文件都在 `backend/`、`docs/` 下，全部是 UTF-8，无 BOM、无 CR；旧 PHP、`template/`、`static/` 改动数为 0；写 SQL 里没有 DDL。

#### 11.4 打包 jar 实测（本机一次性库，跑完已清理）

- **环境**：`./mvnw package -DskipTests` 打出的 jar 连一次性库 `maccms_it_*`，库里导入 `install.sql` 和 B06 合成内容。可写数据源打开，`MACCMS_SITE_HIT_COUNTER_ENABLED=true`，`W1/W2/W3=false`。旧配置目录是仓库 `application/` 的副本，改了三项：`comment.verify=0`、`search_verify=1`、`vod_search_optimise=frontend`。
- **用 curl 走的流程**，结果都符合第 3～7 节：
  - 视频详情页的互动区：计数图片、顶踩与评分表单、评论链接；
  - 计数图片请求后 `vod_hits` +1，日/周/月从旧值重置为 1；
  - 非 ajax 顶 → 提示页，`vod_up` +1；再顶 → 1002 您已参与过了！；
  - 评分 8 → 平均分 8.5（93/11）；
  - 非 ajax 发表评论 → 提示页"感谢你的留言！"，库里内容按旧版双重转义，评论页按一次转义显示；3 秒内再发 → 1005；
  - 评论顶、举报 → 计数 +1；
  - 留言页带验证码，不填验证码提交 → 1002；
  - `search_verify=1` 时搜索页显示验证码页，表单提交到 `ajax/verify_check`，错码 → 1002。
- **浏览器**：`backend/src/test/browser/site-pages-check.mjs`（Edge 无头）检查视频详情、文章详情、评论页、留言页、报错页和搜索验证码页。375/768/1280/1440/1920 宽度下都没有横向溢出；键盘顺序与焦点样式正常；禁用 JS 后内容仍在。失败 0 项。
- **收尾**：应用日志 0 行 ERROR；jar 进程已按 PID 结束，端口已关闭；一次性库已删除，全局 `maccms\_it\_%` 为 0；临时目录已删除。
- **未做**：新旧 jar 对 B05～B10 游客页逐字节对照（B10 做过）。详情页按设计多了互动区，其他页面靠 MockMvc 测试覆盖。

#### 11.5 实施中修正的测试问题

- **Cookie Max-Age**：旧版的到期时刻按 `REQUEST_TIME` 算，Max-Age 却是发头时现算的，黄金请求慢时会少几秒。复放改为按区间比较（第 11.2 节）。
- **验证码签发时刻**：原来按黄金的 started 签发，黄金生成超过 30 分钟（验证码有效期）后，验证码用例会全部失败。改为按会话存储的时钟签发。
- **管理连接**：原来每个用例新建一次性库的管理连接，整套 mysql-it 跑下来 Windows 临时端口会耗尽（实测出现过一次）。改为全程共用一条。
- **旧测试随 B11 更新**：`ajax/hits|digg|verify_check` 不再是 404；搜索页带 `searchCache`；隐藏字段不要求 label；写入登记表含 B11。

### 12. 风险与待确认

1. **前台归属**：B11 也按"只有 C、沿用极简样式"执行，需要队长确认。互动区和评论、留言页的新类名目前没有样式规则；需要的话，在 `site.css` 里只加最少的规则，可以放在本批，也可以另开任务。
2. **点击计数用隐藏图片请求**：页面不带脚本时只能这样。预取、抓取图片的爬虫、不加载图片的客户端会多计或少计；旧版靠模板里的 JS ajax，同样可以被绕过。计数默认关闭，上线时要打开开关（第 8 节）。
3. **深页码**：评论列表允许页码到 100000，在 MyISAM 上是大偏移扫描。旧版不限页码，新版已收紧，但没有改成游标分页。
4. **MyISAM 表锁**：计数 UPDATE 与长查询互相阻塞（MACC-19 已测到），本批没有解决，属于部署与容量的事项。
5. **黄金体积**：两份 HTTP 黄金各约 5 MB，主要是每个用例记录的 6 张内容表计数列。可读性与完整性优先，没有裁剪。
6. **未核实**：旧模板缺失（D9-a），评论页、留言页、会员中心两页的旧版外观和标签属性无从核实。
