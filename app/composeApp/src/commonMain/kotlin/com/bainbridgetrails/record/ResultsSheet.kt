package com.bainbridgetrails.record

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bainbridgetrails.coverage.TrailStatus
import com.bainbridgetrails.tracking.SessionSummary
import kotlin.math.roundToLong

/** 0.1234 -> "12.3%". */
internal fun pct(fraction: Double): String = "${oneDecimal(fraction * 100)}%"

/** Change in percentage points, signed: "+0.4 pts". */
internal fun pctDelta(before: Double, after: Double): String {
    val d = (after - before) * 100
    return (if (d >= 0) "+" else "−") + oneDecimal(kotlin.math.abs(d)) + " pts"
}

internal fun oneDecimal(x: Double): String {
    val tenths = (x * 10).roundToLong()
    val a = kotlin.math.abs(tenths)
    return (if (tenths < 0) "-" else "") + "${a / 10}.${a % 10}"
}

private fun TrailStatus.label() = when (this) {
    TrailStatus.NOT_STARTED -> "Not started"
    TrailStatus.PARTWAY -> "Partway"
    TrailStatus.NEARLY_DONE -> "Nearly done"
    TrailStatus.COMPLETE -> "Done"
}

/** What the walk changed: island %, trails that rose, newly completed trails, dropped fixes. */
@Composable
internal fun ResultsSheet(summary: SessionSummary, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val names = summary.trails.associate { it.trailId to it.name }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(20.dp)
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Walk saved", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
        Text(
            "Island: ${pct(summary.islandBefore)} → ${pct(summary.islandAfter)}  (${pctDelta(summary.islandBefore, summary.islandAfter)})",
            fontSize = 17.sp,
            color = Color(0xFF1E2A24),
        )
        if (summary.approximateLocation) Text(
            "Recorded with approximate location: trails may not have matched. Turn on Precise Location for this app in Settings.",
            fontSize = 13.sp,
            color = Color(0xFFB3261E),
        )
        if (!summary.verified) Text("Unverified walk (activity or step data unavailable)", fontSize = 13.sp, color = Color(0xFF8A5A00))
        if (summary.newlyCompleted.isNotEmpty()) {
            Text("Newly completed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF7B1689))
            for (id in summary.newlyCompleted) Text("• ${names[id] ?: id}", fontSize = 15.sp, color = Color(0xFF7B1689))
        }
        if (summary.trails.isEmpty()) {
            Text("No trail progress from this walk.", fontSize = 15.sp, color = Color(0xFF5A635D))
        } else {
            Text("Trails", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E2A24))
            for (t in summary.trails) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, Modifier.weight(1f), fontSize = 15.sp, color = Color(0xFF1E2A24))
                    Text("${pct(t.before)} → ${pct(t.after)}", fontSize = 15.sp, color = Color(0xFF1E2A24))
                    Text("  ${t.status.label()}", fontSize = 13.sp, color = Color(0xFF5A635D))
                }
            }
        }
        if (summary.droppedVehicle > 0) Text("${summary.droppedVehicle} points dropped (vehicle or bike)", fontSize = 13.sp, color = Color(0xFF5A635D))
        if (summary.droppedSteps > 0) Text("${summary.droppedSteps} points dropped (too few steps)", fontSize = 13.sp, color = Color(0xFF5A635D))
        Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}
