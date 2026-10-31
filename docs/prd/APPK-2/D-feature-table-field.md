# APPK-2 附表 D：功能 → 现有表 → 字段 逐项清单

> 属于 `docs/prd/APPK-2.md`（v7）。冲突时以主文件为准。**v7 只改了 D-18、D-23、DYN-7、DYN-9、D.1 与写法约定第 4 条，使其与 OP-6、OP-7 的确认口径一致（见主文件修订记录 v7）；v6 只改了 D-01 的“文件与配置”一栏里模板目录的写法（不再假设 `template/default/html/`，见 OP-15），其余内容自 v5 起未改动。**表名/列名全部取自附表 A（脚本生成并逐列校验，写错列名会使生成失败）。v5 起：①评论/留言标签不读 `mac_user`（头像只读库外文件，见 D.2 CS-AVATAR）；②配置文件固定为 D.2 CS-CONFIG 的 13 个文件（含 `route.php`）；③DYN-2、DYN-3、DYN-7 里的 `（表名）`、`（前缀）`、`SHOW COLUMNS FROM （表名）` 等占位全部换成具体表名和列名，并新增 D.5（26 张表逐列锁定）、D.6（19 个标签逐项映射 TAGMAP-1～TAGMAP-19）、D.7（Ajax data 返回列 RET-1～RET-7）；④对照源码修正 v4 的两处事实：标签读取表集合 TS-TAG 由 11 张改为 12 张（增加 `mac_group`），`mac_vod_search` 在搜索优化开启时会被前台关键词查询写入。v4 起：本表不再使用通配写法和开放表达（只在“v3 → v4 展开对照”表里引用 v3 的旧写法）；动态场景在 D.3 逐个给出允许集合、来源规则和越界判定；D.2 给出命名的列集合。**D.1～D.7 与 D-01～D-23 同属一个锁定版本（BR-10）。**

**写法约定**：
1. “整行”= 该表附表 A 中的全部列（括号里写列数，列名以附表 A 为准）。
2. “指定列”= 本表或 D.2 命名集合里逐列列出的列，不得推广到同表其他列。
3. 读取栏只写表（“整行”）的，表示 `SELECT` 可取该表任意列；某行单独写了“不返回/只读”的列限制时，以该限制为准。
4. 所有写入只做 `INSERT/UPDATE/DELETE`，不含 DDL（BR-1）；**唯一例外**：D-23 在空库且文件 SHA-256 等于仓库原版 `install.sql` 时整文件执行该文件（DYN-9）；D-18 的 `optimize`/`repair` 是表维护语句，不改结构（DYN-7）。
   路径写法：D 表里的 `extra/x.php` 都指 `application/extra/x.php`，`route.php` 指 `application/route.php`；文件与配置栏的取值范围见 D.2 CS-CONFIG；D.2 的 CS-AVATAR（头像文件，只读存在性判断）同时适用于渲染模板的前台行 D-01、D-02、D-03、D-04、D-06 和登录行 D-07a。
5. 指定列取自 PHP 源码该写入点附近出现的该表字段并集（宁多勿少），可由 `php/application` 下对应控制器/模型的更新语句复核；未逐语句运行验证。

**越界判定（唯一规则，AC-16 引用）**：对设计单或实现里的每一个读写项（表、列、文件/配置），在其所属功能行（D-01～D-23）依次判：①该表是否列在该行的读取栏或写入栏（方向要对：读项必须在读取栏，写项必须在写入栏）；②该列是否被该行列出（“整行”按 D.5 对应表的全部列），或该行对此表写的是“整行”；③若该行标注“判定见 DYN-n”，还必须满足 D.3 中 DYN-n 的允许集合（DYN-n 引用的 D.6、D.7、D.5 逐项映射同样是允许集合）；④文件/配置项是否列在该行的“文件与配置”栏，且配置文件还必须属于 DYN-8 的 CS-CONFIG 13 个文件。任一为“否”= **越界**，按 BR-8/RISK-8 停下并新增 OP-，不得自行扩大。

**覆盖范围**：附表 C 登记簿 `index`、`api`、`admin`、`install` 共 379 行，每一行在 D.4 都映射到唯一的一个 D 编号（脚本核对：379 行全部映射，无遗漏、无重复）；D-02（标签库）、D-17 中的“前台头像上传”没有独立的 C 行，由 FR-3 / AC-3 与 D-07b 的 C 行验收。

| 编号 | 对应 FR | 功能 | 入口（旧控制器方法） | 读取的表与字段 | 写入的表与字段 | 文件与配置（不在库里） |
| --- | --- | --- | --- | --- | --- | --- |
| D-01 | FR-5 | 首页、地图、RSS、帮助、公告、搜索聚合、自定义标签页、验证码页、二维码、404 页 | 前台 `Index`/`Map`/`Rss`/`Help`/`Note`/`Search`/`Label`/`Verify`/`Qrcode`/`MyError`/`Base._empty` 的全部方法（附表 C 中对应的 18 行） | 读取限于 **TS-TAG**（D.2）列出的 12 张表：`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search`、`mac_group`；读取方式 = 页面模板里的 `{maccms:xxx}` 标签（逐标签的读取表、排序列见 D.6 TAGMAP-1～TAGMAP-19，判定见 DYN-1、DYN-2），或本行页面自带的数据查询（`Map`/`Rss`/`Search` 读 `mac_vod`、`mac_art`，均在 TS-TAG 内）。TS-TAG 以外的 14 张表一律不得读取，其中整张 `mac_user`（包括 `user_id`、`user_portrait` 在内的全部 30 列）都不得读取，判定见 DYN-1 | `mac_vod_search`：搜索优化开启时 INSERT 整行（8 列）、UPDATE `search_hit_count`、`search_last_hit_time`（`vod` 标签和 `Search` 页带关键词类参数 `wd`、`name`、`tag`、`class`、`actor`、`director` 时，源码 `common/model/VodSearch.php` `getResultIdList` 第 37-60 行）；除此之外不写任何表 | 模板包目录（含其下 `label/` 目录里的 `.html` 文件；目录名取自 `extra/maccms.php` `site` 节的 `template_dir` 键、`html_dir` 键、`mob_template_dir` 键、`mob_html_dir` 键、`ads_dir` 键；仓库 `php/` 的基线值分别为 `default`、`html`，v6 模板包是附表 E 的 `default_pc`、`default_wap`，这些键怎么取值见主文件 OP-15，本表不假设）、`application/extra/maccms.php`（`site` 节的 `site_` 开头配置键、`app` 节的 `page_404`）、`extra/captcha.php`、`extra/maccms.php` 的 `rewrite` 节、`application/route.php`（只读；框架启动时加载路由规则，写入方是 D-21 的 `System`）、D.2 CS-AVATAR 的头像文件（只读）；验证码与会话在库外 |
| D-02 | FR-3 | Maccms 标签库 19 个标签 | 模板中 `{maccms:xxx}` | 逐标签的读取表、读取列、排序列、筛选列见 D.6 TAGMAP-1～TAGMAP-19，下面是同一结论的汇总：`vod`：`mac_vod` 整行（82 列）、`mac_type` 整行（20 列，分类缓存）、`mac_group` 整行（10 列，会员组缓存）、`mac_vod_search` 整行（8 列，搜索优化开启时）；`art`：`mac_art` 整行（46 列）、`mac_type`、`mac_group`；`topic`：`mac_topic` 整行（37 列），`topic_rel_vod`/`topic_rel_art` 指向的 `mac_vod`/`mac_art` 整行，以及这两张内容表带出的 `mac_type`、`mac_group`；`actor`：`mac_actor` 整行（42 列）、`mac_type`；`role`：`mac_role` 整行（29 列），`role_rid` 关联的 `mac_vod` 整行，以及 `mac_type`、`mac_group`；`website`：`mac_website` 整行（42 列）、`mac_type`、`mac_group`；`comment`：`mac_comment` 整行（14 列，含按 `comment_pid` 读取的子评论，同一张表），再按评论的 `comment_mid` 读目标内容 1→`mac_vod`、2→`mac_art`、3→`mac_topic`、8→`mac_actor`、9→`mac_role`、11→`mac_website`（各整行，`common/model/Comment.php` 第 56-92 行），目标内容带出的 `mac_type`、`mac_group`；**不读 `mac_user` 的任何列**，返回里的 `user_id` 是 `mac_comment.user_id` 本表列，`user_portrait` 不是库里的列，由库外头像文件判断得出（D.2 CS-AVATAR）；`gbook`：`mac_gbook` 整行（10 列），`user_id` 是 `mac_gbook.user_id` 本表列，`user_portrait` 同 `comment`，**不读 `mac_user`**；`type`：`mac_type` 整行；`link`：`mac_link` 整行（8 列）；`area`、`lang`、`class`、`year`、`version`、`state`：取值清单来自 `extra/maccms.php` `app` 节的 `vod_extend_area`、`vod_extend_lang`、`vod_extend_class`、`vod_extend_year`、`vod_extend_version`、`vod_extend_state`；标签带分类编号（`tid`）大于 0 时，改读 `mac_type` 的分类缓存（整行，实际取用其中的 `type_extend` 列，`common/model/Extend.php` 第 116-121 行等）；`letter`：不读表、不读配置（固定 A～Z 与 0-9）；`foreach`、`for`：不读表；每个标签的**排序列**只能取 D.6 对应行“排序”栏逐项列出的列；**筛选条件**只作用于该标签自己的表（评论标签含其目标内容表）在 D.5 里的列，不得引入其他表 | `mac_vod_search`：搜索优化开启时 INSERT 整行（8 列）、UPDATE `search_hit_count`、`search_last_hit_time`（仅 `vod` 标签带关键词类参数时，判定见 DYN-1）；其他表不写（`cachetime` 只写库外缓存文件） | `extra/maccms.php`、`runtime/cache`（库外）、D.2 CS-AVATAR 的头像文件（只读，`comment`、`gbook` 标签使用） |
| D-03 | FR-5 | 视频前台页（分类、筛选、搜索、详情、播放、下载、观看密码、版权、角色、剧情、RSS、ajax 片段） | 前台 `Vod` 全部方法 | `mac_vod`、`mac_type`、`mac_group`、`mac_user`、`mac_ulog`、`mac_role`、`mac_vod_search`；`group_popedom` 判定权限，`ulog` 判定是否已购买；`vod_pwd/vod_pwd_url/vod_pwd_play/vod_pwd_play_url/vod_pwd_down/vod_pwd_down_url` 观看密码；`vod_points/vod_points_play/vod_points_down` 积分 | 本类方法不直接写库；计数写入见 D-05，扣积分见 D-07；页面模板里的 `{maccms:xxx}` 标签读取按 DYN-1，其中 `mac_vod_search` 的写入同 D-01 | `extra/vodplayer.php`、`vodserver.php`、`voddowner.php`、`static/js/playerconfig.js`（只读）、D.2 CS-AVATAR 的头像文件（只读） |
| D-04 | FR-5 | 文章、演员、角色、专题、网址、剧情前台页 | 前台 `Art`/`Actor`/`Role`/`Topic`/`Website`/`Plot` 全部方法 | `mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website`、`mac_type`、`mac_group`、`mac_user`、`mac_ulog`、`mac_vod`（专题/角色/剧情页按关联读 `mac_vod`、`mac_art`） | 不直接写库；计数写入见 D-05；页面模板里的 `{maccms:xxx}` 标签读取按 DYN-1，其中 `mac_vod_search` 的写入同 D-01 | `extra/maccms.php`、D.2 CS-AVATAR 的头像文件（只读） |
| D-05 | FR-5 | 前台计数与互动 ajax（`Ajax`） | `Ajax.php` 的 data/suggest/hits/referer/digg/score/pwd/verify_check | `data`：`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website` 的整行，**但**不返回 D.2 的 CS-DATA-EX-vod～CS-DATA-EX-website（每表 2 列），且 `vod` 对非会员再不返回 CS-VOD-HIDE 的 8 列；每张表实际返回的逐列清单见 D.7 RET-1～RET-7；筛选限 `vod_status`、`art_status`、`topic_status`、`actor_status`、`role_status`、`website_status` 各自等于 1；`suggest`（mid 1,2,3,8,9,11 对应表同 DYN-3）：只读各表下面这 4 列（对应 D.2 的 CS-SUGGEST-vod、CS-SUGGEST-art、CS-SUGGEST-topic、CS-SUGGEST-actor、CS-SUGGEST-role、CS-SUGGEST-website）：`mac_vod`：`vod_id`、`vod_name`、`vod_en`、`vod_pic`；`mac_art`：`art_id`、`art_name`、`art_en`、`art_pic`；`mac_topic`：`topic_id`、`topic_name`、`topic_en`、`topic_pic`；`mac_actor`：`actor_id`、`actor_name`、`actor_en`、`actor_pic`；`mac_role`：`role_id`、`role_name`、`role_en`、`role_pic`；`mac_website`：`website_id`、`website_name`、`website_en`、`website_pic`；`hits`/`score`/`digg`：读被计数行的主键（`vod_id`、`art_id`、`topic_id`、`actor_id`、`role_id`、`website_id`、`comment_id` 中与 mid 对应的一个）与下面写入栏逐列列出的被更新列（即 D.2 的 CS-HITS-vod～CS-HITS-website、CS-DIGG-vod～CS-DIGG-comment、CS-SCORE-vod～CS-SCORE-website）；`referer`：`mac_website`：`website_jumpurl`；`pwd`：`mac_vod`：`vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`（D.2 CS-PWD-VOD）；`mac_art`：`art_pwd`、`art_pwd_url`（D.2 CS-PWD-ART） | `hits`（mid 1,2,3,8,9,11）：`mac_vod`：`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_time_hits`；`mac_art`：`art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_time_hits`；`mac_topic`：`topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_time_hits`；`mac_actor`：`actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_time_hits`；`mac_role`：`role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_time_hits`；`mac_website`：`website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_time_hits`；`digg`（mid 1,2,3,4,8,9,11）：`mac_vod`：`vod_up`、`vod_down`；`mac_art`：`art_up`、`art_down`；`mac_topic`：`topic_up`、`topic_down`；`mac_actor`：`actor_up`、`actor_down`；`mac_role`：`role_up`、`role_down`；`mac_website`：`website_up`、`website_down`；`mac_comment`：`comment_up`、`comment_down`；`score`（mid 1,2,3,8,9,11）：`mac_vod`：`vod_score`、`vod_score_num`、`vod_score_all`；`mac_art`：`art_score`、`art_score_num`、`art_score_all`；`mac_topic`：`topic_score`、`topic_score_num`、`topic_score_all`；`mac_actor`：`actor_score`、`actor_score_num`、`actor_score_all`；`mac_role`：`role_score`、`role_score_num`、`role_score_all`；`mac_website`：`website_score`、`website_score_num`、`website_score_all`；`referer`：`mac_website`：`website_referer`、`website_referer_day`、`website_referer_week`、`website_referer_month`、`website_time_referer` | 验证码校验、已投票/已评分 cookie 在库外 |
| D-06 | FR-5 | 评论、留言（前台） | 前台 `Comment`、`Gbook` | `mac_comment`、`mac_gbook`、`mac_user`、`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`（评论目标存在性） | `comment`：`mac_comment` 整行（14 列，附表 A 的 T 表）（新增）、`mac_comment`：`comment_report`（举报 +1）、`mac_comment`：`comment_up`、`comment_down`；`gbook`：`mac_gbook` 整行（10 列，附表 A 的 T 表）（新增） | `extra/maccms.php`（评论/留言开关、审核、间隔）、验证码、D.2 CS-AVATAR 的头像文件（只读） |
| D-07a | FR-5 | 会员注册、登录、退出、找回密码、绑定 | 前台 `User` 的 index/login/logout/reg/regcheck/reg_msg/oauth/logincallback/bindmsg/bind/unbind/findpass/findpass_msg/findpass_reset/ajax_login/ajax_info（附表 C 中 index/User.php 的这 16 行） | `mac_user`、`mac_group`、`mac_msg` | `mac_user`：新增注册时整行（30 列）；登录更新 `user_login_num`、`user_login_ip`、`user_login_time`、`user_last_login_ip`、`user_last_login_time`、`user_random`；找回/改密 `user_pwd`；绑定 `user_openid_qq`、`user_openid_weixin`；`mac_msg` 整行（8 列，附表 A 的 T 表）（短信/邮件验证码记录）；邀请注册加分 `user_points`（见 D-07d） | `extra/maccms.php`（user 节）、`extra/captcha.php`、`extra/maccms.php` 的 `connect` 节（第三方登录）、D.2 CS-AVATAR 的头像文件（登录成功时 `common/model/User.php` 第 390 行用其写 cookie，只读存在性）；登录态在库外 |
| D-07b | FR-5 | 会员资料、头像、密保 | 前台 `User` 的 info/portrait（附表 C 中 index/User.php 的这 2 行） | `mac_user` | `mac_user`：`user_nick_name`、`user_email`、`user_phone`、`user_portrait`、`user_question`、`user_answer`、`user_pwd`；头像文件另写 `mac_annex`（见 D-17） | `upload/user/` 下 `0`～`9` 十个子目录里的头像 `.jpg`（D.2 CS-AVATAR，写；`common/model/Upload.php` 第 187-206 行同时更新 `mac_user.user_portrait`） |
| D-07c | FR-5 | 购买播放/下载权限、会员升级、积分 | 前台 `User` 的 ajax_buy_popedom/buy/upgrade/popedom（附表 C 中 index/User.php 的这 4 行） | `mac_user`、`mac_group`、`mac_ulog`、`mac_plog`、`mac_vod`、`mac_art` | `mac_user`：`user_points`、`user_end_time`、`group_id`；`mac_ulog` 整行（9 列，附表 A 的 T 表）（购买记录）；`mac_plog` 整行（7 列，附表 A 的 T 表）（积分流水） | — |
| D-07d | FR-5 | 收藏、播放记录、下载记录、积分记录、分销 | 前台 `User` 的 favs/plays/downs/ulog/ajax_ulog/ulog_del/plog/plog_del/reward/visit（附表 C 中 index/User.php 的这 10 行） | `mac_ulog`、`mac_plog`、`mac_user`、`mac_vod`、`mac_art`、`mac_actor`、`mac_topic`、`mac_role`、`mac_website` | `mac_ulog` 整行（9 列，附表 A 的 T 表）；`mac_plog` 整行（7 列，附表 A 的 T 表）（读写）；`mac_user`：`user_points`、`user_pid`、`user_pid_2`、`user_pid_3`；`mac_visit` 整行（5 列，附表 A 的 T 表）（邀请访问记录，邀请访问加分 `user_points`） | `extra/maccms.php`（分销比例、积分规则） |
| D-07e | FR-5 | 在线支付、订单、充值卡、提现、我的评论/留言 | 前台 `User` 的 pay/gopay/qrcode/orders/order_info/cards/cash/cash_del/comment/gbook（index/User.php 的这 10 行）；`Payment.notify`（1 行） | `mac_order`、`mac_card`、`mac_cash`、`mac_user`、`mac_plog`、`mac_comment`、`mac_gbook` | `mac_order` 整行（10 列，附表 A 的 T 表）（下单）；`mac_order`：`order_status`、`order_pay_time`、`order_pay_type`（支付回调）；`mac_card`：`card_points`、`card_sale_status`、`card_use_status`、`card_use_time`、`user_id`；`mac_cash` 整行（10 列，附表 A 的 T 表）（申请提现）；`mac_user`：`user_points`、`user_points_froze`；`mac_plog` 整行（7 列，附表 A 的 T 表）（积分流水） | `extra/maccms.php` 的 `pay` 节（支付通道配置） |
| D-08 | FR-12 | 开放接口（`api` 控制器） | `Provide`（vod/art/actor/role/website/comment 列表与详情，采集协议 XML/JSON）、`Receive`（外部入库） | Provide：`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_type`（只读） | Receive：`mac_vod` 整行（82 列，附表 A 的 T 表）；`mac_art` 整行（46 列，附表 A 的 T 表）；`mac_actor` 整行（42 列，附表 A 的 T 表）；`mac_role` 整行（29 列，附表 A 的 T 表）；`mac_website` 整行（42 列，附表 A 的 T 表）；`mac_comment` 整行（14 列，附表 A 的 T 表）；`mac_vod_search`（搜索优化开启时）；其余控制器不直接写库 | `extra/maccms.php`（api 节、接口密码）、`extra/bind.php` |
| D-09 | FR-12 | 定时任务入口、公众号、接口首页 | API `Timming`、`Wechat`、`Index` | 无（定时任务调用的生成/采集功能所读写的表见 D-14、D-19） | 无 | `extra/timming.php`、`extra/maccms.php`（weixin 节） |
| D-10 | FR-12 | 后台登录、首页、欢迎页、缓存清理、快捷菜单 | 后台 `Index`、`Base` | `mac_admin`、`mac_type`、`mac_vod_search` | `mac_admin`：`admin_login_num`、`admin_login_ip`、`admin_login_time`、`admin_last_login_ip`、`admin_last_login_time`、`admin_random`；缓存清理时删除 `mac_vod_search` 的过期行（DML `DELETE`） | `runtime/` 缓存目录、`extra/quickmenu.php`、`static/js/playerconfig.js`（`admin/controller/Base.php` 第 102 行写） |
| D-11 | FR-12 | 内容管理：视频、文章、演员、角色、专题、网址 | 后台 `Vod`/`Art`/`Actor`/`Role`/`Topic`/`Website` 的 data/info/del/batch/field/updateToday（`Vod` 另有 `iplot`） | 各自内容表（`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website` 整行）+ `mac_type`、`mac_group`、`mac_vod` | 各自内容表整行：`mac_vod` 整行（82 列，附表 A 的 T 表）；`mac_art` 整行（46 列，附表 A 的 T 表）；`mac_topic` 整行（37 列，附表 A 的 T 表）；`mac_actor` 整行（42 列，附表 A 的 T 表）；`mac_role` 整行（29 列，附表 A 的 T 表）；`mac_website` 整行（42 列，附表 A 的 T 表）；`mac_vod_search`（同步）；重复数据查询：**旧版用 `CREATE TABLE mac_tmpvod/tmpart/tmpwebsite`（DDL）**，Java 已改子查询（`docs/architecture/22-b13-admin-content.md` D-B13-7），本期不得回到 DDL（BR-1） | `upload/` 图片文件 |
| D-12 | FR-12 | 分类管理 | 后台 `Type` 的 index/info/del/field/batch/extend/move | `mac_type`、`mac_vod`、`mac_art`、`mac_actor`、`mac_website` | `mac_type` 整行（20 列，附表 A 的 T 表）；`move`：`mac_vod`、`mac_art` 的 `type_id`、`type_id_1`（`mac_vod`：`type_id`、`type_id_1`；`mac_art`：`type_id`、`type_id_1`） | `extra/maccms.php` 的筛选项配置 |
| D-13 | FR-12 | 会员与交易类后台 | 后台 `User`/`Group`/`Card`/`Cash`/`Order`/`Plog`/`Ulog`/`Comment`/`Gbook`/`Link`/`Visit`/`Admin`/`Annex` | `mac_user`、`mac_group`、`mac_card`、`mac_cash`、`mac_order`、`mac_plog`、`mac_ulog`、`mac_comment`、`mac_gbook`、`mac_link`、`mac_visit`、`mac_admin`、`mac_annex` | 各自表整行：`mac_user` 整行（30 列，附表 A 的 T 表）；`mac_group` 整行（10 列，附表 A 的 T 表）；`mac_card` 整行（10 列，附表 A 的 T 表）；`mac_cash` 整行（10 列，附表 A 的 T 表）；`mac_order` 整行（10 列，附表 A 的 T 表）；`mac_plog` 整行（7 列，附表 A 的 T 表）；`mac_ulog` 整行（9 列，附表 A 的 T 表）；`mac_comment` 整行（14 列，附表 A 的 T 表）；`mac_gbook` 整行（10 列，附表 A 的 T 表）；`mac_link` 整行（8 列，附表 A 的 T 表）；`mac_visit` 整行（5 列，附表 A 的 T 表）；`mac_admin` 整行（11 列，附表 A 的 T 表）；`mac_annex` 整行（5 列，附表 A 的 T 表）；`Cash.audit`：`mac_cash`：`cash_status`、`cash_time_audit`、`mac_user`：`user_points_froze`；`User.reward`：`mac_user`：`user_points` + `mac_plog` 新增；`System` 保存会员组配置时读取 `mac_group` | `extra/maccms.php` |
| D-14 | FR-11/FR-12 | 资源站采集（本地版本与在线版本） | 后台 `Collect`（含 `union/load/api/bind/clearbind/vod/art/actor/role/website` 与 `timing`），API `Receive` | `mac_collect`、`mac_type`、`mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` | `mac_collect` 整行（12 列）；入库写 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment` 整行（各表列数见 D-08）；`mac_vod_search`（搜索优化开启时）；远程图片落盘时 `mac_annex` 整行（仅 OP-5 放开后）；入库目标表只能是上述 6 张内容/评论表，采集类型与表的对应见 D.3 DYN-5 | `extra/bind.php`、`extra/timming.php`；本地版本只读本地文件源 |
| D-15 | FR-11/FR-12 | 自定义规则采集 | 后台 `Cj`（14 行） | `mac_cj_node`、`mac_cj_content`、`mac_cj_history`、`mac_collect`（均整行）；`content_into`（发布）查重与分类映射读 `mac_vod`（节点 `mid`=1）或 `mac_art`（`mid`=2）、`mac_type`；`program` 页执行 `SHOW COLUMNS FROM mac_vod`（`mid`=1）或 `mac_art`（`mid`=2）取字段名清单（只读结构信息，不改结构） | `mac_cj_node` 整行（31 列，新增/修改/导入）；`mac_cj_node`：`lastdate`（采集后更新）；`mac_cj_content`：`nodeid`、`status`、`url`、`title`、`data`；`mac_cj_history`：`md5`；**发布（`content_into`）只写**：节点 `mid`=1 时 `mac_vod` 整行（82 列）+ `mac_vod_search`（搜索优化开启时），`mid`=2 时 `mac_art` 整行（46 列）；不写 actor/role/website/topic。判定见 D.3 DYN-6 | 规则导入导出文件 |
| D-16 | FR-12 | 图片同步（远程图片下载） | 后台 `Images` 的 data/opt/del/sync | `mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website` | 各内容表的图片/正文列：`mac_vod`：`vod_pic`、`vod_content`；`mac_art`：`art_pic`、`art_content`；`mac_topic`：`topic_pic`、`topic_content`；`mac_actor`：`actor_pic`、`actor_content`；`mac_role`：`role_pic`、`role_content`；`mac_website`：`website_pic`、`website_content`；`mac_annex` 整行 | `upload/`（仅 OP-5 放开后可下载远程图） |
| D-17 | FR-12 | 上传与附件 | 后台 `Upload`；前台头像上传 | `mac_annex`、`mac_user` | `mac_annex` 整行（5 列，附表 A 的 T 表）；`mac_user`：`user_portrait` | `upload/`（后台上传目录）、`upload/user/` 下 `0`～`9` 十个子目录（前台头像，D.2 CS-AVATAR）、`extra/maccms.php`（upload 节） |
| D-18 | FR-9 | 数据库工具 | 后台 `Database`（9 行） | `SHOW TABLE STATUS`（库内 26 张表，即附表 A）、`SHOW COLUMNS FROM` 与 `SHOW CREATE TABLE`：对 D.5 LK-01～LK-26 的 26 张表各执行一次（表名逐个列在 D.5 与 DYN-7）、导出备份时对这 26 张表执行 `SELECT *` | `rep`（批量替换）：对 D.5 中某一行的表和该行“列”栏里的某一列（二者组成 (表, 列) 对，共 503 个）执行且只执行一条形如 `UPDATE 表名 SET 列名=REPLACE(列名,:findstr,:tostr) WHERE 1=1` 的语句（表名、列名取该 (表, 列) 对；旧版校验该列属于 `SHOW COLUMNS` 的结果，**不限列类型**；v3 写的“文本列”不准确，v4 更正）；还原备份（`import`，只执行数据语句）、执行 SQL（`sql`，只放行 `SELECT/INSERT/UPDATE/DELETE`）、优化（`optimize`）、修复（`repair`）（对 D.5 的 26 张表执行 `OPTIMIZE TABLE`/`REPAIR TABLE`，不改结构）：**OP-6 已确认**；判定见 D.3 DYN-7 | `application/data/backup/` 目录下的备份文件（写入与删除） |
| D-19 | FR-6 | 静态生成 | 后台 `Make`；定时任务 `make` | 各内容表 + `mac_type` | 六张内容表各自的 time_make 列（逐表列出）：`mac_vod`：`vod_time_make`；`mac_art`：`art_time_make`；`mac_topic`：`topic_time_make`；`mac_actor`：`actor_time_make`；`mac_role`：`role_time_make`；`mac_website`：`website_time_make` | 静态页输出目录、`extra/timming.php` |
| D-20 | FR-7 | 模板管理、广告 | 后台 `Template` | 无 | 无 | `template/`、`ads/` 目录文件 |
| D-21 | FR-12 | 系统配置、域名、播放器/下载器/服务器配置、定时任务、站长推送、安全 | 后台 `System`/`Domain`/`Vodplayer`/`Voddowner`/`Vodserver`/`Timming`/`Urlsend`/`Safety`/`Update` | `System`：读 `mac_group`（会员组下拉）；`Urlsend`：读 `mac_vod`、`mac_art`、`mac_actor`、`mac_role`、`mac_topic`、`mac_website` | 无 | `application/extra/` 目录下现有的 12 个文件：`addons.php`、`bind.php`、`captcha.php`、`domain.php`、`maccms.php`、`queue.php`、`quickmenu.php`、`timming.php`、`version.php`、`voddowner.php`、`vodplayer.php`、`vodserver.php`（`var_export` 格式，BR-2）、`application/route.php`（`System` 保存 `rewrite` 配置时由 `admin/controller/System.php` 第 231 行写出）；以上 13 个文件即 D.2 CS-CONFIG，判定见 DYN-8；另写 `static/js/tj.js`（`System.php` 第 124 行）、`static/js/playerconfig.js`（`System.php` 第 683 行）、`static/player/` 目录下各播放器的 `.js`（`Vodplayer` 保存时写） |
| D-22 | FR-12 | 插件 | 后台 `Addon`；`addons/` 目录下各插件目录 | 无固定表 | 插件自带 SQL 可建表（旧 `think\addons\Service`），**按 BR-1 不执行，见 OP-8** | `addons/adminloginbg/` 目录（基线里唯一的插件目录：`info.ini`、`config.php`、`Adminloginbg.php`、`controller/Index.php`）、`extra/addons.php`；安装、卸载其他插件时对 `addons/` 的读写见 OP-8（OP-8 决定前不执行） |
| D-23 | FR-10 | 安装向导 | `install` 控制器 | 校验 26 张表存在 | 初始数据：`mac_admin`（首个管理员，整行）；`install.sql` 内 `INSERT`：`mac_group` 3 行、`initdata.sql`：`mac_type` 18 行；建库建表：**唯一允许的 DDL 是原版 `install.sql` 整文件**（仅当目标库是空库且文件 SHA-256 等于仓库原版 `php/application/install/sql/install.sql` 时执行；已有 `mac_*` 表或哈希不一致时不执行），除此之外的 DDL 一律不执行，见 OP-7、DYN-9 | `application/database.php`（写，`install/controller/Index.php` 第 435 行）、`extra/maccms.php`（写，同文件第 215 行）、`application/data/install/install.lock`（写，同文件第 266 行） |

## D.1 发现“装不下”的位置（供 OP- 使用）

| 位置 | 旧版行为 | 为什么与 BR-1 冲突 | 当前处理 |
| --- | --- | --- | --- |
| `admin/Database.php` 还原备份、执行 SQL、优化表、修复表 | 执行备份文件或任意 SQL；`OPTIMIZE/REPAIR TABLE` | 备份含 `DROP/CREATE TABLE`；任意 SQL 可含 DDL | OP-6 已确认：`sql` 只放行 `SELECT/INSERT/UPDATE/DELETE`；`import` 只执行数据语句；`optimize`/`repair` 照 PHP 执行并由 SNAP 证明结构不变（DYN-7） |
| `install/Index.php` `mkDatabase` | 建库建表 | DDL | OP-7 已确认：仅空库 + 文件 SHA-256 等于仓库原版 `install.sql` 时执行该文件，其余 DDL 不执行（DYN-9） |
| `think\addons\Service` 导入插件 `install.sql` | 插件建表 | DDL | OP-8 |
| `admin/Vod.php:103-105`、`Art.php:51-53`、`Website.php:50-52` | 查重时 `DROP/CREATE TABLE `mac_tmpvod`、`mac_tmpart`、`mac_tmpwebsite`` | DDL | Java 已用子查询替代（D-B13-7），不需要决定；列入 AC-15 的 SNAP-2 守护 |
| `common/util/Database.php:118-119` | 备份文件里写 `DROP TABLE IF EXISTS` 文本 | 只是导出文本，不执行；还原时才执行 | 随 OP-6 |

## D.2 命名列集合（逐列展开，供 D-02、D-05 和 D.3 引用）

| 集合 | 所属表 | 包含的列（逐列） | 来源（PHP） |
| --- | --- | --- | --- |
| TS-TAG | 12 张表（v4 写 11 张，漏了 `mac_group`） | `mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search`、`mac_group`（`mac_group` 来自 `Vod`、`Art`、`Website` 模型 `listData` 的会员组缓存，`common/model/Vod.php` 第 53 行等） | `common/taglib/Maccms.php` 各标签调用的 `common/model/*` 查询 |
| CS-TYPE-TPL | `mac_type` | `type_tpl`、`type_tpl_list`、`type_tpl_detail`、`type_tpl_play`、`type_tpl_down`（展开对照见“v3 → v4 展开对照”表） | `Type` 模型取分类模板 |
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
| CS-SUGGEST-vod | `mac_vod` | `vod_id`、`vod_name`、`vod_en`、`vod_pic`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-art | `mac_art` | `art_id`、`art_name`、`art_en`、`art_pic`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-topic | `mac_topic` | `topic_id`、`topic_name`、`topic_en`、`topic_pic`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-actor | `mac_actor` | `actor_id`、`actor_name`、`actor_en`、`actor_pic`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-role | `mac_role` | `role_id`、`role_name`、`role_en`、`role_pic`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-SUGGEST-website | `mac_website` | `website_id`、`website_name`、`website_en`、`website_pic`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` suggest 第 109 行 `$field` |
| CS-DATA-EX-vod | `mac_vod` | `vod_time_hits`、`vod_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-art | `mac_art` | `art_time_hits`、`art_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-topic | `mac_topic` | `topic_time_hits`、`topic_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-actor | `mac_actor` | `actor_time_hits`、`actor_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-role | `mac_role` | `role_time_hits`、`role_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-DATA-EX-website | `mac_website` | `website_time_hits`、`website_time_make` | `Ajax.php` data 第 56 行 `unset` |
| CS-VOD-HIDE | `mac_vod` | `vod_play_from`、`vod_play_server`、`vod_play_note`、`vod_play_url`、`vod_down_from`、`vod_down_server`、`vod_down_note`、`vod_down_url`（非会员时不返回；v3 只写了下载 4 列，源码实际 `unset` 的是播放 4 列加下载 4 列，v4 更正） | `Ajax.php` data 第 60-61 行 |
| CS-PWD-VOD | `mac_vod` | `vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` pwd |
| CS-PWD-ART | `mac_art` | `art_pwd`、`art_pwd_url`（展开对照见“v3 → v4 展开对照”表） | `Ajax.php` pwd |
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
| CS-CONFIG | 文件，共 13 个 | CS-EXTRA 的 12 个文件加 `application/route.php`（路由规则，由 `System` 保存 `rewrite` 配置时生成，`admin/controller/System.php` 第 231 行；前台由框架加载，只读）。各文件的写入方：`addons.php`：`Addon`（D-22）；本期只读（OP-8 决定前不改）；`bind.php`：`Collect`（D-14）第 194、222 行写；`common/model/Collect.php` 第 205 行读（D-14 采集入库；D-08 的 `Receive` 也经 Collect 模型入库，同样只读）；`captcha.php`：全目录检索 `mac_arr2file` 没有写入点，本期只读；`domain.php`：`Domain`（D-21）第 35、61、109 行写；`maccms.php`：`System`（D-21）、`Urlsend`（D-21）、安装向导（D-23）写；前台、API 读；`queue.php`：没有写入点，本期只读；`quickmenu.php`：`admin/Index`（D-10）第 123 行写；`timming.php`：`admin/Timming`（D-21）、`api/Timming`（D-09）写；`version.php`：没有写入点，本期只读；`voddowner.php`：`Voddowner`（D-21）写；`vodplayer.php`：`Vodplayer`（D-21）写；`vodserver.php`：`Vodserver`（D-21）写；`route.php`：`System`（D-21）写，前台 D-01 读 | 上列各行的源码位置；`ls php/application/extra`、`ls php/application/route.php` |
| CS-AVATAR | 文件 | `upload/user/0/`～`upload/user/9/` 十个子目录里、文件名为“user_id 数值加 `.jpg`”的文件（子目录号 = user_id 除以 10 的余数；user_id 取自被返回的 `mac_comment.user_id` 或 `mac_gbook.user_id`，登录时取自登录用户）；找不到时用 `static/images/touxiang.png`。**只做文件是否存在的判断，不读库** | `common.php` 第 1205-1215 行 `mac_get_user_portrait`（函数体只有 `file_exists`）；调用点：`common/model/Comment.php` 第 43、52 行、`common/model/Gbook.php` 第 33 行、`common/model/User.php` 第 390 行 |

**v3 → v4 通配/开放表达展开对照**

| v3 写法 | 所在行 | v4 处理 |
| --- | --- | --- |
| `type_tpl*` | D-02 | D-02 的 `type` / `vod` 读取改为 `mac_type` 整行（20 列）；其中模板列 5 列见 CS-TYPE-TPL |
| `*_id/*_name/*_en/*_pic` | D-05 suggest | CS-SUGGEST-（表名）（6 张表各 4 列） |
| `*_status=1` | D-05 data | 六张表各自的 `（前缀）_status`，已在 D-05 逐列写出 |
| `*_time_hits`、`*_time_make` | D-05 data | CS-DATA-EX-（表名）（6 张表各 2 列） |
| `vod_down_*` | D-05 data | CS-VOD-HIDE（8 列，含播放 4 列，更正 v3 漏项） |
| `vod_pwd*`、`art_pwd*` | D-05 pwd | CS-PWD-VOD（6 列）、CS-PWD-ART（2 列） |
| `*_time_make` | D-19 | 改为逐表逐列（D-19 已写出 6 列） |
| “由模板里的标签决定” | D-01 | TS-TAG 11 张表 + DYN-1 越界判定 |
| “可按 A 中 vod 任意列筛选/排序” | D-02 | 筛选列 = `mac_vod` 的 A 中列（82 列整行）；排序列限 CS-SORT-vod（DYN-2） |
| “按规则 `SHOW COLUMNS` 映射，限 vod/art/actor/role/website 对应的列” | D-15 | `mid`=1 → `mac_vod`，`mid`=2 → `mac_art`，只有这两张表（源码 `Cj.php` 第 121-124 行、`content_into`），DYN-6 |
| “列限于 A 中该表的文本列” | D-18 | 更正为 A 中该表的任一列（源码只校验列在 `SHOW COLUMNS` 中），DYN-7 |
| `extra/connect`、`extra/pay`、`extra/rewrite` | D-07a、D-07e、D-01 | 这三个文件不存在，对应配置在 `extra/maccms.php` 的 `connect`、`pay`、`rewrite` 节，已更正 |
| “`application/extra/*.php` 等” | D-21 | CS-EXTRA 12 个文件，DYN-8 |

**v4 → v5 修订对照（评审 Q-006-a～d）**

| 编号 | v4 写法 | v5 处理 | 源码依据 |
| --- | --- | --- | --- |
| Q-006-a | D-02、DYN-2 允许 `comment`、`gbook` 标签读 `mac_user` 的 `user_id`、`user_portrait`；DYN-1 又把 `mac_user` 列为越界 | 删除 D-02/DYN-2 对 `mac_user` 的读取；D-01、D-02、DYN-1、DYN-2、D.6 的 TAGMAP-10/11 统一为“标签不读 `mac_user` 的任何列”；`user_id` 是 `mac_comment`/`mac_gbook` 本表列 | `common/model/Comment.php` 第 39-52 行、`Gbook.php` 第 31-34 行：只用查询结果里的 `$v['user_id']` 调函数，没有读 `mac_user` |
| Q-006-b | D-01 的文件栏有 `application/route.php`，DYN-8 只允许 12 个 `extra/*.php` | 新增 D.2 CS-CONFIG（13 个文件 = 12 个 `extra/*.php` + `route.php`），写明每个文件的写入方和来源；D-01 对 `route.php` 只读，D-21 写；DYN-8 改为以 CS-CONFIG 为允许集合 | `admin/controller/System.php` 第 231 行 `mac_arr2file(APP_PATH . 'route.php', $route)` |
| Q-006-c | DYN-2/DYN-3/DYN-7 用 `CS-SORT-（表名）`、`CS-SUGGEST-（表名）`、`（前缀）_*`、`SHOW COLUMNS FROM （表名）`、`UPDATE T SET F` | DYN-2 改引用 D.6（19 个标签逐项）；DYN-3 逐表写出列名并引用 D.7（Ajax data 返回列 RET-1～RET-7）；DYN-7 逐个列出 26 张表并引用 D.5（26 张表共 503 列逐列）；AC-16 改为直接引用这三张表 | 同 v4 的源码行，另见 D.6 各行 |
| Q-006-d | 写“用户头像经 `mac_get_user_portrait` 只读 `mac_user`：`user_id`、`user_portrait`” | 删除；`mac_get_user_portrait` 只做 `file_exists`，头像文件读取放在库外，新增 D.2 CS-AVATAR | `common.php` 第 1205-1215 行 |
| 顺带更正 | D-01 写“只读”“TS-TAG 11 张表”“不写任何表”；D-02 写 `area`/`lang` 等标签“不读表” | TS-TAG 改为 12 张（加 `mac_group`）；`mac_vod_search` 搜索优化开启时被 INSERT/UPDATE（D-01、D-02、D-03、D-04、DYN-1）；`area`、`lang`、`class`、`year`、`version`、`state` 带 `tid` 时读 `mac_type` 的 `type_extend`；D-10、D-21 补写入的 `static/js` 文件，D-22 把按插件名的占位写法换成基线里的 `addons/adminloginbg/`，D-01 的模板目录写法由两个尖括号占位换成基线值 `default`、`html` | `common/model/Vod.php` 第 53 行、`VodSearch.php` 第 37-60 行、`Extend.php` 第 116-121 行等、`admin/controller/Base.php` 第 102 行、`System.php` 第 124、683 行、`php/addons/` 目录 |

## D.3 动态场景：允许集合、来源规则、越界判定

| 编号 | 对应 D 行 | 动态场景 | 允许集合（逐项） | 来源规则 | 越界判定 |
| --- | --- | --- | --- | --- | --- |
| DYN-1 | D-01、D-03、D-04、D-06 | 前台页面由模板标签/页面方法取数 | ①D-01：整行只读 TS-TAG 的 12 张表（D.2）：`mac_vod`、`mac_art`、`mac_topic`、`mac_actor`、`mac_role`、`mac_website`、`mac_comment`、`mac_gbook`、`mac_type`、`mac_link`、`mac_vod_search`、`mac_group`；②D-03、D-04、D-06：控制器自己的读写按各行读取栏、写入栏判定，页面模板里的 `{maccms:xxx}` 标签读取按 D-01 的 12 张表和 D.6 逐项判定；③全部四行共同的唯一写入：`mac_vod_search`：搜索优化开启时 INSERT 整行（8 列）、UPDATE `search_hit_count`、`search_last_hit_time`（`vod` 标签和 `Search` 页带关键词类参数 `wd`、`name`、`tag`、`class`、`actor`、`director` 时，源码 `common/model/VodSearch.php` `getResultIdList` 第 37-60 行）；不写任何其他表，也不写 `mac_vod_search` 的其他列、不 DELETE | 页面所在模板里出现的 `{maccms:xxx}` 标签（D-02、D.6）和 D-01 列出的页面方法；`mac_vod_search` 的写入来自 `common/model/VodSearch.php` 第 37-60 行 | 读取 TS-TAG 以外的 14 张表（`mac_admin`、`mac_annex`、`mac_card`、`mac_cash`、`mac_cj_content`、`mac_cj_history`、`mac_cj_node`、`mac_collect`、`mac_msg`、`mac_order`、`mac_plog`、`mac_ulog`、`mac_user`、`mac_visit`）中任一张的任一列（`mac_user` 的 `user_id`、`user_portrait` 也在内）；写 `mac_vod_search` 以外的表；对 `mac_vod_search` 写 INSERT 整行、UPDATE `search_hit_count`、`search_last_hit_time` 以外的内容或 DELETE；文件与配置超出该行文件栏或 DYN-8 的 CS-CONFIG（见 DYN-8）= 越界 |
| DYN-2 | D-02 | 标签的数据表、筛选与排序 | 逐标签的读取表、读取列、排序列、筛选列、库外读取，一律以 D.6 的 TAGMAP-1（`link`）、TAGMAP-2（`area`）、TAGMAP-3（`lang`）、TAGMAP-4（`year`）、TAGMAP-5（`class`）、TAGMAP-6（`version`）、TAGMAP-7（`state`）、TAGMAP-8（`letter`）、TAGMAP-9（`type`）、TAGMAP-10（`comment`）、TAGMAP-11（`gbook`）、TAGMAP-12（`role`）、TAGMAP-13（`actor`）、TAGMAP-14（`topic`）、TAGMAP-15（`art`）、TAGMAP-16（`vod`）、TAGMAP-17（`website`）、TAGMAP-18（`foreach`）、TAGMAP-19（`for`）各行为准；每行引用的“整行”列集合见 D.5；排序 `by="rnd"` 是随机取行，不指定列 | 源码：`common/model/{Vod,Art,Topic,Actor,Role,Website,Comment,Gbook,Type,Link}.php` 的 `in_array($by, [...])` 白名单；`Extend.php`；`common/taglib/Maccms.php` 的标签属性表。白名单中 `Actor`、`Website` 还有 `in`，对应 `actor_in`、`website_in`，这两列不在附表 A，不属于允许集合，Java 侧不得为此新增列 | 标签读取该标签 TAGMAP 行以外的表或列、用 JOIN/子查询读到其他表、排序列不在该行“排序”栏、读取 `mac_user` 的任何列、或写 `mac_vod_search` 以外的表 = 越界 |
| DYN-3 | D-05 | Ajax 的 data / suggest / pwd / hits / digg / score / referer | ①`data`：对 mid 1→`mac_vod`、2→`mac_art`、3→`mac_topic`、8→`mac_actor`、9→`mac_role`、11→`mac_website`，返回列逐列见 D.7 RET-1（`vod` 会员）、RET-2（`vod` 非会员）、RET-3（`art`）、RET-4（`topic`）、RET-5（`actor`）、RET-6（`role`）、RET-7（`website`）；②`suggest`：mid 1→`mac_vod`：`vod_id`、`vod_name`、`vod_en`、`vod_pic`；2→`mac_art`：`art_id`、`art_name`、`art_en`、`art_pic`；3→`mac_topic`：`topic_id`、`topic_name`、`topic_en`、`topic_pic`；8→`mac_actor`：`actor_id`、`actor_name`、`actor_en`、`actor_pic`；9→`mac_role`：`role_id`、`role_name`、`role_en`、`role_pic`；11→`mac_website`：`website_id`、`website_name`、`website_en`、`website_pic`；筛选列只有下列两列：`vod_name`、`vod_en`；`art_name`、`art_en`；`topic_name`、`topic_en`；`actor_name`、`actor_en`；`role_name`、`role_en`；`website_name`、`website_en`；排序列只有该表主键 `vod_id`、`art_id`、`topic_id`、`actor_id`、`role_id`、`website_id`；③`pwd`（mid 1、2）：`vod`：`vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`；`art`：`art_pwd`、`art_pwd_url`；④写：`hits`→D-05 写入栏 hits 段（D.2 CS-HITS-vod、CS-HITS-art、CS-HITS-topic、CS-HITS-actor、CS-HITS-role、CS-HITS-website），`digg`→D-05 写入栏 digg 段（CS-DIGG-vod、CS-DIGG-art、CS-DIGG-topic、CS-DIGG-actor、CS-DIGG-role、CS-DIGG-website、CS-DIGG-comment），`score`→D-05 写入栏 score 段（CS-SCORE-vod、CS-SCORE-art、CS-SCORE-topic、CS-SCORE-actor、CS-SCORE-role、CS-SCORE-website），`referer`→`mac_website` 的 5 列；写入的 mid→表：`hits`、`score` 为 1→`mac_vod`、2→`mac_art`、3→`mac_topic`、8→`mac_actor`、9→`mac_role`、11→`mac_website`；`digg` 另有 4→`mac_comment` | `Ajax.php` 第 24-38、94-118、155-160、305-311、350-357、402-421 行的 `mid` 校验、`$field` 与 `unset`（`mid`=4 的表名来自 `common.php` `mac_get_mid_code`：4→`comment`） | 返回了 D.7 对应行以外的列（含 CS-DATA-EX-vod、CS-DATA-EX-art、CS-DATA-EX-topic、CS-DATA-EX-actor、CS-DATA-EX-role、CS-DATA-EX-website 的列，非会员时含 CS-VOD-HIDE 的列）、suggest 返回或筛选了上列以外的列、写入栏以外的列、或 `mid` 不在上述映射内仍写库 = 越界 |
| DYN-4 | D-11、D-12、D-13 | 后台内容/分类/会员的 field、batch、move、del | 写入限定在各行写入栏的表：`D-11`→六张内容表（整行）+`mac_vod_search`；`D-12`→`mac_type` 整行，`move` 只写 `mac_vod`、`mac_art` 的 `type_id`、`type_id_1`；`D-13`→13 张表整行及行内指定列；`batch`/`field` 只改本行写入栏表的列 | D-11～D-13 各行的写入栏 | 写入栏以外的表、DDL（含 `mac_tmpvod`、`mac_tmpart`、`mac_tmpwebsite` 临时表）= 越界 |
| DYN-5 | D-14 | 采集类型决定入库目标表 | `vod`→`mac_vod`+`mac_vod_search`；`art`→`mac_art`；`actor`→`mac_actor`；`role`→`mac_role`；`website`→`mac_website`；`comment`→`mac_comment`；以上写“整行”；另写 `mac_collect` 整行；远程图片落盘写 `mac_annex`（仅 OP-5 放开后） | `admin/controller/Collect.php` 的 vod/art/actor/role/website 方法与 `common/model/Collect.php` 的 `vod_data`、`art_data`、`actor_data`、`role_data`、`website_data`、`comment_data` | 写以上 8 张表以外的表、`mac_topic` 入库、DDL = 越界 |
| DYN-6 | D-15 | 自定义规则采集发布的目标表 | 节点 `mid`=1 → 只写 `mac_vod`（整行）+`mac_vod_search`；节点 `mid`=2 → 只写 `mac_art`（整行）；规则库 `mac_cj_node`、`mac_cj_content`（`nodeid`、`status`、`url`、`title`、`data`）、`mac_cj_history`（`md5`）按 D-15；`SHOW COLUMNS FROM` 只对 `mac_vod`/`mac_art` 执行 | `Cj.php` 第 121-124 行（`mid`=2 取 art，否则 vod）与 `content_into`（第 424-428 行经 `Collect` 模型入库） | 发布写入 `mac_actor`、`mac_role`、`mac_website`、`mac_topic`，或对其他表执行 `SHOW COLUMNS`，或 DDL = 越界 |
| DYN-7 | D-18 | 数据库工具的表与列 | `SHOW TABLE STATUS`、`SHOW COLUMNS FROM`、`SHOW CREATE TABLE`、`SELECT *`：表限于 D.5 的 26 张表，即 `mac_actor`、`mac_admin`、`mac_annex`、`mac_art`、`mac_card`、`mac_cash`、`mac_cj_content`、`mac_cj_history`、`mac_cj_node`、`mac_collect`、`mac_comment`、`mac_gbook`、`mac_group`、`mac_link`、`mac_msg`、`mac_order`、`mac_plog`、`mac_role`、`mac_topic`、`mac_type`、`mac_ulog`、`mac_user`、`mac_visit`、`mac_vod`、`mac_vod_search`、`mac_website`；`rep`：(表, 列) 对限于 D.5 各行“列”栏里列出的 503 个对，语句形态唯一为 `UPDATE 表名 SET 列名=REPLACE(列名,?,?) WHERE 1=1`（表名、列名取该 (表, 列) 对）；`sql`：允许集合只有 `SELECT`、`INSERT`、`UPDATE`、`DELETE` 四类语句，其他任何语句（含 `CREATE/ALTER/DROP/RENAME/TRUNCATE`）整条拒绝且不发送给数据库；`import`：允许集合只有备份文件中的数据语句（`INSERT/UPDATE/DELETE`），备份中的 `DROP TABLE/CREATE TABLE` 等结构语句不执行；`optimize`、`repair`：允许集合只有对 D.5 的 26 张表之一执行 `OPTIMIZE TABLE`/`REPAIR TABLE`（按 PHP 行为），执行前后必须由 SNAP-1～SNAP-7 证明结构不变；以上之外的操作一律拒绝（OP-6 已确认） | `admin/controller/Database.php`：`isValidTable`（第 325-333 行，取自 `SHOW TABLE STATUS`）、`rep`（第 287-316 行，列取自 `SHOW COLUMNS`） | 表不在 D.5 的 26 张内、列不在该表在 D.5 的列内、`rep` 带其他 WHERE/SET、`sql`/`import` 执行了允许集合以外的语句、对 D.5 的 26 张表以外的表执行 `OPTIMIZE`/`REPAIR`、`OPTIMIZE`/`REPAIR` 前后 SNAP-1～SNAP-7 有差异 = 越界 |
| DYN-8 | 所有“文件与配置”栏列出 `application/extra/` 下的 .php 文件或 `route.php` 的行（D-01、D-02、D-03、D-04、D-06、D-07a、D-07d、D-07e、D-08、D-09、D-10、D-12、D-13、D-14、D-17、D-19、D-21、D-22、D-23） | 配置文件读写 | 只能读写 D.2 CS-CONFIG 的 13 个文件：`application/extra/` 下的 `addons.php`、`bind.php`、`captcha.php`、`domain.php`、`maccms.php`、`queue.php`、`quickmenu.php`、`timming.php`、`version.php`、`voddowner.php`、`vodplayer.php`、`vodserver.php` 共 12 个，加 `application/route.php`；每个文件的读写方向以 CS-CONFIG 行的“写入方”为准（只读文件不得写）；`maccms.php` 内共 22 个顶层节：`db`、`site`、`app`、`user`、`gbook`、`comment`、`upload`、`interface`、`pay`、`collect`、`api`、`connect`、`weixin`、`view`、`path`、`rewrite`、`email`、`play`、`sms`、`extra`、`seo`、`urlsend`，这些节都在同一个文件里，不是独立文件。本条只判 CS-CONFIG 里的文件；D 行里的其他文件和目录（`template/`、`upload/`、`static/`、`runtime/`、`application/data/backup/`、静态页输出目录、CS-AVATAR）只按越界判定第④步看该行文件栏 | `ls php/application/extra`、`php/application/route.php`；节名见 `extra/maccms.php`；写入点见 CS-CONFIG 行 | 读写 CS-CONFIG 以外的配置文件、写入方向与 CS-CONFIG 行不符（例如写 `captcha.php`）、或把配置写进数据库表 = 越界（配置不入库，BR-2） |
| DYN-9 | D-22、D-23 | 插件与安装向导 | D-22：不执行插件自带 SQL，允许集合为空，见 OP-8；D-23：只写 `mac_admin`（首个管理员，整行）、`mac_group` 3 行、`mac_type` 18 行（均为 INSERT），建库建表允许集合只有一个元素——仓库原版 `php/application/install/sql/install.sql` 整文件，且仅当目标库是空库（无任何 `mac_*` 表）并且待执行文件的 SHA-256 等于该原版文件的 SHA-256 时执行（已有 `mac_*` 表或哈希不一致时不执行；执行结果等于附表 A 基线），其他任何 DDL 允许集合为空，见 OP-7；D-23 写的文件只有 `application/database.php`、`application/extra/maccms.php`、`application/data/install/install.lock` | D-22、D-23 行及 D.1 | 插件里出现任何 DDL，安装向导执行了原版 `install.sql` 以外的 DDL、或在非空库/哈希不一致时执行了 `install.sql`，写以上三表以外的表，或写以上三个文件以外的文件 = 越界 |

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

## D.5 26 张表逐列锁定（供 D-18、DYN-2、DYN-7 和所有“整行”引用）

“整行”= 本表“列”栏全部列，列名和顺序与附表 A 一致（由 `.work/tabs.json` 生成，写错会中止）。

| 编号 | 表 | 列数 | 列（逐列） |
| --- | --- | --- | --- |
| LK-01 | `mac_actor` | 42 | `actor_id`、`type_id`、`type_id_1`、`actor_name`、`actor_en`、`actor_alias`、`actor_status`、`actor_lock`、`actor_letter`、`actor_sex`、`actor_color`、`actor_pic`、`actor_blurb`、`actor_remarks`、`actor_area`、`actor_height`、`actor_weight`、`actor_birthday`、`actor_birtharea`、`actor_blood`、`actor_starsign`、`actor_school`、`actor_works`、`actor_tag`、`actor_class`、`actor_level`、`actor_time`、`actor_time_add`、`actor_time_hits`、`actor_time_make`、`actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_score`、`actor_score_all`、`actor_score_num`、`actor_up`、`actor_down`、`actor_tpl`、`actor_jumpurl`、`actor_content` |
| LK-02 | `mac_admin` | 11 | `admin_id`、`admin_name`、`admin_pwd`、`admin_random`、`admin_status`、`admin_auth`、`admin_login_time`、`admin_login_ip`、`admin_login_num`、`admin_last_login_time`、`admin_last_login_ip` |
| LK-03 | `mac_annex` | 5 | `annex_id`、`annex_time`、`annex_file`、`annex_size`、`annex_type` |
| LK-04 | `mac_art` | 46 | `art_id`、`type_id`、`type_id_1`、`group_id`、`art_name`、`art_sub`、`art_en`、`art_status`、`art_letter`、`art_color`、`art_from`、`art_author`、`art_tag`、`art_class`、`art_pic`、`art_pic_thumb`、`art_pic_slide`、`art_pic_screenshot`、`art_blurb`、`art_remarks`、`art_jumpurl`、`art_tpl`、`art_level`、`art_lock`、`art_points`、`art_points_detail`、`art_up`、`art_down`、`art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_time`、`art_time_add`、`art_time_hits`、`art_time_make`、`art_score`、`art_score_all`、`art_score_num`、`art_rel_art`、`art_rel_vod`、`art_pwd`、`art_pwd_url`、`art_title`、`art_note`、`art_content` |
| LK-05 | `mac_card` | 10 | `card_id`、`card_no`、`card_pwd`、`card_money`、`card_points`、`card_use_status`、`card_sale_status`、`user_id`、`card_add_time`、`card_use_time` |
| LK-06 | `mac_cash` | 10 | `cash_id`、`user_id`、`cash_status`、`cash_points`、`cash_money`、`cash_bank_name`、`cash_bank_no`、`cash_payee_name`、`cash_time`、`cash_time_audit` |
| LK-07 | `mac_cj_content` | 6 | `id`、`nodeid`、`status`、`url`、`title`、`data` |
| LK-08 | `mac_cj_history` | 1 | `md5` |
| LK-09 | `mac_cj_node` | 31 | `nodeid`、`name`、`lastdate`、`sourcecharset`、`sourcetype`、`urlpage`、`pagesize_start`、`pagesize_end`、`page_base`、`par_num`、`url_contain`、`url_except`、`url_start`、`url_end`、`title_rule`、`title_html_rule`、`type_rule`、`type_html_rule`、`content_rule`、`content_html_rule`、`content_page_start`、`content_page_end`、`content_page_rule`、`content_page`、`content_nextpage`、`down_attachment`、`watermark`、`coll_order`、`customize_config`、`program_config`、`mid` |
| LK-10 | `mac_collect` | 12 | `collect_id`、`collect_name`、`collect_url`、`collect_type`、`collect_mid`、`collect_appid`、`collect_appkey`、`collect_param`、`collect_filter`、`collect_filter_from`、`collect_opt`、`collect_sync_pic_opt` |
| LK-11 | `mac_comment` | 14 | `comment_id`、`comment_mid`、`comment_rid`、`comment_pid`、`user_id`、`comment_status`、`comment_name`、`comment_ip`、`comment_time`、`comment_content`、`comment_up`、`comment_down`、`comment_reply`、`comment_report` |
| LK-12 | `mac_gbook` | 10 | `gbook_id`、`gbook_rid`、`user_id`、`gbook_status`、`gbook_name`、`gbook_ip`、`gbook_time`、`gbook_reply_time`、`gbook_content`、`gbook_reply` |
| LK-13 | `mac_group` | 10 | `group_id`、`group_name`、`group_status`、`group_type`、`group_popedom`、`group_points_day`、`group_points_week`、`group_points_month`、`group_points_year`、`group_points_free` |
| LK-14 | `mac_link` | 8 | `link_id`、`link_type`、`link_name`、`link_sort`、`link_add_time`、`link_time`、`link_url`、`link_logo` |
| LK-15 | `mac_msg` | 8 | `msg_id`、`user_id`、`msg_type`、`msg_status`、`msg_to`、`msg_code`、`msg_content`、`msg_time` |
| LK-16 | `mac_order` | 10 | `order_id`、`user_id`、`order_status`、`order_code`、`order_price`、`order_time`、`order_points`、`order_pay_type`、`order_pay_time`、`order_remarks` |
| LK-17 | `mac_plog` | 7 | `plog_id`、`user_id`、`user_id_1`、`plog_type`、`plog_points`、`plog_time`、`plog_remarks` |
| LK-18 | `mac_role` | 29 | `role_id`、`role_rid`、`role_name`、`role_en`、`role_status`、`role_lock`、`role_letter`、`role_color`、`role_actor`、`role_remarks`、`role_pic`、`role_sort`、`role_level`、`role_time`、`role_time_add`、`role_time_hits`、`role_time_make`、`role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_score`、`role_score_all`、`role_score_num`、`role_up`、`role_down`、`role_tpl`、`role_jumpurl`、`role_content` |
| LK-19 | `mac_topic` | 37 | `topic_id`、`topic_name`、`topic_en`、`topic_sub`、`topic_status`、`topic_sort`、`topic_letter`、`topic_color`、`topic_tpl`、`topic_type`、`topic_pic`、`topic_pic_thumb`、`topic_pic_slide`、`topic_key`、`topic_des`、`topic_title`、`topic_blurb`、`topic_remarks`、`topic_level`、`topic_up`、`topic_down`、`topic_score`、`topic_score_all`、`topic_score_num`、`topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_time`、`topic_time_add`、`topic_time_hits`、`topic_time_make`、`topic_tag`、`topic_rel_vod`、`topic_rel_art`、`topic_content`、`topic_extend` |
| LK-20 | `mac_type` | 20 | `type_id`、`type_name`、`type_en`、`type_sort`、`type_mid`、`type_pid`、`type_status`、`type_tpl`、`type_tpl_list`、`type_tpl_detail`、`type_tpl_play`、`type_tpl_down`、`type_key`、`type_des`、`type_title`、`type_union`、`type_extend`、`type_logo`、`type_pic`、`type_jumpurl` |
| LK-21 | `mac_ulog` | 9 | `ulog_id`、`user_id`、`ulog_mid`、`ulog_type`、`ulog_rid`、`ulog_sid`、`ulog_nid`、`ulog_points`、`ulog_time` |
| LK-22 | `mac_user` | 30 | `user_id`、`group_id`、`user_name`、`user_pwd`、`user_nick_name`、`user_qq`、`user_email`、`user_phone`、`user_status`、`user_portrait`、`user_portrait_thumb`、`user_openid_qq`、`user_openid_weixin`、`user_question`、`user_answer`、`user_points`、`user_points_froze`、`user_reg_time`、`user_reg_ip`、`user_login_time`、`user_login_ip`、`user_last_login_time`、`user_last_login_ip`、`user_login_num`、`user_extend`、`user_random`、`user_end_time`、`user_pid`、`user_pid_2`、`user_pid_3` |
| LK-23 | `mac_visit` | 5 | `visit_id`、`user_id`、`visit_ip`、`visit_ly`、`visit_time` |
| LK-24 | `mac_vod` | 82 | `vod_id`、`type_id`、`type_id_1`、`group_id`、`vod_name`、`vod_sub`、`vod_en`、`vod_status`、`vod_letter`、`vod_color`、`vod_tag`、`vod_class`、`vod_pic`、`vod_pic_thumb`、`vod_pic_slide`、`vod_pic_screenshot`、`vod_actor`、`vod_director`、`vod_writer`、`vod_behind`、`vod_blurb`、`vod_remarks`、`vod_pubdate`、`vod_total`、`vod_serial`、`vod_tv`、`vod_weekday`、`vod_area`、`vod_lang`、`vod_year`、`vod_version`、`vod_state`、`vod_author`、`vod_jumpurl`、`vod_tpl`、`vod_tpl_play`、`vod_tpl_down`、`vod_isend`、`vod_lock`、`vod_level`、`vod_copyright`、`vod_points`、`vod_points_play`、`vod_points_down`、`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_duration`、`vod_up`、`vod_down`、`vod_score`、`vod_score_all`、`vod_score_num`、`vod_time`、`vod_time_add`、`vod_time_hits`、`vod_time_make`、`vod_trysee`、`vod_douban_id`、`vod_douban_score`、`vod_reurl`、`vod_rel_vod`、`vod_rel_art`、`vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`、`vod_content`、`vod_play_from`、`vod_play_server`、`vod_play_note`、`vod_play_url`、`vod_down_from`、`vod_down_server`、`vod_down_note`、`vod_down_url`、`vod_plot`、`vod_plot_name`、`vod_plot_detail` |
| LK-25 | `mac_vod_search` | 8 | `search_key`、`search_word`、`search_field`、`search_hit_count`、`search_last_hit_time`、`search_update_time`、`search_result_count`、`search_result_ids` |
| LK-26 | `mac_website` | 42 | `website_id`、`type_id`、`type_id_1`、`website_name`、`website_sub`、`website_en`、`website_status`、`website_letter`、`website_color`、`website_lock`、`website_sort`、`website_jumpurl`、`website_pic`、`website_pic_screenshot`、`website_logo`、`website_area`、`website_lang`、`website_level`、`website_time`、`website_time_add`、`website_time_hits`、`website_time_make`、`website_time_referer`、`website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_score`、`website_score_all`、`website_score_num`、`website_up`、`website_down`、`website_referer`、`website_referer_day`、`website_referer_week`、`website_referer_month`、`website_tag`、`website_class`、`website_remarks`、`website_tpl`、`website_blurb`、`website_content` |

## D.6 19 个标签逐项映射（供 D-02、DYN-1、DYN-2 引用）

数据来源：`common/taglib/Maccms.php` 第 9-27 行的标签表（19 个标签）和 `common/model/` 目录下各模型的 `listData`、`infoData`、`Extend.php`。表中“整行（LK-nn）”按 D.5 的该表全部列。

| 编号 | 标签 | 读取的表（只读，另有说明的除外） | 读取的列 | 排序 | 筛选列 | 库外与配置 |
| --- | --- | --- | --- | --- | --- | --- |
| TAGMAP-1 | `link` | `mac_link` | `mac_link` 整行（LK-14，8 列） | by 取值 id→`link_id`；sort→`link_sort`；其他取值一律回落为 `link_id` | `mac_link`（LK-14）的列；属性 `type` 对应 `link_type` | 无 |
| TAGMAP-2 | `area` | 无表；`tid` 大于 0 时读 `mac_type` | `tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `area` 项，经分类缓存读整行 LK-20） | 无 `by` 属性，`order` 只决定正序、倒序 | 无（不筛选表） | `extra/maccms.php` 的 `app` 节 `vod_extend_area` |
| TAGMAP-3 | `lang` | 无表；`tid` 大于 0 时读 `mac_type` | `tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `lang` 项，经分类缓存读整行 LK-20） | 无 `by` 属性，`order` 只决定正序、倒序 | 无（不筛选表） | `extra/maccms.php` 的 `app` 节 `vod_extend_lang` |
| TAGMAP-4 | `year` | 无表；`tid` 大于 0 时读 `mac_type` | `tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `year` 项，经分类缓存读整行 LK-20） | 无 `by` 属性，`order` 只决定正序、倒序 | 无（不筛选表） | `extra/maccms.php` 的 `app` 节 `vod_extend_year` |
| TAGMAP-5 | `class` | 无表；`tid` 大于 0 时读 `mac_type` | `tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `class` 项，经分类缓存读整行 LK-20） | 无 `by` 属性，`order` 只决定正序、倒序 | 无（不筛选表） | `extra/maccms.php` 的 `app` 节 `vod_extend_class` |
| TAGMAP-6 | `version` | 无表；`tid` 大于 0 时读 `mac_type` | `tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `version` 项，经分类缓存读整行 LK-20） | 无 `by` 属性，`order` 只决定正序、倒序 | 无（不筛选表） | `extra/maccms.php` 的 `app` 节 `vod_extend_version` |
| TAGMAP-7 | `state` | 无表；`tid` 大于 0 时读 `mac_type` | `tid` 等于 0 时：不读列；`tid` 大于 0 时：`mac_type` 的 `type_extend`（取其中 `state` 项，经分类缓存读整行 LK-20） | 无 `by` 属性，`order` 只决定正序、倒序 | 无（不筛选表） | `extra/maccms.php` 的 `app` 节 `vod_extend_state` |
| TAGMAP-8 | `letter` | 无 | 无 | 无 `by` 属性 | 无 | 无配置；清单固定为 A～Z 与 0-9 |
| TAGMAP-9 | `type` | `mac_type` | `mac_type` 整行（LK-20，20 列） | by 取值 id→`type_id`；sort→`type_sort`；其他取值一律回落为 `type_id` | `mac_type`（LK-20）的列；属性 `mid` 对应 `type_mid` | 无 |
| TAGMAP-10 | `comment` | `mac_comment`（含子评论）；目标内容 `comment_mid`=1→`mac_vod`；`comment_mid`=2→`mac_art`；`comment_mid`=3→`mac_topic`；`comment_mid`=8→`mac_actor`；`comment_mid`=9→`mac_role`；`comment_mid`=11→`mac_website`；以及目标内容带出的 `mac_type`、`mac_group` | `mac_comment` 整行（LK-11，14 列）；目标内容各表整行（`mac_vod` LK-24、`mac_art` LK-04、`mac_topic` LK-19、`mac_actor` LK-01、`mac_role` LK-18、`mac_website` LK-26）；`mac_type` 整行（LK-20，20 列）；`mac_group` 整行（LK-13，10 列）。**不读 `mac_user` 的任何列** | by 取值 id→`comment_id`；time→`comment_time`；up→`comment_up`；down→`comment_down`；其他取值一律回落为 `comment_time` | `mac_comment`（LK-11）的列；属性 `rid`、`pid`、`mid`、`uid` 依次对应 `comment_rid`、`comment_pid`、`comment_mid`、`user_id`；固定条件 `comment_status`=1，未给 `pid` 时再加 `comment_pid`=0（`common/model/Comment.php` 第 164-177 行） | D.2 CS-AVATAR：返回项 `user_portrait` 来自头像文件是否存在，不是库里的列 |
| TAGMAP-11 | `gbook` | `mac_gbook` | `mac_gbook` 整行（LK-12，10 列）。**不读 `mac_user` 的任何列** | by 取值 id→`gbook_id`；time→`gbook_time`；reply_time→`gbook_reply_time`；其他取值一律回落为 `gbook_time` | `mac_gbook`（LK-12）的列；属性 `rid`、`uid` 依次对应 `gbook_rid`、`user_id` | D.2 CS-AVATAR：同 `comment` |
| TAGMAP-12 | `role` | `mac_role`；`role_rid` 关联的 `mac_vod`；`mac_vod` 带出的 `mac_type`、`mac_group` | `mac_role` 整行（LK-18，29 列）；`mac_vod` 整行（LK-24，82 列）；`mac_type` 整行（LK-20，20 列）；`mac_group` 整行（LK-13，10 列） | by 取值 id→`role_id`；time→`role_time`；time_add→`role_time_add`；score→`role_score`；hits→`role_hits`；hits_day→`role_hits_day`；hits_week→`role_hits_week`；hits_month→`role_hits_month`；up→`role_up`；down→`role_down`；level→`role_level`；其他取值一律回落为 `role_time` | `mac_role`（LK-18）的列；属性 `rid` 对应 `role_rid` | 无 |
| TAGMAP-13 | `actor` | `mac_actor`；`mac_type` | `mac_actor` 整行（LK-01，42 列）；`mac_type` 整行（LK-20，20 列） | by 取值 id→`actor_id`；time→`actor_time`；time_add→`actor_time_add`；score→`actor_score`；hits→`actor_hits`；hits_day→`actor_hits_day`；hits_week→`actor_hits_week`；hits_month→`actor_hits_month`；up→`actor_up`；down→`actor_down`；level→`actor_level`；其他取值一律回落为 `actor_time`；`actor_in` 不在附表 A，不允许 | `mac_actor`（LK-01）的列 | 无 |
| TAGMAP-14 | `topic` | `mac_topic`；`topic_rel_vod` 指向的 `mac_vod`、`topic_rel_art` 指向的 `mac_art`；这两张表带出的 `mac_type`、`mac_group` | `mac_topic` 整行（LK-19，37 列）；`mac_vod` 整行（LK-24，82 列）；`mac_art` 整行（LK-04，46 列）；`mac_type` 整行（LK-20，20 列）；`mac_group` 整行（LK-13，10 列） | by 取值 id→`topic_id`；time→`topic_time`；time_add→`topic_time_add`；score→`topic_score`；hits→`topic_hits`；hits_day→`topic_hits_day`；hits_week→`topic_hits_week`；hits_month→`topic_hits_month`；up→`topic_up`；down→`topic_down`；level→`topic_level`；其他取值一律回落为 `topic_time` | `mac_topic`（LK-19）的列 | 无 |
| TAGMAP-15 | `art` | `mac_art`；`mac_type`；`mac_group` | `mac_art` 整行（LK-04，46 列）；`mac_type` 整行（LK-20，20 列）；`mac_group` 整行（LK-13，10 列） | by 取值 id→`art_id`；time→`art_time`；time_add→`art_time_add`；score→`art_score`；hits→`art_hits`；hits_day→`art_hits_day`；hits_week→`art_hits_week`；hits_month→`art_hits_month`；up→`art_up`；down→`art_down`；level→`art_level`；其他取值一律回落为 `art_time` | `mac_art`（LK-04）的列 | 无 |
| TAGMAP-16 | `vod` | `mac_vod`；`mac_type`；`mac_group`；`mac_vod_search`（搜索优化开启时读写） | `mac_vod` 整行（LK-24，82 列）；`mac_type` 整行（LK-20，20 列）；`mac_group` 整行（LK-13，10 列）；`mac_vod_search` 整行（LK-25，8 列）；`mac_vod_search` 的写入：INSERT 整行、UPDATE `search_hit_count`、`search_last_hit_time` | by 取值 id→`vod_id`；time→`vod_time`；time_add→`vod_time_add`；score→`vod_score`；hits→`vod_hits`；hits_day→`vod_hits_day`；hits_week→`vod_hits_week`；hits_month→`vod_hits_month`；up→`vod_up`；down→`vod_down`；level→`vod_level`；其他取值一律回落为 `vod_time` | `mac_vod`（LK-24）的列；关键词类参数 `wd`、`name`、`tag`、`class`、`actor`、`director` 依次作用于 `vod_name`（含配置的附加规则列）、`vod_name`、`vod_tag`、`vod_class`、`vod_actor`、`vod_director` | 无 |
| TAGMAP-17 | `website` | `mac_website`；`mac_type`；`mac_group` | `mac_website` 整行（LK-26，42 列）；`mac_type` 整行（LK-20，20 列）；`mac_group` 整行（LK-13，10 列） | by 取值 id→`website_id`；time→`website_time`；time_add→`website_time_add`；score→`website_score`；hits→`website_hits`；hits_day→`website_hits_day`；hits_week→`website_hits_week`；hits_month→`website_hits_month`；up→`website_up`；down→`website_down`；level→`website_level`；referer→`website_referer`；referer_day→`website_referer_day`；referer_week→`website_referer_week`；referer_month→`website_referer_month`；其他取值一律回落为 `website_time`；`website_in` 不在附表 A，不允许 | `mac_website`（LK-26）的列 | 无 |
| TAGMAP-18 | `foreach` | 无 | 无 | 无 | 无 | 无 |
| TAGMAP-19 | `for` | 无 | 无 | 无 | 无 | 无 |

## D.7 Ajax data 返回列逐项（供 D-05、DYN-3 引用）

返回列 = D.5 该表全部列去掉 D.2 中同名前缀的 CS-DATA-EX 行（CS-DATA-EX-vod、CS-DATA-EX-art、CS-DATA-EX-topic、CS-DATA-EX-actor、CS-DATA-EX-role、CS-DATA-EX-website，每表 2 列）；`vod` 对非会员再去掉 D.2 CS-VOD-HIDE（8 列）。来源：`index/controller/Ajax.php` 第 56 行、第 60-61 行的 `unset`。

| 编号 | 对应 mid 与表 | 对象 | 返回列数 | 返回列（逐列） |
| --- | --- | --- | --- | --- |
| RET-1 | 1 → `mac_vod` | 会员 | 80 | `vod_id`、`type_id`、`type_id_1`、`group_id`、`vod_name`、`vod_sub`、`vod_en`、`vod_status`、`vod_letter`、`vod_color`、`vod_tag`、`vod_class`、`vod_pic`、`vod_pic_thumb`、`vod_pic_slide`、`vod_pic_screenshot`、`vod_actor`、`vod_director`、`vod_writer`、`vod_behind`、`vod_blurb`、`vod_remarks`、`vod_pubdate`、`vod_total`、`vod_serial`、`vod_tv`、`vod_weekday`、`vod_area`、`vod_lang`、`vod_year`、`vod_version`、`vod_state`、`vod_author`、`vod_jumpurl`、`vod_tpl`、`vod_tpl_play`、`vod_tpl_down`、`vod_isend`、`vod_lock`、`vod_level`、`vod_copyright`、`vod_points`、`vod_points_play`、`vod_points_down`、`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_duration`、`vod_up`、`vod_down`、`vod_score`、`vod_score_all`、`vod_score_num`、`vod_time`、`vod_time_add`、`vod_trysee`、`vod_douban_id`、`vod_douban_score`、`vod_reurl`、`vod_rel_vod`、`vod_rel_art`、`vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`、`vod_content`、`vod_play_from`、`vod_play_server`、`vod_play_note`、`vod_play_url`、`vod_down_from`、`vod_down_server`、`vod_down_note`、`vod_down_url`、`vod_plot`、`vod_plot_name`、`vod_plot_detail` |
| RET-2 | 1 → `mac_vod` | 非会员 | 72 | `vod_id`、`type_id`、`type_id_1`、`group_id`、`vod_name`、`vod_sub`、`vod_en`、`vod_status`、`vod_letter`、`vod_color`、`vod_tag`、`vod_class`、`vod_pic`、`vod_pic_thumb`、`vod_pic_slide`、`vod_pic_screenshot`、`vod_actor`、`vod_director`、`vod_writer`、`vod_behind`、`vod_blurb`、`vod_remarks`、`vod_pubdate`、`vod_total`、`vod_serial`、`vod_tv`、`vod_weekday`、`vod_area`、`vod_lang`、`vod_year`、`vod_version`、`vod_state`、`vod_author`、`vod_jumpurl`、`vod_tpl`、`vod_tpl_play`、`vod_tpl_down`、`vod_isend`、`vod_lock`、`vod_level`、`vod_copyright`、`vod_points`、`vod_points_play`、`vod_points_down`、`vod_hits`、`vod_hits_day`、`vod_hits_week`、`vod_hits_month`、`vod_duration`、`vod_up`、`vod_down`、`vod_score`、`vod_score_all`、`vod_score_num`、`vod_time`、`vod_time_add`、`vod_trysee`、`vod_douban_id`、`vod_douban_score`、`vod_reurl`、`vod_rel_vod`、`vod_rel_art`、`vod_pwd`、`vod_pwd_url`、`vod_pwd_play`、`vod_pwd_play_url`、`vod_pwd_down`、`vod_pwd_down_url`、`vod_content`、`vod_plot`、`vod_plot_name`、`vod_plot_detail` |
| RET-3 | 2 → `mac_art` | 所有人 | 44 | `art_id`、`type_id`、`type_id_1`、`group_id`、`art_name`、`art_sub`、`art_en`、`art_status`、`art_letter`、`art_color`、`art_from`、`art_author`、`art_tag`、`art_class`、`art_pic`、`art_pic_thumb`、`art_pic_slide`、`art_pic_screenshot`、`art_blurb`、`art_remarks`、`art_jumpurl`、`art_tpl`、`art_level`、`art_lock`、`art_points`、`art_points_detail`、`art_up`、`art_down`、`art_hits`、`art_hits_day`、`art_hits_week`、`art_hits_month`、`art_time`、`art_time_add`、`art_score`、`art_score_all`、`art_score_num`、`art_rel_art`、`art_rel_vod`、`art_pwd`、`art_pwd_url`、`art_title`、`art_note`、`art_content` |
| RET-4 | 3 → `mac_topic` | 所有人 | 35 | `topic_id`、`topic_name`、`topic_en`、`topic_sub`、`topic_status`、`topic_sort`、`topic_letter`、`topic_color`、`topic_tpl`、`topic_type`、`topic_pic`、`topic_pic_thumb`、`topic_pic_slide`、`topic_key`、`topic_des`、`topic_title`、`topic_blurb`、`topic_remarks`、`topic_level`、`topic_up`、`topic_down`、`topic_score`、`topic_score_all`、`topic_score_num`、`topic_hits`、`topic_hits_day`、`topic_hits_week`、`topic_hits_month`、`topic_time`、`topic_time_add`、`topic_tag`、`topic_rel_vod`、`topic_rel_art`、`topic_content`、`topic_extend` |
| RET-5 | 8 → `mac_actor` | 所有人 | 40 | `actor_id`、`type_id`、`type_id_1`、`actor_name`、`actor_en`、`actor_alias`、`actor_status`、`actor_lock`、`actor_letter`、`actor_sex`、`actor_color`、`actor_pic`、`actor_blurb`、`actor_remarks`、`actor_area`、`actor_height`、`actor_weight`、`actor_birthday`、`actor_birtharea`、`actor_blood`、`actor_starsign`、`actor_school`、`actor_works`、`actor_tag`、`actor_class`、`actor_level`、`actor_time`、`actor_time_add`、`actor_hits`、`actor_hits_day`、`actor_hits_week`、`actor_hits_month`、`actor_score`、`actor_score_all`、`actor_score_num`、`actor_up`、`actor_down`、`actor_tpl`、`actor_jumpurl`、`actor_content` |
| RET-6 | 9 → `mac_role` | 所有人 | 27 | `role_id`、`role_rid`、`role_name`、`role_en`、`role_status`、`role_lock`、`role_letter`、`role_color`、`role_actor`、`role_remarks`、`role_pic`、`role_sort`、`role_level`、`role_time`、`role_time_add`、`role_hits`、`role_hits_day`、`role_hits_week`、`role_hits_month`、`role_score`、`role_score_all`、`role_score_num`、`role_up`、`role_down`、`role_tpl`、`role_jumpurl`、`role_content` |
| RET-7 | 11 → `mac_website` | 所有人 | 40 | `website_id`、`type_id`、`type_id_1`、`website_name`、`website_sub`、`website_en`、`website_status`、`website_letter`、`website_color`、`website_lock`、`website_sort`、`website_jumpurl`、`website_pic`、`website_pic_screenshot`、`website_logo`、`website_area`、`website_lang`、`website_level`、`website_time`、`website_time_add`、`website_time_referer`、`website_hits`、`website_hits_day`、`website_hits_week`、`website_hits_month`、`website_score`、`website_score_all`、`website_score_num`、`website_up`、`website_down`、`website_referer`、`website_referer_day`、`website_referer_week`、`website_referer_month`、`website_tag`、`website_class`、`website_remarks`、`website_tpl`、`website_blurb`、`website_content` |
