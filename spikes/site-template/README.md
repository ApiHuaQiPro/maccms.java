## site 模板引擎选型 spike（MACC-7）

**只用于技术验证，不是正式代码。** 正式 `pom.xml` 不引用本目录，本目录也不依赖正式工程。结论与分析见 `docs/architecture/04-site-template-spike.md`。

| 目录 | 候选 | 版本 |
|---|---|---|
| `thymeleaf/` | Thymeleaf，Spring Boot 官方 starter | Spring Boot 4.1.1 管理的 `thymeleaf` / `thymeleaf-spring6` 3.1.5.RELEASE |
| `jte/` | jte，预编译 + 类型检查 | `gg.jte` 3.2.4（`jte-maven-plugin`、`jte-models`、`jte-spring-boot-starter-4`） |

两个子工程都继承 `spring-boot-starter-parent` 4.1.1（与正式工程相同），只渲染**合成数据**：

- 不配置数据源，不连接任何数据库，不读取 `MACCMS_*` 等环境变量或任何外部配置文件。
- 只有 `/spike/view/{fixture}`（Spring MVC 视图解析，边渲染边写响应）与 `/spike/buffered/{fixture}`（先渲染成字符串再写响应）两条 spike 路由；`fixture` 为 `normal`、`xss`、`empty`、`boom-small`、`boom-large`。没有正式首页/分类/详情路由。

### 复跑命令

在仓库根目录执行，使用正式工程已有的 Maven Wrapper（Maven 3.9.16，按 `distributionSha256Sum` 校验）。Git Bash 用 `./mvnw`，PowerShell 用 `.\mvnw.cmd`（把 `/` 换成 `\`），两种入口都实测过。需要 JDK 21。

```bash
# 1. 两个候选的全部测试（预期 BUILD SUCCESS：thymeleaf 19 个、jte 22 个测试）
./mvnw -f spikes/site-template/pom.xml clean verify

# 2. 字段失配实验：错字段位于测试数据会走到的分支
./mvnw -f spikes/site-template/thymeleaf/pom.xml -Pmismatch clean verify   # 预期 BUILD FAILURE（渲染测试失败）
./mvnw -f spikes/site-template/jte/pom.xml -Pmismatch clean verify         # 预期 BUILD FAILURE（编译失败）

# 3. 字段失配实验：错字段只在测试数据从未走到的分支
./mvnw -f spikes/site-template/thymeleaf/pom.xml -Pmismatch-branch clean verify   # 预期 BUILD SUCCESS —— 这就是盲区
./mvnw -f spikes/site-template/jte/pom.xml -Pmismatch-branch clean verify         # 预期 BUILD FAILURE（编译失败）

# 4. 失配实验会改写 target/ 下的模板，结束后务必清理（或直接重新执行第 1 条）
./mvnw -f spikes/site-template/pom.xml clean
```

- 失配实验必须带 `clean`：profile 把故意写错的模板复制进 `target/`，不清理的话下一次普通构建可能沿用错误模板。
- 测试输出里以 `SPIKE-RESULT` 开头的行是报告引用的实测值（状态码、是否分块、是否完整、各上下文的转义结果）。

### 手工启动

```bash
./mvnw -f spikes/site-template/pom.xml clean package
java -jar spikes/site-template/thymeleaf/target/site-template-thymeleaf-0.0.0-SPIKE.jar   # 127.0.0.1:18291
java -jar spikes/site-template/jte/target/site-template-jte-0.0.0-SPIKE.jar               # 127.0.0.1:18292
curl -i http://127.0.0.1:18291/spike/buffered/normal
curl -i -H 'Accept: text/html' http://127.0.0.1:18291/spike/view/boom-small
```

- 前台运行按 Ctrl+C 停止；后台运行时记录 PID 并按 PID 结束（PowerShell `Start-Process ... -PassThru` / `Stop-Process -Id <PID>`），不要按进程名批量结束 java。
- 端口 18291、18292 只监听 127.0.0.1；不要使用 8080/18080（本机已占用）。

### 不要提交

`target/`、`jte-classes/`、日志（本目录 `.gitignore` 已忽略）。
