package tech.illusion.spacefireworks.content

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Must match `pico.spatial.stage.id` in AndroidManifest.xml. */
const val IMMERSIVE_STAGE_ID = "SpaceFireworksImmersive"

/**
 * How tall the castle should stand in the immersive scene -- a real building, per the request.
 *
 * 40m puts it between Disneyland's Sleeping Beauty Castle (~23m) and Cinderella Castle (~57m):
 * unmistakably architecture rather than a model, without being so tall you cannot take it in.
 */
private const val IMMERSIVE_CASTLE_HEIGHT_M = 40f

/**
 * The castle's height at scale 1, derived rather than measured.
 *
 * The model is fitted with a `min` across all three axes, so which term binds depends on the shape
 * of the box. In the *window* (0.6 x 1.35 x 0.45) the width/depth terms bind and the castle comes
 * out 0.231m; once [IMMERSIVE_FOOTPRINT_SPREAD] widens the floor those terms go slack and the
 * height share takes over, which is a property of the bounds alone and so can be computed exactly.
 *
 * Getting this wrong is not silent: an earlier version hardcoded the window's 0.231 and the castle
 * came out 67.8m against a 40m target, which the runtime check below caught immediately.
 */
private const val NOMINAL_CASTLE_HEIGHT_M = NOMINAL_HALF_H * 2f * BACKDROP_HEIGHT_SHARE

/**
 * Everything the scene builds is a fraction of [SceneBounds], so one factor scales the castle, the
 * ground, the moon, the sky dome and the burst geometry together -- which is exactly "按比例放大".
 */
private const val IMMERSIVE_SCALE = IMMERSIVE_CASTLE_HEIGHT_M / NOMINAL_CASTLE_HEIGHT_M

/** Tolerated drift between [IMMERSIVE_CASTLE_HEIGHT_M] and what the scene actually built. */
private const val CASTLE_HEIGHT_TOLERANCE = 0.1f

/**
 * How much wider and deeper the immersive footprint is than a straight scale-up.
 *
 * The window is a tall, shallow box (0.6 x 1.35 x 0.45), and scaling that shape up gives a castle
 * standing on a 102 x 76m postage stamp -- you cannot back up far enough to see the top of a 40m
 * building without walking off the edge of the world. Widening only the footprint is safe: the
 * castle is fitted with a `min` across all three axes and the *height* term is the one that wins,
 * so more floor does not make the castle bigger. It also widens where shells may drift and how big
 * a burst may be, which is what real fireworks over real grounds should look like.
 */
private const val IMMERSIVE_FOOTPRINT_SPREAD = 3f

/**
 * Viewing distance as a multiple of the castle's height.
 *
 * 1.7x puts the whole building comfortably inside the field of view with sky above it, which is
 * roughly where you would stand to photograph one.
 */
private const val VIEW_DISTANCE_FACTOR = 1.7f

/** Margin kept between the user and the back... front edge of the ground, in meters. */
private const val GROUND_EDGE_MARGIN_M = 10f

/** Must match `GROUND_DEPTH_SHARE` in Scene.kt -- how much of the bounds' depth the ground covers. */
private const val GROUND_DEPTH_SHARE_MIRROR = 0.98f

/** Three minutes, per the request. The window's show stays at one. */
private const val IMMERSIVE_SHOW_SECONDS = 180f

// --- hand tracking, ported from SpaceCube's V1 controller (see that project's AGENTS.md) ---

/**
 * Pinch thresholds, with hysteresis so a hand hovering at the boundary does not chatter.
 *
 * These are SpaceCube's twice-tightened values, arrived at from direct user feedback there rather
 * than from a spec: 0.025/0.04 read as pinched with a visible gap between the fingertips, and
 * 0.015/0.025 still felt loose.
 */
private const val PINCH_START_DISTANCE_M = 0.010f
private const val PINCH_RELEASE_DISTANCE_M = 0.018f

/** A flip must survive this many consecutive polls, filtering single-frame tracking noise. */
private const val PINCH_CONFIRM_TICKS = 2

private const val HAND_POLL_INTERVAL_MS = 20L

/**
 * How long the second pinch of a double-pinch may lag the first.
 *
 * Generous because the window has to span three debounce-gated transitions (pinch, release, pinch),
 * each costing [PINCH_CONFIRM_TICKS] polls before it is even recognised -- ~120ms of the budget is
 * software latency before any finger has moved.
 */
private const val DOUBLE_PINCH_WINDOW_MS = 600L

private const val PINCH_INDICATOR_RADIUS_M = 0.008f
private val PINCH_INDICATOR_IDLE_COLOR = Color4(0.75f, 0.75f, 0.75f, 1f)
private val PINCH_INDICATOR_ACTIVE_COLOR = Color4(0.34f, 0.88f, 0.63f, 1f)

private const val HAND_LOG_TAG = "FireworkHand"
private const val HAND_TRACKING_PERMISSION = "com.picovr.permission.HAND_TRACKING"
private const val HAND_TRACKING_PERMISSION_REQUEST_CODE = 1001

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * `stage` (contract 1): the immersive show, opened from the window's 「沉浸」 button.
 *
 * The same scene as the window, built from [buildFireworkScene] at [IMMERSIVE_SCALE] and anchored
 * in front of the user instead of inside a box. Two ways out, on purpose: a left-hand double-pinch
 * (the requested gesture) and the HUD's 「退出沉浸」 button, because hand tracking depends on device
 * firmware and there must be an exit that does not.
 */
@Composable
fun ImmersiveStage() {
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val director = remember { FireworkDirector("stage", IMMERSIVE_SHOW_SECONDS) }
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

    // Parent of the four fingertip spheres. A bare identity entity added straight to the view's
    // content -- deliberately NOT the scene root, whose transform would drag the hands along with
    // the castle. It doubles as the reference frame every raw joint position is converted into.
    val handRoot = remember { Entity() }
    val indicators = remember { List(4) { createPinchIndicator() } }

    HandGestureController(
        handRoot = handRoot,
        indicators = indicators,
        onRightDoublePinch = {
            android.util.Log.i(HAND_LOG_TAG, "right double-pinch -> ignite")
            director.ignite()
        },
        onLeftDoublePinch = {
            android.util.Log.i(HAND_LOG_TAG, "left double-pinch -> back to window")
            scope.launch { leaveImmersive(navigator) }
        },
    )

    // `stageHud` (contract 1): a plain 2D overlay inside the Stage. Augment does not work here --
    // it only renders inside a WindowContainer.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ControlPanel(
            onIgnite = { director.ignite() },
            immersiveIcon = PanelIcon.Collapse,
            immersiveLabel = "退出沉浸",
            onImmersive = { scope.launch { leaveImmersive(navigator) } },
        )
    }

    SpatialView(
        modifier = Modifier.fillMaxSize(),
        initial = { content, _ ->
            // Same swallow-the-throw hazard as the window's initial; log explicitly.
            try {
                android.util.Log.i("FireworkDiag", "stage initial: start")
                System.register(FireworkSystem::class.java)

                content.addEntity(handRoot)
                indicators.forEach { handRoot.addChild(it.entity) }

                val scaled =
                    SceneBounds(NOMINAL_HALF_W, NOMINAL_HALF_H, VIEW_DEPTH_M / 2f)
                        .scaled(IMMERSIVE_SCALE)
                val bounds =
                    scaled.copy(
                        halfW = scaled.halfW * IMMERSIVE_FOOTPRINT_SPREAD,
                        halfD = scaled.halfD * IMMERSIVE_FOOTPRINT_SPREAD,
                    )
                // A Stage's content origin is on the floor beneath the user with -Z forward, so
                // lifting the root by the scene's own half-height drops its ground to underfoot.
                // The distance puts the user inside the ground's front edge rather than in front
                // of it -- see GROUND_FOOTHOLD_M.
                val groundHalfDepth = bounds.halfD * GROUND_DEPTH_SHARE_MIRROR
                // Stand back far enough to take the castle in, but never past the ground's edge --
                // floating over a void is worse than a slightly tight framing.
                val distance =
                    (IMMERSIVE_CASTLE_HEIGHT_M * VIEW_DISTANCE_FACTOR)
                        .coerceAtMost(groundHalfDepth - GROUND_EDGE_MARGIN_M)
                        .coerceAtLeast(1f)
                val root = Entity()
                root.components[TransformComponent::class.java]?.apply {
                    position = Vector3(0f, bounds.halfH, -distance)
                }

                val scene = buildFireworkScene(content, root, bounds, director)
                director.ignite()
                android.util.Log.i(
                    "FireworkDiag",
                    ("stage initial: ignited at %.0fx -- castle %.1fm (target %.0fm), " +
                        "ground %.0fx%.0fm, %.0fm ahead, dome apex %.0fm")
                        .format(
                            IMMERSIVE_SCALE,
                            scene.landmarkHeight,
                            IMMERSIVE_CASTLE_HEIGHT_M,
                            bounds.halfW * 2f * GROUND_DEPTH_SHARE_MIRROR,
                            groundHalfDepth * 2f,
                            distance,
                            bounds.halfH,
                        ),
                )
                val drift =
                    kotlin.math.abs(scene.landmarkHeight - IMMERSIVE_CASTLE_HEIGHT_M) /
                        IMMERSIVE_CASTLE_HEIGHT_M
                if (drift > CASTLE_HEIGHT_TOLERANCE) {
                    android.util.Log.w(
                        "FireworkDiag",
                        ("castle came out %.1fm against a %.0fm target -- NOMINAL_CASTLE_HEIGHT_M " +
                            "(%.3f) is stale for this model; re-measure it from the window's " +
                            "`castle: h=` log line")
                            .format(
                                scene.landmarkHeight,
                                IMMERSIVE_CASTLE_HEIGHT_M,
                                NOMINAL_CASTLE_HEIGHT_M,
                            ),
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.e("FireworkDiag", "stage initial FAILED", t)
            }
        },
        attachments = {},
    )
}

/** The raw thumb-tip and index-tip positions a pinch check is based on. */
private class FingerTips(val thumbTip: Vector3, val indexTip: Vector3)

/**
 * Distance-based pinch detection with hysteresis plus a short debounce.
 *
 * Ported from SpaceCube, where the thresholds were tuned on device. Everything about the timing
 * here matters to how the double-pinch feels, so change the two together.
 */
private class PinchTracker(private val label: String) {

    var isPinching: Boolean = false
        private set

    /** True only on the exact update where the debounce confirms a false -> true flip. */
    var justStartedPinching: Boolean = false
        private set

    private var pendingState: Boolean? = null
    private var pendingCount = 0

    /** Returns the fingertips whenever the hand is tracked, pinching or not. */
    fun update(pose: HandPose?): FingerTips? {
        justStartedPinching = false
        if (pose == null) {
            isPinching = false
            pendingState = null
            pendingCount = 0
            return null
        }
        val thumbTip = pose.joint(HandJoint.Index.THUMB_TIP).position
        val indexTip = pose.joint(HandJoint.Index.INDEX_TIP).position
        val distance = Vector3.distance(thumbTip, indexTip)
        val instant =
            if (isPinching) distance <= PINCH_RELEASE_DISTANCE_M
            else distance <= PINCH_START_DISTANCE_M
        when {
            instant == isPinching -> {
                pendingState = null
                pendingCount = 0
            }
            pendingState == instant -> {
                pendingCount++
                if (pendingCount >= PINCH_CONFIRM_TICKS) {
                    val was = isPinching
                    isPinching = instant
                    pendingState = null
                    pendingCount = 0
                    justStartedPinching = !was && isPinching
                    android.util.Log.i(
                        HAND_LOG_TAG,
                        "$label pinch=$isPinching at %.4fm".format(distance),
                    )
                }
            }
            else -> {
                pendingState = instant
                pendingCount = 1
            }
        }
        return FingerTips(thumbTip, indexTip)
    }
}

/**
 * One sphere riding one fingertip; colour signals the pinch state.
 *
 * A raw hand-joint position is NOT already valid as an entity's local position -- it has to go
 * through `referenceEntity.convertPositionFrom(raw, null)` first, per `convertPositionFrom`'s own
 * KDoc and the SDK's controller-model sample.
 */
private class PinchIndicator(val entity: ModelEntity, private val material: UnlitMaterial) {

    fun update(reference: Entity, point: Vector3?, isPinching: Boolean) {
        if (point == null) {
            entity.enabled = false
            return
        }
        entity.enabled = true
        entity.components[TransformComponent::class.java]?.position =
            reference.convertPositionFrom(point, null)
        material.setBaseColor(
            if (isPinching) PINCH_INDICATOR_ACTIVE_COLOR else PINCH_INDICATOR_IDLE_COLOR
        )
    }
}

private fun createPinchIndicator(): PinchIndicator {
    val material = UnlitMaterial.create().apply { setBaseColor(PINCH_INDICATOR_IDLE_COLOR) }
    val entity =
        ModelEntity(MeshResource.createSphere(PINCH_INDICATOR_RADIUS_M), material).apply {
            enabled = false
        }
    return PinchIndicator(entity, material)
}

/**
 * No visual output of its own: a background poller that drives the fingertip spheres and the two
 * double-pinch gestures.
 *
 * [HandTrackingProvider] carries `@RequiredFullSpace`, which is why this lives in the Stage and not
 * the window -- under a `WindowContainer` it would stay `PENDING` forever and silently do nothing.
 * On device, hand tracking is additionally gated by the headset's own **system firmware** version,
 * separately from the app's SDK version; if the spheres never appear, check that before re-reading
 * this code (SpaceCube lost a debugging round to exactly this).
 *
 * @param indicators four spheres, ordered left-thumb, left-index, right-thumb, right-index -- one
 *   per fingertip rather than one per hand at the midpoint, so they visibly converge on a pinch.
 */
@Composable
private fun HandGestureController(
    handRoot: Entity,
    indicators: List<PinchIndicator>,
    onRightDoublePinch: () -> Unit,
    onLeftDoublePinch: () -> Unit,
) {
    val context = LocalContext.current
    val provider = remember { HandTrackingProvider() }
    val rightAction = rememberUpdatedState(onRightDoublePinch)
    val leftAction = rememberUpdatedState(onLeftDoublePinch)
    var hasHandTrackingPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, HAND_TRACKING_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(Unit) {
        if (!hasHandTrackingPermission) {
            context.findActivity()?.let { activity ->
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(HAND_TRACKING_PERMISSION),
                    HAND_TRACKING_PERMISSION_REQUEST_CODE,
                )
            }
        }
    }

    DisposableEffect(provider, hasHandTrackingPermission) {
        var started = false
        if (hasHandTrackingPermission) {
            val result = provider.start()
            started = true
            android.util.Log.i(
                HAND_LOG_TAG,
                "start() -> $result, supportState=${provider.supportState}, state=${provider.state}",
            )
        }
        onDispose {
            if (started) {
                provider.stop()
            }
        }
    }

    LaunchedEffect(provider, hasHandTrackingPermission) {
        if (!hasHandTrackingPermission) {
            while (isActive && !hasHandTrackingPermission) {
                delay(250L)
                if (
                    ContextCompat.checkSelfPermission(context, HAND_TRACKING_PERMISSION) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    hasHandTrackingPermission = true
                }
            }
            return@LaunchedEffect
        }

        // Support/state take a moment to negotiate; log again so logcat shows whether this ever
        // leaves PENDING / WITHOUT_PERMISSION / DEVICE_NOT_SUPPORTED.
        delay(2000)
        android.util.Log.i(
            HAND_LOG_TAG,
            "2s in: supportState=${provider.supportState}, state=${provider.state}",
        )

        val left = PinchTracker("left")
        val right = PinchTracker("right")
        // Separate timestamps per hand on purpose: a left pinch followed by a right one is two
        // gestures, not a double-pinch.
        var lastLeftPinchMs = 0L
        var lastRightPinchMs = 0L
        var loggedFirstData = false

        while (isActive) {
            val data = provider.latestData
            if (!loggedFirstData) {
                loggedFirstData = true
                android.util.Log.i(
                    HAND_LOG_TAG,
                    "first latestData: left=${data.left != null}, right=${data.right != null}",
                )
            }

            val leftTips = left.update(data.left)
            val rightTips = right.update(data.right)
            indicators[0].update(handRoot, leftTips?.thumbTip, left.isPinching)
            indicators[1].update(handRoot, leftTips?.indexTip, left.isPinching)
            indicators[2].update(handRoot, rightTips?.thumbTip, right.isPinching)
            indicators[3].update(handRoot, rightTips?.indexTip, right.isPinching)

            val now = android.os.SystemClock.elapsedRealtime()
            if (right.justStartedPinching) {
                if (now - lastRightPinchMs <= DOUBLE_PINCH_WINDOW_MS) {
                    lastRightPinchMs = 0L
                    rightAction.value()
                } else {
                    lastRightPinchMs = now
                }
            }
            if (left.justStartedPinching) {
                if (now - lastLeftPinchMs <= DOUBLE_PINCH_WINDOW_MS) {
                    lastLeftPinchMs = 0L
                    leftAction.value()
                } else {
                    lastLeftPinchMs = now
                }
            }

            delay(HAND_POLL_INTERVAL_MS)
        }
    }
}

/**
 * Re-opens the volumetric window, then closes the Stage.
 *
 * The window was *closed* on the way in, not minimized, so coming back means opening a fresh one --
 * it rebuilds its scene from scratch, which is why the castle takes a moment to reappear. Opening
 * before closing keeps the app from momentarily owning no container at all.
 */
private suspend fun leaveImmersive(
    navigator: com.pico.spatial.ui.platform.containers.SpatialNavigator
) {
    navigator.openWindowContainer(MAIN_WINDOW_ID)
    android.util.Log.i(HAND_LOG_TAG, "re-opened window $MAIN_WINDOW_ID")
    navigator.closeStage()
}
