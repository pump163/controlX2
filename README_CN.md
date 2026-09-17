# ControlX2

面向手机与 Wear OS 手表的安卓应用，可通过 [PumpX2](https://github.com/jwoglom/pumpx2) 控制 Tandem t:slim X2 或 Mobi 胰岛素泵。

> **警告**\
> **本应用仅供实验使用，可用于修改胰岛素泵的活性胰岛素输注。**\
> **因使用本软件造成的任何后果，均不提供任何明示或默示的担保。**\
> **对任何故障、缺陷或胰岛素输注行为，您需自行承担全部风险。**\
> \
> **本应用与 Tandem、Dexcom 或任何其他制造商无关联，也未获其支持。**\
> **本应用未经官方批准用于临床，仅作为研究工具提供。**

<div>
<img src="https://user-images.githubusercontent.com/192620/213893870-9a3954db-7482-458f-955a-16e2f13a99d1.png" alt="手机端大剂量" title="手机端大剂量" width=300 />
<img src="https://user-images.githubusercontent.com/192620/213893894-a2e63958-3934-47cc-86f2-6eaa6d1212c9.png" alt="手机端大剂量输注" title="手机端大剂量输注" width=300 />
</div>

<div>
<img src="https://user-images.githubusercontent.com/192620/213893946-4aba8e56-cfa5-4166-9e7b-2cd570bd6e2a.png" alt="手表端大剂量" title="手表端大剂量" height=300 />
<img src="https://user-images.githubusercontent.com/192620/206879740-0f0b2a03-8b9d-4c63-b806-a19b23c44675.png" alt="手表端大剂量输注" title="手表端大剂量输注" height=300 />
</div>

**已支持功能：**

* 查看泵状态信息（活性胰岛素 IOB、储药器余量、电池、基础率）
* 查看已连接 CGM 动态血糖信息（当前读数、趋势箭头、探头与发射器状态）
* 从手表端输注大剂量（含单位、碳水化合物与血糖值）
* 从手机端输注大剂量（含单位、碳水化合物与血糖值）
* 接受/拒绝胰岛素校正
* 大剂量计算器（根据输入的碳水与血糖计算单位数）
* 取消大剂量
* 临时基础率与配置文件设置（仅 Tandem Mobi）

**开发中功能：**

* CGM 动态血糖历史曲线
* 表盘复杂功能组件（complication）
* 应用后台服务稳定性
* 手机端 UI 完善

**规划中功能：**

* 手表磁贴（Tile）
* 将泵警报/提醒以通知形式推送
* 上传数据至 Nightscout


<table border=0><tr valign=top>
<td>
<a href="https://www.youtube.com/watch?v=FybrFaLCs9Y">
<b>手机端演示视频：</b>
<br />
<img src="https://img.youtube.com/vi/FybrFaLCs9Y/0.jpg" width=300 alt="PumpX2 Alpha for Android - December 2022" title="PumpX2 Alpha for Android - December 2022" />
</a>
</td>
<td>
<a href="https://www.youtube.com/watch?v=jUUlqxDBQdQ">
<b>手表端演示视频：</b>
<br />
<img src="https://img.youtube.com/vi/jUUlqxDBQdQ/0.jpg" width=300 alt="PumpX2 Alpha for Wear OS - December 2022" title="PumpX2 Alpha for Wear OS - December 2022" />
</a>
</td>
</tr></table>

### 手机端截图
<div>
<img src="https://user-images.githubusercontent.com/192620/213893772-d1fcd8e7-7e7f-41d3-ad47-3e856dfdeb93.png" alt="应用主界面" title="应用主界面" height=450 />
<img src="https://user-images.githubusercontent.com/192620/213893867-2e08401f-ca06-4cdc-8a32-9ac2a0cc1d3b.png" alt="大剂量窗口" title="大剂量窗口" height=450 />
<img src="https://user-images.githubusercontent.com/192620/213893870-9a3954db-7482-458f-955a-16e2f13a99d1.png" alt="大剂量校正" title="大剂量校正" height=450 />
<img src="https://user-images.githubusercontent.com/192620/213893894-a2e63958-3934-47cc-86f2-6eaa6d1212c9.png" alt="大剂量输注" title="大剂量输注" height=450 />
<img src="https://user-images.githubusercontent.com/192620/213893904-13b715cf-2bfa-46fb-b527-b641a4691ffa.png" alt="大剂量通知" title="大剂量通知" height=450 />
<img src="https://user-images.githubusercontent.com/192620/213893911-fac52b14-50f3-40ab-96ea-aacd9fedd63f.png" alt="取消大剂量" title="取消大剂量" height=450 />
<img src="https://user-images.githubusercontent.com/192620/213893923-152b07c7-3de3-45c0-b9d5-0c0d83a8af8b.png" alt="大剂量已取消" title="大剂量已取消" height=450 />
</div>

### Wear OS 手表端截图

![应用主界面](https://user-images.githubusercontent.com/192620/206879718-91b90287-dbad-4a9d-9905-a43144025a0c.png)
![选择大剂量单位](https://user-images.githubusercontent.com/192620/206879726-5c13adad-0c05-4786-8e1a-b4bd63faae7f.png)
![选择大剂量碳水](https://user-images.githubusercontent.com/192620/206879731-cc83616b-d4f2-4f06-97ae-0577ebe30d94.png)
![输注大剂量](https://user-images.githubusercontent.com/192620/206879740-0f0b2a03-8b9d-4c63-b806-a19b23c44675.png)
![输注大剂量](https://user-images.githubusercontent.com/192620/206879749-5b6f6e32-2573-4f7d-acb4-b18448a5d880.png)
![输注大剂量](https://user-images.githubusercontent.com/192620/206879759-6ec60327-8d6a-45ae-9f94-4900dbbbc6cd.png)


## 安装说明

### 通过 GitHub Releases 安装 APK

**这是推荐的安装方式。**

1. 打开 ControlX2 的 GitHub 页面，点击右侧的 ["Releases"（发布版本）](https://github.com/jwoglom/controlX2/releases)
2. 在最新发布版本下，下载手机端应用 `mobile-release.apk` 文件，以及可选的 Wear OS 手表端应用 `wear-release.apk` 文件。
3. 在您的设备上安装 APK 文件。

### 通过 GitHub Actions 安装 APK

1. [点击此链接查看 `main` 分支最近的 GitHub Actions 构建记录。](https://github.com/jwoglom/controlX2/actions?query=branch%3Amain)
2. 从列表中选择最新一条记录。
3. 下载手机端应用 `mobile-release.apk` 文件，以及可选的 Wear OS 手表端应用 `wear-release.apk` 文件。
<img width="400" alt="image" src="https://user-images.githubusercontent.com/192620/213935162-84dc8b92-4131-497c-8c0b-bdd6a08df8f0.png">
4. 在您的设备上安装 APK 文件。

### 从源码构建
克隆仓库，在 Android Studio 中打开，构建以下模块：

* `mobile` - 安卓手机应用
* `wear` - 安卓 Wear OS 手表应用

在手机或手表上启用 ADB 调试，然后选择正确的模块点击 "Run"（运行）：
<img width="350" alt="Android Studio 截图" src="https://user-images.githubusercontent.com/192620/213927714-8338cda1-0b36-4023-9f21-8b84cf8fc7a5.png">

### 使用本地 PumpX2 构建
克隆 PumpX2 仓库并发布到本地 Maven 仓库，操作如下：

```bash
$ git clone https://github.com/jwoglom/pumpx2
$ cd pumpx2
$ ./gradlew build
$ ./gradlew publishToMavenLocal 
```

PumpX2 库文件将发布到 `$HOME/.m2/repository/com/jwoglom/pumpx2/`。

然后，在命令行构建时设置 `use_local_pumpx2` Gradle 属性为 `true`：`./gradlew build -Duse_local_pumpx2=true`，
或在 Android Studio 中编辑 `local.properties` 文件使其生效。

如果重新构建了 PumpX2 库，请同时升级 PumpX2 与 ControlX2 的 Gradle 配置中的版本号，
或在 ControlX2 中执行 `./gradlew build --refresh-dependencies`。否则，如果版本号未升级，
重新构建 ControlX2 时可能仍会使用该版本的旧代码缓存。

## Roborazzi 快照基线

两个模块的快照测试均使用 Roborazzi，并将黄金图像写入稳定、带版本号的路径：

* `mobile/src/test/snapshots/roborazzi/ComposePreviewSnapshotTests/*.png`
* `wear/src/test/snapshots/roborazzi/ComposePreviewSnapshotTests/*.png`

录制快照：

```bash
./gradlew :mobile:recordRoborazziDebug :wear:recordRoborazziDebug
```

当 UI 变更是有意为之的，请在同一个 PR 中更新基线：

1. 重新运行录制任务。
2. 检查变更的快照 PNG 文件。
3. 将更新后的基线随 UI/代码变更一起提交。

Roborazzi 的 diff/actual 报告产物应保持不被跟踪；`.gitignore` 已做相应配置。
