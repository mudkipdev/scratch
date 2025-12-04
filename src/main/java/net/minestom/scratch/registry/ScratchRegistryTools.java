package net.minestom.scratch.registry;

import net.minestom.server.codec.StructCodec;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.animal.ChickenVariant;
import net.minestom.server.entity.metadata.animal.CowVariant;
import net.minestom.server.entity.metadata.animal.FrogVariant;
import net.minestom.server.entity.metadata.animal.PigVariant;
import net.minestom.server.entity.metadata.animal.tameable.CatVariant;
import net.minestom.server.entity.metadata.animal.tameable.WolfSoundVariant;
import net.minestom.server.entity.metadata.animal.tameable.WolfVariant;
import net.minestom.server.entity.metadata.other.PaintingVariant;
import net.minestom.server.instance.block.banner.BannerPattern;
import net.minestom.server.instance.block.jukebox.JukeboxSong;
import net.minestom.server.item.armor.TrimMaterial;
import net.minestom.server.item.armor.TrimPattern;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.item.enchant.EntityEffect;
import net.minestom.server.item.enchant.LevelBasedValue;
import net.minestom.server.item.enchant.LocationEffect;
import net.minestom.server.item.enchant.ValueEffect;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.message.ChatType;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.Registries;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ScratchRegistryTools {

    private static final Registries REGISTRIES = new Registries() {
        @Override public DynamicRegistry<ChatType> chatType() { return CHAT_TYPE; }
        @Override public DynamicRegistry<DimensionType> dimensionType() { return DIMENSION_TYPE; }
        @Override public DynamicRegistry<Biome> biome() { return BIOME; }
        @Override public DynamicRegistry<Dialog> dialog() { return DIALOG; }
        @Override public DynamicRegistry<DamageType> damageType() { return DAMAGE_TYPE; }
        @Override public DynamicRegistry<TrimMaterial> trimMaterial() { return TRIM_MATERIAL; }
        @Override public DynamicRegistry<TrimPattern> trimPattern() { return TRIM_PATTERN; }
        @Override public DynamicRegistry<BannerPattern> bannerPattern() { return BANNER_PATTERN; }
        @Override public DynamicRegistry<Enchantment> enchantment() { return ENCHANTMENT; }
        @Override public DynamicRegistry<PaintingVariant> paintingVariant() { return PAINTING_VARIANT; }
        @Override public DynamicRegistry<JukeboxSong> jukeboxSong() { return JUKEBOX_SONG; }
        @Override public DynamicRegistry<Instrument> instrument() { return INSTRUMENT; }
        @Override public DynamicRegistry<WolfVariant> wolfVariant() { return WOLF_VARIANT; }
        @Override public DynamicRegistry<WolfSoundVariant> wolfSoundVariant() { return WOLF_SOUND_VARIANT; }
        @Override public DynamicRegistry<CatVariant> catVariant() { return CAT_VARIANT; }
        @Override public DynamicRegistry<ChickenVariant> chickenVariant() { return CHICKEN_VARIANT; }
        @Override public DynamicRegistry<CowVariant> cowVariant() { return COW_VARIANT; }
        @Override public DynamicRegistry<FrogVariant> frogVariant() { return FROG_VARIANT; }
        @Override public DynamicRegistry<PigVariant> pigVariant() { return PIG_VARIANT; }
        @Override public DynamicRegistry<StructCodec<? extends LevelBasedValue>> enchantmentLevelBasedValues() { return ENCHANTMENT_LEVEL_BASED_VALUES; }
        @Override public DynamicRegistry<StructCodec<? extends ValueEffect>> enchantmentValueEffects() { return ENCHANTMENT_VALUE_EFFECTS; }
        @Override public DynamicRegistry<StructCodec<? extends EntityEffect>> enchantmentEntityEffects() { return ENCHANTMENT_ENTITY_EFFECTS; }
        @Override public DynamicRegistry<StructCodec<? extends LocationEffect>> enchantmentLocationEffects() { return ENCHANTMENT_LOCATION_EFFECTS; }
    };

    public static final DynamicRegistry<StructCodec<? extends LevelBasedValue>> ENCHANTMENT_LEVEL_BASED_VALUES = LevelBasedValue.createDefaultRegistry();
    public static final DynamicRegistry<StructCodec<? extends ValueEffect>> ENCHANTMENT_VALUE_EFFECTS = ValueEffect.createDefaultRegistry();
    public static final DynamicRegistry<StructCodec<? extends EntityEffect>> ENCHANTMENT_ENTITY_EFFECTS = EntityEffect.createDefaultRegistry();
    public static final DynamicRegistry<StructCodec<? extends LocationEffect>> ENCHANTMENT_LOCATION_EFFECTS = LocationEffect.createDefaultRegistry();

    public static final DynamicRegistry<ChatType> CHAT_TYPE = ChatType.createDefaultRegistry();
    public static final DynamicRegistry<DimensionType> DIMENSION_TYPE = DimensionType.createDefaultRegistry();
    public static final DynamicRegistry<Biome> BIOME = Biome.createDefaultRegistry();
    public static final DynamicRegistry<DamageType> DAMAGE_TYPE = DamageType.createDefaultRegistry();
    public static final DynamicRegistry<TrimMaterial> TRIM_MATERIAL = TrimMaterial.createDefaultRegistry();
    public static final DynamicRegistry<TrimPattern> TRIM_PATTERN = TrimPattern.createDefaultRegistry();
    public static final DynamicRegistry<BannerPattern> BANNER_PATTERN = BannerPattern.createDefaultRegistry();
    public static final DynamicRegistry<PaintingVariant> PAINTING_VARIANT = PaintingVariant.createDefaultRegistry();
    public static final DynamicRegistry<JukeboxSong> JUKEBOX_SONG = JukeboxSong.createDefaultRegistry();
    public static final DynamicRegistry<Instrument> INSTRUMENT = Instrument.createDefaultRegistry();
    public static final DynamicRegistry<WolfVariant> WOLF_VARIANT = WolfVariant.createDefaultRegistry();
    public static final DynamicRegistry<WolfSoundVariant> WOLF_SOUND_VARIANT = WolfSoundVariant.createDefaultRegistry();
    public static final DynamicRegistry<CatVariant> CAT_VARIANT = CatVariant.createDefaultRegistry();
    public static final DynamicRegistry<ChickenVariant> CHICKEN_VARIANT = ChickenVariant.createDefaultRegistry();
    public static final DynamicRegistry<CowVariant> COW_VARIANT = CowVariant.createDefaultRegistry();
    public static final DynamicRegistry<FrogVariant> FROG_VARIANT = FrogVariant.createDefaultRegistry();
    public static final DynamicRegistry<PigVariant> PIG_VARIANT = PigVariant.createDefaultRegistry();

    public static final DynamicRegistry<Dialog> DIALOG;
    public static final DynamicRegistry<Enchantment> ENCHANTMENT;

    public static final List<ServerPacket> REGISTRY_PACKETS;

    static {
        DIALOG = Dialog.createDefaultRegistry(REGISTRIES);
        ENCHANTMENT = Enchantment.createDefaultRegistry(REGISTRIES);

        List<ServerPacket> packets = new ArrayList<>();
        for (DynamicRegistry<?> registry : Set.of(
                CHAT_TYPE,
                DIMENSION_TYPE,
                BIOME,
                DAMAGE_TYPE,
                TRIM_MATERIAL,
                TRIM_PATTERN,
                BANNER_PATTERN,
                PAINTING_VARIANT,
                JUKEBOX_SONG,
                INSTRUMENT,
                WOLF_VARIANT,
                WOLF_SOUND_VARIANT,
                CAT_VARIANT,
                CHICKEN_VARIANT,
                COW_VARIANT,
                FROG_VARIANT,
                PIG_VARIANT
                // DIALOG,
                // ENCHANTMENT
        )) {
            final SendablePacket sendablePacket = registry.registryDataPacket(REGISTRIES, false);
            final ServerPacket packet = SendablePacket.extractServerPacket(ConnectionState.CONFIGURATION, sendablePacket);
            packets.add(packet);
        }
        REGISTRY_PACKETS = List.copyOf(packets);
    }

    public static Registries registries() {
        return REGISTRIES;
    }
}