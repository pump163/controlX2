# HANDOFF · pumppan 分支交接记录

> 生成日期：2026-11-02（构建产物时间 2026-11-03 00:02）
> 分支：`pumppan`　工作树：`D:\Programming\controlX2-i18n_pumppan`
> 上一提交：`438aa3f feat(pumppan): 阻断修复 + 只读交互定稿 + 设置页重排 + 首页显示开关`
> 本文档自包含，可脱离对话记录直接接手。

## 一句话现状

pumppan 分支已完成「只读表达改造 + 设置页/Debug 页重排 + 暗色可读性修复」，`:mobile:compileDebugKotlin` 与 `:mobile:assembleRelease` 均通过；release 包已产出并复制到 `D:\Programming\apk\mobile-release_pumppan.apk`（用 debug keystore 签名，可直接安装）。待真机复核暗色表现。

---

## 1. 约定（基线）

| 树 | 路径 | 分支 | 定位 |
|----|------|------|------|
| i18n main | `D:\Programming\controlX2-i18n` | main | **汉化主线**：继承上游代码，只做汉化与补缺；超出汉化的改动必须先报备获准 |
| i18n dev | `D:\Programming\controlX2-i18n-dev` | dev | **紧跟上游 dev**，仅做同步汉化；上游 bug 只记录、不在这条线提 PR |
| pumppan | `D:\Programming\controlX2-i18n_pumppan` | pumppan | **主战场**：查 bug、改 UI、定制功能；需要向上游递进（改 dev/main、向 GitHub 提 PR）的事项一律报用户决定 |
| lab assets | `D:\Programming\controlX2-i18n-lab` | assets | 翻译记忆（TM）、工作记录、工具脚本，不含产品代码 |

**远端**：`i18n-origin` → `https://gitee.com/pumppan/controlX2-i18n.git`
**硬约束**：

- 新增 UI 文案必须走 `strings.xml` + TM，不硬编码；
- `pumpcomm` / `shared` 模块不引入 Compose；
- 回归禁带三件：72dp 固定行高、`Color.Red` 红字、充电图标镂空矢量；
- 已冻结的决策：子菜单折叠方案否决；「行可点、弹窗按钮灰」为最终交互规则。

### 1.1 构建与签名约定

1. **签名沿用现状**：继续使用仓库内公开的 `debug.keystore`（`mobile/build.gradle:12-15` 的 `signingConfigs.release`，默认口令 `password`、别名 `alias_name`）。三棵树 main / dev / pumppan 的配置与 keystore 完全一致（同一 blob `43135b2f`，上游 `65c6a95` 提交）；**release 证书 SHA-256 前缀 `1289b7a3…`**。debug 走本机 AGP 自动生成的 `~/.android/debug.keystore`（证书 `6d5cd025…`）。
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

**当前产物状态**：`D:\Programming\apk\mobile-release_pumppan.apk`，22,621,279 bytes，2026-11-03 构建，签名 `1289b7a3…`。

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

3. **终端 `CI=true` 会踩坑**：`mobile/build.gradle:78-80` 在 `CI=true` 时把 Robolectric 切到离线模式并指向 `~/.robolectric`（只有 API 35 一个 jar），导致 40 例测试报 `Path is not a file`。本地跑测前先 `Remove-Item Env:\CI`。（`CI` 只存在于当前终端会话，不是用户级/机器级变量。）

4. **`local.properties` 被 `.gitignore:18` 忽略**：三棵树各自已有 `sdk.dir=D:/winapps/android-sdk`，但新 worktree **不会自动生成**；因 `ANDROID_HOME` 已在机器级，AGP 可回退环境变量，不阻塞构建。

5. **测试现状**（2026-11-03）：`:shared` 15/15 通过，`:mobile` 98/99 通过；唯一失败是基线 DST 用例 `VicoCgmChartTest.toCgmDataPoint_appliesPumpLocalTimezoneCorrection`，三棵树实现与测试完全一致，见 [bug-基线单测DST失败.md](bug-基线单测DST失败.md)。

6. **Robolectric jar 缺失时**：从阿里云 `https://maven.aliyun.com/repository/central/org/robolectric/android-all-instrumented/<ver>/` 取（`<ver>` 形如 `13-robolectric-9030017-i7`），jar **平铺**放入 `D:\winapps\robolectric`（文件名即 `android-all-instrumented-<ver>.jar`），Robolectric 从此目录直接读取，不再写 C 盘 `~/.m2` 或 `~/.robolectric`。

---

## 2. 推进路线

1. 功能先在 **pumppan** 验证（编译 + 真机）；
2. 回主线时按 **main → dev** 顺序规划移植点，不做反向；
3. 迁移清单（本次未纳入 pumppan 的 main-zh 改动全量登记）：
   `D:\Programming\controlX2-i18n\docs\feature\UI迁移清单-controlX2-main-zh-to-i18n.md`
4. 批次划分见该清单 B1–B5；其中 **B5（TIR 数据源适配）尚未开始**，其余批次已在本分支以「按 pumppan 场景重写」的方式落地，**不是原样 cherry-pick**。

---

## 3. 本轮已做（含证据）

### 3.1 型号门控与只读表达

- **型号门控数据层**：`CommService.kt:905` 临时基础率按钮门控原引用未解析的 `dataStore`，改为 `Prefs(applicationContext).pumpModelName()`；写入点 `CommService.kt:478`（连接回调 `setPumpModelName`）。
- **控制页 13 行组件化**：`Actions.kt` 统一行样式；`RowStyle.kt` 新增 `DangerousRowMinHeight = 88.dp`。
- **子菜单「行可点、弹窗按钮灰」**：入口/操作行保持可点，禁用落在弹窗确认按钮，并在弹窗正文顶部渲染原因（复用 `cartridge_insulin_control_unsupported_model` + 型号）。
  落点：`CGMActions.kt`、`QuickBolusSettingsActions.kt`、`ControlIQSettingsActions.kt`、`SafetyLimitsActions.kt`、`ProfileActions.kt`；
  组件参数化：`AddProfileDialog.kt` / `AddSegmentDialog.kt` / `EditSegmentDialog.kt` 增加 `enabled` + `reasonText`。
  例外（规则允许）：`ProfileActions.kt` 的「刷新缓存」「激活配置」两处点击即执行、无弹窗，保留行级灰。
- **同步泵时间 / 查找泵**：已从设置页搬到控制页底部（`Actions.kt:747 / 763` 两个 ListItem + 对应 Dialog），设置页原位置仅保留注释。

### 3.2 设置页

- **重排与分组**：`Settings.kt:85` 起新增统一 `SettingsDivider()`（`1.dp` + `outlineVariant.copy(alpha = 0.38f)`，无额外留白），全页 17 处分隔线一致。
- **顺序**（最终渲染）：语言 → 启用服务 → 强制重载 → 重新配置泵 → 重新配置应用 → 泵主机设备 → 重置 → 大剂量按钮 → 临时基础率按钮 → 首页显示（3 开关）→ Nightscout → xDrip → 调试选项。
- **支持包搬家**：菜单与弹窗移到 Debug 页（`Debug.kt:1104`），资源 key **不改名**，仍用 `settings_*`；设置页整块以注释保留（`Settings.kt:135 / 693`，标记 `// 已搬去 Debug 页`）。
- **常驻通知两行开关**：搬至 Nightscout 之前，原位置注释保留（`Settings.kt:247`，标记 `// 已移至 Nightscout 前`）。
- **首页显示三开关**：`Settings.kt:431-483`；后端 `FeatureFlag.kt:18-20 / 32-34 / 52-58 / 63-67`（枚举 + defaultValue + 全局 `mutableStateOf` + `set()` 同步写 Prefs）；消费点 `Dashboard.kt:226 / 240 / 247`（CGM 图表 / 传感器卡 / 历史同步条）；启动初始化 `PumppanApplication.kt:24` `FeatureFlag.initState(this)`。

### 3.3 Debug 页

- **六组重排**：G1 禁用服务 / G2 支持包·发送泵消息·接收缓存·同步进度·查看历史日志·显示泵运行日志 / G3 清除日志·清空数据库 / G4 弹窗开关·HTTP API / G5 泵状态·泵型号覆盖 / G6 功能开关 / 页尾返回。
- **支持包搬入**：`Debug.kt:1104`，弹窗正文改用既有 `settings_send_debug_files_with_logs_query`（后统一为 `debug_*` 引用），未新增 key。
- **蓝牙监听整段注释**：`Debug.kt:796-838`。原因：t:connect 共享连接已被上游关闭，`FeatureFlags.ConnectionSharing` 为 `const false`，开关已无实际用途；代码原样保留备查。
- **三个全屏查看器暗色修复**：新增 `DebugPopupSurface`（`Debug.kt:177-197`，`Surface(surface/onSurface)` + `fillMaxSize`），替换 `Color.Black/DarkGray` 半透明遮罩与 `Color.White` 白底；应用于接收缓存（`524`）、历史日志（`671`）、泵状态（`1030`）。
- **「显示泵运行日志」空数据护栏**：`Debug.kt:630-641`，缓存为空时 Toast 提示（`debug_history_log_messages_not_loaded`）而不打开弹窗，避免空白全屏遮罩 / 响应解析链上的 null 崩溃。
- **顺带修掉搬移引入的三处编译错误**：`Debug.kt:137` import 路径 `presentation.util.SupportBundleSummary`；`Debug.kt:44 / 58` 补 `AlertDialog` / `TextButton`；`PumppanSettingsRows.kt:78` key 名为 `pumppan_pump_model_refresh_hint`。

### 3.4 资源与 TM

- 新增 key（仅本轮）：`debug_history_log_messages_not_loaded`（`values/strings.xml:291`、`values-zh-rCN/strings.xml:285`）。
- 首页显示 7 个 `settings_home_show_*`（en/zh 双侧各 7 条）。
- TM：`i18n-pumppan/tm/translations_cn.csv` 已登记上述条目；**主 TM 不登记 pumppan 定制 key**（主 TM 只服务 main 汉化线，pumppan 定制功能回主线时再补）。

### 3.5 构建产物

- `.\gradlew :mobile:assembleRelease` → **BUILD SUCCESSFUL in 5m 17s**
- 产物：`mobile/build/outputs/apk/release/mobile-release.apk`，22,621,279 bytes
- 签名：`mobile/build.gradle:12` release signingConfig 默认使用 `../debug.keystore`，`minifyEnabled false` → **已签名、可直接安装**
- 已复制为 `D:\Programming\apk\mobile-release_pumppan.apk`（覆盖旧的 22,599,571 bytes / 2026-11-02 03:13 同名文件），MD5 `1EEF1BD86DB0C9450220066E761CF452` 与源一致

---

## 4. 未完成 / 待办

| 项 | 说明 |
|----|------|
| 真机暗色复核 | `DebugPopupSurface` 只换了主题 token，浅色/暗色两套都要实机看：三查看器的 ✕/导出按钮、筛选框标签、条目计数条 |
| 测试 | `:mobile:compileDebugKotlin` 通过，但**未跑单测 / roborazzi 截图测试** |
| Debug 页遗留缺陷 | 硬编码英文 Toast（HTTP Debug API 启停、资格事件）；清除调试日志成功后无反馈；同步泵时间失败走 5 秒静默降级；同步进度条固定 50dp 空白；过滤框把输入直接当过滤值（非崩溃诱因，仅交互瑕疵） |
| TIR 适配 | 迁移清单 **B5**，未开始 |
| TM 边界 | pumppan 定制 key 只进 `i18n-pumppan` TM，主 TM 保持干净 |
| wip 三件套 | 按设计重做，不继承旧分支实现 |
| 向上游递进 | 充电图标矢量、PR #162 细节等需向 GitHub 提的事项，**等用户决定**，不在本分支擅自动手 |

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
