package io.th0rgal.oraxen.mechanics.provided.gameplay.furniture;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureBreakEvent;
import io.th0rgal.oraxen.api.events.furniture.OraxenFurniturePlaceEvent;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.blocksounds.BlockSounds;
import io.th0rgal.protectionlib.ProtectionLib;
import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

import static io.th0rgal.oraxen.utils.BlockHelpers.isLoaded;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_BREAK_PITCH;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_BREAK_VOLUME;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_FALL_PITCH;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_FALL_VOLUME;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_HIT_PITCH;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_HIT_VOLUME;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_PLACE_PITCH;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_PLACE_VOLUME;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STEP_PITCH;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STEP_VOLUME;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STONE_BREAK;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STONE_FALL;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STONE_HIT;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STONE_PLACE;
import static io.th0rgal.oraxen.utils.blocksounds.BlockSounds.VANILLA_STONE_STEP;

public class FurnitureSoundListener implements Listener {

    private final Map<Location, BukkitTask> breakerPlaySound = new HashMap<>();

    // Play sound due to furniture/barrier custom sound replacing stone
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlacingStone(final BlockPlaceEvent event) {
        Block block = event.getBlock();

        if (block.getType() == Material.TRIPWIRE) return;
        if (block.getBlockData().getSoundGroup().getPlaceSound() != Sound.BLOCK_STONE_PLACE) return;
        BlockHelpers.playCustomBlockSound(event.getBlock().getLocation(), VANILLA_STONE_PLACE, VANILLA_PLACE_VOLUME, VANILLA_PLACE_PITCH);
    }

    // Play sound due to furniture/barrier custom sound replacing stone
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakingStone(final BlockBreakEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();

        if (block.getType() == Material.TRIPWIRE) return;
        if (block.getBlockData().getSoundGroup().getBreakSound() != Sound.BLOCK_STONE_BREAK) return;
        if (breakerPlaySound.containsKey(location)) {
            breakerPlaySound.get(location).cancel();
            breakerPlaySound.remove(location);
        }

        if (!event.isCancelled() && ProtectionLib.canBreak(event.getPlayer(), location))
            BlockHelpers.playCustomBlockSound(location, VANILLA_STONE_BREAK, VANILLA_BREAK_VOLUME, VANILLA_BREAK_PITCH);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHitStone(final BlockDamageEvent event) {
        Block block = event.getBlock();
        Location location = block.getLocation();
        SoundGroup soundGroup = block.getBlockData().getSoundGroup();

        if (event.getInstaBreak()) return;
        if (block.getType() == Material.BARRIER || soundGroup.getHitSound() != Sound.BLOCK_STONE_HIT) return;
        if (breakerPlaySound.containsKey(location)) return;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(OraxenPlugin.get(), () ->
                BlockHelpers.playCustomBlockSound(location, VANILLA_STONE_HIT, VANILLA_HIT_VOLUME, VANILLA_HIT_PITCH), 2L, 4L);
        breakerPlaySound.put(location, task);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStopHittingStone(final BlockDamageAbortEvent event) {
        Location location = event.getBlock().getLocation();
        if (breakerPlaySound.containsKey(location)) {
            breakerPlaySound.get(location).cancel();
            breakerPlaySound.remove(location);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStepFall(final GenericGameEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity)) return;
        if (!isLoaded(entity.getLocation())) return;

        GameEvent gameEvent = event.getEvent();
        Block block = entity.getLocation().getBlock();
        Block blockBelow = block.getRelative(BlockFace.DOWN);
        EntityDamageEvent cause = entity.getLastDamageCause();
        SoundGroup soundGroup = blockBelow.getBlockData().getSoundGroup();



        // Apparently water and air use stone sounds
        // Seems stone is the generic one so might be used in alot of places we don't want this to play
        if (blockBelow.getType() == Material.WATER || blockBelow.getType() == Material.AIR) return;
        if (soundGroup.getStepSound() != Sound.BLOCK_STONE_STEP) return;
        if (gameEvent == GameEvent.HIT_GROUND && cause != null && cause.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!BlockHelpers.isReplaceable(block) || block.getType() == Material.TRIPWIRE) return;
        FurnitureMechanic mechanic = OraxenFurniture.getFurnitureMechanic(blockBelow);

        String sound;
        float volume;
        float pitch;
        if (gameEvent == GameEvent.STEP) {
            boolean check = blockBelow.getType() == Material.BARRIER && mechanic != null && mechanic.hasBlockSounds() && mechanic.getBlockSounds().hasStepSound();
            sound = (check) ? mechanic.getBlockSounds().getStepSound() : VANILLA_STONE_STEP;
            volume = (check) ? mechanic.getBlockSounds().getStepVolume() : VANILLA_STEP_VOLUME;
            pitch = (check) ? mechanic.getBlockSounds().getStepPitch() : VANILLA_STEP_PITCH;
        } else if (gameEvent == GameEvent.HIT_GROUND) {
            boolean check = (blockBelow.getType() == Material.BARRIER && mechanic != null && mechanic.hasBlockSounds() && mechanic.getBlockSounds().hasFallSound());
            sound = (check) ? mechanic.getBlockSounds().getFallSound() : VANILLA_STONE_FALL;
            volume = (check) ? mechanic.getBlockSounds().getFallVolume() : VANILLA_FALL_VOLUME;
            pitch = (check) ? mechanic.getBlockSounds().getFallPitch() : VANILLA_FALL_PITCH;
        } else return;

        BlockHelpers.playCustomBlockSound(entity.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlacingFurniture(final OraxenFurniturePlaceEvent event) {
        final FurnitureMechanic mechanic = event.getMechanic();
        if (mechanic == null || !mechanic.hasBlockSounds()) return;
        BlockSounds blockSounds = mechanic.getBlockSounds();
        if (blockSounds.hasPlaceSound())
            BlockHelpers.playCustomBlockSound(event.getBaseEntity().getLocation(), blockSounds.getPlaceSound(), blockSounds.getPlaceVolume(), blockSounds.getPlacePitch());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakingFurniture(final OraxenFurnitureBreakEvent event) {
        Location loc = event.getBlock() != null ? event.getBlock().getLocation() : event.getBaseEntity().getLocation();
        final FurnitureMechanic mechanic = event.getMechanic();
        if (mechanic == null || !mechanic.hasBlockSounds()) return;
        BlockSounds blockSounds = mechanic.getBlockSounds();
        if (blockSounds.hasBreakSound())
            BlockHelpers.playCustomBlockSound(loc, blockSounds.getBreakSound(), blockSounds.getBreakVolume(), blockSounds.getBreakPitch());
    }
}
