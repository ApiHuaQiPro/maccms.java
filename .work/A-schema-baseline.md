# APPK-2 附表 A：原表原结构基线与结构快照定义

> 属于 `docs/prd/APPK-2.md`（v4）。冲突时以主文件为准。基线来源：`php/application/install/sql/install.sql`（26 条 `CREATE TABLE`，共 503 列；初始数据在同目录 `initdata.sql`：`mac_type` 18 行 INSERT，`install.sql` 内 `mac_group` 3 行 INSERT）。本附表由脚本从该 SQL 解析生成，列顺序、类型、默认值照原文，未做改动。

## A.1 结构快照 SNAP 的固定内容

SNAP 是对**同一个一次性库**用 MySQL 5.7 的 `information_schema` 与 `SHOW CREATE TABLE` 采集的下列 8 项。任何一项出现与基线不同的行，即判 BR-1 被破坏。所有验收（AC-11、AC-12、AC-15 及每个迁移项收尾）用**同一份采集脚本、同一份报告格式**，不另行比较。

| 项 | 采集内容（来源表/命令与列） | 判定 |
| --- | --- | --- |
| SNAP-1 | 库默认字符集与排序规则：`SCHEMATA.DEFAULT_CHARACTER_SET_NAME`、`DEFAULT_COLLATION_NAME` | 与基线一致 |
| SNAP-2 | 表集合与表属性：`TABLES` 中 `TABLE_NAME`、`TABLE_TYPE`、`ENGINE`、`ROW_FORMAT`、`TABLE_COLLATION`、`CREATE_OPTIONS`、`TABLE_COMMENT`（不含 `AUTO_INCREMENT`、行数、数据长度、更新时间：这些随数据变化） | 表名集合恰为本附表 A.2 的 26 张（多一张或少一张都算差异，含 `mac_tmp*` 临时表）；每张表属性与基线一致 |
| SNAP-3 | 列：`COLUMNS` 中 `TABLE_NAME`、`COLUMN_NAME`、`ORDINAL_POSITION`、`COLUMN_DEFAULT`、`IS_NULLABLE`、`DATA_TYPE`、`CHARACTER_MAXIMUM_LENGTH`、`NUMERIC_PRECISION`、`NUMERIC_SCALE`、`CHARACTER_SET_NAME`、`COLLATION_NAME`、`COLUMN_TYPE`、`COLUMN_KEY`、`EXTRA`、`COLUMN_COMMENT`、`GENERATION_EXPRESSION` | 列数 = 503；逐列与基线一致 |
| SNAP-4 | 索引：`STATISTICS` 中 `TABLE_NAME`、`NON_UNIQUE`、`INDEX_NAME`、`SEQ_IN_INDEX`、`COLUMN_NAME`、`COLLATION`、`SUB_PART`、`NULLABLE`、`INDEX_TYPE`、`INDEX_COMMENT`（不含 `CARDINALITY`：随数据变化） | 逐行与基线一致 |
| SNAP-5 | 约束边界：`TABLE_CONSTRAINTS`、`KEY_COLUMN_USAGE`、`REFERENTIAL_CONSTRAINTS` 全部列 | 与基线一致；`REFERENTIAL_CONSTRAINTS`（外键）必须为 0 行（基线没有外键） |
| SNAP-6 | 数据库对象：`TRIGGERS`、`VIEWS`、`ROUTINES`（存储过程与函数）、`EVENTS` 各自在本库的行数 | 4 项均为 0 行（基线没有这些对象） |
| SNAP-7 | 建表语句：26 张表各一条 `SHOW CREATE TABLE` 原文，去掉其中 `AUTO_INCREMENT=<数字>` 片段后逐字符比较 | 逐字符一致 |
| SNAP-8 | 采集环境：MySQL 版本字符串、`character_set_server`、`collation_server`、`sql_mode` 写进报告头 | 仅记录，不参与差异判定；基线与复测的版本必须相同，不同则该次报告无效 |

**基线文件**：在一次性库导入 `install.sql`（并导入 `initdata.sql`）后，用同一采集脚本生成 `docs/test/APPK-2/schema-baseline.*`（格式由设计单定，必须能逐行 diff）。生成基线是 AC-15 的前置产物（见主文件 §13 前置验收产物 PRE-1）。基线中表、列、索引、表属性必须与本附表 A.2 逐项一致，不一致则基线无效。

**报告判定**：`docs/test/APPK-2/schema-diff-<时点>.md` 逐项列出 SNAP-1～SNAP-7 的差异行数；全部为 0 才算通过，KPI-2 = 7 项差异行数之和。AC-11、AC-12、AC-15 引用同一报告格式。

**辅助证据（不替代 SNAP）**：①`SchemaGuardTest` 静态扫描通过；②验收环境里应用使用的数据库账号只授予 `SELECT, INSERT, UPDATE, DELETE`，跑完验收用例后日志中没有因权限不足被拒绝的 DDL 语句（出现即说明有迁移项在尝试改结构，按 BR-1 判失败）。

## A.2 26 张表的列清单（基线）

| 编号 | 表 | 引擎与属性 | 列数 | 索引/键行数 |
| --- | --- | --- | --- | --- |
| T-01 | `mac_actor` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 42 | 18 |
| T-02 | `mac_admin` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 11 | 2 |
| T-03 | `mac_annex` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 5 | 4 |
| T-04 | `mac_art` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 46 | 21 |
| T-05 | `mac_card` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 10 | 6 |
| T-06 | `mac_cash` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 10 | 3 |
| T-07 | `mac_cj_content` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC` | 6 | 3 |
| T-08 | `mac_cj_history` | `ENGINE=MyISAM DEFAULT CHARSET=utf8` | 1 | 2 |
| T-09 | `mac_cj_node` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC` | 31 | 1 |
| T-10 | `mac_collect` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 12 | 1 |
| T-11 | `mac_comment` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 14 | 7 |
| T-12 | `mac_gbook` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 10 | 6 |
| T-13 | `mac_group` | `ENGINE=MyISAM AUTO_INCREMENT=4 DEFAULT CHARSET=utf8` | 10 | 2 |
| T-14 | `mac_link` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 8 | 5 |
| T-15 | `mac_msg` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 8 | 4 |
| T-16 | `mac_order` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 10 | 4 |
| T-17 | `mac_plog` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 7 | 3 |
| T-18 | `mac_role` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 29 | 14 |
| T-19 | `mac_topic` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 37 | 17 |
| T-20 | `mac_type` | `ENGINE=MyISAM AUTO_INCREMENT=20 DEFAULT CHARSET=utf8` | 20 | 6 |
| T-21 | `mac_ulog` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 9 | 5 |
| T-22 | `mac_user` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 30 | 4 |
| T-23 | `mac_visit` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 5 | 3 |
| T-24 | `mac_vod` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 82 | 35 |
| T-25 | `mac_vod_search` | `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='vod搜索缓存表'` | 8 | 5 |
| T-26 | `mac_website` | `ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8` | 42 | 28 |

以下逐表列出全部列（名称 + 原文定义）与键。后文“整行”= 该表这里列出的全部列。

### T-01 `mac_actor`（42 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `actor_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `type_id` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `type_id_1` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `actor_name` | `varchar(255) NOT NULL DEFAULT ''` |
| 5 | `actor_en` | `varchar(255) NOT NULL DEFAULT ''` |
| 6 | `actor_alias` | `varchar(255) NOT NULL DEFAULT '' ` |
| 7 | `actor_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 8 | `actor_lock` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `actor_letter` | `char(1) NOT NULL DEFAULT '' ` |
| 10 | `actor_sex` | `char(1) NOT NULL DEFAULT ''` |
| 11 | `actor_color` | `varchar(6) NOT NULL DEFAULT '' ` |
| 12 | `actor_pic` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 13 | `actor_blurb` | `varchar(255) NOT NULL DEFAULT ''` |
| 14 | `actor_remarks` | `varchar(100) NOT NULL DEFAULT '' ` |
| 15 | `actor_area` | `varchar(20) NOT NULL DEFAULT ''` |
| 16 | `actor_height` | `varchar(10) NOT NULL DEFAULT '' ` |
| 17 | `actor_weight` | `varchar(10) NOT NULL DEFAULT '' ` |
| 18 | `actor_birthday` | `varchar(10) NOT NULL DEFAULT '' ` |
| 19 | `actor_birtharea` | `varchar(20) NOT NULL DEFAULT ''` |
| 20 | `actor_blood` | `varchar(10) NOT NULL DEFAULT '' ` |
| 21 | `actor_starsign` | `varchar(10) NOT NULL DEFAULT ''` |
| 22 | `actor_school` | `varchar(20) NOT NULL DEFAULT ''` |
| 23 | `actor_works` | `varchar(255) NOT NULL DEFAULT ''` |
| 24 | `actor_tag` | `varchar(255) NOT NULL DEFAULT ''` |
| 25 | `actor_class` | `varchar(255) NOT NULL DEFAULT ''` |
| 26 | `actor_level` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 27 | `actor_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 28 | `actor_time_add` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 29 | `actor_time_hits` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 30 | `actor_time_make` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 31 | `actor_hits` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 32 | `actor_hits_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 33 | `actor_hits_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 34 | `actor_hits_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 35 | `actor_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0'` |
| 36 | `actor_score_all` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 37 | `actor_score_num` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 38 | `actor_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 39 | `actor_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 40 | `actor_tpl` | `varchar(30) NOT NULL DEFAULT ''` |
| 41 | `actor_jumpurl` | `varchar(150) NOT NULL DEFAULT ''` |
| 42 | `actor_content` | `text NOT NULL ` |

键/索引：`PRIMARY KEY (`actor_id`)`；`KEY `type_id` (`type_id`) USING BTREE`；`KEY `type_id_1` (`type_id_1`) USING BTREE`；`KEY `actor_name` (`actor_name`) USING BTREE`；`KEY `actor_en` (`actor_en`) USING BTREE`；`KEY `actor_letter` (`actor_letter`) USING BTREE`；`KEY `actor_level` (`actor_level`) USING BTREE`；`KEY `actor_time` (`actor_time`) USING BTREE`；`KEY `actor_time_add` (`actor_time_add`) USING BTREE`；`KEY `actor_sex` (`actor_sex`)`；`KEY `actor_area` (`actor_area`)`；`KEY `actor_up` (`actor_up`)`；`KEY `actor_down` (`actor_down`)`；`KEY `actor_tag` (`actor_tag`)`；`KEY `actor_class` (`actor_class`)`；`KEY `actor_score` (`actor_score`)`；`KEY `actor_score_all` (`actor_score_all`)`；`KEY `actor_score_num` (`actor_score_num`)`

### T-02 `mac_admin`（11 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `admin_id` | `smallint(6) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `admin_name` | `varchar(30) NOT NULL DEFAULT ''` |
| 3 | `admin_pwd` | `char(32) NOT NULL DEFAULT ''` |
| 4 | `admin_random` | `char(32) NOT NULL DEFAULT ''` |
| 5 | `admin_status` | `tinyint(1) unsigned NOT NULL DEFAULT '1'` |
| 6 | `admin_auth` | `text NOT NULL` |
| 7 | `admin_login_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 8 | `admin_login_ip` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 9 | `admin_login_num` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 10 | `admin_last_login_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 11 | `admin_last_login_ip` | `int(10) unsigned NOT NULL DEFAULT '0'` |

键/索引：`PRIMARY KEY (`admin_id`)`；`KEY `admin_name` (`admin_name`)`

### T-03 `mac_annex`（5 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `annex_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `annex_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 3 | `annex_file` | `varchar(255) NOT NULL DEFAULT ''` |
| 4 | `annex_size` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 5 | `annex_type` | `varchar(8) NOT NULL DEFAULT ''` |

键/索引：`PRIMARY KEY (`annex_id`)`；`KEY `annex_time` (`annex_time`)`；`KEY `annex_file` (`annex_file`)`；`KEY `annex_type` (`annex_type`)`

### T-04 `mac_art`（46 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `art_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `type_id` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `type_id_1` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `group_id` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 5 | `art_name` | `varchar(255) NOT NULL DEFAULT '' ` |
| 6 | `art_sub` | `varchar(255) NOT NULL DEFAULT '' ` |
| 7 | `art_en` | `varchar(255) NOT NULL DEFAULT '' ` |
| 8 | `art_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `art_letter` | `char(1) NOT NULL DEFAULT '' ` |
| 10 | `art_color` | `varchar(6) NOT NULL DEFAULT '' ` |
| 11 | `art_from` | `varchar(30) NOT NULL DEFAULT '' ` |
| 12 | `art_author` | `varchar(30) NOT NULL DEFAULT '' ` |
| 13 | `art_tag` | `varchar(100) NOT NULL DEFAULT '' ` |
| 14 | `art_class` | `varchar(255) NOT NULL DEFAULT '' ` |
| 15 | `art_pic` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 16 | `art_pic_thumb` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 17 | `art_pic_slide` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 18 | `art_pic_screenshot` | `text` |
| 19 | `art_blurb` | `varchar(255) NOT NULL DEFAULT '' ` |
| 20 | `art_remarks` | `varchar(100) NOT NULL DEFAULT '' ` |
| 21 | `art_jumpurl` | `varchar(150) NOT NULL DEFAULT '' ` |
| 22 | `art_tpl` | `varchar(30) NOT NULL DEFAULT '' ` |
| 23 | `art_level` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 24 | `art_lock` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 25 | `art_points` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 26 | `art_points_detail` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 27 | `art_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 28 | `art_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 29 | `art_hits` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 30 | `art_hits_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 31 | `art_hits_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 32 | `art_hits_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 33 | `art_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 34 | `art_time_add` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 35 | `art_time_hits` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 36 | `art_time_make` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 37 | `art_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0' ` |
| 38 | `art_score_all` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 39 | `art_score_num` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 40 | `art_rel_art` | `varchar(255) NOT NULL DEFAULT '' ` |
| 41 | `art_rel_vod` | `varchar(255) NOT NULL DEFAULT '' ` |
| 42 | `art_pwd` | `varchar(10) NOT NULL DEFAULT '' ` |
| 43 | `art_pwd_url` | `varchar(255) NOT NULL DEFAULT '' ` |
| 44 | `art_title` | `mediumtext NOT NULL ` |
| 45 | `art_note` | `mediumtext NOT NULL ` |
| 46 | `art_content` | `mediumtext NOT NULL ` |

键/索引：`PRIMARY KEY (`art_id`)`；`KEY `type_id` (`type_id`) USING BTREE`；`KEY `type_id_1` (`type_id_1`) USING BTREE`；`KEY `art_level` (`art_level`) USING BTREE`；`KEY `art_hits` (`art_hits`) USING BTREE`；`KEY `art_time` (`art_time`) USING BTREE`；`KEY `art_letter` (`art_letter`) USING BTREE`；`KEY `art_down` (`art_down`) USING BTREE`；`KEY `art_up` (`art_up`) USING BTREE`；`KEY `art_tag` (`art_tag`) USING BTREE`；`KEY `art_name` (`art_name`) USING BTREE`；`KEY `art_enn` (`art_en`) USING BTREE`；`KEY `art_hits_day` (`art_hits_day`) USING BTREE`；`KEY `art_hits_week` (`art_hits_week`) USING BTREE`；`KEY `art_hits_month` (`art_hits_month`) USING BTREE`；`KEY `art_time_add` (`art_time_add`) USING BTREE`；`KEY `art_time_make` (`art_time_make`) USING BTREE`；`KEY `art_lock` (`art_lock`)`；`KEY `art_score` (`art_score`)`；`KEY `art_score_all` (`art_score_all`)`；`KEY `art_score_num` (`art_score_num`)`

### T-05 `mac_card`（10 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `card_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `card_no` | `varchar(16) NOT NULL DEFAULT '' ` |
| 3 | `card_pwd` | `varchar(8) NOT NULL DEFAULT '' ` |
| 4 | `card_money` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 5 | `card_points` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 6 | `card_use_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `card_sale_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 8 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `card_add_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 10 | `card_use_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |

键/索引：`PRIMARY KEY (`card_id`)`；`KEY `user_id` (`user_id`) USING BTREE`；`KEY `card_add_time` (`card_add_time`) USING BTREE`；`KEY `card_use_time` (`card_use_time`) USING BTREE`；`KEY `card_no` (`card_no`)`；`KEY `card_pwd` (`card_pwd`)`

### T-06 `mac_cash`（10 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `cash_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 3 | `cash_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `cash_points` | `smallint(6) unsigned NOT NULL DEFAULT '0'` |
| 5 | `cash_money` | `decimal(12,2) unsigned NOT NULL DEFAULT '0.00'` |
| 6 | `cash_bank_name` | `varchar(60) NOT NULL DEFAULT ''` |
| 7 | `cash_bank_no` | `varchar(30) NOT NULL DEFAULT ''` |
| 8 | `cash_payee_name` | `varchar(30) NOT NULL DEFAULT ''` |
| 9 | `cash_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 10 | `cash_time_audit` | `int(10) unsigned NOT NULL DEFAULT '0'` |

键/索引：`PRIMARY KEY (`cash_id`)`；`KEY `user_id` (`user_id`)`；`KEY `cash_status` (`cash_status`) USING BTREE`

### T-07 `mac_cj_content`（6 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `nodeid` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 3 | `status` | `tinyint(1) unsigned NOT NULL DEFAULT '1'` |
| 4 | `url` | `char(255) NOT NULL` |
| 5 | `title` | `char(100) NOT NULL` |
| 6 | `data` | `mediumtext NOT NULL` |

键/索引：`PRIMARY KEY (`id`)`；`KEY `nodeid` (`nodeid`)`；`KEY `status` (`status`)`

### T-08 `mac_cj_history`（1 列）

`ENGINE=MyISAM DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `md5` | `char(32) NOT NULL` |

键/索引：`PRIMARY KEY (`md5`)`；`KEY `md5` (`md5`)`

### T-09 `mac_cj_node`（31 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `nodeid` | `smallint(6) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `name` | `varchar(20) NOT NULL` |
| 3 | `lastdate` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 4 | `sourcecharset` | `varchar(8) NOT NULL` |
| 5 | `sourcetype` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 6 | `urlpage` | `text NOT NULL` |
| 7 | `pagesize_start` | `tinyint(3) unsigned NOT NULL DEFAULT '0'` |
| 8 | `pagesize_end` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 9 | `page_base` | `char(255) NOT NULL` |
| 10 | `par_num` | `tinyint(3) unsigned NOT NULL DEFAULT '1'` |
| 11 | `url_contain` | `char(100) NOT NULL` |
| 12 | `url_except` | `char(100) NOT NULL` |
| 13 | `url_start` | `char(100) NOT NULL DEFAULT ''` |
| 14 | `url_end` | `char(100) NOT NULL DEFAULT ''` |
| 15 | `title_rule` | `char(100) NOT NULL` |
| 16 | `title_html_rule` | `text NOT NULL` |
| 17 | `type_rule` | `char(100) NOT NULL` |
| 18 | `type_html_rule` | `text NOT NULL` |
| 19 | `content_rule` | `char(100) NOT NULL` |
| 20 | `content_html_rule` | `text NOT NULL` |
| 21 | `content_page_start` | `char(100) NOT NULL` |
| 22 | `content_page_end` | `char(100) NOT NULL` |
| 23 | `content_page_rule` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 24 | `content_page` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 25 | `content_nextpage` | `char(100) NOT NULL` |
| 26 | `down_attachment` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 27 | `watermark` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 28 | `coll_order` | `tinyint(3) unsigned NOT NULL DEFAULT '0'` |
| 29 | `customize_config` | `text NOT NULL` |
| 30 | `program_config` | `text NOT NULL` |
| 31 | `mid` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |

键/索引：`PRIMARY KEY (`nodeid`)`

### T-10 `mac_collect`（12 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `collect_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `collect_name` | `varchar(30) NOT NULL DEFAULT '' ` |
| 3 | `collect_url` | `varchar(255) NOT NULL DEFAULT '' ` |
| 4 | `collect_type` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 5 | `collect_mid` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 6 | `collect_appid` | `varchar(30) NOT NULL DEFAULT '' ` |
| 7 | `collect_appkey` | `varchar(30) NOT NULL DEFAULT '' ` |
| 8 | `collect_param` | `varchar(100) NOT NULL DEFAULT '' ` |
| 9 | `collect_filter` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 10 | `collect_filter_from` | `varchar(255) NOT NULL DEFAULT '' ` |
| 11 | `collect_opt` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 12 | `collect_sync_pic_opt` | `tinyint(1) unsigned NOT NULL DEFAULT '0' COMMENT '同步图片选项，0-跟随全局，1-开启，2-关闭'` |

键/索引：`PRIMARY KEY (`collect_id`)`

### T-11 `mac_comment`（14 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `comment_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `comment_mid` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 3 | `comment_rid` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `comment_pid` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 5 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 6 | `comment_status` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 7 | `comment_name` | `varchar(60) NOT NULL DEFAULT '' ` |
| 8 | `comment_ip` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `comment_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 10 | `comment_content` | `varchar(255) NOT NULL DEFAULT ''` |
| 11 | `comment_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 12 | `comment_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 13 | `comment_reply` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 14 | `comment_report` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |

键/索引：`PRIMARY KEY (`comment_id`)`；`KEY `comment_mid` (`comment_mid`) USING BTREE`；`KEY `comment_rid` (`comment_rid`) USING BTREE`；`KEY `comment_time` (`comment_time`) USING BTREE`；`KEY `comment_pid` (`comment_pid`)`；`KEY `user_id` (`user_id`)`；`KEY `comment_reply` (`comment_reply`)`

### T-12 `mac_gbook`（10 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `gbook_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `gbook_rid` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `gbook_status` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 5 | `gbook_name` | `varchar(60) NOT NULL DEFAULT '' ` |
| 6 | `gbook_ip` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `gbook_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 8 | `gbook_reply_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `gbook_content` | `varchar(255) NOT NULL DEFAULT '' ` |
| 10 | `gbook_reply` | `varchar(255) NOT NULL DEFAULT '' ` |

键/索引：`PRIMARY KEY (`gbook_id`)`；`KEY `gbook_rid` (`gbook_rid`) USING BTREE`；`KEY `gbook_time` (`gbook_time`) USING BTREE`；`KEY `gbook_reply_time` (`gbook_reply_time`) USING BTREE`；`KEY `user_id` (`user_id`)`；`KEY `gbook_reply` (`gbook_reply`)`

### T-13 `mac_group`（10 列）

`ENGINE=MyISAM AUTO_INCREMENT=4 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `group_id` | `smallint(6) NOT NULL AUTO_INCREMENT` |
| 2 | `group_name` | `varchar(30) NOT NULL DEFAULT '' ` |
| 3 | `group_status` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 4 | `group_type` | `text NOT NULL` |
| 5 | `group_popedom` | `text NOT NULL` |
| 6 | `group_points_day` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `group_points_week` | `smallint(6) NOT NULL DEFAULT '0' ` |
| 8 | `group_points_month` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `group_points_year` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 10 | `group_points_free` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |

键/索引：`PRIMARY KEY (`group_id`)`；`KEY `group_status` (`group_status`)`

### T-14 `mac_link`（8 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `link_id` | `smallint(6) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `link_type` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `link_name` | `varchar(60) NOT NULL DEFAULT '' ` |
| 4 | `link_sort` | `smallint(6) NOT NULL DEFAULT '0' ` |
| 5 | `link_add_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 6 | `link_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `link_url` | `varchar(255) NOT NULL DEFAULT '' ` |
| 8 | `link_logo` | `varchar(255) NOT NULL DEFAULT '' ` |

键/索引：`PRIMARY KEY (`link_id`)`；`KEY `link_sort` (`link_sort`) USING BTREE`；`KEY `link_type` (`link_type`) USING BTREE`；`KEY `link_add_time` (`link_add_time`)`；`KEY `link_time` (`link_time`)`

### T-15 `mac_msg`（8 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `msg_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 3 | `msg_type` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 4 | `msg_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 5 | `msg_to` | `varchar(30) NOT NULL DEFAULT ''` |
| 6 | `msg_code` | `varchar(10) NOT NULL DEFAULT ''` |
| 7 | `msg_content` | `varchar(255) NOT NULL DEFAULT ''` |
| 8 | `msg_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |

键/索引：`PRIMARY KEY (`msg_id`)`；`KEY `msg_code` (`msg_code`)`；`KEY `msg_time` (`msg_time`)`；`KEY `user_id` (`user_id`)`

### T-16 `mac_order`（10 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `order_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT ` |
| 2 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `order_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `order_code` | `varchar(30) NOT NULL DEFAULT '' ` |
| 5 | `order_price` | `decimal(12,2) unsigned NOT NULL DEFAULT '0.00' ` |
| 6 | `order_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `order_points` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 8 | `order_pay_type` | `varchar(10) NOT NULL DEFAULT '' ` |
| 9 | `order_pay_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 10 | `order_remarks` | `varchar(100) NOT NULL DEFAULT '' ` |

键/索引：`PRIMARY KEY (`order_id`)`；`KEY `order_code` (`order_code`) USING BTREE`；`KEY `user_id` (`user_id`) USING BTREE`；`KEY `order_time` (`order_time`) USING BTREE`

### T-17 `mac_plog`（7 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `plog_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 3 | `user_id_1` | `int(10) NOT NULL DEFAULT '0'` |
| 4 | `plog_type` | `tinyint(1) unsigned NOT NULL DEFAULT '1'` |
| 5 | `plog_points` | `smallint(6) unsigned NOT NULL DEFAULT '0'` |
| 6 | `plog_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 7 | `plog_remarks` | `varchar(100) NOT NULL DEFAULT ''` |

键/索引：`PRIMARY KEY (`plog_id`)`；`KEY `user_id` (`user_id`)`；`KEY `plog_type` (`plog_type`) USING BTREE`

### T-18 `mac_role`（29 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `role_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `role_rid` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `role_name` | `varchar(255) NOT NULL DEFAULT '' ` |
| 4 | `role_en` | `varchar(255) NOT NULL DEFAULT '' ` |
| 5 | `role_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 6 | `role_lock` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `role_letter` | `char(1) NOT NULL DEFAULT '' ` |
| 8 | `role_color` | `varchar(6) NOT NULL DEFAULT '' ` |
| 9 | `role_actor` | `varchar(255) NOT NULL DEFAULT '' ` |
| 10 | `role_remarks` | `varchar(100) NOT NULL DEFAULT ''` |
| 11 | `role_pic` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 12 | `role_sort` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 13 | `role_level` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 14 | `role_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 15 | `role_time_add` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 16 | `role_time_hits` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 17 | `role_time_make` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 18 | `role_hits` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 19 | `role_hits_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 20 | `role_hits_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 21 | `role_hits_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 22 | `role_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0'` |
| 23 | `role_score_all` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 24 | `role_score_num` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 25 | `role_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 26 | `role_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 27 | `role_tpl` | `varchar(30) NOT NULL DEFAULT '' ` |
| 28 | `role_jumpurl` | `varchar(150) NOT NULL DEFAULT '' ` |
| 29 | `role_content` | `text NOT NULL  ` |

键/索引：`PRIMARY KEY (`role_id`)`；`KEY `role_rid` (`role_rid`)`；`KEY `role_name` (`role_name`)`；`KEY `role_en` (`role_en`)`；`KEY `role_letter` (`role_letter`)`；`KEY `role_actor` (`role_actor`)`；`KEY `role_level` (`role_level`)`；`KEY `role_time` (`role_time`)`；`KEY `role_time_add` (`role_time_add`)`；`KEY `role_score` (`role_score`)`；`KEY `role_score_all` (`role_score_all`)`；`KEY `role_score_num` (`role_score_num`)`；`KEY `role_up` (`role_up`)`；`KEY `role_down` (`role_down`)`

### T-19 `mac_topic`（37 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `topic_id` | `smallint(6) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `topic_name` | `varchar(255) NOT NULL DEFAULT '' ` |
| 3 | `topic_en` | `varchar(255) NOT NULL DEFAULT '' ` |
| 4 | `topic_sub` | `varchar(255) NOT NULL DEFAULT '' ` |
| 5 | `topic_status` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 6 | `topic_sort` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `topic_letter` | `char(1) NOT NULL DEFAULT '' ` |
| 8 | `topic_color` | `varchar(6) NOT NULL DEFAULT '' ` |
| 9 | `topic_tpl` | `varchar(30) NOT NULL DEFAULT '' ` |
| 10 | `topic_type` | `varchar(255) NOT NULL DEFAULT '' ` |
| 11 | `topic_pic` | `varchar(1024) NOT NULL DEFAULT ''` |
| 12 | `topic_pic_thumb` | `varchar(1024) NOT NULL DEFAULT ''` |
| 13 | `topic_pic_slide` | `varchar(1024) NOT NULL DEFAULT ''` |
| 14 | `topic_key` | `varchar(255) NOT NULL DEFAULT '' ` |
| 15 | `topic_des` | `varchar(255) NOT NULL DEFAULT '' ` |
| 16 | `topic_title` | `varchar(255) NOT NULL DEFAULT '' ` |
| 17 | `topic_blurb` | `varchar(255) NOT NULL DEFAULT '' ` |
| 18 | `topic_remarks` | `varchar(100) NOT NULL DEFAULT '' ` |
| 19 | `topic_level` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 20 | `topic_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 21 | `topic_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 22 | `topic_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0' ` |
| 23 | `topic_score_all` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 24 | `topic_score_num` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 25 | `topic_hits` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 26 | `topic_hits_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 27 | `topic_hits_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 28 | `topic_hits_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 29 | `topic_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 30 | `topic_time_add` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 31 | `topic_time_hits` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 32 | `topic_time_make` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 33 | `topic_tag` | `varchar(255) NOT NULL DEFAULT '' ` |
| 34 | `topic_rel_vod` | `text NOT NULL` |
| 35 | `topic_rel_art` | `text NOT NULL` |
| 36 | `topic_content` | `text NOT NULL` |
| 37 | `topic_extend` | `text NOT NULL` |

键/索引：`PRIMARY KEY (`topic_id`)`；`KEY `topic_sort` (`topic_sort`) USING BTREE`；`KEY `topic_level` (`topic_level`) USING BTREE`；`KEY `topic_score` (`topic_score`) USING BTREE`；`KEY `topic_score_all` (`topic_score_all`) USING BTREE`；`KEY `topic_score_num` (`topic_score_num`) USING BTREE`；`KEY `topic_hits` (`topic_hits`) USING BTREE`；`KEY `topic_hits_day` (`topic_hits_day`) USING BTREE`；`KEY `topic_hits_week` (`topic_hits_week`) USING BTREE`；`KEY `topic_hits_month` (`topic_hits_month`) USING BTREE`；`KEY `topic_time_add` (`topic_time_add`) USING BTREE`；`KEY `topic_time` (`topic_time`) USING BTREE`；`KEY `topic_time_hits` (`topic_time_hits`) USING BTREE`；`KEY `topic_name` (`topic_name`)`；`KEY `topic_en` (`topic_en`)`；`KEY `topic_up` (`topic_up`)`；`KEY `topic_down` (`topic_down`)`

### T-20 `mac_type`（20 列）

`ENGINE=MyISAM AUTO_INCREMENT=20 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `type_id` | `smallint(6) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `type_name` | `varchar(60) NOT NULL DEFAULT '' ` |
| 3 | `type_en` | `varchar(60) NOT NULL DEFAULT '' ` |
| 4 | `type_sort` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 5 | `type_mid` | `smallint(6) unsigned NOT NULL DEFAULT '1' ` |
| 6 | `type_pid` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `type_status` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 8 | `type_tpl` | `varchar(30) NOT NULL DEFAULT '' ` |
| 9 | `type_tpl_list` | `varchar(30) NOT NULL DEFAULT ''` |
| 10 | `type_tpl_detail` | `varchar(30) NOT NULL DEFAULT '' ` |
| 11 | `type_tpl_play` | `varchar(30) NOT NULL DEFAULT '' ` |
| 12 | `type_tpl_down` | `varchar(30) NOT NULL DEFAULT '' ` |
| 13 | `type_key` | `varchar(255) NOT NULL DEFAULT '' ` |
| 14 | `type_des` | `varchar(255) NOT NULL DEFAULT '' ` |
| 15 | `type_title` | `varchar(255) NOT NULL DEFAULT '' ` |
| 16 | `type_union` | `varchar(255) NOT NULL DEFAULT ''` |
| 17 | `type_extend` | `text NOT NULL` |
| 18 | `type_logo` | `VARCHAR( 255 )  NOT NULL DEFAULT  ''` |
| 19 | `type_pic` | `VARCHAR( 1024 )  NOT NULL DEFAULT  ''` |
| 20 | `type_jumpurl` | `VARCHAR( 150 )  NOT NULL DEFAULT  ''` |

键/索引：`PRIMARY KEY (`type_id`)`；`KEY `type_sort` (`type_sort`) USING BTREE`；`KEY `type_pid` (`type_pid`) USING BTREE`；`KEY `type_name` (`type_name`)`；`KEY `type_en` (`type_en`)`；`KEY `type_mid` (`type_mid`)`

### T-21 `mac_ulog`（9 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `ulog_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT ` |
| 2 | `user_id` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `ulog_mid` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `ulog_type` | `tinyint(1) unsigned NOT NULL DEFAULT '1' ` |
| 5 | `ulog_rid` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 6 | `ulog_sid` | `tinyint(3) unsigned NOT NULL DEFAULT '0' ` |
| 7 | `ulog_nid` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 8 | `ulog_points` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `ulog_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |

键/索引：`PRIMARY KEY (`ulog_id`)`；`KEY `user_id` (`user_id`)`；`KEY `ulog_mid` (`ulog_mid`)`；`KEY `ulog_type` (`ulog_type`)`；`KEY `ulog_rid` (`ulog_rid`)`

### T-22 `mac_user`（30 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `user_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `group_id` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 3 | `user_name` | `varchar(30) NOT NULL DEFAULT '' ` |
| 4 | `user_pwd` | `varchar(32) NOT NULL DEFAULT '' ` |
| 5 | `user_nick_name` | `varchar(30) NOT NULL DEFAULT '' ` |
| 6 | `user_qq` | `varchar(16) NOT NULL DEFAULT '' ` |
| 7 | `user_email` | `varchar(30) NOT NULL DEFAULT '' ` |
| 8 | `user_phone` | `varchar(16) NOT NULL DEFAULT '' ` |
| 9 | `user_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 10 | `user_portrait` | `varchar(100) NOT NULL DEFAULT '' ` |
| 11 | `user_portrait_thumb` | `varchar(100) NOT NULL DEFAULT '' ` |
| 12 | `user_openid_qq` | `varchar(40) NOT NULL DEFAULT '' ` |
| 13 | `user_openid_weixin` | `varchar(40) NOT NULL DEFAULT '' ` |
| 14 | `user_question` | `varchar(255) NOT NULL DEFAULT '' ` |
| 15 | `user_answer` | `varchar(255) NOT NULL DEFAULT '' ` |
| 16 | `user_points` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 17 | `user_points_froze` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 18 | `user_reg_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 19 | `user_reg_ip` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 20 | `user_login_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 21 | `user_login_ip` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 22 | `user_last_login_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 23 | `user_last_login_ip` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 24 | `user_login_num` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 25 | `user_extend` | `smallint(6) unsigned NOT NULL DEFAULT '0'` |
| 26 | `user_random` | `varchar(32) NOT NULL DEFAULT '' ` |
| 27 | `user_end_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 28 | `user_pid` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 29 | `user_pid_2` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 30 | `user_pid_3` | `int(10) unsigned NOT NULL DEFAULT '0' ` |

键/索引：`PRIMARY KEY (`user_id`)`；`KEY `type_id` (`group_id`) USING BTREE`；`KEY `user_name` (`user_name`)`；`KEY `user_reg_time` (`user_reg_time`)`

### T-23 `mac_visit`（5 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `visit_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT ` |
| 2 | `user_id` | `int(10) unsigned DEFAULT '0'` |
| 3 | `visit_ip` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `visit_ly` | `varchar(100) NOT NULL DEFAULT ''` |
| 5 | `visit_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |

键/索引：`PRIMARY KEY (`visit_id`)`；`KEY `user_id` (`user_id`)`；`KEY `visit_time` (`visit_time`)`

### T-24 `mac_vod`（82 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `vod_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT ` |
| 2 | `type_id` | `smallint(6) NOT NULL DEFAULT '0' ` |
| 3 | `type_id_1` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 4 | `group_id` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 5 | `vod_name` | `varchar(255) NOT NULL DEFAULT '' ` |
| 6 | `vod_sub` | `varchar(255) NOT NULL DEFAULT '' ` |
| 7 | `vod_en` | `varchar(255) NOT NULL DEFAULT '' ` |
| 8 | `vod_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 9 | `vod_letter` | `char(1) NOT NULL DEFAULT '' ` |
| 10 | `vod_color` | `varchar(6) NOT NULL DEFAULT '' ` |
| 11 | `vod_tag` | `varchar(100) NOT NULL DEFAULT '' ` |
| 12 | `vod_class` | `varchar(255) NOT NULL DEFAULT '' ` |
| 13 | `vod_pic` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 14 | `vod_pic_thumb` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 15 | `vod_pic_slide` | `varchar(1024) NOT NULL DEFAULT '' ` |
| 16 | `vod_pic_screenshot` | `text` |
| 17 | `vod_actor` | `varchar(255) NOT NULL DEFAULT '' ` |
| 18 | `vod_director` | `varchar(255) NOT NULL DEFAULT '' ` |
| 19 | `vod_writer` | `varchar(100) NOT NULL DEFAULT '' ` |
| 20 | `vod_behind` | `varchar(100) NOT NULL DEFAULT '' ` |
| 21 | `vod_blurb` | `varchar(255) NOT NULL DEFAULT '' ` |
| 22 | `vod_remarks` | `varchar(100) NOT NULL DEFAULT '' ` |
| 23 | `vod_pubdate` | `varchar(100) NOT NULL DEFAULT '' ` |
| 24 | `vod_total` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 25 | `vod_serial` | `varchar(20) NOT NULL DEFAULT '0' ` |
| 26 | `vod_tv` | `varchar(30) NOT NULL DEFAULT '' ` |
| 27 | `vod_weekday` | `varchar(30) NOT NULL DEFAULT '' ` |
| 28 | `vod_area` | `varchar(20) NOT NULL DEFAULT '' ` |
| 29 | `vod_lang` | `varchar(10) NOT NULL DEFAULT '' ` |
| 30 | `vod_year` | `varchar(10) NOT NULL DEFAULT '' ` |
| 31 | `vod_version` | `varchar(30) NOT NULL DEFAULT '' ` |
| 32 | `vod_state` | `varchar(30) NOT NULL DEFAULT '' ` |
| 33 | `vod_author` | `varchar(60) NOT NULL DEFAULT '' ` |
| 34 | `vod_jumpurl` | `varchar(150) NOT NULL DEFAULT '' ` |
| 35 | `vod_tpl` | `varchar(30) NOT NULL DEFAULT '' ` |
| 36 | `vod_tpl_play` | `varchar(30) NOT NULL DEFAULT '' ` |
| 37 | `vod_tpl_down` | `varchar(30) NOT NULL DEFAULT '' ` |
| 38 | `vod_isend` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 39 | `vod_lock` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 40 | `vod_level` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 41 | `vod_copyright` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 42 | `vod_points` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 43 | `vod_points_play` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 44 | `vod_points_down` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 45 | `vod_hits` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 46 | `vod_hits_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 47 | `vod_hits_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 48 | `vod_hits_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 49 | `vod_duration` | `varchar(10) NOT NULL DEFAULT '' ` |
| 50 | `vod_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 51 | `vod_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 52 | `vod_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0' ` |
| 53 | `vod_score_all` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 54 | `vod_score_num` | `mediumint(8) unsigned NOT NULL DEFAULT '0' ` |
| 55 | `vod_time` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 56 | `vod_time_add` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 57 | `vod_time_hits` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 58 | `vod_time_make` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 59 | `vod_trysee` | `smallint(6) unsigned NOT NULL DEFAULT '0' ` |
| 60 | `vod_douban_id` | `int(10) unsigned NOT NULL DEFAULT '0' ` |
| 61 | `vod_douban_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0' ` |
| 62 | `vod_reurl` | `varchar(255) NOT NULL DEFAULT '' ` |
| 63 | `vod_rel_vod` | `varchar(255) NOT NULL DEFAULT '' ` |
| 64 | `vod_rel_art` | `varchar(255) NOT NULL DEFAULT '' ` |
| 65 | `vod_pwd` | `varchar(10) NOT NULL DEFAULT '' ` |
| 66 | `vod_pwd_url` | `varchar(255) NOT NULL DEFAULT '' ` |
| 67 | `vod_pwd_play` | `varchar(10) NOT NULL DEFAULT '' ` |
| 68 | `vod_pwd_play_url` | `varchar(255) NOT NULL DEFAULT '' ` |
| 69 | `vod_pwd_down` | `varchar(10) NOT NULL DEFAULT '' ` |
| 70 | `vod_pwd_down_url` | `varchar(255) NOT NULL DEFAULT '' ` |
| 71 | `vod_content` | `mediumtext NOT NULL ` |
| 72 | `vod_play_from` | `varchar(255) NOT NULL DEFAULT '' ` |
| 73 | `vod_play_server` | `varchar(255) NOT NULL DEFAULT '' ` |
| 74 | `vod_play_note` | `varchar(255) NOT NULL DEFAULT '' ` |
| 75 | `vod_play_url` | `mediumtext NOT NULL ` |
| 76 | `vod_down_from` | `varchar(255) NOT NULL DEFAULT '' ` |
| 77 | `vod_down_server` | `varchar(255) NOT NULL DEFAULT '' ` |
| 78 | `vod_down_note` | `varchar(255) NOT NULL DEFAULT '' ` |
| 79 | `vod_down_url` | `mediumtext NOT NULL ` |
| 80 | `vod_plot` | `tinyint(1) unsigned NOT NULL DEFAULT '0' ` |
| 81 | `vod_plot_name` | `mediumtext NOT NULL ` |
| 82 | `vod_plot_detail` | `mediumtext NOT NULL ` |

键/索引：`PRIMARY KEY (`vod_id`)`；`KEY `type_id` (`type_id`) USING BTREE`；`KEY `type_id_1` (`type_id_1`) USING BTREE`；`KEY `vod_level` (`vod_level`) USING BTREE`；`KEY `vod_hits` (`vod_hits`) USING BTREE`；`KEY `vod_letter` (`vod_letter`) USING BTREE`；`KEY `vod_name` (`vod_name`) USING BTREE`；`KEY `vod_year` (`vod_year`) USING BTREE`；`KEY `vod_area` (`vod_area`) USING BTREE`；`KEY `vod_lang` (`vod_lang`) USING BTREE`；`KEY `vod_tag` (`vod_tag`) USING BTREE`；`KEY `vod_class` (`vod_class`) USING BTREE`；`KEY `vod_lock` (`vod_lock`) USING BTREE`；`KEY `vod_up` (`vod_up`) USING BTREE`；`KEY `vod_down` (`vod_down`) USING BTREE`；`KEY `vod_en` (`vod_en`) USING BTREE`；`KEY `vod_hits_day` (`vod_hits_day`) USING BTREE`；`KEY `vod_hits_week` (`vod_hits_week`) USING BTREE`；`KEY `vod_hits_month` (`vod_hits_month`) USING BTREE`；`KEY `vod_plot` (`vod_plot`) USING BTREE`；`KEY `vod_points_play` (`vod_points_play`) USING BTREE`；`KEY `vod_points_down` (`vod_points_down`) USING BTREE`；`KEY `group_id` (`group_id`) USING BTREE`；`KEY `vod_time_add` (`vod_time_add`) USING BTREE`；`KEY `vod_time` (`vod_time`) USING BTREE`；`KEY `vod_time_make` (`vod_time_make`) USING BTREE`；`KEY `vod_actor` (`vod_actor`) USING BTREE`；`KEY `vod_director` (`vod_director`) USING BTREE`；`KEY `vod_score_all` (`vod_score_all`) USING BTREE`；`KEY `vod_score_num` (`vod_score_num`) USING BTREE`；`KEY `vod_total` (`vod_total`) USING BTREE`；`KEY `vod_score` (`vod_score`) USING BTREE`；`KEY `vod_version` (`vod_version`)`；`KEY `vod_state` (`vod_state`)`；`KEY `vod_isend` (`vod_isend`)`

### T-25 `mac_vod_search`（8 列）

`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='vod搜索缓存表'`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `search_key` | `char(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '搜索键（关键词md5）'` |
| 2 | `search_word` | `varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '搜索关键词'` |
| 3 | `search_field` | `varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '搜索字段名（可有多个，用\|分隔）'` |
| 4 | `search_hit_count` | `bigint unsigned NOT NULL DEFAULT '0' COMMENT '搜索命中次数'` |
| 5 | `search_last_hit_time` | `int unsigned NOT NULL DEFAULT '0' COMMENT '最近命中时间'` |
| 6 | `search_update_time` | `int unsigned NOT NULL DEFAULT '0' COMMENT '添加时间'` |
| 7 | `search_result_count` | `int unsigned NOT NULL DEFAULT '0' COMMENT '结果Id数量'` |
| 8 | `search_result_ids` | `mediumtext CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '搜索结果Id列表，英文半角逗号分隔'` |

键/索引：`PRIMARY KEY (`search_key`)`；`KEY `search_field` (`search_field`)`；`KEY `search_update_time` (`search_update_time`)`；`KEY `search_hit_count` (`search_hit_count`)`；`KEY `search_last_hit_time` (`search_last_hit_time`)`

### T-26 `mac_website`（42 列）

`ENGINE=MyISAM AUTO_INCREMENT=1 DEFAULT CHARSET=utf8`

| # | 列 | 定义 |
| --- | --- | --- |
| 1 | `website_id` | `int(10) unsigned NOT NULL AUTO_INCREMENT` |
| 2 | `type_id` | `smallint(5) unsigned NOT NULL DEFAULT '0'` |
| 3 | `type_id_1` | `smallint(5) unsigned NOT NULL DEFAULT '0'` |
| 4 | `website_name` | `varchar(60) NOT NULL DEFAULT ''` |
| 5 | `website_sub` | `varchar(255) NOT NULL DEFAULT ''` |
| 6 | `website_en` | `varchar(255) NOT NULL DEFAULT ''` |
| 7 | `website_status` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 8 | `website_letter` | `char(1) NOT NULL DEFAULT ''` |
| 9 | `website_color` | `varchar(6) NOT NULL DEFAULT ''` |
| 10 | `website_lock` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 11 | `website_sort` | `int(10) NOT NULL DEFAULT '0'` |
| 12 | `website_jumpurl` | `varchar(255) NOT NULL DEFAULT ''` |
| 13 | `website_pic` | `varchar(1024) NOT NULL DEFAULT ''` |
| 14 | `website_pic_screenshot` | `text` |
| 15 | `website_logo` | `varchar(255) NOT NULL DEFAULT ''` |
| 16 | `website_area` | `varchar(20) NOT NULL DEFAULT ''` |
| 17 | `website_lang` | `varchar(10) NOT NULL DEFAULT ''` |
| 18 | `website_level` | `tinyint(1) unsigned NOT NULL DEFAULT '0'` |
| 19 | `website_time` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 20 | `website_time_add` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 21 | `website_time_hits` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 22 | `website_time_make` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 23 | `website_time_referer` | `int(10) unsigned NOT NULL DEFAULT '0'` |
| 24 | `website_hits` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 25 | `website_hits_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 26 | `website_hits_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 27 | `website_hits_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 28 | `website_score` | `decimal(3,1) unsigned NOT NULL DEFAULT '0.0'` |
| 29 | `website_score_all` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 30 | `website_score_num` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 31 | `website_up` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 32 | `website_down` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 33 | `website_referer` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 34 | `website_referer_day` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 35 | `website_referer_week` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 36 | `website_referer_month` | `mediumint(8) unsigned NOT NULL DEFAULT '0'` |
| 37 | `website_tag` | `varchar(100) NOT NULL DEFAULT ''` |
| 38 | `website_class` | `varchar(255) NOT NULL DEFAULT ''` |
| 39 | `website_remarks` | `varchar(100) NOT NULL DEFAULT ''` |
| 40 | `website_tpl` | `varchar(30) NOT NULL DEFAULT ''` |
| 41 | `website_blurb` | `varchar(255) NOT NULL DEFAULT ''` |
| 42 | `website_content` | `mediumtext NOT NULL` |

键/索引：`PRIMARY KEY (`website_id`)`；`KEY `type_id` (`type_id`)`；`KEY `type_id_1` (`type_id_1`)`；`KEY `website_name` (`website_name`)`；`KEY `website_en` (`website_en`)`；`KEY `website_letter` (`website_letter`)`；`KEY `website_sort` (`website_sort`)`；`KEY `website_lock` (`website_lock`)`；`KEY `website_time` (`website_time`)`；`KEY `website_time_add` (`website_time_add`)`；`KEY `website_time_referer` (`website_time_referer`)`；`KEY `website_hits` (`website_hits`)`；`KEY `website_hits_day` (`website_hits_day`)`；`KEY `website_hits_week` (`website_hits_week`)`；`KEY `website_hits_month` (`website_hits_month`)`；`KEY `website_time_make` (`website_time_make`)`；`KEY `website_score` (`website_score`)`；`KEY `website_score_all` (`website_score_all`)`；`KEY `website_score_num` (`website_score_num`)`；`KEY `website_up` (`website_up`)`；`KEY `website_down` (`website_down`)`；`KEY `website_level` (`website_level`)`；`KEY `website_tag` (`website_tag`)`；`KEY `website_class` (`website_class`)`；`KEY `website_referer` (`website_referer`)`；`KEY `website_referer_day` (`website_referer_day`)`；`KEY `website_referer_week` (`website_referer_week`)`；`KEY `website_referer_month` (`website_referer_month`)`
