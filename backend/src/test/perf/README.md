## 搜索/筛选性能测量（MACC-19，T2）

`SearchPerf.java` 在一次性 MySQL 库上测量契约 1.1.0 搜索/筛选语句的耗时、EXPLAIN、Handler/排序/临时表计数与 MyISAM 表锁等待，
结果与结论见 `docs/architecture/09-content-search-filter-perf-report.md`。

- **不是测试，不进 `./mvnw verify`**：Maven 不编译 `src/test/perf/`；脚本以 Java 单文件方式运行，只依赖 JDK 21 与本地 Maven 仓库里的
  `mysql-connector-j-8.2.0.jar`（与应用打包的驱动同一版本）。
- 只使用 `MACCMS_IT_ADMIN_URL`、`MACCMS_IT_ADMIN_USERNAME`、`MACCMS_IT_ADMIN_PASSWORD`（与 `-Pmysql-it` 相同的变量）。
  创建的库名必须匹配 `^maccms_perf_[0-9]{14}_[0-9a-f]{8}$`，只读写这个库；`drop` 删除后再查 `information_schema.schemata` 确认不存在。
- 对其他库只读 `information_schema.tables` 中 `maccms_dev` 的元数据（表名、引擎、行数、长度、更新时间），在 `create` 前、`drop` 后各取一次并比较，
  不以 `maccms_dev` 为默认库连接，不读其数据。
- 不改服务器全局变量。冷态只执行 `FLUSH TABLES <一次性库>.mac_vod`（关闭该表、释放其 MyISAM 键缓存块；操作系统文件缓存不受影响）。
- 被测应用是 `target/maccms-backend-0.1.0-SNAPSHOT.jar` 的子进程：管理账号经环境变量传入（不出现在命令行）、`MACCMS_CONTENT_SEARCH_ENABLED=true`
  只在这个子进程里设置，仓库默认值不变；结束时只结束这个子进程（按 PID）。
- 合成数据只依赖 `vod_id` 与固定种子，不含真实用户、密钥或真实地址（图片/播放地址都在 `example.invalid` 下）。

### 运行

```bash
cd backend
./mvnw -q package -DskipTests
export MACCMS_IT_ADMIN_URL='jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&useSSL=false'
export MACCMS_IT_ADMIN_USERNAME=<有建库/删库权限的本机账号>
export MACCMS_IT_ADMIN_PASSWORD=<密码>
bash src/test/perf/run-all.sh          # 全流程约 2 小时；退出时（含失败）自动删除一次性库
```

单步（`P="java -cp <mysql-connector-j-8.2.0.jar> src/test/perf/SearchPerf.java"`）：

| 子命令 | 作用 |
|---|---|
| `$P create` | 建库、导入 `application/install/sql/install.sql`、写 30 个分类与游客组权限，库名记在 `target/perf/state.properties` |
| `$P grow <行数>` | 追加 `mac_vod` 到指定行数（`DISABLE KEYS` → 批量插入 → `ENABLE KEYS` → `ANALYZE`，只作用于一次性库） |
| `$P context <标签>` | 机器、MySQL 版本与变量、表状态、数据分布、索引基数 |
| `$P bench <标签> [形态正则]` | 每个形态 × `time`/`hits` 倒序 × 页码：EXPLAIN、冷/热耗时、会话计数增量、经应用的 HTTP 耗时、应用 CPU 与堆 |
| `$P sqlcheck <标签>` | 用应用的 MyBatis DEBUG 日志核对本脚本 SQL 文本与绑定值（去空白后逐字） |
| `$P lock <标签> <秒>` | 固定 5 次/秒 `UPDATE … vod_hits=vod_hits+1` + 1/4/8 个低频关键字读者（直连 / 经应用）的表锁实验 |
| `$P timeout <标签>` | 另一会话持有 `LOCK TABLES mac_vod WRITE` 时验证 `MAX_EXECUTION_TIME`、并发上限与 503 |
| `$P drop` | 删除一次性库并确认不存在；比较 `maccms_dev` 元数据前后快照 |

输出都在 `backend/target/perf/`（已被 `.gitignore` 忽略）。
