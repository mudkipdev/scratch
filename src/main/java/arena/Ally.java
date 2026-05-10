package arena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.scratch.entity.MetaHolder;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.network.packet.server.play.*;

import java.util.List;
import java.util.UUID;

/**
 * Ally: a small NPC that targets the nearest enemy and attacks on contact.
 * Far cheaper than Enemy because they don't need to ranged-shoot or contribute to crowd avoidance.
 */
final class Ally {
    final ArenaServer arena;
    final int id;
    final UUID uuid = UUID.randomUUID();
    final Kind kind;
    final Hero owner;
    Pos position;
    Vec velocity = Vec.ZERO;
    int hp;
    final int maxHp;
    int attackCooldown = 0;
    int lifetimeTicks;
    Pos lastSentPos;
    int sinceTeleport = 0;

    final MetaHolder metaHolder;
    final Broadcast.World.Entry entry;

    Ally(ArenaServer arena, Hero owner, Kind kind, Pos position) {
        this.arena = arena;
        this.owner = owner;
        this.kind = kind;
        this.id = arena.lastEntityId.incrementAndGet();
        this.position = position;
        this.lastSentPos = position;
        this.maxHp = kind.hp;
        this.hp = maxHp;
        this.lifetimeTicks = kind.lifetimeTicks;

        this.metaHolder = new MetaHolder(id);
        metaHolder.set(MetadataDef.LivingEntity.HEALTH, (float) hp);
        metaHolder.set(MetadataDef.HAS_GLOWING_EFFECT, true);
        metaHolder.set(MetadataDef.CUSTOM_NAME, Component.text(kind.label, NamedTextColor.AQUA));
        metaHolder.set(MetadataDef.CUSTOM_NAME_VISIBLE, true);

        this.entry = arena.synchronizer.makeEntry(id, position,
                () -> List.of(new SpawnEntityPacket(id, uuid, kind.entity, position, 0f, 0, Vec.ZERO),
                        metaHolder.metaDataPacket()),
                () -> List.of(new DestroyEntitiesPacket(id)));
    }

    boolean tick() {
        if (--lifetimeTicks <= 0 || hp <= 0) return false;
        if (attackCooldown > 0) attackCooldown--;

        Enemy target = arena.enemyGrid.nearest(position.x(), position.z(), 30);
        if (target == null) {
            // Follow owner
            if (owner != null && owner.alive) {
                double dx = owner.position.x() - position.x();
                double dz = owner.position.z() - position.z();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 4 && dist > 0.001) {
                    velocity = new Vec(dx / dist * kind.speed, 0, dz / dist * kind.speed);
                } else {
                    velocity = Vec.ZERO;
                }
            }
        } else {
            double dx = target.position.x() - position.x();
            double dz = target.position.z() - position.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 0.001) {
                velocity = new Vec(dx / dist * kind.speed, 0, dz / dist * kind.speed);
                final float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                position = position.withView(yaw, 0);
            }
            if (dist <= kind.attackRadius && attackCooldown == 0) {
                attackCooldown = kind.attackCooldown;
                target.takeDamage(kind.damage, false, owner, position);
                Effects.localSound(arena, position, kind.attackSound, 0.6f, 1.0f);
            }
        }

        position = position.add(velocity.x(), 0, velocity.z());
        entry.move(position);
        sinceTeleport++;
        if (sinceTeleport >= 20 || lastSentPos.distanceSquared(position) > 64) {
            entry.signalLocal(new EntityTeleportPacket(id, position, Vec.ZERO, 0, true));
            lastSentPos = position;
            sinceTeleport = 0;
        } else {
            entry.signalLocal(EntityPositionAndRotationPacket.getPacket(id, position, lastSentPos, true));
            entry.signalLocal(new EntityHeadLookPacket(id, position.yaw()));
            lastSentPos = position;
        }
        return true;
    }

    void unregister() {
        Effects.burstDeath(arena, position);
        Effects.localSound(arena, position, "minecraft:entity.iron_golem.death", 0.5f, 1.4f);
        arena.allies.remove(id);
        entry.unmake();
    }

    enum Kind {
        WOLF(EntityType.WOLF, 30, 6, 0.18, 1.5, 8, "Wolf",
                "minecraft:entity.wolf.attack", 60 * 20),
        GOLEM(EntityType.IRON_GOLEM, 80, 14, 0.10, 2.0, 18, "Iron Guardian",
                "minecraft:entity.iron_golem.attack", 60 * 20);

        final EntityType entity;
        final int hp;
        final int damage;
        final double speed;
        final double attackRadius;
        final int attackCooldown;
        final String label;
        final String attackSound;
        final int lifetimeTicks;

        Kind(EntityType entity, int hp, int damage, double speed, double attackRadius,
             int attackCooldown, String label, String attackSound, int lifetimeTicks) {
            this.entity = entity;
            this.hp = hp;
            this.damage = damage;
            this.speed = speed;
            this.attackRadius = attackRadius;
            this.attackCooldown = attackCooldown;
            this.label = label;
            this.attackSound = attackSound;
            this.lifetimeTicks = lifetimeTicks;
        }
    }
}
