package arena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.scratch.entity.MetaHolder;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.particle.Particle;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A pickupable item bobbing on the ground. On pickup, applies a random buff or stat to the hero.
 */
final class LootDrop {
    private static final Random R = new Random();

    static void spawnRandom(ArenaServer arena, Pos at, boolean boss) {
        final Type[] table = boss ? bossTable() : normalTable();
        final Type type = table[R.nextInt(table.length)];
        new LootDrop(arena, at, type);
    }

    static void spawnSpecific(ArenaServer arena, Pos at, Type type) {
        new LootDrop(arena, at, type);
    }

    final ArenaServer arena;
    final int id;
    final UUID uuid = UUID.randomUUID();
    final Type type;
    Pos position;
    int ageTicks = 0;
    int lifetimeTicks = 30 * 20; // 30 seconds
    final MetaHolder metaHolder;
    final Broadcast.World.Entry entry;
    final ItemStack itemStack;
    Pos lastSentPos;
    int sinceTeleport = 0;
    Vec velocity;

    private LootDrop(ArenaServer arena, Pos at, Type type) {
        this.arena = arena;
        this.type = type;
        this.id = arena.lastEntityId.incrementAndGet();
        // Slight pop-up arc
        final double angle = R.nextDouble() * Math.PI * 2;
        this.velocity = new Vec(Math.cos(angle) * 0.05, 0.25, Math.sin(angle) * 0.05);
        this.position = at.add(0, 0.2, 0);
        this.lastSentPos = position;
        this.itemStack = ItemStack.of(type.material);

        this.metaHolder = new MetaHolder(id);
        metaHolder.set(MetadataDef.HAS_NO_GRAVITY, false);
        metaHolder.set(MetadataDef.HAS_GLOWING_EFFECT, true);
        metaHolder.set(MetadataDef.ItemEntity.ITEM, itemStack);

        this.entry = arena.synchronizer.makeEntry(id, position,
                () -> List.of(new SpawnEntityPacket(id, uuid, EntityType.ITEM, position, 0f, 0, velocity.mul(20)),
                        metaHolder.metaDataPacket(),
                        new EntityVelocityPacket(id, velocity.mul(8000))),
                () -> List.of(new DestroyEntitiesPacket(id)));

        arena.drops.put(id, this);
    }

    boolean tick() {
        if (++ageTicks > lifetimeTicks) return false;
        // Simple ballistic
        velocity = velocity.add(0, -0.04, 0).mul(0.96);
        position = position.add(velocity);
        if (position.y() <= ArenaServer.FLOOR_Y + 0.1) {
            position = position.withY(ArenaServer.FLOOR_Y + 0.1);
            velocity = new Vec(velocity.x() * 0.6, Math.max(0, velocity.y() * -0.3), velocity.z() * 0.6);
            if (Math.abs(velocity.y()) < 0.02) velocity = new Vec(0, 0, 0);
        }
        entry.move(position);
        sinceTeleport++;
        if (sinceTeleport >= 10 || lastSentPos.distanceSquared(position) > 64) {
            entry.signalLocal(new EntityTeleportPacket(id, position, Vec.ZERO, 0, false));
            lastSentPos = position;
            sinceTeleport = 0;
        } else {
            entry.signalLocal(EntityPositionPacket.getPacket(id, position, lastSentPos, false));
            lastSentPos = position;
        }
        // Sparkle on rare drops
        if (type.rare && ageTicks % 6 == 0) {
            Effects.localParticleOffset(arena, position.add(0, 0.4, 0), Particle.END_ROD, 1,
                    0.1f, 0.1f, 0.1f, 0.0f);
        }
        // Blink before despawn
        if (lifetimeTicks - ageTicks < 60 && ageTicks % 10 < 5) {
            metaHolder.set(MetadataDef.IS_INVISIBLE, true);
        } else if (lifetimeTicks - ageTicks < 60) {
            metaHolder.set(MetadataDef.IS_INVISIBLE, false);
        }
        return true;
    }

    /**
     * Try to pick up. Returns true if consumed.
     */
    boolean tryPickup(Hero hero) {
        if (ageTicks < 6) return false; // brief delay so it doesn't insta-pickup
        if (!hero.alive) return false;
        double dx = position.x() - hero.position.x();
        double dy = position.y() - (hero.position.y() + 0.9);
        double dz = position.z() - hero.position.z();
        if (dx * dx + dy * dy + dz * dz > 1.7 * 1.7) return false;

        // Magnet animation
        entry.signalLocal(new CollectItemPacket(id, hero.id, 1));
        Effects.localSound(arena, position, type.pickupSound, 0.6f, 1.0f + (float) Math.random() * 0.3f);
        applyEffect(hero);
        // Self-destruct
        ageTicks = lifetimeTicks + 1;
        return true;
    }

    private void applyEffect(Hero hero) {
        switch (type) {
            case XP_SMALL -> hero.grantXp(10);
            case XP_MEDIUM -> hero.grantXp(30);
            case XP_LARGE -> hero.grantXp(80);
            case HEAL_SMALL -> hero.heal(8);
            case HEAL_LARGE -> hero.heal(25);
            case STRENGTH -> {
                hero.applyStrength(20 * 12);
                announce(hero, Component.text("STRENGTH!", NamedTextColor.RED));
            }
            case SPEED -> {
                hero.applySpeed(20 * 15);
                announce(hero, Component.text("SWIFTNESS!", NamedTextColor.AQUA));
            }
            case BOMB -> {
                Effects.explosion(arena, hero.position, 6.5);
                arena.enemyGrid.forEachInRange(hero.position.x(), hero.position.z(), 6.5,
                        e -> e.takeDamage(40 + hero.level * 5, true, hero, hero.position));
                announce(hero, Component.text("BOOM!", NamedTextColor.GOLD));
            }
            case WOLF_SUMMON -> {
                summonAlly(hero, Ally.Kind.WOLF, 3);
                announce(hero, Component.text("Wolf pack summoned!", NamedTextColor.YELLOW));
            }
            case GOLEM_SUMMON -> {
                summonAlly(hero, Ally.Kind.GOLEM, 1);
                announce(hero, Component.text("Iron Guardian summoned!", NamedTextColor.GREEN));
            }
            case CRIT_BUFF -> {
                hero.critChance = Math.min(0.9, hero.critChance + 0.1);
                announce(hero, Component.text("Crit chance up!", NamedTextColor.LIGHT_PURPLE));
            }
            case MAX_HP_UP -> {
                hero.maxHp += 6;
                hero.heal(6);
                announce(hero, Component.text("Max HP +6", NamedTextColor.GREEN));
            }
            case DAMAGE_UP -> {
                hero.baseDamage += 3;
                announce(hero, Component.text("Damage +3", NamedTextColor.RED));
            }
        }
    }

    private static void announce(Hero hero, Component msg) {
        hero.sendPacket(new ActionBarPacket(msg));
    }

    private void summonAlly(Hero hero, Ally.Kind kind, int count) {
        if (arena.allies.size() >= ArenaServer.MAX_ALLIES) return;
        for (int i = 0; i < count; i++) {
            final double a = Math.random() * Math.PI * 2;
            final Pos at = hero.position.add(Math.cos(a) * 1.5, 0, Math.sin(a) * 1.5);
            Ally ally = new Ally(arena, hero, kind, at);
            arena.allies.put(ally.id, ally);
        }
    }

    void unregister() {
        entry.unmake();
        arena.drops.remove(id);
    }

    private static Type[] normalTable() {
        return new Type[]{
                Type.XP_SMALL, Type.XP_SMALL, Type.XP_SMALL, Type.XP_SMALL,
                Type.XP_MEDIUM, Type.XP_MEDIUM,
                Type.HEAL_SMALL, Type.HEAL_SMALL,
                Type.HEAL_LARGE,
                Type.STRENGTH, Type.SPEED,
                Type.BOMB,
                Type.CRIT_BUFF,
                Type.MAX_HP_UP, Type.DAMAGE_UP,
                Type.WOLF_SUMMON
        };
    }

    private static Type[] bossTable() {
        return new Type[]{
                Type.XP_LARGE, Type.XP_LARGE,
                Type.GOLEM_SUMMON,
                Type.MAX_HP_UP,
                Type.DAMAGE_UP,
                Type.CRIT_BUFF,
                Type.STRENGTH,
                Type.HEAL_LARGE
        };
    }

    enum Type {
        XP_SMALL(Material.EXPERIENCE_BOTTLE, "minecraft:entity.experience_orb.pickup", false),
        XP_MEDIUM(Material.EXPERIENCE_BOTTLE, "minecraft:entity.experience_orb.pickup", false),
        XP_LARGE(Material.EMERALD, "minecraft:entity.experience_orb.pickup", true),
        HEAL_SMALL(Material.GOLDEN_APPLE, "minecraft:item.honey_bottle.drink", false),
        HEAL_LARGE(Material.ENCHANTED_GOLDEN_APPLE, "minecraft:entity.player.levelup", true),
        STRENGTH(Material.BLAZE_POWDER, "minecraft:entity.evoker.cast_spell", false),
        SPEED(Material.SUGAR, "minecraft:entity.horse.gallop", false),
        BOMB(Material.TNT, "minecraft:entity.tnt.primed", false),
        WOLF_SUMMON(Material.BONE, "minecraft:entity.wolf.howl", false),
        GOLEM_SUMMON(Material.IRON_BLOCK, "minecraft:entity.iron_golem.attack", true),
        CRIT_BUFF(Material.AMETHYST_SHARD, "minecraft:block.amethyst_block.chime", true),
        MAX_HP_UP(Material.NETHER_STAR, "minecraft:item.totem.use", true),
        DAMAGE_UP(Material.DIAMOND_SWORD, "minecraft:item.armor.equip_netherite", true);

        final Material material;
        final String pickupSound;
        final boolean rare;

        Type(Material material, String pickupSound, boolean rare) {
            this.material = material;
            this.pickupSound = pickupSound;
            this.rare = rare;
        }
    }
}
