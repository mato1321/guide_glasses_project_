package com.guideglasses.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 可注入的 Dispatcher 來源。
 *
 * 舊專案直接寫死 `Dispatchers.IO` / `withContext(Dispatchers.Main)`，
 * 導致任何涉及執行緒切換的邏輯都無法在單元測試中驗證。
 * 抽成介面之後，測試可以注入 `UnconfinedTestDispatcher`。
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher

    /** 影像推論等 CPU/NPU 密集工作。 */
    val compute: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val compute: CoroutineDispatcher = Dispatchers.Default
}
