## 视频搜索/筛选：一次性 MySQL 库性能、EXPLAIN 与表锁测量报告（MACC-19，T2）

> 依据：`docs/architecture/09-content-search-filter-plan.md` 第 6.5 节与第 8 节 T2。被测代码是父任务主线 `ce531c1`（含 MACC-18 的搜索 API/SQL），本任务没有修改任何业务代码、配置默认值、契约、PHP、DDL 或账号。
> 测量脚本：`backend/src/test/perf/`（`SearchPerf.java`、`run-all.sh`、`summarize.py`，用法见该目录 `README.md`，不进 `./mvnw verify`）。
> 原始结果：`docs/architecture/09-content-search-filter-perf-raw/`（下文表格由 `summarize.py` 从这些文件生成，可复算；表中"⚠"标出冷态错误码与热态出错次数，HTTP 列只在不全是 200 时给出状态码分布）。
> 所有数字都是**本机一次运行**的测量值（2026-10-15 18:26–19:40），不是吞吐或延迟承诺。

### 0. 结论

**建议：包含匹配（`keyword`、`classes`）保持关闭**（`MACCMS_CONTENT_SEARCH_ENABLED=false`，本任务未改）。依据：

1. **每个包含匹配请求都要扫描两遍候选行**（count 一遍、分页一遍）。50 万、100 万行时 EXPLAIN 为 `type=ALL`（全表扫描）+ `Using filesort`；10 万行时优化器选 `type_id` 范围 + MRR 回表，同样逐行读取全部候选行（第 4 节）。
2. **单个请求就超过参考目标**（方案 6.5："50 万行热态 p95 < 1 s"）：50 万行时有命中的关键字/`classes` 请求热态 p95 为 1.4–2.4 s（直连 DB 与经应用 HTTP 都是；无命中关键字只需 count，约 0.9–1.0 s；关键字+精确条件 `kw-low+area` 2.8–4.1 s），10 万行时也有 1.3–1.8 s；100 万行时 3.5–6.2 s，单条语句已接近 3 s 时限，**无并发时也会零星出现 503 和 500**（第 4 节）。
3. **MyISAM 表锁使点击计数 `UPDATE` 明显变慢**：只有 1 个低频关键字读者时，UPDATE p95 在 10 万/50 万/100 万行分别为 0.7–0.8 s / 1.1–1.2 s / 2.0–2.4 s；4 个读者时为 1.5–1.6 s / 1.6–1.8 s / 2.8–2.9 s。参考目标（"并发 4 时 UPDATE 等待 p95 < 1 s"）**三档都没达到**。UPDATE 等待的上界约 3 s，由 `MAX_EXECUTION_TIME(3000)` 限住；同一时刻最多有 4–18 个会话在等表锁（第 5 节）。
4. **发现缺陷：时限在 filesort 阶段触发时返回 HTTP 500 而不是 503**。MySQL 5.7.44 在排序阶段被 `MAX_EXECUTION_TIME` 中止时报 **1028**（`ER_FILSORT_ABORT`），不是 3024；`StatementTimeouts.isServerTimeout` 只认 3024，于是落到 `GlobalExceptionHandler` 变成 500。100 万行测量中出现 8 次 HTTP 500（基准 6 次、锁实验 2 次），直连同一类语句时至少记录到 9 次 1028（第 6.3 节）。开启前必须修复。（已由 MACC-22 修复为 503，见第 6.3 节末尾；其余结论不变，包含匹配仍保持关闭。）
5. 已在 1.0.0 上线的**不带条件的列表与分类列表也有同样的扫描与表锁问题**：100 万行时无条件列表 3.3–4.0 s、分类列表 2.9–3.3 s，且这两条语句没有 `MAX_EXECUTION_TIME`（只有 MyBatis 10 s 兜底），在写锁下要等到 10 s 才返回 503（第 4、6.1 节）。这不属于本任务范围，但影响 SSR 首页/分类页，建议队长另行评估。
6. 精确筛选：`level` 很快（≤ 0.17 s，选择性高的索引）；`area`/`lang`/`year` 走单列索引 `ref`（utf8 列与 utf8mb4 连接比较时**能用索引**，`key_len` 62/32，回答了方案 6.2 的待核实项），100 万行时 1.2–2.5 s；三项组合用 `index_merge` 交集，0.7–1.3 s。

**若队长/用户仍要开启**，按数据最低需要同时满足：① 先修复 1028 → 503 映射并补测试；② 线上 `mac_vod` 实测行数与行体积在 10 万行、约 3.6 KB/行量级以内；③ `MACCMS_CONTENT_SEARCH_MAX_CONCURRENT=1`；④ 接受每次搜索约 1.3–1.6 s，以及搜索期间 PHP 点击计数 UPDATE 被阻塞到约 1 s（p95）。超过 10 万行时，按方案 6.3 第 4 条，下一步只能是改变实现（FULLTEXT ngram 索引或 MyISAM→InnoDB，均需 DDL 批准；或外部搜索引擎），本报告**没有**测量这些替代方案。

### 1. 范围与安全证据

| 项 | 证据 |
|---|---|
| 管理连接 | 只用 `MACCMS_IT_ADMIN_URL`/`_USERNAME`/`_PASSWORD`（本机账号，值只在执行时的 shell 环境里，未写入任何文件、日志或命令行参数；被测应用经子进程环境变量拿到同一账号，连接池仍是只读会话） |
| 一次性库 | 冒烟库 `maccms_perf_20261015181652_9bd49014`（18:16:52 建、18:26:02 删）；正式库 `maccms_perf_20261015182611_e8dfd14e`（18:26:11 建、19:39:43 删）。库名创建前用 `^maccms_perf_[0-9]{14}_[0-9a-f]{8}$` 校验；删除后查 `information_schema.schemata` 确认不存在，且剩余 `maccms_perf_*` 库数为 0（`run.log` 末尾） |
| 共享开发库 | 没有以 `maccms_dev` 为默认库连接、没有读其数据。只在建库前、删库后各读一次 `information_schema.tables` 中 `maccms_dev` 的元数据（26 张表的引擎、行数、数据/索引长度、更新时间）：`guard-before.tsv` 与 `guard-after.tsv` 逐字相同（`run.log`："maccms_dev information_schema snapshot unchanged: true"） |
| 服务器 | 没有修改全局变量；冷态只执行 `FLUSH TABLES <一次性库>.mac_vod`。写操作（建表、灌数、`ANALYZE`、`DISABLE/ENABLE KEYS`、锁实验 UPDATE、`LOCK TABLES … WRITE`）都只作用于一次性库 |
| 默认值 | 包含匹配只在被测应用子进程里经环境变量开启；仓库 `application.yml` 默认 `false` 未改 |
| 数据 | 全部合成（固定种子，只依赖 `vod_id`），无真实用户、密钥或真实地址；图片/播放地址都在 `example.invalid` 下 |
| 进程 | 每个阶段启动一个应用子进程，结束时按该子进程 PID 结束；没有按进程名结束任何进程 |

### 2. 环境与数据

| 项 | 值 |
|---|---|
| 机器 | QEMU 虚拟机，AMD Ryzen 9 5950X，16 个逻辑 CPU，内存 32 GB（测量开始时可用约 15 GB），SATA 虚拟盘；Windows 11 IoT Enterprise LTSC 24H2 |
| MySQL | 5.7.44 Community（本机服务），`key_buffer_size` 8 MB、`query_cache_type` OFF、`concurrent_insert` AUTO、`low_priority_updates` OFF、`sort_buffer_size` 256 KB、`read_buffer_size` 128 KB、`tmp_table_size` 16 MB、`max_execution_time` 0（完整列表见 `s*-context.tsv`）。连接字符集 utf8mb4、表 utf8_general_ci |
| 应用 | `target/maccms-backend-0.1.0-SNAPSHOT.jar`（主线 `ce531c1` 打包），JDK 21.0.11，默认堆参数，Hikari 池 10，`max-concurrent` 4，`VodMapper` DEBUG 日志开启（用于 SQL 核对） |
| 表结构 | `application/install/sql/install.sql` 原样导入（MyISAM、Dynamic 行格式、单列索引） |
| 分类 | 30 个：1–25 可见；26 停用顶级、27 父分类停用、28 游客无列表权限、29 文章分类、30 停用子类 |
| 灌数 | 每档追加：`DISABLE KEYS` → 批量插入 → `ENABLE KEYS`（排序重建索引）→ `ANALYZE`。耗时：10 万 18 s + 14 s；→50 万 68 s + 101 s；→100 万 106 s + 187 s |

| 规模 | 数据文件 | 索引文件 | 平均行长 | `vod_status=1` 且分类可见（候选行） | `vod_content` / `vod_play_url` 平均字节 |
|---|---|---|---|---|---|
| 10 万 | 365 MB | 29 MB | 3653 B | 90,194 | 2031 / 1105 |
| 50 万 | 1.83 GB | 141 MB | 3654 B | 451,218 | 2031 / 1104 |
| 100 万 | 3.65 GB | 281 MB | 3654 B | 902,474 | 2031 / 1104 |

分布（100 万行，`s1m-context.tsv`）：`vod_status=1` 95%；约 5% 行落在不可见分类；`vod_level=0` 约 98%；area/lang/year 取 `application/extra/maccms.php:84-86` 的取值并偏斜（日本 10%、粤语 8%、2020 年 7.7%）；`vod_class` 1–3 个值（含"科幻" 16.9%）；名称为中文词组合，5% 英文，0.1% 含 `%`、`_`、`!`、`\`、`&`、`&amp;`、`,`、`<`、`"`、`'` 样本。关键字样本：高频"龙腾"（名称命中 99,921 行，约 10%）、低频"麒麟阁"（50 行，0.005%）、无命中"无此片名ZQX"、英文"Silent"（4,172 行）、实体变体"Tom & Jerry"（192 行，两组 LIKE 模式）。

### 3. 方法

- **形态**（15 种 × `time`/`hits` 倒序）：`base` 无条件、`category` 顶级分类 1（含 4 个子类）、`area`/`lang`/`year`/`level` 单项精确、`exact-combo`（大陆+国语+2020）、`kw-high`/`kw-low`/`kw-none`/`kw-en`/`kw-amp` 关键字、`classes`（科幻）、`kw-high+cat`（关键字+分类）、`kw-low+area`（关键字+精确）。`base`、`area`、`kw-high`、`classes` 另测第 50、1000 页（pageSize 20）。
- **SQL 一致性**：脚本按 `VodMapper.xml` 拼出 count 与分页语句（含 `/*+ MAX_EXECUTION_TIME(3000) */`），`sqlcheck` 用被测应用的 MyBatis DEBUG 日志逐条核对（去空白后的 SQL 文本 + 全部绑定值及类型）：三档各 58 条一致、0 条不一致；另 2 条是 total=0 时应用本就不执行的分页语句（`s*-sqlcheck.txt`）。
- **DB 测量**（直连，同一会话）：EXPLAIN（type/key/key_len/rows/filtered/Extra）；冷态 = `FLUSH TABLES <库>.mac_vod` 后首次 count+page（MyISAM 键缓存块随表关闭释放，**操作系统文件缓存仍是热的**——3.65 GB 数据小于可用内存）；热态 = 紧接着重复 N 次（冷态 < 0.1 s 时 N=30，< 1 s 时 10，否则 5；**N=5 时 p95 即最大值**）；`SHOW SESSION STATUS` 的 `Handler_read_*`、`Sort_*`、`Created_tmp_*`、`Select_*` 增量（已扣除 SHOW STATUS 自身开销）；全局 `Key_reads`。与应用一样，total=0 或偏移量越界时不执行分页语句。
- **HTTP 测量**：同一形态经应用 `GET /api/v1/videos` 顺序请求 N 次，记录状态码与耗时；应用进程 CPU 时间（`ProcessHandle`）与 `jcmd GC.heap_info`。
- **表锁实验**：固定 5 次/秒的 `UPDATE mac_vod SET vod_hits=vod_hits+1 WHERE vod_id=?`（随机 id，每次用独立连接，模拟彼此独立的 PHP 请求，被阻塞不影响下次调度），同时 0/1/4/8 个读者循环执行 `kw-low`（直连：count+page；HTTP：经应用，503 后隔 50 ms 重试），每档约 30 s；每 200 ms 采样 `information_schema.processlist` 中本库处于 `Waiting for table level lock` 的会话数；记录全局 `Table_locks_waited/immediate` 增量。
- **时限与 503**：另一会话 `LOCK TABLES mac_vod WRITE` 时，直连带时限语句、8 个并发关键字请求、精确筛选请求、无条件请求的结果与耗时。

### 4. EXPLAIN、计数与耗时（全部见 `s*-explain.txt`、`s*-bench.tsv`）

| 形态 | 10 万 | 50 万 / 100 万 |
|---|---|---|
| `base`、所有关键字/`classes` | `range` on `type_id`（rows≈94,664，Using index condition; Using where; Using MRR；分页另 Using filesort） | `ALL`（rows=全表，Using where；分页另 Using filesort） |
| `category`、`kw-high+cat` | `range` on `type_id` + MRR | `range` on `type_id`（rows 94,457 / 144,293）+ filesort |
| `area`/`lang`/`year`/`level` | `ref` on 对应单列索引（key_len 62/32/32/1）+ filesort | 同左 |
| `exact-combo` | `ref` on `vod_year` | `index_merge`，`Using intersect(vod_year,vod_area)` + filesort |
| `kw-low+area` | `ref` on `vod_area`，关键字在回表后过滤 | 同左（100 万行 rows≈310,612） |

- 10 万行时 `base` 一次请求 `Handler_read_next`=190,014、`Handler_read_rnd`=190,034（经索引逐行回表两遍）；50 万/100 万行改为全表扫描，`Handler_read_rnd_next`=1,000,002 / 2,000,002（两遍全表）。分页只有 20 行进入排序（`Sort_rows`=20），第 1000 页 `Sort_rows`=20,000 且出现 `Sort_merge_passes`=1。
- 所有形态 `Created_tmp_tables`、`Created_tmp_disk_tables` 增量为 0。冷态 `Key_reads` 只有几十到约 1,500 次，冷/热耗时接近：成本主要是读数据行，不是索引。
- 没有一种包含匹配形态能用索引缩小扫描范围；排序列索引（`vod_time`、`vod_hits`）在任何形态下都没有被选用。

#### 第 1 页，`sort=time&order=desc`（ms；DB = 直连执行 count+page，HTTP = 经应用的完整请求；热态）

| 形态 | 10 万 命中 | 10 万 DB p50/p95 | 10 万 HTTP p50/p95 | 50 万 命中 | 50 万 DB p50/p95 | 50 万 HTTP p50/p95 | 100 万 命中 | 100 万 DB p50/p95 | 100 万 HTTP p50/p95 |
|---|---|---|---|---|---|---|---|---|---|
| `base` | 90194 | 1618/1838 | 1410/1606 | 451218 | 2310/2542 | 2475/2776 | 902474 | 3650/3831 | 3594/3652 |
| `category` | 17987 | 266/315 | 280/376 | 90211 | 2316/2358 | 2171/2532 | 180463 | 3161/3326 | 3051/3320 |
| `area` | 9091 | 175/209 | 156/185 | 45155 | 1133/1234 | 1372/1508 | 90383 | 1564/1740 | 1605/1767 |
| `lang` | 7191 | 150/164 | 148/201 | 36274 | 835/1028 | 761/936 | 72326 | 1319/1359 | 1352/1395 |
| `year` | 6886 | 127/138 | 148/170 | 34800 | 723/835 | 786/833 | 69220 | 1192/1268 | 1224/1332 |
| `level` | 205 | 5/6 | 12/18 | 998 | 22/28 | 33/55 | 1930 | 87/126 | 115/149 |
| `exact-combo` | 964 | 120/139 | 131/169 | 4813 | 398/424 | 395/446 | 9560 | 914/1315 | 719/977 |
| `kw-high` | 8947 | 1568/1661 | 1563/1638 | 45079 | 1885/2360 | 1615/1744 | 90169 | 3707/3862 | 3653/4024 |
| `kw-low` | 5 | 1402/1462 | 1263/1305 | 23 | 1734/1980 | 1670/1673 | 46 | 3735/4211 | 3946/4483 |
| `kw-none` | 0 | 753/892 | 633/809 | 0 | 850/958 | 890/993 | 0 | 2116/2194 | 2124/2381 |
| `kw-en` | 387 | 1268/1303 | 1308/1354 | 1861 | 1615/1656 | 1687/1783 | 3773 | 4496/4725 | 4423/4549 |
| `kw-amp` | 12 | 1335/1413 | 1380/1607 | 87 | 1836/2065 | 1818/1878 | 168 | 6014/6027 ⚠5错 | 5541/6015 {200=3, 500=1, 503=1} |
| `classes` | 15077 | 1298/1329 | 1286/1459 | 76312 | 1512/1597 | 1571/1629 | 152657 | 3509/3666 | 3552/3934 |
| `kw-high+cat` | 1764 | 259/338 | 276/360 | 9054 | 1452/1627 | 1594/1768 | 18113 | 3664/3762 | 3554/3897 |
| `kw-low+area` | 2 | 562/602 | 500/651 | 10 | 3580/4054 | 3247/4042 | 15 | 5822/6023 ⚠page:E1028 ⚠2错 | 5723/5863 {200=2, 500=1, 503=2} |

#### 第 1 页，`sort=hits&order=desc`（ms；DB = 直连执行 count+page，HTTP = 经应用的完整请求；热态）

| 形态 | 10 万 命中 | 10 万 DB p50/p95 | 10 万 HTTP p50/p95 | 50 万 命中 | 50 万 DB p50/p95 | 50 万 HTTP p50/p95 | 100 万 命中 | 100 万 DB p50/p95 | 100 万 HTTP p50/p95 |
|---|---|---|---|---|---|---|---|---|---|
| `base` | 90194 | 1403/1478 | 1497/1587 | 451218 | 2471/2822 | 2661/2929 | 902474 | 3310/3634 | 3759/3789 |
| `category` | 17987 | 338/388 | 355/380 | 90211 | 2546/2654 | 2386/2673 | 180463 | 2895/3184 | 2879/2970 |
| `area` | 9091 | 162/192 | 165/202 | 45155 | 991/1018 | 976/1064 | 90383 | 1546/1667 | 1596/1664 |
| `lang` | 7191 | 141/164 | 147/177 | 36274 | 754/893 | 716/939 | 72326 | 1245/1435 | 1187/1344 |
| `year` | 6886 | 134/182 | 135/153 | 34800 | 720/768 | 716/774 | 69220 | 1376/1843 | 1637/2476 |
| `level` | 205 | 5/6 | 13/20 | 998 | 25/30 | 27/35 | 1930 | 99/132 | 86/167 |
| `exact-combo` | 964 | 124/153 | 139/203 | 4813 | 384/411 | 438/530 | 9560 | 727/796 | 729/859 |
| `kw-high` | 8947 | 1339/1379 | 1376/1510 | 45079 | 1895/1958 | 1842/1899 | 90169 | 3767/3848 | 3719/3738 |
| `kw-low` | 5 | 1236/1298 | 1325/1405 | 23 | 1646/1731 | 1699/1761 | 46 | 3753/4214 | 4391/6187 {200=4, 500=1} |
| `kw-none` | 0 | 728/908 | 645/712 | 0 | 896/948 | 839/897 | 0 | 2044/2478 | 2542/3012 {200=4, 503=1} |
| `kw-en` | 387 | 1262/1378 | 1278/1364 | 1861 | 1646/1808 | 1734/1753 | 3773 | 4546/4756 | 4758/5976 {200=4, 500=1} |
| `kw-amp` | 12 | 1357/1378 | 1674/1774 | 87 | 1889/2206 | 1962/2392 | 168 | 3008/3016 ⚠count:E3024 ⚠4错 | 4750/4940 |
| `classes` | 15077 | 1564/1608 | 1576/1811 | 76312 | 1469/1484 | 1428/1447 | 152657 | 3921/4594 | 3945/4886 |
| `kw-high+cat` | 1764 | 254/371 | 374/418 | 9054 | 1504/1576 | 1586/1850 | 18113 | 3716/3924 | 3600/4063 |
| `kw-low+area` | 2 | 450/512 | 505/551 | 10 | 2627/2805 | 2809/3188 | 15 | 3002/3015 ⚠count:E3024 ⚠3错 | 5444/5734 {200=3, 500=2} |

#### 深分页（第 50 / 1000 页，热态 DB count+page p50/p95，ms；total ≤ 偏移量时应用不执行分页语句）

| 形态 | 排序 | 10 万 p50 | 10 万 p1000 | 50 万 p50 | 50 万 p1000 | 100 万 p50 | 100 万 p1000 |
|---|---|---|---|---|---|---|---|
| `base` | time | 1869/2020 | 1627/2070 | 2420/2498 | 3332/3635 | 3666/4078 | 3973/4925 |
| `base` | hits | 1417/1628 | 1858/2100 | 2772/3030 | 2928/3264 | 3944/4196 | 3824/4043 |
| `area` | time | 169/225 | 83/106 | 1260/1309 | 1150/1256 | 1590/1913 | 2029/2058 |
| `area` | hits | 159/193 | 73/91 | 988/1182 | 1348/1461 | 1723/1844 | 1795/1938 |
| `kw-high` | time | 1621/1654 | 665/697 | 1632/1842 | 1821/1935 | 3543/3857 | 3712/4003 |
| `kw-high` | hits | 1515/1624 | 649/889 | 1674/1750 | 1885/2053 | 3646/4271 | 3952/4182 |
| `classes` | time | 1200/1305 | 780/841 | 1552/1575 | 1616/1740 | 3588/3598 | 4011/4380 |
| `classes` | hits | 1496/1718 | 683/753 | 1436/1515 | 1660/1854 | 3734/3930 | 4596/4733 |

#### 冷态（`FLUSH TABLES <库>.mac_vod` 后首次，count / page，ms）与 100 万行热态单条语句 p95

| 形态 | 10 万 冷 count/page | 50 万 冷 count/page | 100 万 冷 count/page | 100 万 热 count p95 | 100 万 热 page p95 |
|---|---|---|---|---|---|
| `base` | 1016/891 | 1316/1134 | 2019/1800 | 1897 | 2027 |
| `category` | 121/127 | 1224/1265 | 1476/1395 | 1736 | 1673 |
| `area` | 89/93 | 503/569 | 776/794 | 939 | 818 |
| `lang` | 82/85 | 458/481 | 677/687 | 665 | 733 |
| `year` | 58/59 | 303/280 | 561/604 | 628 | 647 |
| `level` | 3/3 | 13/11 | 31/25 | 63 | 75 |
| `exact-combo` | 87/86 | 204/190 | 476/523 | 695 | 620 |
| `kw-high` | 774/748 | 1128/1002 | 2041/1967 | 1915 | 2037 |
| `kw-low` | 608/619 | 790/898 | 1867/2208 | 2138 | 2073 |
| `kw-none` | 670/0 | 924/0 | 2091/0 | 2194 | 0 |
| `kw-en` | 719/638 | 788/788 | 2475/2232 | 2313 | 2684 |
| `kw-amp` | 707/686 | 1000/939 | 2598/2643 | 3024 | 3016 |
| `classes` | 650/619 | 672/655 | 1649/1791 | 1976 | 1782 |
| `kw-high+cat` | 136/140 | 808/695 | 2108/1879 | 2008 | 1898 |
| `kw-low+area` | 281/255 | 1490/1762 | 2962/3015 | 3015 | 3008 |

#### s1m：每次请求（count+page）的会话计数增量（热态平均，`sort=time`，第 1 页）

| 形态 | 计数增量 |
|---|---|
| `base` | Handler_read_rnd=20 Handler_read_rnd_next=2000002 Select_scan=2 Sort_rows=20 Sort_scan=1 |
| `category` | Handler_read_key=10 Handler_read_next=379992 Handler_read_rnd=20 Select_range=2 Sort_range=1 Sort_rows=20 |
| `area` | Handler_read_key=2 Handler_read_next=200098 Handler_read_rnd=20 Sort_range=1 Sort_rows=20 |
| `lang` | Handler_read_key=2 Handler_read_next=160196 Handler_read_rnd=20 Sort_range=1 Sort_rows=20 |
| `year` | Handler_read_key=2 Handler_read_next=153402 Handler_read_rnd=20 Sort_range=1 Sort_rows=20 |
| `level` | Handler_read_key=2 Handler_read_next=4304 Handler_read_rnd=20 Sort_range=1 Sort_rows=20 |
| `exact-combo` | Handler_read_key=4 Handler_read_next=851296 Handler_read_rnd=53590 Select_range=2 Sort_range=1 Sort_rows=20 |
| `kw-high` | Handler_read_rnd=20 Handler_read_rnd_next=2000002 Select_scan=2 Sort_rows=20 Sort_scan=1 |
| `kw-low` | Handler_read_rnd=20 Handler_read_rnd_next=2000002 Select_scan=2 Sort_rows=20 Sort_scan=1 |
| `kw-none` | Handler_read_rnd_next=1000001 Select_scan=1 |
| `kw-en` | Handler_read_rnd=20 Handler_read_rnd_next=2000002 Select_scan=2 Sort_rows=20 Sort_scan=1 |
| `kw-amp` | Handler_read_rnd_next=1844429 Select_scan=2 Sort_scan=1 |
| `classes` | Handler_read_rnd=20 Handler_read_rnd_next=2000002 Select_scan=2 Sort_rows=20 Sort_scan=1 |
| `kw-high+cat` | Handler_read_key=10 Handler_read_next=379992 Handler_read_rnd=20 Select_range=2 Sort_range=1 Sort_rows=20 |
| `kw-low+area` | Handler_read_key=2 Handler_read_next=672389 Handler_read_rnd=9 Sort_range=1 Sort_rows=9 |

### 5. 表锁实验

#### 表锁实验（UPDATE 固定 5 次/秒，每档约 30 s；读者为低频关键字 `kw-low`，`sort=time` 第 1 页）

| 规模 | 方式 | 读者 | UPDATE 次数 | UPDATE 延迟 p50/p95/max (ms) | 读完成数 | 读耗时 p50/p95/max (ms) | 读结果 | Table_locks_waited | 等锁会话 max/avg | 应用 CPU (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| 10 万 | update-only | 0 | 150 | 1/2/8 | 0 | 0/0/0 | {} | 0 | 0/0.00 | 0 |
| 10 万 | jdbc | 1 | 150 | 372/684/883 | 21 | 1439/1666/1774 | {ok:21} | 152 | 4/1.75 | 0 |
| 10 万 | jdbc | 4 | 150 | 784/1539/1902 | 40 | 3050/3798/3838 | {ok:40} | 226 | 11/4.21 | 0 |
| 10 万 | jdbc | 8 | 151 | 1574/2936/2994 | 85 | 3010/3018/5959 | {E1028:2, E3024:83} | 230 | 17/7.95 | 0 |
| 10 万 | http | 1 | 150 | 382/797/958 | 19 | 1512/2318/2318 | {200:19} | 148 | 5/2.03 | 2312 |
| 10 万 | http | 4 | 151 | 848/1644/1914 | 38 | 3306/3949/4538 | {200:38} | 201 | 12/4.39 | 2125 |
| 10 万 | http | 8 | 151 | 890/1733/1872 | 1954 | 2/5/3824 | {200:36, 503:1918} | 200 | 10/4.33 | 11859 |
| 50 万 | update-only | 0 | 151 | 1/2/7 | 0 | 0/0/0 | {} | 0 | 0/0.00 | 0 |
| 50 万 | jdbc | 1 | 151 | 578/1117/1277 | 14 | 2286/2701/2701 | {ok:14} | 171 | 6/2.72 | 0 |
| 50 万 | jdbc | 4 | 150 | 842/1580/1784 | 40 | 3238/3500/3513 | {ok:40} | 225 | 12/4.05 | 0 |
| 50 万 | jdbc | 8 | 150 | 1238/2372/2633 | 56 | 4934/5274/5279 | {ok:56} | 246 | 18/6.43 | 0 |
| 50 万 | http | 1 | 150 | 608/1197/1351 | 12 | 2538/2759/2759 | {200:12} | 147 | 7/3.03 | 2812 |
| 50 万 | http | 4 | 150 | 870/1797/2204 | 33 | 3632/4586/4677 | {200:32, 503:1} | 181 | 12/4.50 | 3156 |
| 50 万 | http | 8 | 151 | 1033/1956/2176 | 1957 | 4/6/4553 | {200:32, 503:1925} | 191 | 13/5.10 | 18250 |
| 100 万 | update-only | 0 | 151 | 1/2/8 | 0 | 0/0/0 | {} | 0 | 0/0.00 | 0 |
| 100 万 | jdbc | 1 | 150 | 1031/1958/2336 | 8 | 4002/4492/4492 | {ok:8} | 164 | 12/4.87 | 0 |
| 100 万 | jdbc | 4 | 150 | 1548/2932/2994 | 36 | 3014/5924/5939 | {E1028:6, E3024:28, ok:2} | 186 | 15/7.80 | 0 |
| 100 万 | jdbc | 8 | 150 | 1565/2922/2998 | 88 | 3012/3015/3017 | {E3024:88} | 230 | 15/7.97 | 0 |
| 100 万 | http | 1 | 150 | 1244/2444/2824 | 6 | 5052/5775/5775 | {200:6} | 149 | 14/6.15 | 1750 |
| 100 万 | http | 4 | 151 | 1377/2769/2976 | 32 | 3022/6208/6208 | {200:8, 500:2, 503:22} | 177 | 17/7.87 | 2781 |
| 100 万 | http | 8 | 151 | 1532/2908/3007 | 1945 | 4/7/3038 | {503:1945} | 154 | 15/7.69 | 10078 |

- `update-only` 基线：UPDATE p95 2 ms，`Table_locks_waited` 增量 0。
- 有读者时 UPDATE 必须等正在执行的 SELECT 结束；UPDATE 排队后，后到的 SELECT 又排在 UPDATE 之后（MyISAM 写优先），所以读者耗时也被拉长：100 万行 1 个 HTTP 读者时，单个关键字请求从约 3.9 s（第 4 节表，无写入时 HTTP p50）变为 5.1 s（p50）。
- 4/8 个读者时，UPDATE 最大等待约 3.0 s，与时限一致；读者大部分以 3024（直连）或 503（HTTP）结束，100 万行 4 个 HTTP 读者 30 s 内只有 8 次成功。
- HTTP 8 个客户端时，超过并发上限的请求立即 503（p50 2–4 ms），进入数据库的仍最多 4 个，所以 UPDATE 延迟与 4 个读者相近：**并发上限确实限住了同时持有表读锁的长查询数，但限不住每次锁住的时长**。被拒请求以 50 ms 间隔重试时，应用 CPU 约 10–18 s/30 s（日志里每次拒绝一条 WARN），说明对频繁重试的客户端需要在 nginx 层限速（方案 D2）。
- `Table_locks_waited` 是全局计数，可能含本机其他会话；测量期间本机没有其他已知的 MySQL 负载（基线 0 次也说明了这一点）。

### 6. 执行时限、并发上限与 503

#### 6.1 写锁下（100 万行，`s1m-timeout.txt`）

| 请求（另一会话持有 `LOCK TABLES mac_vod WRITE`） | 结果 |
|---|---|
| 直连带时限的 `kw-low` count | 3,037 ms 后错误 3024 |
| 8 个并发 `kw-low` 请求（上限 4） | 4 个 3.35–3.55 s 后 503（时限），4 个 8–45 ms 内 503（并发上限）；日志 4 条 `content search rejected: concurrency limit reached`、4 条 `database statement timeout` |
| `area` 精确筛选 | 3,040 ms 后 503（精确筛选也带时限，不占并发许可） |
| 无条件列表（1.0.0 语句，无时限） | 10,022 ms 后 503（MyBatis 10 s JDBC 超时，`QueryTimeoutException`） |
| 释放写锁后 `kw-low` | 200，4,350 ms |

#### 6.2 正常负载下的时限

100 万行时，包含匹配的单条 count 或分页语句热态 p95 为 1.8–3.0 s（第 4 节冷态表右两列），加上并发竞争即越过 3 s：基准中顺序执行就出现了 3024（`kw-amp`、`kw-low+area`），锁实验 4/8 个读者时绝大多数读请求以 3024/503 结束。

#### 6.3 缺陷：1028 → HTTP 500

| 证据 | 位置 |
|---|---|
| 100 万行基准：`kw-low` hits、`kw-en` hits、`kw-amp` time 各 1 次 HTTP 500，`kw-low+area` time 1 次、hits 2 次 HTTP 500 | `s1m-bench.tsv` 的 `http_status` 列；`run.log` |
| 100 万行锁实验：4 个 HTTP 读者时 2 次 500 | `s1m-lock.tsv` |
| 同期直连执行同一语句：`kw-low+area` 冷态分页语句 3 s 后错误 **1028**；锁实验直连读者 1028 共 8 次（10 万行 2 次、100 万行 6 次），其余为 3024 | `s1m-bench.tsv` `cold_error` 列；`s100k-lock.tsv`、`s1m-lock.tsv` |
| 应用日志：500 对应的 ERROR 为 `GlobalExceptionHandler: database access failed: UncategorizedSQLException`（不是 `ContentReadService` 的 `database statement timeout`），栈在 `ContentReadService.doListVideos`（分页语句） | 应用日志（未提交，`backend/target/perf/s1m-app.log`、`s1m-lock-app.log`） |

根因：MySQL 5.7 在 filesort 期间被 `MAX_EXECUTION_TIME` 中止时报 1028 `ER_FILSORT_ABORT`（"Sort aborted"），而 `backend/src/main/java/com/maccms/backend/content/infrastructure/StatementTimeouts.java` 只识别 3024。MACC-18 的集成测试用 `LOCK TABLES … WRITE` 制造超时，语句在等锁阶段被中止，只会得到 3024，所以没有覆盖到。应用日志按设计不记录错误码与 message，500 与 1028 的对应是按"同一语句、同一时段、直连得到 1028 + 应用走了非 3024 分支"推断的；在本次未加负载的单独复现中（`s1m-timeout.txt` 末尾，kw-amp 分页语句各 6 次）语句都在 3 s 内完成，没有复现。修复建议（未实施）：`ContentReadService.read` 把 1028 也归为 503（或只在带时限的语句上这样做），并用"排序阶段被中止"的集成测试锁定。

> **修复状态（MACC-22）**：`StatementTimeouts.isServerTimeout` 现按错误码 3024 或 1028 识别（不限于带时限的语句），1028 与 3024 返回同一 503 响应体、同一日志 `database statement timeout: <类型链>`。回归测试为异常链替身（`StatementTimeoutsTest`、`ContentSearchApiContractTest`、`ContentReadServiceUnavailableTest`）；"排序阶段被中止"的真实 MySQL 集成测试仍未做（本节单独复现未成功，无法稳定触发）。本节以上测量数据是修复前的记录，未重测。

### 7. Java 进程

| 规模 | 基准阶段应用 CPU / HTTP 请求数 | 每请求 CPU | 堆（G1，已提交 80 MB）使用前 → 后 |
|---|---|---|---|
| 10 万 | 13.6 s / 440 | 31 ms | 31 MB → 55 MB |
| 50 万 | 14.3 s / 320 | 45 ms | 33 MB → 55 MB |
| 100 万 | 16.3 s / 290 | 56 ms | 32 MB → 64 MB |

每请求 CPU 含 MyBatis DEBUG 日志、`jcmd` 附加和健康检查，只作量级参考。HTTP 耗时与直连 DB 耗时基本一致（第 4 节表），**瓶颈在 MySQL 扫描，不在应用**。没有使用 JFR。

### 8. 已测量与未验证

已测量（本机一次运行）：三档规模的 EXPLAIN、冷/热耗时、会话计数、HTTP 耗时与状态码、应用 CPU/堆、1/4/8 读者表锁实验、写锁下时限与并发上限、SQL 与 `VodMapper.xml` 一致性、共享库元数据不变。

未验证或不同于线上（结论须结合这些再判断）：

- 线上 MySQL 版本与配置、硬件、`mac_vod` 行数、行体积、数据分布、是否改过索引/引擎——全部**【待核实】**。本机 `key_buffer_size` 为默认 8 MB。
- **真正的冷缓存（数据不在操作系统缓存中）没有测**：3.65 GB 数据全部在内存里，磁盘读取下扫描会更慢。
- 只模拟了点击计数 UPDATE（5 次/秒）；采集批量写入、后台编辑、PHP 前台自身的查询负载、`concurrent_insert` 下的 INSERT 行为都没有测。
- 合成数据的索引由 `ENABLE KEYS` 一次性重建（紧凑），线上增量维护的索引可能更大。
- 每个形态 N=5–30 次，p95 在 N=5 时等于最大值；没有做吞吐压测，也不对外宣称吞吐。
- 1028 → 500 的单独复现未成功（见 6.3）；替代方案（FULLTEXT ngram、InnoDB、外部引擎、前缀匹配）都没有测。
- 旧 PHP 搜索（`application/common/model/Vod.php:462-468`）对同样的列做同样的 LIKE，线上是否开启、是否已有同样的成本**【待核实】**。

### 9. 复现

```bash
cd backend
./mvnw -q package -DskipTests
export MACCMS_IT_ADMIN_URL='jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&useSSL=false'
export MACCMS_IT_ADMIN_USERNAME=<本机管理账号>
export MACCMS_IT_ADMIN_PASSWORD=<密码>
bash src/test/perf/run-all.sh > target/perf-run.log 2>&1      # 本次约 73 分钟；退出时自动删除一次性库
python src/test/perf/summarize.py target/perf > target/perf/summary.md
```

本次正式运行之前做过一次 2 万行冒烟（库 `maccms_perf_20261015181652_9bd49014`，结果未采用，已删除）。冒烟之后脚本只改了两处：`sqlcheck` 把 total=0 时应用不执行的分页语句记为跳过；锁实验 HTTP 客户端 503 后隔 50 ms 重试。正式运行中途（100 万行基准发现 HTTP 500 后、`timeout` 步骤执行前）给 `timeout` 子命令追加了 kw-amp 分页语句的错误码统计，此前已完成的步骤不受影响。

原始文件：`docs/architecture/09-content-search-filter-perf-raw/` 下 `run.log`（全过程日志）、`s{100k,500k,1m}-{context,bench,explain,lock,jvm,sqlcheck}`、`s1m-timeout.txt`、`guard-{before,after}.tsv`。为通过 `git diff --check`，复制时统一为 LF 并去掉行尾空白（TSV 末尾的空字段因此被截掉），内容未做其他改动。应用日志较大且只用于 SQL 核对与 6.3 节，未提交。
