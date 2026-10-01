package dev.leon.itemskills;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.placeholders.PlaceholderString;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.bukkit.events.MythicPlaceholdersLoadEvent;
import io.lumine.mythic.bukkit.events.MythicReloadedEvent;
import io.lumine.mythic.core.skills.MetaSkill;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.SkillMechanic;
import io.lumine.mythic.core.skills.placeholders.Placeholder;
import io.lumine.mythic.core.skills.placeholders.PlaceholderExecutor;
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
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemSkillsPlugin extends JavaPlugin implements Listener {
   private static final Pattern TRIGGER = Pattern.compile("\\s*~on([A-Za-z]+)(?::(\\d+))?\\s*$");
   private final Map<String, Map<String, String>> dispatch = new HashMap<>();
   private final Map<String, List<String>> timerSkills = new HashMap<>();
   private final Map<String, Integer> intervals = new HashMap<>();
   private long tick;

   public void onEnable() {
      this.getServer().getPluginManager().registerEvents(this, this);
      this.getServer().getScheduler().runTask(this, () -> {
         try {
            this.registerNbtPlaceholder(MythicBukkit.inst().getPlaceholderManager());
            this.getLogger().info("Registered the itemnbt placeholder.");
         } catch (LinkageError | Exception var2) {
            this.getLogger().warning("Could not register the itemnbt placeholder: " + var2);
         }

         this.rebuild();
      });
      this.getServer().getScheduler().runTaskTimer(this, this::runTimers, 1L, 1L);
   }

   NamespacedKey nbtKey(String key) {
      return new NamespacedKey(this, key.toLowerCase(Locale.ROOT));
   }

   @EventHandler
   public void onMythicReload(MythicReloadedEvent event) {
      this.getServer().getScheduler().runTask(this, this::rebuild);
   }

   private void rebuild() {
      this.dispatch.clear();
      this.timerSkills.clear();
      this.intervals.clear();
      Plugin mythic = this.getServer().getPluginManager().getPlugin("MythicMobs");
      if (mythic == null) {
         this.getLogger().severe("MythicMobs not found, item skills stay disabled.");
      } else {
         SkillExecutor skillManager = MythicBukkit.inst().getSkillManager();
         int registered = 0;

         for (File file : this.itemFiles(mythic.getDataFolder())) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

            for (String itemId : yaml.getKeys(false)) {
               List<String> lines = yaml.getStringList(itemId + ".Skills");
               if (!lines.isEmpty()) {
                  for (Entry<String, List<String>> group : groupByTrigger(lines).entrySet()) {
                     String trigger = group.getKey();
                     String skillName = "itemskills_" + itemId + "_" + trigger.replace(':', '_');
                     skillManager.registerSkill(skillName, new MetaSkill(skillManager, file, group.getValue()));
                     if (trigger.startsWith("timer:")) {
                        this.intervals.put(skillName, Integer.parseInt(trigger.substring("timer:".length())));
                        this.timerSkills.computeIfAbsent(itemId, k -> new ArrayList<>()).add(skillName);
                     } else {
                        this.dispatch.computeIfAbsent(itemId, k -> new HashMap<>()).put(trigger, skillName);
                     }

                     registered++;
                  }
               }
            }
         }

         this.getLogger().info("Registered " + registered + " item skill trigger(s).");
      }
   }

   static Map<String, List<String>> groupByTrigger(List<String> lines) {
      Map<String, List<String>> byTrigger = new LinkedHashMap<>();

      for (String line : lines) {
         Matcher matcher = TRIGGER.matcher(line);
         if (matcher.find()) {
            String trigger = matcher.group(1).toLowerCase(Locale.ROOT);
            if (matcher.group(2) != null) {
               trigger = trigger + ":" + matcher.group(2);
            }

            byTrigger.computeIfAbsent(trigger, k -> new ArrayList<>()).add(line.substring(0, matcher.start()).trim());
         }
      }

      return byTrigger;
   }

   private List<File> itemFiles(File root) {
      List<File> found = new ArrayList<>();

      try (Stream<Path> paths = Files.walk(root.toPath())) {
         paths.filter(p -> p.toString().endsWith(".yml")).filter(ItemSkillsPlugin::underItemsDirectory).forEach(p -> found.add(p.toFile()));
      } catch (IOException var8) {
         this.getLogger().warning("Could not scan " + root + ": " + var8.getMessage());
      }

      return found;
   }

   private static boolean underItemsDirectory(Path path) {
      for (Path part : path.getParent()) {
         if (part.toString().equalsIgnoreCase("items")) {
            return true;
         }
      }

      return false;
   }

   private void fire(Player player, ItemStack stack, String trigger) {
      if (stack != null && !stack.getType().isAir()) {
         String itemId = MythicBukkit.inst().getItemManager().getMythicTypeFromItem(stack);
         if (itemId != null) {
            Map<String, String> triggers = this.dispatch.get(itemId);
            if (triggers != null) {
               String skill = triggers.get(trigger);
               if (skill != null) {
                  MythicBukkit.inst().getAPIHelper().castSkill(player, skill);
               }
            }
         }
      }
   }

   @EventHandler
   public void onInteract(PlayerInteractEvent event) {
      if (event.getHand() == EquipmentSlot.HAND) {
         ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
         switch (event.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
               this.fire(event.getPlayer(), held, "use");
               break;
            case LEFT_CLICK_AIR:
            case LEFT_CLICK_BLOCK:
               this.fire(event.getPlayer(), held, "swing");
         }
      }
   }

   @EventHandler
   public void onSwapHands(PlayerSwapHandItemsEvent event) {
      ItemStack toOffHand = event.getOffHandItem();
      ItemStack toMainHand = event.getMainHandItem();
      this.fire(event.getPlayer(), toOffHand, "pressf");
      if (!sameMythicType(toOffHand, toMainHand)) {
         this.fire(event.getPlayer(), toMainHand, "pressf");
      }
   }

   private static boolean sameMythicType(ItemStack a, ItemStack b) {
      if (a != null && b != null) {
         String left = MythicBukkit.inst().getItemManager().getMythicTypeFromItem(a);
         return left != null && left.equals(MythicBukkit.inst().getItemManager().getMythicTypeFromItem(b));
      } else {
         return false;
      }
   }

   @EventHandler
   public void onHeldSlotChange(PlayerItemHeldEvent event) {
      this.fire(event.getPlayer(), event.getPlayer().getInventory().getItem(event.getNewSlot()), "equip");
   }

   @EventHandler
   public void onPickup(EntityPickupItemEvent event) {
      if (event.getEntity() instanceof Player player) {
         ItemStack var4 = event.getItem().getItemStack();
         this.fire(player, var4, "pickup");
         this.fire(player, var4, "itempickup");
      }
   }

   @EventHandler
   public void onDrop(PlayerDropItemEvent event) {
      ItemStack stack = event.getItemDrop().getItemStack();
      this.fire(event.getPlayer(), stack, "drop");
      this.fire(event.getPlayer(), stack, "itemdrop");
   }

   private void runTimers() {
      this.tick++;
      if (!this.timerSkills.isEmpty()) {
         for (Player player : this.getServer().getOnlinePlayers()) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!held.getType().isAir()) {
               String itemId = MythicBukkit.inst().getItemManager().getMythicTypeFromItem(held);
               if (itemId != null) {
                  List<String> skills = this.timerSkills.get(itemId);
                  if (skills != null) {
                     for (String skill : skills) {
                        if (this.tick % (long)this.intervals.get(skill).intValue() == 0L) {
                           MythicBukkit.inst().getAPIHelper().castSkill(player, skill);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      sender.sendMessage("Item skill triggers: " + this.dispatch);
      sender.sendMessage("Timer skills: " + this.timerSkills + " " + this.intervals);
      if (sender instanceof Player player) {
         ItemStack held = player.getInventory().getItemInMainHand();
         if (held.getType().isAir()) {
            sender.sendMessage("Nothing in your main hand.");
            return true;
         } else {
            sender.sendMessage("Mythic type: " + MythicBukkit.inst().getItemManager().getMythicTypeFromItem(held));
            if (held.hasItemMeta()) {
               PersistentDataContainer pdc = held.getItemMeta().getPersistentDataContainer();

               for (NamespacedKey key : pdc.getKeys()) {
                  if (key.getNamespace().equals(this.getName().toLowerCase(Locale.ROOT))) {
                     sender.sendMessage("  " + key.getKey() + " = " + (String)pdc.get(key, PersistentDataType.STRING));
                  }
               }
            }

            sender.sendMessage("itemnbt registered: " + (MythicBukkit.inst().getPlaceholderManager().getPlaceholder("itemnbt") != null));
            AbstractEntity self = BukkitAdapter.adapt(player);
            sender.sendMessage("<caster.itemnbt.trackname>   -> '" + PlaceholderString.of("<caster.itemnbt.trackname>").get(self) + "'");
            sender.sendMessage("<caster.item.nbt.trackname> -> '" + PlaceholderString.of("<caster.item.nbt.trackname>").get(self) + "'");
            return true;
         }
      } else {
         return true;
      }
   }

   @EventHandler
   public void onMechanicLoad(MythicMechanicLoadEvent event) {
      String var2 = event.getMechanicName().toLowerCase(Locale.ROOT);
      switch (var2) {
         case "setitemnbt":
            event.register(new ItemSkillsPlugin.SetItemNbtMechanic(this, event.getConfig()));
            break;
         case "setlore":
            event.register(new ItemSkillsPlugin.SetLoreMechanic(event.getConfig()));
      }
   }

   @EventHandler
   public void onPlaceholdersLoad(MythicPlaceholdersLoadEvent event) {
      this.registerNbtPlaceholder((PlaceholderExecutor)event.getManager());
   }

   private void registerNbtPlaceholder(PlaceholderExecutor placeholders) {
      placeholders.register(new String[]{"itemnbt", "item.nbt"}, Placeholder.entity((entity, key) -> {
         if (entity != null && key != null) {
            ItemStack held = mainHand(entity.getBukkitEntity());
            if (held != null && held.hasItemMeta()) {
               String value = (String)held.getItemMeta().getPersistentDataContainer().get(this.nbtKey(key), PersistentDataType.STRING);
               return value == null ? "" : value;
            } else {
               return "";
            }
         } else {
            return "";
         }
      }));
   }

   static ItemStack mainHand(Entity entity) {
      if (entity instanceof LivingEntity living && living.getEquipment() != null) {
         ItemStack held = living.getEquipment().getItemInMainHand();
         return held.getType().isAir() ? null : held;
      }

      return null;
   }

   static final class SetItemNbtMechanic extends SkillMechanic implements ITargetedEntitySkill {
      private final ItemSkillsPlugin plugin;
      private final PlaceholderString key;
      private final PlaceholderString value;

      SetItemNbtMechanic(ItemSkillsPlugin plugin, MythicLineConfig config) {
         super(MythicBukkit.inst().getSkillManager(), config.getLine(), config);
         this.plugin = plugin;
         this.key = config.getPlaceholderString(new String[]{"key", "k"}, "", new String[0]);
         this.value = config.getPlaceholderString(new String[]{"value", "val", "v"}, "", new String[0]);
      }

      public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
         ItemStack held = ItemSkillsPlugin.mainHand(target.getBukkitEntity());
         if (held == null) {
            return SkillResult.INVALID_TARGET;
         } else {
            String resolvedKey = this.key.get(data, target);
            if (resolvedKey.isEmpty()) {
               return SkillResult.INVALID_CONFIG;
            } else {
               ItemMeta meta = held.getItemMeta();
               meta.getPersistentDataContainer().set(this.plugin.nbtKey(resolvedKey), PersistentDataType.STRING, this.value.get(data, target));
               held.setItemMeta(meta);
               ((LivingEntity)target.getBukkitEntity()).getEquipment().setItemInMainHand(held);
               return SkillResult.SUCCESS;
            }
         }
      }
   }

   static final class SetLoreMechanic extends SkillMechanic implements ITargetedEntitySkill {
      private final List<PlaceholderString> lines = new ArrayList<>();

      SetLoreMechanic(MythicLineConfig config) {
         super(MythicBukkit.inst().getSkillManager(), config.getLine(), config);

         for (String line : config.getStringList(new String[]{"lore", "l"}, "")) {
            this.lines.add(PlaceholderString.of(line));
         }
      }

      public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
         ItemStack held = ItemSkillsPlugin.mainHand(target.getBukkitEntity());
         if (held == null) {
            return SkillResult.INVALID_TARGET;
         } else {
            List<Component> lore = new ArrayList<>(this.lines.size());

            for (PlaceholderString line : this.lines) {
               lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line.get(data, target)));
            }

            ItemMeta meta = held.getItemMeta();
            meta.lore(lore);
            held.setItemMeta(meta);
            ((LivingEntity)target.getBukkitEntity()).getEquipment().setItemInMainHand(held);
            return SkillResult.SUCCESS;
         }
      }
   }
}
