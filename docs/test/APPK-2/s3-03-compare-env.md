# APPK-2 S3-03 对照环境与快照脚本（只写阶段交付说明）

> 归属：测试人员-海外。本文件**只有脚本与离线自测结论**，不含任何执行结论、不含地址/账号/密码/令牌。
> 技术设计依据：`docs/design/APPK-2/tech-design.md` @ `9671aae6`（v15）§7.3 ENV-1～ENV-8、
> §6.20 SNAPD-1～SNAPD-5、附录 A.1 SNAP-1～SNAP-8、§7.4 C-6～C-10/C-62/C-63/C-66。

## 1. 这次补的是什么

上游提交 `7cf9cbc0` 只交了模板包校验（C-9）与 PRE-5 扫描器。S3-03 在技术设计里还挂了对照环境装配与
两个快照器，这些此前**不存在**，本提交补齐：

| 编号 | 文件 | 覆盖的设计条目 |
| --- | --- | --- |
| C-6 | `.gitignore`（新增 `.compare/`）+ `.compare/APPK-2/` 目录约定 | ENV-1 |
| C-7 | `tests/compare/APPK-2/maccms-overlay.json` | ENV-3（7 个键：`template_dir`/`html_dir`/`mob_template_dir`/`mob_html_dir`/`ads_dir`/`mob_ads_dir`/`mob_status`） |
| C-8 | `tests/compare/APPK-2/assemble_site.py` | ENV-1～ENV-4 |
| C-63 | 同上（`database.php` 会话时区、`vendor/` 摘要、ENV-5 回滚） | ENV-2、ENV-5、SNAPD-4、SNAPD-5 |
| C-10 | `tests/compare/APPK-2/php.ini` | ENV-8（PHP 运行参数） |
| C-62 | `tests/compare/APPK-2/data_snapshot.py` | SNAPD-1～SNAPD-3 |
| C-66 | `tests/compare/APPK-2/schema_snapshot.py` | SNAP-1～SNAP-8、V-21 |
| — | `tests/compare/APPK-2/selftest_all.py` | 三个脚本的离线自测总入口 |

## 2. 每个脚本负责什么、不负责什么

### `assemble_site.py`（C-8 / C-63）

做的事：

1. **ENV-1** 用 `git -c core.autocrlf=false archive <提交> php/` 导出 `php/` 的已跟踪文件到
   `pristine/`（**去掉 `php/template/`**），不从工作区复制——避免本机 `core.autocrlf=true`
   把 LF 改成 CRLF 触发 NOIGN-4。
2. **DEC-1** 把模板包 `third_party/maccms-template-v2020.1000.1035/template/{default_pc,default_wap}`
   放进 `pristine/template/`。
3. **ENV-2** `install.lock` 写固定内容 `APPK-2`；`database.php` 从环境变量
   `MACCMS_DB_URL`/`MACCMS_DB_USERNAME`/`MACCMS_DB_PASSWORD` 生成，并且
   **SNAPD-4** 把 `'params' => []` 换成
   `'params' => [PDO::MYSQL_ATTR_INIT_COMMAND => "SET time_zone='+08:00'"]`。
4. **SNAPD-5** 从仓库外生产备份恢复 `vendor/`，记录文件数与按路径排序后的 SHA-256 汇总值。
5. **ENV-3** 对 `maccms.php` 只按 C-7 的 7 个键做**整行字面替换**；替换前后都断言每个键在
   `site` 节里恰好出现一次；不重新序列化整个文件。
6. **ENV-4** 由 `pristine/` 完整复制出 `php-site/`、`java-site/`，输出 13 个 CS-CONFIG 文件的
   SHA-256 并断言三方逐个相同（PHPB-9 / V-3）。
7. **ENV-5**（`check-configs` 子命令）每个请求单元开始前比对并回滚 13 个 CS-CONFIG，
   输出 `restored` 清单供恢复记录引用。

不做的事：不连数据库（除了把库名/账号写进生成的配置文件）、不启动任何服务、不发 HTTP 请求。
**`--db-url` 一类的取值只从环境变量读，报告里只出现“已设置/未设置”，不打印取值。**

### `data_snapshot.py`（C-62）

* `dump`：用技术设计逐字规定的 5 个参数
  （`--no-create-info --skip-triggers --complete-insert --hex-blob --skip-extended-insert`）
  导出 26 张表的数据到 `.compare/APPK-2/data-snapshot.sql`，并把 `CHECKSUM TABLE` 与每表
  `AUTO_INCREMENT` 记进 `data-snapshot.json`。快照先写 `.part` 再原子替换，避免半截文件被当成有效快照。
* `restore`：`DELETE FROM` 26 张表 → 导入快照 → 按 JSON 复位 `AUTO_INCREMENT` → 重算 checksum 比对；
  **不一致重试一次，第二次仍不一致判“环境失败”**，并把每次尝试追加到恢复记录 JSONL（SNAPD-3）。
* `verify`：只读比对当前库与快照。
* 密码通过 600 权限的临时 `--defaults-file` 传给客户端，**不进命令行参数**（进程列表里看不到）。

### `schema_snapshot.py`（C-66）

* `snapshot`：取 SNAP-1（库字符集/排序规则）、SNAP-2（26 张表属性）、SNAP-3（列定义，基线 503 列）、
  SNAP-4（索引）、SNAP-5（约束，基线外键 0 个）、SNAP-6（触发器/视图/存储过程/事件，基线各 0 个）、
  SNAP-7（`SHOW CREATE TABLE`，**抹掉 `AUTO_INCREMENT=<n>`**）、SNAP-8（环境头，**只记录不比对**）。
* `diff`：两份快照逐项摊平比对，输出 `docs/test/APPK-2/schema-diff-<时点>.md`，返回 diff 行数；
  KPI-2 要求 **0**。
* SNAP-7 的长文本在报告里只标“有差异”，全文留在两份快照 JSON 里，避免表格被上万字撑爆。

## 3. 离线自测：跑了什么、结果

命令与结果（工作目录 = 仓库根，本机 Python 3.13.14、PHP 7.3.33 与 8.0.30、MySQL 5.7.44）：

```text
$ python tests/compare/APPK-2/selftest_all.py
PASS C-8/C-63 assemble_site.py    24/24 项 (rc=0)
PASS C-62 data_snapshot.py        37/37 项 (rc=0)
PASS C-66 schema_snapshot.py      34/34 项 (rc=0)
PASS C-6 .compare/ 已 gitignore
PASS C-10 php.ini 生效（php.EXE）
PASS 脚本内无明文密码

总判定：PASS
```

三个脚本各自的 `selftest` 可以单独跑：

```text
$ python tests/compare/APPK-2/assemble_site.py selftest    # 24/24
$ python tests/compare/APPK-2/data_snapshot.py selftest    # 37/37
$ python tests/compare/APPK-2/schema_snapshot.py selftest  # 34/34
```

自测覆盖的**反面用例**（证明脚本会报错，而不只是能跑通）：

* C-7 的键在 `maccms.php` 里出现两次 → 断言失败；
* `database.php` 缺环境变量、`MACCMS_DB_URL` 不是 `jdbc:mysql://` → 断言失败；
* `vendor/` 备份里出现 `../` 路径穿越条目 → 中止解压；
* 库名是 `maccms_dev` / `mysql` / `maccms_prod` → 拒绝连接；
* 数据快照：checksum 变了、`AUTO_INCREMENT` 变了、缺一张表 → 各自产出差异行；
* 结构快照：表数少 1、列数 502、外键 1 个、触发器 1 个、缺表 → 各自被基线自检检出；
* SNAP-8 的 `session_time_zone` 从 `+08:00` 改成 `SYSTEM` → **不产生 diff**（它只记录，不该制造假差异）。

## 4. 已在本机做过的真机核对（一次性库，已删除）

> 这些是**脚本可用性**核对，不是 T3 执行结论，也不对任何被测实现下判断。
> 用一次性库 `maccms_it_s303_*`（导入 `install.sql`），核对完立即 `DROP DATABASE`。

| 核对 | 结果 |
| --- | --- |
| `schema_snapshot.py snapshot` | 表 26、列 503、外键 0 —— **与附录 A.1 基线完全一致** |
| 同一库连取两次快照再 `diff` | diff 行数 **0** |
| 人为 `ALTER TABLE mac_vod ADD COLUMN` 后再 `diff` | diff 行数 **6**（5 行列属性 + 1 行建表语句），检出正确 |
| `data_snapshot.py dump` | 26 张表，快照落盘并记 checksum |
| `restore` 往返（未改数据） | 一致，尝试 1 次 |
| 人为插一行后再 `verify` | 报 2 项差异（checksum + `AUTO_INCREMENT`），`restore` 后恢复一致 |
| 真实生产备份 `vendor/` 摘要 | 110 个文件，SHA-256 汇总值 `d7b0866a160bb15c0e8c485da5083047aa1d634a7229565c9e1d029e313d4d9c` |
| `assemble_site.py assemble` 真跑 | `php/` 908 个文件、模板包 274 个（`default_pc` 147 / `default_wap` 127）、13 个 CS-CONFIG 三方同哈希 |

顺手修掉的两个真实缺陷（都是自测与真机核对发现的）：

1. `REPO_ROOT` 少算一级，导致 `git archive` 在错误目录下执行；
2. 快照器的 26 张表清单是照 `install.sql` 猜的（含 `mac_admin_auth` 等不存在的表），
   真机一跑就 `Table doesn't exist`；已换成从真实库 `information_schema` 核出的清单
   （`mac_actor`…`mac_website`），改后真机 26/503/0 一次通过。

## 5. BLOCKED：本阶段没有做、也不能声称做了的部分

| 项 | 状态 | 缺什么 / 谁提供 |
| --- | --- | --- |
| T3 对测试环境执行 | **BLOCKED** | G2.5 未通过；且 `MULTICA.md` 记录“未登记测试环境”、版本探针只有 `/actuator/health` 没有版本号 |
| 两侧站点真正起起来做逐页比对 | **BLOCKED** | 同上；需要 智能调度 在 Issue 写“G2.5 PASS @提交号 测试环境地址” |
| PHP 侧首页/分类页渲染 | **BLOCKED** | 旧站 `template/` 在工作树里仍是空的，只能靠 DEC-1 模板包；真机页面渲染未跑（属执行阶段） |
| Java 侧构建与 `SchemaGuardTest` | **BLOCKED** | 未在本阶段跑（属 T3 / G2 证据范围） |
| 模板包来源提交 | **注意** | `third_party/` 只在 `feature/APPK-2-be-php-template-migration` 上；本分支没有。所以 `assemble` 支持 `--php-commit` 与 `--template-commit` 分开指定，S3-01 合入后两者可相同 |
| `mysqldump`/`mysql` 版本 | **已知差异** | 本机是 MySQL 5.7 客户端；线上库版本未核实（属“线上核实”范畴，未获批准） |
| `data-snapshot.json` / `schema-*.json` 落仓库 | **未提交** | 它们是执行阶段产物，本阶段不生成；`.compare/` 已 gitignore |

## 6. 复核人怎么复现

```bash
# 1. 离线自测（不需要数据库、不需要仓库外备份）
python tests/compare/APPK-2/selftest_all.py

# 2. 单独看某个脚本
python tests/compare/APPK-2/assemble_site.py selftest
python tests/compare/APPK-2/data_snapshot.py selftest
python tests/compare/APPK-2/schema_snapshot.py selftest

# 3. 真跑装配（需要三条 MACCMS_DB_* 环境变量指向一次性库；只在执行阶段做）
export MACCMS_DB_URL='jdbc:mysql://127.0.0.1:3306/maccms_it_xxx'
export MACCMS_DB_USERNAME=...
export MACCMS_DB_PASSWORD=...
python tests/compare/APPK-2/assemble_site.py assemble \
    --commit <被测提交> --template-commit <带模板包的提交> \
    --report docs/test/APPK-2/php-baseline-report.md

# 4. 起 PHP 侧（ENV-8 口径）
php -c tests/compare/APPK-2/php.ini -d extension_dir=<php 7.3 ext 目录> \
    -S 127.0.0.1:18180 -t .compare/APPK-2/php-site
```

**注意**：第 3、4 步是执行阶段动作，本单（只写）**没有**把它们当成已完成的结论。
