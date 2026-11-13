# HANDOFF · pumppan 分支交接记录

> 生成日期：2026-11-02（构建产物时间 2026-11-03 00:02）　最后同步：**2026-11-12**（docs 归档 8 份 / 查询器 overlays 移除 / DST 单测修复 / 定时大剂量与通知页流水两份设计定稿 / `HISTORY` 重复触发收口 / `waitForLoaded` 有上限收敛）
> 分支：`pumppan`　工作树：`D:\Programming\controlX2-i18n_pumppan`
> 当前 HEAD：以 `git log -1` 为准（本批：查询器收口 + `waitForLoaded` 收敛）。**已推送** `i18n-origin/pumppan`。
> **提交范围（2026-11-12 晚）**：代码与 `docs/HANDOFF-pumppan.md`、`docs/upstream-pr-candidates.md`；**其余 docs 一律不入库**（归档 8 份、`README`/索引/设计文档改动只留在工作区）。个人笔记（`docs/doubao 历史.txt`、`docs/trae-cn 历史.txt`、`docs/危险指令 pass.txt`）同样未提交。
> 本文档自包含，可脱离对话记录直接接手。

## 重启前状态（2026-11-03）

### 三树 HEAD（均已推送）

| 树 | 分支 | HEAD | 主题 |
|---|---|---|---|
| pumppan | `pumppan` | 以 `git log -1` 为准 | 2026-11-12 提交范围：代码（查询器删 `overlays`/`PolicyResolver`、`coveredDomains`、`waitForLoaded` 收敛、DST 修复、只读白名单 + 测试）+ `HANDOFF-pumppan.md` / `upstream-pr-candidates.md`；其余 docs 仅工作区未入库。此前 `ce437f54` 切片 0 收口（`UNKNOWN` 65%→0%）+ 大剂量超时取证 + 状态闩锁自愈；`46a0399b` 查询层收敛定稿 + 通知页流水账草案、`c0c34b92` 断连取证迁危险指令页、`5d04c075` 查询层第 0 步、`7fb08b32` 底栏输注细条、`12d43634` 向导更名；11-08 `491d5d1d` IOB 口径（pumpx2→1.9.3）、`eb574055` 设置自检页、`e83e389d` 三段式页、`8c720c66` 输注期状态机；11-06 `8d665764` S6 发送适配层 |
| i18n main | `main` | `f0543e1` | 归档已完成文档、未完成文档移交 pumppan（2026-11-05 复核，工作区干净） |
| lab | `assets` | `e5c62321` | WORKLOG §95（2026-11-06）；2026-11-09 补记 §96–§103（未提交） |

### 已拍板（不再重复讨论）

- **X2 全只读**：真机实测控制页全部只读、**仅大剂量可执行**；`canControlPump` 保持 `model == MOBI`，**非缺陷、勿擅自放开**；能力收窄**搁置**，等 pumpx2 按固件收敛后再评。
- **多语 key 白名单**：`values-zh-rCN` 为唯一正式目录；mobile 1090/1084（差 6）、wear 348/347（差 1）属刻意例外，**差额扩大即报警**。
- **pumpx2 版本**：**已升到 `1.9.3`**（2026-11-08 `491d5d1d`，因 `BolusType` 语义重构需要）。原先「维持 1.9.0 暂不升级」的表述作废；后续升级门槛仍为「主线发布 ≥ 下一版且含能力相关变更」。
- **充电图标**：改为挖空矢量并本地采用（`ce4db28`），原「禁带」已解除。
- **`SettingsGroup`**：已删除（零调用 + 实现被否决的折叠方案）；`StatusRow` 保留并标注 0 调用。
- **声音页「更改位图」输入框**：已删除（属我们误引入），并补型号门控（非 Mobi 禁用「应用」+ 原因文案）。
- **星期选择器**：`ControlIQSettingsActions :: SleepDaysSelector` 已落地（不依赖 `MultiDay`）。
- **历史缓存清空**：已修复（清空后不再显示旧数据）。
- **首页显示三项开关**（CGM 图表 / 传感器信息 / 历史同步条）：**用户本人要求新增的本地定制**，不是上游功能（设置页 UI 由 `438aa3f` 引入、`637012e` 图标化；枚举与 state 经 zh 表带入，调试页 `FeatureFlags.kt` 原本靠 `FeatureFlag.values()` 自动列出）。`8309cee` 用 `settingsOnlyFlags` 在调试页遍历里排除这三项，UI 只留设置页；枚举、`xxxState`、`initState`、`Dashboard` 消费点**一律未动**。**回退点：`FeatureFlags.kt :: settingsOnlyFlags`** —— 上游若反对/要求收敛，去掉或清空该集合即可让功能开关页重新出现三行，再移除或降级设置页那三项；回退只是 UI 位置切换，不涉及数据与逻辑。若日后纳入上游，应把设置页 UI 迁到 `FeatureFlags` 页并统一 key。
- **历史库策略 = 全量自动拉取（C 方案）定稿（规格决策，2026-11-04）**：不采用内存环形缓冲（A：重启即空、无回填、后台断档）与有界裁剪（B：按 3h/6h 裁会让 TIR 与 24h 图表失真）。**TIR 硬约束**：`TherapyMetricsCard.kt :: TherapyMetricsCardFromDataStore` 取 `latestItemsForTypes(CgmReadingHistoryLogs, 288)` ≈ 24h（当前实现是 **24h、非多天**，仓库内无 TBR/TAR/GMI 多天统计）；**任何未来裁剪历史库的提议，必须先保证窗口 ≥ 24h + 余量（≥288 条 CGM）**，若将来引入多天统计，则按多天窗口重算。容量事实：`InitialHistoryLogCount=5000` 只是首次追赶的 seqId 跨度（不是上限）；内存只有 `recentSeqIds = LruCache(256)`（按 seqId 去重）与 `itemLruCache = LruCache(500)`（解析缓存）；Room 表 `pumpdata_historylog` 以 `["seqId","pumpSid"]` 为主键、`insert` 用 `IGNORE` 去重，**除调试 `deleteAll()` 外无任何清理，长期无界增长**（下限约 288 行/天）。
- **术语表：历史记录 / 消息缓存 / 本地数据库 / 调试日志文件（2026-11-04）**：**历史记录** = 泵侧下载的历史流水（Room `pumpdata_historylog`，图表与 TIR 的数据源）；**消息缓存** = 调试窗口临时保存的已收消息（`DataStore.historyLogCache`，「查看消息缓存」那块）；**本地数据库** = Room/SQLite 持久层本体（清空数据库相关操作）；**调试日志文件** = Timber 文件日志。禁止再用「历史日志 / 日志缓存 / 运行日志 / 调试记录」互借。落地方式：**只改取值、不改 key**，en/zh 两侧与两处 TM CSV 同步（lab WORKLOG §75）。上游 TIR 未完成（仅 24h/288 点/70–180 占比）只记 `docs/upstream-pr-candidates.md` §8，不进界面文案。

### 未完成的下一步

#### 当前第一件事（2026-11-10）

1. **切片 1（通知页流水账）**：`LedgerEntry` + `LedgerStore` + 只计数空实现。三条硬前提：不新增一条泵查询、不参与既有职责（角标/滑动消除/耗材拦截）、不是医疗记录。**设计已定稿（2026-11-12 改判 + 设置页开关）**：`docs/feature/通知页-流水账-设计.md` §1 = **同页两卡片**（报警卡置顶原样不动 + 流水卡在下，不做整页切换/Tab）；设置页开关「通知页显示大剂量流水」默认关闭（`pumppan-ledger-card`=false），开启后通知页才变两卡片；流水纯内存不落库（§10、§15）。
2. **状态闩锁修复已编译进包**（2026-11-10）：`BolusReconciler.finish()` else → `UNCONFIRMED`；`BolusSessionController.onCurrentBolusEmpty` 对 `UNKNOWN` 收空证据 → 自愈 `IDLE`。release 已出包并 `adb install -r`（`buildVersion=ce437f54`）。
3. **查询层切片 0 已收口**：`PumpTopic` 映射补全，`UNKNOWN` 65% → **0%**；NOTIFICATIONS 从不可达变为最大单一消费者。
4. **通知链路全链路实测通过**（2026-11-09）：大剂量超时触发 → 泵推 `[ALERT, HOME_SCREEN_CHANGE]` → `AlertStatusResponse[INCOMPLETE_BOLUS_ALERT]` → 通知页 `Notifications fetched` → 泵上取消 → 通知页消失。
5. **推送已完成**：15 条历史 + `ce437f54` + `39e3c2c5` 已推 `i18n-origin/pumppan`。
6. 五步真机测试（`0/0/0` 干跑 → `0.05U` 立即 → 方波 0:100 → `-96` 取消 → 双波 50:50，合计 ≤0.5U）**已在 2026-11-08 第四轮完成**（`950ef9eb`）：v2 发送路径连打两笔 0.05U、第二笔取消，确认非旧弹窗兜底。剩余疑点见 #59–#64。

#### 其余待办

1. **大剂量 v2 剩余代码**：① 通知改造（补 `setContentIntent`、正文开始/结束时间、`maxRequests=60` 跟随延后段时长；约 3 文件 40–50 行）；② #61 剂量校验三态（空/非法/合法，挂在字段值上，已定案待实现）；③ #64 横幅重构为泵输注状态视图（两层模型、可见性规则、待验两项）。**已完成**：第 2 页三按钮 + 自动命名预设、入口状态机 `phaseToPage`、三段式页、`BolusStatusStrip` 底栏细条。
   **2026-11-10 新增**：第二步校验统一（`isImmediateLegLive` / `BolusForm.validate(delivery)` / `ACTIVE` 分流 /「扩展」开关禁用 / 顶部常驻提示）；P0 延展判定改用结束证据（`expectedEnd` 只作展示）+ `46` 补证按大剂量页可见性门控；细条改两级取消并补「被覆盖的延展段」提示（`priorExtended`）。**仍未做**：细条「预计结束/剩余量」（用户暂缓，蓝牙断连吞数据）。**多笔两行分别取消已于 2026-11-12 结项、不实施**（泵侧 `-91` 顶掉前一笔，持久化不扩），评估记录归档在 `docs/old/细条-多笔并存-评估与待办.md`。
2. **大剂量批次 A 状态**：S0–S6 全部完成，`-98` 生产通道**已于 2026-11-06 真机送达**（泵回 `-97` 拒绝，非发送层丢弃，见 WORKLOG §95）；2026-11-08 第四轮真机确认 v2 发送路径可连打两笔 0.05U 并可取消。批次 B（向导）已落地 IOB 口径与结果页交接；批次 C（定时大剂量提醒）仅登记、不设计（§5.1）。
3. **TIR 三档**：见 `docs/feature/TIR多日汇总-远期目标.md`（档 1 多窗口 + 五区间 + 覆盖率 + GRI（阈值可配）→ 档 2 AGP / 逐日曲线 / 变异度 / LBGI / 低血糖事件频次 → 档 3 可视化 + 导出）；依赖 C 方案 + 自动读取开启 + 首次追赶完成。
4. **蓝牙断连**：看门狗 / 变主动 / 指数退避 / UI 快捷开关四条方向见 `docs/old/bug-蓝牙断连.md`（`57bc4c5`；2026-11-12 阶段完成 · 待细化并归档），是否动 `pumpcomm` 待拍板；真机已确认今日断联为手机端 BLE、泵侧零异常（2026-11-05），可优先按此定向。
5. **术语遗留**：`app_no_debug_logs_are_available_to_share` / `app_debug_logs_found` 仍用「调试记录」；建议未来重命名 3 个 key（`debug_view_history_log_mess`、`debug_no_history_log_entries_present_in_cache`、`debug_database_and_cache_cleared`）。原「两个同名入口」已解决：缓存查看器改叫「缓存查看器 / Cache Viewer」（用户定调：调试页不必严格套术语表，取短），拉取入口仍为「查看历史记录」。
6. **真机待验三项**：清空缓存后重拉、充电图标暗色可读性、~~声音页 X2 只读表现~~（2026-11-04 已验证：X2 不能远程改声音，Mobi-only 门控正确，见 WORKLOG §79）。
7. **能力表 / 巡检**：`shared` 侧 `CapabilityRegistry` 只读骨架（原方案文档已归档：`docs/old/泵能力收敛与自建挡板方案_main.md`，结论过时、仅作追溯）；是否每周巡检 pumpx2 主线 + tag。现状：`canControlPump` 仍是 `model == MOBI` 单规则，18 处调用点各自判断。

> 以上细节与「已改 / 没改 / 要改」三态清单统一见 `docs/old/工作清单-2026-11-02至04.md`（内容已并入 lab `i18n/WORKLOG.md` §78），HANDOFF 不再重复维护。

### 已知基线失败

- **无（2026-11-12）**：`VicoCgmChartTest` 的 DST 失败已修复——`UnitsRenderer.pumpTimeToLocalTz` 改为按事件时刻取偏移（`getOffset(time.toEpochMilli())/1000`），`VicoCgmChartTest` 10 条全绿、`:mobile:testReleaseUnitTest` **383/383**。原记录见 `docs/old/bug-基线单测DST失败.md` §7。

---

## 一句话现状

pumppan 分支已完成「只读表达改造 + 设置页/Debug 页重排 + 暗色可读性修复 + 声音设置/睡眠计划/充电图标改造 + 术语收敛」，`:mobile:compileDebugKotlin` 通过。2026-11-04 这批：术语收敛（历史记录 / 消息缓存 / 本地数据库，38 key 只改取值、key 未改、en/zh `1090/1084`，`9c392f0`）· 自动读取历史记录开关恢复并单独成组（`de763ef` `728ecb2`）· 首页三项开关在调试页已去重（`8309cee`，回退点 `FeatureFlags.kt :: settingsOnlyFlags`）· 本地数据库 **C 方案**定稿（`1791a54`）· TIR 多日汇总列为远期目标（`aa07d41`）· 工作清单出档（`ec97202`）。release 包对应提交 `438aa3f`（用 debug keystore 签名，可直接安装）；**2026-11-04 已重新出包**：`D:\Programming\apk\mobile-release_pumppan.apk`，22,641,133 字节，SHA-256 `6C76E8FCB761043F…422CE4`，11-04 01:26 编译（`assembleRelease`，签名证书与旧包一致）。11-04 后续：缓存查看器改名以区分拉取入口（`82710ea`）、工作清单归档进 `docs/old/` 并并入 lab `WORKLOG.md` §78。**2026-11-05**：设置/调试/控制页行组件统一为 `BaseRow`（`ActionRow` / `NavigationRow` / `ToggleRow` 全部改调，`fe8a887`），`:mobile:compileDebugKotlin` 通过、未出包；同日 HTTP 调试 API 真机实测（设备 `buildVersion=82710ea`）——只读命令全过，`46/-74` 有响应**证实 V2 可用**，`-94` 权限与 `-96` 取消通过，`-98` 大剂量被 `PumpCommHandler.isBolusCommand` 在 BULK 通道拦截（Timeout → HTTP 500，**未送达未输注**），`debug-write-bt-characteristic` 为 stub 不可用；取消后 `CurrentBolusStatus` / `ExtendedBolusStatusV2` 均 Idle、无残留，泵侧今日零异常（详见 `docs/old/HTTP取证与真机点检.md` §8 与 lab WORKLOG §80–§82）；用户另将 `docs/pumppan-审查修改意见.md` 归档到 `docs/old/`（`ec92377`）。**2026-11-09 ~ 11-10**：查询层切片 0 补 `PumpTopic` 映射（`shared/.../query/PumpTopic.kt`，`fromRequestSimpleName`/`fromResponseSimpleName` 两轮补全；`UNKNOWN` 65%→**0%`，NOTIFICATIONS 可达）· 大剂量超时取证全链路闭环（`alert_trigger_20261109b.log`：`[ALERT, HOME_SCREEN_CHANGE]` → `AlertStatusResponse[INCOMPLETE_BOLUS_ALERT, cargo[1]=0x08]` → 通知页有反应 → 泵上取消 → 通知页消失）· **状态闩锁自愈修复**（`BolusReconciler.finish()` else → `UNCONFIRMED`；`BolusSessionController.onCurrentBolusEmpty` 对 `UNKNOWN` 收空证据 → 自愈 `IDLE`；根因：重连后对账整体超时 5s 落 Unknown 且不自愈，导致首页误报「正在输注/断联」）· release 出包 + `adb install -r` 直装（`ce437f54`，23,219,323 bytes）。WORKLOG 已补记 §104；`docs/feature/通知页-流水账-设计.md` §3 已修正（`dispatchExternalMessage` 数据源系**错误**，真实源为 Dashboard 批通知响应 + 泵推送事件）。

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

**当前产物状态**（2026-11-10 复核）：`D:\Programming\apk\mobile-release_pumppan.apk`，23,219,323 bytes，2026-11-10 01:18 构建（`buildVersion=ce437f54`、`buildTime=11/10/2026 00:07:17`、`pumpx2 1.9.3`），签名 `1289b7a3…`，已 `adb install -r` 直装（设备 `4c49b224`）。**含切片 0 映射 + 状态闩锁自愈修复**。

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

5. **测试现状**（2026-11-03 快照）：`:shared` 15/15 通过，`:mobile` 98/99 通过；唯一失败是基线 DST 用例 `VicoCgmChartTest.toCgmDataPoint_appliesPumpLocalTimezoneCorrection`。**2026-11-12 已修复**（`pumpTimeToLocalTz` 改取事件时刻偏移），全量 383/383 绿；记录归档在 [bug-基线单测DST失败.md](old/bug-基线单测DST失败.md)。

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

### 3.6 `HISTORY` 每 5s「计划一次、被拒一次」收口（2026-11-12 17:2x，已装机复验）

- **现象**：`/api/query/stats` 连续多拍 `domains:["HISTORY"], requests:1, sent:0, rejected:1`，
  `nextDueIn.HISTORY` 为极大负值。
- **根因（两条叠加）**：① `Dashboard.dashboardCommands` 仍带 `HistoryLogStatusRequest`，
  进页 / 连接建立 / 下拉刷新三条页面路径会抢先经**同一个** `PumpCommandPort` 发一遍，
  把协调器那一拍压成 `THROTTLED`；② 协调器只在 `sent > 0` 时推进 `lastPlannedAt`，
  被 `THROTTLED`/`DUPLICATE`/`IN_FLIGHT` 挡住时仍每拍重试，退化成 5s 空转。
- **改法**：`Dashboard.kt` 摘掉该请求（`HISTORY` 域独占）；`QueryCoordinator.tick`
  新增 `coveredDomains`——一轮 `sent=0`、未 `hitBusy`、且全部拒绝理由属
  `THROTTLED`/`DUPLICATE`/`IN_FLIGHT` 的域按「已跑」推进计时（`BUSY`/`QUIET`/`SEND_FAILED` 仍不推进）。
- **验证**：`QueryCoordinatorTest` 全绿；`mobile-release.apk` 23,278,968 B 装机后
  启动 4 分钟内 `HistoryLogStatusRequest` 的 `SENT-MESSAGE` **只有 1 条**（改前 3 条），
  `nextDueIn.HISTORY` 由负值恢复为正并正常倒数（+267s → +152s），首页数据正常刷新、无崩溃。
- 细节与时间线：`docs/feature/查询器改造-推进计划.md` §7.14。
- **顺带收敛**：`Dashboard.waitForLoaded` 的「等字段填满」补拉由无限循环改为
  `4 × 2.5s ≈ 10s` 上限（超限打 `gave up ... still null N/12` 后交回协调器周期域）；
  同时把触发点从 `refreshing` 改为独立的 `loadTrigger`（否则收尾置 `false` 会让 effect
  自我重触发、变成每 10s 一轮），并只在 `pumpConnected == true` 时启动。
  `Landing` / `Dashboard` 的整批补拉（刷新按钮、下拉、`onStart`、连接建立）保留，
  理由与分界见 `查询器改造-推进计划.md` §7.14 补记。真机 45s 复验：1 次 `loading done`、
  4 次 CACHED 批、首页数据齐、`nextDueIn` 全正、无 FATAL。

---

## 4. 未完成 / 待办

> 2026-11-04 起的统一待办见 `docs/old/工作清单-2026-11-02至04.md` 与 lab `i18n/WORKLOG.md` §78；本节保留 2026-11-03 快照细节。

| 项 | 说明 |
|----|------|
| **定时大剂量（C 轮）** | **设计定稿 2026-11-12、零实现**：链 3 笔 / 预约池 6 条模板（R21）、外来大剂量按开始证据判 `CHAIN_ABORTED`（R22）、免确认超级开关（R23，默认关不持久化）、提前 2 min / 确认窗口 5 min、流水纯内存 + 单条链状态（§8.0）、参数全部可配（§3.1）。正本 `docs/feature/定时大剂量-设计.md`；C16/C17 已结案，仅余真机校默认值 |
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
