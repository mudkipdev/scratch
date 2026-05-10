package arena;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.network.packet.server.play.ActionBarPacket;
import net.minestom.server.network.packet.server.play.BossBarPacket;

import java.util.UUID;

/**
 * On-screen indicators for the hero. Two boss bars (XP & combo) plus a per-tick action bar.
 */
final class Hud {
    private static final UUID XP_BAR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMBO_BAR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static void installBossBar(Hero hero) {
        final Component title = xpTitle(hero);
        hero.sendPacket(new BossBarPacket(XP_BAR_ID,
                new BossBarPacket.AddAction(title, 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10, (byte) 0)));
    }

    static void installComboBar(Hero hero) {
        final Component title = comboTitle(hero);
        hero.sendPacket(new BossBarPacket(COMBO_BAR_ID,
                new BossBarPacket.AddAction(title, 0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS, (byte) 0)));
    }

    static void update(Hero hero) {
        // XP bar
        final float xpPct = (float) hero.xp / hero.xpForNextLevel();
        hero.sendPacket(new BossBarPacket(XP_BAR_ID, new BossBarPacket.UpdateTitleAction(xpTitle(hero))));
        hero.sendPacket(new BossBarPacket(XP_BAR_ID, new BossBarPacket.UpdateHealthAction(Math.min(1f, xpPct))));

        // Combo bar (only if comboHits > 0)
        if (hero.comboHits > 0) {
            final float pct = Math.min(1f, hero.comboTicksLeft / 40f);
            hero.sendPacket(new BossBarPacket(COMBO_BAR_ID, new BossBarPacket.UpdateTitleAction(comboTitle(hero))));
            hero.sendPacket(new BossBarPacket(COMBO_BAR_ID, new BossBarPacket.UpdateHealthAction(pct)));
        } else {
            hero.sendPacket(new BossBarPacket(COMBO_BAR_ID, new BossBarPacket.UpdateHealthAction(0f)));
            hero.sendPacket(new BossBarPacket(COMBO_BAR_ID, new BossBarPacket.UpdateTitleAction(idleComboTitle())));
        }

        // Action bar with HP / DPS info
        hero.sendPacket(new ActionBarPacket(actionBar(hero)));
    }

    private static Component xpTitle(Hero hero) {
        return Component.text()
                .append(Component.text("✦ ", NamedTextColor.GOLD))
                .append(Component.text("Lvl " + hero.level, NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text("  ·  ", NamedTextColor.GRAY))
                .append(Component.text("Wave " + hero.arena.waveNumber, NamedTextColor.RED))
                .append(Component.text("  ·  ", NamedTextColor.GRAY))
                .append(Component.text("Kills " + hero.kills, NamedTextColor.WHITE))
                .append(Component.text("  ·  ", NamedTextColor.GRAY))
                .append(Component.text(hero.xp + "/" + hero.xpForNextLevel() + " xp", NamedTextColor.AQUA))
                .build();
    }

    private static Component comboTitle(Hero hero) {
        TextColor color = hero.comboHits >= 50 ? NamedTextColor.LIGHT_PURPLE
                : hero.comboHits >= 20 ? NamedTextColor.GOLD
                : hero.comboHits >= 10 ? NamedTextColor.YELLOW
                : NamedTextColor.WHITE;
        String label = hero.comboHits >= 50 ? "PHENOMENAL"
                : hero.comboHits >= 30 ? "DEVASTATING"
                : hero.comboHits >= 15 ? "RELENTLESS"
                : hero.comboHits >= 8 ? "FIERCE"
                : "STRIKE";
        return Component.text()
                .append(Component.text(label + " · ", color, TextDecoration.BOLD))
                .append(Component.text(hero.comboHits + "x combo", color))
                .build();
    }

    private static Component idleComboTitle() {
        return Component.text("Build a combo!", NamedTextColor.DARK_GRAY);
    }

    private static Component actionBar(Hero hero) {
        var builder = Component.text();
        // HP hearts
        builder.append(Component.text("❤ " + (int) Math.ceil(hero.hp) + "/" + hero.maxHp, NamedTextColor.RED));
        builder.append(Component.text("   "));
        // Damage
        builder.append(Component.text("⚔ " + (hero.baseDamage + (hero.strengthTicks > 0 ? 6 : 0)),
                hero.strengthTicks > 0 ? NamedTextColor.GOLD : NamedTextColor.WHITE));
        // Crit chance
        builder.append(Component.text("   ✦ " + (int) (hero.critChance * 100) + "% crit", NamedTextColor.LIGHT_PURPLE));
        // Buffs
        if (hero.strengthTicks > 0) {
            builder.append(Component.text("   STR " + hero.strengthTicks / 20 + "s", NamedTextColor.RED, TextDecoration.BOLD));
        }
        if (hero.speedTicks > 0) {
            builder.append(Component.text("   SPD " + hero.speedTicks / 20 + "s", NamedTextColor.AQUA, TextDecoration.BOLD));
        }
        // Cooldowns
        if (hero.dashCdLeft > 0) {
            builder.append(Component.text("   dash " + (hero.dashCdLeft / 20 + 1) + "s", NamedTextColor.DARK_GRAY));
        } else {
            builder.append(Component.text("   [dash ready]", NamedTextColor.DARK_GREEN));
        }
        return builder.build();
    }
}
