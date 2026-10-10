# MacCMS 重构

## 现状
- MacCMS v10，PHP + ThinkPHP 5.0.24。代码来自线上生产备份 `maccms10-prod-20260711.zip`（2026-07-11）。
- 目录：`application/`（admin 后台、index 前台、api、common、extra 配置）、`template/` 前台模板、`addons/` 插件、
  `extend/` 扩展类、`static/` 静态资源、`thinkphp/` 框架、`vendor/` 依赖。

## 配置与密钥
- 仓库里的 `application/database.php` 已改为**本地开发库**：127.0.0.1:3306，库名 `maccms_dev`，root/root。
- `application/extra/maccms.php` 里的密钥、token、appid 等值已清空。
- 线上原始配置保存在仓库外 `D:\Ai\MACCMS\prod-config\`，**不要**把其中内容写进仓库、评论或日志。

## 重构目标
（待定：由「现状分析」和「重构方案」任务产出，经用户确认后写在这里）

## 本地运行与测试
（待定：由「现状分析」任务补充）
