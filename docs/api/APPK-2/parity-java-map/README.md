# APPK-2 附表 C 登记簿 → Java 类 / 方法 / 测试映射（APPK-12）

| 项 | 内容 |
| --- | --- |
| Issue | APPK-12（APPK-10 T-FR12 阶段 A 证据补齐） |
| 输入台账 | `docs/test/APPK-2/parity-stage-a-rows.md`（测试回传提交 `7968ff7a25b46b41683a571d248108d8d4c135da`，已原样合入本分支，未修改） |
| Java 源码口径 | 与 `a57e9cb` 相同（本分支只新增 `docs/api/APPK-2/parity-java-map/` 与合入的测试台账，未改 `src/`） |
| 覆盖 | **R-001～R-379 全部 379 行**，每行一条，`verify_map.py` 核对无缺无重 |
| 本文件**不是** | AC-14 的四值判定。`证据状态` 只说“找到/没找到哪些类、方法、测试”，判定由测试人员填写 |

## 1. 文件

| 文件 | 覆盖行 | 产生方式 |
| --- | --- | --- |
| `admin-rows.md` | 后台 210 行（R-165～R-374） | `gen_admin_map.py` 机械生成（可复跑、输出与提交件一致） |
| `api-install-rows.md` | API 21 行（R-144～R-164）+ 安装向导 5 行（R-375～R-379） | 人工逐行读源码填写 |
| `site-rows.md` | 前台 143 行（R-001～R-143） | 人工逐行读源码填写（含 `说明` 列） |
| `gen_admin_map.py` | — | 后台映射生成器，规则写在文件头 |
| `verify_map.py` | — | 三张表的可检出性核对 |

## 2. 列与证据状态口径

- **Java 实现**：`相对 src/main/java/com/maccms/backend/ 的路径:行号` + `类#方法`。后台给出分派行（`case "控制器/动作"`、先判控制器再 switch 动作的 `case "动作"`、`SystemConfigService.GROUPS`、`AdminMakeActions.ACTIONS`），箭头后是该分支调用的处理。
- **测试**：`测试类#测试方法`。黄金夹具驱动的测试另写夹具路径与命中的场景 label。`〔弱〕` = 只验证权限拒绝/菜单（方法名含 Auth/Permission/Denied、`AdminPermissionsGoldenTest`、夹具场景 `anonymous`/`auth-*`）或字面量只在辅助代码中，**不计入**“实现+测试”。
- **证据状态**（只取以下值）：

| 状态 | 含义 | 行数 |
| --- | --- | --- |
| 实现+测试 | 找到处理该动作的 Java 分派/方法，且至少一个非〔弱〕测试请求了它 | 341 |
| 实现·缺测试 | 有 Java 处理，没找到请求它的测试 | 19 |
| 间接实现·待核 | 原路由无独立处理，功能由别的入口承担，或只部分覆盖，需人工确认是否等价 | 6 |
| 显式不提供 | Java 代码明确返回“不提供/非法请求/不做测试”的固定响应 | 3 |
| 缺实现 | 没有任何 Java 映射或分支处理该动作 | 5 |
| 非动作（占位行） | 台账 3 个空类占位行，类体无 public function | 3 |
| 非动作（辅助方法） | 旧 `admin/Base.php` 的 `check_auth`、`_cache_clear`（`AdminPermissions.java:303` 注释写明不算动作），给出新版承担位置 | 2 |
| **合计** | | **379** |

## 3. 缺口与待核清单（交智能调度 / 测试人员）

| 行 | 现状 | 证据 | 建议归属 |
| --- | --- | --- | --- |
| R-145 `api/Index::index` | 缺实现 | 旧方法体为空；`/api.php` 下只映射 provide/receive/wechat/timming | 测试判定（可能是“旧版无功能”，需读 PHP 源码证据）；如需补实现 → 后端开发 |
| R-010 `Ajax::index` | 缺实现 | 旧方法体为空；`SiteAjaxController.java:158` default 返回 404 | 同上 |
| R-044 `Note::index`、R-068 `Search::index`、R-119 `Vod::index` | 缺实现 | `site/web` 全部映射中无对应路由（`LegacyPage` 无 `VOD_INDEX`） | 产品经理判定是否建 GAP- 或按 OP-/D 排除；后端开发补实现 |
| R-041 `Label::index` | 显式不提供 | `SiteAjaxController.java:128` 固定返回“非法请求” | 产品经理确认对应的 D/OP 编号 |
| R-224 `Collect::timing` | 显式不提供 | `AdminPermissions.java:300` `NOT_PROVIDED`（注释记 2026-10-28 用户决定删除） | 产品经理确认决策编号 |
| R-296 `System::test_cache` | 显式不提供 | `AdminSystemActions.java:73` 固定提示“未进行连接测试” | 产品经理确认决策编号 |
| R-227～R-231 `Collect::vod/art/actor/role/website` | 间接实现·待核 | 旧版由 `Collect::api` 按 mid 内部调用；新版无独立 case（直接访问落入批次提示），按类型处理在 `collect/api` → `CollectPreviewService`（`ProvideKind`）；没有按行直接请求的测试 | 测试判定等价性；后端开发补针对性测试 |
| R-018 `Ajax::pwd` | 间接实现·待核 | 原址 404；密码校验改为 POST 播放/下载/文章页；**视频详情页密码未实现（503）** | 产品经理判定 GAP-；后端开发 |
| R-280 `Make::rss`、R-282 `Make::topic_index` | 实现·缺测试 | `AdminMakeActions.ACTIONS` 含该动作，`MakeServiceTest` 无该动作用例 | 测试人员/后端开发补测试 |
| R-008/R-059/R-142 `ajax_detail`（actor/role/website）；R-009/R-051/R-060/R-074/R-143 `rss`（actor/plot/role/topic/website）；R-031 `Comment::ajax`；R-036/R-037 `Gbook::ajax/report`；R-076/R-102/R-104/R-106/R-113/R-115 `User::ajax_info/downs/ulog/plog/cards/gbook` | 实现·缺测试 | 见 `site-rows.md` 各行（downs/plog/cards 只在会员中心页做了链接检查，没有请求它们） | 测试人员/后端开发补测试 |

其他需要测试人员知道的限制（不改变状态，已写在各行 `说明`）：

- **149 行“实现+测试”的测试证据全部来自 `*MySqlIT`**（后台内容/会员/系统/采集、Provide/Receive、前台交互等）。这些测试需要 `MACCMS_IT_ADMIN_URL` / `MACCMS_IT_ADMIN_USERNAME` / `MACCMS_IT_ADMIN_PASSWORD` 等外部环境，**本轮没有执行**。行号：R-004 R-005 R-006 R-014 R-015 R-016 R-017 R-019 R-023 R-030 R-032 R-033 R-034 R-035 R-038 R-048 R-057 R-105 R-107 R-137 R-138 R-140 R-146～R-156 R-160 R-165～R-168 R-183～R-194 R-197～R-223 R-225 R-226 R-232～R-235 R-238～R-240 R-249～R-260 R-270～R-275 R-286～R-293 R-295 R-321～R-333 R-335 R-336 R-339～R-347 R-349～R-354 R-369～R-374。
- 前台若干行的测试只覆盖门禁或关闭态：R-050 plot `ajax_detail`（只测默认关闭 404）、R-114 `User::comment`（只测未登录与服务缺失 404）、R-071 topic `ajax_search`（MVC 只测 429）、R-139 website `search`（MVC 只测 400）、R-109 `cash_del`（MVC 只测未登录，服务层由 IT 覆盖）。
- R-029 `Base::_empty`、R-043 `MyError::_empty`：未知动作返回站点 HTML 404；未映射的控制器返回全局 JSON 404（“接口不存在”），不是旧版带跳转脚本的 HTML 页。
- R-040 `Index::index`：只映射了 `/` 与 `/index.php`，`/index.php/index/index` 未映射。

## 4. 复跑命令（工作目录 = 仓库根，只读）

```bash
# 1. 重新生成后台 210 行，并确认与提交件一致（应无差异，exit 0）
python docs/api/APPK-2/parity-java-map/gen_admin_map.py --out /tmp/admin-rows.md \
  && git diff --no-index --ignore-cr-at-eol --exit-code docs/api/APPK-2/parity-java-map/admin-rows.md /tmp/admin-rows.md

# 2. 核对三张表：379 行无缺无重、旧方法与台账一致、每个 路径:行号 在文件范围内、每个 类#方法 存在、状态取值合法
python docs/api/APPK-2/parity-java-map/verify_map.py

# 3. 抽查任一引用：例如 R-276
sed -n 63p src/main/java/com/maccms/backend/admin/web/AdminMakeActions.java   # if (action.equals("opt")) {
```

本轮实测结果（本提交）：

```
$ python docs/api/APPK-2/parity-java-map/gen_admin_map.py --out …
admin 行 210：实现+测试 197；实现·缺测试 2；显式不提供 2；间接实现·待核 5；非动作（占位行） 2；非动作（辅助方法） 2
$ git diff --no-index --ignore-cr-at-eol --exit-code …   → exit 0
$ python docs/api/APPK-2/parity-java-map/verify_map.py
行 379 / 台账 379；路径引用 471 处、类#方法引用 1105 处已核对
证据状态：实现+测试 341；实现·缺测试 19；显式不提供 3；缺实现 5；间接实现·待核 6；非动作（占位行） 3；非动作（辅助方法） 2
exit 0
```

抽跑被引用的非 IT 测试（证明引用的测试类可运行；不代表行为等价判定）：

```
$ ./mvnw -q -o test -Dtest='AdminSystemGoldenTest,AdminMakeMvcTest,MakeServiceTest,AddonMarketGoldenTest,AdminTemplateMvcTest,TimmingControllerMvcTest,TimmingGoldenTest,InstallWizardGoldenTest,InstallModeTest,WechatServiceTest,KindIngestTest,VodIngestTest,ProvideGoldenVersionsTest,AdminMvcTest'
exit 0；14 个测试类 84 个用例：失败 0、错误 0、跳过 0（target/surefire-reports）
```

`*MySqlIT` 与前台 `Site*MvcTest` 本轮未执行。

`verify_map.py` 核对的是**引用可检出**（文件、行号范围、类与方法名存在），不核对“这一行代码确实处理该动作”；后者在后台由生成器规则保证，在前台/API/安装行由人工阅读保证，评审可按 §4 第 3 条逐行抽查。

## 5. 边界

- 未改 `src/`、PRD、技术设计、PHP、API 契约，也未改测试人员已有的 `docs/test/APPK-2/*` 与 `tests/compare/APPK-2/*`（只是合入）。
- 不给 `✅已对齐` / `旧版无功能` / `排除-` / `缺口` 判定，不建 GAP-。
