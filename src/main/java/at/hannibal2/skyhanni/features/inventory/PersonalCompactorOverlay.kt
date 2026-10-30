package at.hannibal2.skyhanni.features.inventory

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.api.event.HandleEvent
import at.hannibal2.skyhanni.config.features.inventory.PersonalCompactorConfig
import at.hannibal2.skyhanni.data.ProfileStorageData
import at.hannibal2.skyhanni.events.InventoryUpdatedEvent
import at.hannibal2.skyhanni.events.RenderItemTipEvent
import at.hannibal2.skyhanni.events.RenderObject
import at.hannibal2.skyhanni.events.minecraft.ToolTipTextEvent
import at.hannibal2.skyhanni.skyhannimodule.SkyHanniModule
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.InventoryUtils.filterInnerSlots
import at.hannibal2.skyhanni.utils.ItemUtils.cleanName
import at.hannibal2.skyhanni.utils.ItemUtils.getCleanLore
import at.hannibal2.skyhanni.utils.ItemUtils.getInternalName
import at.hannibal2.skyhanni.utils.ItemUtils.getInternalNameOrNull
import at.hannibal2.skyhanni.utils.ItemUtils.repoItemName
import at.hannibal2.skyhanni.utils.KeyboardManager.isKeyHeld
import at.hannibal2.skyhanni.utils.NeuItems.getInternalNameFromHypixelIdOrNull
import at.hannibal2.skyhanni.utils.NeuItems.getItemStack
import at.hannibal2.skyhanni.utils.NumberUtil.formatInt
import at.hannibal2.skyhanni.utils.RegexUtils.groupOrNull
import at.hannibal2.skyhanni.utils.RegexUtils.matchMatcher
import at.hannibal2.skyhanni.utils.RegexUtils.matches
import at.hannibal2.skyhanni.utils.SafeItemStack
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getAttributeString
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getItemUuid
import at.hannibal2.skyhanni.utils.SkyBlockItemModifierUtils.getPersonalCompactorActive
import at.hannibal2.skyhanni.utils.SkyBlockUtils
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.renderables.RenderableTooltips
import at.hannibal2.skyhanni.utils.renderables.container.RenderableInventory.fakeInventory
import at.hannibal2.skyhanni.utils.renderables.primitives.empty
import at.hannibal2.skyhanni.utils.renderables.primitives.text
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import kotlin.Triple

@SkyHanniModule
object PersonalCompactorOverlay {

    private val config get() = SkyHanniMod.feature.inventory.personalCompactor

    private val patternGroup = RepoPattern.group("inventory.personalcompactor")

    /**
     * REGEX-TEST: PERSONAL_COMPACTOR_4000
     * REGEX-TEST: PERSONAL_DELETOR_7000
     * REGEX-TEST: PERSONAL_COMPACTOR_FARMING_4000
     */
    private val internalNamePattern by patternGroup.pattern(
        "internalname",
        "PERSONAL_(?<type>[^_]+)_(?<skill>[A-Z]+)?_?(?<tier>\\d+)",
    )

    /**
     * REGEX-TEST: (1/2) Personal Farming Compactor
     * REGEX-TEST: Personal Mining Compactor
     */
    private val compactorInventoryPattern by patternGroup.pattern(
        "inventory",
        "(?:\\(\\d\\/\\d\\) )?Personal (?<type>\\w+) Compactor",
    )

    /**
     * REGEX-TEST: ENABLED
     */
    private val itemCompactionEnabledPattern by patternGroup.pattern(
        "compactionenabled",
        "ENABLED",
    )

    private val slotsMap = mapOf(
        7000 to 12,
        6000 to 7,
        5000 to 3,
        4000 to 1,
    )

    private val storage get() = ProfileStorageData.profileSpecific?.compactorItems

    private const val MAX_ITEMS_PER_ROW = 7

    private val compactorRenderableMap = mutableMapOf<String, Renderable>()
    private val compactorEnabledMap = mutableMapOf<String, Boolean>()

    @HandleEvent
    private fun onToolTip(event: ToolTipTextEvent) {
        if (!isEnabled()) return
        if (!shouldShow()) return

        val itemStack = event.itemStack
        val internalName = itemStack.getInternalName()
        val name = event.toolTip.firstOrNull() ?: return
        val (type, tier, skill) = internalNamePattern.matchMatcher(internalName.asString()) {
            Triple(
                group("type"),
                group("tier").formatInt(),
                CompactorType.getBySkillNameOrNull(groupOrNull("skill")),
            )
        } ?: return

        val prefix = when (type) {
            "COMPACTOR" -> "personal_compact_"
            "DELETOR" -> "personal_deletor_"
            else -> return
        }

        val uuid = itemStack.getItemUuid() ?: return
        val enabled = getPersonalCompactorEnabled(itemStack) ?: return

        val slots = slotsMap[tier] ?: return
        // TODO once https://hypixel.net/threads/october-29-accessory-bag-revamp-economy-changes.6165308/ is on main change to just check is deletor.
        val itemList = if (skill != null) {
            storage?.get(skill)?.map {
                ChatUtils.debug("${it.key.repoItemName} has been run through map for tooltip render")
                if (it.value) it.key.getItemStack() else null
            }?.filterNotNull() ?: emptyList()
        } else {
            (0 until slots).map { slot ->
                val skyblockId = itemStack.getAttributeString(prefix + slot)
                skyblockId?.let { getInternalNameFromHypixelIdOrNull(it) }?.getItemStack()
            }
        }

        val fakeInventory = compactorRenderableMap.getOrPut(uuid) {
            Renderable.fakeInventory(itemList, MAX_ITEMS_PER_ROW, 1.0)
        }

        val title = Renderable.text(name)
        val statusFormat = "§7Status: " + if (enabled) "§aEnabled" else "§cDisabled"
        val status = if (skill == null) Renderable.text(statusFormat) else Renderable.empty()

        RenderableTooltips.setTooltipForRender(listOf(title, status, fakeInventory), spacedTitle = true)
        event.cancel()
    }

    @HandleEvent
    private fun onInventoryClose() {
        compactorRenderableMap.clear()
    }

    @HandleEvent
    private fun onInventoryUpdated(event: InventoryUpdatedEvent) {
        compactorEnabledMap.clear()
        compactorInventoryPattern.matchMatcher(event.inventoryName) {
            val type = groupOrNull("type") ?: return
            val compactorType = CompactorType.getBySkillName(type)
            val innerItems = event.inventoryItems.filterInnerSlots()
            innerItems.forEach { (_, stack) ->
                if (stack.getCleanLore().any { itemCompactionEnabledPattern.matches(it) }) {
                    storage?.getOrPut(compactorType) { mutableMapOf() }[stack.getInternalName()] = true
                    ChatUtils.debug("${stack.cleanName} has been added to ${compactorType.name}'s Compactor List")
                } else {
                    storage?.getOrPut(compactorType) { mutableMapOf() }[stack.getInternalName()] = false
                    ChatUtils.debug("${stack.cleanName} has been removed from ${compactorType.name}'s Compactor List")
                }
            }
        }
    }

    @HandleEvent(onlyOnSkyblock = true)
    private fun onRenderItemTip(event: RenderItemTipEvent) {
        if (!config.showToggle) return
        val itemStack = event.stack
        val internalName = itemStack.getInternalNameOrNull() ?: return
        if (!internalNamePattern.matches(internalName.asString())) return

        val skill = internalNamePattern.matchMatcher(internalName.asString()) {
            groupOrNull("skill")
        }
        if (skill != null) return
        // TODO once https://hypixel.net/threads/october-29-accessory-bag-revamp-economy-changes.6165308/ is on main change to just check is deletor?

        val enabled = getPersonalCompactorEnabled(itemStack) ?: return
        val text = if (enabled) "§a✔" else "§c✖"
        val renderObject = RenderObject(
            text,
            -8,
            -10,
        )
        event.renderObjects.add(renderObject)
    }

    private fun shouldShow() = when (config.visibilityMode) {
        PersonalCompactorConfig.VisibilityMode.ALWAYS -> true
        PersonalCompactorConfig.VisibilityMode.KEYBIND -> config.keybind.isKeyHeld()
        PersonalCompactorConfig.VisibilityMode.EXCEPT_KEYBIND -> !config.keybind.isKeyHeld()
    }

    private fun getPersonalCompactorEnabled(itemStack: SafeItemStack): Boolean? {
        val uuid = itemStack.getItemUuid() ?: return null
        return compactorEnabledMap.getOrPut(uuid) { itemStack.getPersonalCompactorActive() }
    }

    enum class CompactorType(val skillName: String) {
        COMBAT("Combat"),
        FARMING("Farming"),
        FISHING("Fishing"),
        FORAGING("Foraging"),
        HUSBANDRY("Husbandry"),
        MINING("Mining"),
        ;

        companion object {
            fun getBySkillName(skillName: String): CompactorType {
                return CompactorType.entries.first { it.name == skillName.uppercase() }
            }

            fun getBySkillNameOrNull(skillName: String?): CompactorType? {
                if (skillName == null) return null
                return getBySkillName(skillName)
            }
        }

    }

    private fun isEnabled() = SkyBlockUtils.inSkyBlock && config.enabled
}
