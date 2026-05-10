package arena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.network.packet.server.play.SoundEffectPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;

/**
 * Tiny helper to send particles, sounds, and titles. Pure stateless utility.
 */
final class Effects {

    static void playSound(ArenaServer arena, Hero target, String key, float volume, float pitch) {
        final SoundEvent event = SoundEvent.fromKey(key);
        if (event == null) return;
        target.sendPacket(new SoundEffectPacket(event, net.kyori.adventure.sound.Sound.Source.MASTER,
                target.position, volume, pitch, 0L));
    }

    static void localSound(ArenaServer arena, Pos at, String key, float volume, float pitch) {
        final SoundEvent event = SoundEvent.fromKey(key);
        if (event == null) return;
        arena.synchronizer.signalAt(at, new SoundEffectPacket(event,
                net.kyori.adventure.sound.Sound.Source.HOSTILE, at, volume, pitch, 0L));
    }

    static void localParticle(ArenaServer arena, Pos at, Particle particle, int count, float spread, float speed) {
        arena.synchronizer.signalAt(at, new ParticlePacket(particle, true, false,
                at, Vec.ZERO, spread, count));
    }

    static void localParticleOffset(ArenaServer arena, Pos at, Particle particle, int count,
                                    float ox, float oy, float oz, float speed) {
        arena.synchronizer.signalAt(at, new ParticlePacket(particle, true, false, at.x(), at.y(), at.z(),
                ox, oy, oz, speed, count));
    }

    static void burstHit(ArenaServer arena, Pos at) {
        localParticleOffset(arena, at.add(0, 0.8, 0), Particle.CRIT, 6, 0.3f, 0.3f, 0.3f, 0.4f);
        localParticleOffset(arena, at.add(0, 0.8, 0), Particle.DAMAGE_INDICATOR, 3, 0.2f, 0.2f, 0.2f, 0.1f);
    }

    static void burstDeath(ArenaServer arena, Pos at) {
        localParticleOffset(arena, at.add(0, 0.5, 0), Particle.SMOKE, 12, 0.4f, 0.4f, 0.4f, 0.05f);
        localParticleOffset(arena, at.add(0, 0.5, 0), Particle.POOF, 6, 0.3f, 0.3f, 0.3f, 0.05f);
    }

    static void burstLevelUp(ArenaServer arena, Pos at) {
        localParticleOffset(arena, at.add(0, 1, 0), Particle.HAPPY_VILLAGER, 30, 0.6f, 1.0f, 0.6f, 0.05f);
        localParticleOffset(arena, at.add(0, 1, 0), Particle.END_ROD, 16, 0.4f, 0.7f, 0.4f, 0.1f);
    }

    static void aoeRing(ArenaServer arena, Pos center, double radius) {
        final int steps = Math.max(12, (int) (radius * 8));
        for (int i = 0; i < steps; i++) {
            final double a = i * Math.PI * 2 / steps;
            final double x = center.x() + Math.cos(a) * radius;
            final double z = center.z() + Math.sin(a) * radius;
            arena.synchronizer.signalAt(center, new ParticlePacket(Particle.SWEEP_ATTACK, true, false,
                    x, center.y() + 0.2, z, 0f, 0f, 0f, 0f, 1));
        }
    }

    static void explosion(ArenaServer arena, Pos at, double radius) {
        final int rings = Math.max(2, (int) radius);
        for (int r = 1; r <= rings; r++) {
            final int steps = 12 + r * 4;
            for (int i = 0; i < steps; i++) {
                final double a = i * Math.PI * 2 / steps;
                final double x = at.x() + Math.cos(a) * r;
                final double z = at.z() + Math.sin(a) * r;
                arena.synchronizer.signalAt(at, new ParticlePacket(Particle.EXPLOSION, true, true,
                        x, at.y() + 0.5, z, 0f, 0f, 0f, 0f, 1));
            }
        }
        arena.synchronizer.signalAt(at, new ParticlePacket(Particle.FLASH, true, true,
                at.x(), at.y() + 1, at.z(), 0f, 0f, 0f, 0f, 1));
        localSound(arena, at, "minecraft:entity.generic.explode", 1.5f, 1.0f);
    }

    static void floatingDamageNumber(ArenaServer arena, Pos at, int damage, boolean crit) {
        Component text = Component.text(damage, crit ? NamedTextColor.GOLD : NamedTextColor.RED,
                crit ? TextDecoration.BOLD : TextDecoration.ITALIC);
        FloatingText.spawn(arena, at.add(0, 1.7 + Math.random() * 0.3, 0), text, crit ? 12 : 8);
    }

    static void floatingHealNumber(ArenaServer arena, Pos at, int amount) {
        Component text = Component.text("+" + amount, NamedTextColor.GREEN, TextDecoration.BOLD);
        FloatingText.spawn(arena, at.add(0, 2.0, 0), text, 14);
    }

    static void floatingExpNumber(ArenaServer arena, Pos at, int amount) {
        Component text = Component.text("+" + amount + " XP", NamedTextColor.AQUA);
        FloatingText.spawn(arena, at.add(0, 2.0, 0), text, 12);
    }
}
