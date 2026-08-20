package tech.illusion.spacefireworks.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The control panel's icons, drawn here rather than pulled from an icon pack.
 *
 * `androidx.compose.material.icons` is the obvious source and is exactly what this project forbids
 * (SpatialUI-only rule), and the SDK's own SDF icon set is addressed by name through a manifest
 * array rather than as an `ImageVector`. Four glyphs on a 24x24 grid is little enough to just draw.
 *
 * `Icon` tints whatever it is given with the current content colour, so the white below is a
 * placeholder the theme overwrites -- it is never the colour actually drawn.
 */
enum class PanelIcon {
    /** 点火 -- a flame. */
    Flame,
    /** 禁音 (off) -- speaker with sound waves. */
    SoundOn,
    /** 禁音 (on) -- speaker with a cross. */
    SoundOff,
    /** 沉浸 -- arrows pushing out to the corners. */
    Expand,
    /** 退出沉浸 -- arrows pulling back to the centre. */
    Collapse;

    @Composable
    fun vector(): ImageVector = remember(this) { build() }

    private fun build(): ImageVector =
        when (this) {
            Flame -> icon("Flame") { flame() }
            SoundOn -> icon("SoundOn") { speaker(); waves() }
            SoundOff -> icon("SoundOff") { speaker(); cross() }
            Expand -> icon("Expand") { arrows(outward = true) }
            Collapse -> icon("Collapse") { arrows(outward = false) }
        }
}

/**
 * A broad teardrop with a notch cut out of one shoulder.
 *
 * Deliberately fat: the first attempt was an anatomically nicer flame spanning only 9 of the 24
 * units, and at the size an icon actually renders it came out as a white sliver (verified on
 * device). Glyphs at 24dp need to fill the box.
 */
private fun ImageVector.Builder.flame() {
    path(fill = SolidColor(Color.White)) {
        moveTo(12f, 2.2f)
        curveTo(15.2f, 6.0f, 18.6f, 8.6f, 18.6f, 13.2f)
        curveTo(18.6f, 17.9f, 15.6f, 21.8f, 12f, 21.8f)
        curveTo(8.4f, 21.8f, 5.4f, 17.9f, 5.4f, 13.2f)
        curveTo(5.4f, 10.4f, 7.0f, 8.6f, 8.6f, 7.0f)
        curveTo(9.6f, 9.4f, 10.4f, 7.0f, 12f, 2.2f)
        close()
    }
}

private fun ImageVector.Builder.speaker() {
    path(fill = SolidColor(Color.White)) {
        moveTo(3f, 9.2f)
        lineTo(6.6f, 9.2f)
        lineTo(11.4f, 4.6f)
        lineTo(11.4f, 19.4f)
        lineTo(6.6f, 14.8f)
        lineTo(3f, 14.8f)
        close()
    }
}

private fun ImageVector.Builder.waves() {
    stroke {
        moveTo(15.5f, 8.5f)
        quadTo(17.6f, 12f, 15.5f, 15.5f)
    }
    stroke {
        moveTo(18.4f, 6f)
        quadTo(21.8f, 12f, 18.4f, 18f)
    }
}

private fun ImageVector.Builder.cross() {
    stroke {
        moveTo(16f, 9f)
        lineTo(21.5f, 15f)
    }
    stroke {
        moveTo(21.5f, 9f)
        lineTo(16f, 15f)
    }
}

/**
 * Four corner brackets: wide apart for "go immersive", pulled in for "come back".
 *
 * Brackets only -- an earlier version added a diagonal arrow into each corner, which at 24dp just
 * filled the corners with mush. Two strokes per corner is the most this size carries.
 */
private fun ImageVector.Builder.arrows(outward: Boolean) {
    val corner = if (outward) 3.6f else 8.6f
    val arm = if (outward) 5.2f else 4.6f
    for (sx in listOf(1f, -1f)) {
        for (sy in listOf(1f, -1f)) {
            fun x(v: Float) = 12f + sx * (v - 12f)
            fun y(v: Float) = 12f + sy * (v - 12f)
            stroke {
                moveTo(x(corner + arm), y(corner))
                lineTo(x(corner), y(corner))
                lineTo(x(corner), y(corner + arm))
            }
        }
    }
}

private fun ImageVector.Builder.stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        pathBuilder = block,
    )
}

private fun icon(name: String, content: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply(content)
        .build()
