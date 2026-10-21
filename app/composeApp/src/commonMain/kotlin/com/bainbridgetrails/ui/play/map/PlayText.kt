package com.bainbridgetrails.ui.play.map

/** Positional `%n$s` / `%n$d` substitution for `play_*` templates resolved outside composition (pure logic). */
internal object PlayText {
    fun fmt(template: String, vararg args: Any): String {
        var out = template
        args.forEachIndexed { i, a ->
            val n = i + 1
            out = out.replace("%$n\$s", a.toString()).replace("%$n\$d", a.toString())
        }
        return out
    }
}
