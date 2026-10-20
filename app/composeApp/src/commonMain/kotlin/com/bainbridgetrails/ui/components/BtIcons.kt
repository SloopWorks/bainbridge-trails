package com.bainbridgetrails.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bainbridgetrails.resources.Res
import com.bainbridgetrails.resources.ic_arrow_back
import com.bainbridgetrails.resources.ic_check
import com.bainbridgetrails.resources.ic_chevron_right
import com.bainbridgetrails.resources.ic_close
import com.bainbridgetrails.resources.ic_cloud_off
import com.bainbridgetrails.resources.ic_directions_walk
import com.bainbridgetrails.resources.ic_emoji_events
import com.bainbridgetrails.resources.ic_error
import com.bainbridgetrails.resources.ic_expand_more
import com.bainbridgetrails.resources.ic_hiking
import com.bainbridgetrails.resources.ic_hiking_filled
import com.bainbridgetrails.resources.ic_info
import com.bainbridgetrails.resources.ic_keyboard_arrow_down
import com.bainbridgetrails.resources.ic_location_disabled
import com.bainbridgetrails.resources.ic_lock
import com.bainbridgetrails.resources.ic_map
import com.bainbridgetrails.resources.ic_map_filled
import com.bainbridgetrails.resources.ic_more_horiz
import com.bainbridgetrails.resources.ic_my_location
import com.bainbridgetrails.resources.ic_my_location_filled
import com.bainbridgetrails.resources.ic_open_in_new
import com.bainbridgetrails.resources.ic_person
import com.bainbridgetrails.resources.ic_person_filled
import com.bainbridgetrails.resources.ic_search
import com.bainbridgetrails.resources.ic_settings
import com.bainbridgetrails.resources.ic_verified_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Material Symbols Rounded (24dp, weight 400; Apache 2.0) as vector drawables in composeResources/drawable.
 * No icon library dependency: add a symbol by converting its SVG (viewBox 0 -960 960 960) the same way.
 */
object BtIcons {
    val ArrowBack = Res.drawable.ic_arrow_back
    val Check = Res.drawable.ic_check
    val ChevronRight = Res.drawable.ic_chevron_right
    val Close = Res.drawable.ic_close
    val CloudOff = Res.drawable.ic_cloud_off
    val DirectionsWalk = Res.drawable.ic_directions_walk
    val Trophy = Res.drawable.ic_emoji_events
    val Error = Res.drawable.ic_error
    val ExpandMore = Res.drawable.ic_expand_more
    val Hiking = Res.drawable.ic_hiking
    val HikingFilled = Res.drawable.ic_hiking_filled
    val Info = Res.drawable.ic_info
    val KeyboardArrowDown = Res.drawable.ic_keyboard_arrow_down
    val LocationDisabled = Res.drawable.ic_location_disabled
    val Lock = Res.drawable.ic_lock
    val Map = Res.drawable.ic_map
    val MapFilled = Res.drawable.ic_map_filled
    val MoreHoriz = Res.drawable.ic_more_horiz
    val MyLocation = Res.drawable.ic_my_location
    val MyLocationFilled = Res.drawable.ic_my_location_filled
    val OpenInNew = Res.drawable.ic_open_in_new
    val Person = Res.drawable.ic_person
    val PersonFilled = Res.drawable.ic_person_filled
    val Search = Res.drawable.ic_search
    val Settings = Res.drawable.ic_settings
    val Verified = Res.drawable.ic_verified_filled
}

/** Tinted icon from [BtIcons]. [contentDescription] null = decorative. */
@Composable
fun BtIcon(
    icon: DrawableResource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}
