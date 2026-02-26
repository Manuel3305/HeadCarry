package de.manuellxposure.headcarry;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

public class HeadCarryPlugin extends JavaPlugin implements Listener {

    private static final double HEAD_OFFSET = 2.15;

    private static class CarryState {
        final UUID mobId;
        final UUID standId;
        CarryState(UUID mobId, UUID standId) {
            this.mobId = mobId;
            this.standId = standId;
        }
    }

    private final Map<UUID, CarryState> carrying = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override public void run() {
                for (Map.Entry<UUID, CarryState> e : new ArrayList<>(carrying.entrySet())) {
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p == null || !p.isOnline()) {
                        dropSilently(e.getKey());
                        continue;
                    }

                    CarryState st = e.getValue();
                    Entity standE = Bukkit.getEntity(st.standId);
                    Entity mobE = Bukkit.getEntity(st.mobId);

                    if (!(standE instanceof ArmorStand stand) || !(mobE instanceof LivingEntity mob)) {
                        dropSilently(e.getKey());
                        continue;
                    }

                    Location head = p.getLocation().clone().add(0, HEAD_OFFSET, 0);
                    head.setYaw(p.getLocation().getYaw());
                    head.setPitch(0f);
                    stand.teleport(head);

                    p.sendActionBar(ChatColor.AQUA + "Du trägst: " + ChatColor.WHITE + niceName(mob.getType())
                            + ChatColor.DARK_GRAY + "  (Rechtsklick = absetzen)");
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (UUID playerId : new ArrayList<>(carrying.keySet())) {
            dropSilently(playerId);
        }
        carrying.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickUp(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();

        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!player.isSneaking()) return;

        if (carrying.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            msg(player, ChatColor.RED + "Du trägst schon ein Mob. Erst absetzen.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1.2f);
            return;
        }

        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof LivingEntity living)) return;

        if (clicked instanceof Player) return;
        if (clicked instanceof ArmorStand) return;

        // Friendly = alles was NICHT von selbst aggressiv ist (Enemy)
        if (living instanceof Enemy) {
            event.setCancelled(true);
            msg(player, ChatColor.RED + "Dieses Mob greift von selbst an -> nicht erlaubt.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1.2f);
            return;
        }

        if (living.getVehicle() != null) {
            event.setCancelled(true);
            msg(player, ChatColor.RED + "Das Mob sitzt schon irgendwo drauf.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1.2f);
            return;
        }

        event.setCancelled(true);

        Location spawn = player.getLocation().clone().add(0, HEAD_OFFSET, 0);
        ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(spawn, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setCollidable(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);

        try { living.setAI(false); } catch (Throwable ignored) {}
        living.setGravity(false);
        living.setCollidable(false);
        living.setSilent(true);

        stand.addPassenger(living);

        carrying.put(player.getUniqueId(), new CarryState(living.getUniqueId(), stand.getUniqueId()));

        msg(player, ChatColor.GREEN + "Aufgehoben: " + ChatColor.WHITE + niceName(living.getType()));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        CarryState st = carrying.get(player.getUniqueId());
        if (st == null) return;

        event.setCancelled(true);

        Entity mobE = Bukkit.getEntity(st.mobId);
        Entity standE = Bukkit.getEntity(st.standId);

        if (!(mobE instanceof LivingEntity mob) || !(standE instanceof ArmorStand stand)) {
            dropSilently(player.getUniqueId());
            return;
        }

        Block target = null;
        BlockFace face = null;

        if (a == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            target = event.getClickedBlock();
            face = event.getBlockFace();
        } else {
            RayTraceResult r = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER);
            if (r != null) {
                target = r.getHitBlock();
                face = r.getHitBlockFace();
            }
        }

        if (target == null || face == null) {
            msg(player, ChatColor.RED + "Schau einen Block an, damit ich es absetzen kann.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1.2f);
            return;
        }

        Location place;

        if (face == BlockFace.DOWN) {
            place = fallbackInFront(player);
        } else {
            Block relative = target.getRelative(face);

            if (relative.getType().isSolid()) {
                place = fallbackInFront(player);
            } else {
                place = relative.getLocation().add(0.5, 0.05, 0.5);
                if (face == BlockFace.UP) {
                    place.add(0, 0.15, 0);
                }
            }
        }

        stand.removePassenger(mob);
        mob.teleport(place);

        try { mob.setAI(true); } catch (Throwable ignored) {}
        mob.setGravity(true);
        mob.setCollidable(true);
        mob.setSilent(false);

        stand.remove();
        carrying.remove(player.getUniqueId());

        msg(player, ChatColor.YELLOW + "Abgesetzt: " + ChatColor.WHITE + niceName(mob.getType()));
        player.playSound(player.getLocation(), Sound.BLOCK_WOOL_PLACE, 1f, 1.1f);
    }

    private void dropSilently(UUID playerId) {
        CarryState st = carrying.remove(playerId);
        if (st == null) return;

        Entity mobE = Bukkit.getEntity(st.mobId);
        Entity standE = Bukkit.getEntity(st.standId);

        if (standE instanceof ArmorStand stand) {
            if (mobE instanceof LivingEntity mob) {
                stand.removePassenger(mob);
                mob.teleport(stand.getLocation().clone().add(0, 0.2, 0));
                try { mob.setAI(true); } catch (Throwable ignored) {}
                mob.setGravity(true);
                mob.setCollidable(true);
                mob.setSilent(false);
            }
            stand.remove();
        } else if (mobE instanceof LivingEntity mob) {
            try { mob.setAI(true); } catch (Throwable ignored) {}
            mob.setGravity(true);
            mob.setCollidable(true);
            mob.setSilent(false);
        }
    }

    private Location fallbackInFront(Player player) {
        Location base = player.getLocation().clone();
        Vector forward = base.getDirection().setY(0).normalize().multiply(1.2);
        base.add(forward);

        int y = player.getWorld().getHighestBlockYAt(base);
        base.setY(y + 1.0);
        base.setPitch(0);
        return base;
    }

    private void msg(Player p, String text) {
        p.sendMessage(ChatColor.DARK_GRAY + "[HeadCarry] " + ChatColor.RESET + text);
    }

    private String niceName(EntityType type) {
        String[] parts = type.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }
}
