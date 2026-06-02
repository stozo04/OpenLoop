package io.github.stozo04.openloop.diagnostics

/**
 * In-memory [AnalyticsReporter] for unit tests. Records every call so tests can assert that the
 * ViewModel emitted the expected `screen_view` / `logEvent` / `setUserProperty` calls in the right
 * order with the right params — no Firebase SDK, no Robolectric, no mocking.
 *
 * Pair with [io.github.stozo04.openloop.ui.OpenLoopViewModel] as the 6th constructor arg in tests.
 */
class FakeAnalyticsReporter : AnalyticsReporter {

    sealed interface Recorded {
        data class ScreenView(val name: String, val cls: String?) : Recorded
        data class LogEvent(val name: String, val params: Map<String, Any>) : Recorded
        data class UserProperty(val name: String, val value: String?) : Recorded
    }

    private val _calls = mutableListOf<Recorded>()

    /** Read-only view of every call recorded so far, in order. */
    val calls: List<Recorded> get() = _calls.toList()

    /** Convenience: every event name logged (excluding screen_views & user properties). */
    fun eventNames(): List<String> = _calls.filterIsInstance<Recorded.LogEvent>().map { it.name }

    /** Convenience: every screen_name passed to [screenView]. */
    fun screenViewNames(): List<String> = _calls.filterIsInstance<Recorded.ScreenView>().map { it.name }

    fun clear() = _calls.clear()

    override fun screenView(screenName: String, screenClass: String?) {
        _calls += Recorded.ScreenView(screenName, screenClass)
    }

    override fun logEvent(name: String, params: Map<String, Any>) {
        _calls += Recorded.LogEvent(name, params)
    }

    override fun setUserProperty(name: String, value: String?) {
        _calls += Recorded.UserProperty(name, value)
    }
}
