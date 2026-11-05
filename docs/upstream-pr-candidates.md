# 待提上游 PR 清单（pumppan 分支）

> 背景：上游近段时间几乎没有活动，本分支修好但**不只属于汉化**的问题先记在这里，等上游恢复后按条目单独提 PR。
> 约定：每条注明「是否需要纯英文 PR」与「是否与我们的 i18n 分支改动冲突」；行号以本文件撰写时（2026-11-03）的三棵树为准，上游合入后请复核。

三棵树：
- main：`D:\Programming\controlX2-i18n`
- dev：`D:\Programming\controlX2-i18n-dev`
- pumppan：`D:\Programming\controlX2-i18n_pumppan`

状态取值：`已在我们分支修`（pumppan 已落地）/ `仅记录未修`。

---

## 1. 声音设置（`SoundSettingsActions.kt`）

### 1.1 `CGM_ALERT(64)` 漏算 —— 已在我们分支修
- **问题**：自动推导的「本次修改范围」位掩码只累加 `QUICK_BOLUS(2)/GENERAL(4)/REMINDER(8)/ALERT(16)/ALARM(32)`，漏掉 `CGM_ALERT(64)`；用户改了 CGM 警报 B 后掩码仍不含 64，泵不会更新该项。
- **证据**：main `mobile/.../SoundSettingsActions.kt:174-193`（`changed` 列表构造）、`:350-351`（`cgmBText` 与 `changeBitmaskText` 一并传入请求）；dev 同文件 `:164-183`、`:333-334`。位定义见 pumpx2 `SetPumpSoundsRequest.ChangeBitmask`（2/4/8/16/32/64，`fromBitmask` 用 `(bitmask & id) != 0`）。
- **建议改法**：`cgmBText` 相对基线变化时 `changed.add(ChangeBitmask.CGM_ALERT)`。
- **影响面**：声音设置页（Mobi），一处计算。
- **是否需纯英文 PR**：否（无文案变更）。
- **与 i18n 分支冲突**：会；本分支已在 `9da6a96` 修，改动集中在该 `run { }` 块，冲突面小。

### 1.2 「更改位图」掩码可手填 → 双写源 —— 已在我们分支修
- **问题**：UI 同时存在「代码自动推导的掩码」和「用户可编辑的数字输入框」，两套写源，用户手填的值会直接进请求。
- **证据**：main `SoundSettingsActions.kt:330-332`（`OutlinedTextField(label = sound_change_bitmask, onValueChange = changeBitmaskText)`）；dev `:313-315`。
- **建议改法**：删除输入框，掩码完全由代码推导（用户只会看到自己改了哪几项的结果）。
- **影响面**：声音设置页。
- **是否需纯英文 PR**：否。
- **与 i18n 分支冲突**：会；本分支已在 `cef56d8` 删除（含孤儿文案 key `sound_change_bitmask`）。

### 1.3 无校验、无发送反馈、泵拒绝被静默吞掉 —— 部分已修
- **问题**：发送前用 `toIntOrNull() ?: 0` 静默归零（main `:350-351`、dev `:333-334`）；发送后不读 `SetPumpSoundsResponse.status`，X2 泵拒绝时界面无任何反馈。
- **证据**：三棵树全文均无 `SetPumpSoundsResponse` 引用（grep 结果为空）；main `:343-352` 只有 `sendPumpCommands`。
- **建议改法**：发送前范围校验 + Toast；发送后读取 `SetPumpSoundsResponse` 并提示成功/失败；对不支持写入的型号禁用应用按钮并说明原因。
- **影响面**：声音设置页 + 消息回读链路（需确认 `sendMessage` 回调是否已消费 response）。
- **是否需纯英文 PR**：否（文案可随 PR 附 en 默认值）。
- **与 i18n 分支冲突**：会；本分支已在 `94cf7bc`（校验+反馈）、`cef56d8`（型号门控）修，**读 status 仍未做**，可单独作为后续 PR。

### 1.4 提示音用数字输入框、英文枚举直露 —— 已在我们分支修
- **问题**：提示音用 `EnumAnnunciationTextField` 数字输入，辅助说明直接打印 `AnnunciationEnum`/`CgmAlertAnnunciationEnum` 英文名。
- **证据**：main `SoundSettingsActions.kt:297-324`（含 `cgmAnnunciationLabel`）；dev `:280-307`。
- **建议改法**：换成下拉/单选，显示本地化音量档位（高/中/低/振动）与 CGM A 固定 0、CGM B 限 0/2/3。
- **影响面**：声音设置页；不涉及协议。
- **是否需纯英文 PR**：否（需要新增 en 文案，但可作为 PR 自带默认英文串）。
- **与 i18n 分支冲突**：会；本分支已在 `94cf7bc` 修。

### 1.5 `Globals:` 调试文本泄漏到用户页 —— 已在我们分支修
- **问题**：声音设置页直接渲染 `Text("Globals:\n${globalsText}")` 原始 cargo 转储。
- **证据**：main `SoundSettingsActions.kt:262`；dev `:245`。
- **建议改法**：移到 Debug 页或删除。
- **影响面**：仅 UI。
- **是否需纯英文 PR**：否。
- **与 i18n 分支冲突**：会；本分支已在 `9da6a96` 移除。

### 1.6 暗色主题硬编码选中色 —— 已在我们分支修
- **问题**：`Color.Blue`/`Color.Gray` 判断选中态，深色主题下对比度不足且不随主题。
- **证据**：main `SoundSettingsActions.kt:519`；dev `:411`。
- **建议改法**：`MaterialTheme.colorScheme.primary` / `onSurfaceVariant`。
- **影响面**：声音设置警报暂停弹窗。
- **是否需纯英文 PR**：否。
- **与 i18n 分支冲突**：会；本分支已在 `94cf7bc` 修。

---

## 2. 睡眠计划/提醒的星期掩码 + pumpx2 `MultiDay.fromBitmask` 缺陷

### 2.1 手填位掩码 UX —— 已在我们分支修
- **问题**：睡眠计划编辑弹窗要求用户直接输入 0–127 的位掩码，并配一句「周一=1，周二=2，……，全选=127」的说明。
- **证据**：main `ControlIQSettingsActions.kt:428`；dev `:425`；文案 main `values/strings.xml:604` / `values-zh-rCN/strings.xml:598`，dev `:268` / `:262`。
- **建议改法**：改为 Sun→Sat 的 `FilterChip` 选择器 + 全选/清空，保存前 `coerceIn(0,127)`，越界提示；保留原始值只读回显兜底。
- **影响面**：Control-IQ 睡眠计划（Mobi）。
- **是否需纯英文 PR**：否（但需新增星期短文案的 en 默认值）。
- **与 i18n 分支冲突**：会；本分支已在 `4d5fa7c` 修（该提交刻意独立，便于单独摘给上游）。

### 2.2 pumpx2 `MultiDay.fromBitmask` 用 `%` 判位 —— 建议上游修库（仅记录未修）
- **问题**：`if (bitmask % day.id() == 0)` 判位，`42 % 2 == 0`、`6 % 4 == 0` 等组合会误判，返回错误的星期集合。
- **证据**：`D:\Programming\pumpx2-main-dev\messages\src\main\java\com\jwoglom\pumpx2\pump\messages\models\MultiDay.java:30-38`（`%` 在 `:33`），`ALL_DAYS = fromBitmask(127)` 在 `:18`。
- **建议改法**：`if ((bitmask & day.id()) != 0)`。
- **影响面**：所有调用 `fromBitmask` 的展示/写回路径（睡眠计划、提醒星期）。
- **是否需纯英文 PR**：**是**（pumpx2 库自身，纯代码 + 单测，直接提给 pumpx2 仓库）。
- **与 i18n 分支冲突**：否；本分支已改为自实现按位与（`ControlIQSettingsActions.kt` 的 `SleepDaysSelector`），不依赖该方法。

---

## 3. 基线单测失败 `VicoCgmChartTest`（DST）—— 已记录，未提 PR
- **现象**：`VicoCgmChartTest.toCgmDataPoint_appliesPumpLocalTimezoneCorrection` 失败（期望 `11:54 PM`，实际 `12:54 AM`；2026-11-05 本机复现，单独跑该用例同样失败）。
- **根因**：`pumpTimeToLocalTz` 用**当天**的时区偏移换算任意历史时间戳；用例时间戳是 3 月（EDT −4），运行日为 11 月（EST −5），两者差 1 小时。
- **证据**：`shared/src/main/java/com/jwoglom/controlx2/shared/util/UnitsRenderer.kt:14-16`，`val offsetSeconds = TimeZone.getDefault().getOffset(Date().time) / 1000`（`:15`）。
- **建议改法**：`getOffset(time.toEpochMilli())`，或改用 `ZoneId.default` + `LocalDateTime` 换算。
- **影响判断**：跨夏令时的历史血糖/事件时间显示可能偏 1 小时（需真机或带 DST 的单测确认，**目前只判为可能**，不写成已确认缺陷）。
- **状态**：**已记录，未提 PR**。属上游既有问题，与本方案无关（S1/S2 未改动该换算链路）。
- **是否需纯英文 PR**：是（纯代码）。
- **与 i18n 分支冲突**：否（该文件未被本分支改动）。

---

## 4. 清空数据库未清历史日志内存缓存 —— 已在我们分支修
- **问题**：清空数据库后，`HistoryLogFetcher`/`PumpCommHandler` 里的历史日志内存缓存仍在，页面继续显示已删数据。
- **证据**：本分支提交 `090d4c2`，涉及 `mobile/.../CommService.kt`、`mobile/.../util/HistoryLogFetcher.kt`、`mobile/.../pump/PumpCommHandler.kt`、`mobile/.../sections/Debug.kt`（清空入口统一调用清理）。
- **建议改法**：把缓存清理挂在「清空数据库」单一入口上，避免各处重复实现。
- **影响面**：Debug 页清库、历史日志展示。
- **是否需纯英文 PR**：否（无文案；新增 Toast 文案可给 en 默认值）。
- **与 i18n 分支冲突**：会；本分支已修，可直接整提交摘出。

---

## 5. 充电图标 ⚡ emoji 与 `colorControlNormal` 取色 —— pumppan 已实现（待确认后提交）
- **说明**：main 树**没有** `HorizBatteryIcon.kt` 与 `battery_horiz_*_charging.xml`，这是 zh/pumppan 侧特性；若上游要收，按下面方案给。
- **问题**：旧实现在电池矢量上叠 8sp ⚡ `Text`，颜色取 `colorControlNormal`，在 24dp 图标上过小、且深色 surface 上对比度不足；早期 zh 版本用 `?android:colorBackground` 填色做「挖空」，透出的是背景色而非容器色，换到卡片/弹窗上就失效。
- **证据**：zh 树 `mobile/src/main/res/drawable/battery_horiz_*_charging.xml`（`?android:colorBackground` 填色）；pumppan 工作区 `presentation/screens/sections/components/HorizBatteryIcon.kt`（emoji 叠加已被移除）。
- **建议改法**：6 张 `battery_horiz_{000,010,025,050,075,100}_charging.xml`，外壳 + 填充条 + 闪电子路径合成单 path 并设 `android:fillType="evenOdd"`（API 24+，minSdk 26 满足）——闪电在实心区被挖空、在空白区变实心，**镂空透出的是 Icon 的 tint 之外的真实背景（卡片/弹窗 surface）**，不需要额外填色；`Icon(painter)` 只用 tint 给实心区域着色，不会填满镂空处。
- **影响面**：首页/通知横置电池图标；仅资源 + 1 个选择函数。
- **是否需纯英文 PR**：否（纯资源 + 代码）。
- **与 i18n 分支冲突**：低；改动集中在新增 drawable 与 `HorizBatteryIcon.kt`。

---

## 6. Debug 页 G7 指令覆盖不全 —— 仅记录未修
- **问题**：Debug「发送泵消息」下拉只接了 G6 相关与 `SetDexcomG7PairingCodeRequest`，G7 的起/停传感器没有对应入口；另外曾观察到 G7 start 弹窗会误关 G6 菜单（复制粘贴痕迹）。
- **证据**：main `Debug.kt:355-362`（`SetG6TransmitterIdRequest` → `StartDexcomG6SensorSessionRequest` → `SetDexcomG7PairingCodeRequest`）；pumppan `Debug.kt:443-447` 同结构；三棵树均无 `StopDexcomG7*`/`StartDexcomG7SensorSession*` 处理分支。
  - 注：「G7 start 误关 G6 菜单」为先前观察结论，精确行号尚未复核，提 PR 前需重新定位，勿直接引用。
- **建议改法**：补齐 G7 start/stop 分支并统一 `showSendPumpMessageMenu = false` 的收敛位置；调试弹窗的 `expanded` 状态不要跨指令复用。
- **影响面**：仅 Debug 页。
- **是否需纯英文 PR**：是（纯代码）。
- **与 i18n 分支冲突**：会（本分支对 Debug 页做过文案收编与 `.toIntOrNull()` 防护），但冲突点集中在文案与解析，逻辑分支可独立摘出。

---

## 7. 睡眠计划弹窗长文案撑破排版 —— 已在我们分支修
- **问题**：把一整句「生效星期位掩码（周一=1，周二=2，……，周日=64，全选=127）」塞进 `OutlinedTextField` 的 floating label，单行不折行，横向超出输入框右边界、压到卡片边缘（用户截图，2026-11-03）。
- **证据**：main `ControlIQSettingsActions.kt:428`（该 label 的 `OutlinedTextField`）；dev `:425`；文案 main `values/strings.xml:604`（英文 45 字符）/ `values-zh-rCN/strings.xml:598`（中文 40+ 字），dev `:268` / `:262`。英文同样会溢出，只是略窄。
- **建议改法**：label 用短词（`Active days`），说明放到 `supportingText`/正文并允许折行；或直接改成星期选择器。弹窗 `text` 槽需 `verticalScroll`，否则内容变高后 `AlertDialog` 不滚动，会把标题/按钮挤出可视区。
- **影响面**：Control-IQ 睡眠计划编辑弹窗（Mobi）。
- **是否需纯英文 PR**：否（短 label 的 en 文案可随 PR 给默认值）。
- **与 i18n 分支冲突**：会；本分支 `4d5fa7c`（改星期选择器 + label 缩为「生效星期」/“Active days”）、`b31e6d4`（弹窗内容改为可滚动）。

---

## 8. TIR 统计未完成（仅 24h）—— 已知上游缺口·不建议自建

- **状态**：仅记录未修。**不建议我们在 pumppan 自建**，除非用户明确要求。
- **问题**：首页「时间在范围内」只统计**最近 288 条** CGM 记录（每 5 分钟一点，约 24h），且只算 70–180 mg/dL 的**条数占比**（无时间加权）；**没有 TBR/TAR/GMI，也没有多天报表**。
- **证据**：`mobile/.../cards/TherapyMetricsCard.kt :: TherapyMetricsCardFromDataStore` 第 143–146 行 `latestItemsForTypes(CgmReadingHistoryLogs, 288)`（注释 `~24h at one reading per 5 minutes`）；第 148–153 行计数逻辑；图表侧独立实现 `components/VicoCgmChart.kt :: calculateTimeInRange` 第 899–911 行；DAO `HistoryLogDao.kt :: getLatestItemsForTypes` 第 74–81 行按 `seqId DESC LIMIT` 取条数而非时间窗。
- **影响**：关闭「自动读取历史记录」后，首页 CGM 图表与 TIR 会停在旧值，容易被误读为「血糖变稳了」；因此开关副标题已明确写出「CGM 图表与 TIR 停止更新」（`settings_auto_fetch_history_logs_sub`，不写「24 小时」，避免暗示完整 TIR 报告）。
- **是否需纯英文 PR**：是（若上游要做完整 TIR/AGP，属功能实现而非汉化）。
- **与 i18n 分支冲突**：低（当前仅文案）。

