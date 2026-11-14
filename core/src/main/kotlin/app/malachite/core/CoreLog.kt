package app.malachite.core

/**
 * A tiny hook for diagnostics. The core module writes nothing anywhere by itself; the app may
 * plug a sink in (its optional log file) to see, for example, which online service failed.
 */
object CoreLog {
    @Volatile
    var sink: ((tag: String, message: String, error: Throwable?) -> Unit)? = null

    fun i(tag: String, message: String) { sink?.invoke(tag, message, null) }
    fun w(tag: String, message: String, error: Throwable? = null) { sink?.invoke(tag, message, error) }
}
