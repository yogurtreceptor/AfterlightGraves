package io.github.yogurtreceptor.afterlightgraves;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class GraveRecord {
    final UUID id;
    final UUID ownerId;
    final String ownerName;
    final UUID worldId;
    final String worldName;
    final long createdAt;
    final double deathX;
    final double deathY;
    final double deathZ;
    final double markerX;
    final double markerY;
    final double markerZ;
    final List<ItemStack> items;
    int experience;
    boolean claimLocked;

    GraveRecord(
            UUID id,
            UUID ownerId,
            String ownerName,
            UUID worldId,
            String worldName,
            long createdAt,
            double deathX,
            double deathY,
            double deathZ,
            double markerX,
            double markerY,
            double markerZ,
            List<ItemStack> items,
            int experience
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.worldId = worldId;
        this.worldName = worldName;
        this.createdAt = createdAt;
        this.deathX = deathX;
        this.deathY = deathY;
        this.deathZ = deathZ;
        this.markerX = markerX;
        this.markerY = markerY;
        this.markerZ = markerZ;
        this.items = new ArrayList<>(items);
        this.experience = experience;
    }

    boolean isEmpty() {
        return items.isEmpty() && experience <= 0;
    }
}
