package arena;

import net.minestom.scratch.entity.MetaHolder;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;

import java.util.List;
import java.util.UUID;

/**
 * Simple straight-line projectile (skeleton arrow). Hit-checks against the nearest hero each tick.
 */
final class Projectile {
    static final double SPEED = 0.7;
    static final double HIT_RADIUS = 1.0;
    static final int MAX_TICKS = 60;

    static void spawn(ArenaServer arena, Pos from, Pos toward, int damage, Enemy owner) {
        new Projectile(arena, from, toward, damage, owner);
    }

    final ArenaServer arena;
    final int id;
    final UUID uuid = UUID.randomUUID();
    final int damage;
    final Enemy owner;
    Pos position;
    Vec velocity;
    int ticksAlive = 0;
    final Broadcast.World.Entry entry;

    private Projectile(ArenaServer arena, Pos from, Pos toward, int damage, Enemy owner) {
        this.arena = arena;
        this.id = arena.lastEntityId.incrementAndGet();
        this.position = from;
        this.damage = damage;
        this.owner = owner;
        Vec dir = new Vec(toward.x() - from.x(), toward.y() - from.y(), toward.z() - from.z()).normalize();
        this.velocity = dir.mul(SPEED);
        Pos spawnPos = from.withDirection(velocity);
        MetaHolder meta = new MetaHolder(id);
        meta.set(MetadataDef.HAS_NO_GRAVITY, true);
        this.entry = arena.synchronizer.makeEntry(id, position,
                () -> List.of(new SpawnEntityPacket(id, uuid, EntityType.ARROW, spawnPos, 0f, 0, velocity),
                        meta.metaDataPacket()),
                () -> List.of(new DestroyEntitiesPacket(id)));
        arena.projectiles.put(id, this);
    }

    boolean tick() {
        if (++ticksAlive > MAX_TICKS) return false;
        position = position.add(velocity);
        entry.move(position);
        entry.signalLocal(new EntityTeleportPacket(id, position.withDirection(velocity), Vec.ZERO, 0, false));

        // Hit-test: nearest hero within HIT_RADIUS
        Hero target = arena.heroGrid.nearest(position.x(), position.z(), HIT_RADIUS + 1.0);
        if (target != null && target.alive) {
            double dx = target.position.x() - position.x();
            double dy = target.position.y() + 1.0 - position.y();
            double dz = target.position.z() - position.z();
            if (dx * dx + dy * dy + dz * dz <= HIT_RADIUS * HIT_RADIUS) {
                target.takeDamage(damage, position);
                return false;
            }
        }
        return true;
    }

    void unregister() {
        entry.unmake();
        arena.projectiles.remove(id);
    }
}
