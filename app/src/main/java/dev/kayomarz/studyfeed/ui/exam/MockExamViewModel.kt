package dev.kayomarz.studyfeed.ui.exam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kayomarz.studyfeed.exam.ExamQuestion
import dev.kayomarz.studyfeed.exam.MockExamRepository
import dev.kayomarz.studyfeed.pack.PackRegistry
import dev.kayomarz.studyfeed.progress.FsrsBridge
import dev.kayomarz.studyfeed.progress.McqAttempt
import dev.kayomarz.studyfeed.progress.Mistake
import dev.kayomarz.studyfeed.progress.ProgressDatabase
import dev.kayomarz.studyfeed.progress.Review
import io.github.openspacedrepetition.Rating as FsrsRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Drives the **mock exam** (A7 cycle 3, P4): sample → answer (no reveal) → submit → score report. On
 * submit the answers are real progress — each writes an `mcq_attempt` + advances FSRS (correct → Good,
 * wrong → Again) + opens/keeps a mistake — so a mock counts toward the feed and your stats.
 */
class MockExamViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { CONFIG, RUNNING, DONE }

    data class Ui(
        val phase: Phase = Phase.CONFIG,
        val loading: Boolean = false,
        val hasPack: Boolean = true,
        val questions: List<ExamQuestion> = emptyList(),
        val answers: Map<Int, Int> = emptyMap(),   // question index → chosen option
        val report: Report? = null,
    )

    data class SubjectScore(val name: String, val correct: Int, val total: Int)
    data class Report(
        val total: Int, val correct: Int, val elapsedSec: Long, val passMark: Int,
        val bySubject: List<SubjectScore>,
    ) {
        val pct get() = if (total > 0) correct * 100 / total else 0
        val passed get() = pct >= passMark
    }

    private val registry = PackRegistry(app)
    private val fsrs = FsrsBridge()
    private var examCode: String? = null
    private var startMs = 0L

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun start(n: Int) = viewModelScope.launch {
        _ui.update { it.copy(loading = true) }
        val pack = withContext(Dispatchers.IO) { registry.activePack() }
        if (pack == null) { _ui.update { it.copy(loading = false, hasPack = false) }; return@launch }
        examCode = pack.examCode
        val qs = withContext(Dispatchers.IO) {
            val r = MockExamRepository.open(pack.dbFile); val list = r?.sample(n).orEmpty(); r?.close(); list
        }
        startMs = System.currentTimeMillis()
        _ui.update { it.copy(loading = false, phase = Phase.RUNNING, questions = qs, answers = emptyMap(), report = null) }
    }

    fun answer(qIndex: Int, optIndex: Int) = _ui.update { it.copy(answers = it.answers + (qIndex to optIndex)) }

    fun submit() = viewModelScope.launch {
        val s = _ui.value
        val elapsed = (System.currentTimeMillis() - startMs) / 1000
        var correct = 0
        val subj = LinkedHashMap<String, IntArray>()
        s.questions.forEachIndexed { i, q ->
            val ok = s.answers[i] != null && s.answers[i] == q.correctIdx
            if (ok) correct++
            val arr = subj.getOrPut(q.subjectName ?: "Other") { intArrayOf(0, 0) }
            arr[1]++; if (ok) arr[0]++
        }
        withContext(Dispatchers.IO) { writeResults(s) }
        _ui.update {
            it.copy(phase = Phase.DONE, report = Report(
                total = s.questions.size, correct = correct, elapsedSec = elapsed, passMark = PASS_MARK,
                bySubject = subj.map { (k, v) -> SubjectScore(k, v[0], v[1]) }.sortedByDescending { it.total },
            ))
        }
    }

    private suspend fun writeResults(s: Ui) {
        val pack = examCode ?: return
        ProgressDatabase.ensureExists(getApplication(), pack)
        val pdb = ProgressDatabase.open(getApplication(), pack)
        val dao = pdb.dao()
        val now = Instant.now()
        s.questions.forEachIndexed { i, q ->
            val chosen = s.answers[i] ?: return@forEachIndexed   // unanswered → not scored, not written
            val ok = chosen == q.correctIdx
            dao.insertMcqAttempt(McqAttempt(item_id = q.feedItemId, subject_id = q.subjectId, correct = if (ok) 1 else 0, ts = now.toString()))
            val prior = dao.card("mcq", q.feedItemId)
            dao.upsertCard(fsrs.review(prior, "mcq", q.feedItemId, if (ok) FsrsRating.GOOD else FsrsRating.AGAIN, now))
            dao.insertReview(Review(item_type = "mcq", item_id = q.feedItemId, rating = if (ok) 3 else 1, ts = now.toString(), elapsed_ms = null))
            if (!ok) {
                val reset = dao.resetUnresolved("mcq", q.feedItemId, now.toString(), chosen)
                if (reset == 0) dao.insertMistake(Mistake(item_type = "mcq", item_id = q.feedItemId,
                    subject_id = q.subjectId, chosen_idx = chosen, ts = now.toString(), resolved = 0))
            }
        }
        pdb.close()
    }

    fun reset() = _ui.update { Ui() }

    private companion object { const val PASS_MARK = 60 }
}
