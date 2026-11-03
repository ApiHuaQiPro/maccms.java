# MacCMS 官方模板包 v2020.1000.1035（default_pc + default_wap）

本目录是 MacCMS 官方仓库在标签 `v2020.1000.1035` 下 `template/` 与 `LICENSE` 的逐字节副本，供 APPK-2 前台 PHP 模板体系使用（技术设计 `docs/design/APPK-2/tech-design.md` DEC-1、C-1～C-4；PRD `docs/prd/APPK-2.md` 附表 E、BR-11）。

## 来源

| 项 | 值 |
| --- | --- |
| 官方仓库 | `https://github.com/magicblack/maccms10` |
| 标签 | `v2020.1000.1035`（提交日期 2020-08-17 UTC） |
| 提交号 | `4c418c2703692148a937ad92893ab8a494bff742` |
| `template/` 目录树 SHA-1 | `9398a1ed539cc754b7513c76e12134b3b5b49a53` |
| `template/default_pc/` 目录树 SHA-1 | `4c8d4ce6f8a68c343f94ccb15beda9157d77aea7` |
| `template/default_wap/` 目录树 SHA-1 | `a211f900e060c1d405f2c9d75523e02abac45b80` |
| `LICENSE` blob SHA-1 | `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64`（Apache License 2.0，官方同一提交根目录原文） |
| 文件数 | `template/default_pc/` 147 + `template/default_wap/` 127 = 274；`template/default_wap/ads/` 在官方包中不存在 |

逐文件路径、字节数与 blob SHA-1 见 PRD 附表 E.3（`docs/prd/APPK-2/E-template-package.md`）。

## 取得命令

在一次性的空目录里执行，不运行官方仓库里的任何脚本：

```sh
git clone --filter=blob:none --no-checkout https://github.com/magicblack/maccms10 src
git -C src -c core.autocrlf=false archive --format=tar 4c418c2703692148a937ad92893ab8a494bff742 template LICENSE \
  | tar -x -C third_party/maccms-template-v2020.1000.1035
```

入库前先提交 `.gitattributes` 的 `third_party/maccms-template-v2020.1000.1035/** -text -whitespace`，再 `git add` 本目录，保证换行不被 `autocrlf` 改写。

## 校验

```sh
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/template              # 9398a1ed539cc754b7513c76e12134b3b5b49a53
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/template/default_pc   # 4c8d4ce6f8a68c343f94ccb15beda9157d77aea7
git rev-parse HEAD:third_party/maccms-template-v2020.1000.1035/template/default_wap  # a211f900e060c1d405f2c9d75523e02abac45b80
git ls-tree -r --name-only HEAD third_party/maccms-template-v2020.1000.1035/template | wc -l   # 274
```

## 修改规则

本目录内的文件**不得修改、增加或删除**（BR-11）。需要改模板才能渲染时，停下并开 OP-；确需换用其他版本或改动后的模板，另开新目录（目录名带版本）并经 OP- 决定，不在本目录上改。

本目录不打进 jar（`pom.xml` 的资源段不引用 `third_party/`）；部署时按 DEC-2 把 `template/default_pc/`、`template/default_wap/` 原样复制到站点根的 `template/` 下。
