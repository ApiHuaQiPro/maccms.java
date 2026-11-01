# APPK-2 对照环境与测试资产清单（S3-03）

> 归属：测试人员-海外（S3-03）。本文件回答两个问题：**对照环境该怎么搭**（谁做、按什么口径）、
> **测试线已经把哪些东西准备好了**。本文件不含任何执行结论，也不含地址、账号、密码或令牌。

## 1. 对照环境的组成（按技术设计 v10 的 DEC-1 / DEC-2，测试线不实现，只核对）

| 组成 | 依据 | 提供方 | 测试线怎么核对 |
| --- | --- | --- | --- |
| 模板包位置（仓库内） | DEC-1：`third_party/maccms-template-v2020.1000.1035/template/{default_pc,default_wap}` | 后端开发（S3-01，C-1～C-5） | `verify_template_package.py --target third_party/maccms-template-v2020.1000.1035` 返回 0 |
| 模板包放置（站点根内） | DEC-2：把包放进站点根的 `template/` | 运营打包（部署）/ PRE-2 脚本 | `verify_template_package.py --target <站点根>` 返回 0 |
| PHP 侧站点根 | 旧系统 `php/` + 旧库；PHP 7.3 | 运营打包 | 只读核对：`php/` 存在且 `template/` 内容与包一致 |
| Java 侧站点根 | 新系统 `src/` 构建产物 + `template/` 与包一致 | 后端 + 运营打包 | 同上 |
| 覆盖差异配置 | C-7 `tests/compare/APPK-2/maccms-overlay.json`（7 个键，把 `site.template_dir` 等指向 `default_pc`/`default_wap`） | 后端开发或架构师（C-7 不在 S3-03） | 逐键核对 7 个键及值；本文件附录列出应有的值，便于比对 |
| 单侧站点装配 | C-8 `assemble_site.py` | 后端开发（C-8 不在 S3-03） | 看两侧 `template/` 的 `verify_template_package.py` 结果 |
| PHP 运行参数 | C-10 `php.ini` | 后端开发 | 侧别一致（`display_errors`、`date.timezone`、`default_charset` 等不吃掉差异） |
| 数据前置（不变量） | PRD CMP-2（每侧跑前用 `it_admin` 还原数据并 `CHECKSUM TABLE`）、DATA-1（不得有等于当天的日期）、DATA-2（`by="rnd"` 标签两侧命中 ≤1 行）、DATA-3 | 测试人员 + 后端 | T2/T3 阶段按 PRE-1 的 DB-1～DB-4 与 SP-1～SP-4 做；本阶段未执行 |

一句话：**对照环境的最小可判定条件是“两侧模板字节完全相同 + 两侧配置只在受控键上不同 + 数据不变量成立”**。
三者缺一，比对差异都可能是环境差异而非实现差异——因此 S3-05 跑之前必须先过 `verify_template_package.py`。

## 2. 测试线已备好的资产（本提交）

| 资产 | 路径 | 现在就有的作用 | 什么时候才产生验收结论 |
| --- | --- | --- | --- |
| 274 行清单副本 | `docs/test/APPK-2/fixtures/template-package-manifest.tsv` | C-9/扫描器的比对基准；可 `--check` 防手改 | 不产生结论（只是基准） |
| 逐文件校验器 | `tests/compare/APPK-2/verify_template_package.py` | 真包/两侧站点根一致性判定 | S3-05 与 PRE-2 阶段 |
| PRE-5 扫描器 | `tests/compare/APPK-2/scan_templates.py` | 出机器事实 + PRE-5 表格初稿 | S3-05（真包）+ 架构师补实现说明 |
| 清单生成器 | `tests/compare/APPK-2/build_template_manifest.py` | 从 PRD 附表 E.3 重算基准 | 不产生结论 |
| 合成夹具生成器 | `tests/compare/APPK-2/make_synthetic_package.py` | 自测用；**不是真包** | 不产生结论 |
| 离线自测 | `tests/compare/APPK-2/selftest_offline.py` | 23 条工具行为用例 | 不产生结论（证明工具会报错，不证明产品正确） |
| 方案与判定口径 | `docs/test/APPK-2/template-scan.md` | 复核人照着能复现 | — |
| T1 用例（既有，未改） | `docs/test/APPK-2/t1-cases.md` | TC-F-TPL-001～004、SYN-001～004、SYN 等已覆盖模板链 | T3 |

## 3. 附录：C-7 `maccms-overlay.json` 应有的 7 个键（只读核对用）

技术设计 v10 的 C-7 规定覆盖配置只含这 7 个键，本表供复核人逐键比对，**测试线不写这个文件**：

| 键 | 值 | 为什么必须是这个值 |
| --- | --- | --- |
| `site.template_dir` | `default_pc` | 附表 E.2：PC 包目录名 |
| `site.html_dir` | `html` | 附表 E.2 的目录结构 |
| `site.mob_template_dir` | `default_wap` | 附表 E.2：手机包目录名 |
| `site.mob_html_dir` | `html` | 同上 |
| `site.ads_dir` | `ads` | EF-6：两个 `info.ini` 都写 `adsdir = ads` |
| `site.mob_ads_dir` | `ads` | 同上（`default_wap` 无 `ads/` 目录，靠 EF-6 的既有行为） |
| `site.mob_status` | `2` | 让手机包在对照中稳定生效，不依赖 UA 判定 |

## 4. 测试线不碰的东西

* 不新建账号，不往网页表单输密码，不读凭据值；只依环境变量名（见产品仓根目录说明文件）。
* 不部署、不改 nginx、不改旧 PHP、不改数据库结构。
* 不在 S3-03 发任何 HTTP 请求、不连测试环境、不打开被测页面。
* 不改 `tests/api/**`。
