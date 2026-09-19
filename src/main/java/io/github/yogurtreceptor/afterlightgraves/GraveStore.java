package io.github.yogurtreceptor.afterlightgraves;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

final class GraveStore {
    private final Path directory;
    private final Logger logger;

    GraveStore(File dataFolder, Logger logger) throws IOException {
        this.directory = dataFolder.toPath().resolve("graves");
        this.logger = logger;
        Files.createDirectories(directory);
    }

    List<GraveRecord> loadAll() {
        List<GraveRecord> records = new ArrayList<>();
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return records;
        }
        for (File file : files) {
            try {
                records.add(load(file));
            } catch (Exception exception) {
                logger.severe("Leaving unreadable grave file untouched: " + file + " (" + exception + ")");
            }
        }
        return records;
    }

    void save(GraveRecord grave) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("id", grave.id.toString());
        yaml.set("owner.uuid", grave.ownerId.toString());
        yaml.set("owner.name", grave.ownerName);
        yaml.set("world.uuid", grave.worldId.toString());
        yaml.set("world.name", grave.worldName);
        yaml.set("created-at", grave.createdAt);
        yaml.set("death.x", grave.deathX);
        yaml.set("death.y", grave.deathY);
        yaml.set("death.z", grave.deathZ);
        yaml.set("marker.x", grave.markerX);
        yaml.set("marker.y", grave.markerY);
        yaml.set("marker.z", grave.markerZ);
        yaml.set("experience", grave.experience);
        yaml.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(grave.items)));

        Path destination = pathFor(grave.id);
        Path temporary = directory.resolve(grave.id + ".yml.new");
        yaml.save(temporary.toFile());
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void delete(UUID graveId) throws IOException {
        Files.deleteIfExists(pathFor(graveId));
    }

    private GraveRecord load(File file) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getInt("format") != 1) {
            throw new IOException("unsupported format " + yaml.getInt("format"));
        }
        String encodedItems = required(yaml, "items");
        ItemStack[] decoded = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encodedItems));
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : decoded) {
            if (item != null && !item.isEmpty()) {
                items.add(item);
            }
        }
        return new GraveRecord(
                UUID.fromString(required(yaml, "id")),
                UUID.fromString(required(yaml, "owner.uuid")),
                required(yaml, "owner.name"),
                UUID.fromString(required(yaml, "world.uuid")),
                required(yaml, "world.name"),
                yaml.getLong("created-at"),
                yaml.getDouble("death.x"),
                yaml.getDouble("death.y"),
                yaml.getDouble("death.z"),
                yaml.getDouble("marker.x"),
                yaml.getDouble("marker.y"),
                yaml.getDouble("marker.z"),
                items,
                yaml.getInt("experience")
        );
    }

    private Path pathFor(UUID graveId) {
        return directory.resolve(graveId + ".yml");
    }

    private static String required(YamlConfiguration yaml, String path) throws IOException {
        String value = yaml.getString(path);
        if (value == null || value.isBlank()) {
            throw new IOException("missing " + path);
        }
        return value;
    }
}
