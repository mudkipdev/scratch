package arena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.scratch.entity.MetaHolder;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.particle.Particle;

import java.util.List;
import java.util.UUID;

/**
 * Lightweight enemy: simplified physics (flat ground), straight-line chase pathing,
 * and contact damage. Designed to scale to thousands of instances.
 */
final class Enemy {
    final ArenaServer arena;
    final int id;
    final UUID uuid = UUID.randomUUID();
    final Kind kind;

    Pos position;
    Vec velocity = Vec.ZERO;
    int hp;
    final int maxHp;
    final double speed;
    final int damage;
    final int xpReward;
    final double aggroRadius = 60.0;
    final double attackRadius;

    int attackCooldown = 0;
    int hurtTicks = 0;
    int rangedCooldown;

    final MetaHolder metaHolder;
    final Broadcast.World.Entry entry;

    int lastTargetId = -1;
    Pos lastSentPos;
    int sinceTeleport = 0;

    Enemy(ArenaServer arena, Kind kind, Pos position, int wave) {
        this.arena = arena;
        this.kind = kind;
        this.id = arena.lastEntityId.incrementAndGet();
        this.position = position;
        this.lastSentPos = position;
        this.maxHp = scaleHp(kind, wave);
        this.hp = maxHp;
        this.speed = kind.speed;
        this.damage = scaleDamage(kind, wave);
        this.xpReward = kind.xpReward + wave;
        this.attackRadius = kind.ranged ? 14.0 : 1.6;
        this.rangedCooldown = 30 + (int) (Math.random() * 30);

        this.metaHolder = new MetaHolder(id);
        metaHolder.set(MetadataDef.LivingEntity.HEALTH, (float) hp);
        if (kind == Kind.BOSS) {
            metaHolder.set(MetadataDef.HAS_GLOWING_EFFECT, true);
            metaHolder.set(MetadataDef.CUSTOM_NAME, Component.text("⚔ WARLORD ⚔", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            metaHolder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);
        } else if (kind == Kind.HEAVY) {
            metaHolder.set(MetadataDef.CUSTOM_NAME, Component.text("Brute", NamedTextColor.RED));
            metaHolder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);
        }

        this.entry = arena.synchronizer.makeEntry(id, position,
                () -> List.of(new SpawnEntityPacket(id, uuid, kind.entity, position, 0f, 0, Vec.ZERO),
                        metaHolder.metaDataPacket()),
                () -> List.of(new DestroyEntitiesPacket(id)));
    }

    private static int scaleHp(Kind kind, int wave) {
        return (int) (kind.baseHp * (1.0 + wave * 0.18));
    }

    private static int scaleDamage(Kind kind, int wave) {
        return (int) (kind.baseDamage + wave * 0.4);
    }

    boolean tick() {
        if (hurtTicks > 0) hurtTicks--;
        if (attackCooldown > 0) attackCooldown--;
        if (kind.ranged && rangedCooldown > 0) rangedCooldown--;

        // Find target hero
        Hero target = arena.heroGrid.nearest(position.x(), position.z(), aggroRadius);
        if (target == null) {
            // Idle wander
            if (arena.tickCount % 20 == 0) {
                final double a = Math.random() * Math.PI * 2;
                this.velocity = new Vec(Math.cos(a) * speed * 0.4, 0, Math.sin(a) * speed * 0.4);
            }
        } else {
            this.lastTargetId = target.id;
            double dx = target.position.x() - position.x();
            double dz = target.position.z() - position.z();
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > 0.001) {
                // Charger sprint when in line of sight
                double effectiveSpeed = speed;
                if (kind == Kind.CHARGER && dist < 18) effectiveSpeed *= 1.6;
                if (kind.ranged && dist < 8) {
                    // Back away if too close
                    effectiveSpeed = -speed * 0.5;
                }
                this.velocity = new Vec(dx / dist * effectiveSpeed, 0, dz / dist * effectiveSpeed);

                // Face target
                final float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                this.position = position.withView(yaw, 0);
            }

            // Melee contact
            if (!kind.ranged && dist <= attackRadius && attackCooldown == 0) {
                attackCooldown = kind.attackCooldown;
                target.takeDamage(damage, position);
                if (kind == Kind.BOSS) {
                    Effects.localSound(arena, position, "minecraft:entity.warden.attack_impact", 1.0f, 0.6f);
                } else {
                    Effects.localSound(arena, position, "minecraft:entity.zombie.attack_iron_door", 0.5f, 1.5f);
                }
            }
            // Ranged shoot
            if (kind.ranged && rangedCooldown == 0 && dist < 18) {
                rangedCooldown = 50;
                Projectile.spawn(arena, position.add(0, 1.2, 0), target.position.add(0, 1.0, 0), damage, this);
                Effects.localSound(arena, position, "minecraft:entity.skeleton.shoot", 0.7f, 1.0f);
            }
        }

        // Crowd avoidance: nudge away from neighboring enemies (cheap)
        if (arena.tickCount % 2 == (id & 1)) {
            double[] push = {0, 0};
            arena.enemyGrid.forEachInRange(position.x(), position.z(), 1.2, other -> {
                if (other == this) return;
                double dx = position.x() - other.position.x();
                double dz = position.z() - other.position.z();
                double d2 = dx * dx + dz * dz;
                if (d2 < 0.0001 || d2 > 1.44) return;
                double inv = 1.0 / Math.sqrt(d2);
                push[0] += dx * inv * 0.05;
                push[1] += dz * inv * 0.05;
            });
            this.velocity = velocity.add(push[0], 0, push[1]);
        }

        // Move (flat ground = trivial)
        this.position = position.add(velocity.x(), 0, velocity.z());

        // Synchronize
        entry.move(position);
        sinceTeleport++;
        // Use delta packet for cheap moves; teleport every 20 ticks for resync
        if (sinceTeleport >= 20 || lastSentPos.distanceSquared(position) > 64) {
            entry.signalLocal(new EntityTeleportPacket(id, position, Vec.ZERO, 0, true));
            lastSentPos = position;
            sinceTeleport = 0;
        } else {
            // Position+rotation delta
            entry.signalLocal(EntityPositionAndRotationPacket.getPacket(id, position, lastSentPos, true));
            entry.signalLocal(new EntityHeadLookPacket(id, position.yaw()));
            lastSentPos = position;
        }
        return hp > 0;
    }

    void takeDamage(int amount, boolean crit, Hero attacker, Pos sourcePos) {
        if (hp <= 0) return;
        hp -= amount;
        hurtTicks = 6;
        metaHolder.set(MetadataDef.LivingEntity.HEALTH, (float) Math.max(0, hp));

        // Knockback away from attacker
        final double dx = position.x() - sourcePos.x();
        final double dz = position.z() - sourcePos.z();
        final double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.001) {
            final double mag = (kind == Kind.BOSS ? 0.15 : 0.5);
            final Vec kb = new Vec(dx / d * mag, 0, dz / d * mag);
            this.position = position.add(kb.x(), 0, kb.z());
            entry.signalLocal(new EntityVelocityPacket(id, kb.mul(8000)));
        }

        entry.signalLocal(new DamageEventPacket(id, 1, attacker.id, attacker.id, sourcePos));
        entry.signalLocal(new HitAnimationPacket(id, (float) Math.toDegrees(Math.atan2(dz, dx))));
        Effects.burstHit(arena, position);
        Effects.floatingDamageNumber(arena, position, amount, crit);
        Effects.localSound(arena, position, kind.hurtSound, 0.6f, 0.8f + (float) Math.random() * 0.4f);

        if (hp <= 0) onDeath(attacker);
    }

    private void onDeath(Hero killer) {
        Effects.burstDeath(arena, position);
        Effects.localSound(arena, position, kind.deathSound, 0.7f, 1.0f);
        if (kind == Kind.BOSS) {
            Effects.explosion(arena, position, 5);
            Effects.localParticleOffset(arena, position, Particle.FLAME, 50, 1.0f, 1.0f, 1.0f, 0.2f);
        }

        if (killer != null && killer.alive) {
            killer.kills++;
            arena.totalKills++;
            killer.grantXp(xpReward);
            // Drop loot
            int rolls = (kind == Kind.BOSS ? 8 : (Math.random() < 0.35 ? 1 : 0));
            for (int i = 0; i < rolls; i++) LootDrop.spawnRandom(arena, position, kind == Kind.BOSS);
        }
    }

    void unregister() {
        arena.enemies.remove(id);
        entry.unmake();
    }

    enum Kind {
        SCOUT(EntityType.HUSK, 16, 4, 0.16, 12, 6, false, "minecraft:entity.husk.hurt", "minecraft:entity.husk.death"),
        GRUNT(EntityType.ZOMBIE, 24, 6, 0.13, 14, 10, false, "minecraft:entity.zombie.hurt", "minecraft:entity.zombie.death"),
        HEAVY(EntityType.ZOMBIE_VILLAGER, 60, 10, 0.10, 18, 18, false, "minecraft:entity.zombie_villager.hurt", "minecraft:entity.zombie_villager.death"),
        CHARGER(EntityType.WITHER_SKELETON, 22, 8, 0.18, 10, 14, false, "minecraft:entity.wither_skeleton.hurt", "minecraft:entity.wither_skeleton.death"),
        RANGED(EntityType.SKELETON, 18, 5, 0.10, 999, 12, true, "minecraft:entity.skeleton.hurt", "minecraft:entity.skeleton.death"),
        BOSS(EntityType.WARDEN, 800, 18, 0.09, 16, 200, false, "minecraft:entity.warden.hurt", "minecraft:entity.warden.death");

        final EntityType entity;
        final int baseHp;
        final int baseDamage;
        final double speed;
        final int attackCooldown;
        final int xpReward;
        final boolean ranged;
        final String hurtSound;
        final String deathSound;

        Kind(EntityType entity, int baseHp, int baseDamage, double speed, int attackCooldown,
             int xpReward, boolean ranged, String hurtSound, String deathSound) {
            this.entity = entity;
            this.baseHp = baseHp;
            this.baseDamage = baseDamage;
            this.speed = speed;
            this.attackCooldown = attackCooldown;
            this.xpReward = xpReward;
            this.ranged = ranged;
            this.hurtSound = hurtSound;
            this.deathSound = deathSound;
        }
    }
}
