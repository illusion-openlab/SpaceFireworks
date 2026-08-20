package tech.illusion.spacefireworks

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spacefireworks.content.HomeVolume
import tech.illusion.spacefireworks.content.IMMERSIVE_STAGE_ID
import tech.illusion.spacefireworks.content.ImmersiveStage

/**
 * Two containers, both registered up front.
 *
 * The volumetric window is the entrance (contract 1); the Stage is declared but not opened until
 * the 「沉浸」 button asks for it. Only the *default* container is configured in AndroidManifest.xml
 * -- a secondary Stage is declared here in the DSL and addressed by id.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { PicoTheme { HomeVolume() } }
        Stage(id = IMMERSIVE_STAGE_ID) { PicoTheme { ImmersiveStage() } }
    }
