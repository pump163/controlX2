# 基线单测失败：CGM 时间轴时区（DST）用例

- 记录日期：2026-11-03
- 分支：pumppan（工作树 `D:\Programming\controlX2-i18n_pumppan`）
- 结论：**上游基线既有缺陷**，非本轮改动引入；未向 GitHub 提 PR

## 1. 失败用例

```
VicoCgmChartTest > toCgmDataPoint_appliesPumpLocalTimezoneCorrection FAILED
org.junit.ComparisonFailure: expected:<1[1:54 P]M> but was:<1[2:54 A]M>
    at VicoCgmChartTest.kt:56
```

复现命令：

```powershell
.\gradlew :mobile:testDebugUnitTest --tests "com.jwoglom.controlx2.presentation.screens.sections.components.VicoCgmChartTest"
```

测试本身把 JVM 默认时区设为 `America/New_York`，构造一条泵本地时间 2026-03-11 23:54 的 CGM 记录，期望换算后仍是 11:54 PM。

## 2. 根因

[`shared/src/main/java/com/jwoglom/controlx2/shared/util/UnitsRenderer.kt:14-17`](../../shared/src/main/java/com/jwoglom/controlx2/shared/util/UnitsRenderer.kt#L14-L17)

```kotlin
fun pumpTimeToLocalTz(time: Instant): Instant {
    val offsetSeconds = TimeZone.getDefault().getOffset(Date().time) / 1000
    return time.minusSeconds(offsetSeconds.toLong())
}
```

偏移量取的是 **`Date().time`（运行当下）**，而不是 **`time`（事件发生时刻）**。

- 事件时刻 2026-03-11 处于美国夏令时（EDT，UTC−4）；
- 运行当下（11 月）为 EST（UTC−5）；
- 于是多减了 1 小时 → 23:54 变成次日 00:54，测试期望 11:54 PM 失败。

任何「事件时间为夏令时、运行时间为标准时」的组合都会错 1 小时；中国时区无 DST，本机日常使用不暴露，只在跨 DST 的历史数据回看上出现。

## 3. 影响面

`pumpTimeToLocalTz` 被多处复用，潜在影响：

- CGM 图表时间轴（`toChartTimestampSeconds` → `toCgmDataPoint`）
- 历史日志时间显示
- 任何把泵本地时间换算为设备本地时间的界面

影响条件：设备时区含夏令时，且查看的历史记录跨越 DST 边界（或运行时刻与记录时刻处于不同 DST 状态）。

## 4. 三树核对（只读）

| 树 | `UnitsRenderer.kt` MD5 | `VicoCgmChartTest.kt` MD5 |
|----|------------------------|---------------------------|
| main (`D:\Programming\controlX2-i18n`) | `A162153444C27F75718B4376562B0F9E` | `DA746183F1C865D2A02A039D1A2A2ABE` |
| dev (`D:\Programming\controlX2-i18n-dev`) | 同上 | 同上 |
| pumppan | 同上 | 同上 |

三棵树文件与上游一致，实现与测试均为上游原样，**不是本轮引入**。

## 5. 未提 PR 的说明

- 这是 shared 层公共工具函数，改动面横跨 CGM 图表、历史日志等多个界面，需真机回归，不适合夹带在本次 Debug 页清理批次里；
- 用户当前策略为「pumppan 先验证、需要向上游递进的事项单独报备」，故只记录、不改动、不提 PR。

## 6. 建议处理方式（最小改动）

```kotlin
fun pumpTimeToLocalTz(time: Instant): Instant {
    val offsetSeconds = TimeZone.getDefault().getOffset(time.toEpochMilli()) / 1000
    return time.minusSeconds(offsetSeconds.toLong())
}
```

用事件时刻计算偏移，天然覆盖 DST 与历史时区变更。验证方式：

1. `.\gradlew :mobile:testDebugUnitTest --tests "*VicoCgmChartTest"` 应转绿；
2. 真机（时区为 America/New_York 或 Europe/London）查看跨 DST 边界的历史 CGM 与历史日志时间是否与泵一致；
3. 中国时区（无 DST）回归确认数值不变。
