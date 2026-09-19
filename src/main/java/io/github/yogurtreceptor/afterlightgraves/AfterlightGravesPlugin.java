package io.github.yogurtreceptor.afterlightgraves;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.util.TriState;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AfterlightGravesPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, GraveRecord> graves = new LinkedHashMap<>();
    private GraveStore store;
    private NamespacedKey graveIdKey;
    private NamespacedKey markerKindKey;
    private boolean sharedRecovery;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        sharedRecovery = getConfig().getBoolean("shared-recovery", true);
        graveIdKey = new NamespacedKey(this, "grave_id");
        markerKindKey = new NamespacedKey(this, "marker_kind");
        try {
            store = new GraveStore(getDataFolder(), getLogger());
        } catch (IOException exception) {
            getLogger().severe("Cannot create durable grave storage: " + exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (GraveRecord grave : store.loadAll()) {
            graves.put(grave.id, grave);
        }

        var command = getCommand("graves");
        if (command == null) {
            getLogger().severe("The /graves command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTask(this, this::reconcileLoadedChunks);
        getLogger().info("Loaded " + graves.size() + " durable grave(s); shared recovery is "
                + (sharedRecovery ? "enabled" : "disabled") + ".");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory() || event.getKeepLevel()) {
            getLogger().info("Not creating a grave for " + event.getPlayer().getName()
                    + " because another rule keeps inventory or levels.");
            return;
        }

        Player player = event.getPlayer();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack drop : event.getDrops()) {
            if (drop != null && !drop.isEmpty()) {
                items.add(drop.clone());
            }
        }
        int totalExperience = player.calculateTotalExperiencePoints();
        int storedExperience = totalExperience;
        if (items.isEmpty() && storedExperience <= 0) {
            return;
        }

        Location death = player.getLocation().clone();
        Location marker = findMarkerLocation(death);
        GraveRecord grave = new GraveRecord(
                UUID.randomUUID(),
                player.getUniqueId(),
                player.getName(),
                death.getWorld().getUID(),
                death.getWorld().getName(),
                System.currentTimeMillis(),
                death.getX(), death.getY(), death.getZ(),
                marker.getX(), marker.getY(), marker.getZ(),
                items,
                storedExperience
        );

        try {
            store.save(grave);
        } catch (IOException exception) {
            getLogger().severe("Could not persist a grave for " + player.getName()
                    + "; leaving normal death drops untouched: " + exception);
            player.sendMessage(Component.text("Grave storage failed; your items will drop normally.", NamedTextColor.RED));
            return;
        }

        graves.put(grave.id, grave);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setShouldDropExperience(false);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        spawnMarkers(grave, marker.getChunk());
        player.sendMessage(Component.text("Your items and " + storedExperience
                + " XP are safe in a grave at " + coordinates(marker) + ".", NamedTextColor.GOLD));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        UUID graveId = graveId(event.getRightClicked());
        if (graveId == null) {
            return;
        }
        event.setCancelled(true);
        GraveRecord grave = graves.get(graveId);
        if (grave == null) {
            event.getPlayer().sendMessage(Component.text("That grave no longer has stored contents.", NamedTextColor.GRAY));
            return;
        }
        claim(event.getPlayer(), grave);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMarkerDamage(EntityDamageEvent event) {
        if (graveId(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMarkerCombust(EntityCombustEvent event) {
        if (graveId(event.getEntity()) != null) {
            event.setCancelled(true);
            event.getEntity().setFireTicks(0);
            event.getEntity().setVisualFire(TriState.FALSE);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        getServer().getScheduler().runTask(this, () -> reconcileChunk(event.getChunk()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can use /graves.");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(Component.text("Use /graves with no extra words.", NamedTextColor.RED));
            return true;
        }
        List<GraveRecord> own = graves.values().stream()
                .filter(grave -> grave.ownerId.equals(player.getUniqueId()))
                .sorted(Comparator.comparingLong((GraveRecord grave) -> grave.createdAt).reversed())
                .toList();
        if (own.isEmpty()) {
            player.sendMessage(Component.text("You have no graves.", NamedTextColor.GRAY));
            return true;
        }
        player.sendMessage(Component.text(own.size() == 1 ? "Your grave:" : "Your graves:", NamedTextColor.GOLD));
        for (GraveRecord grave : own) {
            World world = getServer().getWorld(grave.worldId);
            String dimension = dimensionName(world, grave.worldName);
            String text = dimension + ": X " + floor(grave.markerX) + ", Y " + floor(grave.markerY)
                    + ", Z " + floor(grave.markerZ);
            if (world != null && player.getWorld().getUID().equals(grave.worldId)) {
                double dx = player.getLocation().getX() - grave.markerX;
                double dy = player.getLocation().getY() - grave.markerY;
                double dz = player.getLocation().getZ() - grave.markerZ;
                long distance = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                text += " — " + distance + (distance == 1 ? " block away" : " blocks away");
            } else {
                text += " — enter " + dimension + " to calculate distance";
            }
            player.sendMessage(Component.text(text, NamedTextColor.YELLOW));
        }
        return true;
    }

    private void claim(Player player, GraveRecord grave) {
        if (grave.claimLocked) {
            player.sendMessage(Component.text("This grave is locked after a storage error; ask an administrator.", NamedTextColor.RED));
            return;
        }
        if (!sharedRecovery && !grave.ownerId.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only " + grave.ownerName + " can recover this grave.", NamedTextColor.RED));
            return;
        }

        int itemCountBefore = itemCount(grave.items);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(
                grave.items.stream().map(ItemStack::clone).toArray(ItemStack[]::new));
        grave.items.clear();
        for (ItemStack item : leftovers.values()) {
            if (item != null && !item.isEmpty()) {
                grave.items.add(item.clone());
            }
        }
        int recoveredItems = itemCountBefore - itemCount(grave.items);
        int recoveredExperience = grave.experience;
        if (recoveredExperience > 0) {
            player.giveExp(recoveredExperience);
            grave.experience = 0;
        }

        try {
            if (grave.isEmpty()) {
                store.delete(grave.id);
                graves.remove(grave.id);
                removeLoadedMarkers(grave);
            } else {
                store.save(grave);
            }
        } catch (IOException exception) {
            grave.claimLocked = true;
            getLogger().severe("Grave " + grave.id + " could not be updated after recovery and is now locked: " + exception);
            player.sendMessage(Component.text("Recovery worked, but its record could not be updated. The grave is locked; ask an administrator.", NamedTextColor.RED));
            return;
        }

        if (grave.isEmpty()) {
            player.sendMessage(Component.text("Recovered " + recoveredItems + " item(s) and "
                    + recoveredExperience + " XP from " + grave.ownerName + "'s grave.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Recovered " + recoveredItems + " item(s) and "
                    + recoveredExperience + " XP. The grave remains because your inventory is full.", NamedTextColor.YELLOW));
        }
    }

    private void reconcileLoadedChunks() {
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                reconcileChunk(chunk);
            }
        }
    }

    private void reconcileChunk(Chunk chunk) {
        Map<UUID, Map<String, Entity>> present = new HashMap<>();
        for (Entity entity : chunk.getEntities()) {
            UUID id = graveId(entity);
            if (id == null) {
                continue;
            }
            GraveRecord grave = graves.get(id);
            if (grave == null) {
                entity.remove();
                continue;
            }
            String kind = entity.getPersistentDataContainer().get(markerKindKey, PersistentDataType.STRING);
            if (kind != null) {
                present.computeIfAbsent(id, ignored -> new HashMap<>()).put(kind, entity);
            }
        }
        for (GraveRecord grave : graves.values()) {
            if (!grave.worldId.equals(chunk.getWorld().getUID())
                    || (floor(grave.markerX) >> 4) != chunk.getX()
                    || (floor(grave.markerZ) >> 4) != chunk.getZ()) {
                continue;
            }
            Map<String, Entity> kinds = present.getOrDefault(grave.id, Map.of());
            if (!kinds.keySet().containsAll(List.of(
                    "interaction", "base", "headstone", "lantern", "label"))) {
                for (Entity entity : kinds.values()) {
                    entity.remove();
                }
                spawnMarkers(grave, chunk);
            }
        }
    }

    private void spawnMarkers(GraveRecord grave, Chunk expectedChunk) {
        World world = expectedChunk.getWorld();
        if (!world.getUID().equals(grave.worldId)) {
            return;
        }
        Location base = new Location(world, grave.markerX, grave.markerY, grave.markerZ);
        Interaction interaction = world.spawn(base, Interaction.class, entity -> {
            entity.setInteractionWidth(2.0f);
            entity.setInteractionHeight(2.6f);
            entity.setResponsive(true);
            protect(entity);
            tag(entity, grave.id, "interaction");
        });
        BlockDisplay baseDisplay = world.spawn(base.clone().add(-0.5, 0.0, -0.5), BlockDisplay.class, entity -> {
            entity.setBlock(org.bukkit.Material.POLISHED_BLACKSTONE_SLAB.createBlockData());
            protect(entity);
            tag(entity, grave.id, "base");
        });
        BlockDisplay headstone = world.spawn(base.clone().add(-0.5, 0.5, -0.5), BlockDisplay.class, entity -> {
            entity.setBlock(org.bukkit.Material.POLISHED_BLACKSTONE_WALL.createBlockData());
            protect(entity);
            tag(entity, grave.id, "headstone");
        });
        BlockDisplay lantern = world.spawn(base.clone().add(-0.5, 1.5, -0.5), BlockDisplay.class, entity -> {
            entity.setBlock(org.bukkit.Material.SOUL_LANTERN.createBlockData());
            entity.setBrightness(new Display.Brightness(15, 15));
            protect(entity);
            tag(entity, grave.id, "lantern");
        });
        TextDisplay label = world.spawn(base.clone().add(0, 2.35, 0), TextDisplay.class, entity -> {
            entity.text(Component.text(grave.ownerName + "'s grave\nRight-click to recover", NamedTextColor.GOLD));
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
            entity.setLineWidth(180);
            protect(entity);
            tag(entity, grave.id, "label");
        });
        if (!interaction.getChunk().equals(expectedChunk)
                || !baseDisplay.getChunk().equals(expectedChunk)
                || !headstone.getChunk().equals(expectedChunk)
                || !lantern.getChunk().equals(expectedChunk)
                || !label.getChunk().equals(expectedChunk)) {
            getLogger().warning("Grave marker unexpectedly crossed a chunk boundary: " + grave.id);
        }
    }

    private static void protect(Entity entity) {
        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.setGravity(false);
        entity.setFireTicks(0);
        entity.setVisualFire(TriState.FALSE);
    }

    private void removeLoadedMarkers(GraveRecord grave) {
        World world = getServer().getWorld(grave.worldId);
        if (world == null) {
            return;
        }
        int chunkX = floor(grave.markerX) >> 4;
        int chunkZ = floor(grave.markerZ) >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        for (Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
            if (grave.id.equals(graveId(entity))) {
                entity.remove();
            }
        }
    }

    private void tag(Entity entity, UUID graveId, String kind) {
        entity.getPersistentDataContainer().set(graveIdKey, PersistentDataType.STRING, graveId.toString());
        entity.getPersistentDataContainer().set(markerKindKey, PersistentDataType.STRING, kind);
    }

    private UUID graveId(Entity entity) {
        if (graveIdKey == null) {
            return null;
        }
        String value = entity.getPersistentDataContainer().get(graveIdKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Location findMarkerLocation(Location death) {
        World world = death.getWorld();
        int originX = death.getBlockX();
        int originY = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, death.getBlockY()));
        int originZ = death.getBlockZ();
        for (int radius = 0; radius <= 8; radius++) {
            for (int dy = 0; dy <= 8; dy++) {
                int[] ys = dy == 0 ? new int[]{originY} : new int[]{originY + dy, originY - dy};
                for (int y : ys) {
                    if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) {
                        continue;
                    }
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                                continue;
                            }
                            Block feet = world.getBlockAt(originX + dx, y, originZ + dz);
                            Block head = world.getBlockAt(originX + dx, y + 1, originZ + dz);
                            Block above = world.getBlockAt(originX + dx, y + 2, originZ + dz);
                            Block floor = world.getBlockAt(originX + dx, y - 1, originZ + dz);
                            if (feet.isPassable() && head.isPassable() && above.isPassable()
                                    && floor.getType().isSolid()
                                    && !feet.isLiquid() && !head.isLiquid() && !above.isLiquid()) {
                                return new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                            }
                        }
                    }
                }
            }
        }
        return new Location(world, death.getBlockX() + 0.5, originY, death.getBlockZ() + 0.5);
    }

    private static int itemCount(List<ItemStack> items) {
        return items.stream().mapToInt(ItemStack::getAmount).sum();
    }

    private static String coordinates(Location location) {
        return "X " + location.getBlockX() + ", Y " + location.getBlockY() + ", Z " + location.getBlockZ();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static String dimensionName(World world, String fallback) {
        if (world == null) {
            return friendlyWorldName(fallback);
        }
        return switch (world.getEnvironment()) {
            case NORMAL -> "Overworld";
            case NETHER -> "the Nether";
            case THE_END -> "the End";
            case CUSTOM -> friendlyWorldName(world.getName());
        };
    }

    private static String friendlyWorldName(String name) {
        String words = name.replace('_', ' ').replace('-', ' ').trim();
        if (words.isEmpty()) {
            return "this world";
        }
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
