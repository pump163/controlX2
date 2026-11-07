# bug：App 初始化阶段闪一下「需要手动开启蓝牙」提示

- 记录日期：2026-11-07
- 状态：已定位（**未修**）
- 关联：dev 分支「蓝牙修复」引入的分级错误卡（`PumpCriticalErrorClassifier` / `TieredCriticalErrorCard`）的可见性副作用

## 现象

App 初始化（连接向导 / 首次进入那一段）时，页面**短暂闪了一下**一条蓝牙相关提示。用户描述为「需要手动开启蓝牙」之类的提示。
用户操作较快时它只是闪现，随后程序正常走下去，没有卡住，也没有真的停在系统「开启蓝牙」窗口。

用户判断（原样引用）：

> 「蓝牙还没建立，这段程序插的位置有问题」

即：不是状态值算错，而是这一段 UI 被放在了不该在的时机——渲染时机早于蓝牙链路建立。

## 触发路径（H1，最可能）

1. 冷启动 / 首次向导 → `MainActivity` 启动 `CommService` → `PumpCommHandler` 处理 `INIT_PUMP_COMM` → `TandemBluetoothHandler` 开始扫描 / autoConnect。
2. 启动期第一次连接尝试失败（泵尚未就绪、适配器尚未稳定、尚未完成配对，均属正常过程）→ `TandemBluetoothHandler.onConnectionFailed` → `TandemError.BT_CONNECTION_FAILED`。
3. `PumpCommHandler.kt:551-570` 不再弹 toast，改为交给 `PumpCriticalErrorClassifier`（`:562-563` 注释写明：错误由 setup 页内联分级展示）。
4. `PumpCriticalErrorClassifier.kt:182-205` 把 `BT_CONNECTION_FAILED` 定为 **ACTIONABLE**、`occurrenceThreshold = 1`，且默认文案为 `pump_err_toggle_bt`（「请尝试关闭再打开蓝牙」），动作含「打开蓝牙设置」「重试」。
5. 该错误经 `FROM_PUMP_PUMP_CRITICAL_ERROR` 发到手机端，`MainActivity` 写入 `DataStore.pumpCriticalError`。
6. `PumpSetupStageDescription.kt:338-345`：只要 `stage != PUMPX2_PUMP_CONNECTED`、severity 为 ACTIONABLE，且次数 ≥ 1，就立即渲染 `TieredCriticalErrorCard`（`:369-377`）。
7. 下一次连接成功 → `FROM_PUMP_PUMP_CONNECTED` → `MainActivity.kt:773` 把 `pumpCriticalError` 置空 → 卡片消失。

因此卡片在屏时间 = 「启动期第一次连接失败」到「真正连上」之间的间隔。用户操作快、连接恢复快，这段间隔很短，看起来就是闪一下。

## 「闪一帧」的机制

不是把蓝牙状态算成了 `false`，而是**展示位置/条件放错了**：

- 卡片挂在「所有非连接阶段」的通用容器里（`PumpSetupStageDescription.kt:338`），而不是挂在明确的失败阶段（如 `PUMPX2_PUMP_DISCONNECTED`、`PUMPX2_INVALID_PAIRING_CODE`）。
- ACTIONABLE/FATAL 分支完全没有时间门槛（`:344`），第一次失败即满足；只有 TRANSIENT 才有「次数 + 时间」双门槛（`:342-343`）。
- 启动期第一次连接失败本身是可自愈的正常过程，却被当成「需要人工处理」。

纠正者与耗时：没有专门的超时或冷却，唯一清空点是**连接成功消息**（`MainActivity.kt:764-773`）。耗时取决于下一次连接成功的时间，不可预期。

补充：这不是「初值默认 false」问题。`DataStore.kt:60` 的 `pumpCriticalError` 初值为 `null`，首帧不会渲染卡片；`DataStore.kt:53` 的默认 stage 只是让卡片「有资格」显示（`stage != CONNECTED`）。真正的触发是**事件到达后没有判断当前是否仍在初始化**。

## 影响评估

- 手快：提示一闪而过，用户无感，流程不受影响。
- 手慢：用户会以为必须手动开关蓝牙，可能真的去关开蓝牙——恰好会中断正在进行的扫描 / 配对 / autoConnect 注册，把本来可自愈的启动失败变成真故障；也可能触发系统配对弹窗或重连。
- 反复出现会降低用户对状态提示的信任（「又在骗我开蓝牙」）。
- 不涉及输注安全：只是只读提示，断连期间也不会发出命令。

## 待修方向（待确认后做，本记录不含改动）

延续用户「挪位置 / 加前置条件」的方向：

1. **把卡片挪到明确的失败分支**：只在 `PUMPX2_PUMP_DISCONNECTED`、`PUMPX2_INVALID_PAIRING_CODE` 或「已确认连接失败」阶段展示；初始化、搜索、鉴权阶段不展示。
2. **给 ACTIONABLE 加启动静默期/稳定窗口**：同一失败在 T 秒内重复 N 次，或 stage 停留在失败态超过 X 秒，才允许展示；与 TRANSIENT 的门槛机制统一。
3. **三态化**：把「未判定（UNKNOWN）/ 正在建立 / 已失败」显式建模，而不是用 `null` + `stage != CONNECTED` 推导；首帧与建立期都归入 UNKNOWN。
4. **文案分流**：启动期的 `BT_CONNECTION_FAILED` 若 `extra` 不含 `CONNECTION_TIMEOUT` / `AUTH_FAILURE`，先按「正在重试」呈现，而不是「关闭再打开蓝牙」。
5. 不建议只改文案，也不建议首帧整块隐藏——后者会掩盖真实失败。

## 次候选（需一次现场截图或 logcat 区分）

- **H2：适配器尚未 `STATE_ON` 就做同步判定，系统弹窗闪一下。**
  `MainActivity.kt:1167-1181` 在 `isBluetoothEnabled()` 为 `false` 时直接 `startActivityForResult(ACTION_REQUEST_ENABLE)`；`isBluetoothEnabled()`（`:1197-1200`）只读 `adapter.isEnabled`，在 `STATE_TURNING_ON` 阶段返回 `false`。若此时蓝牙其实正在打开，系统弹窗会立刻自行结束，呈现为「闪一下」。
  判据：看到的是系统弹窗（「要开启蓝牙吗？」），而不是页面内卡片。
  注：该分支当前没有 Timber 日志，事后无法从日志确认；若成立，修法是在启动流程里等首次 `STATE_ON` 回调 / 广播，而不是同步读 `isEnabled`——与用户「时机不对」的判断同向。

- **H3：未连接超过 15 秒后的通用排障列表。**
  `PumpSetupStageDescription.kt:347-367` 在 `pumpConnectionWaitingSeconds > TroubleshootingStepsThresholdSeconds` 且仍非连接态时，会显示排障列表；`else` 分支（`:363-366`）第一条就是 `setup_toggle_bt_on_and_off`（「1. 开关一次蓝牙。」）。
  判据：闪现的是编号步骤列表，并持续到连接成功才消失，而不是一张带标题/按钮的卡片。

## 判据汇总（一条证据即可定案）

- 卡片：标题「无法连接到泵」+ 按钮「打开蓝牙设置」/「重试」 → **H1**。
- 编号步骤：「1. 开关一次蓝牙。」 → **H3**。
- 系统窗口：「要开启蓝牙吗？」 → **H2**。
- 日志：`FROM_PUMP_PUMP_CRITICAL_ERROR`（BT_CONNECTION_FAILED）与 `FROM_PUMP_PUMP_CONNECTED` 的时间差，可直接量化「闪」了多久。

## 与 dev 树那次蓝牙修复的关系

**同源，属副作用**：

- `TieredCriticalErrorCard` 与 `PumpCriticalErrorClassifier` 是 dev 分支那次蓝牙修复引入的（pumppan 分支继承）；上游原先只弹 toast（`PumpCommHandler.kt:562-563` 注释）。
- 那次修复把「连接失败」从 toast 改成「内联分级卡」，并让 `BT_CONNECTION_FAILED` 首次出现即 ACTIONABLE——正好把启动期的正常失败暴露到向导页。
- 不是「修了蓝牙开关之后才需要手动开启」；也不是适配器开关/配对逻辑变化：`startBTPermissionsCheck()` 在 dev 与 pumppan 两棵树里逐行一致（dev `MainActivity.kt:1118-1163`，pumppan `MainActivity.kt:1152-1200`），那次修复没有动它。
- 但也不能简单回退：分级卡的本意是让真实失败可见。正确修法是保留卡片、加时机门槛（见「待修方向」）。

## 备注

- 本次只记录，未改代码、未编译、未提交。
- 现场证据已在卸载重装（排查启动闪退）时丢失，以上结论由代码路径推断；H1/H2/H3 用一次截图或 logcat 即可区分。
