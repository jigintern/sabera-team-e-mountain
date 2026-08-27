package jp.jig.glasses.sample.kmp.glass

/**
 * 合わせる印。**「この山を真ん中に入れて」と言うなら、真ん中が見えていないといけない。**
 *
 * 稜線と同じ 1 枚に焼く。別の画像 id にすると 2 枚ぶんバッファを食ううえ、
 * 転送が 2 回になって片方だけ届いている時間ができる。
 *
 * **縦線を全高に引かない。** 稜線と交わったところが読めなくなるので、真ん中を空けて
 * 上下の羽根だけにする。空けた隙間に山頂を入れてもらう形そのものが指示になる。
 *
 * 階調は最上段（[Ink.SKYLINE]）。中間階調は屋外の空に負ける。
 */
fun ByteArray.sightMark(width: Int, height: Int, x: Int) {
    val radius = lineRadius(width)
    val gap = height / 6
    line(width, height, x, 0, x, height / 2 - gap, Ink.SKYLINE, radius)
    line(width, height, x, height / 2 + gap, x, height - 1, Ink.SKYLINE, radius)
}
