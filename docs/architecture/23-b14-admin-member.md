## B14 后台会员与财务管理：会员、会员组、充值卡、订单、提现、积分日志、访问日志、推广访问（路线图 B14，MACC-38）

> **状态：按队长 2026-10-26 验收意见返工完成（第二轮），待独立验收。** 依据：
> - 路线图 `09-roadmap.md` 第 3.2 节 B14 行（第 157 行）、第 5.3 节 B14 验收行（第 396 行：会员列表第 1 页的到期降级与 B10 共用 `<=` 语句、第 2 页不触发；资金类对账；后台改会员密码按 `md5(原值)`、不 trim）、写入方矩阵（第 417-419 行）、第 4.1 节 E05（第 190 行"后台手工处理订单在 B14"，见第 1 节）；
> - B07 `12-write-infrastructure.md`（写 SQL 规范、`WriteSequence`、并发与回滚演练）；B10 `19-b10-member-finance.md`（会员中心的提现、撤销与到期降级语句）；B12 `21-b12-admin-framework.md`（后台入口、入参、会话、`admin_auth`、页面与脚本策略，B14 的 20 个权限码已在 B12 落地）；B13 `22-b13-admin-content.md`（后台表单的取值规则、整行覆盖、页面转写方式）；
> - 用户决定（2026-10-17）：后台复用旧后台 HTML 结构与静态资产，转成 jte，不做新视觉设计。
> - 队长验收意见（2026-10-26，MACC-38 issue 说明"队长独立验收与返工要求"）：补齐后台会员头像上传；订单手工改状态按旧版不新增；积分日志、推广访问的失效复选框按旧行为保留；补同一提现的审核与删除/撤销交错测试；D-B14-4～-9 按已说明边界接受。
>
> 旧代码位置一律相对仓库根目录；新代码写作 `backend/…`。

### 0. 起点与分支

| 项 | 值 |
|---|---|
| 起点 | 平台基线 `712fc98`（树与 main `bd584f3` 相同：B13 合并 `8bfce79` 之后的路线图更新） |
| 分支 | 第一轮 `agent/claude/macc-38`（`79fa074`、`038a39f`）；第二轮返工 `agent/claude/macc-38-r2`，从候选 `038a39f` 起追加普通提交（不含 main 上的误合并 `c2ac5da` 与其撤销 `d7bddf8`）。只用普通提交，不 reset、rebase、amend、force，不自行合并 main |
| 回滚点 | main `bd584f3`（main 现为 `d7bddf8`，树与 `bd584f3` 相同） |
| 提交 | 见交付评论（最终 SHA 以评论为准） |

### 1. 范围

本批在 B12 的分派里接上 23 个动作：auth.php 中归 B14 的 20 个权限码，加 3 个旧控制器里有、auth.php 没列的公开动作（`user/reward`、`visit/index`、`visit/del`；与 B12 登记的 `EXTRA_ACTIONS` 相同）。分派表见 `backend/src/main/java/com/maccms/backend/admin/web/AdminMemberActions.java` 的 `handle`。

| 控制器（旧 `application/admin/controller/*.php`） | 动作 |
|---|---|
| `User` | `data`（第 1 页先到期降级） `info`（GET 表单 / POST 保存） `del` `field`（状态） `reward`（推广会员）；表单的"上传图片"（`upload/upload?flag=user`，第 7.7 节） |
| `Group` | `index` `info` `del` `field` |
| `Card` | `index`（含 `export=1` 导出 CSV） `info`（GET 表单 / POST 批量生成） `del`（含清空 `all=1`） |
| `Order` | `index` `del` |
| `Cash` | `index` `del` `audit` |
| `Plog` `Ulog` `Visit` | `index` `del` |

| 不在本批或旧版没有（归属） | 说明 |
|---|---|
| **订单的手工改状态** | **旧后台没有这个动作**：`admin/controller/Order.php` 只有 `index`、`del`，auth.php 只有 `order/index`、`order/del`，订单状态只由支付回调 `Order::notify`（B24）改。队长按用户"功能一样、不增不减"裁定不新增，也不挪到 B24（2026-10-26）；路线图 B14 行与 E05 的误记已更正（09 文档）。不新增权限码与界面 |
| 在线支付、到账加积分 | B24（E04/E05/W3） |
| 会员中心的提现申请、撤销、卡密充值、升级 | B10（本批与之并发与对账，第 9 节） |

### 2. 模块与依赖

| 包 | 内容 |
|---|---|
| `member.application` | `UserAdminService`（列表与第 1 页到期降级、表单、保存、删除、状态、推广会员）、`AdminPortraitService`（会员表单的头像上传，第 7.7 节；复用 B09 的 `PortraitImages`、`PortraitStore` 与 D-B09-9 的附件登记）、`GroupAdminService`（列表、表单、保存、删除、状态；`group_popedom` 的 PHP `json_encode` 与勾选判断）、`CardAdminService`（列表与导出、批量生成、删除；旧 `mac_get_rndstr`）、`FinanceAdminService`（订单列表与删除；提现列表、审核、删除）、`MemberLogAdminService`（积分日志、访问日志、推广访问的列表与删除）；公共 `MemberAdminSupport`（旧参数语义）、`LegacyTime`（`strtotime` 的两个子集）；装配 `MemberAdminConfiguration`（`@ConditionalOnWriteEnabled`）、聚合 `MemberAdminServices`；`view/MemberRewardStats` |
| `member.infrastructure` | 只读 `MemberAdminMapper`（`mapper/member/MemberAdminMapper.xml`）、筛选条件 `MemberAdminCriteria`；写 `MemberAdminWriteMapper`（`mapper-write/member/MemberAdminWriteMapper.xml`）、`MemberAdminWrites`（数据错误 → `MemberAdminDataException`；另经 B10 的 `MemberFinanceWriteMapper` 执行到期降级、撤销提现的认领与补偿、退回冻结积分）、`MemberAdminInsert`、`CardInsert` |
| `content.application` | `AdminTable` 加 `USER`、`GROUP` 两张表的列目录（会员与会员组的整行覆盖用 B13 同一套取值规则 `AdminRowValues`） |
| `admin.web` | `AdminMemberActions`（全部 B14 动作）；`AdminController` 在 B13 的动作之后交给它，返回 null 时仍按 B12 的"由后续批次实现"/404；`upload/upload` 带 `flag=user`、不带编辑器 `from` 的请求在 B13 的上传之前交给它的 `portraitUpload`（登录、同源与 `upload/upload` 权限检查之后）；`AdminParams.nested`（多维数组参数，会员组权限 `group_popedom[分类][位]`）；`AdminResponses.download`（CSV 导出）；`view/member/`：`MemberListView` `UserInfoView` `GroupInfoView` `CardInfoView` `RewardView` |

- **注册条件**：`MemberAdminConfiguration` 与 `MemberAdminWrites` 只在可写数据源打开时注册；与站点是否启用无关。后台本身要求可写数据源（21 文档第 7 节），所以生产上总是齐全；单元测试的替身上下文里没有时，B14 的动作按"由 B14 批次提供"处理（`AdminMvcTest` 不变）。
- **到期降级**：会员列表第 1 页调用 B10 的 `MemberExpiryService.expireAll()`。站点启用时取容器里的那一个（它还负责部署参数打开时的定时降级），否则用同一条降级语句另建一个不定时执行的实例。
- **边界**（`ModuleBoundaryTest`）：admin.web 只用 `member.application`（含 `.view`），不直接用 Mapper；member 只用 `content.application`（`AdminTable`、`AdminRowValues`、`PhpForm`、`AdminIds`、`AdminPaging`、`ContentLookupService` 等）与 `.view`，同 B13 的 comment 模块。

### 3. 表与列

| 表 | 本批写入（登记批次） | 用途 |
|---|---|---|
| `mac_user` | DELETE（B14）；INSERT、UPDATE 沿用 B09 | 后台新增、整行覆盖、状态、删除；第 1 页到期降级；提现审核扣冻结、删除退冻结 |
| `mac_group` | INSERT、UPDATE、DELETE（B14） | 会员组增删改、状态 |
| `mac_card` | INSERT、DELETE（B14）；UPDATE 沿用 B10 | 批量生成、删除与清空 |
| `mac_order` | DELETE（B14）；INSERT 沿用 B10 | 删除与清空 |
| `mac_cash` | UPDATE（B14）；DELETE、INSERT 沿用 B10 | 审核（认领与补偿）；删除（认领）与补偿插回 |
| `mac_plog` `mac_ulog` | DELETE 沿用 B10 | 删除与清空 |
| `mac_visit` | DELETE（B14）；INSERT 沿用 B10 | 删除与清空 |
| `mac_annex` | INSERT 沿用 B13（经 content 的 `AnnexAdminService.register`，与 D-B09-9 前台头像同一登记） | 会员头像上传后登记附件（第 7.7 节） |
| 文件 `upload/user/<id%10>/<id>.jpg` | 不是数据库写入 | 会员头像（第 7.7 节；写入方式同 B09 前台头像：同目录临时文件 + 原子改名） |

- **登记** `WriteRegistry`（`backend/src/main/java/com/maccms/backend/common/write/WriteRegistry.java`）：新增 9 项，登记表共 60 项（`WriteGuardTest` 逐项、`WriteRegistryTest` 允许与不允许项、`WriteInfrastructureMySqlIT` 生产登记表；全部符合写入方矩阵）。
- **列目录** `AdminTable.USER`、`AdminTable.GROUP` 逐列照抄 `install.sql`；`AdminTableSchemaTest` 逐列比对，并检查 `MemberAdminWriteMapper.xml` 的 `insertUser/updateUser/updateUserByIds`、`insertGroup/updateGroup/updateGroupByIds` 列出了全部列。
- **零结构变更**：没有 DDL、没有新表新列。

### 4. 入参与旧语义

#### 4.1 参数

- 与 B13 相同：web 层按 B12 的规则（`AdminParams`：按字节解析、`filterExp`、数组）解析后转成 `PhpForm` 交给会员服务；每个动作取哪一路参数与旧控制器相同（`info` 的保存只取 POST，其余用合并后的 `input()`）。
- **多维数组**：会员组表单的权限复选框是 `group_popedom[分类][位]`。B12/B13 的 `AdminParams` 只保留第一层（B13 用到的都是一维）；本批让它同时记下第一个 `[键]` 之后的原样名称，`nested(名)` 按 PHP `parse_str` 的规则还原多维数组（`[]` 取下一个整数下标；第一层之后的各段必须紧接 `[`，否则其余忽略；已有的标量再按数组用时换成数组）。`AdminParamsNestedTest` 与 PHP 8.0 `parse_str` 的结果逐项相同；一维数组与标量的结果不变。
- **PHP 判断**原样实现：`empty()`、`in_array` 严格（状态 `'0'/'1'`）与宽松（`val`、`level`、`time == '1'`、`export == '1'`、`all == 1`）、`$param['status'] != ''`（订单、提现的状态：缺失与空串不筛选，`'0'` 筛选）、`intval`。
- 删除、修改的 `ids`：每项必须是十进制数字，最多 1000 项，否则"参数错误"（D-B13-4 的同一规则）。旧版在 `!empty($ids)` 之后才看 `all`，所以清空按钮带的是 `ids=1&all=1`，没有 `ids` 时"参数错误"（照旧，黄金 `card-del/all-no-ids`）。

#### 4.2 取值规则（`AdminRowValues`）

- 会员保存（旧 `User::saveData`）没有 `allowField`，但数据库配置 `fields_strict=false`，ThinkPHP 同样只写表里有的列：**整个 POST 里凡是 `mac_user` 的列都写**（含 `user_points_froze`、`user_pid`、`user_login_num` 等，黄金 `user-save/edit-extra-columns`），与旧版一致。会员组用 `allowField(true)`，同理。
- 旧版在 MySQL 严格模式下报错（整页 500）的取值（超长、负数写进无符号列、非数字、超出范围）返回"参数错误"、不写库（D-B13-3 的同一规则；黄金 `user-save/name-too-long`、`points-bad`、`points-negative`，`group-save/edit-points-overflow`，`card-info/overflow`）。数值列的小数由 MySQL 四舍五入（`group_points_day=1.5` → 2，同旧版）。

#### 4.3 `strtotime`（`LegacyTime`）

| 用途 | 旧代码 | 新版 |
|---|---|---|
| 会员到期时间 | `isset($v) && !is_numeric($v)` 时 `strtotime($v)`（`common/model/User.php:62-67`），失败的 `false` 写库为 0 | 按 PHP 8.0/7.3 实测实现日期控件写出的 `Y-m-d H:i:s` 一类：日期 `年(4 位)-月-日`（月 0～12、日 0～31，一到两位，0 与超出当月的日按 PHP 顺延），可接 `时:分[:秒]`（时 0～24、分 0～59、秒 0～60；与日期之间为空格、制表符或 `T`），首尾可有空白；同一形状但字段越界时为 0（同 `false`）；空串为 0；只有空白时为当前时间（PHP 如此）；其他写法（`now`、`+1 day`、`2030/01/02` 等）不实现，"参数错误"（D-B14-5）。`MemberAdminUnitTest` 逐项对照实测值 |
| 卡密、推广访问的时间筛选 | `strtotime(date('Y-m-d', strtotime('-'.$time.' day')))` | `time` 为 1～5 位数字时为 N 天前的零点（PRC）；空串、`abc` 等 `strtotime` 失败时为 1970-01-01 零点（-28800，列出全部）。下拉框的取值（空、0、1、7、30）与黄金相同；其他写法（如 `- 7`、`--3`）旧版有时能解析，新版按失败处理（只能经手工改地址触发） |

#### 4.4 `user.reg_group`

旧会员组控制器用 `$GLOBALS['config']['user']['reg_group']`（注册默认会员组）：保存时宽松等于提交的 `group_id` 就强制启用，删除时 `strpos(','.$ids.',', ','.$reg_group.',')` 命中就拒绝。仓库与旧后台的系统配置页（`admin/view/system/configuser.html`）都**没有**这一项（值为 null），所以：

- 新增会员组（`group_id` 为空或缺失）总是被强制启用，表单里选了"禁用"也一样（黄金 `group-save/new-status-0`、`new-no-id`）；
- `ids` 里有空段（如 `5,`）时"注册默认会员组无法删除!"（黄金 `group-del/reg-group-blank`）。

新版照旧，并在配置里有这一项时同样按它判断（`GroupAdminService.regGroupEquals`，每次调用读旧配置，同 B13 的 `AdminConfig`）。

### 5. 只读查询

- `MemberAdminMapper.xml` 走只读连接池；列表行全部列按文字取回（同 B13）。会员行**不读** `user_pwd`、`user_random`、`user_openid_*`（表单的密码框总为空，旧 `infoData` 同样把密码置空；`ReadOnlyGuardrailsTest.memberAdminMapperNeverSelectsPasswordsOrLoginSecrets` 锁定）。卡密列表与导出要显示卡密（旧版如此），照旧读 `card_pwd`。
- 每个列表的 count 与分页 `<include>` 同一个条件片段，排序为旧版的主键倒序（会员组主键正序、最多 1000 行，旧版不分页）。`LIKE` 关键字按 B12/B13 的 `PhpText.legacyLike` 转写（`%`、`_` 照旧是通配符）；推广访问的来源是前缀匹配（第 7.6 节）。
- 会员名：订单列表照旧 `LEFT JOIN mac_user`；卡密列表照旧取使用者；提现、积分日志、访问日志旧版取"最新 999 个会员"里的名字（`common/model/Cash.php:28-35` 等写错了条件变量，较早的会员显示为空），新版按本页各行的会员编号关联（D-B14-6，同 B10 的 D-B10-5）。推广访问旧版不取会员名，照旧。
- 访问日志的所属内容名称经 `content.application.ContentLookupService.titles`（视频、文章、专题、演员；不看审核状态，同旧 `infoData`），一页最多 1000 行，按每批 100 个编号查（该服务一次的上限；`AdminMemberGoldenMySqlIT` ⑨：250 条日志、150 个不同视频）。

### 6. 写入

#### 6.1 语句

全部 SQL 在 `mapper-write/member/MemberAdminWriteMapper.xml`（另有 B10 `MemberFinanceWriteMapper.xml` 的 4 条），经 `WriteDatabase.mapper()`，单表、单语句、带顶层 `WHERE`、检查匹配行数：

| 语句 | 要点 | 旧版 |
|---|---|---|
| `insertUser` / `updateUser` / `updateUserByIds` / `deleteUsers` | 整行覆盖只写给出的列（`<if test="c.containsKey(…)">`）；状态只改 `user_status` | `User::saveData/fieldData/delData` |
| `expireMembers`（B10） | `SET group_id = 2 WHERE group_id > 2 AND user_end_time <= ?` | `User::expire`（`common/model/User.php:395-409`） |
| `insertGroup` / `updateGroup` / `updateGroupByIds` | 同上 | `Group::saveData/fieldData` |
| `deleteEmptyGroups` | `WHERE group_id IN (…) AND NOT EXISTS (SELECT 1 FROM mac_user AS u WHERE u.group_id = mac_group.group_id)` | 先数该组下的会员，没有才删（检查与删除之间可被分配会员，D-B14-8） |
| `insertCards` | 一批最多 500 张一条多行 `INSERT` | 一条 `insertAll` |
| `deleteCards` / `deleteAllCards` 等 | `IN (…)`；清空为 `WHERE 主键 > 0` | 同 |
| `auditCash` / `revertAudit` / `settleFrozen` | 认领 `SET cash_status = 1, cash_time_audit = ? WHERE cash_id = ? AND cash_status = 0`；扣冻结 `WHERE user_points_froze >= ?`；补偿按本次写入的状态与时间恢复 | 逐行"改状态 → `setDec`"，不看状态、不看余额（D-B14-1、-2） |
| `deleteCash` / `restoreCash` / `unfreezePoints`（B10） | 删除按读到的状态认领；退冻结 `WHERE user_points_froze >= ?`；退不回时原样插回 | 先删、再按读到的旧值整体覆盖积分（D-B14-1、-3） |

#### 6.2 多语句的操作与部分失败（MyISAM 没有事务）

| 操作 | 语句顺序 | 中途失败时 |
|---|---|---|
| 提现审核（每一行） | `WriteSequence`：认领（`auditCash`）→ 扣冻结（`settleFrozen`）；会员已不存在时只有认领 | 扣冻结不命中（冻结积分不够）：撤销这一行的认领，返回"更新用户积分失败："，前面已审核的行保留（同旧版逐行处理）；补偿也失败时 ERROR 日志、上报人工对账 |
| 提现删除（每一行） | 已审核：按状态删除；未审核：认领（`deleteCash`）→ 退冻结（`unfreezePoints`）；会员已不存在时只删除 | 退冻结不命中：把这一行原样插回（带原 `cash_id`），返回"更新用户积分失败："；认领不命中（已被并发删除或期间被审核）：已审核的按新状态只删除，否则跳过 |
| 提现清空（`all=1`） | 按 `cash_id` 每批 500 行逐行同上 | 某行失败即返回，前面的行已生效；重试安全（按当前数据重算） |
| 生成卡密 | 每 500 张一条 `INSERT` | 前面的批已写入（旧版是一条语句；MyISAM 多行语句出错同样会留下前面的行） |
| 会员组删除 | 数会员 → 条件删除 → 若目标组仍在且有会员，返回"删除失败：用户组下还有用户" | 没有会员的组已删除 |
| 会员列表第 1 页 | 到期降级 → 列表 | 降级是独立的一条语句，与列表无依赖 |
| 会员头像上传 | 检查会员与图片 → 写文件（临时文件 + 原子改名）→ 登记附件 | 检查或转换失败不写盘；写盘失败不留临时文件、原有头像不变；登记失败只记日志（同 D-B09-9）。不写 `mac_user`（第 7.7 节） |

**同一条提现的审核与删除/撤销交错**（`AdminMemberGoldenMySqlIT` Order 10～14，用包装 Mapper 的屏障把每个窗口固定下来，逐步断言库里的状态，不靠随机多跑）：

| 交错 | 结果 |
|---|---|
| 审核已认领（`cash_status=1`、写了审核时间）、还没扣冻结时，同一行被后台删除或会员撤销 | 删除/撤销读到"已审核"（或认领不命中后重读到"已审核"），只删记录、不退冻结；放行后审核扣冻结命中一次。冻结只扣一次、没有退回，对账为空（与"先审核、后删除已审核记录"的顺序执行结果相同） |
| 删除已认领（行已删）、还没退冻结时，审核去认领 | 审核认领不命中，跳过（"审核成功"）；放行后退冻结命中一次 |
| 扣冻结不命中（会员冻结积分已不够，数据本已不一致，如后台表单改过冻结积分）、撤回审核之前同一行被删除 | 撤回审核不命中：审核返回"更新用户积分失败："，并记 ERROR 日志 `admin cash audit needs reconciliation: cash_id=… user_id=… points=…`（`WriteSequence` 另记一条序列名与步骤）。没有重复退或扣：可用、冻结都不变；第 6.3 节的对账语句列出这位会员，按日志里的提现号、会员号、积分人工核对 |
| 扣冻结不命中、撤回审核命中；之后删除同一行、退冻结同样不命中 | 提现回到未审核（审核时间复原）；删除把这一行原样插回（插回之前再审核读不到这一行，什么都不做）。可用、冻结、提现行与开始时相同 |

结论：冻结积分与未审核提现一致时，审核认领之后没有别的操作会动这一行的冻结积分（删除与撤销遇到已审核的行只删记录），扣冻结必然命中，普通并发不会造成记录或积分的丢失。只有数据本已不一致、扣冻结不命中、又恰好在撤回之前被删除时需要人工对账，此时有错误提示、ERROR 日志与对账语句三处可查。删除未审核提现在退冻结、插回都失败时同样记 ERROR 日志（提现号、会员号、积分、提现时间，不记银行信息）。

#### 6.3 资金类对账

提现与冻结积分的关系是：会员的 `user_points_froze` 等于他未审核提现的 `cash_points` 合计；审核把冻结的积分付出去（冻结减少），撤销与删除把冻结退回可用积分。对账语句（只读，运维与测试共用）：

```sql
SELECT u.user_id, u.user_points_froze, COALESCE(SUM(c.cash_points), 0) AS pending
FROM mac_user AS u LEFT JOIN mac_cash AS c ON c.user_id = u.user_id AND c.cash_status = 0
GROUP BY u.user_id, u.user_points_froze
HAVING u.user_points_froze <> COALESCE(SUM(c.cash_points), 0);
```

结果为空即一致。另一条不变量：在没有删除已审核提现、没有后台手工改积分的前提下，"可用 + 冻结 + 已审核提现合计"不变（第 9 节 ②）。旧版用整体覆盖与不看状态的写法，并发或数据不一致时会让两条都不成立（D-B14-1～-3）；新版的条件写入保证并发时两条成立。**后台保存会员表单是整行覆盖**（积分、冻结积分都按表单写），会让这两条按管理员填的值重新开始——这是旧版行为（路线图 B13/B14 的同一结论），第 12 节。

### 7. 各模块行为

#### 7.1 会员（`admin/controller/User.php` → `common/model/User.php`；`UserAdminService`）

- **列表** `data`：`$param['page'] == 1`（缺省、`intval` 小于 1 都按 1；`1.0` 也算；PHP 8 的 `'1abc' == 1` 为假）时先到期降级——`group_id > 2 AND user_end_time <= now`，**等于现在也降级**（与 B10、B08 演练语句相同）；第 2 页不触发（黄金 `user-list/page2`、`page2-loose`，`AdminMemberGoldenMySqlIT` ⑦ 的边界）。筛选：状态严格 `'0'/'1'`、会员组 `!empty`、会员名关键字 `htmlspecialchars(urldecode())`；编号倒序；会员组名称取会员组表。
- **表单** `info`（GET）：按 `intval(id)` 读一行，没有时是空表单；密码框总为空；会员组下拉框列出全部组；编辑已有会员时才有头像一栏；组号 ≤ 2（新增时也是）时到期时间一栏隐藏，选了 > 2 的组由脚本显示；到期时间为 `date('Y-m-d H:i:s', 到期)`（新增会员见 D-B14-7）。
- **保存** `info`（POST）：到期时间换算（第 4.3 节）→ 会员名 `require`、`min:6`（`mb_strlen`，"参数错误：名称必须" / "参数错误：名称最少不能低于6个字符"；**新增也只用 edit 场景，不要求密码**，照旧）→ 有 `user_id` 时密码 `empty()` 不改、否则 `md5(原值)`，按主键整行覆盖；没有时新增，密码 `md5(原值)`（缺失为 `md5('')`，黄金 `user-save/new-no-pwd`）。**密码按字节 `md5`，不 trim、不 urldecode、不 htmlspecialchars**，只经 ThinkPHP `filterExp`（`like` → `md5("like ")`，黄金 `edit-pwd`、`edit-pwd-like`）。
- **删除** `del`、**状态** `field`（`col` 只能是 `user_status`，`val` 宽松等于 0 或 1）：同 B12/B13 的规则。
- **推广会员** `reward`：`uid = intval(uid)`；`level` 非空时宽松等于 1/2/3 只看对应的一列，其他值（如 4）不加推广条件、列出全部会员（照旧，黄金 `level-bad`）；为空时三列任一；另有三级人数与 `plog_type` 4/5/6 的奖励合计。

#### 7.2 会员组（`Group.php` → `common/model/Group.php`；`GroupAdminService`）

- 列表：状态严格 `'0'/'1'`、名称关键字（页面上没有搜索框，只能经地址），编号升序；编号 ≤ 2 的组不显示复选框、状态开关与删除（照旧；但 `field`、`del` 本身不限编号，照旧，黄金 `group-field/guest-off`、`group-del/guest`）。
- 表单：分类树为全部分类（含停用，同 B13 的 `cacheTree`）；分类复选框按 `strpos(','.group_type, ','.分类.',') > 0`；权限复选框按 `json_decode(group_popedom, true)[分类][位]` 非空（JSON 是字符串或损坏时都不勾）。
- 保存：`reg_group` 强制启用（第 4.4 节）→ `group_type` 非空时必须是数组，`','.join(',', 各项).','`，否则 `''`（不是数组时"参数错误"：PHP 8 报错 500、7.3 写成 `,,`，D-B13-3 的同一规则）→ `group_popedom` 非空时 PHP `json_encode`（键恰好是 0、1… 的数组输出为 JSON 数组，`/` 与非 ASCII 转义，与 PHP 逐字节相同；标量也照样编码，如 `"abc"`），否则 `''` → 名称 `require`（`'0'` 也算有）→ 有编号整行覆盖，否则新增。
- 删除：第 4.4 节的 `reg_group` 检查 → 组下有会员时"删除失败：用户组下还有用户"（只要有一个组有会员就一个都不删）→ 条件删除（第 6.1 节）。

#### 7.3 充值卡（`Card.php` → `common/model/Card.php`；`CardAdminService`）

- 列表：售出、使用状态严格 `'0'/'1'`；卡号关键字；`time` 存在时（`isset`，空串也算）宽松等于 1 为"最近一批"（`card_add_time >=` 最大添加时间，没有卡时为 0），否则第 4.3 节的 N 天前零点；编号倒序；使用者名称。
- **导出** `export == 1`（宽松）：第 1 页 9999 条（`page`、`limit` 不起作用，照旧），`text/csv;charset=UTF-8`、`attachment;filename=card_<Y-m-d>.csv`；第一行语言包 `admin/card/import_tip`（"卡号,密码,创建时间"），每张卡 `="卡号",="卡密",添加时间`（不转义，与旧版逐字节相同，黄金 `card-list/export*`）。
- **生成** `info`（POST）：`num`、`money`、`point` 任一 `empty()` → "参数错误"；按 `intval`；数量小于 1 → "保存失败!："（旧版 `insertAll([])` 返回 false）；大于 10000 → "参数错误"（D-B14-4）。卡号 16 位、卡密 8 位，字符集 `num` 数字、`letter` 大写字母、其他为"数字与大写字母"（旧 `mac_get_rndstr` 的混合字符集里**没有 1**，照旧），以 0 开头时换成 1～9（`application/common.php:318-337`）；同一批卡号相同的只留一条（旧版以卡号作数组键）。面值、积分超出 `smallint unsigned` 时"参数错误"（旧版 500）。

#### 7.4 订单（`Order.php`；`FinanceAdminService`）

- 列表：`status != ''` 时按原值（`1.0` 由 MySQL 按数值比较，命中已支付）、会员、单号关键字；订单号倒序，`LEFT JOIN` 会员名。旧模板的"支付类型""支付时间"两列取的是不存在的 `order_type`、`order_paytime`，永远为空——照旧（第 8.2 节）。
- 删除与清空：同卡密。**没有改状态的动作**（第 1 节）。

#### 7.5 提现（`Cash.php` → `common/model/Cash.php`；`FinanceAdminService`）

- 列表：`status != ''`、会员、银行账号关键字；编号倒序；会员名（D-B14-6）。旧模板的"备注"列取不存在的 `cash_remarks`，永远为空——照旧。
- **审核** `audit`：`ids` 为空 → "参数错误"；逐行：未审核的标为已审核并写审核时间，再扣减会员的冻结积分；已审核的跳过（D-B14-2）；会员已不存在时只审核（旧版的 `setDec` 匹配 0 行、照常成功，黄金 `cash-audit/no-user`）；冻结积分不够时撤销这一行并返回"更新用户积分失败："（D-B14-1）。全部完成："审核成功"（旧版写死的文字）。
- **删除** `del`：`ids` 为空 → "参数错误"；`all == 1` 时全部；逐行：已审核的只删除；未审核的删除后退回冻结积分（`user_points += cash_points`、`user_points_froze -= cash_points`）；会员已不存在时只删除；冻结积分不够时插回这一行并返回"更新用户积分失败："（D-B14-1）。与会员中心撤销提现（B10）是同样的语句与补偿。

#### 7.6 积分日志、访问日志、推广访问（`Plog.php`、`Ulog.php`、`Visit.php`；`MemberLogAdminService`）

- 积分日志：类型、会员（`!empty`，原值）；收入类（类型 1～6）积分前显示 `+`，其余 `-`。访问日志：模块、类型、会员；所属内容名称（第 5 节）；"关联"列为 `编号[-sid][-nid]`。页面上两者都有关键字框，旧控制器不读，照旧。
- 推广访问：会员；`time` 存在时为 N 天前零点起；关键字按来源前缀匹配：旧代码本意是"已带协议时另试另一种协议"，但比较写成了 `substr($a,5)`（第 5 个字节之后的部分），实际几乎总是 `LIKE 'http://词%' OR LIKE 'https://词%'`，词不转义（`_`、`%` 是通配符）——照旧（黄金 `visit-list/wd*`）。"模块"列：有会员为"用户"、否则"网站"；模块下拉框旧控制器不读，照旧。
- 删除与清空：同卡密。
- **列表复选框（旧缺陷，照旧保留）**：积分日志、推广访问列表旧模板的复选框取不存在的键（`value="{$vo.ulog_id}"`），值为空。勾选后点"删除"，旧版提交空编号，"删除成功"但什么都没删；新版对空编号返回"参数错误"（D-B13-4 的同一规则），同样什么都不删（黄金 `plog-del/checkbox-blank`、`visit-del/checkbox-blank`）。按队长 2026-10-26 裁定保留旧结构，不修模板；行内"删除"与"清空"正常。

#### 7.7 会员表单的头像上传（`admin/view/user/info.html:42-46、159-182` → `Upload::upload` 的 `flag=user` 分支；`AdminPortraitService`）

**旧行为（PHP 8.0 与 7.3 实跑黄金，`admin_user_portrait_golden.php`）**：编辑已有会员时表单有"上传图片"按钮，layui 把选中的文件 POST 到 `upload/upload?flag=user&user_id=<会员号>`（另带 `thumb=0`、`thumb_class=''`）。权限是 `upload/upload`（没有这一项的管理员得到"您没有权限访问此页面"）。`Upload.php` 把文件写到 `upload/user/<id%10>/<id>.jpg`，用 think-image 按 `user.portrait_size` 固定尺寸缩放后覆盖同一文件（按原格式保存：PNG 上传后 `.jpg` 里是 PNG 字节），登记 `mac_annex`（类型同前台头像；`annex_size` 因 PHP 的 stat 缓存取的是原文件大小），返回 `{"msg":"文件上传成功！","code":1,"data":{"file":"upload/user/…","type":…,"size":原文件 KB,"flag":"user","ctime":…,"thumb_class":"","thumb":[]}}`。第 208 行按 `$GLOBALS['user']['user_id']` 更新 `user_portrait`，**后台没有会员会话，这个全局变量不存在，更新不命中任何行**：上传本身不改 `mac_user`。表单脚本把返回的地址填进头像一栏，管理员点"保存"时随整行覆盖写进 `user_portrait`（第 7.1 节）。前台的 `user.portrait_status` 不影响后台。

**新版**：

- 分派：`upload/upload` 带 `flag=user`、不带编辑器 `from` 的请求由 `AdminMemberActions.portraitUpload` 交给 `AdminPortraitService`；登录、同源检查（D-B12-3）与 `upload/upload` 权限检查照旧在前（`AdminController`）。其他上传仍走 B13 的 `AdminUploadService`，它的护栏（`flag` 字符集、扩展名、脚本标记、只存本地）不变；编辑器带 `flag=user` 的请求照旧拒绝（D-B13-17，旧编辑器界面不会发出）。
- 会员号必须是十进制、不带符号与前导零、在 int unsigned 范围内、`mac_user` 里存在的编号，否则"文件上传失败！"（D-B14-10），不写盘不写库。
- 图片的检查与转换复用前台头像（B09 `PortraitImages`）：上传内容以 `<?php` 开头、扩展名不在三类允许表内时"非系统允许的上传格式！"；首尾 512 字节有脚本标记时"文件包含危险内容！"；解码失败（伪装图片）、尺寸超限（4000 万像素，WebP 2048×2048）时"文件上传失败！"；没有文件（含超过 2 MB 被丢弃）时"未找到上传的文件…"。通过后按 `user.portrait_size`（每次读旧配置，一个数时为正方形，1～1000）拉伸并重新编码为 JPEG。
- 写入复用 `PortraitStore`（部署参数 `maccms.member.upload-dir`，与前台头像、后台上传的 `maccms.upload.dir` 指向同一目录）：同目录临时文件 + 原子改名。任何检查失败都不写盘，原有头像文件不变；写盘失败不留临时文件。
- 附件登记同前台头像（D-B09-9）：同一文件已登记时不重复；类型为 `image`/`file`/`media`（按扩展名）或 data URL 里的类型；`annex_size` 按新版 JPEG 的 KB。登记失败只记日志。
- 不改 `mac_user`（同旧版）；响应格式与旧版逐字节相同（`size` 为上传内容的 KB，`ctime` 为请求时间）。
- 页面：按钮、地址（`upload/upload?flag=user&user_id=…`）、回填与悬停预览的脚本都沿用旧模板（`user-info.js`），没有新界面。

**证据**：`AdminUserPortraitGoldenMySqlIT`（第 9 节）两版共 132 步与 PHP 对照；`AdminPortraitServiceTest`；浏览器走查的按钮上传、预览、保存、重新打开。

### 8. 页面：复用旧后台结构

#### 8.1 jte 页面与脚本

`backend/src/main/jte-admin/admin/pages/` 下 12 个页面，逐个转自旧模板，DOM、类名、`lay-*`、`data-*`、表单字段不变，`static/layui/**`、`admin_style.css`、`admin_common.js` 原样复用：

| 新模板 | 旧模板（`application/admin/view/`） |
|---|---|
| `user/index、info、reward` `group/index、info` `card/index、info` `order/index` `cash/index` `plog/index` `ulog/index` `visit/index` | 同名 `.html` |

- 内联脚本移到 `backend/src/main/resources/static/admin-assets/`：列表的分页沿用 B12 的 `admin-list.js`；新增 4 个：`user-info`（表单校验、laydate、按会员组显示到期时间、头像上传与回填、悬停预览）、`group-info`（全选、反选、校验）、`card-index`（导出按钮）、`card-info`（校验）；服务端写入的地址与语言包文字改由 script 标签的 `data-*` 提供。
- 旧模板原样输出的文字（会员组名、用户名、昵称、订单号、积分日志备注、推广访问来源等）按浏览器看到的值转义输出（D-B13-19 的同一做法，`BrowserText`）：显示与提交的值与旧版相同，文字里的标签不再被当作标签解析，双引号不再截断属性值。语言包里自带 `<br>` 的两段提示（`admin/group/help_tip`、`popedom_tip`）经 `AdminText.trusted` 原样输出。
- `AdminTemplatePolicyTest` 以 B12 的后台策略重新编译全部模板；本批只放宽一处：卡密列表导出用的隐藏 `<iframe id="if" width="0" height="0"></iframe>`（旧模板原样；模板里不带地址，由 `card-index.js` 写入本站的导出地址），`FRAME_EXCEPTIONS` 只列框架页与卡密列表，卡密列表只能有这一段（测试锁定）。

#### 8.2 模板与功能之间的缺口（按用户规则登记，均沿用旧结构）

| 缺口 | 原因 | 处理 |
|---|---|---|
| 积分日志、推广访问列表的复选框 | 旧模板 `value="{$vo.ulog_id}"` 取的是不存在的键，值为空；勾选后"删除"提交的是空编号（旧版"删除成功"但什么都没删） | **按旧行为保留**（队长 2026-10-26 裁定，登记为旧缺陷）：模板照旧 `value=""`；新版对空编号返回"参数错误"（D-B13-4），同样什么都不删——与旧版的差异只在提示文字；行内"删除"与"清空"正常（第 7.6 节） |
| 订单"支付类型""支付时间"、提现"备注"列 | 旧模板取的键不存在 | 照旧为空 |
| 新增会员表单的到期时间 | 旧版 `date('Y-m-d H:i:s', null)`：PHP 8 为当前时间、7.3 为 1970-01-01 08:00:00 | 按 PHP 7.3（D-B14-7）；该栏在默认组时隐藏，保存时写 0 |

### 9. 测试与验证

| 内容 | 测试 / 证据 |
|---|---|
| PHP 黄金（PHP 8.0.30 与 7.3.33） | `backend/src/test/php/admin_member_golden.php`（子进程入口原样复用 B13 的 `admin_content_child.php`）：一次性库、php-cgi 跑完整的 `_admin.php` + ThinkPHP + 控制器；每个场景写入自己的行；记录每个请求的状态码、头（含 Content-Disposition）、正文与请求后变化的表的全部行。两版各 32 个场景、230 个请求步骤 → `backend/src/test/resources/admin/member/php{80,73}-golden.json` |
| 黄金复放（真实 MySQL） | `AdminMemberGoldenMySqlIT.scenariosMatchPhpGolden`：按场景的旧配置启动打开可写数据源与后台的应用上下文，每步把时钟拨到 PHP 请求开始的秒，比较状态码、Location、JSON 字节、页面结构（`AdminContentHtml`）、CSV 正文与 Content-Disposition、请求后 13 张表的全部行；两版共 **460 步**，除 `JAVA`/`JAVA_NO_WRITE`/`JAVA_RESTORE`/`JAVA_GLOBAL`/`JAVA_REGEX` 逐条断言的有意差异（第 10 节）外 0 处不同。新生成的卡号与卡密两边各自随机，只比较长度（字符集见单元测试） |
| 并发与对账 ② | 同一测试类 Order 2～7：**资金类对账**——10 个会员各 1000 积分，32 个线程同时做会员中心的提现申请与撤销（B10 `MemberWalletService`）、后台的审核与删除，结束时每个会员"冻结 = 未审核提现合计"（第 6.3 节的对账语句为空）、"可用 + 冻结 + 已审核提现 = 1000"；16 个管理员同时审核同一批提现，每条只审核一次、冻结只扣一次；12 个管理员同时删除同一条未审核提现，冻结只退一次；后台保存会员表单与 15 次并发提现申请（整行覆盖与旧版一致，无出错，"冻结 = 未审核合计"仍成立）；8 个管理员同时各生成 50 张卡（400 行、卡号不重复）；到期时间恰好等于现在的会员在第 1 页降级、第 2 页不触发；删除会员组与把会员改到该组并发时，条件删除不删已有会员的组 |
| 会员头像上传：PHP 黄金（PHP 8.0.30 与 7.3.33） | `backend/src/test/php/admin_user_portrait_golden.php` + `admin_portrait_child.php`（B13 的子进程入口加上 `VENDOR_PATH`，用仓库外恢复的 `vendor/` 里的 think-image；线上站点根目录下有 `vendor/`）：一次性库、php-cgi 跑完整的 `_admin.php`；每个请求记录状态码、头、正文、请求后 `mac_user`、`mac_annex` 的全部行与 `upload/` 文件清单（大小、sha256、开头字节、`getimagesize`）。两版各 30 个场景、66 个请求步骤：完整流程（打开表单 → 上传 → 保存 → 重新打开）、已有头像、再次上传、PNG/GIF/WebP/imgdata、一个数的尺寸、前台头像开关关闭、已登记过的文件、扩展名属于"文件"类、无权限（`viewer` 没有 `upload/upload`）、`editor`、未登录、会员号缺失/0/不存在/非数字/负数/小数/带字母/前导零/`../../1`、伪装图片、禁止的扩展名、PHP 内容、脚本标记、没有文件、超过 2 MB、失败时原头像不变 → `backend/src/test/resources/admin/member/portrait-php{80,73}-golden.json` |
| 会员头像上传：黄金复放（真实 MySQL、真实 HTTP） | `AdminUserPortraitGoldenMySqlIT.portraitUploadMatchesPhpGolden`：按场景的旧配置启动内嵌 Tomcat（随机端口），经 HTTP 发出与 PHP 相同字节的请求（multipart 解析与 2 MB 上限与线上相同），比较状态码、Location、JSON 字节或页面结构、`mac_user` 全部行、`mac_annex` 除 `annex_size` 外的全部行（新版 `annex_size` 须等于新版文件的 KB）、`upload/` 文件路径（含临时文件，失败不能留下），新版头像须是配置尺寸的 JPEG、不能写到 `upload/user/` 之外。两版共 **132 步**、新版成功上传 26 次，除 `JAVA` 逐条断言的有意差异（D-B14-10；同 D-B09-14 的 PHP 8 GIF 按 PHP 7.3；伪装图片旧版 500、新版"文件上传失败！"；脚本标记旧版先落盘、新版不落盘）外 0 处不同。变异检查：去掉"会员存在"判断后该测试失败（`id-absent` 写出 `upload/user/9/999.jpg`） |
| 会员头像上传：回滚演练 | 同一测试类 `rollbackRestoresPortraitFilesAndRowsWithDmlAndTheUploadBackup`：快照 `mac_user`、`mac_annex` 与 `upload/` → 经 HTTP 上传两位会员的头像（一位覆盖已有头像文件）并保存表单 → `DmlRestore` 以 3 条 DML 恢复两张表、按备份恢复 `upload/`（删除新文件、写回被覆盖的文件）→ 逐行、逐文件相同，表结构指纹不变 |
| 会员头像上传：单元测试 | `AdminPortraitServiceTest`（8 个）：成功时只写重新编码的 JPEG、登记附件、只查会员是否存在而不读写会员行；一个数的尺寸与 data URL 类型；15 种非法或不存在的会员号不写盘、不读库（非法时）；伪装图片、PHP 内容、禁止扩展名、脚本标记、没有文件都不写盘且原头像不变；尺寸配置非法或目录未配置；写盘失败不留临时文件；登记附件失败不影响上传 |
| 同一提现的审核与删除/撤销交错 | `AdminMemberGoldenMySqlIT` Order 10～14（第 6.2 节表格）：屏障固定"认领后、扣冻结前""删除认领后、退冻结前""扣冻结不命中后、撤回前""退冻结不命中后、插回前"四个窗口，逐步断言提现行、可用与冻结积分、对账语句与 ERROR 日志 |
| 回滚演练 ③ | Order 8：快照 8 张会员与财务表 → 经各服务做 17 次 B14 写入（会员新增、编辑、状态、删除与到期降级，会员组增改删与状态，生成与删除卡密，删除订单，提现审核与删除，三种日志的删除与清空）→ `DmlRestore` 以 27 条 DML 恢复 → 逐行 diff 为空 → 表结构指纹与导入 install.sql 时相同 |
| 一次性库 | `maccms_it_<yyyyMMddHHmmss>_<8 位十六进制>`（名称先校验），结束时删库并确认不存在；不连 `maccms_dev` |
| 旧语义单元测试 | `MemberAdminUnitTest`（`strtotime` 子集对照 PHP 实测值、卡号字符集与首位、`group_popedom` 的 `json_encode` 对照 PHP、勾选判断、`reg_group` 宽松比较、推广访问前缀、第 1 页判断、`md5` 字节语义）；`AdminParamsNestedTest`（对照 PHP `parse_str`） |
| 护栏 | `WriteGuardTest`（登记表 60 项与写入方矩阵）、`WriteRegistryTest`、`ReadOnlyGuardrailsTest`（新的读 Mapper 只有 SELECT、不读会员密码）、`AdminTableSchemaTest`（`USER`、`GROUP`）、`AdminTemplatePolicyTest`（全部后台模板与 `admin-assets`；卡密列表的 iframe 例外）、`ModuleBoundaryTest`、`SchemaGuardTest`、`SqlEntryGuardTest`、`OutboundNetworkGuardTest` |
| 浏览器走查 | `backend/src/test/browser/b14_admin_member_check.py`（Playwright 本机 Chromium 无头）+ 合成数据 `b14_seed.sql`：一次性库 `maccms_it_*`、临时旧配置、临时站点根 `upload/`、本机 jar 与只服务本次的 nginx（自带前缀与配置，只提供 `/upload/` 与反向代理，不动共用的 `D:/Tools/nginx`）。第二轮 **52 项全部通过**（新增头像 11 项：按钮选图上传、地址带 `flag=user&user_id=1`、回填、文件是 100×100 JPEG、登记附件、上传本身不改 `user_portrait`、悬停预览从 `/upload/` 读到新头像、保存后写库、重新打开是新地址、伪装图片被拒绝且原头像不变、不存在或非法的会员号失败且不落盘）；其余与第一轮相同：登录；会员列表（layui 表格、分页、第 1 页到期降级、状态开关写库）；添加会员弹层（layui 下拉、laydate、选 VIP 组后显示到期时间、保存后库里是 `md5(原值)`、组、到期时间与积分）；编辑会员（密码框为空、留空不改密码、积分写库）；行内删除；推广会员页的三级人数与奖励；会员组添加（全选后的分类串与权限 JSON）、编辑（按 JSON 勾选、价格写库）、状态开关、删除有会员的组得到提示且不删；生成 5 张卡（16 位数字卡号、8 位卡密）、"导出"经隐藏 iframe 下载 CSV（文件名、表头、行数）、清空；订单行内删除；提现行内审核（冻结扣减）、勾选删除未审核提现（冻结退回）、对账语句为空；积分日志、访问日志（所属内容名称、行内删除）、推广访问（清空）；全程内容安全策略违规、页面脚本错误、控制台错误、对外部主机的请求、4xx/5xx 响应均为 0 |
| 测试总数 | 第一轮（`79fa074`）：单元 2659 个、0 失败；MySQL 集成 85 个、0 失败、跳过 1 个。第二轮的数字见交付评论（最终树上重跑） |

### 10. 与旧版的差异

| 编号 | 旧版 | 新版 | 原因 |
|---|---|---|---|
| D-B14-1 | 提现审核先把记录标为已审核、再 `setDec` 冻结积分；删除未审核提现先删记录、再按读到的旧值整体覆盖积分。冻结积分不够（数据不一致，或后台改过冻结积分）时扣成负数，MySQL 报错 500：已审核但冻结未扣 / 记录已删但积分未退（黄金 `cash-audit/array`、`cash-audit-froze-short`、`cash-del-froze-short`、`cash-del/all`） | 条件写入：冻结积分不够时撤销这一行（审核撤回、删除插回），返回"更新用户积分失败："（旧版同一处的提示文字），已处理的前面各行保留 | 资金一致性 |
| D-B14-2 | 已审核的提现再次审核：再写审核时间、再扣一次冻结积分（黄金 `cash-audit-again`）；审核时对选中的全部行反复写状态 | 只认领未审核的行，已审核的跳过（"审核成功"） | 缺陷（重复扣减） |
| D-B14-3 | 删除未审核提现按读到的值整体覆盖积分，与会员中心的提现、撤销、购买并发时丢失更新 | 增量条件更新（同 B10 D-B10-4），并发对账成立（第 9 节 ②） | 并发正确性 |
| D-B14-4 | 生成卡密数量不限（很大时整页内存耗尽）；卡号卡密用 `mt_rand`；一条 `insertAll` | 一次最多 10000 张，超过"参数错误"；安全随机数；每 500 张一条 `INSERT` | 加固 |
| D-B14-5 | 到期时间不是数字串时 `strtotime` 任意写法 | 只实现后台日期控件写出的写法（第 4.3 节），其他写法"参数错误" | 实现范围（避免把认不出的写法悄悄写成 0 或别的时间） |
| D-B14-6 | 提现、积分日志、访问日志的会员名取"最新 999 个会员"（条件写错了变量），较早的会员显示为空 | 按本页各行的会员编号关联 | 缺陷（同 D-B10-5） |
| D-B14-7 | 新增会员表单的到期时间：PHP 8 为当前时间、PHP 7.3 为 1970-01-01 08:00:00 | 按 PHP 7.3 | 版本差异取一种（同 D-B13-5 的取法） |
| D-B14-8 | 删除会员组：先数该组下的会员、没有才删，两步之间被分配了会员的组照样删掉 | 删除语句另带"组下没有会员"的条件 | 并发正确性 |
| D-B14-9 | 卡密导出的缓存头 `Cache-Control: must-revalidate,post-check=0,pre-check=0`、`Expires: 0`、`Pragma: public`、`Accept-Ranges: bytes` | `Cache-Control: no-store`、`nosniff`；正文、类型与文件名不变 | 卡密不应被缓存 |
| D-B14-10 | 会员头像上传不查会员号：缺失或 0 写 `upload/user/0/0.jpg`；不存在（`999`）、负数（`-3`）、小数（`1.5`）、带字母（`15abc`）、前导零（`015`）照样写出对应路径的文件并登记附件；PHP 7.3 上非数字写 `user/0/abc.jpg`、`../../1` 写到 `upload/1.jpg.jpg`（`user/` 之外）；PHP 8 上非数字报 500（黄金 `id-*`） | 会员号必须是 `mac_user` 里存在的十进制编号（不带符号与前导零），否则"文件上传失败！"，不写盘不写库 | 安全加固（路径与孤立文件；表单里的按钮只会带已有会员的编号） |
| — | 会员头像上传：旧版先把原文件写进 `<id>.jpg` 再检查脚本标记，拒绝后文件留在磁盘上并覆盖了原头像；伪装图片（扩展名合法、内容不是图片）两版都是 500；PHP 8 上 GIF 因 think-image 的解码器 500；文件按原格式保存（PNG、WebP 字节写进 `.jpg`）；`annex_size` 取原文件大小 | 检查通过才写盘；伪装图片"文件上传失败！"；GIF 按 PHP 7.3 正常生成；一律重新编码为 JPEG；`annex_size` 按新版 JPEG | 同 D-B13-16、D-B09-9、D-B09-13、D-B09-14（前台头像的同一规则） |
| — | 积分日志、推广访问列表勾选后批量删除：复选框的值为空（旧模板缺陷），旧版"删除成功"、什么都没删 | "参数错误"、什么都没删；模板照旧 | 旧缺陷照旧保留（队长 2026-10-26 裁定）；提示文字的差异同 D-B13-4 |
| — | 旧版严格模式下报错（500）的取值；`ids` 非数字（旧版"删除成功""审核成功"、什么都不改，含积分日志与推广访问列表复选框提交的空编号）；`group_type` 不是数组 | "参数错误"、不写库 | 同 D-B13-3、D-B13-4 |
| — | 页面原样输出库里的文字（标签被解析、引号截断属性） | 按浏览器看到的值转义输出 | 同 D-B13-19 |
| — | 分页脚本里原样写出 `$param['page']`（`2.0`） | 按 `intval` | 同 D-B13-21 |
| — | 后台保存会员表单整行覆盖（积分、冻结积分、推广人等按表单写），打开表单后会员在前台的消费、冻结被覆盖 | 相同 | **与旧版一致**（路线图 B13/B14、03 草稿第 3.1 节） |
| — | 新增会员组被 `reg_group`（null）强制启用；`ids` 有空段时"注册默认会员组无法删除!" | 相同 | 行为兼容（第 4.4 节） |
| — | 会员组 `field`、`del` 不限编号（可停用、删除游客与默认会员组） | 相同 | 行为兼容（页面上不显示这些按钮） |

### 11. 回滚

- **代码**：本批以一个合并提交进入 main；出问题时 `git revert -m 1 <合并提交>`，回到上一 main 快照（`bd584f3`，树同现在的 main `d7bddf8`）再跑 `./mvnw verify`。不涉及表结构、数据迁移或配置格式；后台默认关闭（`MACCMS_ADMIN_ENABLED=false`）。
- **文件**：会员头像写在 `upload/user/` 下（与前台头像同一目录与命名），无法从数据库恢复；切换与回滚前先备份 `upload/`（22 文档第 11 节的同一要求），回滚时删除新文件、写回被覆盖的文件（第 9 节头像回滚演练）。
- **数据**：写入只有对现有列的 DML（第 3 节），回滚演练（第 9 节 ③）证明全部可用 DML 恢复。新版写入的行（会员、会员组、卡密等）与旧版格式相同，切回 PHP 后照常读取；新版生成的 `group_popedom` 与旧版 `json_encode` 逐字节相同。
- **对账**：切换与回滚前后各跑一次第 6.3 节的对账语句；不为空的会员先人工核对（旧版本身可能留下不一致，D-B14-1）。

### 12. 风险与遗留（第二轮起没有待决事项）

| 项 | 说明 |
|---|---|
| 已决：订单手工改状态 | 旧后台没有这个入口，按"功能一样、不增不减"不新增（队长 2026-10-26 裁定），路线图已更正（第 1 节） |
| 已决：后台会员头像上传 | 已实现（第 7.7 节）。需要部署参数 `maccms.member.upload-dir`（与 `maccms.upload.dir` 指向同一目录），未配置时上传返回"文件上传失败！" |
| 已决：积分日志、推广访问列表的批量删除 | 按旧行为保留（旧缺陷，第 7.6 节、第 10 节） |
| 已决：D-B14-4～-9 | 按已说明的边界接受（队长 2026-10-26） |
| 头像上传与会员删除并发 | 检查会员存在之后、写文件之前会员被删除时，头像文件照样写出（旧版对任何编号都写，影响相同）；不改会员表 |
| 需人工对账的提现窗口 | 只在数据本已不一致（冻结积分少于提现积分）时出现，见第 6.2 节表格：有错误提示、ERROR 日志（提现号、会员号、积分）与对账语句 |
| 删除与撤回审核交错的提示 | 数据本已不一致时，删除认领不命中、重读时审核已撤回（又是未审核），这一行不删、仍返回"删除成功"（再删一次即可，积分不受影响；B10 会员撤销是同样的写法） |
| 整行覆盖覆盖积分 | 后台保存会员表单时积分、冻结积分按表单写，会覆盖打开表单之后会员的消费与冻结，对账从管理员填的值重新开始（与旧版一致） |
| MyISAM 没有事务 | 第 6.2 节所列多语句操作可能部分生效；提现的每一行由 `WriteSequence` 保证"要么两步都生效、要么都撤销"，补偿也失败时 ERROR 日志、需人工对账 |
| `strtotime` 只实现一部分 | 第 4.3 节；日期控件写出的值都能认，手工改地址或改表单写别的写法时"参数错误" |
| 卡号随机 | 卡号来自安全随机数，与旧版 `mt_rand` 的序列不同（不影响格式）；跨批不查重（16 位、35 或 10 个字符，碰撞概率可忽略，旧版同样不查） |
| 浏览器走查的范围 | 只在本机无头 Chromium 上走了第 9 节所列流程；推广会员页的筛选、提现批量审核、卡密按条件导出只有黄金对照，没有逐个点过；头像的 WebP/GIF、超过 2 MB、无权限只有黄金与集成测试，没有在浏览器里点过 |
| `user.reg_group` | 仓库与旧系统配置页都没有这一项，新增会员组总被强制启用（旧版如此）；系统配置在 B15 |
