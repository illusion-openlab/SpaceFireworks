package tech.illusion.spacefireworks.content

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.MeshInstancesResource
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Transform
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Color channels. One [MeshInstancesResource] per channel, because a mesh-instances resource
 * shares a single material -- so "how many colors can be on screen" is bounded by how many
 * channel entities we create, NOT by spark count. See [HomeVolume] for the entity/material setup.
 */
object Palette {
    const val WHITE = 0
    const val GOLD = 1
    const val RED = 2
    const val CYAN = 3
    const val VIOLET = 4
    const val GREEN = 5
    const val COUNT = 6

    /** Channels usable as a shell's main color (white is reserved for flash/rocket cores). */
    val TINTS = intArrayOf(GOLD, RED, CYAN, VIOLET, GREEN)

    /**
     * Star colors, indexed by channel.
     *
     * design-style: fixed-figma-color -- pyrotechnic star colors are the content itself, not
     * adaptive theme roles. Values run above 1 on the dominant channel so additive blending keeps a
     * white-hot center while the edges stay saturated. They live here rather than next to the
     * channel entities because a burst's *light* is derived from them too.
     */
    private val COLORS =
        arrayOf(
            Color4(1.6f, 1.5f, 1.3f, 1f), // WHITE
            Color4(1.6f, 0.95f, 0.28f, 1f), // GOLD
            Color4(1.7f, 0.30f, 0.42f, 1f), // RED
            Color4(0.25f, 1.25f, 1.7f, 1f), // CYAN
            Color4(1.05f, 0.45f, 1.7f, 1f), // VIOLET
            Color4(0.40f, 1.6f, 0.55f, 1f), // GREEN
        )

    fun colorOf(channel: Int): Color4 = COLORS[channel]

    /**
     * The colour a shell's light should be, given the tints it is made of.
     *
     * Averaged and then pulled well toward white: real pyrotechnic light is far less saturated on
     * the ground than the stars themselves look, and a strongly tinted wash reads as a coloured
     * spotlight rather than as a flash.
     */
    fun lightColorOf(tints: IntArray): Color4 {
        var r = 0f
        var g = 0f
        var b = 0f
        for (t in tints) {
            val c = colorOf(t)
            r += c.red
            g += c.green
            b += c.blue
        }
        val n = tints.size.coerceAtLeast(1).toFloat()
        fun mix(v: Float) = (v / n) * (1f - LIGHT_DESATURATION) + LIGHT_DESATURATION
        return Color4(mix(r), mix(g), mix(b), 1f)
    }

    private const val LIGHT_DESATURATION = 0.55f
}

/** The five shell types from the design contract (2.2 / 说明 4). */
enum class FireworkStyle {
    CHRYSANTHEMUM,
    WILLOW,
    RING,
    HEART,
    COLOR_SHIFT,
}

/**
 * Firework show director.
 *
 * Two things make this read as "real" rather than as dots on straight lines:
 *
 * 1. **Linear-drag ballistics.** A real star is thrown fast, is stopped almost immediately by air,
 *    then falls. `v(t) = (v0 - g/k)e^(-kt) + g/k`, integrated to a closed-form position. That
 *    closed form is also why trails are nearly free: a trail segment is just this same spark's
 *    position sampled at `age - lag`, so no per-spark history buffers are needed.
 * 2. **Additive overlap.** Every instance draws with `BlendingMode.ADD`, so overlapping sparks
 *    and their trails sum into a hot core that falls off toward the edge of the burst.
 *
 * Budget note: each channel is one entity / one draw call regardless of instance count, but every
 * live instance costs one `update()` (a JNI hop) per frame. [SPARKS] and [TRAIL_SEGMENTS] are the
 * dials for that; see AGENTS.md -- the cost has not been profiled on real hardware.
 */
/**
 * One running show. There is deliberately **one instance per scene**, not a singleton: the
 * volumetric window and the immersive Stage exist at the same time and each owns its own channel
 * entities, its own sky bounds and its own show clock. A singleton would have the second scene's
 * `bind` silently steal the first's channels, leaving the window driving entities that belong to a
 * container it can no longer see.
 *
 * @param label appears in every `FireworkDiag` line so the two scenes are separable in logcat.
 * @param showDurationS how long one show runs. Shells are scheduled across this whole span rather
 *   than as a fixed count, so this changes the duration and the shell count together. It does not
 *   raise peak cost: shells still fire ~1.15-2.2s apart and each burst lives under 3s, so the
 *   number alive at once is unchanged -- a longer show is more shells over time, not more stars on
 *   screen.
 */
class FireworkDirector(private val label: String, private val showDurationS: Float) {

    companion object {

        /** Burst geometry was tuned by eye against a backdrop this tall (meters). */
        const val REFERENCE_HEIGHT = 0.22f

        private const val TAU = 6.2831855f

        // Per-shell density. Trails multiply instance count by (1 + TRAIL_SEGMENTS).
        // 44 sparks with 2 short trails read as scattered confetti on device; density and trail
        // length are what turn dots into streaks, so both are deliberately high. Watch the
        // FireworkDiag `dt=` field when changing these -- every live instance is a JNI update/frame.
        // Fewer stars with longer tails beats more stars with none: at a 0.03s lag the tail sits
        // inside its own head and just fattens it into a bubble. 0.05s at ~24fps separates them into
        // a readable streak.
        private const val SPARKS = 52

        /** Ceiling on stars per burst. Past this the per-frame JNI cost starts showing in `dt`. */
        private const val SPARKS_MAX = 120

        /**
         * How star size follows the scene's scale. Below 1 on purpose -- see [bind].
         *
         * Tuned against *angular* size, which is what actually reads: the immersive show is viewed
         * from ~58m, so a star has to be 10-15cm across to land as a bright point. Linear scaling
         * (an implicit exponent of 1) gave 0.9m balls -- the "色块" complaint. 0.35 was the
         * over-correction in the other direction: 3cm stars are sub-pixel at that range and the
         * bursts nearly vanished. 0.65 puts a 181x scene's stars at ~30x the window's, ~15cm.
         */
        private const val STAR_SIZE_EXPONENT = 0.65f

        /** How star *count* follows the scene's scale. Gentle; the cap does the rest. */
        private const val SPARK_COUNT_EXPONENT = 0.18f
        private const val TRAIL_SEGMENTS = 5
        private const val TRAIL_LAG = 0.05f

        private const val FLASH_SPARKS = 14
        private const val FLASH_LIFE = 0.2f

        /**
         * Upper bound of the per-star speed jitter applied in [burst]. A star's travel is
         * `radius * reach * jitter`, so the planned radius must be divided by this or the fastest
         * stars overshoot the sky box by 15% and get clipped at the container wall.
         */
        private const val SPEED_JITTER_MAX = 1.15f

        /**
         * Depth squash applied to every star's outward direction.
         *
         * The container is far shallower than it is wide (0.45m deep vs 0.9m tall), and unlike width
         * and height the view's real depth cannot be read back -- `onGloballyPositioned` reports 2D
         * layout coords only, and `Modifier.depth` is a *preferred* size the parent may override. So
         * depth is the one axis whose budget is a guess, and a full-radius sphere is the thing most
         * likely to punch through it. Squashing z keeps bursts wide and tall (where the space and the
         * viewer both are) while staying well clear of the front and back walls.
         */
        private const val Z_FLATTEN = 0.5f

        /**
         * Gravity, expressed against burst radius rather than as an absolute. A star's droop under
         * linear drag settles at `g/k` per second, so a fixed g would make the low-drag willow fall
         * clean out of a small container while barely bending a burst in a large one.
         */
        private const val GRAVITY_PER_RADIUS = 0.36f

        /** How far a burst's wash carries, as a multiple of the sky's largest burst radius. */
        private const val WASH_REACH_PER_RADIUS = 6f

        /** How hard a burst directly overhead washes the scene. 1.0 would double its brightness. */
        private const val WASH_STRENGTH = 0.75f

        /** How long the wash takes to fade. Roughly the life of a burst's brightest stars. */
        private const val WASH_DECAY_S = 0.7f

        /**
         * Words the show opens with, in order, before it turns random.
         *
         * Any word here must be drawable by [FireworkText]; one that is not is skipped rather than
         * dropping a blank shell.
         */
        private val OPENING_WORDS = listOf("Pico", "Story")

        /** Beat after a word, long enough to read it before the next shell goes up. */
        private const val WORD_GAP_S = 2.6f

        /**
         * How a word burns.
         *
         * Low twinkle and a long life so the letters stay legible instead of flickering, one short
         * trail so strokes read as strokes rather than as a comet each, and a slightly bigger star
         * to thicken them.
         */
        private val WORD_PARAMS =
            StyleParams(reach = 1f, drag = 5.5f, life = 2.4f, size = 1.15f, twinkle = 4f, trails = 1)

        /** Quietest a shell can be, so the small ones are still heard. */
        private const val MIN_LOUDNESS = 0.45f

        /** Zero contribution, so a surface falls back to its own resting tint. */
        private val NO_WASH = Color4(0f, 0f, 0f, 1f)

        /**
         * Every director currently attached to a live scene.
         *
         * `System.register` takes a Class and the ECS owns the instance, so a [FireworkSystem]
         * cannot be handed a director directly -- it ticks whatever is in here instead. Copy-on-
         * write because the ECS iterates this from its own frame thread while composition
         * attaches/detaches from the main thread.
         */
        private val attached = CopyOnWriteArrayList<FireworkDirector>()

        /** Advances every live show. Called once per frame by [FireworkSystem]. */
        fun tickAll(deltaTime: Float) {
            attached.forEach { it.update(deltaTime) }
        }
    }

    /** Starts receiving ticks. Pair with [detach] from the owning scene's disposal. */
    fun attach() {
        attached.addIfAbsent(this)
    }

    /** Stops ticks and drops every star, so a closed scene costs nothing. */
    fun detach() {
        attached.remove(this)
        clearAll()
        // The pool is shared app-wide and outlives any one scene, so it is not released here.
        audio = null
    }

    private var channels: List<MeshInstancesResource> = emptyList()
    private var launchOrigin: Vector3 = Vector3.ZERO
    private var park: Vector3 = Vector3.ZERO

    // Point lights the show borrows, one per burst, plus how much life each has left. A burst
    // claims the dimmest one, so a new shell always steals from the flash that is furthest gone
    // rather than from one that is still doing visible work.
    /**
     * Surfaces a burst washes with its own light.
     *
     * The SDK does have real point lights, and they were tried first -- `PointLightComponent` on a
     * pool of entities, ground switched to `PhysicallyBasedMaterial`. The component values verified
     * correct on read-back (`1621lm r=0.30m`), and the ground's measured brightness stayed at
     * exactly 49/255 across four frames at every intensity up to ~2500 lumens over 0.3m: dynamic
     * lighting simply does not reach this render path. So the wash is done in the material instead,
     * which is also cheaper and works regardless of what the lighting pipeline supports.
     */
    var washSurfaces: List<TintedSurface> = emptyList()

    private var washAmount = 0f
    private var washColor: Color4 = Color4(1f, 1f, 1f, 1f)
    private var washApplied = false

    /**
     * Launch and burst SFX. Injected by the owning composable, which is where a Context lives --
     * unlike everything else here this is app-wide and shared, see [FireworkAudio.shared].
     */
    var audio: FireworkAudio? = null
    private var scale: Float = 1f
    private var starScale: Float = 1f
    private var sparkCount: Int = SPARKS
    private var nextId = 0

    // The volume the show has to stay inside. A burst detonating outside the container is simply
    // clipped away -- it does not shrink to fit -- so these are hard bounds, not suggestions.
    private var apexMin = 0f
    private var apexMax = 0f
    private var maxRadius = 0.08f
    private var spreadX = 0f
    private var spreadZ = 0f

    private class Spark(
        val ids: Array<String>,
        val channel: Int,
        val center: Vector3,
        val velocity: Vector3,
        val drag: Float,
        val life: Float,
        val size: Float,
        val twinklePhase: Float,
        val twinkleRate: Float,
        var age: Float = 0f,
    )

    private class Rocket(
        val ids: Array<String>,
        val from: Vector3,
        val to: Vector3,
        val rise: Float,
        val shell: ShellPlan,
        var age: Float = 0f,
    )

    private class ShellPlan(
        val launchAt: Float,
        val style: FireworkStyle,
        val tints: IntArray,
        val target: Vector3,
        val radius: Float,
        /** When set, this shell spells the word out instead of bursting in its style's shape. */
        val text: String? = null,
    )

    private val sparks = mutableListOf<Spark>()
    private val rockets = mutableListOf<Rocket>()
    private val pending = mutableListOf<ShellPlan>()
    private var showClock = 0f

    /**
     * @param backdropHeight Real height (meters, post-scaling) of the scene backdrop the show
     *   should look proportional to. Spark speed and gravity scale off it relative to
     *   [REFERENCE_HEIGHT].
     * @param sky The volume bursts must stay inside, in the same space as [launchOrigin]. Sized
     *   by the caller from the actual container bounds -- see [HomeVolume].
     */
    fun bind(
        channels: List<MeshInstancesResource>,
        launchOrigin: Vector3,
        park: Vector3,
        backdropHeight: Float = REFERENCE_HEIGHT,
        sky: SkyBox,
    ) {
        this.channels = channels
        this.launchOrigin = launchOrigin
        this.park = park
        this.scale = backdropHeight / REFERENCE_HEIGHT
        // A star is a burning pellet a few centimetres across whether the shell is a desk toy or
        // the real thing -- what a bigger shell buys is more stars spread further apart, not fatter
        // ones. Scaling star size linearly with the scene is what turned the immersive show into
        // colour blocks: at 181x a 5mm sphere becomes a 0.9m ball. The exponent keeps stars
        // readable in the tiny window while letting them stay points at real scale.
        this.starScale = scale.pow(STAR_SIZE_EXPONENT)
        // ...and the same shell needs more of them once its radius goes from 15cm to 46m, or the
        // burst reads as a handful of dots on a huge sphere. Capped: every live instance is one
        // JNI update per frame (see the class KDoc's budget note).
        this.sparkCount =
            (SPARKS * scale.pow(SPARK_COUNT_EXPONENT)).toInt().coerceIn(SPARKS, SPARKS_MAX)
        android.util.Log.i(
            "FireworkDiag",
            "[$label] scale=%.1f -> starScale=%.2f sparks=%d".format(scale, starScale, sparkCount),
        )
        updateSky(sky)
    }

    /**
     * Re-bounds the show without restarting it. The container is user-resizable, so the volume the
     * bursts must fit inside can change at any time; shells already in flight keep their planned
     * radius and only newly scheduled ones use the new bounds.
     */
    fun updateSky(sky: SkyBox) {
        this.maxRadius = sky.maxRadius
        this.apexMin = sky.apexMin
        this.apexMax = sky.apexMax
        this.spreadX = sky.spreadX
        this.spreadZ = sky.spreadZ
    }

    /**
     * Where bursts are allowed to happen, in absolute container coordinates.
     *
     * @param apexMin/apexMax Y band for burst centers -- above the backdrop, far enough below the
     *   ceiling that a full-radius burst still fits.
     * @param maxRadius Largest burst radius that stays inside the container on every axis.
     * @param spreadX/spreadZ How far a burst center may wander from the launch origin.
     */
    class SkyBox(
        val apexMin: Float,
        val apexMax: Float,
        val maxRadius: Float,
        val spreadX: Float,
        val spreadZ: Float,
    )

    /**
     * Starts a fresh show: shells fired ~1.15-2.2s apart across [showDurationS], drawing 2-3
     * distinct styles and a random tint set. Restarts immediately if a show is already running --
     * pressing 「点火」 mid-show should give you a new show, not be ignored until it ends.
     */
    fun ignite() {
        if (channels.isEmpty()) return
        clearAll()

        val styles = FireworkStyle.entries.shuffled().take(2 + Random.nextInt(2))

        // Schedule by clock, not by count, so showDurationS alone controls how long it runs.
        var t = 0.15f
        var wordIndex = 0
        while (t < showDurationS) {
            val style = styles[Random.nextInt(styles.size)]
            val tintCount = if (style == FireworkStyle.COLOR_SHIFT) 3 else 1 + Random.nextInt(2)
            val tints = Palette.TINTS.toMutableList().apply { shuffle() }.take(tintCount)

            // Spread shells across the sky rather than stacking them, but keep every burst
            // inside the container -- anything outside is clipped, not scaled down.
            val radius = maxRadius / SPEED_JITTER_MAX * (0.72f + Random.nextFloat() * 0.28f)
            val offsetX = (Random.nextFloat() - 0.5f) * 2f * spreadX
            val offsetZ = (Random.nextFloat() - 0.5f) * 2f * spreadZ
            val apex = apexMin + Random.nextFloat() * (apexMax - apexMin).coerceAtLeast(0f)

            // The show opens by spelling itself out; everything after is the random show.
            val word = OPENING_WORDS.getOrNull(wordIndex)?.takeIf { FireworkText.canRender(it) }
            if (word != null) wordIndex++

            pending.add(
                if (word != null) {
                    ShellPlan(
                        launchAt = t,
                        style = FireworkStyle.CHRYSANTHEMUM,
                        // One colour per word: a word in mixed colours is much harder to read.
                        tints = intArrayOf(Palette.TINTS[Random.nextInt(Palette.TINTS.size)]),
                        // Centred and at full radius -- the word should be the biggest, most
                        // legible thing in the sky, not one of the scattered shells.
                        target = Vector3(launchOrigin.x, apex, launchOrigin.z),
                        radius = maxRadius / SPEED_JITTER_MAX,
                        text = word,
                    )
                } else {
                    ShellPlan(
                        launchAt = t,
                        style = style,
                        tints = tints.toIntArray(),
                        target = Vector3(launchOrigin.x + offsetX, apex, launchOrigin.z + offsetZ),
                        radius = radius,
                    )
                }
            )
            // Words get a longer beat after them so they can be read before the next shell.
            t += if (word != null) WORD_GAP_S else 1.15f + Random.nextFloat() * 1.05f
        }
        showClock = 0f
    }

    private var diagAccum = 0f
    private var diagTicks = 0
    private var loggedFailure = false

    // Worst-case extents actually reached by any star, so "is it inside the container?" can be
    // answered with a measurement instead of an assertion.
    private var peakX = 0f
    private var peakZ = 0f
    private var peakYTop = -9f
    private var peakYBottom = 9f

    fun update(deltaTime: Float) {
        // The ECS dispatches System.update inside its own frame loop; a throw here does not
        // surface as a crash, it just silently stops the effect. Catch and report it once.
        try {
            updateInternal(deltaTime)
        } catch (t: Throwable) {
            if (!loggedFailure) {
                loggedFailure = true
                android.util.Log.e("FireworkDiag", "[$label] update FAILED on tick $diagTicks", t)
            }
        }
    }

    private fun updateInternal(deltaTime: Float) {
        diagTicks++
        if (channels.isEmpty()) {
            if (diagTicks == 1) android.util.Log.w("FireworkDiag", "[$label] tick with no channels bound")
            return
        }
        showClock += deltaTime

        while (pending.isNotEmpty() && pending[0].launchAt <= showClock) {
            launchRocket(pending.removeAt(0))
        }

        updateRockets(deltaTime)
        updateSparks(deltaTime)
        updateWash(deltaTime)

        diagAccum += deltaTime
        if (diagAccum >= 0.5f) {
            diagAccum = 0f
            android.util.Log.i(
                "FireworkDiag",
                ("[$label] t=%.2f dt=%.4f sparks=%d addFail=%d updFail=%d " +
                    "PEAK |x|=%.3f y=[%.3f..%.3f] |z|=%.3f  sky[apex %.3f..%.3f r=%.3f]")
                    .format(
                        showClock,
                        deltaTime,
                        sparks.size,
                        addFails,
                        updateFails,
                        peakX,
                        peakYBottom,
                        peakYTop,
                        peakZ,
                        apexMin,
                        apexMax,
                        maxRadius,
                    ),
            )
        }
    }

    // ---------------------------------------------------------------- rocket

    private fun launchRocket(plan: ShellPlan) {
        val rise = 0.55f + Random.nextFloat() * 0.2f
        val ids = Array(3) { newId() }
        val rocket = Rocket(ids, launchOrigin, plan.target, rise, plan)
        rockets.add(rocket)
        ids.forEach { add(Palette.GOLD, it, launchOrigin, 0f) }
        audio?.playLaunch(loudnessOf(plan))
    }

    private fun updateRockets(deltaTime: Float) {
        val iterator = rockets.iterator()
        while (iterator.hasNext()) {
            val rocket = iterator.next()
            rocket.age += deltaTime
            val t = rocket.age / rocket.rise

            if (t >= 1f) {
                rocket.ids.forEach { remove(Palette.GOLD, it) }
                iterator.remove()
                burst(rocket.shell)
                continue
            }

            // Decelerating rise, so it visibly "coasts" into the burst instead of arriving flat.
            for (i in rocket.ids.indices) {
                val lagged = (t - i * 0.06f).coerceAtLeast(0f)
                val eased = 1f - (1f - lagged) * (1f - lagged)
                val pos = Vector3.lerp(rocket.from, rocket.to, eased)
                val flicker = 0.75f + 0.25f * sin(rocket.age * 40f + i)
                val size = (1.1f - i * 0.3f) * flicker * starScale
                update(Palette.GOLD, rocket.ids[i], pos, size)
            }
        }
    }

    // ---------------------------------------------------------------- burst

    private fun burst(plan: ShellPlan) {
        spawnFlash(plan.target)
        washScene(plan)
        audio?.playBurst(loudnessOf(plan))

        val isWord = plan.text != null
        val directions =
            if (isWord) FireworkText.directions(plan.text!!) else directionsFor(plan.style)
        val base = if (isWord) WORD_PARAMS else baseParamsFor(plan.style)

        for (i in directions.indices) {
            val dir = directions[i]
            val channel =
                when (plan.style) {
                    // Rainbow shell: the tint cycles per spark, so one burst shows every color.
                    FireworkStyle.COLOR_SHIFT -> plan.tints[i % plan.tints.size]
                    else -> plan.tints[Random.nextInt(plan.tints.size)]
                }

            // A word gets no speed jitter: the whole point is that the sparks land where the
            // glyph says, and +-15% of travel smears the strokes into a cloud.
            val speedJitter =
                if (isWord) 1f else (SPEED_JITTER_MAX - 0.3f) + Random.nextFloat() * 0.3f
            val speed = plan.radius * base.reach * base.drag * speedJitter
            val life = base.life * (0.85f + Random.nextFloat() * 0.3f)

            val ids = Array(1 + base.trails) { newId() }
            // A word is already flat by construction; flattening it again would be a no-op, and
            // the z term must stay exactly 0 so the letters read as one plane facing the viewer.
            val throwDir = if (isWord) dir else Vector3(dir.x, dir.y, dir.z * Z_FLATTEN)
            val spark =
                Spark(
                    ids = ids,
                    channel = channel,
                    center = plan.target,
                    velocity = throwDir.times(speed),
                    drag = base.drag,
                    life = life,
                    size = base.size * (0.75f + Random.nextFloat() * 0.5f),
                    twinklePhase = Random.nextFloat() * TAU,
                    twinkleRate = base.twinkle * (0.7f + Random.nextFloat() * 0.6f),
                )
            sparks.add(spark)
            ids.forEach { add(channel, it, plan.target, 0f) }
        }
    }

    /** The white-hot core at the moment of detonation. Short-lived and much larger than a star. */
    private fun spawnFlash(center: Vector3) {
        for (i in 0 until FLASH_SPARKS) {
            val ids = arrayOf(newId())
            sparks.add(
                Spark(
                    ids = ids,
                    channel = Palette.WHITE,
                    center = center,
                    velocity = randomDirection().times(0.05f * scale),
                    drag = 9f,
                    life = FLASH_LIFE * (0.7f + Random.nextFloat() * 0.6f),
                    size = 2.6f * (0.6f + Random.nextFloat() * 0.8f),
                    twinklePhase = 0f,
                    twinkleRate = 0f,
                )
            )
            ids.forEach { add(Palette.WHITE, it, center, 0f) }
        }
    }

    /**
     * @param reach How far the stars travel, as a multiple of the shell's planned radius. Under
     *   linear drag a star's total travel is `v0/k`, so launch speed is derived as
     *   `radius * reach * drag` -- otherwise a low-drag style like [FireworkStyle.WILLOW] would
     *   fly several times further than a high-drag one from the same nominal radius.
     */
    private class StyleParams(
        val reach: Float,
        val drag: Float,
        val life: Float,
        val size: Float,
        val twinkle: Float,
        val trails: Int,
    )

    private fun baseParamsFor(style: FireworkStyle): StyleParams =
        when (style) {
            // Dense, hard-braking sphere with long trails -- the classic round chrysanthemum.
            FireworkStyle.CHRYSANTHEMUM ->
                StyleParams(reach = 1f, drag = 6.5f, life = 1.6f, size = 0.95f, twinkle = 22f, trails = TRAIL_SEGMENTS)
            // Barely any drag + long life, so gravity dominates and the stars droop into strands.
            FireworkStyle.WILLOW ->
                StyleParams(reach = 0.8f, drag = 1.7f, life = 2.6f, size = 1f, twinkle = 8f, trails = TRAIL_SEGMENTS)
            FireworkStyle.RING ->
                StyleParams(reach = 1f, drag = 5.5f, life = 1.6f, size = 0.9f, twinkle = 26f, trails = 4)
            FireworkStyle.HEART ->
                StyleParams(reach = 0.95f, drag = 5f, life = 1.8f, size = 0.9f, twinkle = 16f, trails = 4)
            FireworkStyle.COLOR_SHIFT ->
                StyleParams(reach = 1f, drag = 6f, life = 1.7f, size = 0.95f, twinkle = 30f, trails = 4)
        }

    private fun directionsFor(style: FireworkStyle): List<Vector3> =
        when (style) {
            FireworkStyle.CHRYSANTHEMUM,
            FireworkStyle.COLOR_SHIFT -> List(sparkCount) { randomDirection() }
            // Upward bias so the strands have somewhere to fall from.
            FireworkStyle.WILLOW ->
                List(sparkCount) {
                    val d = randomDirection()
                    Vector3(d.x, abs(d.y) * 0.9f + 0.25f, d.z).normalize()
                }
            FireworkStyle.RING -> ringDirections()
            FireworkStyle.HEART -> heartDirections()
        }

    /** A flat ring on a randomly tilted plane, so consecutive rings don't look identical. */
    private fun ringDirections(): List<Vector3> {
        val normal = randomDirection()
        val helper = if (abs(normal.y) > 0.9f) Vector3(1f, 0f, 0f) else Vector3(0f, 1f, 0f)
        val tangent = Vector3.cross(normal, helper).normalize()
        val bitangent = Vector3.cross(normal, tangent).normalize()
        return List(sparkCount) { i ->
            val a = TAU * i / sparkCount
            // Slight thickness, otherwise it reads as a wireframe circle rather than a shell.
            val wobble = normal.times((Random.nextFloat() - 0.5f) * 0.12f)
            tangent.times(cos(a)).plus(bitangent.times(sin(a))).plus(wobble).normalize()
        }
    }

    /**
     * Classic heart curve, laid in the XY plane. `SpatialView`'s +Z points at the observer
     * (coordinates-and-units.md), so an XY-plane shape faces the user rather than edge-on.
     */
    private fun heartDirections(): List<Vector3> =
        List(sparkCount) { i ->
            val a = TAU * i / sparkCount
            val s = sin(a)
            val x = 16f * s * s * s
            val y =
                13f * cos(a) - 5f * cos(2f * a) - 2f * cos(3f * a) - cos(4f * a)
            val depth = (Random.nextFloat() - 0.5f) * 2.5f
            Vector3(x, y, depth).normalize()
        }

    // ---------------------------------------------------------------- sparks

    private fun updateSparks(deltaTime: Float) {
        val gravity = maxRadius * GRAVITY_PER_RADIUS
        val iterator = sparks.iterator()
        while (iterator.hasNext()) {
            val spark = iterator.next()
            spark.age += deltaTime
            if (spark.age >= spark.life) {
                spark.ids.forEach { remove(spark.channel, it) }
                iterator.remove()
                continue
            }

            val ratio = spark.age / spark.life
            // Hold brightness, then fall off hard at the end -- stars burn out, they don't dim
            // linearly from the moment they appear.
            val fade = (1f - ratio * ratio * ratio).coerceIn(0f, 1f)
            val twinkle =
                if (spark.twinkleRate == 0f) 1f
                else 0.72f + 0.28f * sin(spark.age * spark.twinkleRate + spark.twinklePhase)

            for (i in spark.ids.indices) {
                val t = (spark.age - i * TRAIL_LAG).coerceAtLeast(0f)
                val pos = positionAt(spark, t, gravity)
                if (abs(pos.x) > peakX) peakX = abs(pos.x)
                if (pos.y > peakYTop) peakYTop = pos.y
                if (pos.y < peakYBottom) peakYBottom = pos.y
                if (abs(pos.z) > peakZ) peakZ = abs(pos.z)
                // Head is the bright point; the tail shrinks fast so the streak reads as a
                // comet rather than a string of equal beads.
                val taper = if (i == 0) 1f else 0.66f * (1f - (i - 1).toFloat() / TRAIL_SEGMENTS)
                val size = spark.size * fade * twinkle * taper * starScale
                update(spark.channel, spark.ids[i], pos, size.coerceAtLeast(0f))
            }
        }
    }

    /**
     * Closed-form position under linear drag plus gravity:
     * `p(t) = p0 + (v0 - g/k)(1 - e^(-kt))/k + (g/k)t`, with `g` pointing down.
     *
     * Being closed-form (rather than integrated step by step) is what lets a trail segment ask
     * for this same spark's position a few tens of milliseconds ago without storing any history.
     */
    private fun positionAt(spark: Spark, t: Float, gravity: Float): Vector3 {
        val k = spark.drag
        val decay = (1f - exp(-k * t)) / k
        val terminal = gravity / k
        return Vector3(
            spark.center.x + spark.velocity.x * decay,
            spark.center.y + (spark.velocity.y + terminal) * decay - terminal * t,
            spark.center.z + spark.velocity.z * decay,
        )
    }

    // ---------------------------------------------------------------- plumbing

    private fun randomDirection(): Vector3 {
        val y = Random.nextFloat() * 2f - 1f
        val theta = Random.nextFloat() * TAU
        val r = sqrt((1f - y * y).coerceAtLeast(0f))
        return Vector3(r * cos(theta), y, r * sin(theta))
    }

    private fun newId(): String = "fx${nextId++}"

    private var addFails = 0
    private var updateFails = 0

    private fun add(channel: Int, id: String, position: Vector3, size: Float) {
        val ok =
            channels.getOrNull(channel)?.add(
                MeshInstancesResource.Instance(id, transformOf(position, size))
            )
        if (ok != true) addFails++
    }

    private fun update(channel: Int, id: String, position: Vector3, size: Float) {
        val ok = channels.getOrNull(channel)?.update(id, transformOf(position, size))
        if (ok != true) updateFails++
    }

    private fun remove(channel: Int, id: String) {
        channels.getOrNull(channel)?.remove(id)
    }

    // Instances live in the channel entity's local space; the entity is parked out of sight by the
    // caller, so undo that offset here to keep every caller working in absolute scene coordinates.
    private fun transformOf(position: Vector3, size: Float) =
        Transform(
            position.minus(park),
            EulerAngles(0f, 0f, 0f),
            Vector3(size, size, size),
        )

    /**
     * How loud a shell is, 0..1.
     *
     * Bigger shells are louder, which is the whole reason the immersive show should sound different
     * from the desk-sized one -- but the floor keeps the small end audible rather than silent.
     */
    private fun loudnessOf(plan: ShellPlan): Float {
        val fraction = if (maxRadius > 1e-5f) plan.radius / maxRadius else 1f
        return (MIN_LOUDNESS + (1f - MIN_LOUDNESS) * fraction).coerceIn(0f, 1f)
    }

    /**
     * Kicks off the flash a burst throws onto the scene.
     *
     * Nearer bursts wash harder: the amount is scaled by how close the burst is to the launch
     * origin relative to the sky's reach, so a shell overhead lights the ground and one off at the
     * edge of the field barely does.
     */
    private fun washScene(plan: ShellPlan) {
        if (washSurfaces.isEmpty()) return
        val distance = Vector3.distance(plan.target, launchOrigin)
        val reach = (maxRadius * WASH_REACH_PER_RADIUS).coerceAtLeast(1e-4f)
        val falloff = (1f - distance / reach).coerceIn(0f, 1f)
        val amount = WASH_STRENGTH * falloff
        // A second burst mid-flash should add to it, not cut it short.
        if (amount > washAmount) {
            washAmount = amount
            washColor = Palette.lightColorOf(plan.tints)
        }
    }

    /**
     * Decays the flash and pushes it into the materials.
     *
     * `setBaseColor` multiplies the base color texture, so 1.0 is the surface's own look and
     * anything above that is the burst brightening it. The write is skipped once the flash is fully
     * decayed and has been reset once -- this runs every frame, and a per-frame material write for
     * a value that is not changing is pure JNI cost.
     */
    private fun updateWash(deltaTime: Float) {
        if (washSurfaces.isEmpty()) return
        if (washAmount <= 0f) {
            if (washApplied) {
                // Back to the scene's resting moonlight, not to neutral white.
                washSurfaces.forEach { it.apply(NO_WASH, 0f) }
                washApplied = false
            }
            return
        }
        washAmount = (washAmount - deltaTime / WASH_DECAY_S).coerceAtLeast(0f)
        // Squared so it reads as a flash dropping away rather than a lamp being dimmed.
        val k = washAmount * washAmount
        washSurfaces.forEach { it.apply(washColor, k) }
        washApplied = true
    }

    private fun clearAll() {
        channels.forEach { it.removeAll() }
        washAmount = 0f
        sparks.clear()
        rockets.clear()
        pending.clear()
    }
}

/**
 * Registered once for the whole app; forwards every scene tick to every attached
 * [FireworkDirector].
 *
 * One System serves both scenes on purpose. `System.register` is app-global and idempotent-ish at
 * best, so registering a second one per container would double-tick every show.
 */
class FireworkSystem : System() {
    private var ticks = 0

    override fun update(context: SceneUpdateContext) {
        if (ticks < 3) {
            ticks++
            android.util.Log.i("FireworkDiag", "system tick $ticks dt=${context.deltaTime}")
        }
        FireworkDirector.tickAll(context.deltaTime)
    }
}
