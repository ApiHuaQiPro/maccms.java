# MacCMS 重构

## 现状
- MacCMS v10（`application/extra/version.php` `code=2023.1000.3051`），PHP + ThinkPHP **5.0.24**（`thinkphp/base.php:12` 定义 `THINK_VERSION`，入口 `index.php:39` 加载的就是这个目录）。代码来自线上生产备份 `maccms10-prod-20260711.zip`（2026-07-11）。
  - 另有一处不一致：备份 zip 里 `vendor/composer/installed.json` 记录的是 `topthink/framework v5.0.14`，但备份中没有 `vendor/topthink/framework/` 目录，运行时用不到这份元数据。成因待核实。
- 目录：`application/`（admin 后台、index 前台、api、common、extra 配置）、`template/` 前台模板、`addons/` 插件、
  `extend/` 扩展类、`static/` 静态资源、`thinkphp/` 框架、`vendor/` 依赖。
- 详细分析见 `docs/analysis/`（01 现状、02 Go/Java 选型、03 重构方案草稿，均待用户确认）。
- **仓库不完整**（2026-10-10 核实）：
  - `template/` 只有一个 0 字节的 `template/default/html/index.html`，备份里也一样。本地实测的 3 个前台样本页（首页、分类页、会员登录页）都报"模板文件不存在"，其他前台页面没有逐一实测。
  - `vendor/` 被 `vendor/.gitignore`（内容为 `*`）忽略，没有进入 git；备份 zip 里有 110 个文件，包括 think-captcha、think-image、fastadmin-addons 等。
  - `extend/` 是空的，备份里也是空的，所以 QQ/微信登录、PHPMailer、七牛、又拍 SDK 在本仓库里不可用。
- 已知安全问题（在本仓库代码中发现，线上是否存在**待核实**；详见 `docs/analysis/01-current-state.md` 第 10 节）：
  - 会员可以用 md5 哈希登录（`application/common/model/User.php:346`）——本地实测确认
  - 会员可以只凭 openid 登录（`User.php:331-354`）——本地实测确认
  - `api.php/timming/index` 没有鉴权——**仅代码阅读，未实测**
  - OAuth 首次登录自动建号时用时间戳作密码（`application/index/controller/User.php:263-267`）——仅代码阅读

## 配置与密钥
- 仓库里的 `application/database.php` 已改为**本地开发库**：127.0.0.1:3306，库名 `maccms_dev`，root/root。
- `application/extra/maccms.php` 里的密钥、token、appid 等值已清空。
- 线上原始配置保存在仓库外 `D:\Ai\MACCMS\prod-config\`，**不要**把其中内容写进仓库、评论或日志。

## 重构目标
- **已确认**（用户 2026-10-10 在 MACC-2 回复"那开始吧"）：新系统用 **Java + Spring Boot + MyBatis**，**先沿用现有 MySQL 库（`mac_*` 表）**，做**模块化单体**；采集以后也统一用 Java。今后主要由智能体开发。
- **当前批次（MACC-4）**：`backend/` Java 工程骨架 + `content` 模块只读接口（视频分类、视频分页列表、视频公开详情元数据）+ 健康检查。契约 `docs/contracts/content-read.openapi.yaml`，计划与版本选择 `docs/architecture/01-batch1-content-read-plan.md`，开发规范 `docs/architecture/02-agent-development-rules.md`（在 `backend/` 工作前必读）。
- **仍待定、未实施**（见 `docs/analysis/03-migration-plan-draft.md` 第 8 节）：模板方案（改写还是兼容层）、各阶段写入方切换、MyISAM→InnoDB 与 utf8→utf8mb4 转换、PHP 侧兼容改动。03 仍是草稿，不能当成已实施的方案。
- 未获批准：部署、切 nginx 流量、线上核实、修改旧 PHP、修改数据库结构或账号。新系统对旧库**只读**。

## 本地运行与测试
以下步骤由 Claude 后端工程师于 2026-10-10 在本机执行过（另有注明的除外），用 Git Bash 运行，工作目录为仓库根目录。

1. **恢复 `vendor/`**。它不在 git 里，只能从备份 zip 中解压 `vendor/`。下面的脚本只写 `vendor/` 目录内的文件，遇到解析后落在 `vendor/` 之外的路径就中止；解压出来的文件会被 `vendor/.gitignore` 忽略，不会被提交：
   ```bash
   python - <<'PY'
   import zipfile, os
   root = os.path.realpath('vendor')
   z = zipfile.ZipFile(r'D:/Ai/MACCMS/maccms10-prod-20260711.zip')
   for i in z.infolist():
       p = i.filename.split('/', 1)
       if len(p) != 2 or not p[1].startswith('vendor/') or i.is_dir() or p[1] == 'vendor/.gitignore':
           continue
       dst = os.path.realpath(p[1])
       if not dst.startswith(root + os.sep):
           raise SystemExit('refuse: ' + i.filename)
       os.makedirs(os.path.dirname(dst), exist_ok=True)
       with open(dst, 'wb') as f:
           f.write(z.read(i))
   PY
   ```
   这个带路径检查的版本于 2026-10-10 在一个临时目录里试跑过：解出 110 个文件，全部位于 `vendor/` 下，试跑目录随后删除；zip 中没有含 `..` 或绝对路径的条目。
   如果不恢复，验证码会报 500（`Class think\captcha\Captcha not found`），后台因为要求验证码而无法登录。
2. **初始化开发库——只能用于已核实为空的库**。
   - ⚠️ `application/install/sql/install.sql` 从第 4 行起对 26 张表都执行 `DROP TABLE IF EXISTS`，导入会**清空同名表的已有数据**。
   - 先检查：
     ```bash
     mysql -u root -p -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='maccms_dev';"
     ```
     结果为 0（或者库不存在）才可以继续。**结果大于 0 时停下**：不要导入，不要覆盖；先用 `mysqldump` 备份，再和任务负责人另行确定初始化方式（例如换一个新的空库名，并相应修改本地的 `application/database.php`，该文件不要提交）。2026-10-10 执行这条检查时，本机的 `maccms_dev` 已有 26 张表，所以**现在不能在本机直接执行下面的导入**。
   - 库为空时的导入命令（库名必须是开发库，不要连其他库）：
     ```bash
     mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS maccms_dev DEFAULT CHARACTER SET utf8mb4;"
     mysql -u root -p --default-character-set=utf8 maccms_dev < application/install/sql/install.sql
     mysql -u root -p --default-character-set=utf8 maccms_dev < application/install/sql/initdata.sql   # 18 个示例分类
     mysql -u root -p maccms_dev -e "INSERT INTO mac_admin (admin_name,admin_pwd,admin_status,admin_auth) VALUES ('<账号>',MD5('<本地密码>'),1,'');"
     ```
     首次在空库上执行的结果：26 张表，`mac_type` 18 行。本机 MySQL 5.7 默认是 `STRICT_TRANS_TABLES`，导入时没有报错。
3. **跳过安装向导**：`date '+%F %T' > application/data/install/install.lock`。
   这个文件**不在 .gitignore 里，不要提交**。
4. **启动**：
   ```bash
   D:/Tools/php/8.0/php.exe -S 127.0.0.1:18180 -t .
   ```
   PHP 7.3（`D:/Tools/php/7.3/php.exe`）也验证过。注意本机的 8080 和 18080 端口已被占用。
   - 后台地址：`http://127.0.0.1:18180/_admin.php`。登录页和验证码图片（`/index.php/verify/index.html`）都能正常返回。
   - 后台 18 个主要页面在 7.3 和 8.0 上都返回 200。验证时是用本地脚本注入 session 绕过验证码的，**没有在浏览器里手动走完验证码登录**。
   - 前台 3 个样本页（`/index.php` 首页、分类页、会员登录页）返回 500"模板文件不存在"，原因是模板缺失（见"现状"），不是环境问题。其他前台页面没有逐一实测。
   - `http://127.0.0.1:18180/api.php/provide/vod/?ac=list` 返回 `closed`，这是默认配置下资源站接口关闭的正常结果。
5. **语法检查**：`find application addons index.php api.php _admin.php install.php -name '*.php' -exec php -l {} \;`
   在 PHP 7.2、7.3、8.0、8.2 上都通过（193 个文件）。PHP 8.x 下会有 2 条 Deprecated（`common/extend/sms/Qcloud.php:179`、`common/util/SinaUpload.php:105`，原因是可选参数写在必选参数之前）。
6. **自动化测试**：项目没有应用层测试，只有框架自带的 `thinkphp/phpunit.xml`。
7. **注意**：很多前台 GET 请求有写库副作用（点击、顶踩、评分、购买扣积分等，清单见 `docs/analysis/03-migration-plan-draft.md` 第 3.1 节），在开发库上做请求实验时要知道数据会被改动。
8. **未验证的部分**：
   - nginx 伪静态部署
   - 前台页面与模板标签（缺模板）
   - 定时任务接口（只读了代码）
   - 采集（没有资源站）
   - 支付、短信、邮件、第三方登录（缺密钥和 `extend/` 依赖）
   - 静态生成

## 新系统（backend/）本地运行与测试
以下命令由 Claude 后端工程师于 2026-10-10 在本机执行通过，工作目录为 `backend/`（Git Bash 用 `./mvnw`，PowerShell 用 `.\mvnw.cmd`）。详细说明见 `backend/README.md`。

1. **环境**：JDK 21（本机已装）；Maven 由仓库内的 Maven Wrapper 自动下载并按 `distributionSha256Sum` 校验，无需预装。
2. **构建与测试**（不连数据库）：`./mvnw verify` 或 `.\mvnw.cmd verify`——编译、单元测试、HTTP 契约测试、只读/无凭证静态检查、异常日志脱敏检查。
3. **MySQL 集成测试**：设置环境变量 `MACCMS_IT_ADMIN_URL`（如 `jdbc:mysql://127.0.0.1:3306/?characterEncoding=UTF-8&useSSL=false`，不含库名）、`MACCMS_IT_ADMIN_USERNAME`、`MACCMS_IT_ADMIN_PASSWORD` 后执行 `./mvnw verify -Pmysql-it`。测试自建一次性库 `maccms_it_<时间>_<随机>`、导入 `install.sql` 表结构与合成数据，结束后只删除该库；**不碰 `maccms_dev`**。
4. **运行**：`./mvnw -q package -DskipTests` 后，设置 `MACCMS_DB_URL`、`MACCMS_DB_USERNAME`、`MACCMS_DB_PASSWORD`（样例见 `backend/config/maccms-backend.env.example`），执行 `java -jar target/maccms-backend-0.1.0-SNAPSHOT.jar`，默认监听 `127.0.0.1:18280`；`curl http://127.0.0.1:18280/actuator/health` 返回 `{"status":"UP"}` 即就绪。
   - 缺少数据库环境变量时拒绝启动；用 `root` 需显式设置 `MACCMS_DB_ALLOW_ROOT=true`（仅本机开发）。连接池是只读会话，应用无法写库。
   - 生产应使用只授予 SELECT 的专用账号，由管理员创建（本批未创建任何账号）。
5. **停止**：前台运行按 Ctrl+C；后台运行时按启动时记录的 PID 结束（PowerShell `Stop-Process -Id <PID>`），不要按进程名批量结束 java。
6. **不要提交**：`backend/target/`、`backend/config/*.env`、运行日志。
