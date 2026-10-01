package dev.leon.bettermobcrucible;

import eu.northsoft.bettermob.api.BetterMobAPI;
import eu.northsoft.bettermob.api.MechanicContext;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CustomModelDataComponent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Item-Skills fuer BetterMob: Items werden in {@code items/*.yml} definiert und loesen
 * ueber {@code ~onUse}, {@code ~onSwing}, ... BetterMob-Skills aus.
 */
public final class BetterMobCrucible extends JavaPlugin implements Listener {
    private static final Pattern TRIGGER = Pattern.compile("\\s*~on([A-Za-z]+)(?::(\\d+))?\\s*$");
    private static final String PACK_NAME = "BetterMobCrucible";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<String, ItemDefinition> items = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> dispatch = new HashMap<>();
    private final Map<String, List<String>> timerSkills = new HashMap<>();
    private final Map<String, Integer> intervals = new HashMap<>();
    private long tick;

    record ItemDefinition(String id, Material material, String display, List<String> lore, int modelData,
                          List<String> skills) {}

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        saveDefaultItems();

        BetterMobAPI api = BetterMobAPI.get();
        api.registerMechanic(this, "setitemnbt", this::setItemNbt);
        api.registerMechanic(this, "setlore", this::setLore);

        getServer().getScheduler().runTask(this, this::rebuild);
        getServer().getScheduler().runTaskTimer(this, this::runTimers, 1L, 1L);
    }

    @Override
    public void onDisable() {
        // Mechanics raeumt BetterMob selbst beim Deaktivieren dieses Plugins weg.
    }

    NamespacedKey itemKey() {
        return new NamespacedKey(this, "item_id");
    }

    NamespacedKey nbtKey(String key) {
        return new NamespacedKey(this, "nbt/" + key.toLowerCase(Locale.ROOT));
    }

    private void saveDefaultItems() {
        File folder = new File(getDataFolder(), "items");
        if (!folder.exists()) {
            folder.mkdirs();
            saveResource("items/example.yml", false);
        }
    }

    // ---- Laden ------------------------------------------------------------------------

    /** Items einlesen, Skills als Pack in BetterMob ablegen und BetterMob neu laden, falls sich etwas geaendert hat. */
    private void rebuild() {
        items.clear();
        dispatch.clear();
        timerSkills.clear();
        intervals.clear();

        YamlConfiguration generated = new YamlConfiguration();
        int registered = 0;
        for (File file : itemFiles(new File(getDataFolder(), "items"))) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String itemId : yaml.getKeys(false)) {
                ConfigurationSection section = yaml.getConfigurationSection(itemId);
                if (section == null) continue;
                ItemDefinition item = parseItem(itemId, section);
                if (item == null) continue;
                items.put(itemId.toLowerCase(Locale.ROOT), item);

                for (Map.Entry<String, List<String>> group : groupByTrigger(item.skills()).entrySet()) {
                    String trigger = group.getKey();
                    String skillName = skillName(itemId, trigger);
                    generated.set(skillName + ".Skills", group.getValue());
                    if (trigger.startsWith("timer:")) {
                        intervals.put(skillName, Integer.parseInt(trigger.substring("timer:".length())));
                        timerSkills.computeIfAbsent(item.id().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(skillName);
                    } else {
                        dispatch.computeIfAbsent(item.id().toLowerCase(Locale.ROOT), k -> new HashMap<>()).put(trigger, skillName);
                    }
                    registered++;
                }
            }
        }

        syncSkillPack(generated);
        getLogger().info(items.size() + " Items mit " + registered + " Skill-Trigger(n) geladen.");
    }

    private ItemDefinition parseItem(String id, ConfigurationSection section) {
        String materialName = section.getString("Material", section.getString("Id", "STONE"));
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) {
            getLogger().warning("Item '" + id + "': Material '" + materialName + "' ist unbekannt - uebersprungen.");
            return null;
        }
        return new ItemDefinition(id, material, section.getString("Display", ""), section.getStringList("Lore"),
                section.getInt("Model", 0), section.getStringList("Skills"));
    }

    /**
     * BetterMob kennt nur Skills aus Dateien, daher schreiben wir die Item-Skills in einen
     * eigenen Pack (packs/BetterMobCrucible/skills/items.yml). Aendert sich der Inhalt,
     * laden wir BetterMob neu.
     */
    private void syncSkillPack(YamlConfiguration generated) {
        File betterMobFolder = getServer().getPluginManager().getPlugin("BetterMob").getDataFolder();
        File target = new File(betterMobFolder, "packs/" + PACK_NAME + "/skills/items.yml");
        String content = generated.saveToString();
        try {
            if (target.isFile() && Files.readString(target.toPath()).equals(content)) return;
            target.getParentFile().mkdirs();
            Files.writeString(target.toPath(), content);
        } catch (IOException e) {
            getLogger().warning("Konnte " + target + " nicht schreiben: " + e.getMessage());
            return;
        }
        BetterMobAPI.get().reload();
        getLogger().info("Item-Skills in BetterMob aktualisiert.");
    }

    static String skillName(String itemId, String trigger) {
        return ("bmc_" + itemId + "_" + trigger).toLowerCase(Locale.ROOT).replace(':', '_').replace('.', '_');
    }

    static Map<String, List<String>> groupByTrigger(List<String> lines) {
        Map<String, List<String>> byTrigger = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher matcher = TRIGGER.matcher(line);
            if (matcher.find()) {
                String trigger = matcher.group(1).toLowerCase(Locale.ROOT);
                if (matcher.group(2) != null) trigger = trigger + ":" + matcher.group(2);
                byTrigger.computeIfAbsent(trigger, k -> new ArrayList<>()).add(line.substring(0, matcher.start()).trim());
            }
        }
        return byTrigger;
    }

    private List<File> itemFiles(File root) {
        List<File> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(p -> p.toString().endsWith(".yml")).forEach(p -> found.add(p.toFile()));
        } catch (IOException e) {
            getLogger().warning("Konnte " + root + " nicht lesen: " + e.getMessage());
        }
        return found;
    }

    // ---- Items ------------------------------------------------------------------------

    ItemStack create(ItemDefinition item) {
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        if (!item.display().isEmpty()) {
            meta.displayName(LEGACY.deserialize(item.display()).decoration(TextDecoration.ITALIC, false));
        }
        if (!item.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : item.lore()) lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        }
        if (item.modelData() != 0) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(List.of((float) item.modelData()));
            meta.setCustomModelDataComponent(cmd);
        }
        meta.getPersistentDataContainer().set(itemKey(), PersistentDataType.STRING, item.id());
        stack.setItemMeta(meta);
        return stack;
    }

    String itemIdOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        String id = stack.getItemMeta().getPersistentDataContainer().get(itemKey(), PersistentDataType.STRING);
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    // ---- Trigger ----------------------------------------------------------------------

    private void fire(Player player, ItemStack stack, String trigger) {
        String itemId = itemIdOf(stack);
        if (itemId == null) return;
        Map<String, String> triggers = dispatch.get(itemId);
        if (triggers == null) return;
        String skill = triggers.get(trigger);
        if (skill != null) BetterMobAPI.get().runSkill(skill, player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> fire(event.getPlayer(), held, "use");
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> fire(event.getPlayer(), held, "swing");
            default -> { }
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        ItemStack toOffHand = event.getOffHandItem();
        ItemStack toMainHand = event.getMainHandItem();
        fire(event.getPlayer(), toOffHand, "pressf");
        String off = itemIdOf(toOffHand);
        if (off == null || !off.equals(itemIdOf(toMainHand))) fire(event.getPlayer(), toMainHand, "pressf");
    }

    @EventHandler
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        fire(event.getPlayer(), event.getPlayer().getInventory().getItem(event.getNewSlot()), "equip");
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            ItemStack stack = event.getItem().getItemStack();
            fire(player, stack, "pickup");
            fire(player, stack, "itempickup");
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        fire(event.getPlayer(), stack, "drop");
        fire(event.getPlayer(), stack, "itemdrop");
    }

    private void runTimers() {
        tick++;
        if (timerSkills.isEmpty()) return;
        for (Player player : getServer().getOnlinePlayers()) {
            String itemId = itemIdOf(player.getInventory().getItemInMainHand());
            if (itemId == null) continue;
            List<String> skills = timerSkills.get(itemId);
            if (skills == null) continue;
            for (String skill : skills) {
                if (tick % intervals.get(skill) == 0L) BetterMobAPI.get().runSkill(skill, player);
            }
        }
    }

    // ---- Mechanics --------------------------------------------------------------------

    private static String param(Map<String, String> params, String... names) {
        for (String name : names) {
            String value = params.get(name);
            if (value != null) return value;
        }
        return "";
    }

    private static LivingEntity targetOf(MechanicContext ctx) {
        return ctx.target() instanceof LivingEntity living ? living : ctx.caster();
    }

    private static ItemStack mainHand(Entity entity) {
        if (entity instanceof LivingEntity living && living.getEquipment() != null) {
            ItemStack held = living.getEquipment().getItemInMainHand();
            return held.getType().isAir() ? null : held;
        }
        return null;
    }

    /** {@code setitemnbt{key=x;value=y}} - schreibt einen String-Wert ins gehaltene Item. */
    private void setItemNbt(MechanicContext ctx) {
        LivingEntity target = targetOf(ctx);
        ItemStack held = mainHand(target);
        String key = param(ctx.params(), "key", "k");
        if (held == null || key.isEmpty()) return;
        ItemMeta meta = held.getItemMeta();
        meta.getPersistentDataContainer().set(nbtKey(key), PersistentDataType.STRING, param(ctx.params(), "value", "val", "v"));
        held.setItemMeta(meta);
        target.getEquipment().setItemInMainHand(held);
    }

    /** {@code setlore{lore=Zeile 1|&aZeile 2}} - ersetzt die Lore des gehaltenen Items. */
    private void setLore(MechanicContext ctx) {
        LivingEntity target = targetOf(ctx);
        ItemStack held = mainHand(target);
        String raw = param(ctx.params(), "lore", "l");
        if (held == null) return;
        List<Component> lore = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String line : raw.split("\\|")) lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        ItemMeta meta = held.getItemMeta();
        meta.lore(lore);
        held.setItemMeta(meta);
        target.getEquipment().setItemInMainHand(held);
    }

    // ---- Befehl -----------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> sender.sendMessage("Items: " + String.join(", ", items.keySet()));
            case "reload" -> {
                rebuild();
                sender.sendMessage("BetterMobCrucible neu geladen.");
            }
            case "give" -> {
                if (args.length < 2) {
                    sender.sendMessage("/" + label + " give <item> [spieler]");
                    return true;
                }
                ItemDefinition item = items.get(args[1].toLowerCase(Locale.ROOT));
                Player target = args.length > 2 ? getServer().getPlayer(args[2]) : sender instanceof Player p ? p : null;
                if (item == null) sender.sendMessage("Unbekanntes Item: " + args[1]);
                else if (target == null) sender.sendMessage("Spieler nicht gefunden.");
                else target.getInventory().addItem(create(item));
            }
            case "debug" -> debug(sender);
            default -> sender.sendMessage("/" + label + " <give|list|reload|debug>");
        }
        return true;
    }

    private void debug(CommandSender sender) {
        sender.sendMessage("Item skill triggers: " + dispatch);
        sender.sendMessage("Timer skills: " + timerSkills + " " + intervals);
        if (!(sender instanceof Player player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage("Nothing in your main hand.");
            return;
        }
        sender.sendMessage("Crucible item: " + itemIdOf(held));
        if (held.hasItemMeta()) {
            PersistentDataContainer pdc = held.getItemMeta().getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                if (key.getNamespace().equals(getName().toLowerCase(Locale.ROOT)) && key.getKey().startsWith("nbt/")) {
                    sender.sendMessage("  " + key.getKey().substring(4) + " = " + pdc.get(key, PersistentDataType.STRING));
                }
            }
        }
    }
}
