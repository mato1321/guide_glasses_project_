package com.guideglasses.core.domain

/**
 * 跨層統一的結果型別。
 *
 * 舊專案的做法是讓 exception 直接從網路層穿透到 Fragment，再用
 * `catch (e: Exception) { addBotMessage("發生錯誤: ${e.message}") }` 收尾，
 * 結果是使用者聽到的是英文的 stack trace 訊息。
 *
 * 對視障使用者而言，錯誤訊息本身就是介面的一部分 —— 它會被唸出來。
 * 因此錯誤必須是「有型別、可翻譯成人話」的領域概念，而不是裸的 exception。
 */
sealed interface AppResult<out T> {

    data class Success<T>(val data: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Success) action(data)
}

inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Failure) action(error)
}

/**
 * 領域層的錯誤分類。
 *
 * 刻意不攜帶 [Throwable]：domain 層不應該知道底層用了 Retrofit 還是 gRPC。
 * [debugMessage] 只給 log 用，絕不可直接播報給使用者。
 */
sealed interface AppError {

    /** 給開發者看的訊息，不播報。 */
    val debugMessage: String

    /** 沒有網路連線。 */
    data class NoNetwork(override val debugMessage: String = "no network") : AppError

    /** 有網路但請求失敗（逾時、5xx、解析失敗等）。 */
    data class Remote(
        val statusCode: Int? = null,
        override val debugMessage: String = "remote failure",
    ) : AppError

    /** 眼鏡未連線或連線中斷。 */
    data class GlassesDisconnected(
        override val debugMessage: String = "glasses disconnected",
    ) : AppError

    /** 缺少必要權限（相機、麥克風、定位）。 */
    data class PermissionDenied(
        val permission: String,
        override val debugMessage: String = "permission denied: $permission",
    ) : AppError

    /** 硬體能力不足以完成此操作，例如眼鏡不支援連續影像串流。 */
    data class CapabilityUnavailable(
        val capability: String,
        override val debugMessage: String = "capability unavailable: $capability",
    ) : AppError

    /** 推論／辨識執行了但結果不可用（例如畫面太暗、沒偵測到人臉）。 */
    data class NoResult(override val debugMessage: String = "no result") : AppError

    /** 其他未預期的錯誤。 */
    data class Unknown(override val debugMessage: String) : AppError
}
