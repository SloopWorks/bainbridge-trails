package com.bainbridgetrails.play.fx.effects

import com.bainbridgetrails.play.fx.FxInstance
import com.bainbridgetrails.play.fx.FxLive
import com.bainbridgetrails.play.fx.FxSpec
import com.bainbridgetrails.play.fx.FxStage

/** A live item for [spec] with scene sequence [seq] (what FxScene hands a renderer). */
internal fun live(spec: FxSpec, seq: Long, reduced: Boolean = false, demoted: Boolean = false): FxLive =
    FxLive(FxInstance(spec, seq, spawnedAtMs = 0), FxStage.RUN, t = 0, reduced = reduced, demoted = demoted)
