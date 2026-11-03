package dev.eventserver.plotarmor;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.KineticWeapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * PlotArmor
 *  /plotarmor <players> [on|off]  gives (or removes) for the chosen players:
 *     - +1 block of reach (attack reach and block reach)
 *     - an invisible Regeneration I at low health (no icon, no particles)
 *     - slower hunger drain (this also makes Lunge cost less hunger)
 *  On for EVERYONE, no command needed (1.21.11 spears):
 *     - the charge attack activates sooner (no waiting for the animation)
 *     - spears deal more damage (the multiplier also scales Sharpness)
 */
public final class PlotArmor extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey keyFlag;
    private NamespacedKey keyReach;
    private final Set<UUID> regenMode = new HashSet<>();
    private boolean spearsAvailable;
    private boolean spearWarned;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyFlag = new NamespacedKey(this, "plot_armor");
        keyReach = new NamespacedKey(this, "reach");
        try {
            Class.forName("io.papermc.paper.datacomponent.item.KineticWeapon");
            spearsAvailable = true;
        } catch (Throwable t) {
            spearsAvailable = false;
            getLogger().warning("Spear tweaks are disabled: they need Paper 1.21.11 or newer.");
        }
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("plotarmor");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::regenTick, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::spearTick, 2L, 2L);
        for (Player p : Bukkit.getOnlinePlayers()) syncPlayer(p);
        getLogger().info("PlotArmor enabled. Spear tweaks: " + (spearsAvailable ? "on" : "off"));
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (regenMode.remove(p.getUniqueId())) removeHiddenRegen(p);
        }
    }

    // ------------------------------------------------------------------ plot armor on/off

    private boolean has(Player p) {
        return p.getPersistentDataContainer().has(keyFlag, PersistentDataType.BYTE);
    }

    private void grant(Player p) {
        p.getPersistentDataContainer().set(keyFlag, PersistentDataType.BYTE, (byte) 1);
        setReach(p, true);
    }

    private void revoke(Player p) {
        p.getPersistentDataContainer().remove(keyFlag);
        setReach(p, false);
        if (regenMode.remove(p.getUniqueId())) removeHiddenRegen(p);
    }

    /** Re-applies everything for a player that just joined / after a reload. */
    private void syncPlayer(Player p) {
        regenMode.remove(p.getUniqueId());
        removeHiddenRegen(p); // a leftover from before a restart; the tick re-adds it if still needed
        setReach(p, has(p));
    }

    private void setReach(Player p, boolean on) {
        double extra = getConfig().getDouble("extra-reach", 1.0);
        Attribute[] ranges = {Attribute.ENTITY_INTERACTION_RANGE, Attribute.BLOCK_INTERACTION_RANGE};
        for (Attribute at : ranges) {
            AttributeInstance inst = p.getAttribute(at);
            if (inst == null) continue;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (keyReach.equals(m.getKey())) inst.removeModifier(m);
            }
            if (on) {
                inst.addModifier(new AttributeModifier(keyReach, extra, AttributeModifier.Operation.ADD_NUMBER));
            }
        }
    }

    // ------------------------------------------------------------------ invisible regeneration

    /** Our regen: level I, no icon, no particles, not infinite (so other plugins' effects are never touched). */
    private boolean isHiddenRegen(PotionEffect e) {
        return e != null && !e.hasIcon() && !e.hasParticles() && !e.isInfinite() && e.getAmplifier() == 0;
    }

    private void removeHiddenRegen(Player p) {
        PotionEffect cur = p.getPotionEffect(PotionEffectType.REGENERATION);
        if (isHiddenRegen(cur)) p.removePotionEffect(PotionEffectType.REGENERATION);
    }

    private void regenTick() {
        double start = getConfig().getDouble("regen.start-hearts", 3.0) * 2.0;
        double stop = Math.max(start, getConfig().getDouble("regen.stop-hearts", 5.0) * 2.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!has(p) || p.isDead()) {
                if (regenMode.remove(u)) removeHiddenRegen(p);
                continue;
            }
            double h = p.getHealth();
            if (h <= start) {
                regenMode.add(u);
            } else if (h >= stop) {
                if (regenMode.remove(u)) removeHiddenRegen(p);
                continue;
            }
            if (regenMode.contains(u) && p.getPotionEffect(PotionEffectType.REGENERATION) == null) {
                // a finite duration (not infinite) so the normal "heal every 2.5 seconds" timing works
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 72000, 0, false, false, false));
            }
        }
    }

    // ------------------------------------------------------------------ slower hunger

    @EventHandler(ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent e) {
        if (e.getEntity() instanceof Player p && has(p)) {
            double mult = getConfig().getDouble("hunger.exhaustion-multiplier", 0.5);
            e.setExhaustion((float) (e.getExhaustion() * mult)); // covers walking, sprinting, fighting AND Lunge
        }
    }

    // ------------------------------------------------------------------ spears (everyone)

    private static boolean isSpear(ItemStack it) {
        return it != null && it.getType().name().endsWith("_SPEAR");
    }

    private boolean spearsOn() {
        return spearsAvailable && getConfig().getBoolean("spears.enabled", true);
    }

    /** Wrapper so the 1.21.11-only classes are never touched on older servers. */
    private static final class Spears {
        /** Lowers the charge delay of this spear. Returns true if it changed anything. */
        static boolean speedUp(ItemStack it, int delay) {
            KineticWeapon kw = it.getData(DataComponentTypes.KINETIC_WEAPON);
            if (kw == null || kw.delayTicks() <= delay) return false;
            KineticWeapon.Builder b = KineticWeapon.kineticWeapon()
                    .contactCooldownTicks(kw.contactCooldownTicks())
                    .delayTicks(delay)
                    .damageConditions(kw.damageConditions())
                    .knockbackConditions(kw.knockbackConditions())
                    .dismountConditions(kw.dismountConditions())
                    .forwardMovement(kw.forwardMovement())
                    .damageMultiplier(kw.damageMultiplier())
                    .sound(kw.sound())
                    .hitSound(kw.hitSound());
            it.setData(DataComponentTypes.KINETIC_WEAPON, b.build());
            return true;
        }

        static int delayOf(ItemStack it) {
            KineticWeapon kw = it.getData(DataComponentTypes.KINETIC_WEAPON);
            return kw == null ? 0 : kw.delayTicks();
        }
    }

    private void patchHand(Player p, boolean mainHand, int delay) {
        ItemStack it = mainHand ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
        if (!isSpear(it)) return;
        try {
            if (!Spears.speedUp(it, delay)) return;
            // make sure the change really landed on the stack inside the inventory
            ItemStack now = mainHand ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
            if (Spears.delayOf(now) > delay) {
                if (mainHand) p.getInventory().setItemInMainHand(it);
                else p.getInventory().setItemInOffHand(it);
            }
        } catch (Throwable t) {
            disableSpears(t);
        }
    }

    private void disableSpears(Throwable t) {
        spearsAvailable = false;
        if (!spearWarned) {
            spearWarned = true;
            getLogger().warning("Spear speed-up failed, so it is switched off (damage boost keeps working): " + t);
        }
    }

    private void spearTick() {
        if (!spearsOn()) return;
        int delay = Math.max(0, getConfig().getInt("spears.charge-delay-ticks", 0));
        for (Player p : Bukkit.getOnlinePlayers()) {
            patchHand(p, true, delay);
            patchHand(p, false, delay);
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        if (!spearsOn()) return;
        ItemStack it = e.getPlayer().getInventory().getItem(e.getNewSlot());
        if (!isSpear(it)) return;
        try {
            Spears.speedUp(it, Math.max(0, getConfig().getInt("spears.charge-delay-ticks", 0)));
        } catch (Throwable t) {
            disableSpears(t);
        }
    }

    /** More spear damage. The multiplier is applied to the whole hit, so Sharpness is scaled too. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpearHit(EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("spears.enabled", true)) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.THORNS) return;
        if (!isSpear(p.getInventory().getItemInMainHand())) return;
        double mult = getConfig().getDouble("spears.damage-multiplier", 1.5);
        if (mult != 1.0) e.setDamage(e.getDamage() * mult);
    }

    // ------------------------------------------------------------------ player events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        syncPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        regenMode.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        regenMode.remove(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------ command

    private void msg(CommandSender s, String text, NamedTextColor c) {
        s.sendMessage(Component.text(text, c));
    }

    private List<Player> targets(CommandSender s, String sel) {
        List<Player> out = new ArrayList<>();
        if (sel.startsWith("@")) {
            try {
                for (Entity e : Bukkit.selectEntities(s, sel)) {
                    if (e instanceof Player p) out.add(p);
                }
            } catch (IllegalArgumentException ex) {
                msg(s, "Invalid selector: " + sel, NamedTextColor.RED);
                return out;
            }
        } else {
            Player p = Bukkit.getPlayerExact(sel);
            if (p != null) out.add(p);
        }
        if (out.isEmpty()) msg(s, "No online players matched '" + sel + "'.", NamedTextColor.RED);
        return out;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            msg(s, "/plotarmor <players>        give plot armor", NamedTextColor.YELLOW);
            msg(s, "/plotarmor <players> off    remove plot armor", NamedTextColor.YELLOW);
            msg(s, "/plotarmor list             who has it right now", NamedTextColor.YELLOW);
            msg(s, "/plotarmor reload           reload config.yml", NamedTextColor.YELLOW);
            return true;
        }
        String first = a[0].toLowerCase(Locale.ROOT);
        if (first.equals("reload")) {
            reloadConfig();
            for (Player p : Bukkit.getOnlinePlayers()) syncPlayer(p);
            msg(s, "PlotArmor config reloaded.", NamedTextColor.GREEN);
            return true;
        }
        if (first.equals("list")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (has(p)) names.add(p.getName());
            msg(s, names.isEmpty() ? "Nobody online has plot armor." : "Plot armor: " + String.join(", ", names),
                    NamedTextColor.AQUA);
            return true;
        }
        boolean on = true;
        if (a.length >= 2) {
            String m = a[1].toLowerCase(Locale.ROOT);
            if (m.equals("off") || m.equals("remove")) on = false;
            else if (!m.equals("on") && !m.equals("give")) {
                msg(s, "Usage: /plotarmor <players> [on|off]", NamedTextColor.RED);
                return true;
            }
        }
        int n = 0;
        for (Player p : targets(s, a[0])) {
            if (on) {
                grant(p);
                p.sendMessage(Component.text("You have Plot Armor.", NamedTextColor.GREEN));
            } else {
                revoke(p);
                p.sendMessage(Component.text("Your Plot Armor was removed.", NamedTextColor.GRAY));
            }
            n++;
        }
        if (n > 0) msg(s, (on ? "Gave plot armor to " : "Removed plot armor from ") + n + " player(s).",
                NamedTextColor.GREEN);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("list", "reload", "@a", "@p", "@r", "@s"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 2) {
            out.addAll(List.of("on", "off"));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
