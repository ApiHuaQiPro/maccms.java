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
（待定：由「现状分析」和「重构方案」任务产出，经用户确认后写在这里）

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
