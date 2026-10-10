## 03 重构方案草稿（待用户确认，语言未定）

> 本方案和语言无关，Go 和 Java 都适用（选型见 02）。凡是标注"待确认"的地方，要等用户回答 02 第 5 节的问题后才能定稿。
>
> **本文全部是设计草稿，没有任何一项已经实现或部署。** 文中的新增表和字段（`sys_config`、`collect_bind`、`admin_permission`、`user_pwd_v2` 等）、PHP 侧写入守卫、配置导出脚本、兼容钩子、权限映射表，都是**待实施事项**；其中凡是要改 PHP 业务代码的，都需要用户另外批准。
>
> 原则：**先兼容、后改进**；**按"表 + 操作"确定唯一写入方，切换前先封住旧入口**；**回滚要有门槛和验证步骤，耗时以演练结果为准**；**旧系统下线前不做破坏性的库结构变更**。

### 1. 模块边界（模块化单体）

| 模块 | 职责 | 对应旧代码 | 拥有的表 |
|---|---|---|---|
| `content` | 视频、文章、专题、演员、角色、网址、分类、友链；扩展字典；播放串编解码；点击/顶踩/评分计数 | `common/model/{Vod,Art,Topic,Actor,Role,Website,Type,Link,Extend}.php`、`common.php` 中的 `mac_play_list*`、`index/controller/Ajax.php` 的 hits/digg/score | `mac_vod/art/topic/actor/role/website/type/link`、`mac_vod_search` |
| `site` | 前台渲染、模板标签、SEO URL 生成与解析、播放页（`player_aaaa`）、sitemap/rss、静态生成 | `index/controller/*`、`common/taglib/Maccms.php`、`common/controller/All.php`、`mac_url*`、`admin/controller/Make.php` | 无，只读 |
| `member` | 会员、会员组权限、登录态、积分、订单、卡密、提现、行为日志、推广、短信/邮件验证码 | `common/model/{User,Group,Order,Card,Cash,Plog,Ulog,Visit,Msg}.php`、`index/controller/User.php`、`index/controller/Payment.php` | `mac_user/group/order/card/cash/plog/ulog/visit/msg` |
| `interact` | 评论、留言 | `Comment`、`Gbook` | `mac_comment/gbook` |
| `collect` | 资源站采集（JSON/XML）、分类绑定、合并规则、图片同步、自定义规则采集 | `admin/controller/{Collect,Cj}.php`、`common/model/{Collect,Cj}.php`、`util/Collection.php` | `mac_collect`、`mac_cj_*`；拟新增 `collect_bind` |
| `openapi` | Provide（对外资源站）和 Receive（入库）协议兼容 | `api/controller/{Provide,Receive}.php` | 无 |
| `admin` | 管理员、权限、系统配置、定时任务、数据库工具、附件 | `admin/controller/*` | `mac_admin`、`mac_annex`；拟新增 `sys_config`、`admin_permission` |
| `integration` | 支付、短信、邮件、对象存储、搜索引擎推送、第三方登录的适配器 | `common/extend/*` | 无 |

部署形态是一个 web 进程，加上一个 worker 进程（负责 collect、make、urlsend、定时任务），两者来自同一个代码库。

### 2. 数据库：先沿用

**阶段 A（并行期）：原样沿用 `mac_*` 表结构**
- 新系统直接读写旧库。
- 只允许**增量、向后兼容**的变更：新增表、新增可空列、新增索引。
- 不允许改名、删列、改类型。

**拟新增的表或列（待实施；迁移脚本和反向脚本在实施阶段编写，这里只列清单）**
- `sys_config`：把 `application/extra/*.php` 中的配置迁到库里，每一组是一行 JSON，并带版本号。何时成为配置的唯一来源见第 3.5 节。
- `collect_bind`：把 `extra/bind.php` 迁到库里。
- `admin_permission`：把 `admin_auth` 的文本拆成行。
- `mac_user.user_pwd_v2`、`mac_user.user_pwd_v2_src`（均可空）：密码渐进升级，规则见第 4.3 节。

**表引擎转换：MyISAM 改为 InnoDB**
- 作为一个**独立的维护步骤**执行，不和任何代码切换放在同一个窗口。
- 执行前：全量备份；在副本上演练并计时；确认线上是否开启 binlog（待核实）。
- **备份之后的新写入怎么保全**，二选一，要在演练时定下来：
  - 维护窗口内停写。`app.site_status`（`application/extra/maccms.php:40`）只能关前台，后台、`api.php` 的 Receive/Timming、支付回调是否都能停住**待核实**，可能还要在 nginx 层临时拒绝写入路径；
  - 不停写，靠"全量备份 + binlog 增量"恢复到任意时间点。前提是线上已开 binlog，并且演练过回放。
- **回滚**：从备份恢复会丢掉备份之后的写入，除非用 binlog 补齐或者窗口内停了写。`ALTER TABLE ... ENGINE=MyISAM` 技术上可行，但它要重建整张表，耗时和锁表时间都未知，**没演练过就不能当成安全回滚手段**。
- **不可逆边界**：一旦新系统的资金类逻辑（第 3 节阶段 4）开始依赖 InnoDB 事务，就不再支持退回 MyISAM。如果必须退，只能连同阶段 4 一起回退到 PHP 写入，并重新对账。

**字符集转换：utf8 改为 utf8mb4**
- 和上面一样作为独立步骤；先确认 `varchar(255)` 加索引后不超过索引长度限制。
- PHP 的连接字符集是 `utf8`（`application/database.php:29`）。表改为 utf8mb4 之后，PHP 读到的 4 字节字符（例如 emoji）会显示异常，所以 PHP 的连接字符集要不要同步改，需要一起演练。
- **不可逆边界**：转换之后，一旦有 4 字节字符写入，再转回 utf8 就会丢字符或报错（取决于 `sql_mode`），**不能无损回转**。出问题时要分开看两种"回滚"：
  - **数据恢复（推荐的处理方式）**：表保持 utf8mb4，只恢复出错的数据（从备份 + binlog 增量恢复到出错前的时间点），同时演练 PHP 用 `utf8`、`utf8mb4` 两种连接字符集读写 utf8mb4 表时的表现，确认 PHP 能继续运行。这不是"退回 utf8"。
  - **退回 utf8**：只有在**还没有任何 4 字节字符写入**时才可以无损执行（执行前要用查询核实）。一旦写入过，就不支持无损退回。**不能**用"恢复转换前的旧备份"来实现所谓的安全回滚：那样会丢掉转换之后的全部写入，补增量也无济于事，因为旧的 utf8 列本来就存不下这些 4 字节字符。
  - 因此，是否接受"写入 4 字节字符之后只能保留 utf8mb4"，要由用户在转换前确认。

**阶段 C（旧系统下线以后）**：才考虑重新设计表结构，例如把播放串拆成 `vod_source` 和 `vod_episode` 两张表。到那时提供正式的迁移脚本和反向脚本。

### 3. 新旧并行、写入方与迁移顺序

前面放一层 nginx，按路由把流量逐步切到新系统（绞杀者模式）。核心约束是：**任一"表 + 操作"在任一时刻只有一个写入方**。只停旧的定时任务是不够的，旧后台、旧接口、前台 Ajax、数据库工具都会写同一张表，下面逐一列出。

#### 3.1 旧系统的写入入口清单（代码静态核对）

| 表 / 操作 | 旧系统的全部写入入口 |
|---|---|
| `mac_vod` 内容字段（新增、修改、删除、播放串） | 后台 `Vod::info` 保存（`admin/controller/Vod.php:366-370`）；`Vod::batch` 批量修改（`:141-347`，`:347` 执行 update）；`Vod::del`、`Vod::field`、`Vod::iplot`（`:236,448,490-499,418`）；后台资源站采集（`admin/controller/Collect.php:281` → `common/model/Collect.php:736` insert、`:982` update）；自定义规则采集 Cj（`admin/controller/Cj.php:428`）；入库接口 Receive（`api/controller/Receive.php:54`）；定时任务触发的采集（`api/controller/Timming.php`，翻页在 `Collect.php:1012`）；数据库工具的执行 SQL、批量替换、还原（`admin/controller/Database.php:237-265,287-314,141`） |
| `mac_vod` 计数字段（hits、hits_day/week/month、up、down、score*） | 前台 `Ajax::hits`（`index/controller/Ajax.php:152-203`，先读后写）、`Ajax::digg`（`:302-323`，setInc）、`Ajax::score`（`:347-379`，先读后写）；Provide 详情计数（`api/controller/Provide.php:143-150`，`detail_inc_hits` 开启时）；`updateToday`（`admin/controller/Vod.php:510-514`、`admin/controller/Collect.php:175`、`admin/controller/Make.php:87,245`）；**后台 `Vod::info` 表单本身带计数字段**（`admin/view/vod/info.html` 中有 22 处 hits/up/score），保存时整行覆盖；采集新增时按配置随机生成 hits（`configcollect.html:33-38`） |
| `mac_vod_search` | 前台搜索（`common/model/Vod.php:404` 起，搜索优化开启时写缓存）、采集（`Collect.php:431`）、新增视频（`Vod.php:725`）、后台清缓存（`admin/controller/Index.php:162`） |
| `mac_user` | 前台注册、登录、改资料、密保找回、邮件/短信找回、绑定和解绑、升级会员组、头像（`index/controller/User.php` 各动作，头像在 `common/model/Upload.php:209`）；购买观看权限扣积分（`index/controller/User.php:155`）；推广访问奖励（`User.php:848` 起，`index/controller/User.php:909`）；OAuth 回调建号与绑定（`index/controller/User.php:236-274`）；登录时的到期降级（`common/model/User.php:362-365`，随登录一起 update）；**隐式写入：前台每个请求的登录检查**——`index` 模块除 `Qrcode`、`Verify` 外的所有控制器都继承 `index/controller/Base.php`，其构造函数调用 `label_user`（`Base.php:17`）→ `common/controller/All.php:131` `User::checkLogin`，会员组到期时执行 `UPDATE group_id=2`（`common/model/User.php:455-466`）。这包括 `Ajax`（`index/controller/Ajax.php:5,11`）的 hits/digg/score/referer/pwd 等全部动作、播放页、观看密码页、`Payment`；`Comment`、`Gbook`、`User` 还会再显式调用一次（`Comment.php:50`、`Gbook.php:57`、`index/controller/User.php:27,232`）；支付回调加积分（`common/model/Order.php:138`）；卡密充值（`Card.php:142`）；提现冻结（`Cash.php:180`）；后台 `User::info`（`admin/controller/User.php:142` → `User::saveData`）、`User::del`（`:173`）、`User::field`（`:193`）；**隐式写入：后台会员列表**——`User::data` 是查看动作，但第 1 页会调用 `User::expire` 批量降级（`admin/controller/User.php:18-20` → `common/model/User.php:395-409`）；数据库工具 |
| `mac_order/card/cash/plog/ulog/visit/msg` | 前台会员中心与 `ajax_ulog`、`ajax_buy_popedom`（`index/controller/User.php:47-170`）；支付回调（`index/controller/Payment.php:13-30` → `common/extend/pay/*` → `Order::notify`）；后台 Order/Card/Cash/Plog/Ulog/Visit 各控制器；数据库工具 |
| `mac_comment`、`mac_gbook` | 前台发表（`index/controller/Comment.php:103`、`Gbook.php:103`）；后台编辑（`admin/controller/Comment.php:60`、`Gbook.php:61`）；评论采集（`common/model/Collect.php:2433,2457`）；Receive 的 comment 接口；数据库工具 |
| 配置文件 | 系统配置 `System::config*` 写 `extra/maccms.php`（`admin/controller/System.php:126-719` 共 15 处）和 `route.php`（`:231`）；分类绑定写 `extra/bind.php`（`admin/controller/Collect.php:194,222`）；定时任务写 `extra/timming.php`（`admin/controller/Timming.php:34,53,76`，**`api/controller/Timming.php:42` 每次执行都会回写 runtime**）；播放器配置生成 `static/js/playerconfig.js`（`admin/controller/Base.php:94-102`、`System.php:675-690`） |

**带写入副作用、但可以用 GET 触发的入口**【静态】。这些入口用 `input()` 或 `mac_param_url()` 取参，代码里没有看到请求方法限制：`ajax/hits`、`ajax/digg`、`ajax/score`、`ajax/referer`（网址访问计数，`Ajax.php:250`）、`user/ajax_ulog`、`user/ajax_buy_popedom`（扣积分）、`user/visit`（推广奖励）、`api.php/provide/vod?ac=detail&ids=`（开启计数时）、`api.php/timming/index`。另外，前台的普通页面请求也不是绝对只读：登录检查可能写 `group_id`，搜索可能写 `mac_vod_search`。**影子流量和黄金样本采集都不能把这些请求当作只读。**

#### 3.2 阶段划分

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 | 先核实 01 第 10 节第 1～4 条安全问题在线上是否存在，存在则在 PHP 端修复（需用户确认）；补齐仓库缺失的 `vendor`、模板和脱敏配置；在**一次性的库副本**上生成黄金样本（第 5 节）；准备 PHP 侧写入守卫、兼容改动（清单见 3.7）和配置导出脚本（待批准） | 样本集已产出；守卫在测试环境验证过 |
| 1 | 新系统上线**只读前台**：首页、分类、筛选、详情、播放、搜索、sitemap、rss | 黄金样本 URL 和页面数据一致；404/5xx 和蜘蛛抓取正常；新系统的库账号只有 SELECT 权限 |
| 2 | `openapi`：Provide 对外接口，协议逐字段对比 | 同一请求的新旧输出 diff 为空，允许字段顺序不同 |
| 3 | `collect` worker 接管**抓取**（推荐方案甲，见 3.3） | 影子期抓取结果 diff 为零或可以解释；PHP 侧采集入口已封住 |
| 4 | `member` + `interact`：会员、积分、订单、卡密、提现、评论、留言、支付回调，**连同它们的后台管理页一起**切换 | 资金类对账一致；PHP 侧对应入口已封住；C1 已启用，3.3.1 的隐式写入验收用例全部通过 |
| 5 | `content` 写入 + 其余 `admin`：内容编辑、计数、采集合并逻辑、Receive、系统配置、定时任务、管理员 | 后台逐功能清单验收；配置唯一来源切换完成 |
| 6 | 下线 PHP，进入阶段 C 的库结构优化 | — |

#### 3.3 各阶段写入方矩阵

"封堵"指用 3.4 节的手段让旧入口返回 403 或维护提示；"转发"指旧入口的请求由 nginx 送到新系统处理。

| 表 / 操作 | 阶段 0～2 | 阶段 3 | 阶段 4 | 阶段 5 起 | 切换时旧入口的处理 | 回滚恢复 |
|---|---|---|---|---|---|---|
| `mac_vod` 等内容字段 | PHP | **PHP**（新 worker 只抓取，写入经 PHP 入库端点，见方案甲） | PHP | 新系统 | 阶段 5：封堵后台 Vod/Art/… 写动作、Collect/Cj 执行、Receive、Database 的 sql/rep/import；Receive 的 URL 转发给新系统 | 按 3.6 节流程，停新写入 → 排空 → 对账 → 解除 PHP 封堵 |
| 内容计数字段 | PHP | PHP | PHP | 新系统（和内容字段一起切） | 阶段 5：`ajax/hits`、`digg`、`score`、`referer`、Provide 计数转发给新系统 | 同上；计数是近似值，允许丢失回滚窗口内的增量（需用户接受） |
| `mac_vod_search` | PHP | PHP | PHP | 新系统 | 阶段 1～4 新系统不写这张表，搜索结果缓存放在自己的 Redis | 新系统缓存直接丢弃 |
| `mac_user` 及会员财务表（显式写入） | PHP | PHP | **新系统** | 新系统 | 阶段 4：封堵前台 `user/*`、`ajax_buy_popedom`、`ajax_ulog`、`user/visit`，并转发给新系统；支付回调路径转发；后台 User/Group/Card/Order/Cash/Plog/Ulog/Visit **整个控制器**封堵，包括查看动作（`User::data` 查看时也会写库），新后台同时提供这些页面；Database 工具对这些表封堵 | 3.6 节流程；额外核对密码指纹（4.3）和支付回调在途 |
| `mac_user.group_id` 到期降级（隐式写入） | PHP（登录检查、后台列表） | PHP | **新系统**；PHP 改为纯内存判定（兼容改动 C1，见 3.3.1） | 新系统 | 阶段 4：启用 C1 开关，PHP 剩余前台页面（Ajax 计数、观看密码、播放页等）照常服务但不再写 `mac_user`；后台 `User::data` 随整个 User 控制器封堵 | 关闭 C1 开关、停新系统的到期任务，PHP 恢复写入；降级规则两边相同，只需核对"到期但 `group_id>2`"的账号数（见 3.3.1） |
| `mac_comment`、`mac_gbook` | PHP | PHP | **新系统** | 新系统 | 阶段 4：前台发表、后台编辑、评论采集、Receive comment 一并转发或封堵 | 同上 |
| 文件配置 / `sys_config` | PHP 文件 | PHP 文件 | PHP 文件 | `sys_config` | 见 3.5 节 | 见 3.5 节 |
| 库内配置：`mac_group` | PHP 后台 | PHP 后台 | **新后台** | 新后台 | 阶段 4：后台 Group 控制器整体封堵 | 见 3.5 节 |
| `mac_admin`（含 `admin_pwd`、`admin_auth`） | PHP | PHP | PHP（新后台登录时只读，见 4.3 (7)） | 新系统 | 阶段 5：封堵 PHP 的 Admin 控制器、后台登录与锁屏解锁、安装入口，并让全部 PHP 管理员 session 失效 | 3.6 节流程；所有管理员在 PHP 重新登录；v2 靠指纹作废（4.3 (7)） |

**阶段 3 的两种方案**

- **方案甲（推荐）**：新 worker 负责抓取、解析、并发、限速、断点续采和过滤；**入库仍然调用 PHP 的合并逻辑** `Collect::vod_data`，所以 PHP 仍是 `mac_vod` 的唯一写入方。
  - 现有的 Receive（`api/controller/Receive.php:54`）调用 `vod_data([], $data, 0)`，传入的是空参数，不会套用采集源的 `filter`、`filter_from`、`opt`、`sync_pic_opt`（`Collect.php` 第 420 行起的 `vod_data` 依赖这些参数）。因此需要新增一个**仅内网、强鉴权**的入库端点，接收 `collect_id` 并套用该采集源的参数。这是 PHP 业务代码改动，待用户批准。
  - 提交入库必须**串行**。PHP 的查重是"先查后插"，MyISAM 没有事务，同一条视频并发提交可能产生重复记录。抓取可以并发，提交排队。
  - PHP 侧封堵：后台 `Collect::api`、`Collect::timing` 和 `Cj` 的采集执行动作；`timming.php` 中的采集任务设为停用。绑定、采集源和规则的编辑仍然留在 PHP 后台（配置来源见 3.5）。
  - 回滚：停 worker，等在途提交完成，解除 PHP 侧采集入口的封堵，恢复定时任务。因为写入方一直是 PHP，**不需要把数据回迁到 PHP**；但仍要核对：在途提交是否都已处理完（没有提交到一半的批次）、切换窗口内有没有产生重复记录（串行提交出过故障时尤其要查）、各采集源的断点位置，以便 PHP 采集从正确的页码继续。
- **方案乙**：阶段 3 就让新系统成为 `mac_vod` 的唯一写入方。这要求同时封堵后台 Vod 编辑、Receive、Cj、Database 工具，相当于把阶段 5 的内容后台提前，工作量和风险都更大，不推荐。

合并逻辑（inrule、uprule a～w）迁到新系统放在阶段 5。届时先做"影子合并"：新系统对同一批抓取数据算出合并结果但不写库，和 PHP 实际写入的结果做 diff。

**阶段 1 里，会员权限和收费播放怎么处理**
- 新前台只读：用只有 SELECT 权限的库账号，技术上保证不写库。
- 登录态：新系统按旧算法**只校验、不签发**会员 cookie（`user_check = md5(user_random-用户名-ID-)`，`common/model/User.php:446`）。校验失败时当作游客处理，**不像 PHP 那样回写 cookie**（`common/controller/All.php:136-138`），避免两边互相覆盖。
- 会员组到期：在内存里按旧规则计算有效会员组（`group_id>2` 且 `user_end_time` 已过期时按 2 处理，`User.php:455-466`），**不写库**。真正的降级写入仍由 PHP 在下一次 PHP 请求时完成，可见效果相同。阶段 4 起写入方改变，见 3.3.1。
- 播放、下载、试看权限：新系统只读取 `mac_group.group_popedom`、`mac_ulog`（是否已购买）等表来判定。需要付费确认时，页面上的确认按钮仍然调用 PHP 的 `user/ajax_buy_popedom`，由 PHP 扣积分、写 `mac_ulog` 和 `mac_plog`。
- 登录、注册、退出、会员中心、`ajax/*`、评论、留言、支付回调：全部仍由 nginx 路由到 PHP。
- **设了观看密码的内容**依赖 PHP session（`common/controller/All.php:547-549`、`index/controller/Ajax.php:400-456`），新系统读不到，这类请求由新系统内部回源给 PHP 处理，或者在 nginx 层按规则留在 PHP。

#### 3.3.1 阶段 4 的隐式会员写入（兼容改动 C1，待批准）

**问题**【静态】：阶段 4 起 `mac_user` 的唯一写入方是新系统，但 Ajax 计数（hits/digg/score/referer）、观看密码（`ajax/pwd`）、播放页（`vod/player`、`vod/downer`）等还留在 PHP，直到阶段 5。这些请求在构造函数里就会走 `label_user` → `checkLogin`（见 3.1），遇到已到期会员时执行 `UPDATE mac_user SET group_id=2`（`common/model/User.php:455-466`）。只封堵 `user/*` 挡不住这条路径。而数据库权限兜底（3.4 第 3 条）在这里也不会变成"兼容的只读行为"：UPDATE 被拒绝时，`checkLogin` 返回 1004 或者抛出异常（取决于 PDO 错误模式，未实测）。返回 1004 时，`label_user` 会把会员 cookie 清成游客（`All.php:135-139`），用户表现为被登出；抛异常时页面直接报 500。后台的 `User::data` 也一样，它只是查看列表，但会触发 `expire`（`admin/controller/User.php:18-20`）。

**选定方案**：阶段 4 起 PHP 的到期处理改为纯内存判定，新系统成为降级写入的唯一执行方；后台会员列表随整个 User 控制器封堵。不采用"阶段 4 前把这些前台入口全部迁走或返回 403"，因为那样 Ajax 计数和观看密码在阶段 4 就会失效，或者要把内容写入提前到阶段 4。

- **C1：`checkLogin` 的到期分支加一个开关**（PHP 业务代码改动，待批准）。开关放在 3.4 节写入守卫的同一个配置文件里，切换和回滚都只改这个文件。
  - 开关关闭（阶段 0～3、回滚后）：行为和现在完全一样。
  - 开关打开（阶段 4 起）：到期时只在内存里设置 `$info['group_id']=2`、`$info['group']=$group_list[2]`，**不执行 UPDATE**，返回 code 1。`group_id`、`group_name` 两个 cookie 照旧下发（`User.php:467-468`），只写 cookie，不写库。
  - 和旧版的差异：旧版在降级的那一次请求里只换了 `$info['group']`，没有改 `$info['group_id']`；从下一次请求起，因为库里已经是 2，两者都是 2。C1 在每次请求中都把两者设为 2，等同于旧版"降级之后"的稳定状态。权限判定 `check_user_popedom` 读的是 `$GLOBALS['user']['group']`（`index/controller/Base.php:92-95`），结果和旧版一致。
- **到期边界**：`checkLogin` 用 `user_end_time < time()`（`User.php:455`），而 `expire` 用 `<=`（`User.php:399` 的 `elt`），两者差 1 秒。新系统的内存判定统一用 `<`，与前台一致；批量任务的边界要用黄金向量确认（`user_end_time == now`）。
- **新系统的降级写入**：新系统自己的登录检查同样只在内存里判定；另由 worker 定时执行一次与 `User::expire` 等价的批量更新：`UPDATE mac_user SET group_id=2 WHERE group_id>2 AND user_end_time<?`。这是单条语句，MyISAM 下也是原子的。新后台的会员列表显示有效会员组（内存判定的结果），所以批量任务的执行时间不影响显示。
- **观看密码在阶段 4 仍可用**：`ajax/pwd` 只比较内容表的密码字段，并写 PHP session（`index/controller/Ajax.php:400-456`），不写 `mac_user`。播放页读取同一个 session 判断（`common/controller/All.php:547-549`）。C1 打开后，这些请求对 `mac_user` 只有 SELECT。
- **启用顺序**：C1 部署并验收通过 → 打开开关 → 收回 PHP 库账号对 `mac_user` 的 UPDATE 权限（保留 SELECT）。**顺序不能反**，否则会出现上面说的登出或 500。
- **回滚**：关闭开关，PHP 恢复写入；停用新系统的批量到期任务。两边规则相同，只需核对"已到期但 `group_id>2`"的账号数，这些账号会在 PHP 下一次登录检查或后台列表时被降级。

**验收用例**（测试等实施获批后再写，这里只定义）：
1. 已到期会员（`group_id>2`、`user_end_time` 已过）的有效 cookie，访问仍留在 PHP 的 `ajax/hits`、`ajax/digg`、`ajax/score`：计数照常变化，响应和旧版一致；该会员在 `mac_user` 中的整行前后不变（逐列比对）；cookie 没有被清成游客。
2. 同一 cookie 访问 `ajax/pwd`，提交正确的观看密码：返回 code 1；随后访问 PHP 播放页，按会员组 2 的权限判定放行或拒绝，结果与旧版"降级之后"一致；`mac_user` 不变。
3. 同一 cookie 访问会员组 2 没有播放权限的内容：拒绝方式和旧版一致。
4. 收回 PHP 库账号对 `mac_user` 的 UPDATE 权限后，重复 1～3：全部通过，没有 500，没有被登出。
5. 未到期的 VIP：权限按自己的会员组判定，不受影响。
6. 边界：`user_end_time` 等于当前时间。
7. 旧后台入口（`_admin.php` 或改名后的入口）的 `user/data`，包括 `?s=` 等各种 URL 写法，被守卫拦截，`mac_user` 不变。
8. 关闭 C1 开关并恢复权限后，PHP 恢复降级写入，和旧版行为一致。

#### 3.4 封住旧入口的手段

只靠 nginx 按 URL 拦截不可靠，原因是同一个动作有多种 URL 写法：PATHINFO、`?s=` 参数（`application/config.php:80` `var_pathinfo='s'`）、`route.php` 中的路由别名、多种后缀（`config.php:86`），而且后台入口文件名可以随意改（`_admin.php`）。建议组合使用：

1. **PHP 侧写入守卫（推荐，待批准）**：在已有的 `app_begin` 行为（`application/tags.php`、`common/behavior/Begin.php`）里，按路由解析之后的"模块/控制器/动作"对照一个封堵清单，命中就返回 403 或维护提示。路由解析之后再判断，就能覆盖所有 URL 写法。清单放在一个配置文件里，切换和回滚只改这个文件。
2. **nginx 路由**：负责把需要转发的路径送到新系统。它只是辅助，不作为唯一的封堵手段。
3. **数据库账号兜底**：MySQL 5.7 支持表级权限，可以收回 PHP 账号对"已归新系统所有"的表的 INSERT/UPDATE/DELETE 权限。这是最硬的保证，但 PHP 中剩下的任何隐式写入（例如登录检查的到期降级）都会失败，而且失败的表现不是"只读"，而是登出或 500（见 3.3.1）。所以要先用兼容改动消除这些隐式写入（如 C1），在测试环境确认 PHP 侧已经没有对这些表的写入路径，再启用。
4. **数据库工具**（`admin/controller/Database.php` 的 sql、rep、import）能写任何表。从阶段 3 起，对这几个动作要么封堵，要么只在维护流程中由超级管理员使用。

#### 3.5 配置的唯一来源

配置分两类，归属分开确定：**文件配置**（`application/extra/*.php`、`route.php`、`static/js/playerconfig.js`）和**库内配置**（存在表里的会员组、采集源、规则等）。

| 配置 | 阶段 0～3 | 阶段 4 | 阶段 5 起 | 新系统在 PHP 归属期间怎么拿到 |
|---|---|---|---|---|
| 文件配置：`maccms.php`（含 `user.*` 会员设置、`pay.*`、`collect.*` 等）、`bind.php`、`timming.php`、`vodplayer/vodserver/voddowner.php`、`route.php` | PHP 后台（`System::config*`、`Collect::bind`、`Timming`） | **仍是 PHP 后台**。阶段 4 的新会员模块要用的 `user.*`、`pay.*` 等设置，仍在 PHP 后台的 `System::configuser`、`configpay` 等页面修改 | `sys_config`，只有新后台能改；PHP 后台对应写动作全部封堵 | 一个只读的导出脚本（PHP CLI，`include` 配置文件后输出 JSON，不改业务代码）按文件修改时间增量导出，新系统热加载。阶段 3 新 worker 用的分类绑定 `bind.php`、采集参数 `collect.*` 都从导出结果读取，**worker 不写配置** |
| 库内配置：`mac_group`（会员组与权限） | PHP 后台（Group 控制器） | **新后台**；PHP 后台 Group 控制器整体封堵 | 新后台 | 阶段 0～3 直接 SELECT |
| 库内配置：`mac_collect`、`mac_cj_*`（采集源、自定义规则） | PHP 后台 | PHP 后台 | 新后台 | 直接 SELECT |
| 库内配置：`mac_admin.admin_auth`（管理员权限） | PHP 后台 | PHP 后台（阶段 4 新后台只读，用于判定会员管理页的权限，见 4.3 (7)） | 新后台，拆成 `admin_permission` 并回写 | 直接 SELECT |

- 阶段 5 起，新后台每次保存配置时，同时生成一份 PHP 文件格式的导出（与 `mac_arr2file` 的输出等价，包括 `route.php` 和 `playerconfig.js`），只写到一个暂存目录，**不覆盖** PHP 正在使用的文件。回滚时把这份导出替换到 PHP 目录，和回滚前的版本做 diff 确认后再解除封堵。
- 阶段 4 起 `mac_group` 由新后台管理，而 PHP 剩余页面（Ajax、观看密码、播放页等）仍要读会员组。PHP 用文件缓存保存会员组列表（`model('Group')->getCache`，`common/model/Group.php:127`），新系统改了会员组之后，PHP 看到的可能是旧缓存，需要同时清理 PHP 缓存或缩短缓存时间（兼容改动 C2，见 3.7）。
- 管理员权限：阶段 5 拆成 `admin_permission`，同时**回写** `admin_auth` 文本，以便回滚。只有新系统才有的权限码无法回写，回滚后这些权限会丢失，需要在回滚记录里列出。
- `timming.php` 的 runtime 字段由 `api.php/timming/index` 每次执行回写，所以停用 PHP 的定时任务时，要把对应任务设为停用，而不能只停外部 cron。

#### 3.6 写入方切换的回滚流程

适用于阶段 4、5（阶段 3 采用方案甲时，按方案甲中的回滚步骤执行：不需要数据回迁，但要核对在途提交、重复记录和断点）。**这里不承诺具体耗时**，耗时以演练结果为准。

1. **触发门槛**：对账出现无法解释的差异、核心流程（登录、购买、支付回调、发表评论）冒烟失败、错误率明显高于切换前的基线。具体阈值在演练时和用户一起确定。
2. **停新写入**：新系统对应模块切到维护或只读状态，停掉新 worker 中相关任务。
3. **排空在途**：等在途请求完成；支付回调在这段时间返回失败，让支付平台按其重试策略稍后重发（各渠道的重试策略待核实）。旧 `Order::notify` 是"先查状态后更新"，没有用条件更新（`common/model/Order.php:116-138`），并发重复回调可能重复加积分，所以**任何时刻只能有一方处理回调**；新系统要用 `WHERE order_status=0` 的条件更新，只在影响行数为 1 时加积分。
4. **核对**：订单、积分、卡密、提现的总额对账；切换窗口内新系统写入的记录逐条核对；密码指纹一致性（4.3）；配置导出与 PHP 现用文件的 diff。
5. **恢复旧方**：替换配置导出，解除 PHP 侧封堵和数据库权限限制，nginx 路由切回。
6. **验证**：用黄金用例冒烟（登录、退出、购买、测试订单回调、后台改会员），确认 PHP 缓存已刷新。

只读阶段（1、2）的回滚只需要 nginx 切回 PHP，前提是 PHP 实例一直在运行、配置没动过；切回后用黄金 URL 抽样验证。

**缓存一致性**：并行期内 PHP 用文件缓存（`maccms.php:50` `cache_type=file`），新系统无法让它失效。新系统给只读页面设置短 TTL。如果要让 PHP 后台改内容后主动通知新系统，需要给 PHP 加一个写"内容版本号"的钩子（3.7 的 C4），属于业务代码修改，需要另外确认。

#### 3.7 PHP 侧兼容改动清单（全部待批准，均未实施）

| 编号 | 改动 | 需要的阶段 | 是否改业务代码 | 验收条件 |
|---|---|---|---|---|
| G1 | 写入守卫：`app_begin` 行为里按"模块/控制器/动作"对照封堵清单（3.4 第 1 条）。阶段 4 封堵后台 User/Group/Card/Order/Cash/Plog/Ulog/Visit 整个控制器，阶段 5 封堵管理员与内容后台，都只是改清单 | 阶段 0 准备，阶段 3 起使用 | 是 | 清单内每个动作，用 PATHINFO、`?s=`、路由别名、不同后缀、改名后的后台入口访问都被拦截；清单外的动作不受影响 |
| E1 | 仅内网、强鉴权的采集入库端点，接收 `collect_id` 并套用该采集源参数调用 `vod_data`（3.3 方案甲） | 阶段 3 | 是 | 同一批数据经 E1 入库和经旧后台采集入库，`mac_vod` 结果一致；外网访问被拒绝 |
| C1 | `checkLogin` 到期分支的开关：打开时只在内存里降级，不写 `mac_user`（3.3.1） | 阶段 4 | 是 | 3.3.1 的验收用例 1～8 |
| C2 | 会员组缓存：缩短 `Group::getCache` 的缓存时间，或者提供一个清理会员组缓存的内部入口，供新后台改会员组后调用 | 阶段 4 | 是（只改缓存时间时为配置） | 新后台改会员组权限后，PHP 播放页在约定时间内按新权限判定 |
| C3 | 会员 cookie `user_check` 加 HttpOnly（4.3 (4)） | 并行期任意时间，可选 | 是（配置或代码） | 前台 JS 功能不受影响；PHP 与新系统签发的 cookie 属性一致 |
| C4 | PHP 后台改内容后写"内容版本号"，通知新系统刷新缓存 | 阶段 1 起，可选 | 是 | 后台改内容后，新前台在约定时间内显示新内容 |
| X1 | 配置导出脚本（PHP CLI，只读 `include` 配置文件后输出 JSON，3.5） | 阶段 1 起 | 否（新增独立脚本） | 导出结果和 PHP 运行时读到的配置逐项一致 |

另外，第 0 阶段的安全修复（01 第 10 节第 1～4 条）也是 PHP 改动，但它和切换无关，单独立项。

### 4. 核心数据与行为的兼容策略

#### 4.1 视频与分类
- **字段**：`mac_vod`、`mac_type` 原样读取，时间戳和 IP 的格式保持不变。
- **分类树**：`type_pid` 加 `type_mid`。`type_extend` 按 JSON 解析，键名保持不变。
- **分类模板字段**：`type_tpl*` 在新系统里映射为模板名，找不到时回落到默认模板。
- **点击、评分、顶踩**：旧代码中 hits 和 score 是"先读后写"（`Ajax.php:203,379`），并发时会丢计数；新系统接管后改用原子的 `UPDATE ... SET x=x+1`，这是有意的改进。日、周、月点击数的重置规则照 `updateToday` 实现。
- **查询**：列表和筛选条件要按 `Vod::listCacheData` 的参数逐项实现，包括 `type`、`class`、`area`、`lang`、`year`、`level`、`letter`、`order`/`by` 和 `rnd`。用黄金样本对比返回的 ID 序列。

#### 4.2 播放地址
- **存储格式不变**：`$$$` 分隔播放组，`#` 分隔集，`$` 分隔标题、地址和来源。
- **编解码**：新系统实现与 `mac_play_list` / `mac_play_list_one`（`common.php:1346-1455`）等价的函数，并用 PHP 生成的测试向量覆盖：空标题、缺少 `$`、server 前缀、播放器未启用时被过滤、按 sort 排序、结尾多出 `#`、含 CRLF。
- **前端契约**：播放页输出相同结构的 `player_aaaa`（flag、encrypt、trysee、points、link、link_next、link_pre、url、url_next、from、server、note、id、sid、nid）。`app.encrypt` 取 0、1、2 时的行为也要一致，这样 `static/js/player.js` 和 `static/player/*` 才能原样复用。
- **播放器配置**：`vodplayer.php`、`vodserver.php`、`voddowner.php` 在阶段 5 迁到 `sys_config`，新后台生成 `static/js/playerconfig.js`，格式与 `admin/controller/Base.php:85-103`、`System.php:675-690` 一致。
- **会员组权限**：播放和下载的权限检查（`group_popedom` 中的 3 播放、4 下载、5 试看）以及扣积分规则，阶段 1 只读判定、扣积分仍由 PHP 执行，阶段 4 起由 `member` 模块实现（见 3.3）。

#### 4.3 用户：密码、登录态、权限

**(1) 旧系统的密码写入和校验入口，以及各自的字节语义**【静态，需黄金向量确认】

`default_filter` 为空（`application/config.php:46`），所以 `input()` 拿到的是原始参数，各入口自己再做处理：

| 入口 | 位置 | 写入或比较的值 |
|---|---|---|
| 前台注册 | `common/model/User.php:124,180` | 存 `md5(trim(原值))`。但"非空"和"两次输入一致"的校验用的是 `htmlspecialchars(urldecode(trim(原值)))`（`:126-142`） |
| 前台登录 | `User.php:324,346` | 匹配 `md5(trim(原值))`，**或者**库里的值直接等于 `htmlspecialchars(urldecode(trim(原值)))`（即哈希登录漏洞） |
| 前台改资料时改密 | `User.php:297-299,315-316` → `saveData` `:77` | 旧密码接受 `md5(trim(原值))` 或 `md5(htmlspecialchars(urldecode(trim(原值))))`；新密码存 `md5(trim(user_pwd2))` |
| 密保问题找回 | `User.php:483,515` | 存 `md5(trim(原值))` |
| 邮件/短信找回 | `User.php:792,798,840` | 存 `md5(trim(原值))`；长度校验（≥6）用的是格式化之后的值 |
| 后台新增/编辑会员 | `admin/controller/User.php:142` → `User.php:77,87` | 存 `md5(POST 原值)`，**不做 trim** |
| OAuth 首次登录自动建号 | `index/controller/User.php:263-267` | 密码是 `time()`，即注册时刻的时间戳，可以被推算（新发现的风险，见 01 第 10 节） |
| 管理员 | `common/model/Admin.php:65,76`（保存）、`:124`（登录）、`admin/controller/Index.php:185`（锁屏解锁）、安装向导 `install/controller/Index.php:180,259` | 都是 `md5(原值)`，**不做 trim、不做 urldecode/htmlspecialchars**。规则与会员不同，单独见 (7) |
| 数据库工具 | `admin/controller/Database.php:237-265,287-314` | 可以直接改任何字段 |

所以"旧密码 = `md5(明文)`"只是近似说法。同一个用户输入的字节，在不同入口可能得到不同的存储值（例如首尾空白、`%xx`、`&<>"` 等字符）。

**会员登录的候选集合**：旧前台登录只认 `md5(trim(原值))`（`User.php:324,346`，`trim` 是 PHP 默认的去除字符集 `" \t\n\r\0\x0B"`），另一个条件是哈希登录漏洞。所以后台写入的、带首尾空白的密码（`md5(原值)`），在旧前台本来就登录不了。新系统的会员登录**只接受 `md5(trim(原值))` 一个候选**，与旧版一致；上一版提出的"补一个 `md5(原值)` 候选"撤回，因为那会让旧版登录不了的账号变得可以登录。具体字节语义要用黄金测试向量确认，至少覆盖：首尾空格、制表符和换行、`\0`、`+` 与 `%20`、`%xx` 编码、`& < > " '`、中文和 4 字节字符、空字符串。**不兼容**"库里的哈希值本身可以当密码"的行为（01 第 10 节第 1 条），这是有意的差异。

**(2) 密码归属**
- 会员密码：
  - 阶段 0～3：所有写入都在 PHP，新系统只读。
  - 阶段 4 起：前台注册、改资料改密、密保找回、邮件/短信找回、后台新增/编辑会员（新后台）都归新系统；对应的 PHP 入口封堵（3.3）。
  - 新后台给会员设密码时，**拒绝首尾带空白的密码**。旧后台会把它存成 `md5(原值)`，用户在前台却登录不了（见上）；新后台拒绝之后，`md5(原值)` 与 `md5(trim(原值))` 相同，回滚后 PHP 前台也能登录。这是有意的差异。
- 管理员密码：阶段 0～4 归 PHP，阶段 5 起归新后台，详见 (7)。
- 无论哪个阶段，数据库工具都可能改 `user_pwd` 或 `admin_pwd`，下面的失效规则必须能处理这种情况。

**(3) 会员密码的渐进升级与失效规则（待实施；新增字段和逻辑都未实现）**
- 拟新增 `mac_user.user_pwd_v2`（bcrypt 或 argon2id）和 `mac_user.user_pwd_v2_src`（指纹，记录生成 v2 时的 `user_pwd` 值），均可空。v2 的输入是 `trim(原值)`，与旧登录的规范化方式一致。
- 每次校验先读取 `user_pwd`、`user_pwd_v2`、`user_pwd_v2_src`，再看指纹：
  - `user_pwd_v2` 非空，且 `user_pwd_v2_src == user_pwd`：v2 有效，**只用 v2 判定**。v2 不通过就直接拒绝，不再尝试 md5。
  - `user_pwd_v2_src != user_pwd`：说明 PHP 或数据库工具改过密码，v2 已过期。**忽略 v2**，只按当前 `user_pwd` 校验。
  - 不允许"v2 校验失败后降级接受 md5"，也不允许"v2 过期时仍然接受旧密码"。
- **写回必须是条件更新**，条件就是本次读取并据以判定的值。登录成功后的写入（v2、指纹、`user_random` 和登录时间等）合成一条语句：
  `UPDATE mac_user SET user_pwd_v2=?, user_pwd_v2_src=?, user_random=?, ... WHERE user_id=? AND user_pwd=<读到的值> AND user_pwd_v2 <=> <读到的值>`
  单条 UPDATE 在 MyISAM 下也是原子的，不依赖事务。判断结果时要用"匹配行数"，而不是 MySQL 默认的"实际改变行数"（连接时开启 `CLIENT_FOUND_ROWS`），避免把"值没变"误判为冲突。
- **条件更新匹配 0 行时**（读和写之间密码被改过）：重新读取一次。
  - 如果 `user_pwd` 仍等于刚才验证通过的那个值（只是另一个并发登录先写了 v2），本次认证结论仍然成立：按新读到的值重做一次条件更新，并且**不覆盖**对方刚写好的有效 v2。
  - 如果 `user_pwd` 已经变了，**本次登录直接失败**：不签发 cookie、不写 v2，提示用户重新输入。**不能**拿新读到的哈希为这次提交的旧密码签发 v2 或登录态。
  - 只有条件更新成功之后才签发 cookie，因为 cookie 里的 `user_check` 依赖这次写入的 `user_random`。旧版登录的 update 也带着密码条件（`User.php:346,376`），但它不检查影响行数。
- 可选的数据库兜底：给 `mac_user` 加一个 `BEFORE UPDATE` 触发器，当 `user_pwd` 变化而 `user_pwd_v2` 没变时把 v2 置空。触发器属于库结构变更，需要评估权限和 binlog 设置后再决定。
- 并行期内，新系统改密时用一条语句**同时写** `user_pwd`（按旧的 `md5(trim(原值))` 语义）、`user_pwd_v2`、指纹和新的 `user_random`，条件是读到的旧 `user_pwd`。这样回滚到 PHP 后用户仍能用新密码登录，旧 cookie 也失效。
- 回滚后：PHP 不认识 v2 字段，会直接改 `user_pwd`；之后再切回新系统时，指纹不一致，v2 自动作废，用户按当前 md5 登录后重新升级。
- PHP 下线后：所有账号都升级完（或者强制剩余账号走找回流程）才清空 `user_pwd`。

**(4) 登录态 cookie**

旧系统的会员登录态是**无状态 cookie**：`user_id`、`user_name`、`group_id`、`group_name`、`user_check`、`user_portrait`，有效期 30 天（`User.php:384-389`）；`user_check = md5(user_random-用户名-ID-)`，`user_random` 只在登录时更新（`User.php:368`）。cookie 属性来自 `application/config.php:220-235`：`path=/`，`domain` 为空（只对当前主机名生效），`secure=false`，`httponly` 为空。`static/js/home.js:596-602` 会在前端读取 `user_id`、`user_name`、`group_id`、`group_name`、`user_portrait`。

兼容条件：
- 并行期内，新系统签发的 cookie 必须**同名、同 path、同样不设 domain**。如果新系统设了 `Domain=.example.com`，浏览器会同时带上两个同名 cookie，PHP 可能读到错的那个。
- 值的编码要和 PHP 的 `setcookie` 一致（中文用户名会被 URL 编码；PHP 读取时做 `urldecode` + `htmlspecialchars`，`User.php:428-430`）。用黄金向量覆盖含中文、`&` 等字符的用户名。
- **注销**：旧系统的注销只是删除 cookie（`User.php:411-419`），不会让已经被复制走的 `user_check` 失效；改密也不会更新 `user_random`。新系统在注销和改密时**同时更新 `user_random`**，旧 PHP 校验时读的是库里的值，所以这个改动对 PHP 同样生效，并行期就可以做。
- 删除 cookie 时也要用相同的 path 和 domain，否则删不掉。

哪些加固可以在并行期做，哪些要等 PHP 下线：
| 加固项 | 并行期能否做 | 条件 |
|---|---|---|
| `user_check` 加 HttpOnly | 可以 | 前端 JS 不读 `user_check`（`home.js` 只读上面 5 个）；PHP 侧也要同步设置，需改 PHP 配置或代码（3.7 的 C3），待批准 |
| `user_id` 等 5 个 cookie 加 HttpOnly | 不可以 | 会破坏 `home.js` 的前端逻辑，除非同时改前端 |
| 全部加 Secure | 视情况 | 全站已是 HTTPS 才能做，线上情况待核实 |
| 注销/改密时更新 `user_random` | 可以 | 见上 |
| 换成 HMAC 签名或服务端 session | 不可以 | PHP 无法校验，要等 PHP 下线 |

**(5) 第三方登录**：openid 登录只允许在 OAuth 回调里使用，**不兼容**旧代码"直接提交 openid 即可登录"的行为（01 第 10 节第 2 条）。OAuth 自动建号不再使用时间戳密码。

**(6) 会员组**
- `group_type` 和 `group_popedom` 原样解析。
- 会员到期降级规则照搬：`group_id>2` 且 `user_end_time` 已过期时降为 2（`User.php:362-365,455-466`）。阶段 1～3 新系统只在内存里计算，写库仍由 PHP 完成；阶段 4 起新系统是唯一写入方，PHP 改为纯内存判定（C1，见 3.3.1）。

**(7) 管理员（规则与会员分开）**

*旧语义*【静态，需黄金向量确认】：旧列 `mac_admin.admin_pwd` 存 `md5(原值)`。保存（`common/model/Admin.php:65,76`）、登录（`:124`）、锁屏解锁（`admin/controller/Index.php:185`）、安装向导（`install/controller/Index.php:180,259`）都直接对 `input()` 拿到的原始字节做 md5，**不 trim、不 urldecode、不 htmlspecialchars**。编辑管理员时密码留空表示不改（`Admin.php:61-62`），但只含空格的密码不算空，会被存成 `md5(" ")`。登录页的 `maxlength=20`（`admin/view/index/login.html:37`）只是前端限制，服务端不限长度。锁屏解锁比较的是**登录时存进 session 的那份** `admin_pwd`（`Index.php:185`），不是库里的当前值。

*归属*：
- 阶段 0～4：`admin_pwd` 只由 PHP 写。阶段 4 的新后台（会员管理页）需要管理员登录，此时新系统对 `mac_admin` **只读**：按 `md5(原值)` 校验，不升级、不写登录时间，登录记录写到新系统自己的日志表；权限按 `admin_auth` 文本判定。
- 阶段 5 起：管理员的新增、编辑、改密、重置、停用、删除都归新后台。PHP 侧要封堵的入口有：`admin/controller/Admin.php` 的 `info`（`:41-52`）、`del`（`:99`）、`field`（`:122-133`，只能改 `admin_status`，但停用同样要归新后台）；`admin/controller/Index.php` 的 `login`（`:12-16`）和 `unlocked`（`:180-190`）；安装入口 `install.php`（应已有 `install.lock`，仍建议封堵）；Database 工具。

*渐进升级（待实施；新增字段和逻辑都未实现）*：
- 拟新增 `mac_admin.admin_pwd_v2`、`mac_admin.admin_pwd_v2_src`，均可空。v2 的输入是**原始字节**，不做 trim，与旧管理员语义一致。
- 校验规则与 (3) 相同：指纹一致时只用 v2，v2 不通过就拒绝；指纹不一致时忽略 v2，按 `md5(原值)` 校验；不降级、不接受过期密码。
- 写回规则与 (3) 相同：条件更新 `WHERE admin_id=? AND admin_pwd=<读到的值> AND admin_pwd_v2 <=> <读到的值>`；匹配 0 行时重新读取，`admin_pwd` 已变则本次登录失败，不签发会话、不写 v2。
- 新后台改密或重置时，用一条语句同时写 `admin_pwd = md5(原值)`（**不 trim**）、`admin_pwd_v2`、指纹，条件是读到的旧 `admin_pwd`。这样回滚到 PHP 后，管理员用同样的字节仍能登录。
- 回滚到 PHP 后台后：PHP 改密只写 `admin_pwd`，再切回新系统时指纹不一致，v2 自动作废，管理员按 `md5(原值)` 登录后重新升级。

*会话*：
- 旧后台把整条管理员记录存进 PHP session，之后**不再回库校验**（`Admin.php:145-146,166-176`），所以停用或改密不会让已登录的 PHP 会话失效。
- 新后台的会话要在每次请求（或很短的间隔）回库核对 `admin_status` 和密码指纹；密码或状态一变，会话即失效。
- 阶段 5 切换时，封堵 PHP 后台并让全部 PHP 管理员 session 失效（清理 session 存储或更换 session 前缀，方式待演练）。回滚时同样要求所有管理员在 PHP 后台**重新登录**；回滚前新后台的会话一并作废。
- 管理员 session 不在新旧系统之间共享。

*权限*：`admin_auth`（`,控制器/方法,` 格式的文本）在阶段 5 由迁移脚本拆成权限码写入 `admin_permission`，保留"`admin_id=1` 是超级管理员"的语义；新旧权限码之间要有映射表，并回写 `admin_auth`（3.5）。

#### 4.4 采集配置
- `mac_collect` 原样读取，包括 `collect_url`、`type`、`mid`、`param`（base64）、`filter`、`filter_from` 和 `sync_pic_opt`。
- `extra/bind.php` 阶段 0～4 仍由 PHP 后台维护，新 worker 只读导出结果；阶段 5 由迁移脚本导入 `collect_bind`，`cjflag` 的算法保持 `md5(url)` 不变。
- `maccms.php` 中 `collect.*` 的 inrule、uprule、过滤词和随机规则，阶段 5 导入 `sys_config`。合并逻辑必须用"影子合并 + diff"验证（3.3）。
- `extra/timming.php` 中的定时任务迁到 worker 的调度表，触发接口要求鉴权（**不兼容**旧的无鉴权接口）。
- 新增能力：按资源站配置并发和限速、断点续采、TLS 证书校验（可按资源站关闭）、屏蔽内网网段的 SSRF 防护。

#### 4.5 旧 URL 与 SEO
- **前提**：拿到线上的 `maccms.php`（rewrite、view、path）和 `route.php`（待确认）。
- **URL 生成**：新系统实现与 `mac_url` 等价的生成器，包括 ID 模式（数字、拼音、`mac_alphaID`）、后缀隐藏、是否保留 `index.php`、分页规则（第 1 页不带页码）。
- **URL 解析**：同时支持旧的 PATHINFO 形式（例如 `/index.php/vod/detail/id/1.html`）、`?s=` 形式、路由形式（例如 `/voddetail/1.html`）和当前配置对应的形式。非规范形式统一 **301 跳转到规范 URL**，规范 URL 与旧站一致。
- **黄金样本**：在 PHP 环境的库副本上用 CLI 脚本遍历所有内容，调用 `mac_url_*` 导出 URL 清单，新系统必须 100% 生成一致。sitemap 和 rss 的输出格式也逐一对比。
- **监控**：切换后监控 404、5xx 和蜘蛛抓取情况。

#### 4.6 接口
- **Provide**：参数（ac、t、ids、wd、h、pg、pagesize、at、year、isend、from）、JSON 字段、XML 结构保持一致，`closed` 返回值也保留。IP 白名单改为只信任可信代理传来的真实 IP（**与旧版不同**）。如果线上开了 `detail_inc_hits`（待核实），阶段 2 的新 Provide 不能写计数，带 `ac=detail&ids=` 的请求留在 PHP，直到阶段 5。
- **Receive**：`pass` 校验的语义保留；阶段 5 起由新系统处理。
- **支付回调**：路径保持 `index.php/payment/notify`（POST，参数 `pay_type` 区分渠道，`index/controller/Payment.php:13-30`），或者在支付平台改配新的回调地址（待确认）。任何时刻只能由一方处理回调，条件更新规则见 3.6。
- **Timming**：改为需要鉴权（与旧版不同）。

#### 4.7 第三方插件与模板
- PHP 插件（fastadmin-addons）**不能复用**。现有的 `adminloginbg` 未启用，评估后可以不迁。如果线上还有其他插件，需要列出清单，逐个决定重写还是放弃（待确认）。
- 新系统用"模块内扩展点"（事件或钩子接口）替代插件机制，**不建议**在第一阶段做动态插件。
- **模板**有两条路，需要用户选择：
  - 甲：按新的模板引擎改写线上模板。工作量取决于模板页数，但可控。
  - 乙：实现一个 `{maccms:*}` 标签和常用 `mac_*` 函数的兼容层，或者做一个转换工具，以保留使用现成苹果CMS 模板的能力。前期成本高，模板中嵌入的任意 PHP 代码也无法兼容。

### 5. 验证手段（贯穿各阶段）

- **黄金样本**：在**一次性的库副本**上运行 PHP 导出以下内容（因为很多 GET 请求有写入副作用，见 3.1，不能对生产库或共享开发库跑）：
  - URL 清单
  - 列表和详情的 JSON（通过 Provide）
  - 播放串解析结果
  - 播放页的 `player_aaaa`
  - 会员组权限判定表
  - 密码和 cookie 的字节语义向量（4.3）
- **影子流量**：nginx 用 mirror 把请求复制一份发给新系统，记录差异，不返回给用户。约束：
  - 只复制 GET/HEAD，并且只复制已列入只读白名单的页面路由；3.1 节列出的有副作用的入口一律不复制。
  - 接收影子流量的新系统实例用只有 SELECT 权限的库账号，并关闭所有写入代码路径。
  - 方向只能是"真实请求给 PHP，复制品给新系统"，**不能把请求复制给 PHP**，否则 PHP 的计数、扣积分等副作用会执行两次。
  - 复制的请求带着用户 cookie，新系统的日志不能记录 cookie 和会员身份信息。
- **对账**：资金类切换前先跑一次对账基线（MyISAM 下历史数据可能已经不一致），切换后每日核对积分、订单、卡密、提现的总额。

### 6. 回滚策略汇总

| 类型 | 方式 | 前提与边界 |
|---|---|---|
| 只读路由（阶段 1、2） | nginx 切回 PHP，黄金 URL 抽样验证 | PHP 实例一直在运行、配置未改；耗时待演练 |
| 采集抓取（阶段 3 方案甲） | 停 worker，等在途提交完成，解除 PHP 采集入口封堵，恢复定时任务 | 写入方一直是 PHP，不需要数据回迁；但要核对在途提交、重复记录和各采集源断点 |
| 会员到期降级（阶段 4 起） | 关闭 C1 开关，恢复 PHP 库账号对 `mac_user` 的写权限，停新系统的批量到期任务 | 规则两边相同；核对"已到期但 `group_id>2`"的账号数（3.3.1） |
| 写入方（阶段 4、5） | 按 3.6 节六步执行 | 必须停新写入、排空、对账后才恢复旧方；耗时待演练 |
| 配置（阶段 5 起） | 用新后台持续生成的 PHP 格式导出替换 PHP 目录，diff 后再解除封堵 | 只有新系统才有的配置项和权限码无法回写，需记录 |
| 密码 | 会员：PHP 继续用 `user_pwd`（`md5(trim(原值))`）；管理员：PHP 继续用 `admin_pwd`（`md5(原值)`，不 trim）；v2 靠指纹自动作废 | 新系统改密时已按旧语义同步写旧列；管理员回滚后必须在 PHP 重新登录。见 4.3 (3)、(7) |
| 引擎转换 | 从备份恢复 + binlog 补增量，或窗口内停写 | 未演练前不把 `ALTER ENGINE` 当回滚手段；阶段 4 之后不再支持回到 MyISAM |
| 字符集转换：数据出错 | 表保持 utf8mb4，从备份 + binlog 增量恢复出错的数据；演练 PHP 连接字符集兼容 | 这是数据恢复，不是退回 utf8 |
| 字符集转换：退回 utf8 | 仅在核实**没有任何 4 字节字符写入**时执行 `ALTER` 转回 | 写入过 4 字节字符后**不支持无损退回**；不能用恢复转换前旧备份的办法丢弃这些新数据来"回滚"，补增量也无法让 utf8 存下 4 字节字符。需用户事先确认（见第 2 节） |
| PHP 下线后（阶段 6） | 依靠备份和反向迁移脚本 | 反向脚本在阶段 C 设计时编写 |

### 7. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 线上模板和配置缺失，无法确定 URL 和页面行为 | 前台无法验收，SEO 有损失 | 阶段 0 必须拿到脱敏后的线上配置和模板 |
| `mac_url` 和查询逻辑细节多（3000 行的 `common.php`） | URL 或列表不一致 | 黄金样本加影子流量逐项对比 |
| 采集合并规则复杂（uprule a～w） | 数据被覆盖或重复 | 阶段 3 合并仍由 PHP 执行；阶段 5 影子合并加 diff，按资源站分批切换 |
| 旧入口封堵不全，两个系统同时写同一张表 | 数据覆盖、重复入账 | 路由解析后的 PHP 守卫 + 数据库表级权限兜底；切换前按 3.1 清单逐项测试 |
| PHP 剩余页面的隐式写入（登录检查的到期降级、后台列表的 `expire`） | 阶段 4 后两边同时写 `mac_user`；或者收回权限后会员被登出、页面 500 | C1 改为纯内存判定，整个后台 User 控制器封堵；先验收 C1 再收回权限（3.3.1） |
| 并发改密与登录升级交错 | 过期密码被写成新的 v2 或签发登录态 | 指纹 + 以读到的旧哈希为条件的单语句更新；匹配 0 行且哈希已变时拒绝本次登录（4.3 (3)、(7)） |
| 很多 GET 请求有写入副作用 | 影子流量或样本采集意外改数据 | 白名单复制、只读库账号、样本只在一次性副本上采集 |
| 密码字节语义不一致 | 部分用户切换后无法登录 | 黄金向量；保留候选校验集合；找回流程兜底 |
| MyISAM 没有事务，资金类数据可能已经不一致 | 对账出现差异 | 迁移前先跑对账基线；切换到 InnoDB 后用事务 |
| 引擎和字符集转换不可完全回退 | 回滚时丢数据或丢字符 | 独立窗口、演练、binlog 或停写；事先由用户确认不可逆边界 |
| 并行期缓存不一致 | 前台看到旧数据，会员组权限不同步 | 短 TTL；必要时加内容版本号钩子（待批准） |
| 团队技能、流量、预算未知 | 选型或工期失准 | 先回答 02 第 5 节的问题；用一个 2～3 周的 PoC（只读详情页加采集 worker）验证 |
| 市面上现成的苹果CMS 模板从此不能直接使用 | 长期改版成本上升 | 决策时由用户明确接受，或选择兼容层方案（乙） |
| 本仓库中发现的安全问题，线上是否同样存在未核实 | 若存在，重构期间账号可被接管 | 先核实线上；确认存在则阶段 0 在 PHP 修复（需用户确认） |

### 8. 待用户确认的决策点

1. 语言：Go 还是 Java（见 02）。是否考虑"继续用 PHP 并加固"的路线？
2. 模板走甲（改写）还是乙（兼容层）？
3. 功能范围：哪些模块要砍掉（例如 Cj 规则采集、站群、静态生成、卡密、提现）。
4. 是否同意阶段 0 先核实并在 PHP 修复 01 第 10 节第 1～4 条安全问题。
5. 是否同意 3.7 节的 PHP 侧兼容改动：写入守卫 G1、内网入库端点 E1、登录检查到期分支开关 C1、会员组缓存 C2，以及可选的 C3、C4（除导出脚本 X1 外都是 PHP 业务代码改动）。
6. 阶段 3 采用方案甲还是方案乙。
7. MyISAM 转 InnoDB、utf8mb4 转换的时间窗口，以及是否接受其中的不可逆边界。
8. 能否提供脱敏后的线上配置（rewrite/view/path、route.php、bind.php、timming.php、播放器配置、`api.vod.detail_inc_hits`）和模板；线上是否开启 binlog、是否全站 HTTPS。
