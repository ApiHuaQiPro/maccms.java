# APPK-2 依赖漏洞审计（REV-BE-1）

- 起因：后端评审第 1 轮安全审计 REV-BE-1。OWASP Dependency-Check 缺 NVD API key、CISA feed 返回 403，OSS Index 返回 401，都没有出报告。
- 本次改用 OSV（`https://api.osv.dev`）。OSV 不需要 API key，Maven 生态的数据来自 GitHub Advisory Database。
- 被扫对象：完整运行时依赖，即 compile + runtime 作用域，不含 test、provided、system。共 83 个构件，其中包括 `httpclient5`、`httpcore5`、`httpcore5-h2`。
- 结果：S3-21e 新增的三个 httpcomponents 构件没有已知漏洞。Spring Boot 4.1.1 BOM 带来的 Jackson 3.1.5 和 Tomcat 11.0.24 本来就在 main 上，不是 S3-21e 引入的，但它们共有 11 项已修复的公开漏洞。本次把这两组依赖覆盖到修复版本，复扫结果为 0。

## 工具与数据源

| 项目 | 值 |
| --- | --- |
| SBOM 生成 | `org.cyclonedx:cyclonedx-maven-plugin:2.9.1`（命令行调用，没有写进 pom），CycloneDX 1.6 |
| 扫描器 | `osv-scanner` 2.2.4（osv-scalibr 0.3.6）。安装命令：`go install github.com/google/osv-scanner/v2/cmd/osv-scanner@v2.2.4` |
| 数据源 | OSV 在线 API `api.osv.dev`，扫描时实时查询，不用本地离线库 |
| 扫描时间（UTC） | 修复前 2026-11-01T14:41:51Z；修复后 2026-11-01T14:45:40Z |
| 数据新鲜度 | 修复前命中记录中，最新的 `modified` 是 2026-10-27T15:10:58Z（GHSA-f525-44xv-f2qj） |
| 交叉核对 | 不经过 osv-scanner，直接把同一份 SBOM 的 83 个坐标提交到 `POST https://api.osv.dev/v1/querybatch`。修复前命中 3 个构件 11 项漏洞，修复后 0 项（2026-11-01T14:46:09Z），与 osv-scanner 结果一致 |
| 覆盖核对 | SBOM 的 83 个坐标和 `dependency:list -DincludeScope=runtime` 的结果逐一相同 |

## 命令（仓库根目录，可复跑）

需先设 `JDK_JAVA_OPTIONS=-Djdk.net.unixdomain.tmpdir=<专用目录>`。

```text
./mvnw -B org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom -DincludeTestScope=false -DincludeProvidedScope=false -DincludeSystemScope=false -DoutputFormat=json -DoutputName=bom-runtime
cp target/bom-runtime.json <目录>/bom-runtime.cdx.json        # osv-scanner 按文件名识别 CycloneDX，文件名必须以 .cdx.json 结尾
osv-scanner scan source -L <目录>/bom-runtime.cdx.json --format json --output <目录>/osv.json
osv-scanner scan source -L <目录>/bom-runtime.cdx.json --format table
./mvnw -B -q dependency:list -DincludeScope=runtime -DoutputFile=<文件>   # 覆盖核对
```

## 报告文件

报告都在 `docs/api/APPK-2/dep-audit/`：

| 文件 | 内容 |
| --- | --- |
| `bom-runtime-before.cdx.json` | 修复前的 SBOM，pom 与 `677853de9d8e4c3d2c5beca92208b7008de4d649` 相同 |
| `osv-before.json` | 修复前 osv-scanner 的 JSON 报告：3 个构件，11 项漏洞，退出码 1 |
| `bom-runtime-after.cdx.json` | 修复后的 SBOM，pom 为本次提交 |
| `osv-after.json` | 修复后 osv-scanner 的 JSON 报告：`results` 为空，表格输出为 `No issues found`，退出码 0 |

报告里的 `source.path` 已经改成仓库相对路径，其余内容是工具原样输出。

## 修复前的发现与处理

| 编号 | 别名 | OSV 严重度 | 构件@版本 | 3.x / 11.x 修复版本 | 处理 |
| --- | --- | --- | --- | --- | --- |
| GHSA-7hhh-6rmp-j9qf | CVE-2026-89425 | HIGH | jackson-core@3.1.5 | 3.1.7 | 升到 3.1.7 |
| GHSA-p6pp-m3f8-5c89 | CVE-2026-89407 | HIGH | jackson-core@3.1.5 | 3.1.7 | 升到 3.1.7 |
| GHSA-cxp5-3px4-pw24 | CVE-2026-91777 | HIGH | jackson-databind@3.1.5 | 3.1.7 | 升到 3.1.7 |
| GHSA-wv8q-qhhj-9h54 | CVE-2026-91776 | HIGH | jackson-databind@3.1.5 | 3.1.7 | 升到 3.1.7 |
| GHSA-q4xh-88c3-wmh7 | CVE-2026-68497 | HIGH | jackson-databind@3.1.5 | 3.1.6 | 升到 3.1.7 |
| GHSA-gx83-3vf8-gh7j | CVE-2026-83557 | MODERATE | jackson-databind@3.1.5 | 3.1.6 | 升到 3.1.7 |
| GHSA-wjgm-6hv5-3cvf | CVE-2026-19032 | MODERATE | jackson-databind@3.1.5 | 3.1.6 | 升到 3.1.7 |
| GHSA-9xv2-5v5q-p794 | CVE-2026-65905 | CRITICAL | tomcat-embed-core@11.0.24 | 11.0.25 | 升到 11.0.27 |
| GHSA-f525-44xv-f2qj | CVE-2026-65637 | CRITICAL | tomcat-embed-core@11.0.24 | 11.0.25 | 升到 11.0.27 |
| GHSA-gcx9-497g-6cp6 | CVE-2026-65182 | CRITICAL | tomcat-embed-core@11.0.24 | 11.0.25 | 升到 11.0.27 |
| GHSA-h3x4-894j-xpx5 | CVE-2026-68525 | CRITICAL | tomcat-embed-core@11.0.24 | 11.0.25 | 升到 11.0.27 |

所有发现都已修复，没有需要人类接受的风险。

修复方式：在 `pom.xml` 中覆盖 Spring Boot 管理的两个版本属性，即 `jackson-bom.version=3.1.7` 和 `tomcat.version=11.0.27`。这是 Spring Boot 4.1.2 BOM 采用的同一组版本。

- Spring Boot 父版本保持 4.1.1，所以 `httpclient5` 仍是设计 v10 §12 写明的 5.6.4，`httpcore5` 和 `httpcore5-h2` 仍是 5.4.3。
- 没有新增依赖。同类做法仓库里已有先例：`mysql.version` 覆盖。
- `tomcat.version` 同时管 `tomcat-embed-core`、`-el` 和 `-websocket`，三者都变为 11.0.27。`jackson-bom.version` 管 `tools.jackson.*`。`com.fasterxml.jackson.core:jackson-annotations:2.21` 不变，扫描没有命中它。

回归锁：`DependencyVulnerabilityBaselineTest` 读取运行时实际加载的 Jackson core/databind 版本（`PackageVersion.VERSION`）和 Tomcat 版本（`ServerInfo.getServerNumber()`），断言它们不低于上表的修复版本。

- 修复前运行：4 条中 3 条失败，实际版本分别是 3.1.5、3.1.5、11.0.24。
- 修复后运行：4/4 通过。

## 局限

- OSV 的 Maven 数据以 GitHub Advisory Database 为主。只在 NVD 里用 CPE 登记、没有对应 GHSA 的条目，本次覆盖不到。完成 NVD 侧的核对需要 NVD API key，本运行环境没有。
- 扫描只代表扫描时刻的公开数据。之后新公布的漏洞要重新扫描才能发现。
- 仓库 `MULTICA.md` 的 `dep_audit` 仍是“未配置”。本次没有把扫描接进构建，是否固化为门禁命令由调度决定。
