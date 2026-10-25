## B10 会员权益与财务：记录、积分、购买、升级、卡密、提现、推广、订单（路线图 B10，MACC-34）

> **状态：实施完成，待验收（MACC-34）。** 由 Claude 实施后端与前端接线（队长 2026-10-24 按用户 2026-10-17 决定：本批只有 C，不启用 K；沿用现有极简样式，不另做视觉设计）。契约稿 v1 在实施中按黄金样本与实现修订的地方记在第 12 节"契约修订记录"，验证记录与验收对照见第 10 节。
> 依据：路线图 `09-roadmap.md` 第 5.3 节 B10 行与写入方矩阵；B07 文档 `12-write-infrastructure.md`（写 SQL 规范、`WriteSequence`、幂等约定）；B08 `17-b08-member-session-captcha.md`（到期降级语句）；B09 `18-b09-member-account.md`（路由形态、参数字节语义、会员页、D-B09-7/11）。旧代码位置一律相对仓库根目录。

### 0. 起点、分支与提交

| 项 | 值 |
|---|---|
| 运行起点 | 平台基线 `ca3a9d4`（本任务工作区的快照提交），其父提交为已验收 main `b5f1bbfca3a7aba531d97ac90c8f310608da9e40`（B09 普通 merge，tree `e283acbe…`） |
| 分支 | `agent/claude/macc-34`（只用普通提交，不 reset/rebase/amend/force，不自行合并 main） |
| 起始工作树 | 只有平台生成的 `.claude/`、`CLAUDE.md`（未跟踪，不提交） |

### 1. 范围

| 在本批 | 不在本批（归属） |
|---|---|
| `user/ajax_ulog`（记录写入与 JSON 列表）、`plays`、`downs`、`favs`、`ulog` 列表页、`ulog_del` | `comment`、`gbook` 会员页（**B11**） |
| `user/plog` 积分日志页、`plog_del` | 支付网关下单、回调、跳转（E04/E05/W3，**B24**）：本批"去支付"按旧版未配置支付时的结果返回（第 4.6 节） |
| `user/ajax_buy_popedom`（积分购买观看/下载/文章）与内容页的购买确认、试看购买入口（第 5 节） | `bind`、`unbind`、`bindmsg`（**B25**） |
| `user/upgrade`（积分升级会员组）、`user/popedom`（我的权限） | `oauth`、`logincallback`（**B26**） |
| `user/buy`（卡密充值、在线充值本地建单）、`pay`、`gopay`、`qrcode`、`orders`、`order_info`、`cards` | 后台的会员、卡密、订单、提现审核、日志管理（**B14**），包括后台会员列表第 1 页的 `expire()` 调用（与本批共用第 6.2 节的批量降级语句） |
| `user/cash`（提现申请与列表）、`cash_del`（撤销） | `mac_annex` 头像登记（D-B09-9，**B13**） |
| `user/reward`（推广会员列表）、`user/visit`（推广访问加积分） | |
| 三级分销奖励（`reward`，购买与升级时） | |
| **D-B09-7**：邀请注册给邀请人加 `invite_reg_points` 并写 `mac_plog` | |
| **D-B09-11**：会员组 ≤2 访问收费数据时按 `mac_ulog` 判断是否已购买（视频播放/下载、试看、文章） | |
| 会员组到期批量降级任务（`user_end_time <= now`，B08 演练语句） | |
| 会员中心首页加入本批各页入口；内容页（详情/播放/下载）的收藏、播放/下载记录与购买接线 | |

### 2. 路由总表

地址形态与 B09 相同：`/index.php/user/<操作>[.html|.htm|.shtml|.xml][/键/值…]`（`rewrite` 打开时另认去掉 `/index.php` 的形态），路径里的 `键/值` 是 ThinkPHP 路由参数（如 `user/plays/page/2.html`）。参数合并、字节解析、`clean()`、`empty()`、JSON 编码、ajax 判定都按 18 文档第 3.1 节与第 2 节。

| 操作 | 方法 | 需登录 | 响应 |
|---|---|---|---|
| `ajax_ulog` | 任意 | 是 | `ac=set`：JSON（第 4.1 节）；非 ajax 的 POST 另返回提示页（第 3.3 节）；其他：JSON 列表 |
| `ajax_buy_popedom` | 任意 | 是 | JSON（第 4.2 节）；非 ajax 返回提示页（第 3.3 节） |
| `plays`、`downs`、`favs`、`ulog` | GET/HEAD | 是 | 列表页（第 4.3 节） |
| `ulog_del`、`plog_del`、`cash_del` | 任意 | 是 | JSON `{"code":…,"msg":"…"}`（第 4.4 节） |
| `plog` | GET/HEAD | 是 | 积分日志页 |
| `buy` | GET/HEAD | 是 | 充值页（卡密 + 在线充值金额） |
| `buy` | POST | 是 | `flag=card`：卡密充值 JSON；否则：本地建单 JSON（第 4.5 节） |
| `pay` | 任意 | 是 | 订单支付页；订单不存在 → 提示页"获取失败"（第 4.6 节） |
| `gopay` | 任意 | 是 | B24 前：提示页"该支付选项未开启！"（参数全空时"参数错误"）；ajax → ThinkPHP `error()` JSON |
| `qrcode` | 任意 | 是 | `data` 以 `weixin` 开头 → PNG；否则提示页"参数错误" |
| `upgrade` | GET/HEAD | 是 | 升级页（会员组与各时长价格） |
| `upgrade` | POST | 是 | JSON；成功时带 `group_id`/`group_name` 两个 cookie（第 4.7 节） |
| `popedom` | GET/HEAD | 是 | 我的权限页（第 4.8 节） |
| `cash` | GET/HEAD | 是 | 提现页（申请表单 + 记录） |
| `cash` | POST | 是 | JSON（第 4.9 节） |
| `reward` | GET/HEAD | 是 | 推广会员列表（`level=1|2|3`） |
| `orders` | GET/HEAD | 是 | 订单列表 |
| `order_info` | 任意 | 是 | ajax → JSON；否则订单详情页（第 4.10 节） |
| `cards` | GET/HEAD | 是 | 已使用的充值卡列表 |
| `visit` | 任意 | 否（旧白名单 `User.php:19`） | 推广访问记录与加分（第 4.11 节），之后 302 |

- **需登录的操作未登录时**与 B09 相同：先写 6 个删除 cookie，ajax → `{"code":0,"msg":"未登录","data":"","url":"<站点根路径>index.php/user/login.html","wait":3}`，否则提示页（200，带去登录页的链接）。
- **可写数据源关闭时**与 B09 相同：全部 `user/*`（含 `visit`）返回 503 维护提示，ajax 为 JSON `{"code":503,"msg":"会员功能暂未开放"}`。
- **列表页**：`page = max(1, intval(page))`；`limit = intval(limit) < 20 ? 20 : intval(limit)`，**上限 100**（旧版无上限，D-B10-6）；`page` 上限 100000（超过按 100000 取，通常是空页；旧版无上限）。分页链接 `user/<操作>/page/<n>.html`，筛选参数（`mid`、`type`、`level`）随分页保留（D-B10-7）。
- 所有 `user/*` 响应：`Cache-Control: no-store`、`X-Robots-Tag: noindex`、`X-Content-Type-Options: nosniff`。

### 3. 通用规则

#### 3.1 参数取值

- `intval(x)`：PHP `intval` 对字符串的规则（前导空白、可选符号、十进制数字前缀；越界饱和到 64 位边界），即 `content.application.PhpIntval`。
- **白名单比较按字节精确匹配**：旧版 `in_array($param['type'], ['1','4','5'])` 等是 PHP 宽松比较，`'01'`、`'1.0'`、`' 1'`（PHP 8 另有 `'1 '`）都会被当成 `'1'`。新版只接受白名单中的原文，其他一律按该入口的"参数错误"（D-B10-1，只会更严）。
- `floatval(x)`（提现金额）：PHP `floatval` 的数字前缀规则；`is_numeric(x)`（充值金额）：PHP 8 的数字串规则（前导空白、尾随空白、符号、小数、指数）。
- 客户端 IP 只经 `ClientIpResolver`，写库时按 `mac_get_ip_long`（B09 `legacyIp`）。
- 时间：`time()` 秒；"今天零点"按 `PRC`（`Asia/Shanghai`）。订单号的日期部分 `date('YmdHis')` 同样按 `PRC`。

#### 3.2 JSON

- `Content-Type: application/json; charset=utf-8`，编码与 PHP `json_encode(…, JSON_UNESCAPED_UNICODE)` 逐字节相同（`common.web.LegacyJson`）；键顺序按旧代码数组的顺序。
- 库里的整数列按 JSON 数字输出、`decimal` 列按字符串输出（ThinkPHP 5.0 PDO 原生预处理时的类型，以黄金为准）；`pagecount` 是 PHP `ceil()` 的浮点结果（PHP ≥ 7.1 编码为 `1.0` 这种形式，以黄金为准）。

#### 3.3 非 ajax 请求的提示页（新版的承接方式）

内容页（详情、播放、下载、购买确认）沿用 B05 的严格策略（`script-src 'none'`），上面的"收藏""确认支付"是普通的 `<form method="post">`，不依赖脚本。所以 `ajax_ulog`（`ac=set`）与 `ajax_buy_popedom` 在**非 ajax 请求**时返回提示页（`pages/userJump.jte`，200）：成功（`code == 1`）为 `role="status"`，否则为 `role="alert"`；提示文字是 JSON 里的 `msg`（数组形式的失败结果取第一个元素的 `msg`）；"继续"链接回到对应的内容页（按 `mid/type/id/sid/nid` 生成：播放 → `vod/play`，下载 → `vod/down`，文章 → `art/detail` 第 `sid` 页，收藏 → 对应的详情页），不读 `Referer`。ajax 请求的响应与旧版逐字节相同。这是新增的承接形态（D-B10-2）：旧版这两个入口只给 JSON，由模板脚本弹出提示。

#### 3.4 写库失败的返回

- 严格模式拒绝的写入（超长、越界）：旧版 ThinkPHP 抛异常、返回 500 错误页，且前面的步骤已经生效（例如购买先扣了积分）。新版前置步骤按第 6.3 节补偿，返回该入口"保存失败"一类的 JSON（D-B10-3，同 D-B09-10）。
- 结果不确定（连接中断、超时）或补偿失败：按 B07 `CompensationFailedException` 上报人工对账（ERROR 日志一行，不含参数），返回该入口"保存失败"一类的 JSON；不自动重试。

### 4. 各入口契约

#### 4.1 `ajax_ulog`（`index/controller/User.php:47-99`、`common/model/Ulog.php:19-160`）

**`ac=set`**（`$param['ac'] == 'set'`）：

1. `mid = intval(mid)`、`rid = intval(id)`、`type = intval(type)`、`sid = intval(sid)`、`nid = intval(nid)`、`user_id` = 当前会员。
2. `mid == 1 && type > 3`：按 `vod_id = rid` 查视频（**不看审核状态**，同旧 `Vod::infoData`）；查不到 → `{"code":1002,"msg":"获取失败"}`；否则 `points = intval(type == 4 ? vod_points_play : vod_points_down)`（这里不看 `vod_points_type`，照旧）。其他情况 `points = 0`。
3. 已有完全相同的记录（`user_id, ulog_mid, ulog_rid, ulog_type, ulog_sid, ulog_nid, ulog_points` 七项都相等）→ 把这些记录的 `ulog_time` 更新为现在，返回 `{"code":1,"msg":"获取成功","info":{<更新前的那一行>}}`（`info` 的键为表的全部列，按列顺序）。
4. `points == 0` → 插入（`mid ∈ {1,2,3,8}` 且 `type ∈ {1..5}`，否则 `{"code":1002,"msg":"参数错误"}`）→ `{"code":1,"msg":"保存成功!"}`。
5. 否则 `{"code":2001,"msg":"收费收据需单独记录"}`。

- 并发：第 3、4 步合成"条件插入（`NOT EXISTS` 七项相同）"，匹配 0 行时重读并按第 3 步返回（同一会员连点两次只产生 1 行；旧版先查后插会产生 2 行，D-B10-4）。
- `type`：1 浏览、2 收藏、3 想看、4 播放、5 下载（`mac_get_ulog_type_text`，`common.php:1150-1160`）。

**其他 `ac`**（列表）：`where user_id = 当前会员`，`mid/id/type` 的 `intval > 0` 时加等值条件；`page = max(1, intval(page))`、`limit = intval(limit) < 1 ? 10 : intval(limit)`（上限 100）；排序 `ulog_time DESC, ulog_id DESC`（旧版只有 `ulog_time desc`，同秒的次序不确定）。

```json
{"code":1,"msg":"数据列表","page":1,"pagecount":1.0,"limit":10,"total":1,
 "list":[{"ulog_id":1,"user_id":7,"ulog_mid":1,"ulog_type":2,"ulog_rid":3,"ulog_sid":0,"ulog_nid":0,"ulog_points":0,"ulog_time":1792300000,
          "data":{"id":3,"name":"…","pic":"…","link":"…","type":{"type_id":1,"type_name":"电影","link":"…"}},
          "user_name":"…"}]}
```

- `data`：`mid` 1/2/3/8 分别取视频/文章/专题/演员的 `id`、`name`、`pic`（`mac_url_img`，即 B02 `ImageUrlNormalizer` 的结果字符串）、`link`（视频：`sid>0 && nid>0` 时为播放页，`type=5` 为下载页，否则详情页；其他为详情页）与分类 `type`（专题、演员为 `[]`）。内容不存在时的形态以黄金为准。
- `user_name`：当前会员的用户名（旧版取"最新 999 个会员"里的名字，会员较早时为 `null`，D-B10-5）。

#### 4.2 `ajax_buy_popedom`（`index/controller/User.php:101-173`、`common/model/User.php:894-950`）

参数 `mid`、`id`、`sid`、`nid`、`type`。

| 顺序 | 条件 | 结果 |
|---|---|---|
| 1 | `mid` 不是 `1`/`2`、`type` 不是 `1`/`4`/`5`（字节精确，D-B10-1），或 `intval(id)` 为 0 | `{"code":2001,"msg":"参数错误"}` |
| 2 | `type=1`：按 `art_id` 查文章（不看审核状态）；`type=4/5`：按 `vod_id` 查视频。查不到 | **`[{"code":1002,"msg":"获取失败"}]`**（旧版 `json([$res])`，外层是数组，照搬） |
| — | 积分列：文章 `user.art_points_type == '1'` 时 `art_points`（且 `sid = nid = 0`），否则 `art_points_detail`；视频 `user.vod_points_type == '1'` 时 `vod_points`（且 `sid = nid = 0`），否则 `vod_points_play`/`vod_points_down` | `points = intval(积分列)` |
| 3 | 已有相同记录（`ulog_mid = mid`（1/2）、`ulog_type = type`、`rid`、`sid`、`nid`、`user_id`、`ulog_points = points`） | `{"code":1,"msg":"您已经购买过此条数据，无需再次支付，请刷新页面重试！"}` |
| 4 | `points > 会员当前积分` | `{"code":2002,"msg":"对不起,查看此页面数据需要[<points>]积分，您还剩下[<积分>]积分，请先充值！"}` |
| 5 | 成功：扣积分、写 `mac_plog`（`plog_type=8`，`plog_points=points`）、分销奖励（第 4.12 节）、写 `mac_ulog` | `{"code":1,"msg":"保存成功!"}` |

- `mid` 与 `type` 独立：`type=1` 一律查文章，`ulog_mid` 记为传入的 `mid`（照旧）。`points = 0` 时同样记一笔 0 积分的 `mac_plog` 与 `mac_ulog`（照旧）。
- 写入顺序（第 6.3 节）：**认领**（条件插入 `mac_ulog`，七项相同的记录不存在才插入）→ **扣积分**（`user_points = user_points - points WHERE user_id = ? AND user_points >= points`）→ **记流水**（`mac_plog`）→ 分销。认领匹配 0 行 → 第 3 行"已经购买过"；扣积分匹配 0 行 → 删除刚认领的记录后重读积分，按第 4 行返回。同一会员对同一数据的并发购买只扣一次（旧版先查后扣，可以扣两次，D-B10-4）。
- 旧版第 4 行用的是请求开始时 `label_user` 读到的积分，新版用扣积分前重读的值；扣积分本身由条件更新保证不为负。

#### 4.3 记录列表页 `plays`/`downs`/`favs`/`ulog`（`User.php:596-681`）

| 页 | 条件 | 说明 |
|---|---|---|
| `plays` | `ulog_mid=1 AND ulog_type=4` | 播放记录 |
| `downs` | `ulog_mid=1 AND ulog_type=5` | 下载记录 |
| `favs` | `ulog_type=2`，`mid ∈ {1,2,3,8}` 时加 `ulog_mid=mid` | 收藏 |
| `ulog` | `mid ∈ {1,2,3,8}`、`type ∈ {1..5}` 时各加等值条件 | 全部记录 |

每行显示内容名称（链接到第 4.1 节的 `link`；内容不存在时显示"内容已删除"、不给链接）、模型与类型文字、积分（> 0 时）、时间，以及一个删除表单（`ulog_del`，`ids=<ulog_id>`、`type=<该行类型>`）。页头有"清空"表单（`all=1`、`type=<本页类型>`；`ulog` 页只在选了类型时出现）。

#### 4.4 删除 `ulog_del`/`plog_del`/`cash_del`（`User.php:683-713,733-758,784-809`）

- `ids = clean(ids)`；`ulog_del` 另要求 `type` 是 `1`～`5`（字节精确）→ 否则 `{"code":1001,"msg":"参数错误"}`；`empty(ids) && empty(all)` → 同上。
- `ids` 按 `,` 切分，每项 `intval(abs(项))`（非数字项为 0），去重；最多 1000 项，超过按"参数错误"（D-B10-6）。`all == '1'` 时删除该会员全部（`ulog_del` 为该类型的全部），否则 `id IN (…)`。条件总是带 `user_id = 当前会员`。
- `ulog_del`、`plog_del`：一条 `DELETE`，返回 `{"code":1,"msg":"删除成功"}`。
- `cash_del`：逐行处理（旧 `Cash::delData`，`common/model/Cash.php:117-146`）：未审核（`cash_status=0`）的提现删除后把冻结积分退回（`user_points += cash_points`、`user_points_froze -= cash_points`）；已审核的只删除。每行是"认领（按 `cash_id`、`user_id`、读到的 `cash_status` 条件删除）→ 退回冻结（`WHERE user_points_froze >= cash_points`）"，退回匹配 0 行时把该行原样插回（带原 `cash_id`）并返回 `{"code":1005,"msg":"更新用户积分失败："}`。全部完成 → `{"code":1,"msg":"删除成功"}`。旧版按读到的旧值整体覆盖积分，与后台审核并发时会丢失更新；新版是增量条件更新（D-B10-4）。
- 旧版 PHP 8 下 `abs()` 遇到非数字项（包括 `all=1` 而 `ids` 为空时的空串）抛类型错误、返回 500；PHP 7.3 告警后按 0 处理。新版按 7.3 的结果（D-B10-8）。

#### 4.5 `buy`（`User.php:432-467`、`common/model/Card.php:123-157`、`common/model/Order.php:52-73`）

**卡密**（`flag == 'card'`；`flag` 取合并参数）：`card_no = clean(card_no)`、`card_pwd = clean(card_pwd)`。

| 条件 | 结果 |
|---|---|
| 任一为空 | `{"code":1001,"msg":"参数错误"}` |
| 没有 `card_no`、`card_pwd` 都相等（MySQL 比较规则：不分大小写、忽略尾部空格）且 `card_use_status=0` 的卡 | `{"code":1002,"msg":"充值卡信息有误，请重试"}` |
| 成功：卡标为已售、已用，`user_id`、`card_use_time` 写入；会员加 `card_points` | `{"code":1,"msg":"充值成功，增加积分【<card_points>】"}` |

- 旧版不写 `mac_plog`（照旧）。写入顺序：**认领**（`UPDATE mac_card … WHERE card_id = ? AND card_use_status = 0`）→ **加积分**；加积分失败时按条件释放卡（带本次写入的 `user_id`、`card_use_time`）。同一张卡并发使用只成功一次（B07 ⑤-1 的正式实现）。
- 旧版用卡号卡密作条件整体更新，同号同密的多张未用卡会一起被标为已用、只加一次积分；新版只认领一张（按 `card_id` 最小）（D-B10-4）。

**在线充值建单**（其他 `flag`）：`price = 合并参数 price 的原值`（不清洗，照旧）。

| 条件 | 结果 |
|---|---|
| `empty(price)` | `{"code":1001,"msg":"参数错误"}` |
| `price` 不是 PHP 数字串（D-B10-1） | `{"code":1001,"msg":"参数错误"}` |
| `price < pay.min`（按数值比较） | `{"code":1002,"msg":"最小充值金额不能低于<pay.min 原文>元"}` |
| 成功 | `{"code":1,"msg":"保存成功!","data":{"user_id":<id>,"order_code":"PAY<YmdHis><6 位随机>","order_price":"<price 原文>","order_time":<秒>,"order_points":<intval(pay.scale * price)>}}` |
| 写入被拒（`order_price` 超出 `decimal(12,2)`、`order_points` 超出 `mediumint unsigned`） | `{"code":1002,"msg":"保存失败!："}`（D-B10-3） |

- 订单号用条件插入保证不与已有订单重复（旧版不检查，6 位随机数在同一秒内可能相同）；`order_status=0`。只建单，不发起任何支付请求。

#### 4.6 `pay`、`gopay`、`qrcode`（`User.php:469-547`）——B24 前的"未配置支付"

- `pay`：`order_code = clean(order_code)`，按 `order_code` 与当前会员查订单；查不到 → ThinkPHP `error('获取失败')`（非 ajax：提示页；ajax：`{"code":0,"msg":"获取失败","data":"","url":"","wait":3}`）。查到 → 支付页：订单号、金额、积分、状态、时间；未支付时列出旧版 `mac_extends_list('pay')` 的四种方式（支付宝 `alipay`、码支付 `codepay`、微信支付 `weixin`、幻兮支付 `zhapay`），每种是一个提交到 `gopay` 的 GET 表单（`order_code`、`order_id`、`payment`）；已支付时显示"该单据已支付完成"，不列方式。
- `gopay`：`order_code = clean`、`order_id = intval(trim)`、`payment = strtolower(clean)`；三者都为空 → `error('参数错误')`；否则 **B24 前一律** `error('该支付选项未开启！')`——与旧版 `pay.<payment>.appid` 为空（仓库配置即如此）时的结果相同。**不生成任何网关地址、不跳转、服务端不发请求**。旧版 appid 已配置时会继续核对订单并跳转网关，这部分在 B24 实现（分批缺口 D-B10-9）。
- `qrcode`：`data` 以 `weixin` 开头时输出本地生成的二维码 PNG（纠错 L、每模块 10 像素、静区 4 模块，同旧 `QRcode::png($data, false, QR_ECLEVEL_L, 10)`；字节不同，按"解码出同一文本"验收，同 B06），`data` 超过 1024 字节按失败；否则 `error('参数错误')`。

#### 4.7 `upgrade`（`User.php:549-561`、`common/model/User.php:539-600`）

GET：升级页，列出 `group_id ≥ 3` 且启用的会员组及其日/周/月/年积分；表单字段 `group_id`（单选）、`long`（`day`/`week`/`month`/`year`）。

| 顺序 | 条件 | code | msg |
|---|---|---|---|
| 1 | `long` 不是 `day`/`week`/`month`/`year` | 1001 | 非法操作 |
| 2 | `intval(group_id) < 3` | 1002 | 请选择自定义收费会员组 |
| 3 | 没有该组 | 1003 | 获取会员组信息失败 |
| 4 | `group_status == 0` | 1004 | 会员组已经关闭，无法升级 |
| 5 | 会员积分 `< group_points_<long>` | 1005 | 积分不够，无法升级 |
| 6 | 写库失败 | 1009 | 升级会员组失败 |
| 7 | 成功 | 1 | 升级会员组成功 |

- 时长：日 86400、周 604800、月 2592000、年 31536000 秒。新到期时间 = 原到期时间 > 现在 ? 原到期时间 + 时长 : 现在 + 时长。
- 写库：一条条件更新 `SET user_points = user_points - ?, user_end_time = ?新, group_id = ? WHERE user_id = ? AND user_points >= ? AND user_end_time = ?读到的 AND group_id = ?读到的`；匹配 0 行时重读：积分不够 → 1005，否则（到期时间或组被并发改过）按新值重算再试（每次失败都意味着另一个写入已经生效，所以有进展；上限 64 次，超过按 1009）。之后写 `mac_plog`（`plog_type=7`）、分销奖励。
- 成功时写 `group_id=<组号>`、`group_name=<组名>` 两个 cookie（30 天，`path=/`，同 B09 的组 cookie）。
- 旧版用请求开始时读到的积分整体覆盖（`user_points = 旧值 - 价格`），并发升级会只扣一次却延长两次；新版每次都扣（D-B10-4）。

#### 4.8 `popedom`（`User.php:563-594`、`common/model/User.php:528-537`）

我的权限页：全部分类（含停用分类，同旧 `Type::getCache('type_tree')`）按树显示，每个分类一行，列为"列表页、内容页、播放页、下载页、试看"（非视频分类只有前两列），格子为"是/否"：`group_type` 含该分类且 `group_popedom[分类][位]` 非空。会员组取**有效会员组**（到期按 2；旧版在降级的那一次请求里仍用原组号，D-B10-10）。

#### 4.9 `cash`（`User.php:760-782`、`common/model/Cash.php:66-115`）

GET：提现页，显示可用积分、冻结积分、`cash_ratio`（每元积分）、`cash_min`，`cash_status=1` 时给出申请表单（`cash_money`、`cash_bank_name`、`cash_bank_no`、`cash_payee_name`），下方是记录列表（`cash_id DESC`，每行带删除表单）。

POST：

| 顺序 | 条件 | code | msg |
|---|---|---|---|
| 1 | `user.cash_status != '1'` | 1005 | 提现功能未开启！ |
| 2 | `floatval(cash_money) < cash_min` | 1006 | 最低提现金额！：<cash_min 原文> |
| 3 | `tx = intval(cash_money * cash_ratio)` 大于会员积分 | 1007 | 提现太多了,没有这么多积分哦！ |
| 4 | 三个清洗后的字段有一个为空（ThinkPHP `require`） | 1001 | 参数错误：<字段提示>（以黄金为准，`cash_bank_no` 的提示键在旧验证器里写错了） |
| 5 | 写库被拒（如 `cash_points` 超出 `smallint unsigned`） | 1004 | 保存失败!： |
| 6 | 成功 | 1 | 保存成功! |

- 写库顺序：**冻结**（`user_points = user_points - tx, user_points_froze = user_points_froze + tx WHERE user_id = ? AND user_points >= tx`；匹配 0 行 → 1007）→ **记录**（插入 `mac_cash`，`cash_money` 为 `floatval` 结果按 PHP 浮点转字符串的形式写入，`cash_points = tx`，`cash_time = 现在`，`cash_status = 0`）；插入失败时按条件解冻。并发重复申请不会超额冻结、积分不为负（旧版先插记录、再按旧值整体覆盖积分，并发时可能只扣一次却有两条记录，D-B10-4）。

#### 4.10 `orders`、`order_info`、`cards`（`User.php:839-890`）

- `orders`：本会员订单（`order_id DESC`）：单号、金额、积分、状态（未支付/已支付）、时间；未支付的给"去支付"链接（`pay?order_code=…`），每行有详情链接。
- `order_info`：`order_id = intval(order_id)`，**只查本会员的订单**（旧版不带会员条件，任何登录会员都能读到别人的订单，D-B10-11 安全加固）。ajax → `{"code":1,"msg":"获取成功","info":{<订单全部列>}}`、查不到 `{"code":1002,"msg":"获取失败"}`；非 ajax → 订单详情页（查不到时页面显示"获取失败"）。
- `cards`：本会员已使用的充值卡（`card_use_status=1`，`card_id DESC`）：卡号、面值、积分、使用时间；**不显示卡密**。

#### 4.11 `visit`（`User.php:906-918`、`common/model/User.php:848-892`）

1. `uid = abs(intval(uid))`，为 0 → 不记录（旧版返回 101，控制器忽略结果）。
2. `max = user.invite_visit_num`（`empty` 时为 1）；今天零点（PRC）之后（`visit_time > 零点`）该 `uid` + 本 IP 的访问数 `>= max` → 不记录（102）。
3. 插入 `mac_visit`（`user_id = uid`、`visit_ip`、`visit_time`、`visit_ly = htmlspecialchars(trim(urldecode(Referer)))`）；失败 → 不加分（103）。
4. `UPDATE mac_user SET user_points = user_points + intval(invite_visit_points) WHERE user_id = uid`，匹配 1 行时写 `mac_plog`（`plog_type=3`）。
5. 不论结果，302 跳转：`url` 参数是 `http(s)` 绝对地址且主机（含端口）与请求的 `Host` 相同 → 跳到它；否则跳到 `/`（旧版只比较主机，不检查协议，D-B10-12）。

- 并发：第 2、3 步合成一条条件插入（`… WHERE (SELECT COUNT(*) …) < max`），同一 IP 并发访问不会超过每日次数（旧版先数后插，可超额，D-B10-4）。
- 不存在的 `uid` 同样记一条访问（照旧），只是不加分。

#### 4.12 分销奖励 `reward`（`common/model/User.php:894-950`）

在购买（第 4.2 节）与升级（第 4.7 节）成功后，`fee > 0 && user.reward_status == '1'` 时，对 1～3 级推广人（`user_pid`、`user_pid_2`、`user_pid_3`）分别：比例 `reward_ratio[_2|_3]` 与推广人编号都非 `empty` → `points = floor(fee / 100 * 比例)`；`points > 0` → 推广人 `user_points += points`，匹配 1 行时写 `mac_plog`（`plog_type` 4/5/6，`plog_remarks = 用户【<id>、<用户名>】消费<fee>积分，获得奖励<points>积分`）。浮点计算顺序与旧版相同（先除 100 再乘比例）。每一级是独立的"加积分 → 记流水"序列，某一级失败不影响其他级与主操作的结果（旧版同样忽略），失败按第 3.4 节上报。

#### 4.13 邀请注册积分（D-B09-7，`common/model/User.php:236-264`）

注册写入推广关系（B09 `setInviter`，条件 `user_pid = 0`，每个新会员只生效一次）之后，`intval(invite_reg_points) > 0` 时：邀请人 `user_points += invite_reg_points`，匹配 1 行时写 `mac_plog`（`user_id = 邀请人`、`plog_type = 2`、`plog_points = invite_reg_points`）。同一邀请人被并发注册时，加分总额与流水条数等于成功注册数。旧版在加分语句返回 0 行（邀请人恰好被删）时仍写流水，新版不写（D-B10-13）。

#### 4.14 会员组到期批量降级（B08 演练语句的正式实现）

- `MemberExpiryService.expireAll()`：`UPDATE mac_user SET group_id = 2 WHERE group_id > 2 AND user_end_time <= 现在`（旧 `User::expire`，`common/model/User.php:395-409`：等于现在**降级**，与 `checkLogin` 的 `<` 不同，B08 黄金 `expire` 段）。B14 的后台会员列表第 1 页调用同一方法。
- 定时执行是部署参数 `maccms.member.expiry-sweep-minutes`（`MACCMS_MEMBER_EXPIRY_SWEEP_MINUTES`），默认 0 = 不执行（旧版没有定时降级，只在后台列表第 1 页与会员访问时降级）；> 0 时每隔这么多分钟执行一次，日志只记命中行数。是否在生产打开请队长确认。

### 5. 内容页整合

#### 5.1 已购买判定（D-B09-11，`index/controller/Base.php:118-227`）

`content.application.ContentViewer` 在会员组之外带上**已购买查询**（`ContentPurchases`，由 site 拦截器在 `label_user` 之后设置，实现在 `member.application`，只读 `mac_ulog`）。公开 API 与游客始终"未购买"。

| 入口 | 原判定（B05/B09） | 本批 |
|---|---|---|
| 播放（`vod/play`、`vod/player`） | 组 < 3、有播放位、积分 > 0 → 需要积分 | 已购买（`ulog_mid=1, ulog_type=4, rid, sid, nid, ulog_points=积分`，`vod_points_type='1'` 时 `sid=nid=0`）→ 放行 |
| 试看（组 < 3、无播放位、有试看位、时长 > 0） | 试看 | 积分 > 0 且已购买 → 放行（旧版内页 `check_user_popedom(…, 5)` 的已购买分支）；否则仍为试看 |
| 下载（`vod/down`、`vod/downer`） | 组 1 或 2、积分 > 0 → 需要积分 | 组 2 已购买（`ulog_type=5`）→ 放行；组 1 不查（旧版 4001 直接要求登录） |
| 文章详情 | 组 < 3、积分 > 0 → 需要积分 | 已购买（`ulog_mid=2, ulog_type=1, rid, ulog_sid=当前页`（`art_points_type='1'` 时 0）、`ulog_nid=0`）→ 放行 |

注意 `ulog_points` 参与匹配：价格改动后旧的购买记录不再匹配（照旧）。

#### 5.2 购买确认页（旧 `vod/confirm`，3003/4003）

"需要积分"时（B05 的 403 固定提示页，`no-store`，严格策略）按旧版提示文字改为确认页：

| 情形 | 提示（旧版原文） | 页面 |
|---|---|---|
| 播放、组 < 3 | 观看此数据，需要支付【N】积分，确认支付吗？ | 已登录：`POST user/ajax_buy_popedom`（隐藏 `mid=1`、`id`、`sid`、`nid`、`type=4`），按钮"确认支付"；游客：去登录页的链接 |
| 下载、组 2 | 下载此数据，需要支付【N】积分，确认支付吗？ | 同上，`type=5` |
| 下载、组 1 | 此页面为收费数据，请先登录后访问！ | 去登录页的链接 |
| 文章、组 < 3 | 观看此数据，需要支付【N】积分，确认支付吗？ | 已登录：`mid=2`、`type=1`、`sid=当前页`（`art_points_type='1'` 时 0）、`nid=0` |

提交后按第 3.3 节得到提示页，成功时"继续"回到原页面（此时已购买，正常显示）。播放器内页（`vod/player`）的"需要积分"仍是框内固定提示（只换成上表文字），购买在外层页面进行。

#### 5.3 试看页的购买入口（旧 5001/5002）

已登录会员在试看模式下（播放页 TRIAL）：外层播放页的试看提示下方给出购买表单（积分 ≤ 会员积分）或积分不足的提示与充值页链接，文字用旧版：`试看结束,是否支付[N]积分观看完整数据？您还剩下[M]积分，请先充值！`（5001）/ `对不起,观看此页面数据需要[N]积分，您还剩下[M]积分，请先充值！`（5002）。播放器内页试看结束时显示同一句（`data-trial-end-message`，`trial.js` 在有该属性时用它，没有时保持 B05 的"试看已结束，请登录后继续观看。"）。游客的页面与 B05 完全相同。

#### 5.4 收藏与播放/下载记录

- **收藏**：视频详情、文章详情、专题详情、演员详情页在**已登录会员**访问时多一个"收藏"表单（`POST user/ajax_ulog`，隐藏 `ac=set`、`mid`（1/2/3/8）、`id`、`type=2`）；游客页面不变（与 B05/B09 逐字节相同）。
- **播放/下载记录**：已登录会员打开播放页或下载页（结果为放行或试看）时，服务端按第 4.1 节 `ac=set` 的规则记一笔（`mid=1`、`type=4/5`、`sid`、`nid`）——等同旧模板 `.mac_ulog_set` 在页面加载后发出的那次 `ajax_ulog` 请求（`static/js/home.js:519-523`）。记录失败不影响页面（只记一行 WARN，不含会员与参数）。收费且未购买的数据按旧规则不记（2001）。

### 6. 写库

#### 6.1 登记（`WriteRegistry.REGISTERED`，写入方矩阵：`mac_user` B09/B10/B14；`mac_order`、`mac_card`、`mac_cash`、`mac_plog`、`mac_ulog`、`mac_visit` B10/B14）

| 模块 | 表 | 操作 | 批次 | 用途 |
|---|---|---|---|---|
| `member` | `mac_user` | `UPDATE` | B09（已登记） | 本批另用于积分增减、冻结、升级、到期批量降级 |
| `member` | `mac_ulog` | `INSERT`、`UPDATE`、`DELETE` | B10 | 记录与购买认领、刷新时间、删除与补偿 |
| `member` | `mac_plog` | `INSERT`、`DELETE` | B10 | 流水、会员删除 |
| `member` | `mac_card` | `UPDATE` | B10 | 卡密认领与释放 |
| `member` | `mac_cash` | `INSERT`、`DELETE` | B10 | 提现申请、撤销与补偿插回 |
| `member` | `mac_order` | `INSERT` | B10 | 本地建单 |
| `member` | `mac_visit` | `INSERT` | B10 | 推广访问 |

写 SQL 在 `mapper-write/member/MemberFinanceWriteMapper.xml`（接口 `member.infrastructure.MemberFinanceWriteMapper`，`@WriteMapper`）；读 SQL 在 `mapper/member/MemberFinanceMapper.xml`（只读池）。

#### 6.2 语句清单

| 语句 | 条件（前置条件写进 WHERE） | 预期匹配 |
|---|---|---|
| 记录认领 `INSERT INTO mac_ulog … SELECT … FROM DUAL WHERE NOT EXISTS (七项相同)` | — | 1；0 → 已存在 |
| 刷新时间 `UPDATE mac_ulog SET ulog_time = ? WHERE` 七项 | — | ≥ 1 |
| 删除认领（补偿）`DELETE FROM mac_ulog WHERE ulog_id = ? AND user_id = ?` | — | 1 |
| 删除记录 `DELETE FROM mac_ulog WHERE user_id = ? AND ulog_type = ? [AND ulog_id IN (…)]` | — | 任意 |
| 扣积分 `UPDATE mac_user SET user_points = user_points - ? WHERE user_id = ? AND user_points >= ?` | 积分足够 | 1；0 → 积分不足 |
| 加积分 `… user_points = user_points + ? WHERE user_id = ?` | — | 1（推广人不存在为 0，按旧版跳过） |
| 退积分（补偿）同"加积分" / 收回（补偿）同"扣积分" | — | 1 |
| 冻结 `… user_points = user_points - ?, user_points_froze = user_points_froze + ? WHERE user_id = ? AND user_points >= ?` | 积分足够 | 1 |
| 解冻 `… user_points = user_points + ?, user_points_froze = user_points_froze - ? WHERE user_id = ? AND user_points_froze >= ?` | 冻结足够 | 1 |
| 升级 `… user_points = user_points - ?, user_end_time = ?, group_id = ? WHERE user_id = ? AND user_points >= ? AND user_end_time = ? AND group_id = ?` | 积分足够、到期时间与组未变 | 1 |
| 撤销升级（补偿）`… user_points = user_points + ?, user_end_time = ?旧, group_id = ?旧 WHERE user_id = ? AND user_end_time = ?新 AND group_id = ?新` | — | 1 |
| 批量降级 `UPDATE mac_user SET group_id = 2 WHERE group_id > 2 AND user_end_time <= ?` | — | 任意 |
| 流水 `INSERT INTO mac_plog (user_id, plog_type, plog_points, plog_time, plog_remarks) VALUES …` | — | 1 |
| 删除流水 `DELETE FROM mac_plog WHERE user_id = ? [AND plog_id IN (…)]` | — | 任意 |
| 卡密认领 `UPDATE mac_card SET card_sale_status = 1, card_use_status = 1, user_id = ?, card_use_time = ? WHERE card_id = ? AND card_use_status = 0` | 未用 | 1；0 → 被别人用了 |
| 释放卡（补偿）`… SET card_use_status = 0, card_sale_status = ?旧, user_id = ?旧, card_use_time = ?旧 WHERE card_id = ? AND card_use_status = 1 AND user_id = ? AND card_use_time = ?` | 仍是本次写入的值 | 1 |
| 提现 `INSERT INTO mac_cash (…) VALUES …` | — | 1 |
| 撤销提现（认领）`DELETE FROM mac_cash WHERE cash_id = ? AND user_id = ? AND cash_status = ?读到的` | 状态未变 | 1；0 → 重读 |
| 插回提现（补偿）`INSERT INTO mac_cash (cash_id, …) VALUES (原值…)` | — | 1 |
| 建单 `INSERT INTO mac_order … SELECT … FROM DUAL WHERE NOT EXISTS (同 order_code)` | 单号不重复 | 1；0 → 换单号重试（≤ 5 次） |
| 推广访问 `INSERT INTO mac_visit … SELECT … FROM DUAL WHERE (SELECT COUNT(*) FROM mac_visit WHERE user_id = ? AND visit_ip = ? AND visit_time > ?) < ?` | 当日次数未满 | 1；0 → 次数已满 |

#### 6.3 多表写入的顺序与补偿（B07 `WriteSequence`）

| 操作 | 认领 | 转移 | 记录 | 匹配 0 行 / 失败时 |
|---|---|---|---|---|
| 购买 | 记录认领（`mac_ulog`） | 扣积分 | 流水（type 8） | 认领 0 → 已购买；扣积分 0 → 删认领 → 积分不足 |
| 卡密 | 卡密认领 | 加积分 | — | 认领 0 → 卡信息有误；加积分失败 → 释放卡 |
| 升级 | — | 升级（条件更新） | 流水（type 7） | 升级 0 → 重读重试或积分不足；流水失败 → 撤销升级 |
| 提现 | — | 冻结 | 提现记录 | 冻结 0 → 积分不够；记录失败 → 解冻 |
| 撤销提现（未审核） | 条件删除 | 解冻 | — | 删除 0 → 重读；解冻 0 → 插回该行 |
| 邀请注册 | （B09 推广关系，`user_pid = 0`） | 邀请人加积分 | 流水（type 2） | 加积分 0 → 不写流水 |
| 推广访问 | 条件插入访问 | 加积分 | 流水（type 3） | 插入 0 → 次数已满；加积分 0 → 不写流水 |
| 分销（每一级） | — | 推广人加积分 | 流水（type 4/5/6） | 加积分 0 → 跳过这一级 |

- 补偿失败、结果不确定、匹配行数异常按 B07 第 4.4 节：停止补偿、保留认领、ERROR 日志一行交人工对账，不自动重试。
- 本批的认领键（B07 第 4.5 节）：购买 = 七项相同的 `mac_ulog` 记录；卡密 = `card_use_status`；撤销提现 = `mac_cash` 行本身；推广访问 = 当日计数。

#### 6.4 并发测试（`writedrill.ConcurrentWrites`，一次性库）

| 场景 | 线程 | 断言 |
|---|---|---|
| 同一会员并发购买同一数据 | 16 × 5 轮 | 每轮恰好 1 次扣积分、1 条 `mac_ulog`、1 条 type 8 流水；其余为"已经购买过" |
| 积分只够 k 次的不同数据并发购买 | 16 | 恰好 k 个成功，积分为 0 或余数，不为负；失败的不留认领 |
| 同一张卡并发使用（16 个会员） | 16 | 恰好 1 个成功、积分只加一次 |
| 并发重复提现（积分够 k 次） | 16 | 恰好 k 条记录，`冻结 = Σ cash_points`，积分不为负 |
| 并发撤销同一笔未审核提现 | 16 | 只退回一次 |
| 并发升级（积分够 k 次） | 16 | 恰好 k 次成功，到期时间 = 起点 + k × 时长，k 条 type 7 流水 |
| 同一邀请人被并发注册 | 16 | 加分 = 16 × `invite_reg_points`，16 条 type 2 流水 |
| 同一 IP 并发推广访问（每日 n 次） | 16 | 恰好 n 条访问、n 次加分 |
| 并发收藏同一数据 | 16 | 1 行 |
| 批量降级与续费并发 | 20 轮 | 续费后的会员不被降级；`<=` 边界逐行与判定一致 |

#### 6.5 对账（测试侧 `FinanceLedger`，并发测试前后与回滚演练中执行）

| 项 | 恒等式（按会员） |
|---|---|
| 积分 | `user_points + user_points_froze = 起点 + Σ 卡密积分（本会员已用的卡） + Σ 流水（type 1～6） − Σ 流水（type 7、8）` |
| 冻结 | `user_points_froze = 起点冻结 + Σ cash_points（本会员 cash_status = 0 的提现）` |
| 购买 | `Σ ulog_points（本会员 ulog_type ∈ {1,4,5} 且由购买产生的记录） = Σ 流水 type 8` |
| 卡密 | 已用卡的 `user_id > 0`、`card_use_time > 0`；同一张卡只出现一次积分 |
| 订单 | 本批新建的订单 `order_status = 0`、`order_points = intval(scale × price)`，单号不重复 |

对应的只读 SQL 写进第 10 节，供 B21 切换演练与 B14 对账页复用。

#### 6.6 回滚演练

快照 S0（`mac_user`、`mac_group`、`mac_ulog`、`mac_plog`、`mac_card`、`mac_cash`、`mac_order`、`mac_visit`）→ 本批全部 IT 写入（黄金复放、并发、故障注入）→ `DmlRestore` → diff 为空 → 表结构指纹不变 → 删库并确认不存在。

### 7. 配置

| 项 | 来源 | 说明 |
|---|---|---|
| `user.invite_reg_points`、`invite_visit_points`、`invite_visit_num`、`reward_status`、`reward_ratio`、`reward_ratio_2`、`reward_ratio_3`、`cash_status`、`cash_ratio`、`cash_min` | 旧 `extra/maccms.php` 的 `user` 段（`LegacyConfigService`，启动时读一次） | 取值规则按旧代码（`== '1'`、`intval`、`empty`、浮点运算） |
| `user.vod_points_type`、`art_points_type`、`trysee` | 同上，经 content 已有的设置（B05） | 购买与已购买判定用同一来源 |
| `pay.min`、`pay.scale` | 旧 `maccms.php` 的 `pay` 段 | 充值页与建单 |
| `pay.card.url` | 同上 | 充值页"购买充值卡"链接：只在是本站地址（相对路径或与 `base-url` 同主机）时显示；外部地址不显示——它不在路线图第 4.2 节的 W1～W3 中，按第 1.3 节先报队长（第 11 节） |
| `maccms.member.expiry-sweep-minutes` | 部署参数，默认 0 | 第 4.14 节 |

### 8. 页面、交互与共享文件归属

#### 8.1 页面与交互

| 页 | 交互 |
|---|---|
| 会员中心首页 | 资料区之外加一组导航：购买积分、升级会员、我的权限、收藏、播放记录、下载记录、积分日志、提现、推广、订单、充值卡；显示推广链接（`user/reg?uid=<id>` 与 `user/visit?uid=<id>`）。 |
| `buy` | 两个表单：卡密（`data-member-form="buy_card"`，成功后留在本页并刷新）与在线充值（`data-member-form="buy_order"`，成功后跳到订单列表，在那里"去支付"）。 |
| `pay` | 订单信息 + 四个支付方式按钮（GET 到 `gopay`）；`gopay` 返回"该支付选项未开启！"提示页，带"返回订单"链接。 |
| `upgrade` | 一个表单：会员组单选 + 时长单选；成功后刷新本页（新组与到期时间显示在首页）。 |
| `popedom` | 只读表格。 |
| `plays`/`downs`/`favs`/`ulog`、`plog`、`cash`、`reward`、`orders`、`cards` | 表格 + 分页；行内删除表单与"清空"表单（`data-member-form="delete"`，成功后刷新本页）；`cash` 页另有申请表单（成功后刷新）。`favs`/`ulog` 有模型/类型筛选链接，`reward` 有 1/2/3 级切换。 |
| `order_info` | 订单详情（只读）。 |
| 内容页 | 第 5.2～5.4 节：普通 POST 表单，提交后是提示页；游客页面不变。 |

- 会员页（`user/*` 的页面）沿用 B09 的外壳、nonce CSP 与 `member.js`：表单以 ajax 提交，结果显示在表单内的 `[data-member-message]`，成功且有 `data-success-url` 时跳转。B10 的表单都是 `form[data-member-form]`，**不需要改 `member.js` 的行为**（"刷新本页"即 `data-success-url` 指向本页）。
- 未登录：会员页一律按第 2 节给"未登录"提示页；内容页的购买确认对游客只给登录链接。
- 未配置支付：第 4.6 节；页面上不出现任何外部地址。

#### 8.2 共享文件归属（本批会改动的、非本批新建的文件）

| 文件 | 原归属 | 本批改动 | 理由 |
|---|---|---|---|
| `backend/src/main/jte/site/pages/userIndex.jte` | B09（后端） | 加 B10 导航与推广链接 | 首页入口 |
| `backend/src/main/jte/site/pages/detail.jte`、`libraryDetail.jte` | B05（后端） | 已登录会员时加"收藏"表单（`@if`，游客不输出） | 第 5.4 节 |
| `backend/src/main/jte/site/pages/play.jte`、`accessNotice.jte`（或新的确认页模板） | B05（后端） | 试看购买入口、购买确认表单 | 第 5.2、5.3 节 |
| `backend/src/main/jte/site/player/player.jte` | B05（后端） | 试看结束文字的 `data-trial-end-message` 属性（只在会员时输出） | 第 5.3 节 |
| `backend/src/main/resources/static/site/trial.js`、`backend/src/test/js/site/trial.test.js` | MACC-54（原前端，本批起由 C 维护） | 有 `data-trial-end-message` 时用它作结束文字（约 3 行）；测试加 1 项 | 第 5.3 节 |
| `backend/src/main/resources/static/site/site.css` | 前端（Kimi，B09 段）；本批起由 C 维护 | 文件末尾追加"B10"注释段，只加表格、行内删除表单、筛选链接的最少规则，复用现有变量与 `member-*` 组件；不改已有规则 | 新页面的表格与行内表单 |
| `backend/src/main/resources/static/site/member.js` | 前端（Kimi） | **不改** | 第 8.1 节 |
| `backend/src/main/jte/site/layout/`、`components/` | 前端/共用 | **不改** | — |
| `SiteUserController`、`SiteMemberInterceptor`、`PlaybackPages`、`LibraryPages`、`ContentPages` | 后端 | 路由、观看者的已购买查询、确认页与记录 | — |
| `content.application`：`ContentViewer`、`VideoPlaybackService`、`ArticleReadService`、`GroupPermission`（`ArtMapper.xml` 不改，见第 12 节） | 后端（B05/B09） | 已购买判定、积分值（文章积分值经新的 `ContentLookupService`/`ContentLookupMapper` 读取） | 第 5.1 节 |
| `common.write.WriteRegistry`、`architecture.WriteGuardTest`、`WriteRegistryTest` | B07 | 登记本批条目、断言随之更新 | 第 6.1 节 |
| `docs/architecture/09-roadmap.md` | 队长/后端 | B09 状态行改为"已验收（MACC-33，main `b5f1bbf`）"；B10 行负责方 C + K → C、状态随交付更新 | 队长指令 |
| `docs/architecture/02-agent-development-rules.md` | 后端 | 第 2 节 member 模块与 content 已购买查询的说明 | 规范同步 |

`docs/frontend/` 本批不新增文件（没有独立的前端任务）；前端检查记录写在本文第 10 节。

### 9. 与旧版的差异

| # | 旧版 | 新版 | 性质 |
|---|---|---|---|
| D-B10-1 | 白名单与比较用 PHP 宽松比较（`'01'`、`'1.0'`、`' 1'` 等于 `'1'`）；充值金额非数字串时按字符串比较、写库时报错 | 白名单按原文精确匹配；金额必须是数字串 | 只会更严 |
| D-B10-2 | `ajax_ulog`、`ajax_buy_popedom` 只返回 JSON | 非 ajax 请求返回提示页（内容页的无脚本表单） | 承接形态 |
| D-B10-3 | 写库被严格模式拒绝时 500 错误页，前序写入已生效 | 前序写入补偿，返回该入口的失败 JSON | 非法输入差异 |
| D-B10-4 | 购买、收藏、卡密、提现、撤销提现、升级、推广访问都是先查后写，并发时可重复生效或丢失更新 | 认领 + 条件更新 + 补偿（第 6 节） | 只会更严 |
| D-B10-5 | `ajax_ulog` 列表的 `user_name` 取"最新 999 个会员"，较早的会员为 `null` | 总是当前会员的用户名 | 修正缺陷 |
| D-B10-6 | 列表 `limit`、删除的 `ids` 个数无上限 | `limit` ≤ 100、`ids` ≤ 1000 | 资源上限 |
| D-B10-7 | 分页链接不带筛选参数；`reward` 的分页链接指向 `plog` | 分页保留筛选参数，`reward` 指向自己 | 修正缺陷 |
| D-B10-8 | PHP 8 下删除参数含非数字项（含"全部删除"时的空 `ids`）时 500 | 按 PHP 7.3 的结果（非数字项为 0） | 修正缺陷 |
| D-B10-9 | `pay.<方式>.appid` 已配置时跳转网关 | B24 前一律"该支付选项未开启！" | 分批缺口（B24） |
| D-B10-10 | 到期降级的那一次请求里，`popedom` 页仍按原组号显示 | 按有效会员组 | 一致性修正 |
| D-B10-11 | `order_info` 不限会员，任何登录会员可读别人的订单 | 只读本会员的订单 | 安全加固（请队长确认） |
| D-B10-12 | `visit` 的跳转只比较主机，不看协议 | 只跳 `http(s)` 且主机（含端口）与请求相同的地址 | 只会更严 |
| D-B10-13 | 邀请人加分语句命中 0 行时仍写流水 | 不写 | 对账一致 |
| D-B10-14 | 卡密按卡号卡密整体更新，同号同密的多张未用卡一起作废 | 只认领一张 | 只会更严 |
| D-B10-15 | 购买、升级、提现用请求开始时读到的积分判断 | 写库前重读，并由条件更新兜底 | 只会更严 |
| D-B10-16 | 内容页的收藏/播放记录由模板脚本在加载后请求 `ajax_ulog` | 收藏是表单；播放/下载记录由服务端在渲染页面时记 | 承接形态（模板缺失，D9-a） |
| D-B10-17 | 试看位而无播放位、已购买时，外层播放页仍是 3002（试看提示），完整播放由内页 `check_user_popedom(…, 5)` 的已购买分支给出 | 外层播放页直接按放行（不再显示"试看"提示）；内页结果相同 | 一致性修正（`PurchasePopedomGoldenTest` 中唯一的对照例外） |
| D-B10-18 | 播放器内页"需要积分"时由模板显示旧版确认文字 | 内页仍是 B05 的框内固定提示，确认与购买在外层页面（第 5.2 节） | 承接形态 |

### 10. 验证记录与验收对照

#### 10.1 PHP 黄金

`backend/src/test/php/finance_golden.php`：一次性库 `maccms_it_*`（导入 `install.sql` + `openapi/provide/seed.sql` + 第 10 节夹具），经 php-cgi 走旧 ThinkPHP 路由与 `index/controller/User.php` 本身（`legacy_http_child.php`），PHP 8.0.30 与 7.3.33 各一份：`backend/src/test/resources/member/finance-php{80,73}-golden.json`。

- `cases`：148 个 HTTP 用例（`buy`/`ajax_buy_popedom`、`ajax_ulog` 写入与列表、三种删除、`cash`、`buy` 卡密与建单、`upgrade`、`pay`/`gopay`、`order_info`、`visit`、带 `uid` 的注册），每个记录状态码、正文（≥ 500 时不记）、`Set-Cookie` 与 7 张表的行（`mac_user` 限财务相关列）。
- `popedom`：16 个 `check_user_popedom` 用例（`account_golden_child.php`，反射调用旧 `Base::check_user_popedom`），会员 99 带一组 `mac_ulog` 购买记录：整片/按集播放、试看内页 5001/5002/已购买、下载组 2/组 1、整篇/按页文章、改价后旧记录失效。返回码 `[1, 3003, 3003, 1, 3003, 1, 5001, 5002, 3002, 1, 4003, 4001, 1, 3003, 1, 3003]`。

#### 10.2 测试

| 测试 | 内容 | 结果 |
|---|---|---|
| `MemberFinanceGoldenMySqlIT`（`-Pmysql-it`） | ① 黄金逐条复放：PHP 8.0 与 7.3 各 146 例（另 2 例未登录只在页面层判断，见 `SiteFinanceMvcTest`），返回 JSON 逐字节、写库效果逐列；有意差异 8 例按第 9 节断言新版 JSON 且没有写入（D-B10-1/3/11）；PHP 8 为 500 而 7.3 正常的按 7.3（D-B10-8）。② 并发：第 6.4 节各场景（16 线程；同一数据购买 5 轮各恰好 1 次、积分只够 5 次的按集购买恰好 5 次、同一张卡 16 个会员恰好 1 次、重复提现恰好 5 条且冻结 = 500、并发撤销同一笔只退一次、升级恰好 5 次且到期 = 起点 + 5 个月、邀请注册 16 次加分 160 与 16 条流水、同一 IP 每日 3 次恰好 3 条、并发收藏 1 行），之后第 6.5 节对账。③ 故障注入：加积分溢出 → 卡按条件释放；提现记录被拒 → 解冻；升级流水被拒 → 撤销升级。④ 到期降级 `<=` 边界与 20 轮"续费 vs 批量降级"并发。⑤ 回滚演练：`DmlRestore` 51 条语句后与 S0 逐行相同、表结构指纹不变，库删除并确认不存在 | 通过 |
| `PurchasePopedomGoldenTest` | D-B09-11：`popedom` 段 16 例 × 2 个版本逐条比对已购买/未购买的判定与价格；唯一例外 D-B10-17 | 通过 |
| `SiteFinanceMvcTest`（11）、`SiteUserMvcTest` | 全部 B10 路由：未登录（6 个删除 cookie + 提示页 / JSON）、写数据源关闭时 503、JSON 字节与键顺序、非 ajax 提示页、`upgrade` 的组 cookie、`gopay` 未配置支付、`qrcode` 解码文本、`visit` 跳转白名单 | 通过 |
| `SiteFinanceContentMvcTest`（5）、`SiteFinanceLibraryMvcTest`（2） | 第 5 节：播放/下载/文章确认页（会员表单 / 游客登录链接、下载组 1 为 4001）、试看 5001/5002 与内页结束文字（文字取自黄金）、会员打开播放/下载页的记录、四类详情的收藏表单、游客没有会员表单 | 通过 |
| `MemberFinanceUnitTest`（3） | PHP 浮点转字符串（按本机 PHP 实测修正，第 12 节 R6）、`is_numeric`/`floatval`/`intval(abs())`、财务配置读取 | 通过 |
| `ReadOnlyGuardrailsTest.financeAndLookupMappersNeverSelectSecrets`、`WriteGuardTest`、`WriteRegistryTest`、`WriteInfrastructureMySqlIT`、`SiteTemplatePolicyTest`、`SiteGuardrailsTest` | 新读查询不读密码/地址/正文，卡密只作 WHERE；写入登记 12 条（B10 10 条）；模板策略只为 7 个新会员页加例外（同 B09 的 `member.js` 例外），内容页仍是严格策略 | 通过 |
| Node `node --test backend/src/test/js/site/*.test.js` | `trial.js` 新增 `data-trial-end-message` 用例（有值用它、空值用默认文字） | 24/24 |

命令与结果（最终树）：`./mvnw verify` —— <UT> 个测试全部通过；`./mvnw verify -Pmysql-it` —— 单元 <UT> + 集成 <IT>，全部通过，只用一次性库 `maccms_it_*`，结束后 `maccms\_it\_%` 为 0；没有连接 `maccms_dev`。

#### 10.3 打包 jar + 浏览器（Playwright 1.60 / Chromium，本机一次性库）

脚本与日志只留在本机（不入库）。两个 jar 都由 git 树以 LF 导出后打包：新版 = 最终工作树，基线 = 已验收 main `b5f1bbf`；一次性库 `maccms_it_b10ui_*` 导入 `install.sql` + 内容种子，可写数据源打开，旧配置目录为仓库 `application/`，`MACCMS_BROWSER_EXTERNAL_W1/W2=false`。共 66 项，全部通过：

- **B05～B09 游客页不变**：首页、分类、视频详情、免费视频的播放页与播放器内页、下载页与下载地址页、文章/专题/演员/角色/网址详情、`user/login`、`reg`、`index`、`findpass`、`member.js`，共 19 个地址与基线逐字节相同（只把 CSP nonce 归一）；收费数据的播放器内页仍与基线相同（D-B10-18）。
- **按契约变化的游客页**（4 个）：收费播放页、下载页、下载地址页、文章页从 B05 固定提示改为旧版文字 + 登录链接（第 5.2 节），状态码同为 403。
- **会员流程**：登录 → 会员中心的 B10 导航与推广链接 → 视频详情"收藏"（提示页）→ 收费播放页"确认支付"→ 提示页"继续"回到可播放的页面 → 收费文章确认 → 播放记录 1 行、收藏 1 行并用行内删除清空 → 积分日志 → 错误卡密在表单内报错、正确卡密后积分刷新 → 低于最小金额报错、建单后跳到订单列表 →"去支付"列出 4 种方式 → `gopay`"该支付选项未开启！"→ 升级一周后显示 VIP 会员 → 我的权限 → 提现低于最低金额报错、申请后 1 行、撤销后为空 → 推广会员、充值卡、订单详情。游客访问会员页得到"未登录"与登录链接；`visit?uid=7` 跳回本站首页。
- **对账**：会员 7 积分 = 100 + 1（推广访问）− 5（购买）+ 30（卡）− 5（升级）= 121、冻结 0、组 3；流水依次为 3/1、8/5、7/5；1 条 `mac_visit`；订单未支付、20.00 元、20 积分。
- **键盘**：充值页从头按 Tab 依次到达卡号、卡密、"充值"、金额、"生成订单"，每个获得焦点的元素都有可见的焦点样式。
- **宽度**：320/768/1280 下 13 个会员页、订单详情、视频与文章详情都没有横向溢出；截图（充值、收藏、积分日志、升级、确认页）只留在本机。
- **外部请求**：全程 0 个；没有脚本错误与 CSP 违规。非 2xx 响应只有预期内的 403 确认页，以及 jar 不提供的旧站 `/static/` 资源（默认头像、播放器的 `jquery.js`/`player.js`，部署时由 nginx 提供，与 B05/B09 相同）。
- **收尾**：应用日志 28 行、0 行 ERROR、不含 JDBC 地址；表结构指纹前后相同；两个 jar 进程已停，一次性库已删除并确认 `maccms\_it\_%` 为 0，临时目录已删。

这一轮发现并修正了一个问题：`detail.jte`、`libraryDetail.jte`、`play.jte` 新增的 `@if` 块在游客页面多输出 1～3 个空白字符（本项目的 jte 不裁剪控制行），使游客页面与 B09 不再逐字节相同；改为把块接在前一个控制结构或模板结尾上，复测后逐字节相同。

#### 10.4 验收对照

| # | 验收项 | 结果 |
|---|---|---|
| 1 | `ajax_ulog`、收藏/播放/下载记录与删除 | ✅ 第 4.1、4.3、4.4 节；黄金复放、MockMvc、浏览器 |
| 2 | 积分日志与删除 | ✅ 同上 |
| 3 | `ajax_buy_popedom`、`popedom`、`upgrade`、卡密充值、提现申请与撤销、推广奖励与访问 | ✅ 黄金复放、并发、浏览器 |
| 4 | 订单列表与详情；`buy` 本地建单 | ✅ 不发起任何支付请求（D-B10-9） |
| 5 | 到期批量降级任务 | ✅ `MemberExpiryService`，`<=` 边界与并发续费；定时执行默认关闭（第 11 节待确认） |
| 6 | D-B09-7 邀请注册积分 + `mac_plog`，PHP 黄金与并发测试 | ✅ `reg.*` 黄金用例、16 线程并发 |
| 7 | D-B09-11 组 ≤ 2 按 `mac_ulog` 判定已购买，购买前后对照 PHP 黄金 | ✅ `PurchasePopedomGoldenTest`（唯一差异 D-B10-17） |
| 8 | 写入方矩阵 `mac_user`、`mac_order`、`mac_card`、`mac_cash`、`mac_plog`、`mac_ulog`、`mac_visit` | ✅ 第 6.1 节，`WriteRegistry` 12 条 |
| 9 | 并发重复操作、对账、一次性库回滚、未配置支付 | ✅ 第 10.2 节 |
| 10 | 前端：一次性库上端到端（表单提交、错误提示、未登录跳转）、键盘焦点、320/768/1280、无外部请求、B05～B09 页面不变 | ✅ 第 10.3 节 |
| 11 | `./mvnw verify`、`-Pmysql-it`（只用 `maccms_it_*`） | ✅ 第 10.2 节 |
| 12 | `git diff --check`、UTF-8 无 BOM、路径检查 | ✅ 提交前执行（见任务评论） |
| 13 | 边界：不连生产/共享库、不部署、不改 nginx 与旧 PHP、不做 DDL、不用 `maccms_dev` 做集成测试、外部调用只用本地替身 | ✅ |

### 11. 风险与待确认

- **MyISAM 多表写入**：中途失败靠补偿与对账（第 6.3、6.5 节），补偿失败需人工处理；后台对账项在 B14。
- **`pay.card.url` 外部链接**：旧版充值页可链到站外卖卡页。它不在 W1～W3 中，本批只显示本站地址，外部地址是否放行请队长确认（路线图第 1.3 节"新增外部访问点先停下"）。
- **`order_info` 加会员条件**（D-B10-11）与**定时批量降级默认关闭**（第 4.14 节）请队长确认。
- 会员组价格列 `group_points_week` 是有符号列（install.sql），负值时的行为（旧版会"加分升级"、流水写入失败）不在本批处理，按第 3.4 节上报。
- **未在浏览器里验证的**：试看的购买入口与内页结束文字（仓库配置 `user.trysee = 0`，由 `SiteFinanceContentMvcTest` 与 `trial.test.js` 覆盖）；只测了 Chromium，没有测 Firefox、Safari 与真机；真实支付网关不在本批（B24）。
- **已有行为（非本批引入）**：浏览器在响应写完前离开页面时，B05 的 `SiteExceptionHandler` 把 `AsyncRequestNotUsableException`（客户端中断）记为一行 ERROR。浏览器检查的一次中间运行出现过 1 行，最终运行为 0 行；是否降为 DEBUG 归站点模块，未在本批修改。

### 12. 契约修订记录

| # | 契约稿 v1 | 实施 | 原因 |
|---|---|---|---|
| R1 | 文章积分值经 `ArtMapper.xml` 读出（第 8.2 节） | `ArtMapper` 仍只给布尔值；积分值与按页积分经新的 `content.application.ContentLookupService`（`ContentLookupMapper.xml`，只读积分与标题、分类树）读取，member 的购买、记录列表、权限页也只经它查内容 | `ReadOnlyGuardrailsTest.libraryMappersNeverSelectInternalColumns` 禁止 `ArtMapper` 读出 `art_points*` 的值；新护栏 `financeAndLookupMappersNeverSelectSecrets` 锁定新查询不读地址、密码、正文，卡密只作 WHERE 比较 |
| R2 | 播放器内页的"需要积分"换成第 5.2 节的文字 | 内页保持 B05 固定提示（D-B10-18） | 内页没有表单与登录入口，换文字后"确认支付吗？"无法操作 |
| R3 | 试看已购买时外层页按试看 | 按放行（D-B10-17） | 与内页结果一致 |
| R4 | 第 2 节 `page` 上限 1000 | 100000（`FinanceSupport.MAX_PAGE`） | 稿中笔误；按实现与测试 |
| R5 | `qrcode` 生成方式未定 | `site.web.QrcodeImages.weixinPng`（ZXing core，纠错 L、静区 4 模块、ISO-8859-1 字节），`SiteFinanceMvcTest` 按解码文本验收 | 同 B06 的做法 |
| R6 | — | `PhpNumbers.phpString` 按本机 PHP 7.3/8.0 实测修正：指数 ≥ 14 用 `1.0E+14` 形式、14 位有效数字逢 5 取偶（`MemberFinanceUnitTest`） | 只影响 ≥ 1e14 的金额文字；黄金用例没有覆盖到 |
| R7 | — | 内容页接线测试 `SiteFinanceContentMvcTest`（播放/下载/试看/视频详情）与 `SiteFinanceLibraryMvcTest`（文章确认、文章/专题/演员收藏）；提示文字取自 PHP 黄金 `popedom` 段 | 第 10 节 |
