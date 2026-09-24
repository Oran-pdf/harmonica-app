package com.orangames.harmonica.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Look(
    val id: String,
    val title: String,
    val blurb: String,
    val builtin: Boolean,
    val harp: Int,
    val lit: Int,
    val idle: Int,
    val motion: Int,
    val backdrop: Int,
)

object Looks {
    val harps = listOf("Plain", "Brass", "Wood", "Paper", "Stage", "Glass", "Copper", "Midnight", "Studio")
    val lits = listOf("Flat", "Glow", "Flame", "Ink", "Orb", "Shine", "Ring", "Spark")
    val idles = listOf("Squares", "Dim", "Rings", "Dents", "Paper", "Pinholes", "Dashes", "Hidden")
    val motions = listOf("Snap", "Shatter", "Rise", "Splatter", "Burst", "Drops", "Fade", "Pop")
    val backdrops = listOf("Parchment", "Smoke", "Walnut", "Mist", "Velvet", "Aurora", "Ember", "Festival")

    val builtins = listOf(
        Look("simple", "Simple", "The plain bar from your videos so far.", true, 0, 0, 0, 0, -1),
        Look("brass", "Brass", "A metal harp. Notes glow on, then crack into shards.", true, 1, 5, 3, 1, -1),
        Look("lantern", "Lantern", "Warm wood. Notes catch fire, then sparks lift away.", true, 2, 2, 3, 2, -1),
        Look("ink", "Ink", "Paper and ink. Notes stamp down, then splatter off.", true, 3, 3, 4, 3, -1),
        Look("stage", "Stage", "Neon lights. Notes bloom, then burst in a ring.", true, 4, 1, 1, 4, -1),
        Look("glass", "Glass", "Frosted glass. Notes swell, then break into drops.", true, 5, 4, 2, 5, -1),
    )

    fun builtin(id: String): Look? = builtins.find { it.id == id }
}

object SchemeStore {
    var selectedId by mutableStateOf("simple")
        private set
    var customs by mutableStateOf<List<Look>>(emptyList())
        private set

    val selected: Look
        get() = find(selectedId)

    fun all(): List<Look> = customs + Looks.builtins

    fun find(id: String): Look = Looks.builtin(id) ?: customs.find { it.id == id } ?: Looks.builtins.first()

    fun load(context: Context) {
        val file = file(context)
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        selectedId = json.optString("selected", "simple").ifBlank { "simple" }
        val list = ArrayList<Look>()
        val array = json.optJSONArray("customs") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list.add(
                Look(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    blurb = item.optString("blurb", "Your mix."),
                    builtin = false,
                    harp = item.optInt("harp"),
                    lit = item.optInt("lit"),
                    idle = item.optInt("idle"),
                    motion = item.optInt("motion"),
                    backdrop = item.optInt("backdrop", -1),
                ),
            )
        }
        customs = list
        if (find(selectedId).id != selectedId && selectedId !in list.map { it.id } && Looks.builtin(selectedId) == null) {
            selectedId = "simple"
        }
    }

    fun select(context: Context, id: String) {
        if (find(id).id != id && Looks.builtin(id) == null && customs.none { it.id == id }) return
        selectedId = id
        save(context)
    }

    fun add(context: Context, harp: Int, lit: Int, idle: Int, motion: Int, backdrop: Int): Look {
        val number = customs.size + 1
        val look = Look(
            id = "c" + System.currentTimeMillis().toString(36),
            title = "Your own $number",
            blurb = listOf(
                Looks.harps[harp],
                Looks.lits[lit],
                Looks.motions[motion],
            ).joinToString(" · "),
            builtin = false,
            harp = harp,
            lit = lit,
            idle = idle,
            motion = motion,
            backdrop = backdrop,
        )
        customs = listOf(look) + customs
        selectedId = look.id
        save(context)
        return look
    }

    fun delete(context: Context, id: String) {
        customs = customs.filterNot { it.id == id }
        if (selectedId == id) selectedId = "simple"
        save(context)
    }

    private fun save(context: Context) {
        val array = JSONArray()
        for (look in customs) {
            array.put(
                JSONObject()
                    .put("id", look.id)
                    .put("title", look.title)
                    .put("blurb", look.blurb)
                    .put("harp", look.harp)
                    .put("lit", look.lit)
                    .put("idle", look.idle)
                    .put("motion", look.motion)
                    .put("backdrop", look.backdrop),
            )
        }
        val json = JSONObject().put("selected", selectedId).put("customs", array)
        file(context).writeText(json.toString())
    }

    private fun file(context: Context) = File(context.filesDir, "schemes.json")
}
