package tech.illusion.spacefireworks.content

import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshInstancesResource
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.ResourceLoadingException
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The volumetric window's nominal half-extents, mirroring `pico.spatial.windowcontainer.defaultsize`
 * (0.6 x 1.35 x 0.45 m).
 *
 * Two uses. In the window these are only a first-frame fallback -- the container is user-resizable
 * (`resizerestriction=1`), so the real clip volume is whatever the view measures (see
 * `HomeVolume`'s onGloballyPositioned). In the Stage they are the *reference* the immersive scale
 * multiplies, deliberately the nominal size rather than the measured one so that dragging the
 * window bigger does not also change how big the immersive show is.
 */
const val NOMINAL_HALF_W = 0.30f
const val NOMINAL_HALF_H = 0.675f

/**
 * Depth the windowed SpatialView asks for. Entities are clipped to the *view's* box, not to the
 * container, so the view has to be told to span the container's depth. Unlike width and height this
 * one cannot be measured back: `onGloballyPositioned` reports 2D layout coords only.
 */
const val VIEW_DEPTH_M = 0.45f

/** Must match `pico.spatial.windowcontainer.id` in AndroidManifest.xml. */
const val MAIN_WINDOW_ID = "SpaceFireworksVolume"

/**
 * The box a scene is built inside, in meters, measured from its own centre.
 *
 * The whole scene is expressed as fractions of this, which is what lets the same builder produce
 * the desk-sized show inside the volumetric window and the room-sized one inside the immersive
 * Stage from one set of constants. In the window these are the *view's* measured half-extents (the
 * real clip volume -- see `HomeVolume`); in the Stage they are simply the window's scaled up.
 */
data class SceneBounds(
    val halfW: Float,
    val halfH: Float,
    val halfD: Float,
    /**
     * How far this has been blown up from the window's nominal size.
     *
     * Almost everything in the scene is a *share* of the half-extents and so scales for free. The
     * burst band is the exception: [BURST_LIFT_M] and [BURST_BAND_M] are absolute on purpose, so
     * that dragging the window bigger adds empty sky instead of launching the fireworks higher.
     * That is right for a resize and wrong for the immersive scene, which is meant to be the same
     * show at a different size -- so the band is multiplied by this, which only [scaled] ever
     * changes.
     */
    val scale: Float = 1f,
) {

    /** The same box, [factor] times bigger on every axis. */
    fun scaled(factor: Float) =
        SceneBounds(halfW * factor, halfH * factor, halfD * factor, scale * factor)
}

/**
 * Builds one complete show -- ground, castle, sky dome, moon, firework channels -- into [content],
 * and binds [director] to it.
 *
 * Callers differ only in the [bounds] they pass and in what they parent [content] to. Returns the
 * scene's anchors so the caller can re-derive burst bounds if its view is later re-measured.
 */
suspend fun buildFireworkScene(
    content: SpatialViewContent,
    root: Entity,
    bounds: SceneBounds,
    director: FireworkDirector,
): SceneAnchors {
    content.addEntity(root)
    // Build the firework rig BEFORE loading the backdrop: this runs as one sequential coroutine,
    // and the castle is a ~13MB asset, so loading it first would hold the whole show behind it
    // (observed: several seconds of nothing).
    // Where the channel entities are parked. Bounds-relative, NOT a constant: a ModelComponent
    // keeps drawing its source mesh at the entity's own transform even while `meshInstances` drives
    // the real stars, so the entities have to sit outside the scene. A fixed -5m does that in a
    // 1.35m window and lands squarely *inside* a scene scaled to a real castle.
    val park = Vector3(0f, -bounds.halfH * 3f, 0f)
    val channels = createPaletteChannels(root, park)
    android.util.Log.i("FireworkDiag", "scene: channels=${channels.size}")

    val scene = loadSceneBackdrop(root, bounds)
    // The dome is centred on the castle's base, so the scene has to be measured first; the moon is
    // then fitted against the dome rather than the other way round.
    val sky = skyDomeOver(bounds, scene.launchOrigin.y)
    loadMoon(root, bounds, sky)
    addSkyDome(root, sky)

    // No visible marker at the launch point: the contract defines `fireworkLaunchAnchor` as a
    // non-visible anchor.
    director.washSurfaces = scene.washSurfaces
    director.bind(
        channels,
        scene.launchOrigin,
        park,
        scene.landmarkHeight,
        skyAbove(scene.landmarkTopY, bounds),
    )
    return scene
}

// Vertical span the bursts are spread over, measured up from just above the backdrop. This is a
// FIXED distance on purpose (it is the band the show had in the 0.9m container) so that resizing
// the container changes how much empty sky there is, not how high the fireworks fly.
private const val BURST_BAND_M = 0.35f

// How high above the backdrop's top the LOWEST burst centre sits. This is the single knob for
// "raise/lower the fireworks" -- the whole band moves with it, and it is independent of both the
// container size and the burst radius. Was an implicit `radius * 0.85` (~0.128m); lowered on
// 2026-08-11 per user request.
private const val BURST_LIFT_M = 0.07f

// What share of the measured view the castle may occupy. Deliberately a small share of the
// height: this is a fireworks app, and every centimeter the castle takes is sky the bursts can't
// use (burst radius is derived from the leftover headroom -- see `skyAbove`).
private const val BACKDROP_WIDTH_SHARE = 0.6f
const val BACKDROP_HEIGHT_SHARE = 0.29f
private const val BACKDROP_DEPTH_SHARE = 0.58f

// The ground plane fills the footprint. Height is capped as well, so swapping in a model that
// carries hills/terrain can't quietly eat the sky the show needs.
private const val GROUND_WIDTH_SHARE = 0.98f
private const val GROUND_DEPTH_SHARE = 0.98f
private const val GROUND_MAX_HEIGHT_SHARE = 0.10f

// Lifts the whole scene (ground + whatever stands on it) off the container floor. The castle
// rides on the ground and the burst band is anchored to the castle's roofline, so raising this
// raises all three together and keeps their spacing intact.
private const val SCENE_LIFT_M = 0.001f

// Moon placement, as fractions of the measured view so it survives a window resize. It hangs in
// the sky above the castle, pushed off-centre and toward the back wall so it reads as a distant
// object behind the fireworks rather than a prop floating among them. The horizontal shares are a
// *starting* point: the moon is pulled toward the dome's axis until the sky closes over it -- see
// `loadMoon`.
private const val MOON_DIAMETER_SHARE = 0.18f // of view width
private const val MOON_X_SHARE = 0.52f // of halfW, off to one side
private const val MOON_Y_SHARE = 0.30f // of halfH
private const val MOON_Z_SHARE = -0.55f // of halfD, negative = away from the viewer
// Absolute, not a share, because that is how it was asked for (2026-08-11: "月亮高度下降0.2m").
// Applied after the share so the starting height still tracks a resized window.
//
// Deliberately NOT multiplied by `SceneBounds.scale`, unlike the burst band. It is a framing tweak
// for the desk-sized window, and scaling it swallowed the immersive moon's whole share (20.7m of
// share minus a 20.4m drop left it sitting at the scene's waist) -- which is what "沉浸模式下月亮
// 高度应该会更远一点" was about.
private const val MOON_DROP_M = 0.20f

// Night sky: a black dome built in code rather than a model. Set to false for a see-through window.
private const val SKY_DOME_ENABLED = true
// Keeps the dome's walls off the container's, so its sides are not clipped.
private const val SKY_DOME_INSET = 0.99f
// Slack between the moon's surface and the dome, so it is enclosed rather than tangent.
private const val SKY_DOME_MOON_CLEARANCE_M = 0.02f
// How far in the moon may be pulled to fit under the dome, and in what steps.
private const val MOON_PULL_STEP = 0.02f
private const val MOON_PULL_MIN = 0.05f


// Black, and it stays black: the dome is OPAQUE blending, and sampling the emulator confirmed it
// -- pixels inside the dome sit at ~25/255 no matter whether the room behind them is a bright
// pillow (172), the painting (116) or the blanket (129). That ~25 floor is passthrough veil added
// by the compositor, not the material leaking; raising this colour does not remove it.
/**
 * The warm cast the moon throws over everything solid in the scene.
 *
 * There are no real lights here -- dynamic point lights were measured to have no effect on this
 * render path (see `FireworkDirector.washSurfaces`) -- so "moonlight" is a tint multiplied into
 * every scene material's base colour. Above 1 on red and green and below on blue: a lift as well as
 * a warm shift, because the untinted scene read as cold and lifeless rather than merely neutral.
 *
 * `setBaseColor` multiplies the material's own texture, so this keeps the marble and the castle's
 * stonework intact and only recolours the light falling on them.
 *
 * Dialled back on 2026-08-11 ("暖色月光颜色太强烈 降低一点") from (1.28, 1.12, 0.86), which turned
 * the ground amber. The blue channel is the knob that matters: at 0.86 it was pulling colour out of
 * the surface, not just adding warmth. The brightness lift is kept -- the original complaint was
 * that the scene read cold and lifeless, and that was as much about level as about hue.
 */
private val MOONLIGHT_TINT = Color4(1.12f, 1.05f, 0.95f, 1f)

/**
 * The same moonlight, held back for the castle.
 *
 * A warm multiplier lands very differently on the two surfaces: the ground is dark marble where a
 * blue channel at 0.95 barely registers, while the castle is near-white stone where the same tint
 * reads straight off as yellow ("建筑颜色有点偏黄"). So the landmark gets its own value rather than
 * dragging the ground back to neutral with it.
 *
 * The blue channel is well *above* 1 on purpose. The model's own stone texture is cream to begin
 * with -- measured at rest, the untinted patch carries R-B = +6.2 -- and a hue-neutral multiplier
 * preserves that rather than removing it. These values are solved to put R and B back on top of
 * each other, which lands the stone near neutral with a hint of warmth left.
 *
 * Measure at *rest*, not mid-show: a burst's wash brightens and tints these surfaces for 0.7s, and
 * sampling during one is what made the first two attempts here look like they had barely moved.
 */
private val LANDMARK_TINT = Color4(1.02f, 1.05f, 1.09f, 1f)

/**
 * The moon's own colour: warmer and brighter than what it casts, since it is the source.
 *
 * A real full moon photographs near-white, but it *reads* as warm to the eye against a night sky,
 * and the scene it lights has to look like it came from somewhere.
 */
private val MOON_TINT = Color4(1.40f, 1.24f, 0.95f, 1f)

/** Fallback if the gradient texture cannot be loaded: the flat black the dome used to be. */
private val SKY_COLOR = Color4(0f, 0f, 0f, 1f)

/**
 * The dome's surface: a vertical gradient rather than a flat fill.
 *
 * `assets/sky_gradient.png` is an 8x512 RGBA strip -- deep blue-black and near-opaque at one pole,
 * fading to fully clear at the other, so the sky reads as night overhead easing out toward the
 * horizon instead of a hard black slab pasted over the room. A sphere's V runs pole to pole, which
 * is what makes a 1-D texture enough.
 *
 * [BlendingMode.TRANSPARENT] is required for the alpha to mean anything; [MaterialCullingMode.FRONT]
 * is what keeps this a sky rather than a ball, and it also means the gradient is only ever blended
 * once (the near half is culled before it can double up).
 */
private fun skyMaterial(): UnlitMaterial =
    try {
        UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColorTexture(TextureResource.load("sky_gradient.png"))
            setCullingMode(MaterialCullingMode.FRONT)
        }
    } catch (e: ResourceLoadingException) {
        android.util.Log.e("FireworkDiag", "sky gradient failed to load; falling back to flat", e)
        UnlitMaterial.create().apply {
            setBaseColor(SKY_COLOR)
            setCullingMode(MaterialCullingMode.FRONT)
        }
    }


/**
 * Carves the leftover headroom between the top of the backdrop and the container ceiling into a
 * budget the show can use. A burst that detonates outside the volumetric window is clipped away
 * rather than scaled to fit, so this is what keeps the show visible.
 */
fun skyAbove(backdropTop: Float, bounds: SceneBounds): FireworkDirector.SkyBox {
    val (halfW, halfH, halfD) = bounds
    // 0.90 not 0.94: the star meshes have their own radius on top of the burst radius, so the
    // burst sphere must stop short of the actual ceiling.
    val ceiling = halfH * 0.90f
    val headroom = (ceiling - backdropTop).coerceAtLeast(0.02f)

    // A burst needs `radius` of clearance in every direction from its center.
    val radius = minOf(headroom * 0.5f, halfD * 0.8f, halfW * 0.5f)

    // The show sits in a FIXED band just above the backdrop. It deliberately does NOT stretch to
    // fill the ceiling: growing the container is a request for headroom, not a request to fly the
    // fireworks higher. Extra height therefore becomes empty sky above the show, and the ceiling
    // only ever acts as a clamp.
    val apexMin = (backdropTop + BURST_LIFT_M * bounds.scale).coerceAtMost(ceiling - radius)
    val apexMax =
        (apexMin + BURST_BAND_M * bounds.scale).coerceIn(apexMin, ceiling - radius)

    return FireworkDirector.SkyBox(
        apexMin = apexMin,
        apexMax = apexMax,
        maxRadius = radius,
        spreadX = (halfW * 0.9f - radius).coerceAtLeast(0f),
        spreadZ = (halfD * 0.6f - radius).coerceAtLeast(0f),
    )
}

/**
 * One entity per [Palette] channel. A mesh-instances resource carries a single material, so the
 * number of colors on screen is bounded by how many of these exist -- not by spark count. Each
 * one is still a single draw call no matter how many sparks it holds, and all of them blend
 * additively so overlapping stars sum into a hot core.
 */
private fun createPaletteChannels(root: Entity, park: Vector3): List<MeshInstancesResource> {
    val colors = List(Palette.COUNT) { Palette.colorOf(it) }

    val star = MeshResource.createSphere(0.005f)
    return colors.mapIndexed { index, color ->
        val instances = MeshInstancesResource.create("fireworkChannel$index")
        val entity = Entity()
        entity.components.set(
            ModelComponent(
                star,
                UnlitMaterial.create(BlendingMode.ADD).apply { setBaseColor(color) },
            ).apply { meshInstances = instances }
        )
        // Parked out of sight: the component draws its source mesh at this transform regardless
        // of the instances, which otherwise appears as a permanent dot at the window centre.
        entity.components[TransformComponent::class.java]?.apply {
            position = park
        }
        root.addChild(entity)
        instances
    }
}

/**
 * One material the scene keeps recolouring: its resting tint plus whatever a burst washes over it.
 *
 * Wrapping rather than replacing matters -- these are the materials the models shipped with, and
 * `setBaseColor` multiplies their textures. Replacing them would flatten the castle's stonework,
 * roofs and gilt into one colour.
 */
class TintedSurface(private val material: Material, private val rest: Color4) {

    /** Applies the resting tint with [washColor] added on top at strength [washAmount]. */
    fun apply(washColor: Color4, washAmount: Float) {
        val color =
            Color4(
                rest.red + washColor.red * washAmount,
                rest.green + washColor.green * washAmount,
                rest.blue + washColor.blue * washAmount,
                1f,
            )
        when (material) {
            is UnlitMaterial -> material.setBaseColor(color)
            is PhysicallyBasedMaterial -> material.setBaseColor(color)
            // Anything else (ShaderGraph, video, portal) has no base colour to tint; skip it
            // rather than guess.
            else -> Unit
        }
    }
}

/**
 * Collects every tintable material under [entity] and puts it at its resting [rest] tint.
 *
 * `ModelComponent.materials[i]` hands back the concrete subclass (the SDK's own adapter conversion
 * does the `is` dispatch), which is what makes tinting a loaded model possible at all without
 * unpacking its textures.
 */
private fun tintTree(entity: Entity, rest: Color4, label: String): List<TintedSurface> {
    val surfaces = mutableListOf<TintedSurface>()
    fun walk(e: Entity) {
        e.components[ModelComponent::class.java]?.let { model ->
            for (i in 0 until model.materials.size) {
                val surface = TintedSurface(model.materials[i], rest)
                surface.apply(BLACK, 0f)
                surfaces += surface
            }
        }
        e.getChildren().forEach { walk(it) }
    }
    walk(entity)
    android.util.Log.i("FireworkDiag", "$label: tinted ${surfaces.size} material(s)")
    return surfaces
}

private val BLACK = Color4(0f, 0f, 0f, 1f)

/**
 * Re-materials the ground plane with the marble texture, in code.
 *
 * `Ground.usdz` was authored in Spatial Editor and its marble is a ShaderGraph material, which
 * the runtime refuses when the asset is loaded as a raw `.usdz`:
 *
 * ```
 * W SPC-…-Material: ShaderGraph[/Root/Plane/Marble_13/material/M_Marble_13] is unsupported.
 * E SPC-…-UsdGeomGprimReader: Failed to get Material,Plane!
 * ```
 *
 * ShaderGraph belongs to the Editor's AssetBundle pipeline (materials-lighting-effects.md), so
 * the geometry loads but the surface falls back to a checkerboard placeholder. Rather than
 * require a `.bundle` re-export, the colour map is unpacked from the asset into
 * `assets/ground_color.jpg` and applied here as a plain [UnlitMaterial]. Unlit (not PBR) because
 * this scene has no lights or IBL set up, so a PBR surface would render black.
 */
private fun applyGroundMaterial(ground: Entity) {
    val marble =
        try {
            UnlitMaterial.create().apply {
                setBaseColorTexture(TextureResource.load("ground_color.jpg"))
                // The plane is single-sided; without this it disappears when viewed from the
                // other side of the window.
                setCullingMode(MaterialCullingMode.NONE)
            }
        } catch (e: ResourceLoadingException) {
            android.util.Log.e("FireworkDiag", "ground texture failed to load", e)
            return
        }

    var applied = 0
    fun walk(entity: Entity) {
        entity.components[ModelComponent::class.java]?.let { model ->
            if (model.materials.size > 0) model.materials[0] = marble else model.materials += marble
            applied++
        }
        entity.getChildren().forEach { walk(it) }
    }
    walk(ground)
    android.util.Log.i("FireworkDiag", "ground material applied to $applied mesh node(s)")
}

/**
 * The night-sky dome's geometry, derived once so the moon can be placed against it.
 *
 * An ellipsoid centred on the castle's base ([cy] is that base's Y; X and Z are zero because the
 * castle is centred), with:
 * - horizontal semi-axes pinned just inside the container walls, and
 * - a vertical semi-axis that puts the apex just under the ceiling.
 *
 * Nothing here is hand-tuned; all three fall out of the measured view.
 */
class SkyDome(val ax: Float, val b: Float, val az: Float, val cy: Float) {

    /** Apex height. Sits just below the container's ceiling by construction. */
    val top: Float
        get() = cy + b

    /**
     * True when a sphere of radius [r] centred at [c] lies wholly inside.
     *
     * This samples the sphere's surface instead of the obvious shortcut of shrinking the
     * ellipsoid's semi-axes by `r` and testing the centre. That shortcut is only valid for a
     * *sphere*: on an ellipsoid this eccentric (0.297 x 1.34 x 0.223) a centre well inside the
     * shrunken ellipsoid can still let the sphere poke out through the flat end -- e.g. semi-axes
     * (10, 1), r = 0.5, centre (9.0, 0.15) passes the shrunken test but reaches 1.13 on the real
     * surface. A coarse grid is enough: both surfaces are smooth and convex.
     */
    fun contains(c: Vector3, r: Float): Boolean {
        for (i in 0..CONTAINS_RINGS) {
            val theta = PI_F * i / CONTAINS_RINGS
            val sinTheta = sin(theta)
            val y = c.y + r * cos(theta)
            for (j in 0 until CONTAINS_SEGMENTS) {
                val phi = 2f * PI_F * j / CONTAINS_SEGMENTS
                val x = c.x + r * sinTheta * cos(phi)
                val z = c.z + r * sinTheta * sin(phi)
                val v = (x / ax).pow(2) + ((y - cy) / b).pow(2) + (z / az).pow(2)
                if (v > 1f) return false
            }
        }
        return true
    }

    private companion object {
        const val PI_F = 3.1415927f
        const val CONTAINS_RINGS = 12
        const val CONTAINS_SEGMENTS = 24
    }
}

/**
 * Works out the dome that will sit over the scene, centred on the castle's base at [baseY].
 *
 * A conventional skybox *encloses* the camera, which cannot work here: content is clipped to the
 * SpatialView's box, so any surface outside it is cut away and a surrounding sky would render as
 * nothing at all. The dome therefore sits just *inside* the walls.
 *
 * The container is 0.6 x 1.35 x 0.45 m -- far from cubic -- so a true sphere large enough to cover
 * the scene would burst through the side and back walls and be clipped into a dome full of holes.
 * Horizontal semi-axes are therefore pinned at the walls, which is the largest they can ever be,
 * and the height is whatever reaches the ceiling from the castle's base.
 *
 * That pinning is also why the *moon* moves rather than the dome: at the walls the horizontal
 * budget is fixed, and the moon's old spot (52% of half-width, -55% of half-depth) spent more of it
 * than a dome closing under the ceiling has left -- so far over budget that even a zero-radius moon
 * parked there would have been outside. [loadMoon] pulls it in to fit.
 */
private fun skyDomeOver(bounds: SceneBounds, baseY: Float): SkyDome =
    SkyDome(
        ax = bounds.halfW * SKY_DOME_INSET,
        az = bounds.halfD * SKY_DOME_INSET,
        b = bounds.halfH * SKY_DOME_INSET - baseY,
        cy = baseY,
    )

/**
 * Draws the dome.
 *
 * [MaterialCullingMode.FRONT] is what makes it a sky rather than a black ball: viewed from outside
 * a closed surface every visible face is front-facing, so all of them are culled and what remains is
 * the inside of the far side. That test is per fragment against the current view, so it keeps
 * working from any angle the user walks to.
 *
 * Everything below the container floor is clipped away, which is what turns the ellipsoid into a
 * dome standing on the ground.
 */
private fun addSkyDome(root: Entity, sky: SkyDome) {
    if (!SKY_DOME_ENABLED) return

    val dome = Entity()
    dome.components.set(
        ModelComponent(
            // Unit sphere, shaped by the transform below -- there is no ellipsoid primitive.
            MeshResource.createSphere(1f),
            skyMaterial(),
        )
    )
    dome.components[TransformComponent::class.java]?.apply {
        scaleVector = Vector3(sky.ax, sky.b, sky.az)
    }
    dome.components[TransformComponent::class.java]?.apply {
        position = Vector3(0f, sky.cy, 0f)
    }
    root.addChild(dome)

    android.util.Log.i(
        "FireworkDiag",
        "sky dome: semi=(%.3f,%.3f,%.3f) base y=%.3f apex=%.3f"
            .format(sky.ax, sky.b, sky.az, sky.cy, sky.top),
    )
}

/**
 * Hangs the moon in the upper sky above the castle.
 *
 * Loaded and failed independently of the ground/castle: a missing or broken moon should cost the
 * moon, not the whole scene. Unlike the backdrop it is centred (not grounded) on its target,
 * since it floats, and it is deliberately placed above the burst band and pushed toward the back
 * wall so the fireworks read as being in front of it.
 *
 * Its horizontal offset is not final until it has been fitted against [sky]: the dome's sides are
 * pinned at the container walls, so it is the moon that gives way, not the sky.
 */
private suspend fun loadMoon(root: Entity, bounds: SceneBounds, sky: SkyDome) {
    val (halfW, halfH, halfD) = bounds
    try {
        val moon = Entity.loadSuspend("asset://moon.usdz")
        root.addChild(moon)

        val raw = moon.getVisualBounds(relativeTo = root, recursive = true)
        val size = raw.size
        val widest = maxOf(size.x, size.y, size.z)
        if (widest <= 1e-5f) {
            android.util.Log.w("FireworkDiag", "moon has no measurable bounds; skipped")
            return
        }
        val scale = (halfW * 2f * MOON_DIAMETER_SHARE) / widest
        val radius = widest * scale / 2f
        
        // Keep the whole sphere inside the container on every axis.
        val wanted =
            Vector3(
                (halfW * MOON_X_SHARE).coerceAtMost(halfW - radius),
                (halfH * MOON_Y_SHARE - MOON_DROP_M).coerceAtMost(halfH - radius),
                (halfD * MOON_Z_SHARE).coerceAtLeast(-(halfD - radius)),
            )

        // Then walk it in toward the dome's axis until the sky closes over it. Only X and Z give
        // way -- the height is the composition, the horizontal offset is the slack. The step is
        // linear rather than a bisection because `contains` is not a clean predicate to bisect on
        // (a sphere can re-enter as it moves), and 50 cheap tests at load time cost nothing.
        var pull = 1f
        while (pull > MOON_PULL_MIN &&
            !sky.contains(
                Vector3(wanted.x * pull, wanted.y, wanted.z * pull),
                radius + SKY_DOME_MOON_CLEARANCE_M,
            )
        ) {
            pull -= MOON_PULL_STEP
        }
        val target = Vector3(wanted.x * pull, wanted.y, wanted.z * pull)

        moon.components[TransformComponent::class.java]?.apply {
            scaleVector = Vector3(scale, scale, scale)
        }
        moon.components[TransformComponent::class.java]?.apply {
            // Centre the model on the target rather than resting it on anything.
            position =
                Vector3(
                    target.x - raw.center.x * scale,
                    target.y - raw.center.y * scale,
                    target.z - raw.center.z * scale,
                )
        }
        // Warm and bright, and deliberately NOT in washSurfaces: the moon is the light source, so
        // a firework brightening it would read backwards.
        tintTree(moon, MOON_TINT, "moon")

        android.util.Log.i(
            "FireworkDiag",
            "moon: r=%.3f wanted=(%.3f,%.3f,%.3f) pull=%.2f -> centre=(%.3f,%.3f,%.3f) inSky=%s"
                .format(
                    radius,
                    wanted.x,
                    wanted.y,
                    wanted.z,
                    pull,
                    target.x,
                    target.y,
                    target.z,
                    sky.contains(target, radius),
                ),
        )
    } catch (e: ResourceLoadingException) {
        android.util.Log.e("FireworkDiag", "moon.usdz failed to load; scene continues without it", e)
    }
}

/** What the loaded scene tells the firework show. */
class SceneAnchors(
    /** Height of the castle alone -- the landmark a burst is judged "proportional" against. */
    val landmarkHeight: Float,
    /** Absolute Y of the castle's roofline; bursts are placed relative to this. */
    val landmarkTopY: Float,
    /** Where rockets launch from: the grass surface under the castle. */
    val launchOrigin: Vector3,
    /**
     * Materials a burst may wash with its own colour -- see `FireworkDirector.washSurfaces`.
     *
     * The ground and the castle. Both sit at the warm [MOONLIGHT_TINT] at rest and brighten toward
     * a burst's own colour while one is going off.
     */
    val washSurfaces: List<TintedSurface> = emptyList(),
)

/**
 * Loads the ground plane and stands the castle on it.
 *
 * Every placement is derived from `getVisualBounds` rather than from the authored transforms --
 * a model's pivot is not reliably at its base or at its footprint centre (see the two placement
 * bugs in AGENTS.md). Each model is scaled, re-measured, then shifted so its footprint centre is
 * at x/z = 0 and its base rests on the surface below it.
 *
 * Falls back to a placeholder slab if the assets fail to load, so a bad/missing file does not
 * take the whole show down.
 */
private suspend fun loadSceneBackdrop(root: Entity, bounds: SceneBounds): SceneAnchors {
    val (halfW, halfH, halfD) = bounds
    val floorY = -halfH + SCENE_LIFT_M
    return try {
        // --- ground plane, resting on the container floor -------------------------------------
        val ground = Entity.loadSuspend("asset://ground.usdz")
        root.addChild(ground)
        val groundRaw = ground.getVisualBounds(relativeTo = root, recursive = true)
        val groundSize = groundRaw.size

        // X and Z are scaled INDEPENDENTLY so the ground actually fills a non-square footprint.
        // That is only safe because this asset is a flat plane; a terrain/scenery model would
        // visibly shear, and the previous square grassland was left uniformly scaled (and so
        // left gaps) for exactly that reason.
        val sx = (halfW * 2f * GROUND_WIDTH_SHARE) / groundSize.x
        val sz = (halfD * 2f * GROUND_DEPTH_SHARE) / groundSize.z
        // A plane has zero height, so guard the divide; any real thickness stays proportional
        // to the smaller footprint factor and is still capped so it can't eat the sky.
        val maxGroundH = halfH * 2f * GROUND_MAX_HEIGHT_SHARE
        val sy =
            if (groundSize.y > 1e-5f) minOf(sx, sz, maxGroundH / groundSize.y) else minOf(sx, sz)

        ground.components[TransformComponent::class.java]?.apply {
            scaleVector = Vector3(sx, sy, sz)
        }
        ground.components[TransformComponent::class.java]?.apply {
            position =
                Vector3(
                    -groundRaw.center.x * sx,
                    floorY - groundRaw.min.y * sy,
                    -groundRaw.center.z * sz,
                )
        }
        applyGroundMaterial(ground)
        val groundSurfaces = tintTree(ground, MOONLIGHT_TINT, "ground")

        val grassTopY = floorY + groundSize.y * sy
        android.util.Log.i(
            "FireworkDiag",
            "ground: raw=%.2fx%.2fx%.2f scale=(%.4f,%.4f,%.4f) -> top=%.3f"
                .format(groundSize.x, groundSize.y, groundSize.z, sx, sy, sz, grassTopY),
        )

        // --- landmark: castle, standing on the grass -----------------------------------------
        val castle = Entity.loadSuspend("asset://castle.usdz")
        root.addChild(castle)
        val rawBounds = castle.getVisualBounds(relativeTo = root, recursive = true)
        val rawSize = rawBounds.size
        val fitScale =
            minOf(
                (halfW * 2f * BACKDROP_WIDTH_SHARE) / rawSize.x,
                (halfH * 2f * BACKDROP_HEIGHT_SHARE) / rawSize.y,
                (halfD * 2f * BACKDROP_DEPTH_SHARE) / rawSize.z,
            )
        castle.components[TransformComponent::class.java]?.apply {
            scaleVector = Vector3(fitScale, fitScale, fitScale)
        }
        castle.components[TransformComponent::class.java]?.apply {
            position =
                Vector3(
                    -rawBounds.center.x * fitScale,
                    // stands on the grass, not on the container floor
                    grassTopY - rawBounds.min.y * fitScale,
                    -rawBounds.center.z * fitScale,
                )
        }

        val castleSurfaces = tintTree(castle, LANDMARK_TINT, "castle")

        val castleHeight = rawSize.y * fitScale
        android.util.Log.i(
            "FireworkDiag",
            "castle: h=%.3f standing on grass top=%.3f -> roof=%.3f"
                .format(castleHeight, grassTopY, grassTopY + castleHeight),
        )
        SceneAnchors(
            landmarkHeight = castleHeight,
            landmarkTopY = grassTopY + castleHeight,
            launchOrigin = Vector3(0f, grassTopY, 0f),
            washSurfaces = groundSurfaces + castleSurfaces,
        )
    } catch (e: ResourceLoadingException) {
        android.util.Log.e("HomeVolume", "scene assets failed to load, using placeholder", e)
        val ground = Entity()
        ground.components.set(
            ModelComponent(
                MeshResource.createBox(
                    Vector3(
                        halfW * 2f * BACKDROP_WIDTH_SHARE,
                        0.02f,
                        halfD * 2f * BACKDROP_DEPTH_SHARE,
                    )
                ),
                UnlitMaterial.create().apply { setBaseColor(Color4(0.08f, 0.10f, 0.16f, 1f)) },
            )
        )
        ground.components[TransformComponent::class.java]?.apply {
            position = Vector3(0f, floorY, 0f)
        }
        root.addChild(ground)
        SceneAnchors(
            landmarkHeight = 0.22f,
            landmarkTopY = floorY + 0.22f,
            launchOrigin = Vector3(0f, floorY, 0f),
        )
    }
}

