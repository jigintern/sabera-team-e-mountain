package jp.jig.glasses.sample.kmp.geo

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 座標変換パイプラインの ①〜⑤。
 * 仕様は docs/team-e/20_coordinate-system.md。Android に依存しないので JVM テストから直接叩ける。
 *
 * world 座標系は ENU（X=東 / Y=北 / Z=天頂）、方位角は北 = 0° の東回り、右手系。
 * ここを変えると全段の符号が狂うので、規約はこのファイルだけに置く。
 */

const val DEG = 180.0 / Math.PI
const val RAD = Math.PI / 180.0

/** 単位ベクトル。角度で持つと天頂で方位角が定義できず cos h でも割れないため、内部はこれで統一する */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vec3(x / scale, y / scale, z / scale)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = length()
        return if (n < 1e-12) Vec3(0.0, 1.0, 0.0) else Vec3(x / n, y / n, z / n)
    }

    /** [axis] 方向の成分を落とした残り。長さは残す（0 に近いかで有効性を見たいので） */
    fun dropAlong(axis: Vec3): Vec3 = this - axis * (this dot axis)
}

/**
 * グラスのヨーを方位[度]へ直す。**符号の規約はここだけに置く。**
 *
 * **ヨーは左を向くと増え、方位は右を向くと増える。** 実機で測った 3 つから決まる:
 *
 * | 実測 | 出どころ |
 * |---|---|
 * | 上 = 加速度の +X 軸 | 加速度軸の自動判定 |
 * | ジャイロは右手系 | 同上（基底と回転の向きを突き合わせ） |
 * | `gyroXDps` とヨーは同符号 | 90° を 7 回まわして確認 |
 *
 * 右手系で回転軸が上を向いていれば、正の角速度は**上から見て反時計回り＝左**。
 * ヨーがそれと同符号なので、**ヨーは方位と逆に回る**。
 *
 * **2026-08-21 に実機で確認した。** 誘導が「左へ 30°」と言う状態から左を向くと
 * 30 → 40 → 50 と増え、目標が逃げていった。足し算のままだと、
 * 首を振った分だけ 2 倍の速さでずれる（合わせた瞬間だけ正しい）。
 */
fun azimuthFromYaw(yawDeg: Double, headingOffsetDeg: Double): Double =
    (normalizeDeg(headingOffsetDeg - yawDeg) + 360.0) % 360.0

/** [azimuthFromYaw] の逆。「この方位を向いていたときヨーがこうだった」からオフセットを作る */
fun headingOffsetFor(azimuthDeg: Double, yawDeg: Double): Double = normalizeDeg(azimuthDeg + yawDeg)

/** 方位角・高度[度] → ENU の単位ベクトル */
fun enu(azDeg: Double, altDeg: Double): Vec3 {
    val a = azDeg * RAD
    val h = altDeg * RAD
    val c = cos(h)
    return Vec3(c * sin(a), c * cos(a), sin(h))
}

private fun equatorialVector(raDeg: Double, decDeg: Double): Vec3 {
    val ra = raDeg * RAD
    val dec = decDeg * RAD
    return Vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
}

private fun equatorialAngles(v: Vec3): DoubleArray = doubleArrayOf(
    ((atan2(v.y, v.x) * DEG) % 360.0 + 360.0) % 360.0,
    asin(v.z.coerceIn(-1.0, 1.0)) * DEG,
)

/**
 * 標準大気での Sæmundsson の近似式（**真高度 → 見かけの高度**の向き）。
 * 地平線では約 0.48° 持ち上がり、10°以上では 0.1°未満になる。
 * 気温・気圧が無いので低高度の完全補正ではないが、補正しない場合の系統誤差を減らす。
 *
 * **Bennett の式と取り違えない。** あちらは見かけ → 真の向きで、係数も 7.31 / 4.4 と違う。
 * 逆向きが要るときは [geometricAltitudeDeg]（この式を反復で戻す）を使う。
 */
fun apparentAltitudeDeg(geometricAltitudeDeg: Double): Double {
    if (geometricAltitudeDeg < -1.0 || geometricAltitudeDeg >= 90.0) return geometricAltitudeDeg
    val correction = 1.02 / tan(
        (geometricAltitudeDeg + 10.3 / (geometricAltitudeDeg + 5.11)) * RAD,
    ) / 60.0
    return (geometricAltitudeDeg + correction).coerceAtMost(90.0)
}

/** 見かけの高度を、星表計算で使う幾何学的高度へ反復で戻す。 */
fun geometricAltitudeDeg(apparentDeg: Double): Double {
    var geometric = apparentDeg
    repeat(8) { geometric -= apparentAltitudeDeg(geometric) - apparentDeg }
    return geometric
}

/**
 * 視線を中心とした接平面の基底。
 * up は天頂を視線に直交する成分だけ残したもので、これが「地平線を水平に固定する」の実体。
 *
 * **[rollDeg] で首の傾きに追従する。** パネルは頭に固定されているので、首を傾けると
 * 本物の地平線は画面の中で回る。絵を水平のまま出すと**地平線だけが傾いて残る**
 * （実機で確認・2026-08-22）。6DoF にロールは無いので、加速度から起こして渡す。
 */
class Basis(azDeg: Double, altDeg: Double, rollDeg: Double = 0.0) {
    val forward: Vec3 = enu(azDeg, altDeg)
    val up: Vec3
    val right: Vec3

    init {
        val f = forward
        val u = Vec3(-f.z * f.x, -f.z * f.y, 1.0 - f.z * f.z)
        val level = if (hypot(hypot(u.x, u.y), u.z) < 1e-9) Vec3(0.0, 1.0, 0.0) else u.normalized()
        val levelRight = f cross level
        if (rollDeg == 0.0) {
            up = level
            right = levelRight
        } else {
            // 視線まわりに基底ごと回す。**絵を回すのではなく見ている枠を回す**ので、
            // 星も星座線も地平線も同じだけ回り、互いの位置関係は崩れない
            val c = cos(rollDeg * RAD)
            val s = sin(rollDeg * RAD)
            up = Vec3(
                c * level.x + s * levelRight.x,
                c * level.y + s * levelRight.y,
                c * level.z + s * levelRight.z,
            ).normalized()
            right = f cross up
        }
    }
}

/**
 * 加速度から首の傾き[度]を出す。**右耳が下がる向きを正**にする。
 *
 * 6DoF はピッチとヨーしか返さない（SDK 0.6.0）ので、重力そのものから起こす。
 *
 * **機体の軸は実測してある**（docs/team-e/70_measurements.md・静止 865 件・ピッチ幅 76°）。
 * **上 = +X・前 = −Y**、したがって **右 = 前 × 上 = +Z**。
 * 静止中の加速度はこの 3 軸で
 *
 * ```
 * a = 1000 ( sinθ·前 + cosθ·cosφ·上 − cosθ·sinφ·右 )      θ=ピッチ φ=ロール
 * ```
 *
 * になるので、`aX = 1000·cosθ·cosφ` と `aZ = −1000·cosθ·sinφ` から
 * **ピッチに影響されずに φ だけ**が出る（`tanφ = −aZ / aX`）。
 *
 * **「X 軸が右」と仮定してはいけない。** そう書いていたときは水平に構えただけで
 * `atan2(1000, 0) = 90°` を返し、**返る値は実際には (90° − ピッチ) だった**。
 * 星図が枠ごと 90° 回り、見上げるほど回転量が変わるので、**目標が画面の中を動いて逃げた**。
 * 左右の傾きも区別できていなかった（ロール +20° と −20° がどちらも 70°）。
 *
 * 真上（cosθ → 0）ではロールそのものが定義できないので 0 を返す（そのときは地平線も画面に無い）。
 * 動いている間は重力以外の加速度が乗るので、**首が止まっているときだけ使う**。
 */
fun rollFromAccel(xMilliG: Int, yMilliG: Int, zMilliG: Int): Double {
    val up = xMilliG.toDouble()
    val right = zMilliG.toDouble()
    // 真上を向くと上も右も 0 に落ちる。**ここで Y（前）を混ぜない**（混ぜるとピッチが漏れる）
    if (hypot(up, right) < 200.0) return 0.0
    return atan2(ROLL_SIGN * -right, ROLL_SIGN * up) * DEG
}

/**
 * ロールの向き。**実機で 180° 回っていたら符号を変えるだけ**で直る。
 *
 * 加速度計が重力を「反力」で返すか「加速度」で返すかは SDK に書かれていない。
 * 反力なら水平で `aX = +1000`、加速度なら `−1000` になり、**ロールが 180° ずれる**
 * （71_yaw-drift.md に「実機のログで −177° と出た」の記録がある）。
 * **上と右を同時に反転させる**ので、片方だけに掛けてはいけない。
 */
private const val ROLL_SIGN = 1.0

/**
 * ステレオ投影 r = 2 tan(θ/2)。等角なので星座の形が崩れない。
 * 視野外は null。画面座標は左上原点で、y は下向き。
 */
fun project(v: Vec3, b: Basis, k: Double, w: Int, h: Int): DoubleArray? {
    val cosTheta = v dot b.forward
    if (cosTheta <= -0.3) return null
    val x = v dot b.right
    val y = v dot b.up
    val len = hypot(x, y)
    if (len < 1e-12) return doubleArrayOf(w / 2.0, h / 2.0)
    val r = 2.0 * tan(acos(cosTheta.coerceIn(-1.0, 1.0)) / 2.0)
    return doubleArrayOf(w / 2.0 + k * r * x / len, h / 2.0 - k * r * y / len)
}

/** 2 方向のなす角[度]。視野に入っているかを度で判定するのに使う */
fun angleBetweenDeg(a: Vec3, b: Vec3): Double = acos((a dot b).coerceIn(-1.0, 1.0)) * DEG

/**
 * 横画角 [fovDeg]・縦横比 [panelAspect]（高さ ÷ 幅）のパネルに、この向きが入るか。
 *
 * **円で切らない。** 星図は横長で、544×340・画角 35° なら**横は ±17.5° あるのに縦は ±11.0°**、
 * 隅は 20.6° まで届く。半径 fov/2 の円で切ると、**上下は絵に無いものを拾い、隅は絵にあるものを
 * 落とす**。AI へ渡す根拠がそこで絵とずれる（#37）。
 *
 * 判定は [project] の枠内判定そのもので、画素に直す前の長さで測るだけ
 * （画面 x が 0..w に入る ⇔ |r·x/len| ≤ (w/2)/k = 2 tan(fov/4)）。
 * **[projectionScale] と同じ式を使うので、星図の幅が 544 でも 528 でも答えは変わらない。**
 */
fun withinPanel(v: Vec3, b: Basis, fovDeg: Double, panelAspect: Double): Boolean {
    val cosTheta = v dot b.forward
    if (cosTheta <= 0.0) return false
    val x = v dot b.right
    val y = v dot b.up
    val len = hypot(x, y)
    val halfWidth = 2.0 * tan(fovDeg * RAD / 4.0)
    if (len < 1e-12) return true
    val r = 2.0 * tan(acos(cosTheta.coerceIn(-1.0, 1.0)) / 2.0)
    return abs(r * x / len) <= halfWidth && abs(r * y / len) <= halfWidth * panelAspect
}

/** 横 fovDeg が幅 w に収まるときの倍率。r = 2 tan(θ/2) の θ = fov/2 が w/2 に来る */
fun projectionScale(w: Int, fovDeg: Double): Double = (w / 2.0) / (2.0 * tan(fovDeg * RAD / 4.0))

/** 高度を ±90° に収める。オフセットの足し込みで天頂・天底を越えたときの保険 */
fun clampAltDeg(deg: Double): Double = deg.coerceIn(-90.0, 90.0)

/** 角度の差を -180..180 に畳む。ヨーが ±180 で折り返すので、差分を取るときは必ず通す */
fun normalizeDeg(deg: Double): Double {
    var v = deg % 360.0
    if (v > 180.0) v -= 360.0
    if (v < -180.0) v += 360.0
    return if (abs(v) < 1e-12) 0.0 else v
}

private const val B1875_DAYS_FROM_J2000 = -45_655.74145
