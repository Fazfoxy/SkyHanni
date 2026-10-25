package at.hannibal2.skyhanni.features.event.diana

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.utils.ColorUtils.toColor
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.compat.appendWithColor
import at.hannibal2.skyhanni.utils.compat.componentBuilder
import io.github.notenoughupdates.moulconfig.ChromaColour
import net.minecraft.network.chat.Component

enum class BurrowType(val text: String, var color: ChromaColour) {

    START(
        "Start",
        SkyHanniMod.feature.event.diana.burrowCustomization::startBurrowColor.get(),
    ),
    MOB(
        "Mob",
        SkyHanniMod.feature.event.diana.burrowCustomization::mobBurrowColor.get()
    ),
    TREASURE(
        "Treasure",
        SkyHanniMod.feature.event.diana.burrowCustomization::treasureBurrowColor.get()
    ),
    UNKNOWN(
        "Unknown?!",
        LorenzColor.WHITE.toChromaColor()
    ),
    ;


    override fun toString() = text

    fun getBurrowText(): Component = componentBuilder {
        appendWithColor(this@BurrowType.text, this@BurrowType.color.toColor().rgb)
    }
}
