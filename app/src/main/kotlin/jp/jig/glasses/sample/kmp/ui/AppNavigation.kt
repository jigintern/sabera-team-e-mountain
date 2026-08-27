package jp.jig.glasses.sample.kmp.ui

/** 画面。**星しるべから台本・共有・取り込みを外した 4 枚**。 */
internal enum class AppScreen {
    HOME,
    CONNECTION,

    /** 方位合わせ。スマホの十字で粗く、稜線合わせで追い込む */
    CALIBRATION,

    /** 稜線を出している画面。**この間は頭の向き＝見ている方角** */
    RIDGE,
}

/**
 * 戻るキーの遷移表。**null は「1 つ戻る」では済まない画面**。
 *
 * - ホームは終わってよい場所（握るとアプリを閉じられなくなる）
 * - 稜線の画面は確認を挟む（**やめると方位合わせからやり直し**で、
 *   そのうえ地平線を焼き直すのに実機で 2.8 秒かかる）
 *
 * 画面を足したら必ずこの表も埋める。when が網羅を強制する。
 */
internal fun backDestination(screen: AppScreen): AppScreen? = when (screen) {
    AppScreen.HOME -> null
    AppScreen.CONNECTION -> AppScreen.HOME
    AppScreen.CALIBRATION -> AppScreen.CONNECTION
    AppScreen.RIDGE -> null
}
