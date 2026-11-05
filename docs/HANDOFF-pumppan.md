# HANDOFF · pumppan 分支交接记录

> 生成日期：2026-11-02（构建产物时间 2026-11-03 00:02）　最后同步：2026-11-05（用户改动 / 真机实测 / HEAD 复核）
> 分支：`pumppan`　工作树：`D:\Programming\controlX2-i18n_pumppan`
> 当前 HEAD：以 `git log -1` 为准（本文件同步时为 `ec92377`；本次 docs 提交后再前进一格）
> 本文档自包含，可脱离对话记录直接接手。

## 重启前状态（2026-11-03）

### 三树 HEAD（均已推送，非强推）

| 树 | 分支 | HEAD | 主题 |
|---|---|---|---|
| pumppan | `pumppan` | `ec92377` | 2026-11-05：UI 行组件统一为 `BaseRow` + HTTP 真机实测（`fe8a887`）；用户归档审查意见到 `docs/old/`（`ec92377`）；此前 TIR 细化 `5e24620`、缓存查看器改名 `82710ea`、术语收敛 `9c392f0` |
| i18n main | `main` | `f0543e1` | 归档已完成文档、未完成文档移交 pumppan（2026-11-05 复核，工作区干净） |
| lab | `assets` | `ad6f4d7` | WORKLOG §80/§81 用户真机记录 + §82 进度更新（2026-11-05，已推送） |

### 已拍板（不再重复讨论）

- **X2 全只读**：真机实测控制页全部只读、**仅大剂量可执行**；`canControlPump` 保持 `model == MOBI`，**非缺陷、勿擅自放开**；能力收窄**搁置**，等 pumpx2 按固件收敛后再评。
- **多语 key 白名单**：`values-zh-rCN` 为唯一正式目录；mobile 1090/1084（差 6）、wear 348/347（差 1）属刻意例外，**差额扩大即报警**。
- **pumpx2 版本**：维持 `1.9.0`，**暂不升级**；1.9.3 试升曾因改动过大回滚；重评条件=主线发布 ≥ `v1.9.4` 且含能力相关变更。
- **充电图标**：改为挖空矢量并本地采用（`ce4db28`），原「禁带」已解除。
- **`SettingsGroup`**：已删除（零调用 + 实现被否决的折叠方案）；`StatusRow` 保留并标注 0 调用。
- **声音页「更改位图」输入框**：已删除（属我们误引入），并补型号门控（非 Mobi 禁用「应用」+ 原因文案）。
- **星期选择器**：`ControlIQSettingsActions :: SleepDaysSelector` 已落地（不依赖 `MultiDay`）。
- **历史缓存清空**：已修复（清空后不再显示旧数据）。
- **首页显示三项开关**（CGM 图表 / 传感器信息 / 历史同步条）：**用户本人要求新增的本地定制**，不是上游功能（设置页 UI 由 `438aa3f` 引入、`637012e` 图标化；枚举与 state 经 zh 表带入，调试页 `FeatureFlags.kt` 原本靠 `FeatureFlag.values()` 自动列出）。`8309cee` 用 `settingsOnlyFlags` 在调试页遍历里排除这三项，UI 只留设置页；枚举、`xxxState`、`initState`、`Dashboard` 消费点**一律未动**。**回退点：`FeatureFlags.kt :: settingsOnlyFlags`** —— 上游若反对/要求收敛，去掉或清空该集合即可让功能开关页重新出现三行，再移除或降级设置页那三项；回退只是 UI 位置切换，不涉及数据与逻辑。若日后纳入上游，应把设置页 UI 迁到 `FeatureFlags` 页并统一 key。
- **历史库策略 = 全量自动拉取（C 方案）定稿（规格决策，2026-11-04）**：不采用内存环形缓冲（A：重启即空、无回填、后台断档）与有界裁剪（B：按 3h/6h 裁会让 TIR 与 24h 图表失真）。**TIR 硬约束**：`TherapyMetricsCard.kt :: TherapyMetricsCardFromDataStore` 取 `latestItemsForTypes(CgmReadingHistoryLogs, 288)` ≈ 24h（当前实现是 **24h、非多天**，仓库内无 TBR/TAR/GMI 多天统计）；**任何未来裁剪历史库的提议，必须先保证窗口 ≥ 24h + 余量（≥288 条 CGM）**，若将来引入多天统计，则按多天窗口重算。容量事实：`InitialHistoryLogCount=5000` 只是首次追赶的 seqId 跨度（不是上限）；内存只有 `recentSeqIds = LruCache(256)`（按 seqId 去重）与 `itemLruCache = LruCache(500)`（解析缓存）；Room 表 `pumpdata_historylog` 以 `["seqId","pumpSid"]` 为主键、`insert` 用 `IGNORE` 去重，**除调试 `deleteAll()` 外无任何清理，长期无界增长**（下限约 288 行/天）。
- **术语表：历史记录 / 消息缓存 / 本地数据库 / 调试日志文件（2026-11-04）**：**历史记录** = 泵侧下载的历史流水（Room `pumpdata_historylog`，图表与 TIR 的数据源）；**消息缓存** = 调试窗口临时保存的已收消息（`DataStore.historyLogCache`，「查看消息缓存」那块）；**本地数据库** = Room/SQLite 持久层本体（清空数据库相关操作）；**调试日志文件** = Timber 文件日志。禁止再用「历史日志 / 日志缓存 / 运行日志 / 调试记录」互借。落地方式：**只改取值、不改 key**，en/zh 两侧与两处 TM CSV 同步（lab WORKLOG §75）。上游 TIR 未完成（仅 24h/288 点/70–180 占比）只记 `docs/upstream-pr-candidates.md` §8，不进界面文案。

### 未完成的下一步

1. **大剂量 v2 批次 A（当前阻塞点）**：按 `docs/feature/大剂量功能设计-v2.md` §5.6 的 **S0 纯状态层**起步（可单测、零泵交互），S1–S5 跟随；`-98` 未验期间不先做完整发送链路，S5 后单独一个提交做 **S6-prep 11 参探针**（走生产通道 `TO_SERVER_BOLUS_REQUEST_PHONE`，不走 HTTP BULK）。0.05U/25U、扩展最小 0.05U、CIQ 2h/非 CIQ 8h、不自建 90s 超时等已按说明书 7/12/13 章修订（§5.6 末表）。批次 B（向导）现在只做置灰入口（§5.7）。阈值外置方案见 `docs/feature/阈值配置外置-设计.md`（**仅设计，待实现**）。**S9 已提前落地（开关与导航入口已实现，v2 入口暂为占位页）**：设置页「自动读取历史记录」同组新增「使用新的大剂量输注」开关（pref key `pumppan-bolus-ui`，默认关闭），开启后导航栏中间出现大剂量图标（复用旧 drawable）、Dashboard 右下角旧 FAB 隐藏；判断集中在 `com.pumppan.controlx2.bolus.BolusRouter`，点击进入 `BolusPlaceholderPage`（无输入框、不发泵命令），S4 落地后直接替换该文件即可。
2. **TIR 三档**：见 `docs/feature/TIR多日汇总-远期目标.md`（档 1 多窗口 + 五区间 + 覆盖率 + GRI（阈值可配）→ 档 2 AGP / 逐日曲线 / 变异度 / LBGI / 低血糖事件频次 → 档 3 可视化 + 导出）；依赖 C 方案 + 自动读取开启 + 首次追赶完成。
3. **蓝牙断连**：看门狗 / 变主动 / 指数退避 / UI 快捷开关四条方向见 `docs/bug-蓝牙断连.md`（`57bc4c5`），是否动 `pumpcomm` 待拍板；真机已确认今日断联为手机端 BLE、泵侧零异常（2026-11-05），可优先按此定向。
4. **术语遗留**：`app_no_debug_logs_are_available_to_share` / `app_debug_logs_found` 仍用「调试记录」；建议未来重命名 3 个 key（`debug_view_history_log_mess`、`debug_no_history_log_entries_present_in_cache`、`debug_database_and_cache_cleared`）。原「两个同名入口」已解决：缓存查看器改叫「缓存查看器 / Cache Viewer」（用户定调：调试页不必严格套术语表，取短），拉取入口仍为「查看历史记录」。
5. **真机待验三项**：清空缓存后重拉、充电图标暗色可读性、~~声音页 X2 只读表现~~（2026-11-04 已验证：X2 不能远程改声音，Mobi-only 门控正确，见 WORKLOG §79）。
6. **能力表 / 巡检**：`shared` 侧 `CapabilityRegistry` 只读骨架（方案文档为 `docs/feature/泵能力收敛与自建挡板方案_main.md`，来自 main 树）；是否每周巡检 pumpx2 主线 + tag。

> 以上细节与「已改 / 没改 / 要改」三态清单统一见 `docs/old/工作清单-2026-11-02至04.md`（内容已并入 lab `i18n/WORKLOG.md` §78），HANDOFF 不再重复维护。

### 已知基线失败

- `VicoCgmChartTest`：DST 时区下失败（`UnitsRenderer` 用当前时刻算偏移），**与本次改动无关**，勿误判为回归。

---

## 一句话现状

pumppan 分支已完成「只读表达改造 + 设置页/Debug 页重排 + 暗色可读性修复 + 声音设置/睡眠计划/充电图标改造 + 术语收敛」，`:mobile:compileDebugKotlin` 通过。2026-11-04 这批：术语收敛（历史记录 / 消息缓存 / 本地数据库，38 key 只改取值、key 未改、en/zh `1090/1084`，`9c392f0`）· 自动读取历史记录开关恢复并单独成组（`de763ef` `728ecb2`）· 首页三项开关在调试页已去重（`8309cee`，回退点 `FeatureFlags.kt :: settingsOnlyFlags`）· 本地数据库 **C 方案**定稿（`1791a54`）· TIR 多日汇总列为远期目标（`aa07d41`）· 工作清单出档（`ec97202`）。release 包对应提交 `438aa3f`（用 debug keystore 签名，可直接安装）；**2026-11-04 已重新出包**：`D:\Programming\apk\mobile-release_pumppan.apk`，22,641,133 字节，SHA-256 `6C76E8FCB761043F…422CE4`，11-04 01:26 编译（`assembleRelease`，签名证书与旧包一致）。11-04 后续：缓存查看器改名以区分拉取入口（`82710ea`）、工作清单归档进 `docs/old/` 并并入 lab `WORKLOG.md` §78。**2026-11-05**：设置/调试/控制页行组件统一为 `BaseRow`（`ActionRow` / `NavigationRow` / `ToggleRow` 全部改调，`fe8a887`），`:mobile:compileDebugKotlin` 通过、未出包；同日 HTTP 调试 API 真机实测（设备 `buildVersion=82710ea`）——只读命令全过，`46/-74` 有响应**证实 V2 可用**，`-94` 权限与 `-96` 取消通过，`-98` 大剂量被 `PumpCommHandler.isBolusCommand` 在 BULK 通道拦截（Timeout → HTTP 500，**未送达未输注**），`debug-write-bt-characteristic` 为 stub 不可用；取消后 `CurrentBolusStatus` / `ExtendedBolusStatusV2` 均 Idle、无残留，泵侧今日零异常（详见 `docs/feature/HTTP取证与真机点检.md` §8 与 lab WORKLOG §80–§82）；用户另将 `docs/pumppan-审查修改意见.md` 归档到 `docs/old/`（`ec92377`）。

---

## 1. 约定（基线）

> **目录分工的唯一权威是 `D:\Programming\controlX2-i18n-lab\i18n\HANDOFF.md` §二（2026-11-04 用户定稿）**。本表只列代码树，不复制全量约定，避免两处漂移。

| 树 | 路径 | 分支 | 定位 |
|----|------|------|------|
| i18n main | `D:\Programming\controlX2-i18n` | main | **i18n main 分支**：紧跟英文 `main`，基本不做改动（发行版）。超出汉化的改动必须先报备获准 |
| i18n dev | `D:\Programming\controlX2-i18n-dev` | dev | **i18n dev 分支**：紧跟上游 `dev` 做汉化版，**基本不提新功能**；上游 bug 只记录、不在这条线提 PR |
| pumppan | `D:\Programming\controlX2-i18n_pumppan` | pumppan | **个人订制版**：在 i18n-dev 基础上做优化，**主战场**（查 bug、改 UI、定制功能）；不合入上游，只推 `i18n-origin/pumppan` |
| lab assets | `D:\Programming\controlX2-i18n-lab` | assets | **汉化工具与产物**：TM、rules、tools、WORKLOG、HANDOFF，不含产品代码 |

其余目录（只读/产物，全量见 lab HANDOFF §二）：`controlX2-main_dev` = 英文上游 main+dev 镜像；`controlX2-main-zh` = 硬替换老版本，已冻结；`pumpx2-main-dev` = 底层库镜像（main+dev）；`apk` = 唯一产物目录（`_dev` / `_pumppan` 后缀）。

**远端**：`i18n-origin` → `https://gitee.com/pumppan/controlX2-i18n.git`
**硬约束**：

- 新增 UI 文案必须走 `strings.xml` + TM，不硬编码；
- `pumpcomm` / `shared` 模块不引入 Compose；
- 回归禁带两项：72dp 固定行高、`Color.Red` 红字（原第三项「充电图标镂空矢量」已于 2026-11-03 解除，改为本地采用，`ce4db28`）；
- 已冻结的决策：子菜单折叠方案否决（`SettingsGroup` 死代码已删除）；「行可点、弹窗按钮灰」为最终交互规则。
- **X2 只读口径（2026-11-03 真机定案）**：X2 控制页**全部只读，仅大剂量可执行**，`canControlPump` 保持 `model == MOBI`——**这不是缺陷，勿擅自放开**；等 pumpx2 库按固件版本号收敛能力后再评。

### 1.1 构建与签名约定

1. **签名沿用现状**：继续使用仓库内公开的 `debug.keystore`（`mobile/build.gradle` 的 `signingConfigs.release` 块，默认口令 `password`、别名 `alias_name`）。三棵树 main / dev / pumppan 的配置与 keystore 完全一致（同一 blob `43135b2f`，上游 `65c6a95` 提交）；**release 证书 SHA-256 前缀 `1289b7a3…`**。debug 走本机 AGP 自动生成的 `~/.android/debug.keystore`（证书 `6d5cd025…`）。
2. **默认构建 release**：日常出包用 `.\gradlew :mobile:assembleRelease`，不再用 debug。
3. **产物命名约定**：三条线用后缀区分，避免覆盖，统一放 `D:\Programming\apk`：

   | 支线 | mobile | wear |
   |------|--------|------|
   | main | `mobile-release.apk`（**不带后缀**） | `wear-release.apk`（不带后缀） |
   | dev | `mobile-release_dev.apk`（`_dev`） | `wear-release_dev.apk`（`_dev`） |
   | pumppan | `mobile-release_pumppan.apk`（`_pumppan`） | （暂无） |

   `controlX2_mobile-release_约旦大兵汉化.apk` / `controlX2_wear-release_约旦大兵汉化.apk` 属旧 main-zh 遗留产物，**不再产出、不要覆盖**（仅作存档）。
4. **覆盖签名途径**：只能通过 JVM 系统属性 `-DRELEASE_STORE_FILE=… -DRELEASE_STORE_PASSWORD=… -DRELEASE_KEY_ALIAS=… -DRELEASE_KEY_PASSWORD=…`（`build.gradle` 读的是 `System.properties`）；写进 `gradle.properties` 无效。
5. **注意**：debug 与 release 证书不同（`6d5cd025…` vs `1289b7a3…`），同一 `applicationId` 不能互相覆盖安装，切换前先卸载。

**当前产物状态**（2026-11-04 复核）：`D:\Programming\apk\mobile-release_pumppan.apk`，22,641,133 bytes，2026-11-04 01:26 构建，签名 `1289b7a3…`。**不含 2026-11-03 之后的改动**（用户要求暂不出版本包）。

### 1.2 环境与单测

1. **本机资产目录 `D:\winapps`（缺工具链先在这里找，不要重复下载）**：

   | 目录 | 体积 | 用途 |
   |------|------|------|
   | `android-sdk` | ~11.7 GB | Android SDK，`ANDROID_HOME` / `ANDROID_SDK_ROOT`（机器级） |
   | `android-avd` | ~11.0 GB | 模拟器镜像 |
   | `.gradle` | ~4.5 GB | **`GRADLE_USER_HOME`**：依赖缓存、wrapper、init.d |
   | `robolectric` | ~1.5 GB | 11 个 `android-all-instrumented` jar（SDK 26–36），平铺存放 |
   | `jdk-21.0.12.1` | ~318 MB | `JAVA_HOME`（用户级）+ `org.gradle.java.home` |
   | `node-v24.22.1-win-x64` | ~102 MB | Node 运行时 |

2. **Gradle init 脚本只能放 `D:\winapps\.gradle\init.d\robolectric-mirror.gradle`**：`GRADLE_USER_HOME=D:\winapps\.gradle` 是机器级设置，放 `%USERPROFILE%\.gradle\init.d` **不会加载**。脚本做两件事：① 追加阿里云 Maven 镜像（`maven.aliyun.com/repository/public`、`/google`，不替换原有 `google()/mavenCentral()/jitpack`）；② 给所有 `Test` 任务设 `robolectric.offline=true` 与 `robolectric.dependency.dir=D:\winapps\robolectric`。回退方式：删除该文件。

3. **终端 `CI=true` 会踩坑**：`mobile/build.gradle` 中 `CI` 分支的 Robolectric 配置在 `CI=true` 时把 Robolectric 切到离线模式并指向 `~/.robolectric`（只有 API 35 一个 jar），导致 40 例测试报 `Path is not a file`。本地跑测前先 `Remove-Item Env:\CI`。（`CI` 只存在于当前终端会话，不是用户级/机器级变量。）

4. **`local.properties` 被 `.gitignore` 的 `local.properties` 规则忽略**：三棵树各自已有 `sdk.dir=D:/winapps/android-sdk`，但新 worktree **不会自动生成**；因 `ANDROID_HOME` 已在机器级，AGP 可回退环境变量，不阻塞构建。

5. **测试现状**（2026-11-03）：`:shared` 15/15 通过，`:mobile` 98/99 通过；唯一失败是基线 DST 用例 `VicoCgmChartTest.toCgmDataPoint_appliesPumpLocalTimezoneCorrection`，三棵树实现与测试完全一致，见 [bug-基线单测DST失败.md](bug/bug-基线单测DST失败.md)。

6. **Robolectric jar 缺失时**：从阿里云 `https://maven.aliyun.com/repository/central/org/robolectric/android-all-instrumented/<ver>/` 取（`<ver>` 形如 `13-robolectric-9030017-i7`），jar **平铺**放入 `D:\winapps\robolectric`（文件名即 `android-all-instrumented-<ver>.jar`），Robolectric 从此目录直接读取，不再写 C 盘 `~/.m2` 或 `~/.robolectric`。

---

## 2. 推进路线

1. 功能先在 **pumppan** 验证（编译 + 真机）；
2. 回主线时按 **main → dev** 顺序规划移植点，不做反向；
3. 迁移清单（本次未纳入 pumppan 的 main-zh 改动全量登记）：
   `D:\Programming\controlX2-i18n\docs\feature\UI迁移清单-controlX2-main-zh-to-i18n.md`
4. 批次划分见该清单 B1–B5；其中 **B5（TIR 数据源适配）已完成**（见 §4 与 `TherapyMetricsCard :: TherapyMetricsCardFromDataStore`），其余批次已在本分支以「按 pumppan 场景重写」的方式落地，**不是原样 cherry-pick**。

---

## 3. 本轮已做（含证据）

### 3.1 型号门控与只读表达

- **型号门控数据层**：`CommService.kt` 中临时基础率按钮的 `determinePumpModel(Prefs(applicationContext).pumpModelName())`（原引用未解析的 `dataStore`）；写入点 `CommService.kt` 连接回调的 `setPumpModelName`。
- **控制页 13 行组件化**：`Actions.kt` 统一行样式；`RowStyle.kt` 新增 `DangerousRowMinHeight = 88.dp`。
- **子菜单「行可点、弹窗按钮灰」**：入口/操作行保持可点，禁用落在弹窗确认按钮，并在弹窗正文顶部渲染原因（复用 `cartridge_insulin_control_unsupported_model` + 型号）。
  落点：`CGMActions.kt`、`QuickBolusSettingsActions.kt`、`ControlIQSettingsActions.kt`、`SafetyLimitsActions.kt`、`ProfileActions.kt`；
  组件参数化：`AddProfileDialog.kt` / `AddSegmentDialog.kt` / `EditSegmentDialog.kt` 增加 `enabled` + `reasonText`。
  例外（规则允许）：`ProfileActions.kt` 的「刷新缓存」「激活配置」两处点击即执行、无弹窗，保留行级灰。
- **同步泵时间 / 查找泵**：已从设置页搬到控制页底部（`Actions.kt` 中 `settings_sync_pump_time` 与「查找泵」两个 `ListItem` + 对应 Dialog），设置页原位置仅保留注释。

### 3.2 设置页

- **重排与分隔线**：`Settings.kt` 两档，组间 `SettingsDivider()`（1.5dp + `colorScheme.outline`，与控制页 `GroupBoundary` 同规格）、组内 `SettingsRowDivider()`（0.5dp + `colorScheme.outlineVariant`，与 `GroupDivider` 同规格），均无额外留白；全页 8 处组间线、12 处组内线。无组标题（「首页显示」标题按用户要求注释保留、不渲染）。
- **顺序**（最终渲染）：语言 → 启用服务 → 强制重载 → 重新配置泵 → 重新配置应用 → 泵主机设备 → 重置 → 大剂量按钮 → 临时基础率按钮 → 首页显示（3 开关）→ 自动读取历史记录（单独成组）→ Nightscout → xDrip → 调试选项。
- **支持包搬家**：菜单与弹窗移到 Debug 页（`Debug.kt` 的 `showSupportBundleDialog` 所在块），资源 key **不改名**，仍用 `settings_*`；设置页整块以注释保留（`Settings.kt` 中标记 `// 已搬去 Debug 页` 的两处注释块）。
- **常驻通知两行开关**：搬至 Nightscout 之前，原位置注释保留（`Settings.kt` 中标记 `// 已移至 Nightscout 前` 的注释块）。
- **首页显示三开关**：`Settings.kt` 的「首页显示」开关区；后端 `FeatureFlag.kt` 的枚举 + `defaultValue` + 全局 `mutableStateOf` + `set()` 同步写 Prefs（`cgmChartVisibleState` / `sensorInfoCardVisibleState` / `historyLogSyncBarVisibleState`）；消费点 `Dashboard.kt` 的 CGM 图表 / 传感器卡 / 历史同步条三处；启动初始化 `PumppanApplication` 的 `FeatureFlag.initState(this)`。

### 3.3 Debug 页

- **六组重排**：G1 禁用服务 / G2 支持包·发送泵消息·查看消息缓存·同步进度·查看历史记录（拉取）·缓存查看器 / G3 清除调试日志文件·清空本地数据库 / G4 弹窗开关·HTTP API / G5 泵状态·泵型号覆盖 / G6 功能开关 / 页尾返回。
- **支持包搬入**：`Debug.kt` 的 `showSupportBundleDialog` 所在块，弹窗正文改用既有 `settings_send_debug_files_with_logs_query`（后统一为 `debug_*` 引用），未新增 key。
- **蓝牙监听整段注释**：`Debug.kt` 中 `FeatureFlags.ConnectionSharing` 判断整段（2026-11-03 复核：`if (FeatureFlags.ConnectionSharing ...)` 整块被注释）。原因：t:connect 共享连接已被上游关闭，`FeatureFlags.ConnectionSharing` 为 `const false`，开关已无实际用途；代码原样保留备查。
- **三个全屏查看器暗色修复**：新增 `DebugPopupSurface`（`Debug.kt` 的 `DebugPopupSurface` 组合函数，`Surface(surface/onSurface)` + `fillMaxSize`），替换 `Color.Black/DarkGray` 半透明遮罩与 `Color.White` 白底；应用于消息缓存 / 历史记录 / 泵状态三个查看器（`DebugPopupSurface` 的三个调用点）。
- **「缓存查看器」空数据护栏**：`Debug.kt` 中该查看器的空数据护栏（缓存为空时 Toast 提示 `debug_history_log_messages_not_loaded`，不打开弹窗），避免空白全屏遮罩 / 响应解析链上的 null 崩溃。
- **顺带修掉搬移引入的三处编译错误**：`Debug.kt` 的 `SupportBundleSummary` import 路径 `presentation.util.SupportBundleSummary`；`Debug.kt` 补 `AlertDialog` / `TextButton` import；`PumppanSettingsRows.kt` 的 key 名 `pumppan_pump_model_refresh_hint`。

### 3.4 资源与 TM

- 新增 key（仅本轮）：`debug_history_log_messages_not_loaded`（`values/strings.xml` 与 `values-zh-rCN/strings.xml` 同名 key）。
- 首页显示 7 个 `settings_home_show_*`（en/zh 双侧各 7 条）。
- TM：`i18n-pumppan/tm/translations_cn.csv` 已登记上述条目；**主 TM 不登记 pumppan 定制 key**（主 TM 只服务 main 汉化线，pumppan 定制功能回主线时再补）。

### 3.5 构建产物

- `.\gradlew :mobile:assembleRelease` → **BUILD SUCCESSFUL in 5m 17s**
- 产物：`mobile/build/outputs/apk/release/mobile-release.apk`，22,621,279 bytes
- 签名：`mobile/build.gradle` 的 `signingConfigs.release` 块默认使用 `../debug.keystore`，`buildTypes.release.minifyEnabled false` → **已签名、可直接安装**
- 已复制为 `D:\Programming\apk\mobile-release_pumppan.apk`（覆盖旧的 22,599,571 bytes / 2026-11-02 03:13 同名文件），MD5 `1EEF1BD86DB0C9450220066E761CF452` 与源一致

---

## 4. 未完成 / 待办

> 2026-11-04 起的统一待办见 `docs/old/工作清单-2026-11-02至04.md` 与 lab `i18n/WORKLOG.md` §78；本节保留 2026-11-03 快照细节。

| 项 | 说明 |
|----|------|
| 真机暗色复核 | `DebugPopupSurface` 只换了主题 token，浅色/暗色两套都要实机看：三查看器的 ✕/导出按钮、筛选框标签、条目计数条 |
| 测试 | `:mobile:compileDebugKotlin` 通过，但**未跑单测 / roborazzi 截图测试** |
| Debug 页遗留缺陷 | 同步泵时间失败走 5 秒静默降级；过滤框把输入直接当过滤值（非崩溃诱因，仅交互瑕疵）〔2026-11-04 复核：HTTP API 启停 / 资格事件 / 清除调试日志均已改用资源并带反馈（`be6a92e`）；同步进度条 50dp 空白已修，`HistoryLogSyncProgressBar.kt` 完成态提前 `return`〕 |
| TIR 适配 | 迁移清单 **B5 已完成**（`TherapyMetricsCard :: TherapyMetricsCardFromDataStore`，最近 288 条 CGM 读数实算） |
| TM 边界 | pumppan 定制 key 只进 `i18n-pumppan` TM，主 TM 保持干净 |
| wip 三件套 | 按设计重做，不继承旧分支实现 |
| 向上游递进 | 充电图标挖空矢量已在本分支实施（`HorizBatteryIcon.kt :: getPainterForBatteryPercent` + 6 × `battery_horiz_*_charging.xml`，`ce4db28`；原「禁带」已解除）；连同 PR #162 细节等需向 GitHub 提的事项，**等用户决定**，不在本分支擅自动手 |

---

## 附 A · 常用命令

```powershell
# 进入工作树
cd D:\Programming\controlX2-i18n_pumppan

# 仅编译（快速校验）
.\gradlew :mobile:compileDebugKotlin --console=plain

# 构建 release 包（输出 mobile\build\outputs\apk\release\mobile-release.apk）
.\gradlew :mobile:assembleRelease --console=plain

# 推送（不带 --force）
git push i18n-origin pumppan

# 核对远端与本地
git ls-remote i18n-origin pumppan
git status --short
```

---

## 附 B · 本次改动文件清单

```
mobile/src/main/java/com/jwoglom/controlx2/presentation/screens/sections/Debug.kt         | 389 +++++-----
mobile/src/main/java/com/jwoglom/controlx2/presentation/screens/sections/Settings.kt      |  49 ++-
mobile/src/main/java/com/pumppan/controlx2/PumppanSettingsRows.kt                         |   2 +-
mobile/src/main/res/values-zh-rCN/strings.xml                                            |   1 +
mobile/src/main/res/values/strings.xml                                                   |   1 +
5 files changed, 236 insertions(+), 206 deletions(-)
```

（另有本文件 `docs/HANDOFF-pumppan.md` 为新增。）
