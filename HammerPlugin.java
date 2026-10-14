package de.hammer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class HammerPlugin extends JavaPlugin implements Listener, TabExecutor {

    private NamespacedKey key;
    // Verhindert, dass unsere eigenen Block-Events die Verzauberung erneut auslösen
    private final Set<UUID> active = new HashSet<>();

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "hammer_size");
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("hammer").setExecutor(this);
        getCommand("hammer").setTabCompleter(this);
        getLogger().info("HammerEnchant aktiviert.");
    }

    // ---------------------------------------------------------------- Abbauen

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (active.contains(player.getUniqueId())) return;
        if (player.isSneaking()) return; // Schleichen = normal abbauen

        ItemStack tool = player.getInventory().getItemInMainHand();
        int size = getSize(tool);
        if (size == 0) return;

        Block center = event.getBlock();
        RayTraceResult ray = player.rayTraceBlocks(7.0);
        BlockFace face = (ray != null && ray.getHitBlockFace() != null)
                ? ray.getHitBlockFace() : BlockFace.UP;

        int start = -((size - 1) / 2);
        int end = start + size - 1;

        active.add(player.getUniqueId());
        try {
            outer:
            for (int a = start; a <= end; a++) {
                for (int b = start; b <= end; b++) {
                    if (a == 0 && b == 0) continue;
                    Block target = switch (face) {
                        case UP, DOWN -> center.getRelative(a, 0, b);
                        case NORTH, SOUTH -> center.getRelative(a, b, 0);
                        default -> center.getRelative(0, a, b);
                    };
                    if (!breakExtra(player, tool, target)) break outer;
                }
            }
        } finally {
            active.remove(player.getUniqueId());
        }
    }

    /** @return false, wenn die Spitzhacke kaputtgegangen ist und die Schleife stoppen soll */
    private boolean breakExtra(Player player, ItemStack tool, Block target) {
        Material type = target.getType();
        if (type.isAir() || type.getHardness() < 0) return true; // Luft, Bedrock usw.
        if (target.isLiquid()) return true;
        if (!target.isPreferredTool(tool)) return true;          // nur was das Werkzeug abbauen kann
        if (target.getState() instanceof TileState) return true; // keine Kisten, Öfen, Spawner ...

        // Schutz-Plugins (WorldGuard etc.) bekommen ein echtes Break-Event
        BlockBreakEvent ev = new BlockBreakEvent(target, player);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return true;

        if (player.getGameMode() == GameMode.CREATIVE || !ev.isDropItems()) {
            target.setType(Material.AIR);
            return true;
        }

        target.breakNaturally(tool);
        return damage(player, tool);
    }

    private boolean damage(Player player, ItemStack tool) {
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) return true;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return true;

        damageable.setDamage(damageable.getDamage() + 1);
        if (damageable.getDamage() >= tool.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            return false;
        }
        tool.setItemMeta(damageable);
        return true;
    }

    // ----------------------------------------------------- Verzauberung lesen/setzen

    private boolean isHammerTool(ItemStack tool) {
        String name = tool.getType().name();
        return name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL");
    }

    private int getSize(ItemStack tool) {
        if (tool == null || !isHammerTool(tool)) return 0;
        ItemMeta meta = tool.getItemMeta();
        if (meta == null) return 0;
        Integer value = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    private void applySize(ItemStack tool, int size) {
        ItemMeta meta = tool.getItemMeta();
        if (size > 0) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, size);
        } else {
            meta.getPersistentDataContainer().remove(key);
        }

        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(c -> PlainTextComponentSerializer.plainText().serialize(c).startsWith("Hammer"));
        if (size > 0) {
            String roman = size == 3 ? "III" : size == 4 ? "IV" : "V";
            lore.add(Component.text("Hammer " + roman + " (" + size + "x" + size + ")", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore.isEmpty() ? null : lore);
        meta.setEnchantmentGlintOverride(size > 0 ? Boolean.TRUE : null);
        tool.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ Befehl

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("hammer.admin")) {
            sender.sendMessage(Component.text("Dazu hast du keine Rechte.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Benutzung: /hammer <3|4|5|off> [Spieler]", NamedTextColor.YELLOW));
            return true;
        }

        int size;
        switch (args[0].toLowerCase()) {
            case "3" -> size = 3;
            case "4" -> size = 4;
            case "5" -> size = 5;
            case "off" -> size = 0;
            default -> {
                sender.sendMessage(Component.text("Benutzung: /hammer <3|4|5|off> [Spieler]", NamedTextColor.YELLOW));
                return true;
            }
        }

        Player target;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Spieler nicht gefunden.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("Bitte einen Spieler angeben.");
            return true;
        }

        ItemStack tool = target.getInventory().getItemInMainHand();
        if (!isHammerTool(tool)) {
            sender.sendMessage(Component.text("Der Spieler muss eine Spitzhacke oder Schaufel in der Hand halten.", NamedTextColor.RED));
            return true;
        }

        applySize(tool, size);
        String msg = size > 0
                ? "Hammer " + size + "x" + size + " wurde auf das Werkzeug gelegt."
                : "Hammer wurde vom Werkzeug entfernt.";
        sender.sendMessage(Component.text(msg, NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("3", "4", "5", "off")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
