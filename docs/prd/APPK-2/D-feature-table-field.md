# APPK-2 附表 D：功能 → 现有表 → 字段 逐项清单

> 属于 `docs/prd/APPK-2.md`（v4）。冲突时以主文件为准。表名/列名全部取自附表 A（脚本生成并逐列校验，写错列名会使生成失败）。v4 起：本表不再使用通配写法和开放表达（只在“v3 → v4 展开对照”表里引用 v3 的旧写法）；动态场景在 D.3 逐个给出允许集合、来源规则和越界判定；D.2 给出命名的列集合。**D.1～D.4 与 D-01～D-23 同属一个锁定版本（BR-10）。**

**写法约定**：
1. “整行”= 该表附表 A 中的全部列（括号里写列数，列名以附表 A 为准）。
2. “指定列”= 本表或 D.2 命名集合里逐列列出的列，不得推广到同表其他列。
3. 读取栏只写表（“整行”）的，表示 `SELECT` 可取该表任意列；某行单独写了“不返回/只读”的列限制时，以该限制为准。
4. 所有写入只做 `INSERT/UPDATE/DELETE`，不含 DDL（BR-1）。
5. 指定列取自 PHP 源码该写入点附近出现的该表字段并集（宁多勿少），可由 `php/application` 下对应控制器/模型的更新语句复核；未逐语句运行验证。

**越界判定（唯一规则，AC-16 引用）**：对设计单或实现里的每一个读写项（表、列、文件/配置），在其所属功能行（D-01～D-23）依次判：①该表是否列在该行的读取栏或写入栏（方向要对：读项必须在读取栏，写项必须在写入栏）；②该列是否被该行列出，或该行对此表写的是“整行”；③若该行标注“判定见 DYN-n”，还必须满足 D.3 中 DYN-n 的允许集合；④文件/配置项是否列在该行的“文件与配置”栏。任一为“否”= **越界**，按 BR-8/RISK-8 停下并新增 OP-，不得自行扩大。

**覆盖范围**：附表 C 登记簿 `index`、`api`、`admin`、`install` 共 379 行，每一行在 D.4 都映射到唯一的一个 D 编号（脚本核对：379 行全部映射，无遗漏、无重复）；D-02（标签库）、D-17 中的“前台头像上传”没有独立的 C 行，由 FR-3 / AC-3 与 D-07b 的 C 行验收。

| 编号 | 对应 FR | 功能 | 入口（旧控制器方法） | 读取的表与字段 | 写入的表与字段 | 文件与配置（不在库里） |
| --- | --- | --- | --- | --- | --- | --- |
| D-01 | FR-5 | 首页、地图、RSS、帮助、公告、搜索聚合、自定义标签页、验证码页、二维码、404 页 | 前台 `Index`/`Map`/`Rss`/`Help`/`Note`/`Search`/`Label`/`Verify`/`Qrcode`/`MyError`/`Base._empty` 的全部方法（附表 C 中对应的 18 行） | 只读，且限于 **TS-TAG**（D.2）列出的 11 张表：`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search`；读取方式 = 页面模板里的 `{maccms:xxx}` 标签（读取范围与排序列见 D-02 和 D.3 DYN-1/DYN-2），或本行页面自带的数据查询（`Map`/`Rss`/`Search` 读 `mac_vod`、`mac_art`，均在 TS-TAG 内）。超出 TS-TAG 的表（例如 `mac_user` 的密码列、`mac_admin`）不得读取，判定见 DYN-1 | 无 | `template/<dir>/<html>/` 目录（含其下 `label/` 目录里的 `.html` 文件）、`application/extra/maccms.php`（`site` 节的 `site_` 开头配置键、`app` 节的 `page_404`）、`extra/captcha.php`、`extra/maccms.php` 的 `rewrite` 节、`application/route.php`；验证码与会话在库外 |
| D-02 | FR-3 | Maccms 标签库 19 个标签 | 模板中 `{maccms:xxx}` | `vod`：`mac_vod` 整行（82 列）+ `mac_type` 整行（20 列，分类筛选与继承）+ `mac_vod_search`（搜索优化开启时）整行（8 列）；`art`：`mac_art` 整行（46 列）、`mac_type` 整行；`topic`：`mac_topic` 整行（37 列）；`topic_rel_vod`/`topic_rel_art` 指向的 `mac_vod`/`mac_art` 整行；`actor`：`mac_actor` 整行（42 列）；`role`：`mac_role` 整行（29 列）；`role_rid` 关联的 `mac_vod` 整行；`website`：`mac_website` 整行（42 列）；`comment`：`mac_comment` 整行（14 列）；用户头像经 `mac_get_user_portrait` 只读 `mac_user`：`user_id`、`user_portrait`；`gbook`：`mac_gbook` 整行（10 列）；头像同 `comment`，只读 `mac_user`：`user_id`、`user_portrait`；`type`：`mac_type` 整行；`link`：`mac_link` 整行（8 列）；`area`/`lang`/`class`/`year`/`version`/`state`/`letter`：不读表，取 `extra/maccms.php` 对应配置项（`mac_type.type_extend` 的筛选项由 `type` 标签读）；`foreach`/`for`：不读表；每个标签的**排序列**限定为 D.3 DYN-2 的白名单；**筛选条件**只作用于该标签自己的表的附表 A 中的列，不得引入其他表 | 无（`cachetime` 只写库外缓存文件） | `extra/maccms.php`、`runtime/cache`（库外） |
| D-03 | FR-5 | 视频前台页（分类、筛选、搜索、详情、播放、下载、观看密码、版权、角色、剧情、RSS、ajax 片段） | 前台 `Vod` 全部方法 | `mac_vod`、`mac_type`、`mac_group`、`mac_user`、`mac_ulog`、`mac_role`、`mac_vod_search`；`group_popedom` 判定权限，`ulog` 判定是否已购买；`vod_pwd/vod_pwd_url/vod_pwd_play/vod_pwd_play_url/vod_pwd_down/vod_pwd_down_url` 观看密码；`vod_points/vod_points_play/vod_points_down` 积分 | 本类方法不直接写库；计数写入见 D-05，扣积分见 D-07 | `extra/vodplayer.php`、`vodserver.php`、`voddowner.php`、`static/js/playerconfig.js` |
| D-04 | FR-5 | 文章、演员、角色、专题、网址、剧情前台页 | 前台 `Art`/`Actor`/`Role`/`Topic`/`Website`/`Plot` 全部方法 | `mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website`、`mac_type`、`mac_group`、`mac_user`、`mac_ulog`、`mac_vod`（专题/角色/剧情页按关联读 `mac_vod`、`mac_art`） | 不直接写库；计数写入见 D-05 | `extra/maccms.php` |
| D-05 | FR-5 | 前台计数与互动 ajax（`Ajax`） | `Ajax.php` 的 data/suggest/hits/referer/digg/score/pwd/verify_check | `data`：`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website` 的整行，**但**不返回 D.2 CS-DATA-EX 列出的列，且非会员再不返回 CS-VOD-HIDE 的 8 列；筛选限 `vod_status`、`art_status`、`topic_status`、`actor_status`、`role_status`、`website_status` 各自等于 1；`suggest`：只读 D.2 CS-SUGGEST 的列；`hits`/`score`/`digg`：读被计数行的主键与被更新列（D.2 CS-HITS/CS-DIGG/CS-SCORE 即下列写入列）；`referer`：`mac_website`：`website_jumpurl`；`pwd`：只读 D.2 CS-PWD 的列 | `hits`（mid 1,2,3,8,9,11）：`mac_vod`：`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_time_hits`；`mac_art`：`art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_time_hits`；`mac_topic`：`topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_time_hits`；`mac_actor`：`actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_time_hits`；`mac_role`：`role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_time_hits`；`mac_website`：`website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_time_hits`；`digg`（mid 1,2,3,4,8,9,11）：`mac_vod`：`vod_up`、`vod_down`；`mac_art`：`art_up`、`art_down`；`mac_topic`：`topic_up`、`topic_down`；`mac_actor`：`actor_up`、`actor_down`；`mac_role`：`role_up`、`role_down`；`mac_website`：`website_up`、`website_down`；`mac_comment`：`comment_up`、`comment_down`；`score`（mid 1,2,3,8,9,11）：`mac_vod`：`vod_score`、`vod_score_num`、`vod_score_all`；`mac_art`：`art_score`、`art_score_num`、`art_score_all`；`mac_topic`：`topic_score`、`topic_score_num`、`topic_score_all`；`mac_actor`：`actor_score`、`actor_score_num`、`actor_score_all`；`mac_role`：`role_score`、`role_score_num`、`role_score_all`；`mac_website`：`website_score`、`website_score_num`、`website_score_all`；`referer`：`mac_website`：`website_referer`、`website_referer_day`、`website_referer_week`、`website_referer_month`、`website_time_referer` | 验证码校验、已投票/已评分 cookie 在库外 |
| D-06 | FR-5 | 评论、留言（前台） | 前台 `Comment`、`Gbook` | `mac_comment`、`mac_gbook`、`mac_user`、`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`（评论目标存在性） | `comment`：`mac_comment` 整行（14 列，附表 A 的 T 表）（新增）、`mac_comment`：`comment_report`（举报 +1）、`mac_comment`：`comment_up`、`comment_down`；`gbook`：`mac_gbook` 整行（10 列，附表 A 的 T 表）（新增） | `extra/maccms.php`（评论/留言开关、审核、间隔）、验证码 |
| D-07a | FR-5 | 会员注册、登录、退出、找回密码、绑定 | 前台 `User` 的 index/login/logout/reg/regcheck/reg_msg/oauth/logincallback/bindmsg/bind/unbind/findpass/findpass_msg/findpass_reset/ajax_login/ajax_info（附表 C 中 index/User.php 的这 16 行） | `mac_user`、`mac_group`、`mac_msg` | `mac_user`：新增注册时整行（30 列）；登录更新 `user_login_num`、`user_login_ip`、`user_login_time`、`user_last_login_ip`、`user_last_login_time`、`user_random`；找回/改密 `user_pwd`；绑定 `user_openid_qq`、`user_openid_weixin`；`mac_msg` 整行（8 列，附表 A 的 T 表）（短信/邮件验证码记录）；邀请注册加分 `user_points`（见 D-07d） | `extra/maccms.php`（user 节）、`extra/captcha.php`、`extra/maccms.php` 的 `connect` 节（第三方登录）；登录态在库外 |
| D-07b | FR-5 | 会员资料、头像、密保 | 前台 `User` 的 info/portrait（附表 C 中 index/User.php 的这 2 行） | `mac_user` | `mac_user`：`user_nick_name`、`user_email`、`user_phone`、`user_portrait`、`user_question`、`user_answer`、`user_pwd`；头像文件另写 `mac_annex`（见 D-17） | `upload/` 目录文件 |
| D-07c | FR-5 | 购买播放/下载权限、会员升级、积分 | 前台 `User` 的 ajax_buy_popedom/buy/upgrade/popedom（附表 C 中 index/User.php 的这 4 行） | `mac_user`、`mac_group`、`mac_ulog`、`mac_plog`、`mac_vod`、`mac_art` | `mac_user`：`user_points`、`user_end_time`、`group_id`；`mac_ulog` 整行（9 列，附表 A 的 T 表）（购买记录）；`mac_plog` 整行（7 列，附表 A 的 T 表）（积分流水） | — |
| D-07d | FR-5 | 收藏、播放记录、下载记录、积分记录、分销 | 前台 `User` 的 favs/plays/downs/ulog/ajax_ulog/ulog_del/plog/plog_del/reward/visit（附表 C 中 index/User.php 的这 10 行） | `mac_ulog`、`mac_plog`、`mac_user`、`mac_vod`、`mac_art`、`mac_actor`、`mac_topic`、`mac_role`、`mac_website` | `mac_ulog` 整行（9 列，附表 A 的 T 表）；`mac_plog` 整行（7 列，附表 A 的 T 表）（读写）；`mac_user`：`user_points`、`user_pid`、`user_pid_2`、`user_pid_3`；`mac_visit` 整行（5 列，附表 A 的 T 表）（邀请访问记录，邀请访问加分 `user_points`） | `extra/maccms.php`（分销比例、积分规则） |
| D-07e | FR-5 | 在线支付、订单、充值卡、提现、我的评论/留言 | 前台 `User` 的 pay/gopay/qrcode/orders/order_info/cards/cash/cash_del/comment/gbook（index/User.php 的这 10 行）；`Payment.notify`（1 行） | `mac_order`、`mac_card`、`mac_cash`、`mac_user`、`mac_plog`、`mac_comment`、`mac_gbook` | `mac_order` 整行（10 列，附表 A 的 T 表）（下单）；`mac_order`：`order_status`、`order_pay_time`、`order_pay_type`（支付回调）；`mac_card`：`card_points`、`card_sale_status`、`card_use_status`、`card_use_time`、`user_id`；`mac_cash` 整行（10 列，附表 A 的 T 表）（申请提现）；`mac_user`：`user_points`、`user_points_froze`；`mac_plog` 整行（7 列，附表 A 的 T 表）（积分流水） | `extra/maccms.php` 的 `pay` 节（支付通道配置） |
| D-08 | FR-12 | 开放接口（`api` 控制器） | `Provide`（vod/art/actor/role/website/comment 列表与详情，采集协议 XML/JSON）、`Receive`（外部入库） | Provide：`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_type`（只读） | Receive：`mac_vod` 整行（82 列，附表 A 的 T 表）；`mac_art` 整行（46 列，附表 A 的 T 表）；`mac_actor` 整行（42 列，附表 A 的 T 表）；`mac_role` 整行（29 列，附表 A 的 T 表）；`mac_website` 整行（42 列，附表 A 的 T 表）；`mac_comment` 整行（14 列，附表 A 的 T 表）；`mac_vod_search`（搜索优化开启时）；其余控制器不直接写库 | `extra/maccms.php`（api 节、接口密码）、`extra/bind.php` |
| D-09 | FR-12 | 定时任务入口、公众号、接口首页 | API `Timming`、`Wechat`、`Index` | 无（定时任务调用的生成/采集功能所读写的表见 D-14、D-19） | 无 | `extra/timming.php`、`extra/maccms.php`（weixin 节） |
| D-10 | FR-12 | 后台登录、首页、欢迎页、缓存清理、快捷菜单 | 后台 `Index`、`Base` | `mac_admin`、`mac_type`、`mac_vod_search` | `mac_admin`：`admin_login_num`、`admin_login_ip`、`admin_login_time`、`admin_last_login_ip`、`admin_last_login_time`、`admin_random`；缓存清理时删除 `mac_vod_search` 的过期行（DML `DELETE`） | `runtime/` 缓存目录、`extra/quickmenu.php` |
| D-11 | FR-12 | 内容管理：视频、文章、演员、角色、专题、网址 | 后台 `Vod`/`Art`/`Actor`/`Role`/`Topic`/`Website` 的 data/info/del/batch/field/updateToday（`Vod` 另有 `iplot`） | 各自内容表（`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website` 整行）+ `mac_type`、`mac_group`、`mac_vod` | 各自内容表整行：`mac_vod` 整行（82 列，附表 A 的 T 表）；`mac_art` 整行（46 列，附表 A 的 T 表）；`mac_topic` 整行（37 列，附表 A 的 T 表）；`mac_actor` 整行（42 列，附表 A 的 T 表）；`mac_role` 整行（29 列，附表 A 的 T 表）；`mac_website` 整行（42 列，附表 A 的 T 表）；`mac_vod_search`（同步）；重复数据查询：**旧版用 `CREATE TABLE mac_tmpvod/tmpart/tmpwebsite`（DDL）**，Java 已改子查询（`docs/architecture/22-b13-admin-content.md` D-B13-7），本期不得回到 DDL（BR-1） | `upload/` 图片文件 |
| D-12 | FR-12 | 分类管理 | 后台 `Type` 的 index/info/del/field/batch/extend/move | `mac_type`、`mac_vod`、`mac_art`、`mac_actor`、`mac_website` | `mac_type` 整行（20 列，附表 A 的 T 表）；`move`：`mac_vod`、`mac_art` 的 `type_id`、`type_id_1`（`mac_vod`：`type_id`、`type_id_1`；`mac_art`：`type_id`、`type_id_1`） | `extra/maccms.php` 的筛选项配置 |
| D-13 | FR-12 | 会员与交易类后台 | 后台 `User`/`Group`/`Card`/`Cash`/`Order`/`Plog`/`Ulog`/`Comment`/`Gbook`/`Link`/`Visit`/`Admin`/`Annex` | `mac_user`、`mac_group`、`mac_card`、`mac_cash`、`mac_order`、`mac_plog`、`mac_ulog`、`mac_comment`、`mac_gbook`、`mac_link`、`mac_visit`、`mac_admin`、`mac_annex` | 各自表整行：`mac_user` 整行（30 列，附表 A 的 T 表）；`mac_group` 整行（10 列，附表 A 的 T 表）；`mac_card` 整行（10 列，附表 A 的 T 表）；`mac_cash` 整行（10 列，附表 A 的 T 表）；`mac_order` 整行（10 列，附表 A 的 T 表）；`mac_plog` 整行（7 列，附表 A 的 T 表）；`mac_ulog` 整行（9 列，附表 A 的 T 表）；`mac_comment` 整行（14 列，附表 A 的 T 表）；`mac_gbook` 整行（10 列，附表 A 的 T 表）；`mac_link` 整行（8 列，附表 A 的 T 表）；`mac_visit` 整行（5 列，附表 A 的 T 表）；`mac_admin` 整行（11 列，附表 A 的 T 表）；`mac_annex` 整行（5 列，附表 A 的 T 表）；`Cash.audit`：`mac_cash`：`cash_status`、`cash_time_audit`、`mac_user`：`user_points_froze`；`User.reward`：`mac_user`：`user_points` + `mac_plog` 新增；`System` 保存会员组配置时读取 `mac_group` | `extra/maccms.php` |
| D-14 | FR-11/FR-12 | 资源站采集（本地版本与在线版本） | 后台 `Collect`（含 `union/load/api/bind/clearbind/vod/art/actor/role/website` 与 `timing`），API `Receive` | `mac_collect`、`mac_type`、`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` | `mac_collect` 整行（12 列）；入库写 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` 整行（各表列数见 D-08）；`mac_vod_search`（搜索优化开启时）；远程图片落盘时 `mac_annex` 整行（仅 OP-5 放开后）；入库目标表只能是上述 6 张内容/评论表，采集类型与表的对应见 D.3 DYN-5 | `extra/bind.php`、`extra/timming.php`；本地版本只读本地文件源 |
| D-15 | FR-11/FR-12 | 自定义规则采集 | 后台 `Cj`（14 行） | `mac_cj_node`、`mac_cj_content`、`mac_cj_history`、`mac_collect`（均整行）；`content_into`（发布）查重与分类映射读 `mac_vod`（节点 `mid`=1）或 `mac_art`（`mid`=2）、`mac_type`；`program` 页执行 `SHOW COLUMNS FROM mac_vod`（`mid`=1）或 `mac_art`（`mid`=2）取字段名清单（只读结构信息，不改结构） | `mac_cj_node` 整行（31 列，新增/修改/导入）；`mac_cj_node`：`lastdate`（采集后更新）；`mac_cj_content`：`nodeid`、`status`、`url`、`title`、`data`；`mac_cj_history`：`md5`；**发布（`content_into`）只写**：节点 `mid`=1 时 `mac_vod` 整行（82 列）+ `mac_vod_search`（搜索优化开启时），`mid`=2 时 `mac_art` 整行（46 列）；不写 actor/role/website/topic。判定见 D.3 DYN-6 | 规则导入导出文件 |
| D-16 | FR-12 | 图片同步（远程图片下载） | 后台 `Images` 的 data/opt/del/sync | `mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website` | 各内容表的图片/正文列：`mac_vod`：`vod_pic`、`vod_content`；`mac_art`：`art_pic`、`art_content`；`mac_topic`：`topic_pic`、`topic_content`；`mac_actor`：`actor_pic`、`actor_content`；`mac_role`：`role_pic`、`role_content`；`mac_website`：`website_pic`、`website_content`；`mac_annex` 整行 | `upload/`（仅 OP-5 放开后可下载远程图） |
| D-17 | FR-12 | 上传与附件 | 后台 `Upload`；前台头像上传 | `mac_annex`、`mac_user` | `mac_annex` 整行（5 列，附表 A 的 T 表）；`mac_user`：`user_portrait` | `upload/`、`extra/maccms.php`（upload 节） |
| D-18 | FR-9 | 数据库工具 | 后台 `Database`（9 行） | `SHOW TABLE STATUS`（库内 26 张表，即附表 A）、`SHOW COLUMNS FROM <表>`（<表> ∈ 附表 A 的 26 张表）、`SHOW CREATE TABLE <表>`（同）、导出备份时对 26 张表执行 `SELECT *` | `rep`（批量替换）：对表 T 的列 F 执行且只执行一条 `UPDATE T SET F=REPLACE(F,:findstr,:tostr) WHERE 1=1`；允许集合 = T ∈ 附表 A 的 26 张表、F ∈ 附表 A 中 T 的任一列（旧版校验 F ∈ `SHOW COLUMNS FROM T`，**不限列类型**；v3 写的“文本列”不准确，v4 更正）；还原备份（`import`）、执行 SQL（`sql`）、优化（`optimize`）、修复（`repair`）：**会改结构或做表维护，见 OP-6**；判定见 D.3 DYN-7 | `application/data/backup/` 目录下的备份文件（写入与删除） |
| D-19 | FR-6 | 静态生成 | 后台 `Make`；定时任务 `make` | 各内容表 + `mac_type` | 各内容表的 `<前缀>_time_make` 列：`mac_vod`：`vod_time_make`；`mac_art`：`art_time_make`；`mac_topic`：`topic_time_make`；`mac_actor`：`actor_time_make`；`mac_role`：`role_time_make`；`mac_website`：`website_time_make` | 静态页输出目录、`extra/timming.php` |
| D-20 | FR-7 | 模板管理、广告 | 后台 `Template` | 无 | 无 | `template/`、`ads/` 目录文件 |
| D-21 | FR-12 | 系统配置、域名、播放器/下载器/服务器配置、定时任务、站长推送、安全 | 后台 `System`/`Domain`/`Vodplayer`/`Voddowner`/`Vodserver`/`Timming`/`Urlsend`/`Safety`/`Update` | `System`：读 `mac_group`（会员组下拉）；`Urlsend`：读 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website` | 无 | `application/extra/` 目录下现有的 12 个文件：`addons.php`、`bind.php`、`captcha.php`、`domain.php`、`maccms.php`、`queue.php`、`quickmenu.php`、`timming.php`、`version.php`、`voddowner.php`、`vodplayer.php`、`vodserver.php`（`var_export` 格式，BR-2；清单见 D.3 DYN-8） |
| D-22 | FR-12 | 插件 | 后台 `Addon`；`addons/` 目录下各插件目录 | 无固定表 | 插件自带 SQL 可建表（旧 `think\addons\Service`），**按 BR-1 不执行，见 OP-8** | `addons/<插件名>/info.ini`、`addons/<插件名>/config.php`、`extra/addons.php` |
| D-23 | FR-10 | 安装向导 | `install` 控制器 | 校验 26 张表存在 | 初始数据：`mac_admin`（首个管理员，整行）；`install.sql` 内 `INSERT`：`mac_group` 3 行、`initdata.sql`：`mac_type` 18 行；建库建表 DDL **不执行，见 OP-7** | `application/database.php`、`extra/maccms.php`、`data/install/install.lock` |

## D.1 发现“装不下”的位置（供 OP- 使用）

| 位置 | 旧版行为 | 为什么与 BR-1 冲突 | 当前处理 |
| --- | --- | --- | --- |
| `admin/Database.php` 还原备份、执行 SQL、优化表、修复表 | 执行备份文件或任意 SQL；`OPTIMIZE/REPAIR TABLE` | 备份含 `DROP/CREATE TABLE`；任意 SQL 可含 DDL | OP-6 |
| `install/Index.php` `mkDatabase` | 建库建表 | DDL | OP-7 |
| `think\addons\Service` 导入插件 `install.sql` | 插件建表 | DDL | OP-8 |
| `admin/Vod.php:103-105`、`Art.php:51-53`、`Website.php:50-52` | 查重时 `DROP/CREATE TABLE `mac_tmpvod`、`mac_tmpart`、`mac_tmpwebsite`` | DDL | Java 已用子查询替代（D-B13-7），不需要决定；列入 AC-15 的 SNAP-2 守护 |
| `common/util/Database.php:118-119` | 备份文件里写 `DROP TABLE IF EXISTS` 文本 | 只是导出文本，不执行；还原时才执行 | 随 OP-6 |

## D.2 命名列集合（逐列展开，供 D-02、D-05 和 D.3 引用）

| 集合 | 所属表 | 包含的列（逐列） | 来源（PHP） |
| --- | --- | --- | --- |
| TS-TAG | 11 张表 | `mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search` | `common/taglib/Maccms.php` 各标签调用的 `common/model/*` 查询 |
| CS-TYPE-TPL | `mac_type` | `type_tpl`、`type_tpl_list`、`type_tpl_detail`、`type_tpl_play`、`type_tpl_down`（v3 写法 `type_tpl*` 的展开结果，共 5 列） | `Type` 模型取分类模板 |
| CS-HITS-vod | `mac_vod` | `vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_time_hits` | `index/controller/Ajax.php` hits |
| CS-HITS-art | `mac_art` | `art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_time_hits` | `index/controller/Ajax.php` hits |
| CS-HITS-topic | `mac_topic` | `topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_time_hits` | `index/controller/Ajax.php` hits |
| CS-HITS-actor | `mac_actor` | `actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_time_hits` | `index/controller/Ajax.php` hits |
| CS-HITS-role | `mac_role` | `role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_time_hits` | `index/controller/Ajax.php` hits |
| CS-HITS-website | `mac_website` | `website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_time_hits` | `index/controller/Ajax.php` hits |
| CS-DIGG-vod | `mac_vod` | `vod_up`、`vod_down` | Ajax digg |
| CS-DIGG-art | `mac_art` | `art_up`、`art_down` | Ajax digg |
| CS-DIGG-topic | `mac_topic` | `topic_up`、`topic_down` | Ajax digg |
| CS-DIGG-actor | `mac_actor` | `actor_up`、`actor_down` | Ajax digg |
| CS-DIGG-role | `mac_role` | `role_up`、`role_down` | Ajax digg |
| CS-DIGG-website | `mac_website` | `website_up`、`website_down` | Ajax digg |
| CS-DIGG-comment | `mac_comment` | `comment_up`、`comment_down` | Ajax digg |
| CS-SCORE-vod | `mac_vod` | `vod_score`、`vod_score_num`、`vod_score_all` | Ajax score |
| CS-SCORE-art | `mac_art` | `art_score`、`art_score_num`、`art_score_all` | Ajax score |
| CS-SCORE-topic | `mac_topic` | `topic_score`、`topic_score_num`、`topic_score_all` | Ajax score |
| CS-SCORE-actor | `mac_actor` | `actor_score`、`actor_score_num`、`actor_score_all` | Ajax score |
| CS-SCORE-role | `mac_role` | `role_score`、`role_score_num`、`role_score_all` | Ajax score |
| CS-SCORE-website | `mac_website` | `website_score`、`website_score_num`、`website_score_all` | Ajax score |
| CS-SUGGEST-vod | `mac_vod` | `vod_id`、`vod_name`、`vod_en`、`vod_pic`（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-art | `mac_art` | `art_id`、`art_name`、`art_en`、`art_pic`（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-topic | `mac_topic` | `topic_id`、`topic_name`、`topic_en`、`topic_pic`（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-actor | `mac_actor` | `actor_id`、`actor_name`、`actor_en`、`actor_pic`（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-role | `mac_role` | `role_id`、`role_name`、`role_en`、`role_pic`（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-website | `mac_website` | `website_id`、`website_name`、`website_en`、`website_pic`（v3 写法 `*_id/*_name/*_en/*_pic` 的展开结果） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-DATA-EX-vod | `mac_vod` | `vod_time_hits`、`vod_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-art | `mac_art` | `art_time_hits`、`art_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-topic | `mac_topic` | `topic_time_hits`、`topic_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-actor | `mac_actor` | `actor_time_hits`、`actor_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-role | `mac_role` | `role_time_hits`、`role_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-website | `mac_website` | `website_time_hits`、`website_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-VOD-HIDE | `mac_vod` | `vod_play_from`、`vod_play_server`、`vod_play_note`、`vod_play_url`、`vod_down_from`、`vod_down_server`、`vod_down_note`、`vod_down_url`（非会员时不返回；v3 只写了 `vod_down_*`，源码实际 `unset` 的是播放 4 列加下载 4 列，v4 更正） | `Ajax.php` data 第 60-61 行 |
| CS-PWD-VOD | `mac_vod` | `vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`（v3 写法 `vod_pwd*` 的展开结果） | `Ajax.php` pwd |
| CS-PWD-ART | `mac_art` | `art_pwd`、`art_pwd_url`（v3 写法 `art_pwd*` 的展开结果） | `Ajax.php` pwd |
| CS-SORT-vod | `mac_vod` | `vod_id`、`vod_time`、`vod_time_add`、`vod_score`、`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_up`、`vod_down`、`vod_level` | `common/model/Vod.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-art | `mac_art` | `art_id`、`art_time`、`art_time_add`、`art_score`、`art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_up`、`art_down`、`art_level` | `common/model/Art.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-topic | `mac_topic` | `topic_id`、`topic_time`、`topic_time_add`、`topic_score`、`topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_up`、`topic_down`、`topic_level` | `common/model/Topic.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-role | `mac_role` | `role_id`、`role_time`、`role_time_add`、`role_score`、`role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_up`、`role_down`、`role_level` | `common/model/Role.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-actor | `mac_actor` | `actor_id`、`actor_time`、`actor_time_add`、`actor_score`、`actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_up`、`actor_down`、`actor_level` | `common/model/Actor.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-website | `mac_website` | `website_id`、`website_time`、`website_time_add`、`website_score`、`website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_up`、`website_down`、`website_level`、`website_referer`、`website_referer_day`、`website_referer_week`、`website_referer_month` | `common/model/Website.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-comment | `mac_comment` | `comment_id`、`comment_time`、`comment_up`、`comment_down` | `common/model/Comment.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-gbook | `mac_gbook` | `gbook_id`、`gbook_time`、`gbook_reply_time` | `common/model/Gbook.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-type | `mac_type` | `type_id`、`type_sort` | `common/model/Type.php` 的 `in_array($by, [...])` 白名单 |
| CS-SORT-link | `mac_link` | `link_id`、`link_sort` | `common/model/Link.php` 的 `in_array($by, [...])` 白名单 |
| CS-EXTRA | 文件 | `application/extra/` 下现有 12 个文件：`addons.php`、`bind.php`、`captcha.php`、`domain.php`、`maccms.php`、`queue.php`、`quickmenu.php`、`timming.php`、`version.php`、`voddowner.php`、`vodplayer.php`、`vodserver.php` | `ls php/application/extra` |

**v3 → v4 通配/开放表达展开对照**

| v3 写法 | 所在行 | v4 处理 |
| --- | --- | --- |
| `type_tpl*` | D-02 | D-02 的 `type` / `vod` 读取改为 `mac_type` 整行（20 列）；其中模板列 5 列见 CS-TYPE-TPL |
| `*_id/*_name/*_en/*_pic` | D-05 suggest | CS-SUGGEST-<表>（6 张表各 4 列） |
| `*_status=1` | D-05 data | 六张表各自的 `<前缀>_status`，已在 D-05 逐列写出 |
| `*_time_hits`、`*_time_make` | D-05 data | CS-DATA-EX-<表>（6 张表各 2 列） |
| `vod_down_*` | D-05 data | CS-VOD-HIDE（8 列，含播放 4 列，更正 v3 漏项） |
| `vod_pwd*`、`art_pwd*` | D-05 pwd | CS-PWD-VOD（6 列）、CS-PWD-ART（2 列） |
| `*_time_make` | D-19 | 改为逐表逐列（D-19 已写出 6 列） |
| “由模板里的标签决定” | D-01 | TS-TAG 11 张表 + DYN-1 越界判定 |
| “可按 A 中 vod 任意列筛选/排序” | D-02 | 筛选列 = `mac_vod` 的 A 中列（82 列整行）；排序列限 CS-SORT-vod（DYN-2） |
| “按规则 `SHOW COLUMNS` 映射，限 vod/art/actor/role/website 对应的列” | D-15 | `mid`=1 → `mac_vod`，`mid`=2 → `mac_art`，只有这两张表（源码 `Cj.php` 第 121-124 行、`content_into`），DYN-6 |
| “列限于 A 中该表的文本列” | D-18 | 更正为 A 中该表的任一列（源码只校验列在 `SHOW COLUMNS` 中），DYN-7 |
| `extra/connect`、`extra/pay`、`extra/rewrite` | D-07a、D-07e、D-01 | 这三个文件不存在，对应配置在 `extra/maccms.php` 的 `connect`、`pay`、`rewrite` 节，已更正 |
| “`application/extra/*.php` 等” | D-21 | CS-EXTRA 12 个文件，DYN-8 |

## D.3 动态场景：允许集合、来源规则、越界判定

| 编号 | 对应 D 行 | 动态场景 | 允许集合（逐项） | 来源规则 | 越界判定 |
| --- | --- | --- | --- | --- | --- |
| DYN-1 | D-01 | 前台页面由模板里的标签/页面方法取数 | 只读 TS-TAG 的 11 张表（D.2）；不写任何表 | 页面所在模板里出现的 `{maccms:xxx}` 标签（D-02）和 D-01 列出的页面方法 | 读取 TS-TAG 以外的 15 张表（`mac_admin`、`mac_annex`、`mac_card`、`mac_cash`、`mac_cj_content`、`mac_cj_history`、`mac_cj_node`、`mac_collect`、`mac_group`、`mac_msg`、`mac_order`、`mac_plog`、`mac_ulog`、`mac_user`、`mac_visit`）中任一张，或写任何表 = 越界 |
| DYN-2 | D-02 | 标签的数据表、筛选与排序 | ①每个标签可读的表：`vod`：`mac_vod`、`mac_type`、`mac_vod_search`；`art`：`mac_art`、`mac_type`；`topic`：`mac_topic`、`mac_vod`、`mac_art`；`actor`：`mac_actor`；`role`：`mac_role`、`mac_vod`；`website`：`mac_website`；`comment`：`mac_comment`，加 `mac_user` 的 `user_id`、`user_portrait`；`gbook`：`mac_gbook`，加 `mac_user` 的 `user_id`、`user_portrait`；`type`：`mac_type`；`link`：`mac_link`；②排序列（`by` 属性）只能取 D.2 的 CS-SORT-<表>：vod：`vod_id`、`vod_time`、`vod_time_add`、`vod_score`、`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_up`、`vod_down`、`vod_level`；art：`art_id`、`art_time`、`art_time_add`、`art_score`、`art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_up`、`art_down`、`art_level`；topic：`topic_id`、`topic_time`、`topic_time_add`、`topic_score`、`topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_up`、`topic_down`、`topic_level`；role：`role_id`、`role_time`、`role_time_add`、`role_score`、`role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_up`、`role_down`、`role_level`；actor：`actor_id`、`actor_time`、`actor_time_add`、`actor_score`、`actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_up`、`actor_down`、`actor_level`；website：`website_id`、`website_time`、`website_time_add`、`website_score`、`website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_up`、`website_down`、`website_level`、`website_referer`、`website_referer_day`、`website_referer_week`、`website_referer_month`；comment：`comment_id`、`comment_time`、`comment_up`、`comment_down`；gbook：`gbook_id`、`gbook_time`、`gbook_reply_time`；type：`type_id`、`type_sort`；link：`link_id`、`link_sort`；`by="rnd"` 为随机排序，不指定列；其他取值回落为该表的默认列（`<前缀>_time`；`type` 与 `link` 为 `<前缀>_id`）；③筛选条件只能作用于该标签自己表的附表 A 中的列 | 源码：`common/model/{Vod,Art,Topic,Actor,Role,Website,Comment,Gbook,Type,Link}.php` 的 `in_array($by, [...])` 白名单。白名单中还有 `in`（Actor、Website），对应 `actor_in`、`website_in`，这两列不在附表 A，不属于允许集合，Java 侧不得为此新增列 | 标签读取上列以外的表、用 JOIN/子查询读到其他表、排序列不在 CS-SORT 中、或写任何表 = 越界 |
| DYN-3 | D-05 | Ajax 的 data / suggest / pwd / hits / digg / score / referer | ①`data`：D-05 读取栏的 6 张内容表，返回列 = 附表 A 全列减去该表的 CS-DATA-EX，`vod` 在非会员时再减 CS-VOD-HIDE；②`suggest`：只返回 CS-SUGGEST-<表> 的 4 列，筛选列限该表的 `<前缀>_name`、`<前缀>_en`；③`pwd`：只读 CS-PWD-VOD、CS-PWD-ART；④写：`hits`→CS-HITS，`digg`→CS-DIGG，`score`→CS-SCORE，`referer`→D-05 写入栏 5 列；`mid`→表：1→vod、2→art、3→topic、4→comment（仅 digg）、8→actor、9→role、11→website | `Ajax.php` 的 `$field` 与 `unset` 行（见 D.2 来源）；mid 取值以 D-05 写入栏标注的 mid 为准 | 返回了 CS-DATA-EX / CS-VOD-HIDE 中的列、suggest 返回 4 列以外的列、写入栏以外的列、或 `mid` 不在上述映射内仍写库 = 越界 |
| DYN-4 | D-11、D-12、D-13 | 后台内容/分类/会员的 field、batch、move、del | 写入限定在各行写入栏的表：`D-11`→六张内容表（整行）+`mac_vod_search`；`D-12`→`mac_type` 整行，`move` 只写 `mac_vod`、`mac_art` 的 `type_id`、`type_id_1`；`D-13`→13 张表整行及行内指定列；`batch`/`field` 只改本行写入栏表的列 | D-11～D-13 各行的写入栏 | 写入栏以外的表、DDL（含 `mac_tmpvod`、`mac_tmpart`、`mac_tmpwebsite` 临时表）= 越界 |
| DYN-5 | D-14 | 采集类型决定入库目标表 | `vod`→`mac_vod`+`mac_vod_search`；`art`→`mac_art`；`actor`→`mac_actor`；`role`→`mac_role`；`website`→`mac_website`；`comment`→`mac_comment`；以上写“整行”；另写 `mac_collect` 整行；远程图片落盘写 `mac_annex`（仅 OP-5 放开后） | `admin/controller/Collect.php` 的 vod/art/actor/role/website 方法与 `common/model/Collect.php` 的 `vod_data`、`art_data`、`actor_data`、`role_data`、`website_data`、`comment_data` | 写以上 8 张表以外的表、`mac_topic` 入库、DDL = 越界 |
| DYN-6 | D-15 | 自定义规则采集发布的目标表 | 节点 `mid`=1 → 只写 `mac_vod`（整行）+`mac_vod_search`；节点 `mid`=2 → 只写 `mac_art`（整行）；规则库 `mac_cj_node`、`mac_cj_content`（`nodeid`、`status`、`url`、`title`、`data`）、`mac_cj_history`（`md5`）按 D-15；`SHOW COLUMNS FROM` 只对 `mac_vod`/`mac_art` 执行 | `Cj.php` 第 121-124 行（`mid`=2 取 art，否则 vod）与 `content_into`（第 424-428 行经 `Collect` 模型入库） | 发布写入 `mac_actor`、`mac_role`、`mac_website`、`mac_topic`，或对其他表执行 `SHOW COLUMNS`，或 DDL = 越界 |
| DYN-7 | D-18 | 数据库工具的表与列 | `SHOW TABLE STATUS`/`SHOW COLUMNS`/`SHOW CREATE TABLE`/`SELECT *`：表 ∈ 附表 A 的 26 张；`rep`：表 T ∈ 附表 A 的 26 张，列 F ∈ 附表 A 中 T 的任一列，语句形态唯一为 `UPDATE T SET F=REPLACE(F,?,?) WHERE 1=1`；`import`、`sql`、`optimize`、`repair`：本期允许集合为空，见 OP-6（OP-6 决定前一律不执行） | `admin/controller/Database.php`：`isValidTable`（第 325-333 行，取自 `SHOW TABLE STATUS`）、`rep`（第 287-316 行，列取自 `SHOW COLUMNS`） | T 不在 26 张表内、F 不在 T 的附表 A 列内、`rep` 带其他 WHERE/SET、执行任何 DDL 或 `OPTIMIZE`/`REPAIR` = 越界 |
| DYN-8 | D-21、D-09、D-01、D-07a、D-07e | 配置文件读写 | 只能读写 CS-EXTRA 的 12 个文件；`maccms.php` 内共 22 个顶层节：`db`、`site`、`app`、`user`、`gbook`、`comment`、`upload`、`interface`、`pay`、`collect`、`api`、`connect`、`weixin`、`view`、`path`、`rewrite`、`email`、`play`、`sms`、`extra`、`seo`、`urlsend`；这些节都在同一个文件里，不是独立文件 | `ls php/application/extra`；节名见 `extra/maccms.php` | 新增 CS-EXTRA 以外的配置文件，或把配置写进数据库表 = 越界（配置不入库，BR-2） |
| DYN-9 | D-22、D-23 | 插件与安装向导 | D-22：不执行插件自带 SQL，允许集合为空，见 OP-8；D-23：只写 `mac_admin`（首个管理员，整行）、`mac_group` 3 行、`mac_type` 18 行（均为 INSERT），建库建表允许集合为空，见 OP-7 | D-22、D-23 行及 D.1 | 插件或安装向导里出现任何 DDL，或写以上三表以外的表 = 越界 |

## D.4 附表 C 登记范围 → 功能行（覆盖核对）

| 端 | 控制器文件 | C 行数 | 所属 D 编号 |
| --- | --- | --- | --- |
| index | `Actor.php` | 9 | D-04 |
| index | `Ajax.php` | 10 | D-05 |
| index | `Art.php` | 9 | D-04 |
| index | `Base.php` | 1 | D-01 |
| index | `Comment.php` | 5 | D-06 |
| index | `Gbook.php` | 4 | D-06 |
| index | `Help.php` | 1 | D-01 |
| index | `Index.php` | 1 | D-01 |
| index | `Label.php` | 1 | D-01 |
| index | `Map.php` | 1 | D-01 |
| index | `MyError.php` | 1 | D-01 |
| index | `Note.php` | 1 | D-01 |
| index | `Payment.php` | 1 | D-07e |
| index | `Plot.php` | 6 | D-04 |
| index | `Qrcode.php` | 1 | D-01 |
| index | `Role.php` | 8 | D-04 |
| index | `Rss.php` | 7 | D-01 |
| index | `Search.php` | 1 | D-01 |
| index | `Topic.php` | 6 | D-04 |
| index | `User.php` | 42 | D-07a（16 行）、D-07b（2 行）、D-07c（4 行）、D-07d（10 行）、D-07e（10 行） |
| index | `Verify.php` | 2 | D-01 |
| index | `Vod.php` | 16 | D-03 |
| index | `Website.php` | 9 | D-04 |
| api | `Base.php` | 1 | D-09 |
| api | `Index.php` | 1 | D-09 |
| api | `Provide.php` | 10 | D-08 |
| api | `Receive.php` | 7 | D-08 |
| api | `Timming.php` | 1 | D-09 |
| api | `Wechat.php` | 1 | D-09 |
| admin | `Actor.php` | 4 | D-11 |
| admin | `Addon.php` | 10 | D-22 |
| admin | `Admin.php` | 4 | D-13 |
| admin | `Annex.php` | 6 | D-13 |
| admin | `Art.php` | 6 | D-11 |
| admin | `Base.php` | 2 | D-10 |
| admin | `Card.php` | 3 | D-13 |
| admin | `Cash.php` | 3 | D-13 |
| admin | `Cj.php` | 14 | D-15 |
| admin | `Collect.php` | 15 | D-14 |
| admin | `Comment.php` | 4 | D-13 |
| admin | `Database.php` | 9 | D-18 |
| admin | `Domain.php` | 4 | D-21 |
| admin | `Gbook.php` | 4 | D-13 |
| admin | `Group.php` | 4 | D-13 |
| admin | `Images.php` | 4 | D-16 |
| admin | `Index.php` | 11 | D-10 |
| admin | `Link.php` | 4 | D-13 |
| admin | `Make.php` | 10 | D-19 |
| admin | `Order.php` | 2 | D-13 |
| admin | `Plog.php` | 2 | D-13 |
| admin | `Role.php` | 4 | D-11 |
| admin | `Safety.php` | 1 | D-21 |
| admin | `System.php` | 17 | D-21 |
| admin | `Template.php` | 5 | D-20 |
| admin | `Timming.php` | 4 | D-21 |
| admin | `Topic.php` | 4 | D-11 |
| admin | `Type.php` | 7 | D-12 |
| admin | `Ulog.php` | 2 | D-13 |
| admin | `Update.php` | 1 | D-21 |
| admin | `Upload.php` | 3 | D-17 |
| admin | `Urlsend.php` | 3 | D-21 |
| admin | `User.php` | 5 | D-13 |
| admin | `Visit.php` | 2 | D-13 |
| admin | `Vod.php` | 7 | D-11 |
| admin | `Voddowner.php` | 4 | D-21 |
| admin | `Vodplayer.php` | 6 | D-21 |
| admin | `Vodserver.php` | 4 | D-21 |
| admin | `Website.php` | 6 | D-11 |
| install | `Index.php` | 5 | D-23 |

| D 编号 | 覆盖的 C 行数 |
| --- | --- |
| D-01 | 18 |
| D-03 | 16 |
| D-04 | 47 |
| D-05 | 10 |
| D-06 | 9 |
| D-07a | 16 |
| D-07b | 2 |
| D-07c | 4 |
| D-07d | 10 |
| D-07e | 11 |
| D-08 | 17 |
| D-09 | 4 |
| D-10 | 13 |
| D-11 | 31 |
| D-12 | 7 |
| D-13 | 45 |
| D-14 | 15 |
| D-15 | 14 |
| D-16 | 4 |
| D-17 | 3 |
| D-18 | 9 |
| D-19 | 10 |
| D-20 | 5 |
| D-21 | 44 |
| D-22 | 10 |
| D-23 | 5 |
| 合计 | 379 |

核对结论：C 的 379 行（376 个待判定行 + 3 个“旧版无功能”预填行）全部落在除 D-02 以外的 26 个功能行（D-01、D-03～D-06、D-07a～D-07e、D-08～D-23）中，没有未归属的行；D-02 没有独立 C 行（模板标签，由 FR-3 验收）。上表由脚本从附表 C 机械生成，附表 C 改动后必须重新核对。
