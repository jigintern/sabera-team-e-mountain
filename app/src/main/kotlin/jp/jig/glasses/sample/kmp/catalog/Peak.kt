package jp.jig.glasses.sample.kmp.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * 同梱の山名カタログ 1 座。実体は `data/peaks.json`（生成物）で、
 * 中身は `tools/build-peak-catalog.py` が国土地理院の 1003 山から作る。
 *
 * **[name] は一意ではない。** 「駒ヶ岳」は 6 座、「白根山」は 2 座ある。
 * 一意に指すなら **[name] と [elevationM] の組**を使う（生成側で衝突ゼロを確認済み）。
 */
data class Peak(
    /** 元データの「山名＜山頂名＞」。正式名だが人が呼ぶ名前とは限らない */
    val name: String,
    /** 読み。声で聞かれたときに引くのに使う */
    val yomi: String,
    val elevationM: Int,
    val latDeg: Double,
    val lonDeg: Double,
    val pref: String,
    /** 日本百名山の通称。**空なら百名山ではない** */
    val famousName: String,
) {
    val isFamous: Boolean get() = famousName.isNotEmpty()

    /**
     * グラスの枠に出す名前。**枠は日本語 5 文字ぶんしかない。**
     *
     * 優先順は 3 段:
     * 1. 百名山なら通称（「赤岳」より**「八ヶ岳」**のほうが伝わる。使う人は登山の初心者）
     * 2. 「◯◯連山＜△△＞」なら山頂名のほう（連山の名前では山を指せない）
     * 3. 「◯◯（別名）」なら括弧を落とす
     */
    val label: String by lazy {
        if (isFamous) return@lazy famousName
        val summit = SUMMIT.find(name)?.groupValues?.get(1)
        if (summit != null) return@lazy summit
        ALIAS.replace(name, "")
    }

    private companion object {
        val SUMMIT = Regex("＜(.+?)＞")
        val ALIAS = Regex("（.+?）")
    }
}

/** 同梱カタログ全体。標高の高い順に並んでいる（ラベルを切る順序と揃えてある）。 */
class PeakCatalog private constructor(val peaks: List<Peak>) {

    /** 日本百名山だけ。**ラベルの優先枠に入れる 100 座。** */
    val famous: List<Peak> = peaks.filter { it.isFamous }

    private val byName: Map<String, Peak> = buildMap {
        // 標高の高い順に入れるので、同名なら高いほうが残る（putIfAbsent の効果）
        peaks.forEach { p ->
            listOfNotNull(p.famousName.ifEmpty { null }, p.name, p.label, p.yomi)
                .forEach { key -> putIfAbsent(key, p) }
        }
    }

    /**
     * 通称・正式名・ラベル・読みのどれでも引く。
     *
     * 「八ヶ岳はどこ？」と聞かれて赤岳を返せないと案内が成立しない。
     */
    fun findByName(query: String): Peak? = byName[query.trim()]

    companion object {
        /**
         * `data/peaks.json` を読む。
         *
         * 列の並びは JSON の `fields` を見て決める。**位置を決め打ちにしない** —
         * 生成側で列を足したときに黙って別の値を読み始めるのを避けるため。
         */
        fun parse(json: String): PeakCatalog {
            val root = JSONObject(json)
            val fields = root.getJSONArray("fields").let { f ->
                (0 until f.length()).associate { f.getString(it) to it }
            }
            fun idx(key: String) = requireNotNull(fields[key]) { "peaks.json に $key が無い" }
            val name = idx("name")
            val yomi = idx("yomi")
            val elev = idx("elevationM")
            val lat = idx("latDeg")
            val lon = idx("lonDeg")
            val pref = idx("pref")
            val famous = idx("famousName")

            val rows: JSONArray = root.getJSONArray("peaks")
            val peaks = ArrayList<Peak>(rows.length())
            for (i in 0 until rows.length()) {
                val r = rows.getJSONArray(i)
                peaks += Peak(
                    name = r.getString(name),
                    yomi = r.getString(yomi),
                    elevationM = r.getInt(elev),
                    latDeg = r.getDouble(lat),
                    lonDeg = r.getDouble(lon),
                    pref = r.getString(pref),
                    famousName = r.getString(famous),
                )
            }
            return PeakCatalog(peaks)
        }
    }
}
