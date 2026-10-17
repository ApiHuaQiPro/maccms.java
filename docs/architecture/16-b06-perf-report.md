## B06 中规模合成库测量：Provide、ajax/data、ajax/suggest、站点级 RSS 与搜索引擎地图（MACC-30）

> 依据：MACC-30 验收第 6 条"报告……性能限制"。测量经**真实 HTTP** 请求本批最终代码打包的应用（`backend/target/maccms-backend-0.1.0-SNAPSHOT.jar`），
> SQL 与绑定值取自应用的 MyBatis 调试日志（实际执行的语句，不是照抄 XML），EXPLAIN 在同一个一次性库上执行。
> 脚本：`backend/src/test/perf/b06_perf_run.py`（不进 `./mvnw verify`，用法见文件头）；原始结果：`docs/architecture/16-b06-perf-raw/`
> （`bench.json` 每个请求的冷态/热态耗时，`explain.txt` 每条 SQL 与 EXPLAIN，`run.json` 库名、行数、清理结果）。
> 数字是本机**一次运行**的测量值（2026-10-17 15:19–15:23），机器与 B05 报告是同一台虚拟机，测量期间另有智能体进程在跑（未空闲），只能当量级看，不是延迟承诺。

### 0. 结论

1. **选择性高的请求很快**：Provide `ids` 指定 20 条整行约 50 ms（主键 range）；`h=24`（最近 24 小时，`vod_time` range）约 30 ms；单个年份约 0.4 s；
   地图页约 15 ms（只读分类）。
2. **不带条件的列表约 1.0–1.4 s**：Provide `ac=list/videolist`、ajax/data、站点级 RSS（50 条）与搜索引擎地图（1000 条）都是"可见分类 + 已审核"
   的 `COUNT(*)` 全表扫描 + `ORDER BY vod_time DESC, vod_id DESC` 的 filesort（EXPLAIN `type=ALL`、`Using filesort`）。旧版同样每次都做带条件的
   `COUNT(*)`；新版另外因为 02 规范第 3 节要求"排序追加主键同向"而不能沿 `vod_time` 单列索引取前 N 条（旧版只按 `vod_time` 排序）。
3. **接近 3 s 时限的请求**：深分页（`pg=10000`，偏移 20 万行）约 3.4–3.5 s（两条语句各自都在时限内，合计超过 3 s）；`isend=1`（命中约一半的行，
   优化器选 `vod_isend` 单列索引回表）约 3.0–3.2 s。行数再大或机器更忙时，单条语句会超过 `MAX_EXECUTION_TIME(3000)`，Provide 返回 503。
4. **联想词在 30 万行时超时（503）**：`ajax/suggest` 的名称或英文名包含匹配是全表扫描，`COUNT(*)` 超过 3 s 时限，按护栏返回
   `{"code":503,"msg":"服务暂时不可用"}`（HTTP 503、`Retry-After: 5`）。联想词默认随搜索开关关闭（`MACCMS_SITE_SEARCH_ENABLED`、
   `MACCMS_CONTENT_SEARCH_ENABLED` 默认 false），与 B05 报告中包含匹配的结论一致。
5. Provide 的 `wd`、`from`（包含匹配）约 1.0–1.2 s（全表扫描，`type=ALL`），在时限内。

**建议**（只陈述数据含义，是否采取需要队长裁定；本批**没有**实现，不做 DDL）：

- 若要缩短无条件列表：去掉主键并列排序（与 02 规范第 3 节冲突，需裁定），或对 `ORDER BY vod_time` 单独加 `FORCE INDEX (vod_time)` 写法（改变 SQL 文本，
  需单独测量与评审）。
- 深分页与低选择性条件的 503 风险与 B05 报告第 0 节相同，生产启用 Provide 前应按线上真实行数复测；联想词保持默认关闭，开启前须测量。

### 1. 范围与安全证据

| 项 | 证据 |
|---|---|
| 管理连接 | 只用 `MACCMS_IT_ADMIN_URL`/`_USERNAME`/`_PASSWORD`（本机账号，只在执行命令的 shell 环境变量中，没有写进文件、日志或命令行参数；被测应用子进程经环境变量拿到同一账号，连接池仍是只读会话） |
| 一次性库 | `maccms_it_20261017151945_48605a0a`（15:19:45 创建，15:23:23 删除）。库名创建前按 `^maccms_it_[0-9]{14}_[0-9a-f]{8}$` 校验，导入前确认没有任何表；删除后查 `information_schema.schemata` 为 0（`run.json` 的 `databaseDropped: true`） |
| 其他库 | 没有连接、读取或修改 `maccms_dev` 和其他任何库 |
| 服务器 | 没有改全局变量；DDL 只有 `install.sql` 原样导入（新建的空一次性库）与 `DISABLE/ENABLE KEYS`、`ANALYZE`（只对该库的 `mac_vod`） |
| 数据 | 全部合成（只依赖序号），播放地址在 `media.example.test` 下 |
| 进程 | 应用子进程 PID 9012，按 PID 结束；没有按进程名结束任何进程 |
| 旧配置 | 临时目录中的 `extra/maccms.php` 副本（用 PHP CLI 把 `api.vod/art.status` 改为 1），不改仓库文件，测量后删除 |

### 2. 环境与数据

| 项 | 值 |
|---|---|
| MySQL | 5.7.44（本机服务），默认参数；表结构为 `install.sql` 原样（MyISAM、单列索引） |
| 行数 | `mac_vod` 300010 行（合成 300000 + 种子 10），`vod_status=1` 294009 行 |
| 行体积 | `vod_content` 平均 900 B，`vod_play_url` 平均 94 B |
| 分布 | 分类 2/3/4/11 各 1/4；`vod_isend` 0/1 各一半；`vod_year` 2000–2021 均匀；`vod_play_from` 5 种（含 `m3u8` 1/5）；`vod_time` 散布在 2020-09 至 2023-06 |
| 灌数 | 80.2 s（含 `ENABLE KEYS`、`ANALYZE`） |

### 3. 结果（毫秒；冷态 1 次 + 热态 3 次）

| 请求 | 状态 | 字节 | 冷态 | 热态 |
|---|---|---|---|---|
| `provide-list`（`ac=list`） | 200 | 4157 | 2291.3 | 1188.3 / 1304.1 / 1399.3 |
| `provide-list-xml`（`at=xml`） | 200 | 3817 | 1151.2 | 1105.4 / 1154.1 / 1195.9 |
| `provide-list-pg100` | 200 | 4255 | 1229.0 | 1082.3 / 1222.4 / 1433.1 |
| `provide-list-pg10000` | 200 | 4248 | 3630.2 | 3424.1 / 3489.7 / 3534.7 |
| `provide-videolist`（整行 20 条） | 200 | 39517 | 1334.8 | 1183.7 / 1200.6 / 1221.9 |
| `provide-detail-ids20` | 200 | 45638 | 50.5 | 46.7 / 49.1 / 54.9 |
| `provide-t`（`t=3`，约 7.4 万行） | 200 | 4237 | 2079.4 | 1765.1 / 1852.1 / 1921.8 |
| `provide-h24` | 200 | 559 | 32.6 | 18.6 / 30.4 / 33.7 |
| `provide-year`（单年） | 200 | 4236 | 406.7 | 383.5 / 423.2 / 440.7 |
| `provide-year-range`（2000–2020） | 200 | 4202 | 1421.2 | 1407.3 / 1425.5 / 1646.5 |
| `provide-isend`（`isend=1`） | 200 | 4204 | 3269.7 | 3018.2 / 3076.5 / 3240.6 |
| `provide-wd`（包含匹配） | 200 | 2410 | 1059.9 | 1110.5 / 1220.4 / 1229.3 |
| `provide-from`（`from=m3u8`） | 200 | 4245 | 1068.2 | 996.4 / 1024.4 / 1141.3 |
| `provide-from-videolist` | 200 | 48169 | 1101.6 | 997.7 / 1099.8 / 1142.3 |
| `ajax-data`（`mid=1&limit=30&page=20`） | 200 | 84567 | 1175.3 | 1022.4 / 1042.5 / 1059.2 |
| `ajax-data-tid`（`tid=1`，子分类 OR） | 200 | 27550 | 1086.2 | 1021.8 / 1062.9 / 1155.1 |
| `ajax-suggest`（`wd=视频9999`） | **503** | 42 | 3840.1 | 3595.2 / 3622.7 / 3861.0 |
| `rss-index`（50 条） | 200 | 15023 | 1075.9 | 1098.3 / 1111.4 / 1251.9 |
| `rss-baidu`（首页 + 1000 条） | 200 | 166862 | 1236.5 | 1284.1 / 1377.1 / 1390.6 |
| `map` | 200 | 4896 | 70.8 | 12.3 / 14.6 / 24.0 |

### 4. 访问路径（摘自 `explain.txt`）

| 语句 | 访问路径 |
|---|---|
| 无条件 COUNT / 第 1 页 | `ALL`（30 万行），分页另有 `Using filesort` |
| `ids` | `range`（PRIMARY），20 行 |
| `t=3` | `ref`（`type_id`），约 7.5 万行 + filesort |
| `h=24` | `range`（`vod_time`） |
| 单年 / 年份区间 | 单年 `ref`（`vod_year`）；区间 IN 21 项时优化器改为 `ALL` |
| `isend=1` | `ref`（`vod_isend`），约 15 万行回表 |
| `wd`、`from`、联想词 | `ALL`（LIKE 包含匹配） |
| ajax/data `tid` | `ALL`（`type_id IN … OR type_id_1 IN …`） |
| 分类表 | `ALL`（9 行） |
