package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(private val prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** True when the judgment route exists at all. See [Prefs.judgeEnabled]. */
    private val judging: Boolean get() = prefs.judgeEnabled

    /**
     * The 7 judgment questions. Errors come back inside [Analysis.error].
     * With judgment off this returns an empty result and sends no request —
     * the caller is expected to skip the judgment panel entirely.
     */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        if (!judging) return emptyAnalysis()
        return judgeClient.judge(snapshot, relationship, ctx)
    }

    /**
     * Draft 3 candidates on the reply route, then rank them on the judge route.
     * With judgment off the ranking call is skipped too, so only the reply
     * endpoint is ever contacted.
     */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship, ctx)
        if (!judging) return candidates.map { RankedReply(it, 0.0) }
        return judgeClient.rank(snapshot, relationship, candidates, ctx)
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        if (!judging) {
            val start = System.currentTimeMillis()
            return try {
                emptyAnalysis(
                    rankedReplies = replyClient.draft(snapshot, relationship, ctx)
                        .map { RankedReply(it, 0.0) },
                    latencyMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                emptyAnalysis(error = e.message ?: e.javaClass.simpleName)
            }
        }
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    private fun emptyAnalysis(
        rankedReplies: List<RankedReply> = emptyList(),
        latencyMs: Long = 0L,
        error: String? = null
    ) = Analysis(null, null, null, null, null, null, null, rankedReplies, latencyMs, error)
}
