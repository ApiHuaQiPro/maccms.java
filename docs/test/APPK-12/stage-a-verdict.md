# APPK-12 阶段 A：R-001～R-379 四值判定

> 追溯：PRD `AC-14`（阶段 A 完成条件）；上游为 `docs/test/APPK-2/parity-stage-a.md`（阶段 A 台账，结论 BLOCKED）与
> `docs/api/APPK-2/parity-java-map/README.md`（映射与缺口清单）。
> 本轮任务 = 智能调度在评审评论里交办的「用映射提交 `000b58e` 作唯一输入，逐行给 R-001～R-379 四值判定」。

| 项 | 值 |
| --- | --- |
| 唯一输入 | 映射提交 `000b58e3ac5d1a6d000bad01734b3770b76efc09`（本分支以 `54a15ba` 合入，无 `src/`、`php/` 改动） |
| 判定范围 | 379 行全部（376 个真实功能行 + 3 个占位行） |
| 判定台账 | `docs/test/APPK-12/stage-a-verdict-rows.md`（379 行，脚本生成） |
| 机器可读版 | `docs/test/APPK-12/stage-a-verdict-rows.md.json` |
| 生成器 | `docs/test/APPK-12/gen_review_rows.py` |
| 核对器 | `docs/test/APPK-12/verify_verdict.py`（exit 0） |
| 本文件结论 | **四值判定已完成**：`✅已对齐` 283、`旧版无功能` 7、`缺口` 89、`排除-` 0 |

---

## 0. 一句话结论

379 行全部有了四值判定，**没有一行用「源码里存在这个方法」冒充已对齐**：283 行的 `✅已对齐` 全部挂着本轮真正跑过并通过的测试名，
7 行 `旧版无功能` 全部有 PHP 源码证据，剩余 **89 行是缺口**——这正是阶段 A 要交出去的东西，不是本轮没做完。
89 个缺口里 **82 个只是「有实现但缺可执行证据」**（63 行只有未执行的 `*MySqlIT`、19 行根本没有测试），
**7 个是真实功能差异或缺失**，需要产品经理先建/补编号。

## 1. 判定口径（照 PRD 附表 C，不自行发明）

附表 C 的「判定列取值」只有四个，且每个都有硬条件：

| 取值 | 附表 C 要求的证据 | 本轮的落地做法 |
| --- | --- | --- |
| `✅已对齐` | 附 **Java 类/方法与测试名** | 只认「映射表点名的测试 + 该测试在本次 surefire 报告里有执行记录且通过」。名字对不上 → 直接失败，不生成台账 |
| `旧版无功能` | 附 **PHP 源码行证据** | 只认三类：方法体为空、类体无 public function、无对应 URL 动作。依据里必须带 `php/application/…` 路径 |
| `排除-<OP-n 或 D 编号>` | 已关闭的决策来源 | **一行都没填**。仓库里没有已确认的 OP-/D 排除清单，测试不能自行推断排除 |
| `缺口` | 须在 GAP- 登记簿里有一行（AC-19） | 其余全部落到这里，并按 GAP 类型分组，每条写明需建 GAP- 与归属 |

**证据来源等级**（本文件自定，用来区分「缺口」的成因，不替代附表 C 的四值）：

| 等级 | 含义 | 行数 |
| --- | --- | --- |
| `IT-0` | 旧源码 + Java 实现 + **本轮执行通过**的非 IT 测试 | 283 |
| `GAP-IT` | 有实现，测试证据只在未执行的 `*MySqlIT` | 63 |
| `GAP-T` | 有实现，没有任何测试 | 19 |
| `GAP-I` | 实现不完整 / 缺实现，需产品经理定性 | 4 |
| `GAP-X` | 新版行为与旧版不同，决策编号缺失 | 3 |
| `NA` | 旧版无功能（PHP 源码证据） | 7 |
| **合计** | | **379** |

## 2. 执行证据（本轮真跑过的，不是引用）

### 2.1 输入一致性

```
$ git log --oneline -3
54a15ba chore: 合入 APPK-12 映射提交 000b58e 作为四值判定唯一输入
6560972 …
000b58e docs: APPK-2 旧控制器到 Java 的逐行映射（379 行）

$ git diff --stat a57e9cb..000b58e
 10 个文件，全部在 docs/ 与脚本；src/ 0 处、php/ 0 处

$ python docs/api/APPK-2/parity-java-map/verify_map.py
行 379 / 台账 379；路径引用 471 处、类#方法引用 1105 处已核对
exit 0
```

### 2.2 非 IT 测试实跑（`✅已对齐` 的唯一依据）

映射表引用的非 IT 测试共 35 个测试类；本轮把它们全部跑完（不是评审那次的 14 类 84 例）：

```
$ ./mvnw -B -o test -Dtest='<35 个类>'
BUILD SUCCESS
Tests run: 385, Failures: 0, Errors: 0, Skipped: 0
```

`target/surefire-reports/TEST-*.xml` 是判定器的输入：`✅已对齐` 行点名的每个 `类#方法` 都必须在这里面，
且参数化名按 `(…)[n]` 归一后再匹配。映射表共引用 **181 个不同的 `类#方法`**（138 个非 IT + 43 个 `*MySqlIT`），
其中 **138 个非 IT 引用全部可解析**（类存在、方法在报告里有执行记录），未解析集合为空；43 个 IT 引用本轮一律未执行（见 §4）。

### 2.3 判定台账的生成与核对

```
$ python docs/test/APPK-12/gen_review_rows.py --out docs/test/APPK-12/stage-a-verdict-rows.md
行 379
  ✅已对齐 283 / 缺口 89 / 旧版无功能 7

$ python docs/test/APPK-12/verify_verdict.py
行 379；✅已对齐 283；旧版无功能 7；缺口 89；排除- 0
OK：379 行齐全升序；判定取值合法且求和=379；✅已对齐 的测试本轮都执行过；
    旧版无功能 都有 PHP 源码证据；缺口全部具名且写明 GAP-；无自行排除
exit 0
```

**核对器不是摆设**——本轮用 4 个反例验证过它真的会失败：

| 反例 | 结果 |
| --- | --- |
| 把 R-041 改成 `排除-D9-a` | `FAIL：出现排除判定——本轮没有已确认的 OP-/D 清单，测试不得自行排除` |
| 在 R-001 的依据里塞一个不存在的 `FakeTest#notARealTest` | `FAIL：R-001: ✅已对齐 的测试本轮没有执行记录` |
| 删掉 R-200 一行 | `FAIL：行集合不是 R-001～R-379：缺 1 行 ['R-200']，实际 378 行` |
| 同一输入再跑一次生成器 | 输出逐字节相同 |

### 2.4 一行一个依据的抽查（人工读源码）

判定不只看映射表的文字，关键行都回读了源码：

- **R-010 / R-145**：`php/application/index/controller/Ajax.php` 的 `index()`、`php/application/api/controller/Index.php` 的 `index()` 方法体为空 → 旧版无功能。
- **R-044 / R-068 / R-119**：`Note::index`、`Search::index`、`Vod::index` 的方法体是 `return $this->label_fetch('…/index');`，**不是空方法** → 旧版有功能、新版无路由 → 缺口。
- **R-195 / R-196**：`admin/controller/Base.php:42-58` 的 `check_auth` 只做字符串比对；`:60-100` 的 `_cache_clear` 由构造函数调用、不对外暴露 → 无对应 URL 动作。
- **R-227～R-231**：`Collect::api` 按 `mid` 内部调 `vod/art/actor/role/website`；新版 `AdminCollectActions#api` 用 `ProvideKind.ofMid` 分派。链路可读，但只有 IT 覆盖（R-229 `mid=8`、R-230 `mid=9` **连 IT 用例都没有**）。
- **R-018**：PHP `Ajax::pwd` 覆盖 `vod_pwd`（详情）、`vod_pwd_play`、`vod_pwd_down`、`art_pwd`；新版 `PasswordTarget` 只有 `VIDEO_PLAY`/`VIDEO_DOWN`/`ARTICLE`，详情密码抛 `VIDEO_PASSWORD_REQUIRED` 后被 `SiteExceptionHandler` 交还 **503** → 真实实现缺口。

## 3. 缺口清单（89 行，按归属分组）

### 3.1 真实功能差异或缺失（7 行）——需产品经理先定性

| 行 | 旧行为 | 新版行为 | 定性 | 下一步 |
| --- | --- | --- | --- | --- |
| R-018 `Ajax::pwd` | 详情/播放/下载/文章四路密码 | 播放、下载、文章三路已实现并有用例通过；**详情密码未实现，交还 503** | `GAP-I` 实现不完整 | 产品经理建 GAP-；后端开发补 `PasswordTarget.VIDEO_DETAIL`（`PasswordTarget` 注释记 MACC-14 未批） |
| R-044 `Note::index` | `label_fetch('note/index')` | `site/web` 无该路由，Spring 404 | `GAP-I` 缺实现 | 产品经理判定 GAP- 或按 OP-/D 排除 |
| R-068 `Search::index` | `label_fetch('search/index')` | 同上（搜索实际承载在 `/index.php/vod/search.html`） | `GAP-I` 缺实现 | 同上 |
| R-119 `Vod::index` | `label_fetch('vod/index')` | 同上 | `GAP-I` 缺实现 | 同上 |
| R-041 `Label::index` | `label_fetch` 执行模板标签 | `SiteAjaxController` 固定返回「非法请求」，注释称依 D9-a | `GAP-X` 编号缺失 | 产品经理补 `排除-D9-a` 编号，否则建 GAP- |
| R-224 `Collect::timing` | 可用定时挂机页 | `AdminPermissions.NOT_PROVIDED` 固定「未提供」，注释记 2026-10-28 用户决定删除 | `GAP-X` 编号缺失 | 产品经理补该决定的 D-/OP- 编号 |
| R-296 `System::test_cache` | 真连缓存并测试 | 固定「未进行连接测试」，注释记 D-B15-4 | `GAP-X` 编号缺失 | 产品经理补 D-B15-4 的正式编号 |

### 3.2 有实现、只有未执行的 MySQL IT（63 行）——补跑即可升级

这些行**不是功能缺失**，是证据等级不够。补跑 `./mvnw verify -Pmysql-it`（需 `MACCMS_IT_ADMIN_URL/USERNAME/PASSWORD`）通过后，
按本文件的生成器重跑即可自动升级为 `✅已对齐`：

R-004 R-005 R-006 R-014 R-015 R-016 R-017 R-019 R-023 R-030 R-032 R-033 R-034 R-035 R-038 R-048 R-057 R-105 R-107
R-137 R-138 R-140 R-146 R-147 R-148 R-149 R-150 R-151 R-152 R-153 R-154 R-155 R-156 R-157 R-160 R-185 R-194 R-206
R-210 R-211 R-212 R-213 R-218 R-225 R-227 R-228 R-231 R-257 R-259 R-270 R-295 R-331 R-335 R-336 R-339 R-342 R-346
R-347 R-351 R-354 R-374

其中 **R-229（mid=8 actor）、R-230（mid=9 role）连 IT 用例都没有**，补跑也升不了级，需后端开发先补用例。

### 3.3 有实现、完全没有测试（19 行）——补测试

| 组 | 行 | 现状 |
| --- | --- | --- |
| 前台 ajax_detail / rss 片段 | R-008 R-009 R-051 R-059 R-060 R-074 R-142 R-143 | `SiteLibraryController#render`（ajax 分支）/ `#rss` 有对应分支，无测试 |
| 前台交互片段 | R-031 R-036 R-037 | `SiteInteractionController#handle` 的 `ajax`/`report` 分支，无测试 |
| 会员中心页 | R-076 R-102 R-104 R-106 R-113 R-115 | `site/web` 会员控制器有处理；README 只记了「做了链接检查」，没有请求它们 |
| 后台生成 | R-280 R-282 | `AdminMakeActions.ACTIONS` 含该动作，`MakeServiceTest` 无该动作用例 |

## 4. 这不等于 AC-14 通过

必须写清楚，免得被读成「阶段 A 完工」：

1. **89 行是缺口，且缺口必须在 GAP- 登记簿里有行**（附表 C + AC-19）。仓库里还没有 `docs/test/APPK-2/gap-register.md`，
   建 GAP- 的 Owner 是产品经理，不是测试——测试单方面写「缺口」而不建 GAP-，就满足不了 AC-14。本轮把「要建哪 89 个 GAP-」查清了，只差登记。
2. **`✅已对齐` 283 行的依据是「本轮跑过的非 IT 测试通过」**，其中 **205 行同时还挂着未执行的 `*MySqlIT`**，
   另有一些行的测试只覆盖门禁或关闭态（R-050 R-071 R-109 R-114 R-139 等，README §3 已记）。测试跑通说明**被测路径与旧版一致**，
   不等于整行行为等价；真正判定行为等价的证据是 `*MySqlIT` 的黄金样本对照，**那部分本轮没执行**。
3. **`排除-` 仍然 0 行**。3 个 `GAP-X`（R-041/R-224/R-296）只要产品经理给出编号，就能从「缺口」变成「排除-」，
   这是本次唯一「一行编号就能减 3 个缺口」的地方。

## 5. 复跑方式（工作目录 = 仓库根，只读）

```bash
# 1. 跑非 IT 测试（生成器依赖 target/surefire-reports 判定 ✅已对齐）
./mvnw -B -o test -Dtest='<35 个测试类，见 .pi/tests.json 或映射表引用的非 *MySqlIT>'

# 2. 重新生成 379 行判定台账（确定性：同输入逐字节相同）
python docs/test/APPK-12/gen_review_rows.py --out docs/test/APPK-12/stage-a-verdict-rows.md

# 3. 核对（379 行齐全升序 / 取值合法 / 求和=379 / ✅已对齐 有执行记录 / 旧版无功能 有 PHP 证据 / 缺口具名 / 无自行排除）
python docs/test/APPK-12/verify_verdict.py
```

补跑 IT 之后只需重跑第 2、3 步，`GAP-IT` 行会自动升级为 `✅已对齐`——这是本台账最实用的一点。

## 6. 边界与自检

| 约束 | 做法 |
| --- | --- |
| 只用映射提交 `000b58e` 作输入 | §0、§2.1；`git diff --stat a57e9cb..000b58e` 无 `src/`、`php/` |
| 不用「能编译 / 单测通过 / 开发说没问题」判等价 | §1：`旧版无功能` 必须有 PHP 源码证据；`✅已对齐` 必须点名本轮跑过的测试 |
| 不把非 IT 抽跑当成行为等价证明 | §4 第 2 条明写；`IT-0` 等级只声明「被测路径一致」，行为等价待 IT |
| 不自行排除 | §1、§3.1、核对器的反例 1；`排除-` 计数 0 |
| 不自行建 GAP- / 不代产品经理定性 | §3.1、§4 第 1 条：只列「需建 GAP- 的行 + 归属」，编号留给产品经理 |
| 不改产品代码 / 需求 / 验收标准 | 只新增 `docs/test/APPK-12/` 下的台账、生成器、核对器与本文件；`src/`、`php/`、PRD、映射表均未改（`git diff --name-only a57e9cb..000b58e | grep -E '^(src|php)/'` = 0 行） |
| 不写账号密码令牌 | 判定文件与脚本里没有凭据，IT 只提环境变量名 |
| 结论不注水 | §0、§4：`缺口` 89 行照实写，没有把「有实现无测试」写成 `✅已对齐` |
