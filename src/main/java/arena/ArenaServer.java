package arena;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.scratch.interest.Broadcast;
import net.minestom.scratch.network.NetworkContext;
import net.minestom.scratch.registry.ScratchRegistryTools;
import net.minestom.scratch.world.ImmutableChunkRepeatWorld;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.ChunkRange;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientPingRequestPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.status.StatusRequestPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.common.PingResponsePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.network.packet.server.status.ResponsePacket;
import net.minestom.server.network.player.GameProfile;
import org.jctools.queues.SpscUnboundedArrayQueue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Arena: a musou-style hack-n-slash. Thousands of enemies swarm the heroes,
 * allies fight back, and tons of loot drops to fuel the chaos.
 */
public final class ArenaServer {
    static final int PORT = Integer.getInteger("arena.port", 25565);
    static final SocketAddress ADDRESS = new InetSocketAddress("0.0.0.0", PORT);
    static final int VIEW_DISTANCE = 10;
    static final int FLOOR_Y = 49;
    static final Pos SPAWN = new Pos(0.5, FLOOR_Y, 0.5, 0f, 0f);
    static final long TICK_MS = 50L;

    static final int MAX_ENEMIES = 2500;
    static final int MAX_ALLIES = 60;

    public static void main(String[] args) throws Exception {
        MinecraftServer.init();
        new ArenaServer();
    }

    final AtomicInteger lastEntityId = new AtomicInteger();
    final AtomicBoolean stop = new AtomicBoolean(false);
    final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.INET);
    final ConcurrentLinkedQueue<PlayerInfo> waitingPlayers = new ConcurrentLinkedQueue<>();
    final Random random = new Random();

    final ImmutableChunkRepeatWorld world = new ImmutableChunkRepeatWorld(
            ScratchRegistryTools.DIMENSION_TYPE.get(net.minestom.server.world.DimensionType.OVERWORLD),
            ScratchRegistryTools.BIOME, unit -> {
        unit.modifier().fillHeight(0, FLOOR_Y - 1, Block.DEEPSLATE);
        unit.modifier().fillHeight(FLOOR_Y - 1, FLOOR_Y, Block.GRASS_BLOCK);
    });
    final Broadcast broadcast = new Broadcast();
    final Broadcast.World synchronizer = broadcast.makeWorld(VIEW_DISTANCE);

    final Map<Integer, Hero> heroes = new HashMap<>();
    final Map<Integer, Enemy> enemies = new HashMap<>();
    final Map<Integer, Ally> allies = new HashMap<>();
    final Map<Integer, LootDrop> drops = new HashMap<>();
    final Map<Integer, FloatingText.Instance> floatingTexts = new HashMap<>();
    final Map<Integer, Projectile> projectiles = new HashMap<>();

    final SpatialGrid<Enemy> enemyGrid = new SpatialGrid<>(8.0);
    final SpatialGrid<Ally> allyGrid = new SpatialGrid<>(8.0);
    final SpatialGrid<Hero> heroGrid = new SpatialGrid<>(16.0);

    int waveNumber = 0;
    int nextWaveTick = 60;
    int totalKills = 0;
    int tickCount = 0;
    boolean inWave = false;

    ArenaServer() throws Exception {
        server.bind(ADDRESS);
        System.out.println("Arena server listening on " + ADDRESS);
        Thread.startVirtualThread(this::listenCommands);
        Thread.startVirtualThread(this::listenConnections);
        ticks();
        server.close();
        System.out.println("Server stopped");
    }

    void listenCommands() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (!stop.get() && scanner.hasNextLine()) {
                final String line = scanner.nextLine();
                switch (line) {
                    case "stop" -> stop.set(true);
                    case "gc" -> System.gc();
                    case "wave" -> nextWaveTick = tickCount + 5;
                    case "stats" -> System.out.println(
                            "wave=" + waveNumber + " kills=" + totalKills +
                                    " enemies=" + enemies.size() + " allies=" + allies.size() +
                                    " drops=" + drops.size() + " heroes=" + heroes.size());
                }
            }
        } catch (Exception ignored) {
            // No stdin available; just sleep until shutdown
        }
    }

    void listenConnections() {
        while (!stop.get()) {
            try {
                final SocketChannel client = server.accept();
                Connection connection = new Connection(client);
                Thread.startVirtualThread(connection::networkLoopRead);
                Thread.startVirtualThread(connection::networkLoopWrite);
            } catch (IOException e) {
                if (!stop.get()) e.printStackTrace();
            }
        }
    }

    void ticks() {
        int keepAliveCounter = 0;
        long nextTickAt = System.nanoTime();
        while (!stop.get()) {
            final long tickStart = System.nanoTime();
            this.tickCount++;

            // 1. Connect waiting players
            PlayerInfo info;
            while ((info = waitingPlayers.poll()) != null) {
                final Hero hero = new Hero(this, info, randomSpawn());
                heroes.put(hero.id, hero);
                hero.broadcastJoin();
            }

            // 2. Wave logic
            if (heroes.isEmpty()) {
                this.waveNumber = 0;
                this.nextWaveTick = tickCount + 60;
                this.inWave = false;
            } else {
                if (inWave && enemies.isEmpty()) {
                    onWaveCleared();
                    inWave = false;
                }
                if (enemies.size() < 5 && tickCount >= nextWaveTick) {
                    spawnWave();
                    inWave = true;
                }
            }

            // 3. Refresh spatial grids
            heroGrid.clear();
            for (Hero h : heroes.values()) if (h.alive) heroGrid.insert(h.position, h);
            enemyGrid.clear();
            for (Enemy e : enemies.values()) enemyGrid.insert(e.position, e);
            allyGrid.clear();
            for (Ally a : allies.values()) allyGrid.insert(a.position, a);

            // 4. Tick heroes
            final boolean keepAlive = ++keepAliveCounter % 200 == 0;
            List<Runnable> postTick = new ArrayList<>();
            for (Hero hero : new ArrayList<>(heroes.values())) {
                if (keepAlive) hero.sendPacket(new KeepAlivePacket(keepAliveCounter));
                if (!hero.tick()) postTick.add(hero::unregister);
            }

            // 5. Tick enemies
            for (Enemy enemy : enemies.values()) {
                if (!enemy.tick()) postTick.add(enemy::unregister);
            }

            // 6. Tick allies
            for (Ally ally : allies.values()) {
                if (!ally.tick()) postTick.add(ally::unregister);
            }

            // 7. Tick loot drops
            for (LootDrop drop : drops.values()) {
                if (!drop.tick()) postTick.add(drop::unregister);
            }

            // 7b. Tick floating texts
            for (FloatingText.Instance ft : floatingTexts.values()) {
                if (!ft.tick()) postTick.add(ft::unregister);
            }
            // 7c. Tick projectiles
            for (Projectile p : projectiles.values()) {
                if (!p.tick()) postTick.add(p::unregister);
            }

            // 8. Apply removals
            for (Runnable r : postTick) r.run();

            // 9. Process broadcaster
            broadcast.process();

            // 10. HUD updates (every 4 ticks)
            if (tickCount % 4 == 0) {
                for (Hero hero : heroes.values()) hero.updateHud();
            }

            // 11. Wave start announcement
            if (tickCount == nextWaveTick - 30 && !heroes.isEmpty()) {
                announceIncoming();
            }

            // 12. Pace ticks
            nextTickAt += TICK_MS * 1_000_000L;
            long now = System.nanoTime();
            long sleepNanos = nextTickAt - now;
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException ignored) {
                }
            } else if (sleepNanos < -100_000_000L) {
                // Far behind, resync
                nextTickAt = now;
            }

            final double elapsedMs = (System.nanoTime() - tickStart) / 1_000_000.0;
            if (tickCount % 20 == 0 && !heroes.isEmpty()) {
                final long heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                final PlayerListHeaderAndFooterPacket footer = new PlayerListHeaderAndFooterPacket(
                        Component.text("✦ ARENA ✦", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text(String.format("Tick %.2fms · %dMB · %d enemies · %d allies",
                                elapsedMs, heap, enemies.size(), allies.size()), NamedTextColor.GRAY));
                for (Hero h : heroes.values()) h.sendPacket(footer);
            }
        }
    }

    Pos randomSpawn() {
        final double r = 4 + random.nextDouble() * 4;
        final double a = random.nextDouble() * Math.PI * 2;
        return new Pos(Math.cos(a) * r, FLOOR_Y, Math.sin(a) * r, 0f, 0f);
    }

    private void onWaveCleared() {
        // Reward all heroes a small XP bonus & heal
        for (Hero h : heroes.values()) {
            if (!h.alive) continue;
            h.heal(15);
            h.grantXp(20 + waveNumber * 5);
            h.sendPacket(new SetTitleTextPacket(Component.text("WAVE CLEARED",
                    NamedTextColor.GREEN, TextDecoration.BOLD)));
            h.sendPacket(new SetTitleSubTitlePacket(Component.text("+" + (20 + waveNumber * 5) + " xp  ·  next wave incoming",
                    NamedTextColor.YELLOW)));
            h.sendPacket(new SetTitleTimePacket(5, 30, 10));
            Effects.playSound(this, h, "minecraft:ui.toast.challenge_complete", 1.0f, 1.0f);
        }
    }

    private void announceIncoming() {
        final Component title = Component.text("WAVE " + (waveNumber + 1), NamedTextColor.RED, TextDecoration.BOLD);
        final Component sub = Component.text("Brace yourselves!", NamedTextColor.YELLOW);
        for (Hero h : heroes.values()) {
            h.sendPacket(new SetTitleTextPacket(title));
            h.sendPacket(new SetTitleSubTitlePacket(sub));
            h.sendPacket(new SetTitleTimePacket(5, 30, 10));
            Effects.playSound(this, h, "minecraft:event.raid.horn", 1.0f, 1.0f);
        }
    }

    private void spawnWave() {
        this.waveNumber++;
        final int count = Math.min(MAX_ENEMIES - enemies.size(), 30 + waveNumber * 12);
        final Hero focus = heroes.values().iterator().next();
        for (int i = 0; i < count; i++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double dist = 18 + random.nextDouble() * 16;
            final double x = focus.position.x() + Math.cos(angle) * dist;
            final double z = focus.position.z() + Math.sin(angle) * dist;
            final Pos pos = new Pos(x, FLOOR_Y, z, (float) (Math.toDegrees(angle) + 180), 0f);
            final Enemy.Kind kind = pickEnemyKind();
            final Enemy enemy = new Enemy(this, kind, pos, waveNumber);
            enemies.put(enemy.id, enemy);
        }
        // Boss every 5 waves
        if (waveNumber % 5 == 0) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double dist = 20;
            final Pos bp = new Pos(focus.position.x() + Math.cos(angle) * dist, FLOOR_Y,
                    focus.position.z() + Math.sin(angle) * dist, 0f, 0f);
            final Enemy boss = new Enemy(this, Enemy.Kind.BOSS, bp, waveNumber);
            enemies.put(boss.id, boss);
            for (Hero h : heroes.values()) {
                h.sendPacket(new SetTitleTextPacket(Component.text("✦ BOSS ✦", NamedTextColor.DARK_RED, TextDecoration.BOLD)));
                h.sendPacket(new SetTitleSubTitlePacket(Component.text("A great evil approaches", NamedTextColor.RED)));
                h.sendPacket(new SetTitleTimePacket(10, 50, 20));
                Effects.playSound(this, h, "minecraft:entity.wither.spawn", 1.0f, 0.9f);
            }
        }
        this.nextWaveTick = tickCount + Math.max(120, 220 - waveNumber * 6);
    }

    private Enemy.Kind pickEnemyKind() {
        final int r = random.nextInt(100);
        if (waveNumber >= 8 && r < 5) return Enemy.Kind.CHARGER;
        if (waveNumber >= 4 && r < 15) return Enemy.Kind.RANGED;
        if (waveNumber >= 2 && r < 30) return Enemy.Kind.HEAVY;
        if (r < 70) return Enemy.Kind.GRUNT;
        return Enemy.Kind.SCOUT;
    }

    final class Connection {
        final SocketChannel client;
        final NetworkContext.Async networkContext = new NetworkContext.Async();
        final SpscUnboundedArrayQueue<ClientPacket> packetQueue = new SpscUnboundedArrayQueue<>(2048);
        volatile boolean online = true;
        PlayerInfo playerInfo;

        Connection(SocketChannel client) {
            this.client = client;
        }

        void networkLoopRead() {
            while (online) {
                this.online = networkContext.read(buffer -> {
                    try {
                        buffer.readChannel(client);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, this::handleAsyncPacket);
            }
        }

        void networkLoopWrite() {
            while (online) {
                this.online = networkContext.write(buffer -> {
                    try {
                        buffer.writeChannel(client);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        void handleAsyncPacket(ClientPacket packet) {
            if (packet instanceof ClientFinishConfigurationPacket) {
                waitingPlayers.offer(playerInfo);
                return;
            }
            if (networkContext.state() == ConnectionState.PLAY) {
                packetQueue.add(packet);
                return;
            }
            switch (packet) {
                case StatusRequestPacket ignored -> networkContext.write(new ResponsePacket("""
                        {
                            "version": {"name": "%s", "protocol": %s},
                            "players": {"max": 100, "online": %d},
                            "description": {"text": "§c§l✦ ARENA ✦§r §7Musou-style swarm hack-n-slash"},
                            "enforcesSecureChat": false,
                            "previewsChat": false
                        }
                        """.formatted(MinecraftServer.VERSION_NAME, MinecraftServer.PROTOCOL_VERSION, heroes.size())));
                case ClientPingRequestPacket ping ->
                        networkContext.write(new PingResponsePacket(ping.number()));
                case ClientLoginStartPacket start -> {
                    this.playerInfo = new PlayerInfo(this, start.username(), UUID.randomUUID());
                    networkContext.write(new LoginSuccessPacket(new GameProfile(playerInfo.uuid(), playerInfo.username())));
                }
                case ClientLoginAcknowledgedPacket ignored -> {
                    networkContext.write(ScratchRegistryTools.REGISTRY_PACKETS);
                    networkContext.write(ScratchRegistryTools.TAGS_PACKET);
                    networkContext.write(new FinishConfigurationPacket());
                }
                default -> {
                }
            }
        }
    }

    record PlayerInfo(Connection connection, String username, UUID uuid) {
    }

    /**
     * Coarse spatial grid for fast nearest-neighbor lookups.
     * Cells are keyed by floor(coord / cellSize) packed into a long.
     */
    static final class SpatialGrid<T> {
        private final double cellSize;
        private final HashMap<Long, ArrayList<Entry<T>>> cells = new HashMap<>();

        SpatialGrid(double cellSize) {
            this.cellSize = cellSize;
        }

        void clear() {
            cells.clear();
        }

        void insert(Pos p, T value) {
            final long key = key((int) Math.floor(p.x() / cellSize), (int) Math.floor(p.z() / cellSize));
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(new Entry<>(p.x(), p.z(), value));
        }

        T nearest(double x, double z, double maxDist) {
            final int cx = (int) Math.floor(x / cellSize);
            final int cz = (int) Math.floor(z / cellSize);
            final int radius = Math.max(1, (int) Math.ceil(maxDist / cellSize));
            T best = null;
            double bestSq = maxDist * maxDist;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final ArrayList<Entry<T>> bucket = cells.get(key(cx + dx, cz + dz));
                    if (bucket == null) continue;
                    for (int i = 0; i < bucket.size(); i++) {
                        Entry<T> e = bucket.get(i);
                        double ddx = e.x - x;
                        double ddz = e.z - z;
                        double d2 = ddx * ddx + ddz * ddz;
                        if (d2 < bestSq) {
                            bestSq = d2;
                            best = e.value;
                        }
                    }
                }
            }
            return best;
        }

        void forEachInRange(double x, double z, double range, java.util.function.Consumer<T> action) {
            final int cx = (int) Math.floor(x / cellSize);
            final int cz = (int) Math.floor(z / cellSize);
            final int radius = Math.max(1, (int) Math.ceil(range / cellSize));
            final double r2 = range * range;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    final ArrayList<Entry<T>> bucket = cells.get(key(cx + dx, cz + dz));
                    if (bucket == null) continue;
                    for (int i = 0; i < bucket.size(); i++) {
                        Entry<T> e = bucket.get(i);
                        double ddx = e.x - x;
                        double ddz = e.z - z;
                        if (ddx * ddx + ddz * ddz <= r2) action.accept(e.value);
                    }
                }
            }
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        }

        private record Entry<T>(double x, double z, T value) {
        }
    }
}
