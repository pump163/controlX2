# 泵能力收敛与自建挡板方案

> 状态：**建议 / 未实施**（仅有评估，代码零改动）
> 校核日期：2026-11-03　代码基准：`controlX2-i18n_pumppan` 工作树；上游事实经本地镜像 + GitHub API 双向核对
> 锚点约定：一律写「文件名 + 符号名」，**不写行号**（行号每轮 diff 都会漂移）

本文是一份自包含的交接文档：只读本文即可承接「X2 只读范围要不要等上游」这件事。结论在文末。

---

## 一、上游现状事实（可复核）

### 1.1 controlX2（App 本体）

| 项 | 事实 | 证据 |
|---|---|---|
| 主线最后一次提交 | 2026-05-04，此后**零提交**（截至 2026-11-03，约 6 个月） | `45e3795`（`v1.0.0-20260504`） |
| 最新 release | `v1.0.0-20260504`，publish 2026-05-04 | GitHub Releases API |
| 任意分支最后 push | 2026-09-19（`pushed_at`），但**未合入主线** | GitHub Repo API；本地镜像 `origin/dev` 可见同名分支活动 |

本地镜像 `D:\Programming\controlX2-main_dev` 的主线头与 GitHub 一致（`45e3795`），可确认不是「我们的镜像旧了」。

### 1.2 pumpx2（通信库）

| 项 | 事实 | 证据 |
|---|---|---|
| 主线最后一次合并 | 2026-10-27（PR #199） | `456018a5` |
| 最新 tag | `v1.9.3`，2026-10-14 | `6fa94598` |
| 发版之后 | 8 个提交未发版，其中含 `13e9122d Fix MultiDay.fromBitmask decoding`（2026-10-27） | `git log v1.9.3..origin/main` |
| `dev` 分支 | **落后主线 9 个提交、领先 0**（最后提交 `9a397453`，2026-10-11） | `git rev-list --count origin/dev..origin/main`；GitHub `/branches/dev` |
| 高频流实际位置 | 不是 `dev`，而是 `claude/*` PR 分支（如 `claude/sweet-galileo-lspr0y`、`claude/trusting-gates-49iaax`），由作者 merge 进 `main` | 主线 merge commit 标题 |

### 1.3 提交构成：AI 生成 + 人工合并/修正

- 自 2026-09-01 起主线 195 个提交，其中 **85 个 merge commit**；作者构成：James Woglom 136（70%）、Claude 55（**约 28%**）、其他 4。
- 自 2026-08-01 起 193 个提交中 **95 条**带 `Co-Authored-By: Claude` / `Claude-Session:` 尾注。
- **不是定时任务**：`.github/workflows` 内无 `cron`/`schedule` 提交型工作流；提交按日呈爆发式（9-25 29 条、9-26 39 条、10-01 12 条），是会话式产出。
- **人工修正确有痕迹**（样本）：
  - `13e9122d`（James，2026-10-27）修 AI 生成的 `MultiDay.fromBitmask` 解码缺陷；
  - `d572965f`（James，2026-10-27）为睡眠计划每条捕获样本补测试；
  - `48f1abad`（2026-09-28）「restore the QuickBolusIncrement API」，即回滚上一提交删掉的 API。
- 2026 年内**无近期 `Revert` 提交**（最近两次 revert 为 2025-10-08）。

**判断**：上游是「AI 生成 + 人工合并/修正」，代码质量有闸门，但**闸门审的是代码，不审能力语义**——这不改变「能力未收敛」的结论。

### 1.4 本地库与依赖不一致

- 应用依赖：`pumpx2_version = "1.9.0"`（`build.gradle` 的 `pumpx2_version` 定义）。
- 本地库（`D:\Programming\pumpx2-main-dev`）：`main` 检出在 `f9b19bc4`（2026-10-24），**落后 `origin/main` 6 个提交**，工作区干净。
- 差距：应用依赖落后主线 **3 个 release + 8 个未发版提交**。

---

## 二、依赖升级：现状与决策

### 2.1 现状

- pumppan 现用 **`1.9.0`**（2026-04-07 发布）。
- **曾试过一次升级到 `1.9.3`，已回滚；冲突面已查明**（2026-11-02 实测，补记于 2026-11-04）：
  - 编译直接失败：`MainActivity :: sendServiceBolusRequest` 与 `Debug.kt` 报 `Unresolved reference 'FOOD1' / 'FOOD2'`。
  - 根因：`BolusDeliveryHistoryLog.BolusType` 被重构——旧 `FOOD1(1) / CORRECTION(2) / EXTENDED(4) / FOOD2(8)` → 新 `NOW(1) / LATER(2) / OVERRIDE(4) / CORRECTION(8) / CARB(16) / EATING_SOON_MODE(32) / QUEUED(64)`；上游注释写明旧映射无法解释真实泵数据（issue #78）。
  - 影响面：`MainActivity.sendServiceBolusRequest` 用 `FOOD2` 作基值、`fromCarbs > 0` 加 `FOOD1`、`corrVolume > 0` 加 `CORRECTION` 拼 `InitiateBolusRequest` 位掩码——**语义已变，不能盲改**。升级必须先做一次 `BolusType` 语义移植 + 真机验证，不能只改版本号。
  - 处置：`pumpx2_version` 已回退 `1.9.3` → `1.9.0`；**1.9.0 本身已含 `SupportedDevices` / `supports()`**，门控能力不受影响。
- **决策：暂不升级，先观察上游意向。**

### 2.2 升级的潜在收益与风险

| 潜在收益 | 说明 |
|---|---|
| `MultiDay.fromBitmask` 修复 | 已在主线（`13e9122d`）但**未发版**；升级后才能评估把 `ControlIQSettingsActions :: SleepDaysSelector` 的手写位运算换回库实现 |
| 历史日志类更新 | 主线大批 history log 类注册/枚举更新，可能改善 Debug 页日志解析覆盖面 |
| 新消息能力字段 | `@MessageProps` 的 `minApi` / `supportedDevices` 覆盖面扩大，`supportsDevice()` 收窄更准确 |

| 风险点 | 说明 |
|---|---|
| 破坏性 API 变更 | 已有先例：AI 提交曾删掉 `QuickBolusIncrement` API，随后 `48f1abad` 又恢复——说明主线 API 仍不稳定 |
| 发版滞后 | 我们需要的修复（`MultiDay`）迟迟不发版，升级到 tag 也拿不到 |
| 改动面过大 | 与「1.9.3 升级已回滚」的既有教训一致：编译面、行为面同时变化，难以二分定位。**具体实例见 §2.1**：`BolusType` 整体换语义，`sendServiceBolusRequest` 的位掩码拼装必须逐处移植 + 真机验证 |

### 2.3 何时重新评估（触发条件）

满足任一条即重开评估，否则不动：

1. 主线发布 **`>= v1.9.4`**，且该版本含**能力相关变更**（`MessageProps` / `SupportedDevices` / `KnownApiVersion` / `PumpVersionResponse` / 关键请求类）；
2. controlX2 主线复活并自行升级 pumpx2（说明上游已把版本面收敛过一次）；
3. 我们侧出现必须依赖新 API 的功能需求（如换回 `MultiDay`）。

---

## 三、能力收敛现状（符号证据）

**结论：没有固件维度，也没有统一门控。**

| 机制 | 位置（符号名） | 实际覆盖面 |
|---|---|---|
| 每消息声明 | `MessageProps`：`minApi()`、`supportedDevices()`、`modifiesInsulinDelivery()` | message 类可标注，但需调用方主动查询 |
| 型号枚举 | `KnownDeviceModel`：仅 `TSLIM_X2` / `MOBI` | **无固件字段** |
| 型号声明 | `SupportedDevices`：`ALL` / `TSLIM_X2_ONLY` / `MOBI_ONLY`；`supports(model)` | 标 `MOBI_ONLY` 约 26 处，其余默认 `ALL` |
| API 版本 ↔ 固件映射 | `KnownApiVersion` 注释：2.1↔v7.1/7.4、2.5↔v7.6、3.2↔v7.7、3.4↔v7.8、Mobi 3.5/3.6/3.8/3.11↔7.6/7.7/7.9/7.9.2 | **只是注释约定，未编程化** |
| 固件号解析 | `PumpVersionResponse` 解析 `armSwVer` / `mspSwVer` | `TandemBluetoothHandler` **只取 `serialNum`**，固件号读入即弃 |
| 运行时 API 版本 | `ApiVersionResponse` → `PumpState.setPumpAPIVersion`；`PumpState.getPumpAPIVersion` | **可得**，应用已在用（但只用于构造请求） |
| 门控执行点 | `TandemPump` 仅按 `deviceModel` 判 CentralChallenge；`NotificationBundle.allRequests(ApiVersion)` 仅过滤通知包 | **无全局能力门控** |

应用侧现状：`SupportedDevicesUtil :: canControlPump` 是纯 App 规则（`model == MOBI`），再叠 `supportsDevice()` 读 `@MessageProps` 二次收窄；`CommService` / Wear 的 `apiVersion()` 包装已读 `PumpState.getPumpAPIVersion()`，但**不参与门控**。

---

## 四、建议（主方案）：自建 Capability 挡板

### 4.1 形态

在 `shared` 侧新增 `CapabilityRegistry`：

```kotlin
// 形态示意，非落地代码
object CapabilityRegistry {
    fun can(model: KnownDeviceModel?, apiVersion: ApiVersion?, cap: Capability): Boolean
}
```

- 数据表：`(KnownDeviceModel, ApiVersion) -> Set<Capability>`。
- **默认拒绝、只收紧不放开**：表里没有的组合一律 `false`；`apiVersion == null`（连接初期）也一律 `false`。
- `Capability` 枚举建议：`SET_BASAL`、`SET_TEMP_RATE`、`SET_SOUNDS`、`SET_CONTROL_IQ`、`SET_SLEEP_SCHEDULE`、`BOLUS`、`SUSPEND_RESUME`。
- 当前行为必须保持：X2 除大剂量外全部 `false`（与 2026-11-03 真机实测一致）。

### 4.2 与 `canControlPump` 的衔接

- `SupportedDevicesUtil :: canControlPump(model)` **保留为总闸**（现有语义不变）。
- 新增重载：`canControlPump(model, apiVersion, cap)` = 总闸 `&&` `CapabilityRegistry.can(...)` `&&` `supportsDevice(message)`。
- UI 统一走新重载，**约 10 处调用点**：`Actions.kt`（挂起/恢复等行）、7 个子页 `readOnly` 判定、`CommService` 通知动作、Wear 侧对应入口。
- 好处：放开某一项时只改表，不改 UI；回退也只改表。

### 4.3 数据来源

| 字段 | 来源 | 状态 |
|---|---|---|
| 型号 | `determinePumpModel(deviceName)` | **已有** |
| API 版本 | `PumpState.getPumpAPIVersion()` | **已连通**（`CommService` 的 `apiVersion()` 包装） |
| 固件号 | `PumpVersionResponse.armSwVer` / `mspSwVer` | **未消费**，需补一次 `PumpVersionRequest` 并落库；必要性 **待验证**（API 版本已能间接映射固件） |

### 4.4 成本 / 风险 / 验证

- **成本**：1 张表 + 1 个 object + 重载函数 + 2 处签名调整 + 约 10 处调用点；1 组单测（表驱动）+ 1 组 Robolectric；无新依赖。
- **风险**：
  1. **表与真实固件漂移**——默认拒绝可兜底，最坏情况是「锁得过严」，不会误放开写操作；
  2. **医疗安全**：一旦放开某项写操作而判断错误，后果严重 → 放开必须以真机逐项验证为前提；
  3. `apiVersion` 连接初期为空 → 保守默认拒绝，首轮连接后再刷新 UI 状态。
- **真机验证方案**：对每项能力发一次写请求并**读取响应**；`SetPumpSoundsResponse` **当前全程未被读取**，正是「泵拒绝但界面无反馈」的静默拒绝例子——验证时必须先补上响应读取，否则无法判定 accepted/rejected。X2 与 Mobi 各跑一轮，结果写入表并留档。

### 4.5 退路（上游真收敛时如何切换）

保持对外接口 `CapabilityRegistry.can(model, apiVersion, cap)` 不变，**只换内部实现**：从「查自建表」改为「读 `@MessageProps` 的固件级字段」。调用方零改动，迁移成本≈0。

---

## 五、观察清单（可直接执行）

### 5.1 跟主线（低频、可信；建议每周一次）

1. `main` 是否出现新 merge commit：`git log <上次记录的tag>..origin/main --oneline`，只看 merge，不看 PR 分支流水。
2. 是否出现新 tag `>= v1.9.4`：`git tag --sort=-creatordate`；有则触发「2.3 重新评估」。
3. 差量是否触及能力声明：`MessageProps` / `SupportedDevices` / `KnownApiVersion` / `PumpVersionResponse` → 必评。
4. 关键请求类是否变化：`SetTempRateRequest`、`SetPumpSoundsRequest`、`SetSleepScheduleRequest`、`ChangeControlIQSettingsRequest`、`InitiateBolusRequest`。
5. controlX2 主线是否复活（当前 `45e3795` 起 0 提交）。

### 5.2 跟 dev / PR 流（早期信号；每周扫标题即可）

1. 标题含 `firmware` / `apiVersion` / `KnownDeviceModel` / `capabilit`。
2. 提交同时动 `src/test`（说明被认真验过）。
3. `restore` / `revert` / `Fix <上一提交>` 类（说明哪里出过问题）。
4. **可直接忽略**：`historyLog` fixture/解析批量注册、`scripts/` 与 `.github/` 工具链、纯枚举改名——量大但与门控无关。

> 注意：`dev` 分支当前落后主线 9 个提交，**不要盯 `dev`**；PR 分支才是高频流。

---

## 六、待决事项

| # | 事项 | 建议 |
|---|---|---|
| 1 | 能力表何时动工 | **先做只读骨架**：只加 `CapabilityRegistry` + 单测 + 重载函数，表内容维持「X2 全禁、Mobi 按现状」，不改任何 UI 行为；观察一段时间再逐项放开 |
| 2 | 是否设周期性巡检 | 建议设**每周一次**（主线查询 5 分钟，成本极低）；不设每日 |
| 3 | 升级时机 | 暂不升级；等 `>= v1.9.4` 且含能力相关变更 |
| 4 | 固件号是否纳入表键 | 先不纳入（API 版本够用）；若出现同 API 版本不同行为的实测反例再纳入 |

---

## 七、一句话结论

**自建挡板为主、盯主线为辅；不取代上游，只在我们自己这侧先挡一下**——因为 controlX2 已 6 个月无主线提交、pumpx2 主线虽活跃但能力声明仍停留在「型号 + API 版本」且无统一门控，等不到固件级能力表；而自建挡板默认拒绝、只收紧不放开，上游一旦真出固件级声明，只需替换 Registry 内部实现，调用方零改动。
