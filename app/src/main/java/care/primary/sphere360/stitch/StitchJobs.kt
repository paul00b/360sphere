package care.primary.sphere360.stitch

import care.primary.sphere360.util.Bg
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** État en mémoire des assemblages en cours (observé par la galerie). */
object StitchJobs {
    enum class Stage { QUEUED, LOADING, ALIGN, COMPOSE, FINALIZE }

    class JobState(val sessionId: String, val stage: Stage, val percent: Int)

    private val states = ConcurrentHashMap<String, JobState>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun state(sessionId: String): JobState? = states[sessionId]
    fun isActive(sessionId: String): Boolean = states.containsKey(sessionId)

    fun update(sessionId: String, stage: Stage, percent: Int) {
        states[sessionId] = JobState(sessionId, stage, percent)
        notifyListeners()
    }

    fun remove(sessionId: String) {
        states.remove(sessionId)
        notifyListeners()
    }

    private fun notifyListeners() { Bg.onMain { listeners.forEach { it() } } }
}
