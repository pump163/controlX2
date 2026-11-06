package com.pumppan.controlx2.bolus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * v2 三个标签共用的底栏容器：**固定在页面底部**，不参与内容滚动。
 *
 * - `imePadding()`：软键盘弹出时整条底栏上移，按钮始终可见可点；配合 manifest 的
 *   `adjustResize`（窗口随键盘缩放），不需要监听键盘高度手写 offset。
 * - 不加 `navigationBarsPadding()`：本分区位于外层 `Scaffold` 的 `bottomBar`（应用
 *   导航栏）之上，系统手势条 inset 已由导航栏消费，再加会给底栏留一条空档。
 *
 * 各页只负责传入按钮行，insets 处理全部收敛在这里，避免每页各写一份。
 */
@Composable
fun BolusBottomBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content,
        )
    }
}
