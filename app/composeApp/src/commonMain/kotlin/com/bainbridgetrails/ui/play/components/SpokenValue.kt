package com.bainbridgetrails.ui.play.components

import androidx.compose.runtime.Composable
import com.bainbridgetrails.data.LocalGoal
import org.jetbrains.compose.resources.pluralStringResource

/**
 * [v] with its unit spoken in full ("820 feet"; docs/play/trails/ux.md § 1): TalkBack / VoiceOver do not expand "ft"
 * reliably. Through `LocalGoal.current` like every trails key. Used by every trails a11y string that carries a value
 * (Logbook rows, challenge cards, the Me milestone strip and sheet).
 */
@Composable
fun spokenValue(v: TrailValue): String = pluralStringResource(LocalGoal.current.p(v.unit.spoken), v.pluralQuantity, v.number)
