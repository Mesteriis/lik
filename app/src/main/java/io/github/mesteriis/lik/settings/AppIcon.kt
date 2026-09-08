package io.github.mesteriis.lik.settings

import android.content.ComponentName
import io.github.mesteriis.lik.R

/** Alias names are persistent Android component identities; keep them stable across updates. */
enum class AppIcon(val alias: String, val labelRes: Int, val iconRes: Int, val emblemRes: Int, val optionId: Int) {
    CLASSIC("Classic", R.string.icon_classic, R.mipmap.ic_launcher, R.drawable.lik_emblem, R.id.icon_classic),
    EMERALD("Emerald", R.string.icon_emerald, R.mipmap.ic_launcher_emerald, R.drawable.lik_emblem_emerald, R.id.icon_emerald),
    AMETHYST("Amethyst", R.string.icon_amethyst, R.mipmap.ic_launcher_amethyst, R.drawable.lik_emblem_amethyst, R.id.icon_amethyst),
    SAPPHIRE("Sapphire", R.string.icon_sapphire, R.mipmap.ic_launcher_sapphire, R.drawable.lik_emblem_sapphire, R.id.icon_sapphire),
    SUNRISE("Sunrise", R.string.icon_sunrise, R.mipmap.ic_launcher_sunrise, R.drawable.lik_emblem_sunrise, R.id.icon_sunrise),
    MOONSTONE("Moonstone", R.string.icon_moonstone, R.mipmap.ic_launcher_moonstone, R.drawable.lik_emblem_moonstone, R.id.icon_moonstone),
    ROSE("Rose", R.string.icon_rose, R.mipmap.ic_launcher_rose, R.drawable.lik_emblem_rose, R.id.icon_rose),
    ICE("Ice", R.string.icon_ice, R.mipmap.ic_launcher_ice, R.drawable.lik_emblem_ice, R.id.icon_ice),
    AURORA("Aurora", R.string.icon_aurora, R.mipmap.ic_launcher_aurora, R.drawable.lik_emblem_aurora, R.id.icon_aurora),
    AMBER("Amber", R.string.icon_amber, R.mipmap.ic_launcher_amber, R.drawable.lik_emblem_amber, R.id.icon_amber);

    fun componentName(packageName: String): ComponentName =
        ComponentName(packageName, "$packageName.launcher.$alias")
}
