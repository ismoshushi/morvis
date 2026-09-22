package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * X / Twitter timeline (com.twitter.android), measured on device 2026-09-22
 * (uiautomator dump, 1156x2510, node tree fully readable).
 *
 * Distinct from [XAdapter], which handles DM threads. Both live under the same
 * package, so [XRouterAdapter] tries the thread first and falls back here.
 *
 * The tree is NOT obfuscated — a plain accessibility service reads tweet bodies
 * directly, so unlike WeChat this needs no disguised service. But every node has
 * an EMPTY resource-id, so nothing can be matched by id the way QQ can.
 *
 * A tweet is therefore located structurally: its action row is the only stable
 * anchor (six buttons whose content-desc is 回复 / 转帖 / 喜欢 / 展示量 / 书签 /
 * 分享). Those rows cut the feed into cells; inside each cell the body is the
 * widest TextView (bodies span ~0.8 of the width) and the author is the
 * "@handle" TextView. The metric row ("3 4 68 143K") is numeric-only and gets
 * dropped; promoted labels ("来自 xx.com") have no handle and never survive.
 *
 * This is a feed, not a conversation: every item is "other", and there is no
 * reply for us to answer to, hence [manualOnly] — auto-analysis here would fire
 * on every scroll and spend tokens on posts the user is not even reading.
 */
class XTimelineAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"
    override val manualOnly = true

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels

        val texts = ArrayList<Leaf>()
        val actionBottoms = ArrayList<Int>()
        val box = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val desc = node.contentDescription?.toString()?.trim()
            if (desc != null && desc in ACTION_DESCS) {
                node.getBoundsInScreen(box)
                actionBottoms.add(box.bottom)
            }
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && node.className?.toString() == TEXT_VIEW) {
                node.getBoundsInScreen(box)
                texts.add(Leaf(box.left, box.top, box.right, box.bottom, t))
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (actionBottoms.isEmpty() || texts.isEmpty()) return null

        // Merge buttons of the same row, then treat each row as a cell boundary.
        val separators = ArrayList<Int>()
        actionBottoms.sorted().forEach { b ->
            val last = separators.lastOrNull()
            if (last == null || b - last > ROW_MERGE) separators.add(b)
            else separators[separators.lastIndex] = maxOf(last, b)
        }

        val minWidth = (width * 0.6).toInt()
        val middle = height / 2
        var best: Post? = null
        var bestDistance = Int.MAX_VALUE
        var top = 0
        for (bottom in separators + listOf(height)) {
            val post = postIn(texts, top, bottom, minWidth, height)
            top = bottom
            if (post == null) continue
            val distance = kotlin.math.abs(post.center - middle)
            if (distance < bestDistance) { bestDistance = distance; best = post }
        }
        val post = best ?: return null

        val body = if (post.body.length > MAX_BODY) post.body.take(MAX_BODY) else post.body
        val title = listOfNotNull(post.name, post.handle).joinToString(" ")
        return ChatSnapshot(title.ifBlank { null }, listOf(Msg("other", body)))
    }

    /** Pick the one tweet body inside a cell; null when the cell holds no post. */
    private fun postIn(all: List<Leaf>, top: Int, bottom: Int, minWidth: Int, height: Int): Post? {
        val inner = all.filter { it.top >= top && it.bottom <= bottom }
        if (inner.isEmpty()) return null
        val handleLeaf = inner.firstOrNull { it.text.startsWith("@") } ?: return null
        if (handleLeaf.text.length < 2) return null
        // Name sits on the same row as the handle, left of it.
        val name = inner.firstOrNull {
            it.top in (handleLeaf.top - 8)..(handleLeaf.bottom + 8) &&
                it.right <= handleLeaf.left && it.text.length <= 24 && !isNoise(it.text)
        }?.text

        var bodyLeaf: Leaf? = null
        var bestArea = -1
        for (leaf in inner) {
            val w = leaf.right - leaf.left
            if (w < minWidth || isNoise(leaf.text)) continue
            // Visible enough to be the post the user is reading.
            val visible = minOf(leaf.bottom, height) - maxOf(leaf.top, 0)
            if (visible < (leaf.bottom - leaf.top) * 0.5) continue
            val area = w * visible
            if (area > bestArea) { bestArea = area; bodyLeaf = leaf }
        }
        val leaf = bodyLeaf ?: return null
        return Post(name, handleLeaf.text, leaf.text, (leaf.top + leaf.bottom) / 2)
    }

    /** Chrome we must never mistake for a post body. */
    private fun isNoise(t: String): Boolean =
        t.startsWith("@") || t.startsWith("·") || t.startsWith("来自") ||
            t.startsWith("推广") || t.startsWith("Promoted", true) ||
            t.all { it.isDigit() || it == '.' || it == ',' || it == 'K' || it == 'M' }

    private data class Leaf(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String)
    private data class Post(val name: String?, val handle: String, val body: String, val center: Int)

    companion object {
        private const val TEXT_VIEW = "android.widget.TextView"
        private const val ROW_MERGE = 24   // px: action buttons share one row within this
        private const val MAX_BODY = 600   // chars sent onward; long-form posts get clipped
        val ACTION_DESCS = setOf("回复", "转帖", "喜欢", "展示量", "书签", "分享")
    }
}

/**
 * X has two surfaces under one package: a DM thread (a real conversation, where
 * auto-analysis is fine) and the timeline (a feed, which must stay manual).
 * Try the thread's rules first — they are strict and return null off-thread —
 * then fall back to the timeline. [manualOnly] follows whichever one answered,
 * so the capture service needs no X-specific branch.
 */
class XRouterAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"
    private val dm = XAdapter()
    private val timeline = XTimelineAdapter()
    @Volatile private var lastWasTimeline = false

    override val manualOnly: Boolean get() = lastWasTimeline

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        dm.extract(root, res)?.let { lastWasTimeline = false; return it }
        val s = timeline.extract(root, res)
        lastWasTimeline = s != null
        return s
    }
}
