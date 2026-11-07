# bug：取消大剂量窗口压住确认输注窗口，确认按钮点不动

- 记录日期：2026-11-07
- 状态：待复现取证（**未修**）
- 关联：同类「两个窗口抢数据」现象的第 2 次复现

## 现象

用户在大剂量流程中，`确认输注` 对话框还开着，`取消大剂量`（正在取消 / 已取消）对话框又叠到它上面。上层窗口自带全屏遮罩，下层的
「确认输注」按钮点不到，流程卡住，只能等/杀进程。

## 复现路径（legacy 流程）

1. 首页 →「大剂量」→ 输入剂量 → 点「输注」。
2. `BolusWindow` 弹出 `BolusPermissionDialogRegion`（确认输注框），等待用户点确认。
3. 不点确认，改从另一条路径触发取消：通知栏「取消」动作、通知快捷按钮再次深链进入、或泵侧状态变化。
4. 观察到取消类对话框出现在确认框之上，「确认输注」按钮不可点击。

## 影响

- **阻断确认**：确认为输注前最后一步，点不到即无法继续，也无法正常取消/关闭，只能杀进程。
- 中断时刻恰好在「已发权限请求、尚未发输注」之间，状态语义不清（泵上是否有 pending 请求需现场确认）。

## 复现次数与场景

- 次数：**2 次**（此前一次记录为「两个通知/弹窗窗口抢数据」，本次为同类的第二次复现）。
- 场景：legacy 大剂量界面实测期（v2 开关关闭，走 `BolusWindow` 旧流程）。

## 待查方向（按可能性排序）

### H1：同一 `BolusWindow` 内多个对话框标志位没有互斥/优先级（最可能）

`BolusWindow.kt:100-104` 用 5 个独立 `mutableStateOf<Boolean>` 控制 5 个对话框：

```kotlin
showPermissionCheckDialog / showInProgressDialog / showApprovedDialog /
showCancellingDialog / showCancelledDialog
```

这 5 个 `if` 块各自独立渲染（`BolusWindow.kt:380-392` 确认框、`423-439` 输注中、`441-455` 已批准、`457-469` 正在取消、`471-481` 已取消），
没有 `else if`、没有「同一时刻只能有一个」的判定。只要 `showPermissionCheckDialog` 与
`showCancellingDialog`/`showCancelledDialog` 同时为 `true`，两个 `AlertDialog` 各自持有一个窗口 + scrim，
后组合出来的取消框自然压在确认框上。

取消入口 `cancelBolus()`（`BolusWindow.kt:394-421`）在 `:402` 直接 `showCancellingDialog = true`，
**不会**清掉 `showPermissionCheckDialog`；因此「取消被触发时确认框还开着」这一瞬间就是双层窗口。

### H2：通知路径与对话框路径同时活跃，通知/深链把流程再推进一次

- 通知侧会挂「取消」动作：`BolusNotificationBroadcastReceiver.kt:119 / 182 / 265`（动作文案 `R.string.bolus_cancel`），
  点击后走 `:314` / `:365` 直接发 `CancelBolusRequest`，与本地的确认框互不知情。
- 通知同时挂「大剂量」快捷按钮（`CommService.kt:944-948` → `ACTION_OPEN_BOLUS`）。前台收到后由
  `MainActivity.onNewIntent` → `Landing.kt:190-238` 处理，legacy 分支会重新 `bottomScaffoldState = BOLUS_WINDOW`
  并 `expand()`（`Landing.kt:216-222`）。这条路径不会清掉已在运行的 `BolusWindow` 内部对话框状态，
  等于在确认框之上再叠一层从通知进入的交互窗口。
- 也就是说：对话框与通知是两条独立呈现路径，后到者盖住前者，且两边共享同一个全局 `DataStore` 单例
  （`MainActivity.kt:132-133`），响应由谁消费不确定——这就是「抢数据」的来源。

### H3：深链重复进入时 `BolusWindow` 生命周期被重置，但对话框状态残留

`Landing.kt:308-322` 只有一份 `BolusWindow` 组合，`closeWindow` 才会 `resetBolusDataStoreState`。
深链重入只重置 `bolusPrefill` / `bottomScaffoldState`（`Landing.kt:210-222`），
不重置 `BolusWindow` 内部的 5 个 `remember` 标志位；若此时泵侧已回 `BolusCancelResponse`，
`LaunchedEffect` 类逻辑可能把流程推进到取消态，而确认框仍在。需要真机日志确认深链重入是否实际发生。

## 复现时要取的证据

1. `adb logcat -s BolusWindow BolusDialogs BolusNotificationBroadcastReceiver`，抓 `cancelBolus()`、`showCancelledDialog ...`、
   通知动作时间戳，确认两条路径的先后顺序。
2. 记录按下时屏幕上是几个窗口（图层数）与最上层是否有通知 heads-up。
3. 记录当时 `bolusId`（`dataStore.bolusPermissionResponse`）与泵上 pending 请求是否一致。

## 修复方向（待确认后做，本记录不含改动）

- 把 5 个布尔收敛成单一 sealed 状态（同一时刻只有一个对话框），或至少加「确认框打开时忽略取消弹窗」的优先级判定；
- 取消路径统一走 `BolusSessionHolder` 式单一状态源，通知动作与 UI 不再各自发 `CancelBolusRequest`。

## 备注

- `docs/bug/` 下当前只有 `bug-基线单测DST失败.md`、`bug-蓝牙断连.md`，未找到同类「双窗口抢数据」既有记录，故新建本文件；
  若其他会话另有记录，请合并到那一份的「第二次复现」小节。
