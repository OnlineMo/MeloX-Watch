package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 当前页面由 Activity 托管；复用其模型，lazy 行移除不能重建选择或存储任务。 */
@Composable
internal fun <T : ViewModel> rememberWatchViewModel(modelClass: Class<T>): T {
    val owner = LocalLifecycleOwner.current
    check(owner is ViewModelStoreOwner) { "Watch controls require a ViewModelStoreOwner" }
    return remember(owner, modelClass) { ViewModelProvider(owner)[modelClass] }
}
