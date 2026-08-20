package tech.illusion.spacefireworks.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton

/**
 * The show's controls, shared by the window's toolbar and the Stage's HUD.
 *
 * One composable for both so the two can never drift apart -- they had already started to, with the
 * window carrying 点火/沉浸 and the HUD carrying 点火/退出沉浸 as separately written rows. The only
 * difference between the two is the third button's icon and label.
 *
 * Icon-only, on a transparent panel: the controls sit inside the scene they drive, and a glass slab
 * with three captions under it was competing with the show for attention.
 *
 * ### Why this does not collapse
 *
 * It was built to collapse to the single 点火 icon at rest and expand on focus, which is what was
 * asked for. **Ray/gaze hover is never reported in this container**, so that panel could not be
 * opened at all on device. Measured, not assumed: with a hover log on all four interaction sources
 * (the panel Row plus each of the three buttons), a full session on the headset produced exactly
 * one line -- the initial `hover=false` -- and zero `hover=true`. The same build on the emulator,
 * driven by its mouse cursor, reported `panel=true` immediately, so the collection code was right
 * and the events simply do not exist.
 *
 * Two dead ends worth not repeating:
 * - `Modifier.hoverable` on the panel Row alone. Ray hover targets *interactive* elements; a bare
 *   Row is not one. Moving collection onto the buttons' own `interactionSource` was correct and
 *   still produced nothing.
 * - `Modifier.spatialHoverEffect`, which looks like the PICO-native answer. Its block is invoked
 *   twice up front, once with `isActive=true` and once `false`, to precompute both visual states.
 *   It describes an effect; it does not report hover.
 *
 * If a focus signal ever becomes available, the collapse logic is a small amount of state around
 * this Row -- but do not reach for hover again without checking the log first.
 */
@Composable
fun ControlPanel(
    onIgnite: () -> Unit,
    immersiveIcon: PanelIcon,
    immersiveLabel: String,
    onImmersive: () -> Unit,
) {
    Row(
        modifier = Modifier.defaultMinSize(minHeight = 64.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelAction(icon = PanelIcon.Flame, label = "点火", onClick = onIgnite)

        // Deliberately an IconButton like its neighbours, not a ToggleIconButton.
        //
        // The toggle variant is the semantically nicer component and it looked wrong next to the
        // other two: its defaults are a different size (`Min`, 38x32, against `Regular`'s 56x48),
        // a different shape and a different container colour, and none of that can be matched by
        // reading the IconButton defaults back -- `ButtonColors.containerColor` is `internal`.
        // Copying the colour roles by hand would just be a second place to drift. Using the same
        // component makes them identical by construction; the state still reads, from the crossed
        // speaker and from the label flipping to 取消禁音.
        val muted = FireworkSfx.muted
        PanelAction(
            icon = if (muted) PanelIcon.SoundOff else PanelIcon.SoundOn,
            label = if (muted) "取消禁音" else "禁音",
            onClick = { FireworkSfx.muted = !muted },
        )

        PanelAction(icon = immersiveIcon, label = immersiveLabel, onClick = onImmersive)
    }
}

/**
 * An icon-only button.
 *
 * [label] no longer renders -- it stays as the icon's `contentDescription`, which is what a screen
 * reader announces and what names the button in a UI dump, so dropping the visible text costs
 * nothing there.
 */
@Composable
private fun PanelAction(icon: PanelIcon, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon.vector(), contentDescription = label)
    }
}
