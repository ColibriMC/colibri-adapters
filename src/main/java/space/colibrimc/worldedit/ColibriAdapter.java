package space.colibrimc.worldedit;

import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import com.sk89q.worldedit.world.registry.BundledBlockData;
import com.sk89q.worldedit.world.registry.PassthroughBlockMaterial;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * El adaptador de WorldEdit para ColibriMC.
 *
 * Hace por WorldEdit lo que en Paper hace el adaptador hecho contra las clases internas de Minecraft: traducir bloques entre
 * WorldEdit y el servidor, y leer y escribir bloques en el mundo. Aqui el servidor es Minestom, asi que se habla con el
 * directamente: un bloque de WorldEdit es un `BlockState` con sus propiedades, y uno de Minestom un `Block` con las suyas.
 *
 * Lo que no hace todavia: entidades (copiarlas y pegarlas), el contenido de cofres y carteles (el NBT de los bloques),
 * regenerar chunks, arboles y estructuras, ni el arreglador de datos de esquemas viejos.
 */
public final class ColibriAdapter implements BukkitImplAdapter {

    public ColibriAdapter() {
    }

    /**
     * El mundo de Minestom detras de un `org.bukkit.World` de ColibriMC.
     *
     * Se pide por reflexion (`instance()`) para que este modulo no dependa de las clases internas de ColibriMC y
     * se pueda compilar solo, en un repositorio publico.
     */
    private static Instance instance(World world) {
        try {
            java.lang.reflect.Method method = world.getClass().getMethod("instance");
            return (Instance) method.invoke(world);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalArgumentException("Unsupported world (not a ColibriMC world): " + world, e);
        }
    }

    // ─── Bloques: WorldEdit <-> Minestom ────────────────────────────────

    /** El bloque de WorldEdit que corresponde a uno de Minestom. */
    static BlockState toWorldEdit(Block block) {
        BlockType type = BlockTypes.get(block.key().asString());
        if (type == null) return BlockTypes.AIR.getDefaultState();
        BlockState state = type.getDefaultState();
        for (Map.Entry<String, String> entry : block.properties().entrySet()) {
            @SuppressWarnings("unchecked")
            Property<Object> property = (Property<Object>) type.getPropertyMap().get(entry.getKey());
            if (property == null) continue;
            try {
                state = state.with(property, property.getValueFor(entry.getValue()));
            } catch (RuntimeException unloaded) {
                // Un valor que WorldEdit no conoce: se queda el de por defecto.
            }
        }
        return state;
    }

    /** El bloque de Minestom que corresponde a uno de WorldEdit. */
    static Block toMinestom(BlockState state) {
        Block block = Block.fromKey(state.getBlockType().id());
        if (block == null) return Block.AIR;
        String text = state.getAsString();
        int open = text.indexOf('[');
        if (open < 0) return block;
        Map<String, String> properties = new HashMap<>();
        for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) properties.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        try {
            return block.withProperties(properties);
        } catch (RuntimeException e) {
            return block;
        }
    }

    @Override
    public DataFixer getDataFixer() {
        return null;
    }

    @Override
    public BlockState getBlock(Location location) {
        return toWorldEdit(instance(location.getWorld()).getBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    @Override
    public BaseBlock getFullBlock(Location location) {
        return getBlock(location).toBaseBlock();
    }

    @Override
    public WorldNativeAccess<?, ?, ?> createWorldNativeAccess(World world) {
        return new Access(instance(world));
    }

    /**
     * Reenvia a los jugadores los chunks que WorldEdit ha cambiado, una sola vez por chunk y cuando deja de escribir.
     *
     * Reenviar el chunk entero tarda mucho menos que un paquete por bloque: una schematic de 22 millones de bloques
     * son unos cientos de chunks.
     */
    private static final class Resender {
        /** Lo que tarda en considerarse que WorldEdit ha terminado: sin escrituras durante este tiempo. */
        private static final long QUIET_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);

        private static final java.util.Set<Chunk> DIRTY = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static final java.util.concurrent.atomic.AtomicBoolean SCHEDULED = new java.util.concurrent.atomic.AtomicBoolean();
        private static volatile long lastWrite;
        private static final java.util.concurrent.ScheduledExecutorService TIMER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ColibriWorldEdit-resend");
            thread.setDaemon(true);
            return thread;
        });

        static void touched(Chunk chunk) {
            DIRTY.add(chunk);
            lastWrite = System.nanoTime();
            if (SCHEDULED.compareAndSet(false, true)) TIMER.schedule(Resender::check, 150, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private static void check() {
            long quiet = System.nanoTime() - lastWrite;
            if (quiet < QUIET_NANOS) {
                TIMER.schedule(Resender::check, QUIET_NANOS - quiet, java.util.concurrent.TimeUnit.NANOSECONDS);
                return;
            }
            SCHEDULED.set(false);
            for (java.util.Iterator<Chunk> it = DIRTY.iterator(); it.hasNext(); ) {
                Chunk chunk = it.next();
                it.remove();
                try {
                    chunk.invalidate();
                    chunk.sendChunk();
                } catch (RuntimeException unloaded) {
                    // Un chunk descargado entre medias: no hay a quien reenviarlo.
                }
            }
            // Una escritura que llego mientras se vaciaba: se vuelve a mirar.
            if (!DIRTY.isEmpty() && SCHEDULED.compareAndSet(false, true)) TIMER.schedule(Resender::check, 150, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Leer y escribir bloques del mundo para WorldEdit: chunk = `Chunk`, bloque = `Block`, posicion = `BlockVec`. */
    private static final class Access implements WorldNativeAccess<Chunk, Block, BlockVec> {
        private final Instance instance;

        Access(Instance instance) {
            this.instance = instance;
        }

        @Override
        public Chunk getChunk(int x, int z) {
            Chunk chunk = instance.getChunk(x, z);
            return chunk != null ? chunk : instance.loadChunk(x, z).join();
        }

        @Override
        public Block toNative(BlockState state) {
            return toMinestom(state);
        }

        @Override
        public Block getBlockState(Chunk chunk, BlockVec position) {
            return instance.getBlock(position);
        }

        @Override
        public Block setBlockState(Chunk chunk, BlockVec position, Block state) {
            Block old = instance.getBlock(position);
            // Directo al chunk: `instance.setBlock` manda un paquete por bloque a todos los que miran, y pegar una
            // schematic grande son millones (el cliente se ahoga y el servidor lo echa por exceso de paquetes).
            // Se apunta el chunk y se reenvia una sola vez cuando WorldEdit termina de escribir.
            chunk.lockWriteLock();
            try {
                chunk.setBlock(position.blockX(), position.blockY(), position.blockZ(), state);
            } finally {
                chunk.unlockWriteLock();
            }
            Resender.touched(chunk);
            return old;
        }

        @Override
        public Block getValidBlockForPosition(Block block, BlockVec position) {
            return block;
        }

        @Override
        public BlockVec getPosition(int x, int y, int z) {
            return new BlockVec(x, y, z);
        }

        @Override
        public void updateLightingForBlock(BlockVec position) {
        }

        @Override
        public boolean updateTileEntity(BlockVec position, LinCompoundTag tag) {
            return false;
        }

        @Override
        public void notifyBlockUpdate(Chunk chunk, BlockVec position, Block oldState, Block newState) {
        }

        @Override
        public boolean isChunkTicking(Chunk chunk) {
            return true;
        }

        @Override
        public void markBlockChanged(Chunk chunk, BlockVec position) {
        }

        @Override
        public void notifyNeighbors(BlockVec position, Block oldState, Block newState) {
        }

        @Override
        public void updateNeighbors(BlockVec position, Block oldState, Block newState, int recursionLimit) {
        }

        @Override
        public void onBlockStateChange(BlockVec position, Block oldState, Block newState) {
        }
    }

    // ─── Entidades: todavia no ───────────────────────────────────────────

    @Override
    public BaseEntity getEntity(Entity entity) {
        return null;
    }

    @Override
    public Entity createEntity(Location location, BaseEntity entity) {
        return null;
    }

    // ─── Nombres y materiales ────────────────────────────────────────────

    @Override
    public Component getRichBlockName(BlockType blockType) {
        return TextComponent.of(blockType.id());
    }

    @Override
    public Component getRichItemName(ItemType itemType) {
        return TextComponent.of(itemType.id());
    }

    @Override
    public Component getRichItemName(BaseItemStack itemStack) {
        return TextComponent.of(itemStack.getType().id());
    }

    @Override
    public BlockMaterial getBlockMaterial(BlockType blockType) {
        return new PassthroughBlockMaterial(BundledBlockData.getInstance().getMaterialById(blockType.id()));
    }

    /** Las propiedades de un bloque, sacadas de los estados posibles que tiene en Minestom. */
    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        Block block = Block.fromKey(blockType.id());
        if (block == null) return Map.of();
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (Block state : block.possibleStates()) {
            for (Map.Entry<String, String> entry : state.properties().entrySet()) {
                values.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(entry.getValue());
            }
        }
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            // WorldEdit toma como valor por defecto el primero de la lista: el valor por defecto del bloque va delante.
            List<String> ordered = new ArrayList<>(entry.getValue());
            String fallback = block.properties().get(entry.getKey());
            if (fallback != null && ordered.remove(fallback)) ordered.add(0, fallback);
            properties.put(entry.getKey(), propertyOf(entry.getKey(), ordered));
        }
        return properties;
    }

    private static Property<?> propertyOf(String name, List<String> values) {
        if (values.stream().allMatch(v -> v.equals("true") || v.equals("false"))) {
            List<Boolean> booleans = new ArrayList<>();
            for (String v : values) booleans.add(Boolean.parseBoolean(v));
            return new BooleanProperty(name, booleans);
        }
        if (values.stream().allMatch(v -> v.matches("-?\\d+"))) {
            List<Integer> ints = new ArrayList<>();
            for (String v : values) ints.add(Integer.parseInt(v));
            return new IntegerProperty(name, ints);
        }
        if (name.equals("facing") && values.stream().allMatch(ColibriAdapter::isDirection)) {
            List<Direction> directions = new ArrayList<>();
            for (String v : values) directions.add(Direction.valueOf(v.toUpperCase(Locale.ROOT)));
            return new DirectionalProperty(name, directions);
        }
        return new EnumProperty(name, values);
    }

    private static boolean isDirection(String value) {
        return switch (value) {
            case "north", "south", "east", "west", "up", "down" -> true;
            default -> false;
        };
    }

    // ─── Cosas que no hacen falta o no se hacen ──────────────────────────

    @Override
    public void sendFakeNBT(Player player, BlockVector3 position, LinCompoundTag nbt) {
    }

    @Override
    public void sendFakeOP(Player player) {
    }

    @Override
    public boolean canPlaceAt(World world, BlockVector3 position, BlockState state) {
        return true;
    }

    @Override
    public Set<SideEffect> getSupportedSideEffects() {
        return Set.of();
    }

    // ─── Objetos ─────────────────────────────────────────────────────────

    @Override
    public ItemStack adapt(BaseItemStack item) {
        Material material = Material.matchMaterial(item.getType().id());
        return new ItemStack(material != null ? material : Material.AIR, item.getAmount());
    }

    @Override
    public BaseItemStack adapt(ItemStack item) {
        ItemType type = ItemTypes.get(item.getType().getKey().toString());
        return new BaseItemStack(type != null ? type : ItemTypes.AIR, item.getAmount());
    }
}
