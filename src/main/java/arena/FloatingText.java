package arena;

import net.kyori.adventure.text.Component;
import net.minestom.scratch.entity.MetaHolder;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;

import java.util.List;
import java.util.UUID;

/**
 * Self-destructing TEXT_DISPLAY entity. Floats up and despawns after a few ticks.
 */
final class FloatingText {

    static void spawn(ArenaServer arena, Pos at, Component text, int lifeTicks) {
        new Instance(arena, at, text, lifeTicks);
    }

    static final class Instance {
        final ArenaServer arena;
        final int id;
        final UUID uuid = UUID.randomUUID();
        final MetaHolder meta;
        final Broadcast.World.Entry entry;
        Pos position;
        int ticksLeft;

        Instance(ArenaServer arena, Pos at, Component text, int lifeTicks) {
            this.arena = arena;
            this.id = arena.lastEntityId.incrementAndGet();
            this.position = at;
            this.ticksLeft = lifeTicks;
            this.meta = new MetaHolder(id);
            meta.set(MetadataDef.HAS_NO_GRAVITY, true);
            meta.set(MetadataDef.TextDisplay.TEXT, text);
            meta.set(MetadataDef.TextDisplay.HAS_SHADOW, true);
            meta.set(MetadataDef.TextDisplay.IS_SEE_THROUGH, true);
            meta.set(MetadataDef.Display.BILLBOARD_CONSTRAINTS, (byte) 3); // CENTER

            this.entry = arena.synchronizer.makeEntry(id, position,
                    () -> List.of(
                            new SpawnEntityPacket(id, uuid, EntityType.TEXT_DISPLAY, position, 0f, 0, Vec.ZERO),
                            meta.metaDataPacket()),
                    () -> List.of(new DestroyEntitiesPacket(id)));
            arena.floatingTexts.put(id, this);
        }

        boolean tick() {
            if (--ticksLeft <= 0) return false;
            this.position = position.add(0, 0.06, 0);
            entry.move(position);
            entry.signalLocal(new EntityTeleportPacket(id, position, Vec.ZERO, 0, false));
            return true;
        }

        void unregister() {
            entry.unmake();
            arena.floatingTexts.remove(id);
        }
    }
}
