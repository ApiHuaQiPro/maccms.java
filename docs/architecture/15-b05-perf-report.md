## B05 中规模合成库测量：IN/OR 多值、年份区间、随机候选与旧标签条件（MACC-29）

> 依据：MACC-29 补充验收"增加一次中规模合成库测量，记录 IN/OR、年份区间和随机候选的耗时/EXPLAIN"。被测 SQL 是分支 `agent/claude/macc-29` 提交 `6f5e2af` 的 `backend/src/main/resources/mapper/content/VodMapper.xml`（`publishedInTypes` + `matchingConditions` + `legacyConditions` + `pageOrderAndLimit`），条件构造照 `VideoFilter`、`VideoLegacyConditions`、`ContentReadService.criteria/randomPage`。本次没有修改任何业务代码、配置默认值、契约、PHP、DDL 或账号。
> 测量脚本：`backend/src/test/perf/B05Perf.java`（Java 单文件，不进 `./mvnw verify`，用法见文件头注释；风格与约束同 `SearchPerf.java`）。
> 原始结果：`docs/architecture/15-b05-perf-raw/`（下文数字都可以在那里查到）。
> 所有数字都是**本机一次运行**的测量值（2026-10-17 01:58–02:49），而且测量期间机器**没有空闲**（见第 2 节），只能当量级和相对比较来看，不是延迟承诺。

### 0. 结论

1. **选择性高的条件很快**：`ids`（100 个 ID，主键 range + MRR）count+第 1 页热态 p50 约 10 ms；`timeadd` 近 30 天（182 行，`vod_time_add` range）约 20–30 ms；单个年份（`vod_year` ref，9692 行）约 0.3 s；随机排序算法一（计数 601 行）约 0.5 s，随机页的偏移对耗时几乎没有影响。
2. **低选择性列上的 IN 多值反而最慢，会触发 3 s 时限（503）**。`area` 3/7/10 项（命中可见行的 35%–59%）、`isend=1`（55%）、`hits` 区间/`gt`（29%–33%）、`year=2000-2010`（35%）这几类，优化器都选了对应的单列二级索引（`range`/`ref`），然后在 1 GB 的 MyISAM 数据文件里逐行回表读 9 万–20 万行。单条 count 就要 2.5–3 s 以上，`area` 7/10 项、`isend=1` 的每次请求都在 count 处报 **3024**，`hits gt` 的分页还出现过 **1028**（filesort 阶段中止）。去掉时限后 count+分页要 5–16 s。经应用实测：`area=日本,韩国,美国` 5 次里 3 次 503，`year=2000-2010` 5 次全是 503。
3. **对照实验**：同一条 `area IN (3 项)` 的 count，按应用 SQL 走 `vod_area` range 时用 1.7–2.5 s，加 `IGNORE INDEX (vod_area)` 改成全表扫描只要 1.0–1.3 s（各 3 次，`control-area3.txt`）。所以第 2 条的慢是访问路径造成的（大比例二级索引回表比顺序扫描还慢），**不是 IN 列表项数本身**。年份区间也印证了这一点：`1990-2021`（32 项、97% 的行）优化器改用全表扫描，1.7 s，反而比 `2000-2010`（11 项）的 3.0–5.0 s 快。
4. **OR**：`classes` 的 LIKE OR 一律是全表扫描（`type=ALL`），1 项和 3 项耗时相同（count+第 1 页约 1.4–1.5 s），扫描本身占主导，模式数的影响可以忽略；`actor`/`director` 2 项也是全表扫描，3.2–4.4 s，但这一段测量时系统 CPU 负载接近 100%，不能和 `classes`（负载 0.2–0.3）直接比较。SSR 的 `tid` 条件 `(type_id = ? OR type_id_1 = ?)` 用 `index_merge` union，回表行数等于命中行数：顶级分类（5.4 万行）2.7–3.8 s，子分类（1.3 万行）0.7–0.8 s。
5. **随机排序算法二**的耗时几乎全在第一条计数：无条件时是 1.0.0 的 `countPublished`（全表扫描，约 1.4–1.8 s），加上条件时就是该条件 count 的耗时。`selectIdRange` 约 1–2 ms（`Select tables optimized away`）；1000 个候选 ID 的 count 和分页走主键 range + MRR，各 40–110 ms。候选命中：无条件 901/1000、`area=大陆` 324、`classes=科幻` 145（全表 ID 1..300000 连续，所以旧算法的"下标当 ID"缺陷在这里只少一个 ID）。
6. 旧标签条件：`not` 100 个 ID（`NOT IN`）是全表扫描，2.2–3.1 s，和无条件列表同一个量级；三项组合 `timeadd 365 天 + hits > 200000 + isend=1` 优化器选 `vod_time_add` range，1.3 s。
7. 作为参照：不带条件的列表（1.0.0 语句，没有执行时限）count+第 1 页是 2.4–4.6 s，和 MACC-19（09 报告第 0 节第 5 条）"无条件列表也有扫描问题"的结论一致。

**建议**（只陈述数据含义，是否采取需要队长裁定）：

- 30 万行时，大比例命中的精确条件（`area`/`lang` 多值、年份区间、`isend`、`hits*` 区间）在本机负载下经常超过 3 s 时限，SSR 页面和旧标签区块会返回 503。线上真实行数和行体积需要先核实（09 报告第 0 节的前提同样适用）。
- 不做 DDL 也有可选的方向：对这些低选择性列在 SQL 中加 `IGNORE INDEX`，或者在 application 层按候选比例选择写法。这会改变 SQL 文本，需要先单独测量、再评审，本次**没有**实现或测量。
- 带 `ids`、近期 `timeadd`、单个年份、小分类 `tid` 的区块成本低，可以按现有护栏使用。

### 1. 范围与安全证据

| 项 | 证据 |
|---|---|
| 管理连接 | 只用 `MACCMS_IT_ADMIN_URL`/`_USERNAME`/`_PASSWORD`（本机账号，值只在执行命令的 shell 环境变量中，没有写进任何文件、日志或命令行参数；被测应用子进程经环境变量拿到同一账号，连接池仍是只读会话） |
| 一次性库 | `maccms_it_20261017015845_4804bd6c`（01:58:45 创建，02:49:00 删除）。库名创建前按 `^maccms_it_[0-9]{14}_[0-9a-f]{8}$` 校验；导入 `install.sql` 前确认该库没有任何表；删除后查 `information_schema.schemata`，结果为 0（`run.log` 末行，另用 mysql 客户端复查也是 0） |
| 其他库 | 没有连接、读取或修改 `maccms_dev` 和其他任何库：脚本不读其他库的数据或元数据（与 SearchPerf 不同，不做 `maccms_dev` 的 information_schema 快照） |
| 服务器 | 没有改全局变量。DDL 只有 `install.sql` 原样导入（在新建的空一次性库里），外加 `DISABLE/ENABLE KEYS`、`ANALYZE`；冷态只执行 `FLUSH TABLES <一次性库>.mac_vod` |
| 默认值 | 包含匹配只在被测应用子进程里经 `MACCMS_CONTENT_SEARCH_ENABLED=true` 打开；仓库默认值没有改 |
| 数据 | 全部合成（固定种子，只依赖 `vod_id`），没有真实用户、密钥或地址；图片和播放地址都在 `example.invalid` 下 |
| 进程 | 应用子进程启动两次（PID 14612、23652），都按 PID 结束；没有按进程名结束任何进程 |
| 被测应用 | 用 `git archive 6f5e2af backend` 解到已被忽略的 `backend/target/b05src/`，离线打包（`./mvnw -o -q package -DskipTests`）；没有切分支，也没有改其他 worktree |

### 2. 环境与数据

| 项 | 值 |
|---|---|
| 机器 | AMD64 Family 25 Model 33（与 09 报告是同一台 QEMU 虚拟机，Ryzen 9 5950X），16 个逻辑 CPU，内存 32 GB（开始时可用约 10.8 GB）；Windows 11 IoT Enterprise LTSC |
| **负载** | 测量期间**机器不空闲**：Windows Defender（MsMpEng，约 3 个核心，推测在扫描刚写入的约 1 GB 数据文件）和其他智能体的进程同时在跑，开始时 `Win32_Processor.LoadPercentage` 97–100。每行结果都记录了热态循环期间的系统 CPU 负载（`sys_cpu_load` 列），范围 0.21–1.00。同一形态前后两次测量相差可达 30%（无条件列表：02:12 为 3.7/4.6 s，02:45 复测为 2.8/3.0 s，`s300k-rerun-bench.tsv`）。 |
| MySQL | 5.7.44 Community（本机服务），`key_buffer_size` 8 MB、`query_cache_type` OFF、`sort_buffer_size` 256 KB、`read_buffer_size` 128 KB、`eq_range_index_dive_limit` 200、`max_execution_time` 0；连接字符集 utf8mb4，表是 utf8_general_ci（`s300k-context.tsv`） |
| JDBC / JDK | mysql-connector-j 8.2.0，JDK 21.0.11 |
| 表结构 | `install.sql` 原样导入（MyISAM、Dynamic、单列索引；`vod_area/lang/year/letter/state/version/isend/hits/time_add/type_id/type_id_1` 各有单列索引） |
| 行数 | `mac_vod` 300000 行（ID 1..300000 连续）；`vod_status=1` 284997；可见（`vod_status=1` 且分类 1..25）270766 |
| 行体积 | 平均 3653 B/行（`vod_content` 平均 2032 B，`vod_play_url` 平均 1104 B）；数据文件 1.05 GB，索引 80 MB |
| 灌数 | 插入 165.6 s，`ENABLE KEYS` 144.7 s |

可见行的数据分布（完整分布见 `s300k-context.tsv`）：

| 列 | 分布 |
|---|---|
| 分类 | 30 个（与 SearchPerf 相同）：92% 在子类 6–25，3% 在顶级 1–5，5% 在不可见分类 26–30 |
| `vod_area` | 大陆 95002、美国 40368、日本 27194、韩国 27077、香港 21561、台湾 16304……最少的 6 个各约 2600–2800 |
| `vod_lang` | 国语 108459、英语 54302、韩语 27121、日语 27055、粤语 21577…… |
| `vod_year` | 2000–2021，越近越多（2021 年 38468、2010 年 9692、2000 年 7767），2% 为空 |
| `vod_letter` | A–Z 各约 1 万；`0`–`9` 各约 1300（合计 13420） |
| `vod_state` | 正片 216404、预告片 21942、空 21547、花絮 10873 |
| `vod_version` | 高清版 135332、超清版 53956、空 27268、蓝光版 26977、TC 13753、抢先版 13480 |
| `vod_isend` | 1：149048，0：121718 |
| `vod_class` | 1–3 个类目，偏斜同 SearchPerf（科幻约 17%） |
| `vod_actor` / `vod_director` | 30 个姓名中取 3 个 / 取 1 个 |
| `vod_hits` | `rand^4 × 10^6`；`vod_hits_week` 独立取 `rand^4 × 5×10^4` |
| `vod_time` / `vod_time_add` | 固定"当前时间"往前 5 年内；`time_add` 比 `time` 早 0–1 年 |

### 3. 方法与语句形态

- **一次请求 = 应用实际执行的语句序列**（`ContentReadService.doListVideos` / `randomPage`）：
  - 普通列表：`countPublishedMatching` → `selectPublishedMatchingPage`（不带条件时是 1.0.0 的 `countPublished` → `selectPublishedPage`，没有执行时限）；count ≤ 偏移量时不执行分页，与应用相同。
  - 随机算法一（计数 ≤ 2000）：count → 随机页（`hits_week` 倒序，偏移 = (1..c/20+1 中随机取的页 − 1) × 20，每次迭代重新取）；另测固定第 1 页和最后一页。
  - 随机算法二（计数 > 2000）：count（无条件时是 `countPublished`）→ `selectIdRange` → count(条件 + 1000 个候选) → page(同上，`hits_week` 倒序)。候选按 `VideoRandom.sampleIndexes` 的 Floyd 抽样，从下标 0..max−min 中取，**每次迭代重新抽**（固定种子）。
- **SQL 文本**：可见性限制 `vod_status = 1 AND type_id IN (?×25)`，条件片段按 XML 顺序追加，带条件时有 `/*+ MAX_EXECUTION_TIME(3000) */`，排序为 `ORDER BY <列> DESC, vod_id DESC LIMIT ?, ?`，页大小 20。值全部绑定。`explain` 文件里每条语句都附带了文本（列清单缩写为 `<summaryColumns>`，长 IN 列表缩写为 `(?×N)`）。
- **核对**：经应用（`http` 子命令）时开 `VodMapper` DEBUG 日志，把 16 个 API 可达形态的应用 SQL 和脚本 SQL 都去掉全部空白后逐字比较：**全部一致**（普通形态 2/2，随机算法二 4/4，`s300k-http.tsv` 末列）。B05 旧标签条件和 `tid` 只有内部入口，它们的文本照抄 XML，没有经应用核对。
- **冷态**：执行 `FLUSH TABLES <库>.mac_vod` 后跑第一次（会释放 MyISAM 键缓存块，操作系统文件缓存不受影响）。**热态**：冷态耗时 < 1 s 时先预热一次，然后跑 n 次：冷态 < 0.1 s 取 n=20，< 1 s 取 10，< 2 s 取 5，否则取 3（时间预算有限，n=3 时 p95 就是最大值）。
- **会话计数**：`SHOW SESSION STATUS` 的 `Handler_read_*`、`Sort_*`、`Select_*`，已扣除 SHOW 语句自身的开销，取热态每次请求的平均值。
- **超时**：语句报 3024/1028 时记录错误码，继续执行后续语句。应用在 count 超时后会直接返回 503，所以出错行的热态耗时（约 6 s = 两条语句各 3 s）**高于**应用实际的 503 耗时（约 3 s）。出错时另外不带时限跑一次，记为 `uncapped_ms`。
- **深分页**：命中 > 10020 行时测 `time` 倒序、偏移 10000（时间预算有限，`hits` 深分页只在冒烟时测过无条件列表一次：4.5/5.1 s，见 `run.log` 02:07）。

### 4. EXPLAIN（count 语句；分页语句的访问路径相同，另加 `Using filesort`）

| 形态 | 条件（绑定值） | total | type | key | rows | Extra |
|---|---|---|---|---|---|---|
| base | 无（1.0.0） | 270766 | ALL | — | 300000 | Using where |
| a.area1 | `vod_area = '日本'` | 27194 | ref | vod_area | 30079 | Using where |
| a.area3 | `vod_area IN (日本,韩国,美国)` | 94639 | range | vod_area | 103644 | ICP; Using where |
| a.area7 | `vod_area IN (美国…英国 7 项)`（API 上限：20 码点） | 148871 | range | vod_area | 171189 | ICP; Using where |
| a.area10 | `vod_area IN (10 项)`（仅语句层） | 159656 | range | vod_area | 185542 | ICP; Using where |
| a.lang3 | `vod_lang IN (粤语,韩语,日语)` | 75753 | range | vod_lang | 100633 | ICP; Using where |
| a.letter3 | `vod_letter IN (A,B,C)` | 29634 | range | vod_letter | 32468 | ICP; Using where |
| a.letter0-9 | `vod_letter IN ('0'..'9')` | 13420 | range | vod_letter | 14740 | ICP; Using where |
| a.state2 | `vod_state IN (预告片,花絮)` | 32815 | range | vod_state | 51511 | ICP; Using where |
| a.version2 | `vod_version IN (蓝光版,抢先版)` | 40457 | range | vod_version | 46570 | ICP; Using where |
| a.ids100 | `vod_id IN (100 个)` | 90 | range | PRIMARY | 100 | ICP; Using where; Using MRR |
| b.class1 | `vod_class LIKE '%科幻%' ESCAPE '!'` | 45761 | ALL | — | 300000 | Using where |
| b.class3 | `(vod_class LIKE … OR …)` 科幻/战争/武侠 | 95746 | ALL | — | 300000 | Using where |
| b.actor2 | `(vod_actor LIKE '%张伟%' … OR '%王芳%')` | 50620 | ALL | — | 300000 | Using where |
| b.director2 | 同上，`vod_director` | 17939 | ALL | — | 300000 | Using where |
| b.tid-top | `(type_id = 1 OR type_id_1 = 1)` | 54076 | index_merge | type_id,type_id_1 | 56677 | Using union(type_id,type_id_1) |
| b.tid-child | `(type_id = 7 OR type_id_1 = 7)` | 13042 | index_merge | type_id,type_id_1 | 13723 | Using union(type_id,type_id_1) |
| c.year1 | `vod_year = '2010'` | 9692 | ref | vod_year | 11035 | Using where |
| c.year2000-2010 | `vod_year IN ('2000'..'2010')` | 93582 | range | vod_year | 107089 | ICP; Using where |
| c.year1990-2021 | `vod_year IN (32 项)` | 265339 | ALL | — | 300000 | Using where |
| d.rnd1 | `vod_area IN (新加坡,印度) AND vod_letter IN (Q,X,Z)` | 601 | range | vod_area | 7105 | ICP; Using where |
| d.rnd2 候选段 | 原条件 `AND vod_id IN (1000 个)` | 145–901 | range | PRIMARY | 1000 | ICP; Using where; Using MRR |
| d.idRange | `MIN/MAX(vod_id)` | — | — | — | — | Select tables optimized away |
| e.hits-between | `vod_hits BETWEEN 1000 AND 50000` | 79560 | range | vod_hits | 87355 | ICP; Using where |
| e.hits-gt | `vod_hits > 200000` | 90307 | range | vod_hits | 101789 | ICP; Using where |
| e.timeadd-30d | `vod_time_add > NOW−30d` | 182 | range | vod_time_add | 411 | ICP; Using where |
| e.timeadd-365d | `vod_time_add > NOW−365d` | 27046 | range | vod_time_add | 31800 | ICP; Using where |
| e.isend1 | `vod_isend = 1` | 149048 | ref | vod_isend | 168276 | Using where |
| e.notin100 | `vod_id NOT IN (100 个)` | 270676 | ALL | — | 300000 | Using where |
| e.combo | `vod_time_add > NOW−365d AND vod_hits > 200000 AND vod_isend = 1` | 4893 | range | vod_time_add | 31800 | ICP; Using where |

ICP = Using index condition。utf8 列和 utf8mb4 连接比较时可以用索引（key_len 62/32/92/3），与 09 报告相同。

### 5. 耗时（count + 分页，ms；热态 p50 / p95；"⚠"表示有语句报时限错误）

#### 5.1 a. IN 多值与 ids

| 形态 | total | 冷态 time 第 1 页 | time 第 1 页 | hits 第 1 页 | time 偏移 10000 | CPU 负载 | 每次请求 Handler 计数（time 第 1 页） |
|---|---|---|---|---|---|---|---|
| base（参照） | 270766 | 3532 | 3656 / 4011 | 2980 / 3102 | 4583 / 5319 | 0.72–0.91 | read_rnd_next=600002（两遍全表） |
| a.area1 | 27194 | 1703 | 1564 / 1956 | 1522 / 2495 | 1877 / 2162 | 0.66–0.74 | read_next=60294 |
| a.area3 | 94639 | 5371 ⚠ | 5674 / 6350 ⚠ | 5823 / 6030 ⚠ | 6255 / 6462 ⚠ | 0.70–1.00 | read_next=209794 |
| a.area7 | 148871 | 6025 ⚠ | 6028 / 6052 ⚠（全部超时） | 6040 / 6081 ⚠ | 6026 / 6034 ⚠ | ≈1.0 | — |
| a.area10 | 159656 | 6036 ⚠ | 6025 / 6029 ⚠（全部超时） | 6027 / 6044 ⚠ | 6025 / 6039 ⚠ | ≈1.0 | — |
| a.lang3 | 75753 | 4620 | 4376 / 4948 | 4780 / 5549 | 3244 / 3907 | 0.58–0.95 | read_next=167878 |
| a.letter3 | 29634 | 1844 | 1365 / 1965 | 1482 / 1589 | 1168 / 1244 | 0.42–0.73 | read_next=65550 |
| a.letter0-9 | 13420 | 540 | 561 / 713 | 721 / 796 | 2005 / 4070 ⚠（1 次） | 0.46–0.99 | read_next=29760 |
| a.state2 | 32815 | 1665 | 1444 / 1850 | 1497 / 3168 | 1525 / 2413 | 0.63–0.72 | read_next=72728 |
| a.version2 | 40457 | 1514 | 1518 / 1740 | 1685 / 2014 | 2804 / 2889 | 0.49–0.79 | read_next=89642 |
| a.ids100 | 90 | 15 | 10.9 / 12.6 | 9.4 / 11.6 | — | 0.79–0.86 | read_key=200, read_rnd=220 |

不带时限的单次耗时（`uncapped_ms`，count+分页）：area3 5.1–15.0 s，area7 11.5–15.9 s，area10 10.2–13.3 s。超时的位置：先是 count 报 3024，再是分页报 3024；`hits` 倒序时分页还出现过 1028。

#### 5.2 b. OR

| 形态 | total | 冷态 | time 第 1 页 | hits 第 1 页 | time 偏移 10000 | CPU 负载 | Handler（time 第 1 页） |
|---|---|---|---|---|---|---|---|
| b.class1 | 45761 | 1443 | 1366 / 1681 | 1457 / 1724 | 1620 / 1806 | 0.23–0.30 | read_rnd_next=600002 |
| b.class3 | 95746 | 1442 | 1483 / 1515 | 1453 / 1532 | 1765 / 2241 | 0.21–0.34 | read_rnd_next=600002 |
| b.actor2 | 50620 | 4333 | 3933 / 5457 | 3297 / 4028 | 3499 / 3683 | ≈1.0 | read_rnd_next=600002 |
| b.director2 | 17939 | 3742 | 4192 / 4232 | 3154 / 3643 | 4377 / 4951 | ≈1.0 | read_rnd_next=600002 |
| b.tid-top | 54076 | 2857 | 3778 / 3915 | 2691 / 2992 | 3166 / 3687 | ≈0.95 | read_next=114016, read_rnd=114036 |
| b.tid-child | 13042 | 808 | 683 / 829 | 816 / 1037 | 1160 / 1353 | ≈0.9 | read_next=27606, read_rnd=27626 |

`classes`、`actor`、`director` 读的行数完全相同（每次请求两遍全表，600002 次 `read_rnd_next`），差别只在负载。按读取量看，2 项 `actor` 在空闲机器上应和 `classes` 是同一量级（约 1.4 s），但本次没有在空闲时测到。

#### 5.3 c. 年份区间

| 形态 | 展开项数 | total | 冷态 | time 第 1 页 | hits 第 1 页 | time 偏移 10000 | CPU 负载 | Handler |
|---|---|---|---|---|---|---|---|---|
| c.year1 | 1（`=`） | 9692 | 511 | 291 / 477 | 274 / 313 | — | 0.26–0.36 | read_next=21410 |
| c.year2000-2010 | 11（`IN`） | 93582 | 3156 | 3754 / 4250 | 5031 / 5819 | 3062 / 3165 | 0.25–0.58 | read_next=207492 |
| c.year1990-2021 | 32（`IN`） | 265339 | 1592 | 1736 / 2050 | 1745 / 2091 | 1756 / 1946 | 0.21–0.39 | read_rnd_next=600002（全表） |

直连时 `2000-2010` 的单条语句都在 3 s 以内（count 约 1.4–2.9 s）；但经应用的那一轮 5 次全部 503（第 6 节），说明它就在时限边缘。

#### 5.4 d. 随机排序（每次请求的全部语句；括号内是各步热态 p50）

| 形态 | 算法 | 计数 c | 冷态 | 热态 p50 / p95 | 各步 p50（count + idRange + 候选 count + 候选 page） | 候选命中 | CPU 负载 |
|---|---|---|---|---|---|---|---|
| d.rnd1（随机页） | 一 | 601 | 691 | 540 / 820 | 268 + 265 | — | 0.96 |
| d.rnd1（第 1 页 / 末页） | 一 | 601 | 691 / 563 | 486 / 738、537 / 819 | — | — | 0.92–0.95 |
| d.rnd2-none（第 1 / 2 页） | 二 | 270766 | 2281 / 1631 | 1875 / 2019、1521 / 1630 | 1785 + 1.4 + 61 + 53 | 901 / 1000 | 0.68–0.77 |
| d.rnd2-area（大陆，第 1 / 2 页） | 二 | 95002 | 3208 ⚠ / 3171 ⚠ | 2549 / 3103、2694 / 2991 | 2456 + 1.3 + 48 + 45 | 324 | 0.74 |
| d.rnd2-class（科幻，第 1 / 2 页） | 二 | 45761 | 1767 / 1506 | 1471 / 1614、1735 / 1860 | 1385 + 0.9 + 43 + 35 | 145 | 0.58–0.64 |

- 算法二除第一条计数以外的三条语句合计约 0.1 s（每次请求 `read_key=2000`：两遍各查 1000 个候选主键），`selectIdRange` 只读主键两端（`read_first=1`、`read_last=1`）。
- `rnd2-area` 的第一条计数（`vod_area='大陆'` ref，9.5 万行回表）在冷态和 1 次热态中超时，与第 5.1 节是同一个原因。
- 算法一的随机页偏移最大 600，排序行数（`Sort_rows`）只有 20–360，与偏移无关的成本（count 与 range 读）占主导。

#### 5.5 e. B05 旧标签条件（只有内部入口）

| 形态 | total | 冷态 | time 第 1 页 | hits 第 1 页 | time 偏移 10000 | CPU 负载 | Handler（time 第 1 页） |
|---|---|---|---|---|---|---|---|
| e.hits-between | 79560 | 6088 ⚠ | 6025 / 6025 ⚠ | 4485 / 4599 | 3919 / 4191 | 0.45–0.91 | read_next=135862 |
| e.hits-gt | 90307 | 4150 | 3369 / 3788 | 6056 / 6070 ⚠（含 1028） | 6054 / 6169 ⚠ | 0.28–0.99 | read_next=199826 |
| e.timeadd-30d | 182 | 31 | 27.8 / 59.4 | 18.5 / 30.1 | — | 1.00 | read_next=410 |
| e.timeadd-365d | 27046 | 3320 | 2919 / 3196 | 2788 / 2833 | 3114 / 3279 | 1.00 | read_next=59984 |
| e.isend1 | 149048 | 6045 ⚠ | 6034 / 6068 ⚠（全部超时） | 6050 / 6084 ⚠ | 6023 / 6023 ⚠ | 0.97–1.00 | read_next=203445 |
| e.notin100 | 270676 | 3122 | 3100 / 3276 | 2356 / 2600 | 2231 / 2506 | 0.42–0.75 | read_rnd_next=600002 |
| e.combo | 4893 | 1319 | 1360 / 1641 | 1316 / 1422 | — | 0.32–0.47 | read_next=59984 |

不带时限的单次耗时：`isend1` 6.4–11.6 s，`hits-gt` 10.0–11.1 s，`hits-between` 5.5 s。复测（02:47–02:48，负载 0.44–1.00）中 `isend1` 偏移 10000 那一组没有超时（4.9 s），其余仍然超时。

### 6. 经应用（HTTP，`/api/v1/videos`，sort=time 第 1 页或 sort=rnd，各 5 次）

| 形态 | 状态码 | HTTP p50 / p95（ms） | SQL 文本核对 |
|---|---|---|---|
| base | 200×5 | 2036 / 2327 | 2/2 |
| a.area1 | 200×5 | 1472 / 1616 | 2/2 |
| a.area3 | **200×2，503×3** | 5586 / 5698 | 2/2 |
| a.lang3 | 200×5 | 4605 / 5508 | 2/2 |
| a.letter3 | 200×5 | 1440 / 1900 | 2/2 |
| a.state2 | 200×5 | 1696 / 2250 | 2/2 |
| a.ids100 | 200×5 | 52.5 / 59.4（复测 132 / 760，total=90） | 2/2 |
| b.class3 | 200×5 | 2460 / 3226 | 2/2 |
| b.actor2 | 200×5 | 2352 / 2923 | 2/2 |
| c.year1 | 200×5 | 601 / 656（复测 443 / 569，total=9692） | 2/2 |
| c.year2000-2010 | **503×5** | 5841 / 5897 | 2/2 |
| c.year1990-2021 | 200×5 | 3852 / 4512（复测 2489 / 2570，total=265339） | 2/2 |
| d.rnd1 | 200×5 | 470 / 530（复测 273 / 323，total=601） | 2/2 |
| d.rnd2-none | 200×5 | 1488 / 2095（复测 1481 / 1725，total 每次不同） | 4/4 |
| d.rnd2-area | 200×5 | 2620 / 2986 | 4/4 |
| d.rnd2-class | 200×5 | 1461 / 1751 | 4/4 |

- 503 的响应体就是既有的 `SERVICE_UNAVAILABLE`（包括 count 处的 3024；排序阶段的 1028 在 MACC-22 之后也归为 503），没有出现 500。
- `s300k-http.tsv` 的 `total` 列是**脚本缺陷**：正则先匹配到了列表项里的 `"total"`（`vod_total`，值为 0）。脚本已改为匹配分页字段，复测的 4 个形态记录在 `s300k-totals-http.tsv`，total 与直连一致。

### 7. 风险、局限与未测量

- **机器负载**：这是本次最大的误差来源。CPU 负载 0.2–1.0，同一形态前后两次可以相差 30% 以上。正好落在 3 s 时限附近的形态（area3、year2000-2010、hits*、rnd2-area）是否超时取决于当时的负载。结论 2、3 依据的是访问路径和读行数（EXPLAIN、Handler），这两项不受负载影响；毫秒数只能当量级看。
- **数据规模和形状**：只测了一个规模（30 万行、3.6 KB/行，与 MACC-19 的行体积相同）。线上的行数、行体积、`vod_isend`/`vod_area` 的真实分布都**待核实**；大比例命中的索引回表成本对行体积和磁盘缓存很敏感。
- **样本量**：耗时 ≥ 2 s 的形态热态只跑了 3 次，p95 就是最大值。`hits` 倒序的深分页只在冒烟时测过无条件列表一次；经应用只测了 sort=time 第 1 页。
- **未经应用核对的 SQL**：B05 旧标签条件（`legacyConditions`）和 `tid` 没有 API 入口，脚本照抄 XML 文本，没有用应用日志核对。SSR 搜索页（`tid`、`by=rnd`）没有经 HTTP 测量。
- **未测量**：并发、和 PHP 写入的 MyISAM 表锁交互（09 报告第 5 节的结论仍然适用）、`weekday`/`rel` 文本形式（包含匹配，与 `classes` 是同一类全表扫描）、`tv`、`plot`、`timehits`、`level` 多值、`type`/`typenot`、`start`；`IGNORE INDEX` 等替代写法只做了一次 count 对照（第 0 节第 3 条），没有做完整测量。
- **冒烟运行**：第一次冒烟在 `d.rnd2-area` 处因计数超时（脚本当时用带时限的 count 判定算法分支）中止，没有写出 tsv，输出只在 `run.log`（02:05–02:08）；之后脚本改为用不带时限的 count 判定分支。

### 8. 复现

```bash
cd backend
export MACCMS_IT_ADMIN_URL='jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&useSSL=false'
export MACCMS_IT_ADMIN_USERNAME=<有建库/删库权限的本机账号>
export MACCMS_IT_ADMIN_PASSWORD=<密码>
P="java -cp $HOME/.m2/repository/com/mysql/mysql-connector-j/8.2.0/mysql-connector-j-8.2.0.jar src/test/perf/B05Perf.java"
$P create && $P grow 300000 && $P context s300k
$P bench s300k-1 base,d.     # 形态过滤：逗号分隔的名称前缀；不给则全部（约 30 分钟）
$P bench s300k-2 a.area,a.lang
$P bench s300k-3 a.letter,a.state,a.version,a.ids,c.
$P bench s300k-4 b.
$P bench s300k-5 e.
$P http s300k <B05 分支打包的 maccms-backend-0.1.0-SNAPSHOT.jar> base,a.,b.class3,b.actor2,c.,d.   # 可选
$P drop                      # 失败时也要执行；删除后查 information_schema.schemata 确认不存在
```

输出写到 `backend/target/perf-b05/`（已被忽略）。应用日志（`*-app.log`，含 DEBUG SQL，约 1 MB）没有复制到 raw 目录。
