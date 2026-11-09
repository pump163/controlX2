# Bug：泵蓝牙断连后长时间无法恢复

- 记录日期：2026-11-03
- 分支：pumppan（工作树 `D:\Programming\controlX2-i18n_pumppan`）
- 结论：**上游链路既有缺陷**，非本轮 i18n/UI 改动引入；本轮只记录、不改代码
- 依赖版本：`build.gradle` 中 `pumpx2_version = "1.9.0"`，`use_local_pumpx2` 默认 `false`（使用 aar，非本地源码）

## 0. 一句话结论

现有自动重连的退避是 **250ms × 1.5 且无上限**，`discovered → connected` 阶段**没有应用层超时**，`onConnectionFailed` **只上报不重新扫描**；三者叠加就是 2026-09-28 那轮「卡在第 5 步、6h+ 不动」问题的现代版本。用户手动关开一次系统蓝牙仍是目前最可靠的人工恢复手段。

## 1. 症状与历史记录

### 1.1 用户描述（2026-11-03）

- 官方 App 或他人发起的大剂量不成立：泵连接需要蓝牙配对 code，每个新 App 连接都要重新生成；泵本体发起的事件可以读到。
- 蓝牙断联影响很大、很烦人，是下一步要修的问题。
- 上游 dev 版给了一个「开关」，断连后手动关开一次蓝牙开关即可恢复；可见上游也没有根治办法。

### 1.2 历史记录原文要点

来源：`D:\Programming\controlX2-main-zh\docs\工作记录-2026-09-28.md`，小节「BLE 连接卡死问题分析（仅记录思路，未改代码）」。

- 现象：“泵发现成功但 GATT 连接卡在‘第 5 步，共 7 步’（PUMPX2_PUMP_DISCOVERED）”；“持续 6h+ 无超时无重试”；“开关一次蓝牙即可恢复”。
- 卡住位置：“`onPumpDiscovered`（step 5）成功但 `onInitialPumpConnection`（step 6）从未触发——BLE GATT 连接静默失败。”
- 当时三个根因：
  1. 无连接超时：`onPumpDiscovered` 之后等待 `onInitialPumpConnection` 没有任何超时；
  2. `BleChangeReceiver` 只记日志不动作；
  3. 断线后无自动重连：收到 `PUMP_DISCONNECTED` 只切 UI 文案。
- 当时四条方向：30s 连接看门狗；`BleChangeReceiver` 变主动；断线指数退避（10s/20s/40s/60s）；UI「开关蓝牙」快捷按钮。
- 当时结论：“pumpcomm 是本地模块（非 pumpX2 只读库），可以修改。暂不实施。”

### 1.3 历史思路，部分已被实现推翻

> 本节为 2026-09-28 的历史判断，禁止直接当作现状结论引用。

| 历史判断 | 现状 | 说明 |
|---|---|---|
| 「断线后无自动重连」 | **已推翻** | pumpx2 1.9.0 的 `TandemBluetoothHandler :: onDisconnectedPeripheral` 会自动调度重连；缺的是「有界」与「可恢复」 |
| 「`BleChangeReceiver` 只记日志 → 关开蓝牙无法自动恢复」 | **半推翻** | App 侧 [BleChangeReceiver.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/BleChangeReceiver.kt) :: `onReceive` 确实只打日志；但库内 `TandemBluetoothHandler :: onBluetoothAdapterStateChanged` 在 `STATE_ON` 时调用 `startScan()`，所以关开蓝牙有效 |
| 「无连接超时」 | **部分推翻** | 连接建立后 5s 有 `InitialPumpConnectionChecker`；但 `discovered → connected` 之间仍无应用层超时，且 `onConnectionFailed` 后不重扫 |
| 「指数退避 10s→20s→40s→60s」 | **未实现** | 实际是 250ms×1.5 无上限、无抖动 |
| 「UI 按钮直接 `BluetoothAdapter.disable()/enable()`」 | **不可行** | `targetSdk 35` 下普通应用无法程序化关开蓝牙，详见 §4 |

## 2. 现状事实（pumpx2 1.9.0 aar + pumppan 树）

### 2.1 连接与重连由谁发起

- 入口：[CommService.kt](../mobile/src/main/java/com/jwoglom/controlx2/CommService.kt) :: `onStartCommand`（返回 `START_STICKY`）创建 `PumpCommHandler`，并发送 `INIT_PUMP_COMM`。
- 初始化：[PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `handleMessage` 分支 `INIT_PUMP_COMM`：若 `pumpConnectedPrecondition(checkConnected = false)` 为真（`pump.lastPeripheral != null`）则直接 “init_pump_comm already run, ignoring”；否则构造 `Pump(TandemConfig)` 并 `TandemBluetoothHandler.getInstance(...)`，循环调用 `startScan()`（遇到 `SecurityException` 每 500ms 重试）。
- 扫描与连接（库内）：`TandemBluetoothHandler :: startScan` → 1s 后 `immediateConnectToPeripheral`（已配对泵走 `autoConnectPeripheral`，否则按 `PUMP_SERVICE_UUID` 扫描）→ `onDiscoveredPeripheral` 满足条件时 `connectPeripheral`。
- 断连回调（App 侧）：[PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `Pump.onPumpDisconnected` 关闭 session、停止历史同步、清 `pumpCommState`、发送 `FROM_PUMP_PUMP_DISCONNECTED`、toast，并返回 `super.onPumpDisconnected`（库默认返回 `true`，即允许重连）。

### 2.2 退避无上限

`TandemBluetoothHandler :: onDisconnectedPeripheral`：

- `reconnectDelay` 初值 250ms；每次调度后 `reconnectDelay *= 1.5`。
- 仅在 `onConnectedPeripheral` 中重置为 250ms。
- **没有最大间隔、没有抖动、没有次数上限**。第 10 次约 9.6s，第 20 次约 14 分钟，第 30 次已到小时级，等价于“永久放弃”。

### 2.3 两类 `ReconnectPaused` 会永久放弃

同一回调内按断开原因分类：

- `PAIRING_INCOMPLETE_OR_MISSED_PROMPT` → 记录失败次数、回调 `onPairingPromptNotAcceptedYet`，日志 `ReconnectPaused event=pairing_incomplete_or_missed_prompt action=await_user`，**直接 return，不再重连**。
- `INITIAL_AUTH_NO_REPLY` → 120s 窗口内累计达到 `defaultInitialDisconnectRetryMaxCount = 3` 时，日志 `ReconnectPaused event=initial_auth_no_reply action=manual_reinit_required`，**直接 return，不再重连**。
- 其余原因才进入 `handler.postDelayed(immediateConnectToPeripheral, reconnectDelay)`。

### 2.4 连接建立后的 5s 检查

`TandemBluetoothHandler :: onInitialPumpConnection` 之后：

- 5s 后执行 `InitialPumpConnectionChecker`。
- 仅在已进入初始化步骤（`ALREADY_INITIALIZED`）时检查；若 `requestsSent > 0 && repliesReceived == 0`，标记 `INITIAL_AUTH_NO_REPLY` 并 `peripheral.cancelConnection()`。
- 若同时满足硬认证失败、无授权进行中等条件且达到配置阈值，会额外 `central.removeBond(...)`（**待验证：本项目是否配置了该阈值；默认未配置**）。

### 2.5 `onConnectionFailed` 只上报，不重扫

`TandemBluetoothHandler :: onConnectionFailed` 仅打印 `TandemBluetoothHandler: connection '<name>' failed with status <HciStatus>` 并回调 `TandemPump :: onPumpCriticalError(BT_CONNECTION_FAILED)`。

由于连接前已经 `central.stopScan()`，该回调之后**没有任何代码重新 `startScan()`**；App 侧 [PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `onPumpCriticalError` 只做通知/上报。结果就是停在“已发现但连不上”的状态直到外部干预。

### 2.6 `discovered → connected` 无超时

pumppan 与 pumpx2 1.9.0 代码中，`onDiscoveredPeripheral` 调 `connectPeripheral` 之后没有应用层计时器；5s 检查只在 `onInitialPumpConnection`（即 GATT 已建立）之后才启动。blessed 内部的连接超时行为不在本仓库可审计范围（**待验证**）。这正是 09-28「第 5 步卡 6h」对应的现代空洞。

### 2.7 `getInstance` 静态单例与 `resetInstance` 零调用

- `TandemBluetoothHandler :: getInstance(Context, TandemPump, Timber.Tree)` 为 `static synchronized`；`instance != null` 时**直接返回旧实例，忽略新传入的 `TandemPump`**。
- pumpx2 提供 `TandemBluetoothHandler :: resetInstance`，但 pumppan 树全树检索**没有调用点**。
- [PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `handleMessage` 的 `STOP_PUMP_COMM` 只调用 `tandemBTHandler.stop()`（即 `central.stopScan(); central.close()`），不 reset 单例。
- 推论：服务内“重新初始化”无法真正换掉 handler 与其绑定的 pump；`central.close()` 后复用同一实例是否可用**待验证**。

### 2.8 `shutdownAndAwaitDisconnect` 与「配对码无效」

- [PumpCommHandler.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCommHandler.kt) :: `shutdownAndAwaitDisconnect` 调 `peripheral.cancelConnection()` 并等待断连回调 latch，供重启流程优雅释放链路。
- [AppReload.kt](../shared/src/main/java/com/jwoglom/controlx2/shared/util/AppReload.kt) :: `triggerAppReload` 的注释明确记录：若在 GATT 仍打开时 `Runtime.exit(0)`，泵端会进入 “peer vanished” 状态，下一次重连协商成高延迟链路并静默丢弃 JPAKE3，表现为**“配对码无效”**的假错误。
- App 的缓解：[CommService.kt](../mobile/src/main/java/com/jwoglom/controlx2/CommService.kt) :: `appReloadShutdownHook`（`onDestroy` 中注销）在退出前尽力断开。

### 2.9 典型日志信号

| 日志片段 | 含义 |
|---|---|
| `TandemBluetoothHandler: disconnected '<name>' with status <HciStatus> (reconnectDelay: ... reason=...)` | 断连及当前退避值；库输出的是枚举名，Android 常见的 133 通常显示为 `UNKNOWN`（映射**待验证**） |
| `ReconnectBackoff event=initial_auth_no_reply action=retry failuresInWindow=...` | 认证无回复，仍在重试 |
| `ReconnectPaused event=initial_auth_no_reply action=manual_reinit_required` | **已永久放弃**，需人工干预 |
| `ReconnectPaused event=pairing_incomplete_or_missed_prompt action=await_user` | 等待用户完成配对弹窗 |
| `InitialPumpConnectionStuck event=no_response_window action=disconnect` | 已连接但请求无回复，主动断开 |
| `InitialPumpConnectionChecker ... bondState=...` | 5s 检查现场值 |
| `TandemBluetoothHandler: connection '<name>' failed with status ...` | GATT 连接失败（此后不重扫） |
| `service onPumpDisconnected: isConnected=false` | App 侧断连处理 |
| `BleChangeReceiver STATE_CHANGED: off/on` | App 侧仅记录蓝牙开关 |
| `PUMP-DISCOVERED` / `PUMP-CONNECT` / `PUMP-READY-STATE` | 扫描阶段与连接时刻 |

## 3. 「关开蓝牙」为什么有效

1. 系统关蓝牙会强制拆除所有 GATT/ACL 链路并清空 Android 蓝牙栈内部状态（应用无权直接触发）。
2. 系统重新开蓝牙后，pumpx2 库内的 `TandemBluetoothHandler :: onBluetoothAdapterStateChanged` 收到 `STATE_ON` 并调用 `startScan()`，重新扫描并重连。
3. App 自己的 [BleChangeReceiver.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/BleChangeReceiver.kt) :: `onReceive` 对 `BOND_STATE_CHANGED` / `STATE_CHANGED` **只打日志，不触发任何重连**，因此“手动关开”之所以管用完全依赖库内回调。

### 3.1 澄清：上游 dev 的「开关」不是蓝牙开关

dev 树新增（pumppan 继承）的是分级错误卡片，**不是程序化蓝牙开关**：

- [PumpCriticalErrorClassifier.kt](../pumpcomm/src/main/java/com/jwoglom/controlx2/pump/PumpCriticalErrorClassifier.kt)：按 `TRANSIENT` / `ACTIONABLE` / `FATAL` 分级，`TRANSIENT` 还需同时满足次数阈值与时间阈值才展示（短抖动默认不打扰）。
- [PumpSetupStageDescription.kt](../mobile/src/main/java/com/jwoglom/controlx2/presentation/components/PumpSetupStageDescription.kt) :: `TieredCriticalErrorCard` / `actionLabel` / `runErrorAction`，五个动作：

| 动作 | 实现 | 实际效果 |
|---|---|---|
| `Retry` | 发送 `TO_SERVER_FORCE_RELOAD` → [CommService.kt](../mobile/src/main/java/com/jwoglom/controlx2/CommService.kt) :: `handleMessageReceived` → `triggerAppReload`（`Runtime.exit(0)` + 冷启） | **杀进程重启** |
| `RepairPump` | 清 `pumpSetupComplete` / `pumpFinderPumpMac` / `pumpFinderPairingCodeType` / `currentPumpSid`，`PumpState.resetState`，再 `TO_SERVER_APP_RELOAD` | 重新走配对配置（可能触发解绑） |
| `OpenBluetoothSettings` | `Settings.ACTION_BLUETOOTH_SETTINGS` | **引导用户手动关开蓝牙**（上游现状） |
| `EnableConnectionSharing` | 写 `connectionSharingEnabled = true` 后强制重载 | t:connect 共享连接 |
| `CopyDiagnostics` | 组装诊断文本复制 | 取证 |

全仓检索**无任何 `BluetoothAdapter.disable()` / `enable()` 调用**；手机侧只有 [MainActivity.kt](../mobile/src/main/java/com/jwoglom/controlx2/MainActivity.kt) :: `getBluetoothManager` 中的 `ACTION_REQUEST_ENABLE` 首次启动提示。

另：`BTHostSwitch`（[FeatureFlag.kt](../shared/src/main/java/com/jwoglom/controlx2/shared/FeatureFlag.kt) :: `BTHostSwitch`）是手机/手表“蓝牙主机”角色切换，走 `removeBond` + 重启进程，main/dev 均存在，与本问题无关，且会要求重新配对。

## 4. Android 平台限制

本仓库实际值：`mobile/build.gradle` → `targetSdk 35`、`minSdk 26`；`wear/build.gradle` → `targetSdk 33`。Manifest 已声明 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、定位权限与 `FOREGROUND_SERVICE_CONNECTED_DEVICE`。

| 能力 | 结论 |
|---|---|
| `BluetoothAdapter.enable()` | **不可用**。API 33+ 仅系统应用或持 `BLUETOOTH_PRIVILEGED` 生效；普通应用调用无效（可能返回 false 或抛 `SecurityException`），还需要 `BLUETOOTH_CONNECT` |
| `BluetoothAdapter.disable()` | **不可用**，同上；且没有面向普通应用的 `ACTION_REQUEST_DISABLE` |
| `ACTION_REQUEST_ENABLE` | **可用**，但必须系统弹窗由用户确认（现有首启流程即如此） |
| 打开系统蓝牙设置 | **可用**（`Settings.ACTION_BLUETOOTH_SETTINGS`），由用户手动关开 |
| `BluetoothGatt.refresh()` | 隐藏 API（非 SDK 接口）；部分系统需 `BLUETOOTH_PRIVILEGED`，OEM 可能移除；blessed 是否暴露可反射的 `BluetoothGatt` **待验证** |
| 进程内重启蓝牙栈 | **不可做**，需 root / 系统签名 / Shizuku；`Runtime.exit(0)` 只重启 App 进程，不等于重启蓝牙栈 |

## 5. 候选方案对比

| 方案 | 做法 | 改动面 | 成功率预期 | 风险 | 需要 UI |
|---|---|---|---|---|---|
| ① 看门狗 + 有界退避 + 重扫 | `onPumpDiscovered` 后 20–30s 定时，超时 `cancelConnection()` + `startScan()`；`onPumpDisconnected` 自管 5/10/20/40/60s（上限+抖动）；`onPumpCriticalError(BT_CONNECTION_FAILED)` 触发重扫；`STATE_ON` 重置退避 | `pumpcomm/PumpCommHandler.kt`、`pumpcomm/BleChangeReceiver.kt` | **较高**：覆盖“卡在第 5 步”“连接失败不重试”“退避无上限”三类软故障 | 与库内重连叠加导致双连接；重连后 txId/opcode 重同步；扫描耗电 | 失败进入等待态时显示状态 + “打开蓝牙设置” |
| ② 拆 GATT + `resetInstance` 重建 | `shutdownAndAwaitDisconnect` → `tandemBTHandler.stop()` → `TandemBluetoothHandler.resetInstance()` → 重建 `Pump(cfg)` → `getInstance` → `startScan()`；bond 保留、不重配 | `pumpcomm/PumpCommHandler.kt`（可能 `shared`） | **中高**：等价于“关一次蓝牙”，但清不掉 Android 蓝牙栈 | 静态 `PumpState` / `Packetize.txId` 残留；泵端会话未正常结束 → peer vanished / JPAKE3 失败；旧 `central.close()` 后重建是否干净**待验证** | 自动第 N 次失败触发或恢复按钮 |
| ③ 反射 `refresh()` + 重新发现服务 | 反射取 blessed 内部 `BluetoothGatt` → `refresh()` → `discoverServices()` 后重走初始化 | 新反射 helper + `pumpcomm` | **中低**：ROM/版本相关 | 非 SDK 接口被拦、OEM 无此方法、blessed 私有字段随版本变化；重发现会重新授权/同步，可能诱发“配对码无效” | 否 |
| ④ 引导用户关开蓝牙 | 失败阈值后弹卡/手表提示；手机 `ACTION_BLUETOOTH_SETTINGS`；禁止调用 `disable()` | `PumpSetupStageDescription.kt`（dev 已有入口，补正式入口）+ wear 一处 | **高**（用户实测有效），但依赖人工 | 夜间/睡眠不可用；治标不治本；用户明确“很烦人” | **必须** |
| ⑤ 强制重载 | 复用 `triggerAppReload`（`Runtime.exit(0)` + 冷启），现有 shutdown hook 先断开 | 已有（dev `ErrorAction.Retry`），零新增 | **高**（彻底清进程/静态态） | 冷启耗时；退出瞬间 GATT 未清 → 假“配对码无效”；断连窗口内无法发命令 | 可做最后手段按钮 |

不建议：`removeBond` / 重新配对。每次新 App 连接都要泵端重新生成配对 code，代价最大，且可能把可恢复断连升级为“必须重新配对”。

## 6. 建议路线

1. **先做 ①**：改动只在本地 `pumpcomm`，直接命中 09-28 的三个洞；把“永久放弃”改为“有界退避 + 可恢复”，不引入 GATT 重建风险。
2. **同步把 ④ 的产品化入口做上**：把 dev 的 `OpenBluetoothSettings` 下沉为正式恢复路径（手机 + 手表），作为 ① 失败后的兜底。
3. **用日志决定是否上 ②**：若卡点集中在 `PUMP-DISCOVERED → PUMP-CONNECT` 且 `onConnectionFailed` 频发，① 只能反复重扫，此时加 ② 做 handler/central 级拆除。
4. **③、⑤ 不作第一线**：③ 破坏面大且不可移植；⑤ 已有能力，适合作“最后一次”兜底。
5. **pumpx2 升级单独立项**：本地 dev 树（1.9.2 方向）有更多连接硬化，但从 1.9.0 aar 升级会改动整条 BLE 链，需单独回归，不夹带在本次修复里。
6. **任何方案都不得触发 `removeBond`**（配对 code 约束）。

## 7. 真机取证方法

### 7.1 logcat

```powershell
adb logcat -v time | Select-String "PUMP-DISCOVERED|PUMP-CONNECT|PUMP-READY-STATE|TandemBluetoothHandler|TandemPump|ReconnectBackoff|ReconnectPaused|InitialPumpConnectionStuck|InitialPumpConnectionChecker|BleChangeReceiver|service onPumpDisconnected|pumpCommHandler|force-reload"
```

```powershell
adb logcat -b all -v threadtime > bt-disconnect.log
```

- Timber 的 `DebugTree` 默认把 `d/i/w/e` 全部输出到 logcat，无需额外开级别。
- 断连前后各抓一次 `adb shell dumpsys bluetooth_manager > bt-before.txt` / `bt-after.txt`；`adb shell dumpsys activity services com.jwoglom.controlx2` 确认服务存活与 `START_STICKY` 重启行为。

### 7.2 HCI / 协议层

- 开发者选项开启「启用蓝牙 HCI 嗅探日志」，取 `/data/misc/bluetooth/logs/btsnoop_hci.log`（通常需 root 或 `adb bugreport`）。
- 该日志用于区分：链路丢失、本地主机主动断开、GATT 错误（常见 133）三类。

### 7.3 Timber 文件日志

- 路径：`/data/user/0/com.jwoglom.controlx2/files/debugLog-*.txt`（由 [TimberUtil.kt](../shared/src/main/java/com/jwoglom/controlx2/shared/util/TimberUtil.kt) :: `setupTimber` + `DebugTree` 写入，受文件日志开关控制）。
- 仅调试包可用 `run-as` 拉取：

```powershell
adb shell run-as com.jwoglom.controlx2 ls files
adb exec-out run-as com.jwoglom.controlx2 cat files/debugLog-xxx.txt > debugLog.txt
```

- release 包（当前日常出包方式）不是 debuggable，`run-as` 不可用，改用 logcat + 诊断复制动作。

### 7.4 需要记录的现场信息

断连准确时间、`HciStatus` 值、当时 `reconnectDelay`、是否出现 `ReconnectPaused`、之后多久恢复、用何种手段恢复（等待 / 强制重载 / 关开蓝牙）、手表端是否同时断。

## 8. 动手前必须确认（清单）

1. 断连形态占比：初始 `step5→step6` 卡死、会话中掉线、`INITIAL_AUTH_NO_REPLY` 三类各占多少？需至少 3 次完整真机日志分类。
2. 日志中是否出现 `ReconnectPaused ... manual_reinit_required`？出现后“强制重载”能否恢复，还是只有关开蓝牙有效？这决定 ① 是否足够。
3. `HciStatus` 的具体值（133/`UNKNOWN`，还是 8/19/22/62 等）及是否伴随适配器 `STATE_OFF`；不同值指向不同层（链路丢失 / 本地终止 / GATT 错误）。
4. 目标机型与系统版本、省电与后台限制策略；`targetSdk 35` 下前台服务是否被降级或杀掉（`FOREGROUND_SERVICE_CONNECTED_DEVICE` 是否足够）。
5. 断连发生在手机侧还是手表侧（`WearPumpCommService` 路径不同），是否两端同时断，`BTHostSwitch` 角色是否被改过。
6. 产品策略：重连期间是否允许继续下发大剂量等命令；最多自动重试几次后转人工；夜间无人值守如何提示（手表振动 / 手机通知）。
7. pumpx2 是升级到 1.9.2 还是只在 pumppan 本地打补丁；升级后 `resetInstance` 语义与接口是否变化。
8. 所有候选方案均不得触发 `removeBond`：确认现有偏好与恢复流程中不存在隐式解绑，且重连后不会要求重新输入泵端配对 code。
9. ① 与库内重连的优先级：是禁用库内重连（`onPumpDisconnected` 返回 `false`）由 App 全权接管，还是保留库重连只补看门狗；两种方案的日志指标需先定义。

## 9. 路线决策（2026-11-07，挂起）

**决策**：放弃「先打补丁」路线（有界退避、连接失败后 5s 重试、取消 `manual_reinit_required` / `await_user` 永久停手那一套，暂不实施）；选定**直接升级 pumpx2 到 1.9.3**作为唯一路线。当前挂起，本轮不动手。

用户理由（原样引用）：

> 1) 迟早要升；2) 升级后本来也要改我们自己的重连逻辑，现有逻辑明显有问题，先升级再改程序更合理，否则先按旧版改完，升级后发现问题还得重改一遍。

### 9.1 本决策取代的旧结论

- §6 建议路线第 1–3 条（先做 ①、④ 产品化、用日志决定 ②）不再执行。
- §6 第 5 条「pumpx2 升级单独立项」形式保留，但升级由「单独立项」升为**唯一路线**。
- §8 第 7 条（升级 1.9.2 还是本地打补丁）已由本决策回答：**1.9.3**。
- §5 的方案 ①②③④⑤ 保留为参考，不再是实施序列。

### 9.2 升级的已知阻塞：`BolusType` 语义重构

2026-11-02 曾试升 1.9.3，编译失败并回滚（详见 `docs/feature/泵能力收敛与自建挡板方案_main.md` §2.1）。

- 旧枚举：`FOOD1(1) / CORRECTION(2) / EXTENDED(4) / FOOD2(8)`
- 新枚举：`NOW(1) / LATER(2) / OVERRIDE(4) / CORRECTION(8) / CARB(16) / EATING_SOON_MODE(32) / QUEUED(64)`
- 上游注释写明旧映射无法解释真实泵数据（issue #78）。数值位的含义已经变了，`FOOD1→NOW`、`FOOD2→?` **不是 1:1 映射**，不能盲改。

需要移植的调用点（按当前代码）：

| 位置 | 现状 | 迁移要点 |
|---|---|---|
| `mobile/src/main/java/com/jwoglom/controlx2/MainActivity.kt:445-509` :: `sendServiceBolusRequest` | 以 `FOOD2` 为基值；`fromCarbs > 0` 加 `FOOD1`；`corrVolume > 0` 加 `CORRECTION`；`:501` 用 `toBitmask` 拼 `InitiateBolusRequest` 位掩码 | 按泵真实语义重新定义请求掩码；X2 普通大剂量只发 `totalVolume`，掩码不能照搬旧三元组；必须真机抓包验证 |
| `wear/src/main/java/com/jwoglom/controlx2/MainActivity.kt:155-206` | 同样的 `FOOD2` 基值 + `FOOD1`/`CORRECTION` + `toBitmask` | 与手机端同源，必须同步改，否则两端行为不一致 |
| `db/src/test/java/com/jwoglom/controlx2/db/historylog/HistoryLogItemTest.kt:23` | 断言 `setOf(BolusType.FOOD1)` | 仅编译/测试适配；读历史应直接看数值（1/2/4/8/16），不要照新枚举解读 |
| `mobile/src/main/java/com/pumppan/controlx2/bolus/state/PumpCapabilities.kt:98-101,234` 与 `assets/pump-capabilities.json` | 配置键仍是 `FOOD1/FOOD2`；`requestBitmask` 与 `historyBolusTypeBitmask` 分离 | 配置键是否改名、历史掩码是否继续按数值存，属语义决策，不是编译修复 |
| `mobile/src/main/java/com/pumppan/controlx2/bolus/send/BolusSender.kt:74-77,136-137` | 用 `requestBitmask["FOOD2"]`，兜底 `8`；延展用实测 `12` | 升级后兜底值与键名都要重新对照；`12` 是实测值，不得用 `BolusType#toBitmask` 推导 |
| `Debug.kt` | 2026-11-02 报错记录提到；当前树未检出 `FOOD1/FOOD2` 字面量 | 升级时以编译器为准，逐个清 |

判断标准：位掩码是**发给泵的请求字段**，改错等于给泵发不同语义的剂量请求，所以「改版本号即完成」不成立。

### 9.3 升级后仍要改的程序逻辑（升级本身不解决）

1.9.2 起包含上游 PR #153（Tandem Mobi 连接稳定性），但它不覆盖下列我方/配置侧行为：

1. **重连退避无上限**：`onDisconnectedPeripheral` 250ms × 1.5 无 cap，长时间离泵会把间隔拖到小时级；仍需有界退避。
2. **永久停手**：`manual_reinit_required`（120s 内 3 次 `INITIAL_AUTH_NO_REPLY`）与 `await_user`（配对不完整）应在有限次后转为可恢复状态 + UI 重试，而不是 `return` 后不再重连。
3. **pending connection 丢失不重试**：1.9.0 的 `onConnectionFailed` 只上报不重试；升级后要确认上游 5s 重试是否覆盖所有失败路径（含 `cancelConnection` 之后）。
4. **旧实例未 teardown**：`resetInstance()` 在 app 树零调用，`stop()` 只 `stopScan + close`；升级后要确认 `getInstance/resetInstance` 语义，并在重建前显式 teardown，避免旧 autoConnect 注册残留 / 重复连接。

顺序约束：先让升级落地并通过编译与真机验证，再做上述逻辑改动；否则两轮改动叠加，无法二分定位。

### 9.4 回滚点

- 分支：`pumppan`
- 当前 HEAD：`c5f29dfdc5c8f72ed15d6d7f7ffb7d6120125120`（2026-11-07 00:17:25 +0800，`docs(handoff): 记录通知每秒刷新的耗电疑点…`）
- 当前 pumpx2 版本：根 `build.gradle:5` `pumpx2_version = "1.9.0"`，`use_local_pumpx2` 默认 `false`
- 工作区当前不干净：`M mobile/src/main/java/com/jwoglom/controlx2/MainActivity.kt`（其他会话改动）与两个未跟踪的 bug 文档。回滚只能按文件回退（回退 `build.gradle` 与移植提交），**不要** `git reset --hard` / `git clean`，否则会误删他人改动。
- 首次升级失败的现场记录：`docs/feature/泵能力收敛与自建挡板方案_main.md` §2.1。

### 9.5 挂起期间不做的事

- 不实施有界退避、5s 重试、取消永久停手等任何补丁；
- 不修改 `_tmp_pumpx2`；
- 不动其他会话正在修改的文件（当前为 `mobile/src/main/java/com/jwoglom/controlx2/MainActivity.kt`）。

---

### 9.6 1.9.3 升级实施记录（2026-11-08）

**版本**：`build.gradle:5` `pumpx2_version` `1.9.0 → 1.9.3`（三个制品 `pumpx2-android / pumpx2-messages / pumpx2-shared` 均在 jitpack 可解析，POM 用 GET 探测为 200）。

**掩码策略（硬约束）**：

- 请求侧（`InitiateBolusRequest.bolusTypeBitmask`）**一律取 `pump-capabilities.json` 中 `encodings.requestBitmask.values` 的整数值**，禁止用库内枚举推导。
- `mobile/.../MainActivity.kt`：`PumpCapabilities.forModel(null).requestBitmask` → `FOOD2=8` 为基，`FOOD1=1`、`CORRECTION=2` 按需或上（与升级前字节完全一致）。
- `wear/.../MainActivity.kt`：wear 模块读不到 mobile 的 asset，改用 `shared/.../BolusRequestBitmask.kt` 中固化的同名常量（值同上，文件内注明必须与 JSON 保持一致）。**这是唯一一处没有直接从 JSON 读取的地方**，若后续调整 JSON 需同步该文件。
- 这样做的原因：1.9.3 的 `CORRECTION` 从 `2` 变成 `8`，按旧名硬编译会让发给泵的掩码从 `11` 变成 `9/17` 之类，属于静默改变剂量请求语义。

**`BolusDeliveryHistoryLog.BolusType` 新旧映射（仅影响历史记录解释）**：

| 位值 | 1.9.0 | 1.9.3 |
|---|---|---|
| 1 | `FOOD1` | `NOW` |
| 2 | `CORRECTION` | `LATER` |
| 4 | `EXTENDED` | `OVERRIDE` |
| 8 | `FOOD2` | `CORRECTION` |
| 16 | — | `CARB` |
| 32 | — | `EATING_SOON_MODE` |
| 64 | — | `QUEUED` |

上游在 `BolusDeliveryHistoryLog` 的 Javadoc 中给出的理由是 **issue #78**：旧的四值映射无法解释真实泵历史日志中的位组合；新映射按日志证据重定义，属于解释修正。**因此升级后历史页/Nightscout 里的 `types:` 文本会变（如 `FOOD2` → `NOW`），这是预期变化，不代表数据损坏**。受影响显示点：`ProcessBolus.kt:146-148`（写入 Nightscout notes）、`VicoCgmChart.kt:555`（图表标签）。

**代码改动点**：`mobile/.../MainActivity.kt`（掩码 + import）、`wear/.../MainActivity.kt`（掩码 + import）、`shared/.../BolusRequestBitmask.kt`（新增）、`db/.../HistoryLogItemTest.kt`（`FOOD1` → `CARB`）。`BolusPermission*` 相关类 1.9.3 零变化。

**未启用**：`TandemPump.enablePeriodicConnectionPriorityReassert()` 保持关闭（opt-in），先只验证 1.9.3 自带的 5s 重试链，一次只动一个变量。

**回滚点**：`build.gradle:5` 改回 `1.9.0` + revert 上述 4 个文件即可；无 DB schema 变更，无需重新配对。

## 10. 现场取证小结（2026-11-08）

> 本次现场：用户离开手机去吃饭期间泵与手机拉开距离，回来约 40 分钟后仍未恢复。
> 定性：「超距掉线」是蓝牙必然而非 bug；**bug 是「回到范围后不恢复」**。
> 证据标注：**[已证实]** = 源码或系统/应用日志直接可见；**[推断]** = 证据链推出但无直接观测。
> 全程只读取证，未卸载/重装/清数据/重启进程/主动重连/对泵发指令。

### 10.1 结论

**[已证实] 重连链在第一次失败后即终止，且没有任何机制把它重新点着。**

- `TandemBluetoothHandler.onDisconnectedPeripheral` 是唯一排重连的地方：断开后 `postDelayed(immediateConnectToPeripheral, reconnectDelay)`，随后 `reconnectDelay *= 1.5`（1.9.0 handler 第 862-867 行）。
- `TandemBluetoothHandler.onConnectionFailed` **只调用** `tandemPump.onPumpCriticalError(BT_CONNECTION_FAILED)`，**不排任何重试**（1.9.0 handler 第 812-817 行）。
- 本次正好走到这一格：断开 → 250ms 后唯一一次 autoConnect → 该次连接建立失败（`CONNECTION_FAILED_ESTABLISHMENT`）→ 链终止 → 40 分钟内零尝试。
- 把链重新点着的**唯一入口是 `startScan()`**，其触发源实测只有 `onBluetoothAdapterStateChanged(STATE_ON)`；`MainActivity.onResume` 无重连，`CommService.onStartCommand` 有 `if (started) return START_STICKY` 幂等守卫，`periodicUpdateTask`（5 分钟）只发查询、`lastPeripheral == null` 时仅上报 `pump-not-connected null_peripheral`。
- 本次恢复靠的是**系统设置关开蓝牙**，不是 App 自愈：`dumpsys bluetooth_manager` 记录 `11-08 12:53:52 Enabled due to APPLICATION_REQUEST by com.android.settings`，App 日志同刻出现适配器状态 13→10→11→12 后 `startScan`。

**[推断] 本次断连的直接诱因是超距**：断开签名是 `CONNECTION_TIMEOUT` 与 `CHARACTERISTIC_WRITE_FAILED`，与用户「离开手机、距离拉远」的描述吻合；但 Android 不暴露 RSSI/距离，无法直接证明。

### 10.2 时间线（设备本地时间 UTC+8，与采样一致）

| 时间 | 事件 | 证据 |
|---|---|---|
| 12:10 前后 | 泵数据流停止，`lastMessageTime` 停在 12:10 | [已证实] 应用日志最后一个 rx；HTTP 采样同值 |
| 12:14:19 | `disconnected ... CONNECTION_TIMEOUT (reconnectDelay: 250 ms **reason=INITIAL_AUTH_NO_REPLY**)`；写 AUTHORIZATION 失败（`CHARACTERISTIC_WRITE_FAILED`，TRANSIENT） | [已证实] 应用日志 |
| 12:14:19 | `ReconnectBackoff event=initial_auth_no_reply action=retry failuresInWindow=1 maxRetryCount=3`（未到停手阈值） | [已证实] 应用日志 |
| 12:14:20 | 唯一一次重连：`Already bonded ...` → `autoConnectPeripheral` | [已证实] 应用日志 |
| 12:14:44 | `connection ... failed with status CONNECTION_FAILED_ESTABLISHMENT` → `BT_CONNECTION_FAILED` → **此后无任何连接尝试** | [已证实] 应用日志（全文件无后续连接记录） |
| 12:14–12:53 | 40 分钟空窗；期间 12:36:46 仅有一次 `periodicUpdateTask` → `pump not saved peripheral` → `pump-not-connected null_peripheral`，**不重连** | [已证实] 应用日志 |
| 12:53:27 | 用户把 App 调前台：`CommService onStartCommand ... 0 7` → `CommService onStartCommand when already running, ignoring` | [已证实] 应用日志；前台动作为 [推断] |
| 12:53:50–54 | 系统设置关/开蓝牙（`13→10→11→12`），`STATE_ON` 触发 `startScan()` | [已证实] 应用日志 + `dumpsys bluetooth_manager`（`APPLICATION_REQUEST by com.android.settings`）；手动操作为 [推断] |
| 12:53:56–58 | `Already bonded` → 连接 → `initial pump connection established` → `JpakeAuth DONE` → `pump-connected` | [已证实] 应用日志 |
| 之后 | 12:56:44 断开→12:56:55 恢复；12:59 再次断开；13:18:58 / 13:19:24 / 13:19:33 继续「断开→重连」循环（近距时能成环，因为 `onConnectedPeripheral` 会把 `reconnectDelay` 重置为 250ms） | [已证实] 应用日志 |

### 10.3 排除项（这些都不是元凶，已证伪）

| 怀疑项 | 结论 | 证据 |
|---|---|---|
| 服务被系统回收 / 自停 | **[已证伪]** 服务全程存活 | `ServiceRecord{23f8c08 .../.CommService}`：`isForeground=true foregroundId=1 types=00000010`、`startForegroundCount=1`；`createTime` ≈10:20（=进程启动），`restartTime` ≈12:01（**早于 12:10**）；`startRequested=true callStart=true` |
| FGS `startForeground` 失败 | **[已证伪]** 无失败记录，`startForegroundCount=1`，通知 id=1 一直在 | `dumpsys activity services` + `dumpsys notification --noredact` |
| 进程被 lowmemorykiller / oom 回收 | **[已证伪]** 无 `am_kill`/`lmkd` 记录；`oom adj curRaw=200`，`mHasForegroundServices=true`，`trimMemoryLevel=0`，`reportLowMemory=false`，`isFrozen=false` | 系统事件缓冲 + `dumpsys activity processes` |
| ANR | **[已证伪]** 全缓冲无 `am_anr` | 系统事件缓冲（11-07 15:14 → 13:29） |
| Doze / 省电导致停摆 | **[已证伪]** 设备 `deviceIdle=false`、`powerSave=false`；虽未进电池白名单，但不影响本结论 | `/api/diag/state` + `dumpsys deviceidle` |
| `fgsRunning=false`（用户最初的最硬观察） | **[已证伪]** 这是 `DiagMonitor` 关闭时 `snapshot()/emptySnapshot()` 的**默认值**，与真实状态无关；同时 `connected=false`、`linkState=OFF` 也都是同类默认值 | `DiagMonitor.kt` 源码 + `enabled:false` 端点返回 |
| 00:08 的 MainActivity 启动崩溃（`BolusEntryCoordinator.pushState` 空 `MutableState` NPE） | **[已证实] 与本次断连无关**：崩溃仅发生在 11-08 00:08:20 与 00:08:23 两次（`am_crash` 6646 / 7625），相距 12 小时；之后 00:28:55、10:20:18 两次 `am_proc_start` 均无崩溃，12:53:27 前台打开也被日志证明未崩溃，13:30 抓取时 `foregroundActivities=true`。已由 bolus 侧修复提交解决（会话记录引用 `8F9B2767`；**注意**：本仓库 `git log --all` 未命中该 hash，引用前需在修复分支核对） | `dumpsys dropbox` 8 条 `data_app_crash`、`-b crash` 缓冲、`-b events` 的 `am_crash/am_proc_start` |

补充：[已证实] 唯一一次 `INITIAL_AUTH_NO_REPLY` 的 `failuresInWindow=1`（阈值 3），本次**没有**触发 `ReconnectPaused ... manual_reinit_required`；全日志也无该字样。永久停手路径属于另一条独立风险，不是本场元凶。

### 10.4 未取到的证据（设计缺陷）

**[已证实] 本次断连没有被临时监控录到。**

- `GET /api/diag/state` → `enabled:false, incidentCount:0, liveBytes:0`；
- `adb shell run-as com.jwoglom.controlx2 ls files/diag` → `No such file or directory`（`dir` 字段指向的目录根本未创建）；
- 原因：`DiagMonitor` 开关默认关闭（`getBoolean(KEY_ENABLED, false)`），且 5 个 diag 端点全是只读 `GET`，无写端点；`enabled` 是进程内 `@Volatile` 字段，只在 `initialize()` 读一次 prefs，外部改 XML 需重启进程才生效（违反本次硬约束），故**无法远程开启**。

**这是临时取证设施的设计缺陷**：一个只为抓「偶发、不可预期」现场的设施，默认关闭等于默认不工作；唯一可用的持久记录是应用自身的 `files/debugLog-MUA.txt`（本次已全量导出，见 10.5）。

### 10.5 证据文件清单（工作区外取证目录 `_tmp_pumpx2/`）

| 文件（时间戳均为导出时刻） | 用途 |
|---|---|
| `crash_logcat_20261108_132856.txt` | `-b crash` 全量；含 11-08 00:08:20 / 00:08:23 崩溃栈，用于证明崩溃时间与本次无关 |
| `dropbox_data_app_crash_20261108_132902.txt` | `dumpsys dropbox --print data_app_crash` 共 8 条；含 Process-Runtime / Foreground，用于绝对时间与进程运行时长 |
| `logcat_events_20261108_132920.txt` | 系统事件缓冲（9.8MB，覆盖 11-07 15:14→13:29）；`am_crash` / `am_proc_start` / `am_kill` / `notification_enqueue`，是「何时崩、何时被杀、何时启动」的权威来源 |
| `activity_services_20261108_133024.txt` | `dumpsys activity services`：FGS/`startForegroundCount`/`createTime`/`restartTime`，证明服务未被杀 |
| `activity_processes_20261108_133024.txt` | `dumpsys activity processes`：oom adj 200、TOP/fg、未冻结、`mHasForegroundServices`，证明进程未被回收 |
| `notification_20261108_133034.txt` | `dumpsys notification --noredact`：常驻通知 id=1 及其文案，佐证 FGS 与状态文本 |
| `bluetooth_manager_20261108_133034.txt` | `dumpsys bluetooth_manager`：`12:53:52 Enabled due to APPLICATION_REQUEST by com.android.settings` 与适配器状态机记录，证明恢复靠外部关开蓝牙 |
| `ps_20261108_133034.txt` | `ps -A`：pid 5411 存活 |
| `applog_full_20261108_133034.txt` | 应用 `debugLog-MUA.txt` 全量（26MB，已确认包含 04:14:19Z 那段）；本次断连/重连/扫描的唯一逐事件记录 |
| `applog_tail30000_20261108_133034.txt` | 同一日志的 30000 行切片（6MB），便于快速检索，作为全量文件的冗余副本 |

### 10.6 两条待决路线（不替用户选择）

**路线 A：App 层看门狗（不动 pumpx2 版本）**

- 做法：在 `CommService`/`PumpCommHandler` 加一个与 pumpx2 重连链无关的看门狗——在「屏幕点亮 / 回前台 / 周期心跳 / `BluetoothAdapter STATE_ON`」时检查 `lastPeripheral` 与连接状态；若处于「已断开且无 pending 尝试」则重发 `INIT_PUMP_COMM`（`PumpCommHandler.kt:669-709` 会新建 `Pump` + handler + `startScan()`）。
- 代价：要碰 `TandemBluetoothHandler.getInstance` 静态单例语义（`resetInstance` 在 app 树零调用，`stop()` 只 `stopScan + close`），存在旧 autoConnect 注册残留 / 重复连接风险；不解决 `manual_reinit_required` / `await_user` 的永久停手语义；优点是 1.9.0 兼容、改动面小、可快速出包验证。

**路线 B：升级 pumpx2 1.9.3**

- 做法：按 §9 已定路线升级，获得上游 5s 重试与连接硬化（1.9.2 起含 PR #153）。
- 代价：`BolusType` 语义重构是硬阻塞（2026-11-02 试升编译失败已回滚，见 `docs/feature/泵能力收敛与自建挡板方案_main.md` §2.1）；需同步改手机端/wear/配置/测试共 6 处调用点，位掩码必须真机抓包验证（改错等于给泵发不同语义的剂量请求）；且升级本身**不覆盖**我方问题——有界退避、取消永久停手、`onConnectionFailed` 全路径覆盖、旧实例 teardown 仍需另行修改（§9.3）。

两条路线都不是「改个开关」的量级；本小结只记录证据与代价，不给出取舍。

## 11. 1.9.3 现场验证：149 秒恢复与可复用判据（2026-11-08 下午）

> 现场：pumppan release，pumpx2 1.9.3，`diag_monitor_enabled` 默认开，session `42b1600a`，pid 8945。
> 证据来源：`/api/diag/{state,incident,latest}`、`files/debugLog-MUA.txt`、`dumpsys bluetooth_manager` / `deviceidle`，全程只读采集。

### 11.1 验证动作与结果概览（设备本地时间 UTC+8）

| 阶段 | 时间 | 结果 |
| --- | --- | --- |
| 近距离基线（升级后首次连接） | 14:03:30 → 14:05:17 | 正常，存活 106s，MTU 247，INIT 完成 |
| 带手机走远，超距掉线 | 14:05:17.305 | `CONNECTION_TIMEOUT`（statusCode 8） |
| 远离期间 | 14:05:17 → 14:07:46 | 只有一次重连动作，autoConnect 挂着，**149s 后自行 latch** |
| 边缘 / 返回过程 | 14:07:51 → 14:18:39 | 连上即掉，共 17 轮，单轮最长存活 26s |
| 用户停止走动、手机贴近泵 | 14:19:22 起 | 至 14:26:37 连续 7min18s 无断，`incidentCount` 停在 10 |

### 11.2 判据 A：断开 → 重新连上耗时（可复用测量法）

**测量方法**（1.9.3 及以上版本）：

1. 终点也可用应用层信号：`initial pump connection established`（出现即 INIT 完成，UI 可正常刷新）。
2. 起点取 `TandemBluetoothHandler: disconnected ... ` 行（带 `reconnectDelay: xxx ms reason=...`）。
3. 终点取 `TandemBluetoothHandler: connected to '<pump name>'` 行。
4. 两者的时间差即「断开 → 重新连上耗时」；若期间出现多次 `connected to`，按最后一次能稳定存活 > 60s 的算。

**本次实测值：149 秒**（14:05:17.305 → 14:07:46.304）。

**可复用阈值**（用于验收与看门狗设计，非最终决策）：

- > 60s：用户能明显感到「坏了」。
- > 120s：用户大概率会去关开蓝牙或杀进程（本次 1.9.0 事故就是如此）。
- 因此「分离结束后 ≤ 60s 自行恢复」应作为验收线；「≤ 120s」只是底线。

**该 149 秒里发生了什么**（这一段决定了看门狗该挂在哪）：

- `immediateConnectToPeripheral` 只执行了 **一次**（INFO 级 `Already bonded to Tandem peripheral` 只出现一条；`running immediateConnectToPeripheral` 是 `Timber.d`，被文件日志过滤，不能用它判断）。
- 地址过滤扫描全程 **零命中**（`PUMP-DISCOVERED` 0 条），最终是 `autoConnectPeripheral` 在 149s 时 latch 成功。
- 期间没有任何周期性动作，也没有任何错误回调。

### 11.3 判据 B：5 秒重试链在本路径不存在

- 1.9.3 的 5s 定时器挂在 `onConnectionFailed` 上。本次全量日志 **0 条** `connection '...' failed`，说明这条分支从未进入。
- 实际走的是 `onDisconnectedPeripheral` → 250ms 一次性重入 → `immediateConnectToPeripheral` 单发 → 之后交给 autoConnect 无限期等待。
- 因此「每 5 秒重试」不能作为本路径的判据；看门狗必须覆盖 **「单发之后长时间无任何动作」**这一形态。

### 11.4 判据 C：靠近后是否稳定（射频余量 vs 初始化缺陷）

- 远离期 17 轮：单轮存活 5.0–26.0s，**无一超过 30s**。
  - 8 轮有明确失败点，全部落在连上后 5–13s：`SET_MTU_FAILED`(3)、`NOTIFICATION_STATE_FAILED`(2)、`CHARACTERISTIC_WRITE_FAILED`(2，CURRENT_STATUS/AUTHORIZATION)、`read MANUFACTURER_NAME failed`(1)；另有 1 次 `CONNECTION_UPDATE_FAILED(INSUFFICIENT_AUTHORIZATION)`。
  - 9 轮无任何错误，纯链路掉线，存活 19–26s。
- 靠近后连续 7min18s 无断，`rx/tx` 正常，`LINK_ALIVE`。
- **判定：主因是射频余量 / 距离**。初始化流程本身没有缺陷——17 轮里每一轮都完成了 `initial pump connection established`（均在连上后 1–3s 内）。

### 11.5 判据 D：20 秒整点掉线（本时段未复现，保持观察）

- 远离期 14:14:27–14:18:39 连续 7 轮存活时间为 18.6 / 20.1 / 21.6 / 19.2 / 21.7 / 22.2 / 19.2 / 19.3 秒，规整得不像随机射频抖动。
- 近距离 7 分钟内未复现。
- **判据**：若近距离再次出现 18–22s 的规整掉线，则不是距离问题，应按「初始化流程/连接参数」另一条线索排查。

### 11.6 判据 E：`manual_reinit_required` / `await_user` 计数

- 全量日志仅 1 次 `ReconnectBackoff event=initial_auth_no_reply action=retry failuresInWindow=1 maxRetryCount=3`（14:16:18.60），**未到 3/3**。
- 无 `ReconnectPaused`，未进入永久停手。
- `AWAIT_USER waiting_pairing_code` 仅出现于 14:03:30、14:12:53、14:14:00，均在 0.5s 内转 `CONNECTED`，不是库级永久等待。
- **判据**：`failuresInWindow` 一旦到 3（120s 窗口内）并出现 `ReconnectPaused action=manual_reinit_required`，即视为「5 秒/单发重连均已停手」，只能靠外置看门狗重建。

### 11.7 屏灭与 Doze 可观测性（本次结论受限）

- 屏灭（`screenOn=false`）期间泵报文仍能收到：06:21:39 / 06:22:39 / 06:23:39 / 06:24:40（UTC）每分钟一次 `HomeScreenMirrorResponse`；`/api/diag/state` 的 `lastRxAgoMs` 保持 < 60s。
- `periodicUpdateTask` 在本机是 **5 分钟** 一次（06:18:26.98、06:23:27.03），屏灭期间照常执行；它不是 1 秒轮询，不能当「30–60 秒兜底」用。
- **本次无法验证 Doze**：`dumpsys deviceidle` 显示 `mCharging=true`，设备始终保持 `mState=ACTIVE`，Doze 不会进入；同时 `Whitelist user apps` 为空，App **未**加入电池优化白名单。
- **不安排 Doze / 屏灭专项测试**（2026-11-08 用户更正）：BLE 应用必须待在电池优化白名单里，这是前提而非待验项，不再做「不充电 + 屏灭 + 静止 30 分钟」的对照实验。兜底定时器仍需按「CPU 休眠下 Handler 不保证执行」的前提选型（`setExactAndAllowWhileIdle` 或 FGS 常驻），但这属于实现期决策，不再用实验验证。
- **App 不在电池优化白名单 → 必须加入**（2026-11-08 拍板）：任何使用 BLE 的 App 只要留在电量优化或厂商后台清理名单里，一旦被清出内存，链路就断，这是平台常识，不需要再测。
- **本次它不是元凶**：进程从 14:03 起到现在一直是同一个 pid（8945），`CommService` 前台服务在跑（`fgsRunning=true`），无 ANR / 无 lowmemorykiller / 无被回收记录（见 §10.3）。白名单属于**长期防护**，不是本次「断开后不恢复」的成因——回溯时不要赖到它头上。
- **看门狗定时器禁止复用 `periodicUpdateTask`**：实测它是 5 分钟一次（06:18:26.98、06:23:27.03），粒度太粗，覆盖不了「分离结束后 ≤60s 自行恢复」的验收线，也扛不住夜间 30–60s 兜底的要求。

### 11.8 未解释项：`pump.lastPeripheral == null` 的窗口

- 赋值点：`onInitialPumpConnection`（`PumpCommHandler.kt:431`）、`initializeConnection`（`:447`，由 `onPumpConnected` 调）；置空点：`onPumpDisconnected`（`:539`）。
- 因此 `null` 窗口 = 「GATT 已连、`initial pump connection established` 尚未到」的 1–3 秒。14:08:26 / 14:13:01 / 14:13:26 的 `pumpConnectedPrecondition: pump not saved peripheral` 全部落在这个窗口。
- 该状态唯一消费者是 `MainActivity.kt:790` 把 UI 的 `pumpConnected` 置 false，没有重连/重建/扫描副作用。
- 结论：它是「连上 → INIT 完成」之间的表象，不是「连上就掉」的原因；实际影响只是掉线循环时首页状态会跟着闪。

### 11.9 看门狗设计输入（记录用，待人确认，尚未实施）

- 触发 1：回前台 / 屏幕亮且未连接 → 立即 `startScan()`。
- 触发 2：周期兜底 30–60s，仅在未连接时动作（夜间无人亮屏，断连意味着低血糖报警也收不到）。
- 动作强度：优先只 `startScan()`；仅扫描无效或已进入 `manual_reinit_required` 时才重建通信。
- 定时器：必须独立实现，**禁止复用 `periodicUpdateTask`**（5 分钟一次，见 §11.7）。
- 判据补充：1.9.3 的重连是「单发后交给 autoConnect 无限期等待」（§11.3），所以看门狗要判的是「单发之后长时间无任何动作」，不是「5 秒节奏断了」。

### 11.10 本次证据文件（工作区外取证目录 `_tmp_pumpx2/`）

| 文件 | 内容 |
| --- | --- |
| `diag_state_*.json` | 多轮 `/api/diag/state` 快照，含 `incidentCount`、`lastRxAgoMs`、`screenOn` |
| `diag_live_*.jsonl` | live 事件线切片（断开/重连/关键错误） |
| `pumplog_tail_*.txt` / `pumplog_close_*.txt` / `nearbase_*.txt` | `debugLog-MUA.txt` 切片，含 17 轮掉线的完整序列 |
| `btsys_*.txt` | `dumpsys bluetooth_manager`：该 MAC 仅 `Connections: 1`，排除重复连接 |
| `deviceidle_*.txt` | `dumpsys deviceidle`：`mCharging=true`，Doze 未进入 |

### 11.11 夜间实测复核（2026-11-08 23:03 – 2026-11-09 07:46）

> **接口可用性**：本次只读取 5 个端点，全部成功；不存在「连不上主机 / 连上但 404 / 超时 / 返回空 / 夜间未开采集」中的任何一种失败。
> `/api/diag/state`、`/api/diag/incident/latest`、`/api/diag/files`、`/api/pump/current` 均返回完整 JSON；`/api/diag/live?tail=120` 返回 17,894 字节，最后一条为 `07:38:56.225 CONNECTED onPumpConnected`，与 `state.connectedForMs=440,502` 的起点一致。
> 取证落盘：`_tmp_pumpx2/morning_{state,incident,files,pumpcurrent,live}_20261109_074616.*`。
> 全程只读：未改代码、未编译、未重启 App、未动 diag、未 push。

**会话边界（先说明，避免误读）**：监控会话 `2bd9f829`、pid 10780 于 **23:03:20.335** 才建立（上一会话 pid 8849 未正常结束）。`UNCLEAN_EXIT` 记录显示：上一会话在 23:02:40.035 时 `prevLinkState=LINK_SILENT`、`prevLastDisconnectAgoMs=1,010,623`（≈16.8 分钟）。按 `DiagMonitor` 的判定（`connected && 距上次回包>30s → LINK_SILENT`），这是**「已连接但无真实回包」**，不是「断开后未恢复」；随后进程在 23:03:20 重开。**23:03 之前的整段事件线不存在**，本节统计一律以 23:03:20 之后为准。

#### 11.11.1 问题 1：夜间是否稳定

区间 23:03:20 → 07:46:16（8h42m56s），共 **20 次** `DISCONNECTED`（其中 3 次是同一恢复回合内的失败尝试，见 11.11.2）。

最长连续连接：**3h31m32s**（02:00:41.924 → 05:32:14.232，`connectedForMs=12,692,308`）。

| 长连接起点 → 终点 | 时长 |
| --- | --- |
| 23:03:25.601 → 23:38:30.202 | 35m04.6s |
| 23:40:26.123 → 00:46:02.710 | 1h05m36.6s |
| 01:00:00.408 → 01:53:21.084 | 53m20.7s |
| 02:00:41.924 → 05:32:14.232 | **3h31m32.3s** |
| 05:32:18.285 → 07:01:20.522 | 1h29m02.2s |

短连接（<30s）只有 4 次：17.3s（00:46:08→00:46:26）、21.1s（00:47:53→00:48:14）、11.9s（02:00:02→02:00:13）、19.1s（07:38:15→07:38:34）。

| 时段 | 断开次数 | 形态 |
| --- | --- | --- |
| 23:38–23:40 | 2 | 26.2s / 15.4s 恢复 |
| 00:46–00:52 | 6 | 含 2 次失败的 INIT 尝试；恢复 6.1–66.8s，另一次 11m46s |
| 01:53–02:00 | 3 | 恢复 3.9 / 42.6 / 28.0s |
| 02:00–05:32 | 0 | 3h31m32s 零断开 |
| 05:32 | 1 | 4.1s 恢复 |
| 07:01–07:39 | 8 | 4.5–37.2s；含 07:37:47 `NOTIFICATION_STATE_FAILED` |

**判定：不是持续性「连上就掉」的 15–25 秒循环**。短连接只在 3 个簇里各出现 1–2 次，其余是 35 分钟到 3.5 小时的长稳；与白天「边缘距离 17 轮连续掉」形态不同。

#### 11.11.2 问题 2：断开后能否自愈

按「一次 `DISCONNECTED` → 下一次成功 `CONNECTED(onPumpConnected)`」计 17 个恢复回合：

| # | 断开 | 恢复 | 耗时 | 回合内失败尝试 |
| --- | --- | --- | --- | --- |
| 1 | 23:38:30.202 | 23:38:56.367 | 26.2s | 0 |
| 2 | 23:40:10.770 | 23:40:26.123 | 15.4s | 0 |
| 3 | 00:46:02.710 | 00:46:08.807 | 6.1s | 0 |
| 4 | 00:46:26.091 | 00:47:32.915 | **66.8s** | 2（00:46:48 未完成服务发现；00:47:10 `INITIAL_AUTH_NO_REPLY`） |
| 5 | 00:47:45.719 | 00:47:52.997 | 7.3s | 0 |
| 6 | 00:48:14.075 | 01:00:00.408 | **11m46.3s** | 1（00:51:47 `INITIAL_AUTH_NO_REPLY`） |
| 7 | 01:53:21.084 | 01:53:24.973 | 3.9s | 0 |
| 8 | 01:59:19.458 | 02:00:02.015 | 42.6s | 0 |
| 9 | 02:00:13.948 | 02:00:41.924 | 28.0s | 0 |
| 10 | 05:32:14.232 | 05:32:18.285 | 4.1s | 0 |
| 11 | 07:01:20.522 | 07:01:25.611 | 5.1s | 0 |
| 12 | 07:02:58.821 | 07:03:04.132 | 5.3s | 0 |
| 13 | 07:19:38.031 | 07:19:42.509 | 4.5s | 0 |
| 14 | 07:34:10.394 | 07:34:15.214 | 4.8s | 0 |
| 15 | 07:36:02.149 | 07:36:07.126 | 5.0s | 0 |
| 16 | 07:37:38.456 | 07:38:15.643 | 37.2s | 1（07:37:47 `NOTIFICATION_STATE_FAILED`） |
| 17 | 07:38:34.742 | 07:38:56.225 | 21.5s | 0 |

- 11 个回合 ≤30s，3 个 37–67s，1 个 11m46s；**没有一次需要人工干预，全部自行恢复**。
- `ReconnectPaused` / `action=manual_reinit_required`：**0 次**。
- `AWAIT_USER(waiting_pairing_code)`：20 次；18 次在 <1s 内转 `CONNECTED`，2 次（00:47:05、00:51:47）转为失败尝试后再次重试成功。它是本机瞬态策略提示，不是停手态。
- `INITIAL_AUTH_NO_REPLY`：**2 次**（00:47:10、00:51:47），均 `failuresInWindow=1 maxRetryCount=3`，**未到 3/3**。
- **判定：自愈成立**。但「≤60s 验收线」被破 2 次（66.8s、11m46s），其中 11m46s 破 120s 底线——这正是看门狗要覆盖的形态（见 11.11.5 ③）。

#### 11.11.3 问题 3：数据是否冻结

快照（07:46:16.727）与 `/api/pump/current`：

| 项 | 值 | 差值 |
| --- | --- | --- |
| `state.connected` / `connectedForMs` | true / 440,502 | 连接起点 07:38:56.225 |
| `pump.connectionTime` | `2026-11-08T23:38:56.235478Z`（本地 07:38:56） | 与上述一致 |
| `pump.lastMessageTime` | `23:43:46.850609Z`（本地 07:43:46） | 距 `connectionTime` **4m50s**，距取数时刻 **2m30s** |
| `state.lastRxAgoMs` / `lastTxAgoMs` | 149,885 / 150,866 | 末次回包 07:43:46.8 |
| `state.linkState` / `rxLastMinute` | `LINK_SILENT` / 0 | 当前 2.5 分钟无回包 |
| `pump.iobUnits` / `batteryPercent` / `cartridgeRemainingUnits` | 0.0 / 25 / 260 | 泵电量 25%；手机电量 100%（`state.batteryPct`） |

- **当前时点不是冻结**：连接建立后 4m50s 内仍有数据，只是最近 2.5 分钟静默（`LINK_SILENT` 定义是「已连接 + >30s 无真实回包」）。
- **夜间存在长时段「链路在但没数据」**：`minuteBuckets` 里连续零报文的最长空档约 **34 分钟**（05:43–06:17），另有 29 分钟（04:33–05:02）、25 分钟（06:17–06:42）、18 分钟（05:03–05:21、06:42–07:00）等；这些时段 `HEARTBEAT` 仍为 `onBle=true`，说明 GATT 没断。
- 同期心跳间隔从 1 分钟拉长到 5–11 分钟（如 04:52→04:55→05:02→05:10→05:19），与上述空档同源，指向 CPU 休眠/节流；**本次按用户要求不下 Doze 结论**。
- `iob=0.0`、泵电量 25% 这类慢变量不能用来判断冻结，判据应以 `lastMessageTime` / `lastRxAgoMs` 为准。

#### 11.11.4 问题 4：与白天 1.9.3 结论的对照

| 判据 | 白天（14:03–14:26） | 夜间（23:03–07:46） | 对照结论 |
| --- | --- | --- | --- |
| 断开 → 恢复 | 远离后 **149s**（单发一次，autoConnect latch，扫描零命中） | 常见 4–27s；长尾 42.6 / 66.8 / **11m46s** | 场景不同（夜间手机静止在近处），不能直接比；夜间没有 149s 那种「全程无动作」的长尾，但有更长的 11m46s（期间有 2 次 INIT 动作） |
| 「连上就掉」循环 | 边缘 17 轮，单轮 5–26s，无一 >30s | 仅 4 次 <30s，分 3 簇，无持续循环 | **未延续**，与「射频余量」解释一致 |
| 静止/靠近后稳定 | 7m18s 零断 | **3h31m32s** 零断 | 方向一致 |
| `INITIAL_AUTH_NO_REPLY` | 1 次，1/3 | 2 次，均 1/3 | 一致，未到 3/3 |
| `manual_reinit_required` | 0 | 0 | 一致 |
| 错误点 | SET_MTU_FAILED(3) / NOTIFICATION_STATE_FAILED(2) / CHARACTERISTIC_WRITE_FAILED(2) / read MANUFACTURER_NAME(1) | 07:37:47 `NOTIFICATION_STATE_FAILED` 1 次 | 同族错误，夜间频率低 |
| 进程 | 全程同一 pid | 23:03 重启一次；之后同一 pid 10780 存活 8h42m | 无二次重启 |

**新增形态（白天未观察到）**：00:48:14 → 01:00:00 的 **11m46s** 恢复回合里，出现「GATT 连接建立 → `CONNECT_START(initial)` → `INITIAL_AUTH_NO_REPLY` → 掉 → 再试」的反复；这正是「GATT 连上但 INIT 未完成、反复掉」的情形，且没有触发任何库级停手。

#### 11.11.5 问题 5：这条线能否暂时了结

① **「永久死掉」是否确实治好**：**未复现，但不能宣布治好**。
- 23:03 之后：0 次 `manual_reinit_required`、0 次人工干预，17 个恢复回合全部自行恢复（最长 11m46s）。
- 23:03 之前：上一会话终止时是 `LINK_SILENT`（已连接但无回包），不是「断开未恢复」，**没有证据支持永久死掉**。
- 机制未变：1.9.3 仍是「250ms 单发 → autoConnect 无限期 latch」（§11.3），没有主动放弃，也没有周期重试；「这次没死」不等于「以后不会死」。
- 要宣布治好，还需要一次「带走 → 远离 → 返回」的复测，确认 ≤60s 自行恢复、且全程无 `paused`。

② **「连上就掉」是射频余量还是软件缺陷**：**仍以射频余量为主，未见新增软件缺陷，但存在软件可介入的形态**。
- 夜间 4 次短连接集中在 3 个簇，没有持续循环；07:34–07:39 那一簇紧跟在 07:29 的 `rx 52/min` 流量高峰之后（可能与手机被拿起/移动有关，无法确证）。
- 07:37:47 的 `NOTIFICATION_STATE_FAILED` 与白天 2 次同类一致，是链路劣化后的伴随现象；白天已判「初始化流程本身无缺陷」（§11.4）。
- 但 00:48–01:00 的 11m46s 证明「GATT 连上、INIT 未完成、反复掉」真实存在，且库不会自己放弃，这一段必须由看门狗兜底。

③ **看门狗还需要什么判据**（在 §11.9 基础上补充，均有夜间实测依据）：

| 编号 | 判据 | 动作 | 夜间依据 |
| --- | --- | --- | --- |
| T1 | `DISCONNECTED` 后 **60s** 未 `CONNECTED` | 主动 `startScan()` | 命中 #4（66.8s）、#6（11m46s） |
| T2 | `DISCONNECTED` 后 **120s** 未 `CONNECTED` | 重建通信（关/开 GATT、`reinitialize`） | #6 在 00:50:14 即触发，比实际 01:00:00 提前约 9m46s |
| T3 | `CONNECT_START(initial)` 后 **10s** 无 `CONNECTED(onPumpConnected)` → 记 1 次 INIT 失败；**120s 内 ≥2 次** → 重建 | 重建 | 夜间 19 次 `initial` 尝试中 17 次成功、INIT 完成仅 0.8–2.5s（10s 阈值安全）；2 次失败为 00:47:04→00:47:32、00:51:35→00:51:47（`INITIAL_AUTH_NO_REPLY`） |
| T3b | `connected=true` 且距最后一次 `CONNECT_START(initial)` **30s** 无 `CONNECTED`、也无 `DISCONNECTED`（GATT 挂着但 INIT 卡住） | 重建 | 夜间无正样本；`INITIAL_AUTH_NO_REPLY` 超时后会主动断开，故更常见的是「反复掉」，此条作兜底 |
| T4 | 出现 `ReconnectPaused action=manual_reinit_required` | 立即重建 | 夜间 0 次；这是唯一可直接读的库级停手信号 |
| T5 | `INITIAL_AUTH_NO_REPLY` 单次出现（`failuresInWindow=1`） | **不动作**，只计数 | 夜间 2 次均为 1/3，单次就重建会误触发 |
| 不建议 | 用 `LINK_SILENT` / 数据静默作为触发条件 | 仅记录时长 | 夜间健康连接上有 34 / 29 / 25 分钟零回包空档，直接触发会误报 |

**结论：这条线可以「暂挂」，但不能「结案」。**
- 可以暂挂：夜间 8h42m 内无永久停手、无人工干预、进程无二次重启，自愈链路可用；现场验证不需要继续整夜挂机。
- 不能结案：① 缺少「远离→返回」复测；② 66.8s / 11m46s 两次破 60s 验收线，其中一次破 120s 底线，看门狗仍是必需；③ 长时段「链路在但没数据」需要单独定性（本次不作 Doze 结论）。
- 下一步：把看门狗按 T1–T5 落成实现；现场验证这条线暂停。

#### 11.11.6 夜间证据与 diag 设施 revert 时机

- 夜间证据：`_tmp_pumpx2/morning_state_20261109_074616.json`、`morning_incident_…json`、`morning_files_…json`、`morning_pumpcurrent_…json`、`morning_live_…jsonl`（`liveBytes=349,512`，从 23:03 `SESSION_OPEN` 起保留）。
- 落盘 incident 文件只有 10 个（疑似按上限轮转），覆盖 **07:02–07:38**（`incident-20261109-070258.json` 50,727 字节 → `incident-20261109-073856.json` 63,545 字节）；更早的夜间事件只存在于 live 环，**不要先清理 live**。
- 计数口径备注：`state.incidentCount=10`（与文件数一致），最新 incident 快照内是 11，差异不影响结论。
- **revert 时机**：现在不能 revert。建议在「看门狗实现 + 一次远离→返回验证通过」之后，再按 `docs/bug/断连临时监控-设计.md` 的删除清单整体移除（`DiagMonitor`、`/api/diag/*`、incident 落盘、`diag_monitor_enabled`）。移除前先把上述 `morning_*` 与 10 个 `incident-20261109-*.json` 归档。

## 12. 今晚：不安排测试，只让它挂着（2026-11-08）

> 用户更正：**不做 Doze / 屏灭专项测试**。任何使用 BLE 的 App 都必须待在电池优化白名单里，这是前提，不是待验项；§11.7 里「拔线 + 屏灭 + 静止 30 分钟再测一轮」的安排作废。
> 今晚没有任何测试计划：手机已拔线、adb 已失联，唯一发生的事就是让 App 长时间自行挂着。

### 12.1 睡前

- 不做任何操作：不拔插、不点屏幕、不打开 App、不动 `diag_monitor_enabled`。
- 今晚不安排任何实验。

### 12.2 明早只看两件事

1. **掉没掉**：这段时间有没有发生断连。
2. **掉了有没有自己爬起来**：如有断连，是否自行恢复、用了多久。

数据全部取自已有设施，不额外插桩：

- `/api/diag/state`：`incidentCount`、`connected`、`connectedForMs`、`linkState`、`lastRxAgoMs`；
- `/api/diag/live?tail=`：`DISCONNECTED` / `CONNECTED` / `HEARTBEAT` 事件线；
- `files/diag/` 里落盘的 incident 文件；
- `files/debugLog-MUA.txt`：`TandemBluetoothHandler: disconnected ...` → `connected to '<pump>'` 配对计时。

### 12.3 判读

- 有断开且 > 60s 才恢复 → 记为体验问题；> 120s → 底线失守（阈值沿用 §11.2）。
- 有断开且**从未**恢复 → 与 12:14 事故同形态，看门狗必须做（§14）。
- 整夜无断开 → 只说明「近距离长时挂机」通过，**不推出任何 Doze 结论**。

### 12.4 不做的事

- 不重启 App、不关开蓝牙、不手动重连、不 `am force-stop`。
- 不改代码、不 push；新包做好后今晚不装，明早方便时再装（今晚也不需要装）。

## 13. 重连后是否补齐数据（核查结论，2026-11-08）

> 问题拆成两件事，分别回答：**泵是否保留断连期间的数据** / **App 是否主动回拉**。以下结论均来自代码与现场日志，无推测。

### 13.1 结论摘要

| 问题 | 结论 | 依据 |
| --- | --- | --- |
| 泵是否保留断连期间的历史 | **是** | 泵内是带 `sequenceNum` 的有界历史环，`HistoryLogStatusResponse` 直接给出 `firstSequenceNum / lastSequenceNum / numEntries`；断连只影响 App 收取，泵自己照常写入 |
| App 是否主动回拉 | **是**，mobile 与 wear 都有实现 | 每次 `onPumpConnected`（INIT 完成）都会重建 fetcher 并立即发一次 `HistoryLogStatusRequest`，另有 5 分钟周期请求 |
| 从哪个索引续拉 | **DB 里最大的 `seqId`**，不是单独维护的 uploadedIndex | `HistoryLogFetcher.kt:159-177` |
| 一次拉多少 | **32 条/块**，块间 1s，块内每 500ms 轮询，4s 超时即跳过 | `HistoryLogFetcher.kt:102-146` |
| 失败怎么重试 | **没有显式重试**；4s 超时放弃该块，下一次状态响应（≤5 分钟）重新计算 missing 再补 | 同上 + `HistoryLogSyncWorker`（5 分钟） |

> 代码里**没有** `lastHistoryLogUploadedIndex` 这类字段（全仓 grep 无命中）。续拉位置完全由「DB 最大 seqId + 泵的 `firstSequenceNum`」推导。

### 13.2 机制（mobile 侧，手机作为连接主机时）

- **触发**：`PumpCommHandler.onPumpConnected` → `initializeHistoryAndSync()`（`PumpCommHandler.kt:469-479`）→ 新建 `HistoryLogFetcher` + `refreshHistoryLogSyncWorker(triggerImmediateSync = true)`；断开时 fetcher 被置空（`PumpCommHandler.kt:531-550`）。
- **周期**：`HistoryLogSyncWorker` 默认 `intervalMinutes = 5`（`HistoryLogSyncWorker.kt:16`），主线程 Handler 驱动。
- **起点计算**（`HistoryLogFetcher.kt:157-193`）：
  - `catchupThreshold = lastSequenceNum - 5000`（`InitialHistoryLogCount = 5000`）
  - `dbLatestId` 落在 `[catchupThreshold, lastSequenceNum]` 且 DB 条数 ≥ 预期一半 → 从 `dbLatestId` 起；否则从 `catchupThreshold` 起
  - 再夹到 `[firstSequenceNum, lastSequenceNum]`
- **断连期间产生的条目** = `dbLatestId+1 .. lastSequenceNum`，会被整段补齐（按块**倒序**拉）。
- **上限**：若落后超过 5000 条（`dbLatestId < catchupThreshold`）→ 只补最后 5000 条，更早的交给泵的 retention（本来就已被泵删除）。
- **去重与入库**：LRU 256 + DB 主键，`onStreamResponse` 逐条 `insert`。
- **开关**：`prefAutoFetchHistoryLogs()` 默认 `true`；用户关掉后**不会补**。

### 13.3 wear 侧

- `wear/util/HistoryLogFetcher.kt`、`wear/util/HistoryLogSyncWorker.kt` 是同一套逻辑的第二份实现；`WearPumpCommService.kt:401` 实现 `createHistoryLogFetcher`。
- mobile 与 wear **不会同时拉**：谁持有泵连接，谁就用自己那份。

### 13.4 现场实证

- App 重启后首次连接（06:03:33）从 `seq 2083549` 倒序拉到 `seq 2076428` 附近（约 7100 条），说明泵保留了远超单次断连窗口的历史。
- 每次重连 INIT 完成后 2–5 秒内出现一条 `HistoryLogStatusRequest`（06:17:37 / 06:18:02 / 06:18:30 / 06:19:25），随后 `HISTORY_LOG` 流式响应把 `dbLatest+1..last` 补齐。
- **观察陷阱**：`HistoryLogFetcher` 这个 tag 不在 `ShouldLogToFile` 的允许名单里（`ShouldLogToFile.kt:18-27`），所以文件日志里看不到 fetcher 自己的 INFO 日志。判断「有没有补数据」要看 `CommService` 的 `receive-message ... HISTORY_LOG` 与 `L:HistoryLogParser` 的 `sequenceNum` 序列，不要因为日志里没有 `HistoryLogFetcher.onStatusResponse` 就误判成没补。

### 13.5 与 Dexcom「2–3h / 8h」的关系

- 那两个时间只作参考，**不写进程序**。本 App 的补齐没有时间上限，只有两条硬边界：泵的 retention（`firstSequenceNum`）与 5000 条兜底下限。

## 14. 三级升级设计（设计方向，未实施）

> 用户口径：泵离体一般不超过半小时，但这只是**个别医疗常规**，别的医生不一定这么要求，因此不写死为程序规则。
> 「半小时」不是重试 deadline，而是**升级为「该由人来管」的时刻**——到那时问题通常不在蓝牙，而在泵没在身上 / 关机 / 该换耗材。

| 级别 | 触发 | 动作 | 是否打扰 |
| --- | --- | --- | --- |
| 1 秒级 | 回前台 / 屏幕亮 + 未连接 | 立即 `startScan()` 一次 | 否 |
| 2 分钟级 | 静默兜底：未连接时周期重试 | `startScan()`，必要时重建通信 | 否 |
| 3 半小时级 | 仍未恢复 | 通知用户处理（检查泵是否在身上 / 关机 / 耗材） | 是 |

- 三个阈值（秒级数值、分钟级间隔、半小时级时点）**先不写死**：做成配置项还是仅作参考，待用户确定。
- 第 3 级只提醒、不替用户做决定；能连上就不打扰。

## 15. 电池优化 / 内存清理白名单（必须；本次不是元凶）

### 15.1 结论

- **必须加入**（2026-11-08 拍板）：任何使用 BLE 的 App，一旦被系统电量优化或厂商后台清理清出内存，链路立刻断。这是平台常识，不需要再实测。
- **本次它不是元凶**：同一 pid（8945）从 14:03 一直存活，`fgsRunning=true`，无 ANR / 无 lowmemorykiller / 无被回收。白名单是**长期防护**，不要把它写成这次「断开后不恢复」的原因。

### 15.2 检测方式（标准 Android API）

```kotlin
val pm = context.getSystemService(PowerManager::class.java)
val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
```

- 只能检测**系统 Doze / App Standby 白名单**；**检测不到**小米、华为等厂商的「自启动 / 后台清理 / 内存优化」名单——这些没有公开 API，只能引导，不能断言。
- adb 校验：`adb shell dumpsys deviceidle whitelist` 是否包含 `com.jwoglom.controlx2`。

### 15.3 跳转方式（技术备查；最终决定不提供跳转，见 §16.1）

1. 标准一键：`Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))`，需在 manifest 声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限。
2. 兜底：`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（打开系统白名单列表，用户自己找）。
3. 再兜底：`ACTION_APPLICATION_DETAILS_SETTINGS`（App 详情页）。

### 15.4 厂商入口（候选，未在目标机型验证）

| 厂商 | 候选包名 | 大致入口 |
| --- | --- | --- |
| 小米 | `com.miui.securitycenter` | 自启动管理 / 省电策略 |
| 华为 | `com.huawei.systemmanager` | 应用启动管理 |
| OPPO | `com.coloros.safecenter`、`com.oppo.safe` | 后台冻结 / 自启动 |
| vivo | `com.vivo.permissionmanager`、`com.iqoo.secure` | 后台高耗电 / 自启动 |
| 三星 | `com.samsung.android.lool`、`com.samsung.android.sm_cn` | 设备维护 / 电池 |

- 这些**只作候选**：具体 `ComponentName` 随系统版本变化，必须 `try/catch ActivityNotFoundException` 后逐级降级；查不到的组件名一律不写死，本文不做猜测。

### 15.5 提示位置与频率

- 最终形态是**只自检、不跳转**的清单，具体条目与呈现见 §16。
- 出现位置：初始化向导一次 + 设置页「连接」分区随时复查；不做推送式打扰。

## 16. 使用前自检清单（方案，未实现）

> 参照物性质已澄清：截图那份是**自检清单**——只检查、不给跳转；哪项没设好就标出来，用户自己去系统设置里改。
> 我们的场景：BLE 长连接 + 前台服务 + 夜间无人值守 + CGM 报警。

### 16.1 决定：只做自检，不提供跳转

- **不提供任何跳转链接或按钮**，只显示「已设置 / 未设置」和后果说明。
- 理由：厂商 ROM 的自启动页、后台清理页、电池页没有稳定公开入口，组件名随系统版本变化；**跳错页比不给跳更糟**——用户会以为已经设置好了，实际问题仍在。
- 文案里最多给「按关键词搜」的方向，不给具体菜单层级。

### 16.2 候选项逐个核实：能不能真实检测

| 候选 | 能否真实检测 | 检测手段 | 是否列入 |
| --- | --- | --- | --- |
| 蓝牙开关 | 能 | `BluetoothAdapter.isEnabled` | 列入 |
| 通知权限 | 能 | `NotificationManagerCompat.areNotificationsEnabled()`；Android 13+ 另查 `POST_NOTIFICATIONS` 运行时权限 | 列入 |
| 电池优化白名单 | 能 | `PowerManager.isIgnoringBatteryOptimizations(packageName)` | 列入 |
| 系统省电模式 | 能 | `PowerManager.isPowerSaveMode` | 列入（提示级） |
| 位置权限（旧系统） | 能 | `checkSelfPermission(ACCESS_FINE_LOCATION)` | 列入，仅 Android ≤ 11 显示 |
| 定位总开关（旧系统） | 能 | `LocationManager.isLocationEnabled` | 列入，仅 Android ≤ 11 显示 |
| 精确闹钟 | 能 | `AlarmManager.canScheduleExactAlarms()` | **暂不列入**，待兜底实现定稿后再决定是否显示 |
| 免打扰 | 能 | `NotificationManager.getCurrentInterruptionFilter()` | 不列入（它不是「必须开启」的设置，只在报警被静音时作解释） |
| 厂商自启动 | **不能** | 无公开 API | **不列入** |
| 厂商后台清理 / 内存优化 | **不能** | 无公开 API | **不列入** |
| 本地存储 | 能，但不必要 | App 私有目录无需权限 | 不列入 |
| 相机 | 能，但不使用 | — | 不列入 |
| 网络 | 能 | `ConnectivityManager` 活动网络 | 不列入（非核心链路必需） |

**原则：检测不到就不列。** 页面上不出现「未知 / 请自行确认」这类条目——那会让整页失去可信度。

### 16.3 最终清单（只列可检测项）

| 项 | 检测方法 | **不设会怎样** |
| --- | --- | --- |
| 蓝牙 | `BluetoothAdapter.isEnabled` | 无法连接泵，实时值、历史同步、报警全部失效 |
| 通知 | `areNotificationsEnabled` + `POST_NOTIFICATIONS` | 常驻通知不可见；Android 13+ 无权限时前台服务无法启动，链路被系统掐断；夜间报警收不到 |
| 电池优化白名单 | `isIgnoringBatteryOptimizations` | 熄屏静置后被冻结，泵报文与重连停摆，夜里等于失联 |
| 系统省电模式 | `isPowerSaveMode` | 后台任务与网络被限制，重连与同步变慢（提示级，不阻断使用） |
| 位置权限（Android ≤ 11） | `ACCESS_FINE_LOCATION` | 旧系统上 BLE 扫描不可用 → 断线后扫不到泵 |
| 定位总开关（Android ≤ 11） | `LocationManager.isLocationEnabled` | 权限给了但总开关关着，同样扫不到泵 |

### 16.4 呈现方式

- 三态：`已设置`（绿）/ `未设置`（红 + 一句话后果）/ `本条不适用于本机`（灰，例如 Android 12+ 不显示位置项）。
- **不出现「未知」。**
- 页脚固定一句：「以上由 App 自动检测；本机厂商的后台限制无法自动检测，如遇异常可按关键词搜『自启动』『后台运行』自行确认。」——这是文字指引，不是清单条目。

### 16.5 放置位置（建议，未实现）

- **初始化向导里一次**：首次启动逐项检测，未通过项保留红点，允许跳过。
- **设置页「连接」分区随时复查**：系统升级或清理会重置白名单，需要能再看一次。
- 不进首页主流程，不做推送式打扰。

### 16.6 与截图那份的差别

- 它家 7 项里，「本地存储」「相机」我们不列（不用）；「自启动」我们**不列入**（检测不到）。
- 它家把检测不到的也打绿勾，我们不这么做——宁可少列一项，也不给假绿勾。

## 17. 自检清单落点核实：现有向导与设置页分组（方案，未实现）

### 17.1 现有初始化流程（mobile）

入口判定在 `MainActivity.kt:1537-1548`：

- `DeviceRole.CLIENT`：`!appSetupComplete` → `AppSetup`，否则 `Landing`
- `!pumpSetupComplete` → `PumpSetup`
- `!appSetupComplete` → `AppSetup`
- 其余 → `Landing`

实际顺序：

1. **FirstLaunch**（`FirstLaunch.kt`）：健康与安全警告 / TOS。同意 → `PumpSetup`；拒绝 → `exitProcess(0)`。
2. **PumpSetup**（`PumpSetup.kt`，`PumpSetupStage` 按 `step 0..7` 推进）：
   - step 0：`PERMISSIONS_NOT_GRANTED` —— 权限闸门
   - step 1：`WAITING_PUMP_FINDER_INIT` / `PUMP_FINDER_SEARCHING_FOR_PUMPS`
   - step 2：`PUMP_FINDER_SELECT_PUMP`
   - step 3–5：配对码流程（含在泵上确认配对码）
   - step 6：建立初始连接 / 读取泵信息
   - step 7：`PUMPX2_PUMP_CONNECTED` →「继续」→ `AppSetup`（并 `setPumpSetupComplete(true)`）
3. **AppSetup**（`AppSetup.kt`）：不是向导步骤，是一页偏好开关——允许胰岛素输送动作、bolus 确认阈值、Wear 自动确认、自动检查更新、自动获取历史日志、血糖单位。
4. **Landing**（主界面）。

### 17.2 现有「自检」性质的东西只有一个：step 0 权限闸门

- 内容：先请求**通知权限**（`launchNotificationPermissionRequest`，`MainActivity.kt:563-583`），再 `startBTPermissionsCheck()` 查**蓝牙权限**，最后 `checkLocationServices()` 查**位置服务**（`MainActivity.kt:1271`，Android ≤ 11 扫描需要）。
- 性质：**阻断式闸门**，不是清单——过了就消失，用户看不到「当前状态」，也不能回看。
- 全仓 grep 结果：`isIgnoringBatteryOptimizations`、`isPowerSaveMode`、`areNotificationsEnabled` **均无命中**。也就是说电池白名单、省电模式、通知开关状态从来没被检查过。
- `AppSetup` 里没有任何环境检查，只有偏好开关。

### 17.3 与我们 6 项的差异对照

| 项 | 现有覆盖 | 差距 |
| --- | --- | --- |
| 蓝牙开关 | 未检查（只管权限） | 需新增 `BluetoothAdapter.isEnabled` |
| 通知 | 仅首次请求时一次性 | 需新增状态展示 `areNotificationsEnabled()` |
| 电池优化白名单 | 完全没有 | 需新增 `isIgnoringBatteryOptimizations()` |
| 系统省电模式 | 完全没有 | 需新增 `isPowerSaveMode` |
| 位置权限（≤ Android 11） | step 0 已检查（阻断式） | 逻辑可复用，但结果不展示 |
| 定位总开关（≤ Android 11） | step 0 `checkLocationServices()` 已检查 | 逻辑可复用，但结果不展示 |

**结论：不能直接复用 step 0 当检查单。** 它只覆盖 3 项、只在向导里出现一次、且不可回看。可复用的只有检测函数（`areLocationServicesEnabled` / `checkLocationServices`、`BluetoothPermissions.required`）与权限请求逻辑；电池白名单、省电模式、通知状态需要新写一个只读列表——这是新增，不是改造成向导。

### 17.4 设置页「应用」组的现状

- **分隔规则**：组与组之间用 `SettingsDivider()`；组内各项之间用 `SettingsRowDivider()`。该组**没有组标题**（整页只有顶部一个 `HeaderLine("设置")`）。
- **组内顺序**（`Settings.kt`）：
  1. 强制重载服务（240–255，行分隔）
  2. 断开并重新配对泵（308–325，行分隔）
  3. 重新配置应用（327–342，**没有尾部行分隔**）
  4. 泵主机设备 / 重置角色（344–386，受 `FeatureFlag.BTHostSwitch` 控制，末尾 `SettingsDivider()`）
- 若服务未启用，组首还会多一行「启用 CTRL-X2 服务」（169–194）。
- 注意：195–238、257–306 是**整段被注释掉的旧项**（连接共享、通知按钮、临时速率按钮），不是现役代码。

### 17.5 建议插入位置与理由

- **位置**：「重新配置应用」之后、BTHostSwitch 项之前（`Settings.kt:342` 与 `344` 之间），新增一个 `item { 检查单; SettingsRowDivider() }`。
- **理由（阅读顺序）**：前两项是「重新配对泵 / 重新配置应用」，属于同一类动作，应保持相邻；检查单是只读状态项，放在动作项之后，用户读完「为什么会断」再看「现在哪项没设好」。插在这里也不会把它们拆开。
- **样式**：沿用组内 `ActionRow`（title + subtitle + leadingIcon）与 `SettingsRowDivider()`，组末仍用 `SettingsDivider()`；**不新造视觉**。
- 实现提示（非本次范围）：该项需要给「重新配置应用」补一条尾部 `SettingsRowDivider()`；点击后进入新页面需在 `Screen` 路由表注册一条。

### 17.6 命名建议

- 标题：「设置自检」；副标题：「检查蓝牙、通知、电池与省电设置」。
- 点击进入只读页，三态显示，不提供跳转（§16.1）。
