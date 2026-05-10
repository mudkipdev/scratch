package arena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.scratch.entity.MetaHolder;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.scratch.network.NetworkContext;
import net.minestom.scratch.registry.ScratchRegistryTools;
import net.minestom.server.coordinate.ChunkRange;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.network.packet.server.play.data.WorldPos;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The user-controlled hero. Stat sheet, packet plumbing, and combat input.
 */
final class Hero {
    private static final int VIEW = ArenaServer.VIEW_DISTANCE;
    static final double SWING_RADIUS = 4.0;
    static final double SWING_ARC_DEG = 110.0;
    static final int SWING_COOLDOWN = 4;
    static final int DASH_COOLDOWN = 60;

    final ArenaServer arena;
    final ArenaServer.Connection connection;
    final int id;
    final String username;
    final UUID uuid;

    Pos position;
    Pos oldPosition;
    Vec velocity = Vec.ZERO;
    boolean alive = true;

    // Combat stats
    int level = 1;
    int xp = 0;
    int maxHp = 40;
    float hp = 40f;
    int baseDamage = 8;
    double critChance = 0.1;
    double swingRadius = SWING_RADIUS;
    int strengthTicks = 0;
    int speedTicks = 0;
    int swingCdLeft = 0;
    int dashCdLeft = 0;
    int kills = 0;
    int comboHits = 0;
    int comboTicksLeft = 0;
    int respawnTicks = 0;

    final MetaHolder metaHolder;
    final Broadcast.World.Entry entry;
    /** Suppress meta-update broadcasts until the client has finished receiving JoinGamePacket. */
    private boolean metaReady = false;

    final Supplier<List<ServerPacket.Play>> initSupplier;
    final Supplier<List<ServerPacket.Play>> destroySupplier;
    final BiConsumer<List<ServerPacket.Play>, int[]> packetsConsumer;

    Hero(ArenaServer arena, ArenaServer.PlayerInfo info, Pos spawn) {
        this.arena = arena;
        this.connection = info.connection();
        this.username = info.username();
        this.uuid = info.uuid();
        this.id = arena.lastEntityId.incrementAndGet();
        this.position = spawn;
        this.oldPosition = spawn;
        this.metaHolder = new MetaHolder(id, this::onMetaUpdate);
        // Pre-init values so the spawn packet carries them. metaReady is false so this does not
        // emit any EntityMetaDataPacket — important: the client has no level yet, sending one
        // before JoinGamePacket NPEs vanilla's packet handler.
        metaHolder.set(MetadataDef.LivingEntity.HEALTH, hp);

        this.initSupplier = () -> {
            final SpawnEntityPacket spawnPacket = new SpawnEntityPacket(id, uuid, EntityType.PLAYER, position, 0, 0, Vec.ZERO);
            return List.of(addPlayerListEntry(), spawnPacket,
                    new EntityEquipmentPacket(id, equipmentMap()),
                    metaHolder.metaDataPacket());
        };
        this.destroySupplier = () -> List.of(new DestroyEntitiesPacket(id));
        this.packetsConsumer = (plays, exception) ->
                connection.networkContext.write(new NetworkContext.Packet.PlayList(plays, exception));

        this.entry = arena.synchronizer.makeReceiver(id, position, initSupplier, destroySupplier, packetsConsumer);

        connection.networkContext.writePlays(joinPackets());
        // Now safe to emit meta updates: JoinGamePacket and SpawnEntity are queued ahead of any further packets.
        this.metaReady = true;
    }

    void broadcastJoin() {
        // Title splash
        sendPacket(new SetTitleTextPacket(Component.text("✦ ARENA ✦", NamedTextColor.GOLD, TextDecoration.BOLD)));
        sendPacket(new SetTitleSubTitlePacket(Component.text("Slash. Swarm. Survive.", NamedTextColor.YELLOW)));
        sendPacket(new SetTitleTimePacket(10, 70, 30));
        Effects.playSound(arena, this, "minecraft:ui.toast.challenge_complete", 0.8f, 1.0f);
        // BossBar XP track
        Hud.installBossBar(this);
        Hud.installComboBar(this);
        sendPacket(new SystemChatPacket(Component.text(username + " joined the arena.", NamedTextColor.YELLOW), false));
    }

    void sendPacket(ServerPacket.Play packet) {
        connection.networkContext.write(packet);
    }

    Map<EquipmentSlot, ItemStack> equipmentMap() {
        final Map<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        map.put(EquipmentSlot.MAIN_HAND, weaponForLevel());
        map.put(EquipmentSlot.HELMET, ItemStack.of(Material.IRON_HELMET));
        map.put(EquipmentSlot.CHESTPLATE, ItemStack.of(Material.IRON_CHESTPLATE));
        map.put(EquipmentSlot.LEGGINGS, ItemStack.of(Material.IRON_LEGGINGS));
        map.put(EquipmentSlot.BOOTS, ItemStack.of(Material.IRON_BOOTS));
        return map;
    }

    private ItemStack weaponForLevel() {
        if (level >= 20) return ItemStack.of(Material.NETHERITE_SWORD);
        if (level >= 12) return ItemStack.of(Material.DIAMOND_SWORD);
        if (level >= 6) return ItemStack.of(Material.IRON_SWORD);
        return ItemStack.of(Material.STONE_SWORD);
    }

    PlayerInfoUpdatePacket addPlayerListEntry() {
        final var infoEntry = new PlayerInfoUpdatePacket.Entry(uuid, username, List.of(),
                true, 1, GameMode.ADVENTURE, null, null, id, true);
        return new PlayerInfoUpdatePacket(EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER, PlayerInfoUpdatePacket.Action.UPDATE_LISTED),
                List.of(infoEntry));
    }

    private List<ServerPacket.Play> joinPackets() {
        List<ServerPacket.Play> packets = new ArrayList<>();
        final DimensionType dim = arena.world.dimensionType();
        final RegistryKey<DimensionType> dimKey = ScratchRegistryTools.DIMENSION_TYPE.getKey(dim);
        final int dimId = ScratchRegistryTools.DIMENSION_TYPE.getId(dimKey);

        packets.add(new JoinGamePacket(id, false, List.of(), 0,
                VIEW, VIEW, false, true, false,
                dimId, dimKey.name(), 0L, GameMode.ADVENTURE, null, false, true,
                new WorldPos(dimKey.name(), Vec.ZERO), 0, 0, false));
        packets.add(new SpawnPositionPacket(new WorldPos(dimKey.name(), position), 0, 0));
        packets.add(new PlayerPositionAndLookPacket(0, position, Vec.ZERO, position.yaw(), position.pitch(), (byte) 0));
        packets.add(addPlayerListEntry());
        packets.add(new UpdateViewDistancePacket(VIEW));
        packets.add(new UpdateViewPositionPacket(position.chunkX(), position.chunkZ()));
        ChunkRange.chunksInRange(position.chunkX(), position.chunkZ(), VIEW,
                (cx, cz) -> packets.add(arena.world.chunkPacket(cx, cz)));
        packets.add(new ChangeGameStatePacket(ChangeGameStatePacket.Reason.LEVEL_CHUNKS_LOAD_START, 0f));

        // Hotbar slot 0: sword (visual only, locked)
        SetSlotPacket sword = new SetSlotPacket((byte) 0, 0, (short) 36, weaponForLevel());
        packets.add(sword);
        packets.add(new HeldItemChangePacket((byte) 0));
        packets.add(new UpdateHealthPacket(hp / 2f, 20, 5f));
        packets.add(new SetExperiencePacket(0f, level, totalXp()));
        return packets;
    }

    int totalXp() {
        return xp + (level - 1) * 100;
    }

    /** Returns false if the connection died. */
    boolean tick() {
        if (!connection.online) return false;

        // Drain client packets
        connection.packetQueue.drain(this::handle);

        // Decrement cooldowns
        if (swingCdLeft > 0) swingCdLeft--;
        if (dashCdLeft > 0) dashCdLeft--;
        if (strengthTicks > 0) strengthTicks--;
        if (speedTicks > 0) speedTicks--;
        if (comboTicksLeft > 0) comboTicksLeft--;
        else comboHits = 0;

        // Respawn handling
        if (!alive) {
            if (--respawnTicks <= 0) respawn();
        } else {
            // Passive regen, but slow
            if (arena.tickCount % 40 == 0 && hp < maxHp) {
                hp = Math.min(maxHp, hp + 1);
                sendPacket(new UpdateHealthPacket(hp / 2f, 20, 5f));
                metaHolder.set(MetadataDef.LivingEntity.HEALTH, hp);
            }
        }

        this.oldPosition = position;
        return true;
    }

    private void handle(ClientPacket packet) {
        if (!alive && !(packet instanceof ClientChatMessagePacket)) {
            // While dead we still drain to avoid stalling but ignore most
            return;
        }
        switch (packet) {
            case ClientPlayerPositionAndRotationPacket p -> updatePosition(p.position(), p.onGround());
            case ClientPlayerPositionPacket p -> updatePosition(position.withCoord(p.position()), p.onGround());
            case ClientPlayerRotationPacket p -> {
                this.position = position.withView(p.yaw(), p.pitch());
                entry.signalLocal(new EntityRotationPacket(id, p.yaw(), p.pitch(), p.onGround()));
                entry.signalLocal(new EntityHeadLookPacket(id, p.yaw()));
            }
            case ClientAnimationPacket p -> {
                entry.signalLocal(new EntityAnimationPacket(id,
                        p.hand() == PlayerHand.OFF ? EntityAnimationPacket.Animation.SWING_OFF_HAND
                                : EntityAnimationPacket.Animation.SWING_MAIN_ARM));
                attemptSwing();
            }
            case ClientInteractEntityPacket p -> {
                if (p.type() instanceof ClientInteractEntityPacket.Attack) attemptSwing();
            }
            case ClientPlayerActionPacket p -> {
                switch (p.status()) {
                    case SWAP_ITEM_HAND -> attemptDash();
                    case DROP_ITEM, DROP_ITEM_STACK -> attemptBomb();
                    default -> {
                    }
                }
            }
            case ClientUseItemPacket ignored -> attemptDash();
            case ClientChatMessagePacket p -> {
                Component msg = Component.text("<" + username + "> ", NamedTextColor.YELLOW)
                        .append(Component.text(p.message(), NamedTextColor.WHITE));
                for (Hero h : arena.heroes.values()) h.sendPacket(new SystemChatPacket(msg, false));
            }
            default -> {
            }
        }
    }

    private void updatePosition(Pos pos, boolean onGround) {
        // Prevent leaving floor
        if (pos.y() < ArenaServer.FLOOR_Y - 5) {
            pos = pos.withY(ArenaServer.FLOOR_Y);
            sendPacket(new PlayerPositionAndLookPacket(0, pos, Vec.ZERO, pos.yaw(), pos.pitch(), (byte) 0));
        }
        final Pos previous = this.position;
        this.position = pos;
        entry.move(pos);
        entry.signalLocal(new EntityTeleportPacket(id, pos, Vec.ZERO, 0, onGround));
        entry.signalLocal(new EntityHeadLookPacket(id, pos.yaw()));

        // Stream new chunks if we crossed a chunk border
        if (!pos.sameChunk(previous)) {
            sendPacket(new UpdateViewPositionPacket(pos.chunkX(), pos.chunkZ()));
            ChunkRange.chunksInRangeDiffering(pos.chunkX(), pos.chunkZ(),
                    previous.chunkX(), previous.chunkZ(), VIEW,
                    (cx, cz) -> sendPacket(arena.world.chunkPacket(cx, cz)),
                    (cx, cz) -> sendPacket(new UnloadChunkPacket(cx, cz)));
        }

        // Pickup nearby loot
        arena.drops.values().forEach(drop -> drop.tryPickup(this));
    }

    private void attemptSwing() {
        if (!alive) return;
        if (swingCdLeft > 0) return;
        swingCdLeft = SWING_COOLDOWN;

        final double radius = swingRadius + (strengthTicks > 0 ? 1.0 : 0.0);
        final double yawRad = Math.toRadians(position.yaw() + 90); // forward direction
        final double fx = Math.cos(yawRad);
        final double fz = Math.sin(yawRad);

        Effects.aoeRing(arena, position, radius);
        Effects.localSound(arena, position, "minecraft:entity.player.attack.sweep", 0.7f, 1.0f + (float) Math.random() * 0.2f);

        final List<Enemy> hits = new ArrayList<>();
        arena.enemyGrid.forEachInRange(position.x(), position.z(), radius, e -> {
            // Cone check: dot product against forward
            double dx = e.position.x() - position.x();
            double dz = e.position.z() - position.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.001) {
                hits.add(e);
                return;
            }
            double dot = (dx / dist) * fx + (dz / dist) * fz;
            double cos = Math.cos(Math.toRadians(SWING_ARC_DEG / 2));
            if (dot >= cos) hits.add(e);
        });

        if (hits.isEmpty()) return;

        comboHits++;
        comboTicksLeft = 40;

        for (Enemy enemy : hits) {
            int dmg = baseDamage + level / 2 + (strengthTicks > 0 ? 6 : 0) + comboHits / 5;
            boolean crit = Math.random() < critChance;
            if (crit) dmg = (int) Math.round(dmg * 1.7);
            enemy.takeDamage(dmg, crit, this, position);
        }
    }

    private void attemptDash() {
        if (!alive) return;
        if (dashCdLeft > 0) return;
        dashCdLeft = DASH_COOLDOWN;
        final double yawRad = Math.toRadians(position.yaw() + 90);
        final Vec push = new Vec(Math.cos(yawRad) * 1.6, 0.4, Math.sin(yawRad) * 1.6);
        sendPacket(new EntityVelocityPacket(id, push.mul(8000)));
        entry.signalLocal(new EntityVelocityPacket(id, push.mul(8000)));
        Effects.localSound(arena, position, "minecraft:entity.warden.sonic_charge", 0.6f, 2.0f);
        Effects.localParticleOffset(arena, position, net.minestom.server.particle.Particle.CLOUD,
                10, 0.3f, 0.1f, 0.3f, 0.05f);
    }

    private void attemptBomb() {
        if (!alive) return;
        // Drop a stack of TNT-like AoE bomb if any in inventory? We just unconditionally explode.
        final double yawRad = Math.toRadians(position.yaw() + 90);
        final Pos at = position.add(Math.cos(yawRad) * 2.5, 0.5, Math.sin(yawRad) * 2.5);
        Effects.explosion(arena, at, 4.0);
        arena.enemyGrid.forEachInRange(at.x(), at.z(), 4.5, e -> {
            int dmg = (baseDamage + level) * 4;
            e.takeDamage(dmg, true, this, at);
        });
    }

    void takeDamage(int amount, Pos sourcePos) {
        if (!alive) return;
        hp -= amount;
        // Knockback
        final double dx = position.x() - sourcePos.x();
        final double dz = position.z() - sourcePos.z();
        final double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.001) {
            final Vec kb = new Vec(dx / d * 0.4, 0.2, dz / d * 0.4);
            sendPacket(new EntityVelocityPacket(id, kb.mul(8000)));
        }
        // Damage tilt animation for self
        sendPacket(new HitAnimationPacket(id, (float) Math.toDegrees(Math.atan2(dz, dx))));
        sendPacket(new DamageEventPacket(id, 1, 0, 0, sourcePos));
        sendPacket(new UpdateHealthPacket(Math.max(0, hp) / 2f, 20, 5f));
        metaHolder.set(MetadataDef.LivingEntity.HEALTH, Math.max(0, hp));
        Effects.localSound(arena, position, "minecraft:entity.player.hurt", 0.8f, 1.0f);

        if (hp <= 0) die(sourcePos);
    }

    private void die(Pos source) {
        this.alive = false;
        this.respawnTicks = 60;
        Effects.explosion(arena, position, 2.5);
        sendPacket(new SetTitleTextPacket(Component.text("YOU DIED", NamedTextColor.DARK_RED, TextDecoration.BOLD)));
        sendPacket(new SetTitleSubTitlePacket(Component.text("Respawning...", NamedTextColor.GRAY)));
        sendPacket(new SetTitleTimePacket(0, 60, 20));
        for (Hero h : arena.heroes.values()) {
            if (h == this) continue;
            h.sendPacket(new SystemChatPacket(Component.text(username + " was overwhelmed!", NamedTextColor.RED), false));
        }
    }

    private void respawn() {
        this.alive = true;
        this.hp = maxHp;
        this.position = arena.randomSpawn();
        this.comboHits = 0;
        sendPacket(new PlayerPositionAndLookPacket(0, position, Vec.ZERO, 0, 0, (byte) 0));
        sendPacket(new UpdateHealthPacket(hp / 2f, 20, 5f));
        entry.move(position);
        entry.signalLocal(new EntityTeleportPacket(id, position, Vec.ZERO, 0, true));
        sendPacket(new SetTitleTextPacket(Component.text("REVIVED", NamedTextColor.GREEN, TextDecoration.BOLD)));
        sendPacket(new SetTitleTimePacket(0, 30, 10));
        Effects.playSound(arena, this, "minecraft:item.totem.use", 1.0f, 1.0f);
    }

    void grantXp(int amount) {
        xp += amount;
        Effects.floatingExpNumber(arena, position, amount);
        while (xp >= xpForNextLevel()) {
            xp -= xpForNextLevel();
            levelUp();
        }
        sendPacket(new SetExperiencePacket((float) xp / xpForNextLevel(), level, totalXp()));
    }

    int xpForNextLevel() {
        return 50 + level * 25;
    }

    private void levelUp() {
        this.level++;
        this.maxHp += 4;
        this.hp = maxHp;
        this.baseDamage += 2;
        if (level % 5 == 0) this.swingRadius = Math.min(7.0, swingRadius + 0.3);
        if (level % 3 == 0) this.critChance = Math.min(0.6, critChance + 0.03);

        Effects.burstLevelUp(arena, position);
        Effects.playSound(arena, this, "minecraft:entity.player.levelup", 1.0f, 1.0f);
        sendPacket(new SetTitleTextPacket(Component.text("LEVEL " + level, NamedTextColor.GOLD, TextDecoration.BOLD)));
        sendPacket(new SetTitleSubTitlePacket(Component.text("Damage +2  ·  HP refilled",
                TextColor.color(0xFFD700))));
        sendPacket(new SetTitleTimePacket(5, 25, 10));
        sendPacket(new UpdateHealthPacket(hp / 2f, 20, 5f));
        // New weapon visual?
        sendPacket(new EntityEquipmentPacket(id, equipmentMap()));
        entry.signalLocal(new EntityEquipmentPacket(id, equipmentMap()));
        sendPacket(new SetSlotPacket((byte) 0, 0, (short) 36, weaponForLevel()));
    }

    void heal(int amount) {
        if (!alive) return;
        final int actual = (int) Math.min(amount, maxHp - hp);
        if (actual <= 0) return;
        hp += actual;
        sendPacket(new UpdateHealthPacket(hp / 2f, 20, 5f));
        metaHolder.set(MetadataDef.LivingEntity.HEALTH, hp);
        Effects.floatingHealNumber(arena, position, actual);
        Effects.localParticleOffset(arena, position, net.minestom.server.particle.Particle.HEART, 4,
                0.3f, 0.5f, 0.3f, 0.05f);
    }

    void applyStrength(int durationTicks) {
        this.strengthTicks = Math.max(strengthTicks, durationTicks);
        Effects.playSound(arena, this, "minecraft:entity.evoker.cast_spell", 0.6f, 1.4f);
    }

    void applySpeed(int durationTicks) {
        this.speedTicks = Math.max(speedTicks, durationTicks);
        Effects.playSound(arena, this, "minecraft:entity.horse.gallop", 0.5f, 1.2f);
    }

    void updateHud() {
        Hud.update(this);
    }

    private void onMetaUpdate(ServerPacket.Play play) {
        if (!metaReady) return;
        sendPacket(play);
        entry.signalLocal(play);
    }

    void unregister() {
        arena.heroes.remove(id);
        entry.unmake();
        arena.broadcast.broadcast(new PlayerInfoRemovePacket(uuid));
    }
}
