package com.bainbridgetrails.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.ui.theme.PillShape

/**
 * Sign in with Apple (HIG custom button, ux.md §3.9): black in light mode, white in dark, Apple logo + "Sign in with
 * Apple" in the system font, 50pt pill. The logo is the system font's Apple glyph (U+F8FF), present on iOS only —
 * the only platform that shows this button.
 */
@Composable
fun AppleSignInButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color.White else Color.Black
    val fg = if (dark) Color.Black else Color.White
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        enabled = enabled && !loading,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = bg.copy(alpha = 0.38f), disabledContentColor = fg.copy(alpha = 0.7f)),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            Text("", style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium))
    }
}

/**
 * Sign in with Google (Google Identity branding, ux.md §3.9): light = white fill, #747775 stroke, #1F1F1F text;
 * dark = #131314 fill, #8E918F stroke, #E3E3E3 text; pill, 48dp; multicolor G.
 */
@Composable
fun GoogleSignInButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF131314) else Color.White
    val stroke = if (dark) Color(0xFF8E918F) else Color(0xFF747775)
    val fg = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled && !loading,
        shape = PillShape,
        border = BorderStroke(1.dp, stroke),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg, disabledContainerColor = bg.copy(alpha = 0.6f), disabledContentColor = fg.copy(alpha = 0.5f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg) else GoogleG(Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** The four-color "G" mark, drawn (no raster asset). */
@Composable
private fun GoogleG(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension * 0.2f
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        val tl = Offset(inset, inset)
        fun arc(color: Color, start: Float, sweep: Float) = drawArc(color, start, sweep, false, tl, arcSize, style = Stroke(w))
        arc(Color(0xFFEA4335), 200f, 115f) // red, top
        arc(Color(0xFFFBBC05), 145f, 55f) // yellow, left
        arc(Color(0xFF34A853), 45f, 100f) // green, bottom
        arc(Color(0xFF4285F4), -5f, 50f) // blue, right
        drawRect(Color(0xFF4285F4), Offset(size.width / 2, size.height / 2 - w / 2), Size(size.width / 2 - inset + w / 2, w))
    }
}
