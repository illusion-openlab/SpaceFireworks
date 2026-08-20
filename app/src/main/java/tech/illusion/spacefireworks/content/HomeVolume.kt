package tech.illusion.spacefireworks.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.geometry.Rotation3D
import com.pico.spatial.ui.foundation.geometry.RotationAxis3D
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import kotlinx.coroutines.launch

// Toolbar tilt: leans the panel back so its face turns up toward a user looking down at it.
// Positive degrees about X tip the top edge away from the viewer.
private const val TOOLBAR_TILT_DEG = 45f

/** The windowed show runs a minute; the immersive one runs three (see [ImmersiveStage]). */
private const val WINDOW_SHOW_SECONDS = 60f

/**
 * `main` (contract 1): the volumetric window. The scene itself is built by [buildFireworkScene] so
 * that this and the immersive Stage stay one scene described twice at different sizes rather than
 * two drifting copies.
 *
 * `mainToolbar` (contract 1) is an `Augment`, which only renders inside a `WindowContainer` -- the
 * Stage HUD uses a plain 2D overlay instead, see [ImmersiveStage].
 */
@Composable
fun HomeVolume() {
    val converter = LocalPhysicalLengthConverter.current
    val density = LocalDensity.current
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    // Measured half-extents of the SpatialView, in meters. Entities are clipped to the *view's*
    // bounding box (its origin is that box's center -- coordinates-and-units.md), NOT to the
    // WindowContainer, so this is the number the show must fit inside.
    var halfW by remember { mutableStateOf(NOMINAL_HALF_W) }
    var halfH by remember { mutableStateOf(NOMINAL_HALF_H) }
    var landmarkTopY by remember { mutableStateOf(0f) }
    var launchOrigin by remember { mutableStateOf<Vector3?>(null) }

    // This window's own show. The Stage runs a second, independent one; see FireworkDirector's
    // KDoc for why they cannot share.
    val context = LocalContext.current
    val director = remember { FireworkDirector("window", WINDOW_SHOW_SECONDS) }
    DisposableEffect(director) {
        director.audio = FireworkAudio.shared(context)
        director.attach()
        onDispose { director.detach() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> FireworkAudio.shared(context).pause()
                Lifecycle.Event.ON_RESUME -> FireworkAudio.shared(context).resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The panel stands ON the scene's ground plane instead of hanging below the container.
    // `AugmentPositionProvider` places content at `contentBottom = viewBottom + offset.y` with
    // offset.y measured screen-DOWN, so raising the panel onto the ground is a negative offset
    // equal to the ground surface's height above the container floor. `launchOrigin.y` IS that
    // surface (it is the grass top the castle stands on), so this tracks a re-measured view and a
    // swapped-in ground model instead of being a hand-tuned number. Before the scene loads there
    // is no ground yet, so the panel rests on the container floor.
    val groundLift =
        launchOrigin?.let { converter.lengthToDp(it.y + halfH, LengthUnit.Meters) } ?: 0.dp

    // Deliberately the raw `Augment` rather than the built-in `Toolbar`, which is normally
    // preferred: `Toolbar` hardcodes `rotation3D = null` and exposes no rotation parameter, so a
    // tilted toolbar cannot be expressed with it. Everything else here mirrors `Toolbar`'s own
    // values (BottomFront anchor, Material.Regular glass, 32dp corners, 64dp min height, 12dp
    // padding, 8dp item gap) so it still reads as the system toolbar -- except Toolbar's 32dp
    // drop, which is exactly what we are replacing.
    Augment(
        anchor = NormalizedPoint3D.BottomFront,
        alignment = AugmentContentAlignment.BottomCenter,
        offset = DpOffset3D(0.dp, -groundLift, 0.dp),
        // Leans the panel back about its bottom edge -- the same edge that now rests on the
        // ground -- so its face turns up toward a user looking down at it, and the contact point
        // stays put while it tilts.
        rotation3D =
            Rotation3D(
                degree = TOOLBAR_TILT_DEG,
                axis = RotationAxis3D.X,
                pivot = NormalizedPoint3D.Bottom,
            ),
        cornerRadius = 32.dp,
        // No glass slab behind the controls (2026-08-11 user request). The buttons keep their own
        // containers, so they stay legible against the scene without the panel plate.
        enableMaterialBackground = false,
    ) {
        ControlPanel(
            onIgnite = { director.ignite() },
            immersiveIcon = PanelIcon.Expand,
            immersiveLabel = "沉浸",
            onImmersive = {
                // StageStyle.Full: the app takes over the display and passthrough is turned off --
                // the "VR" of the request. It is also what makes hand tracking work at all:
                // HandTrackingProvider carries @RequiredFullSpace and stays PENDING forever under
                // a WindowContainer.
                scope.launch { enterImmersive(navigator) }
            },
        )
    }

    // Re-derive the burst bounds whenever the view is measured or re-measured (the container is
    // user-resizable), without restarting the show.
    LaunchedEffect(halfW, halfH, landmarkTopY, launchOrigin) {
        if (launchOrigin == null) return@LaunchedEffect
        val sky = skyAbove(landmarkTopY, SceneBounds(halfW, halfH, VIEW_DEPTH_M / 2f))
        director.updateSky(sky)
        android.util.Log.i(
            "FireworkDiag",
            "sky rebound: view half=%.3fx%.3fm roof=%.3f -> apex %.3f..%.3f r=%.3f"
                .format(halfW, halfH, landmarkTopY, sky.apexMin, sky.apexMax, sky.maxRadius),
        )
    }

    SpatialView(
        modifier =
            Modifier.fillMaxSize()
                .depth(converter.lengthToDp(VIEW_DEPTH_M, LengthUnit.Meters))
                .onGloballyPositioned { coords ->
                    with(density) {
                        halfW =
                            converter.dpToLength(coords.size.width.toDp(), LengthUnit.Meters) / 2f
                        halfH =
                            converter.dpToLength(coords.size.height.toDp(), LengthUnit.Meters) / 2f
                    }
                },
        initial = { content, _ ->
            // SpatialView's `initial` is a suspend lambda launched into a coroutine scope, so a
            // throw here is swallowed rather than crashing -- the symptom is a scene that half
            // builds and a System that never registers. Log it explicitly.
            try {
                android.util.Log.i("FireworkDiag", "window initial: start")
                System.register(FireworkSystem::class.java)

                // An identity root: the window's scene is already centred on the view, so this
                // exists only so the builder can measure in one consistent frame. The Stage's
                // root is the one that actually carries a transform.
                val scene =
                    buildFireworkScene(
                        content,
                        Entity(),
                        SceneBounds(halfW, halfH, VIEW_DEPTH_M / 2f),
                        director,
                    )
                director.ignite()
                // Publish for the LaunchedEffect above, so a later re-measure re-derives bounds.
                landmarkTopY = scene.landmarkTopY
                launchOrigin = scene.launchOrigin
                android.util.Log.i("FireworkDiag", "window initial: ignited")
            } catch (t: Throwable) {
                android.util.Log.e("FireworkDiag", "window initial FAILED", t)
            }
        },
        attachments = {},
    )
}

/**
 * Opens the Stage, then closes this window.
 *
 * Opening a Stage hides *other apps'* windows, not your own -- verified on the emulator, where the
 * volumetric window kept floating inside the immersive scene with its black sky dome cutting a slab
 * out of the ground. Closing (rather than minimizing) is what the user asked for, and it also frees
 * the window's whole scene: its composition disposes, its `FireworkDirector` detaches, and its
 * ~28MB of models are released while the immersive show runs.
 *
 * Order matters: the Stage is opened *first* so the app is never left with no live container.
 */
private suspend fun enterImmersive(
    navigator: com.pico.spatial.ui.platform.containers.SpatialNavigator
) {
    val result = navigator.openStage(IMMERSIVE_STAGE_ID, StageStyle.Full)
    android.util.Log.i("HomeVolume", "openStage -> $result")
    navigator.closeWindowContainer(MAIN_WINDOW_ID)
    android.util.Log.i("HomeVolume", "closed window $MAIN_WINDOW_ID")
}
