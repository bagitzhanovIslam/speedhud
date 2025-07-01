package k0k0ska.speedhud;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

public class SpeedDisplayHUD extends JavaPlugin {

    private File topspeedFile;
    private YamlConfiguration topspeedConfig;
    private final Set<String> recordingPlayers = new HashSet<>();
    private final Map<String, Double> currentMaxSpeeds = new HashMap<>();

    private static final DecimalFormat DF = new DecimalFormat("0.00");
    private final Set<Player> enabledPlayers = new HashSet<>();
    private final Map<Player, String> playerSelectedUnits = new HashMap<>(); 
    private final Map<Player, String> playerSelectedTopUnits = new HashMap<>(); 

    private static class SpeedUnit {
        @SuppressWarnings("unused")
        String id;
        double multiplier;
        String displayNameKey;
        double greenThreshold;
        double yellowThreshold;

        public SpeedUnit(String id, double multiplier, String displayNameKey, double greenThreshold, double yellowThreshold) {
            this.id = id;
            this.multiplier = multiplier;
            this.displayNameKey = displayNameKey;
            this.greenThreshold = greenThreshold;
            this.yellowThreshold = yellowThreshold;
        }
    }
    
    private final Map<String, SpeedUnit> availableUnits = new LinkedHashMap<>();
    private String defaultUnitId;
    private String defaultTopSpeedDisplayUnit;

    private String cmdEnable, cmdDisable, cmdUnit, cmdReload, cmdHelp, cmdStartRecordSpeed, cmdTopSpeed, cmdTopToggleUnit;
    private final Map<UUID, Location> lastLocations = new HashMap<>();

    private FileConfiguration messagesConfig;
    private String currentLanguage;

    private final Map<UUID, List<Double>> recentSpeeds = new HashMap<>();
    private static final int MAX_HISTORY = 5;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        loadMessages();
        setupTopSpeedFile();

        PluginCommand cmd = getCommand("speedhud");
        if (cmd != null) {
            cmd.setExecutor(this);
        } else {
            getLogger().severe(getMessage("command_not_found_in_yml", null));
        }

        getLogger().info(getMessage("plugin_enabled", null));
        startSpeedTask();
    }

    @Override
    public void onDisable() {
        getLogger().info(getMessage("plugin_disabled", null));
    }

    private void setupTopSpeedFile() {
        topspeedFile = new File(getDataFolder(), "topspeed.yml");
        if (!topspeedFile.exists()) {
            try {
                topspeedFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe(getMessage("topspeed_file_creation_failed", null));
            }
        }
        topspeedConfig = YamlConfiguration.loadConfiguration(topspeedFile);
    }

    @SuppressWarnings("LoggerStringConcat")
    private void loadConfigValues() {
        reloadConfig();
        String langValue = getConfig().getString("language", "ru");
        currentLanguage = (langValue == null ? "ru" : langValue).toLowerCase();
        String defaultUnitRaw = getConfig().getString("default_unit", "kmh");
        defaultUnitId = (defaultUnitRaw == null ? "kmh" : defaultUnitRaw).toLowerCase();
        String defaultTopSpeedDisplayUnitRaw = getConfig().getString("default_topspeed_display_unit", "kmh");
        defaultTopSpeedDisplayUnit = (defaultTopSpeedDisplayUnitRaw == null ? "kmh" : defaultTopSpeedDisplayUnitRaw).toLowerCase();


        availableUnits.clear();
        ConfigurationSection unitsSection = getConfig().getConfigurationSection("speed_units");
        if (unitsSection != null) {
            for (String unitId : unitsSection.getKeys(false)) {
                ConfigurationSection unitConfig = unitsSection.getConfigurationSection(unitId);
                if (unitConfig != null) {
                    boolean enabled = unitConfig.getBoolean("enabled", true);
                    if (!enabled) {
                        continue;
                    }

                    double multiplier = unitConfig.getDouble("multiplier", 1.0);
                    String displayNameKey = unitConfig.getString("display_name_key", "unit_" + unitId);
                    double green = unitConfig.getDouble("color_thresholds.green", 0.0);
                    double yellow = unitConfig.getDouble("color_thresholds.yellow", 0.0);
                    availableUnits.put(unitId, new SpeedUnit(unitId, multiplier, displayNameKey, green, yellow));
                }
            }
        }
        if (availableUnits.isEmpty()) {
            getLogger().warning("Единицы измерения скорости не определены в config.yml или некорректны. Использую стандартные.");
            availableUnits.put("ms", new SpeedUnit("ms", 1.0, "unit_ms", 2.8, 5.5));
            availableUnits.put("kmh", new SpeedUnit("kmh", 3.6, "unit_kmh", 10.0, 20.0));
            defaultUnitId = "kmh";
            defaultTopSpeedDisplayUnit = "kmh";
        }
        if (!availableUnits.containsKey(defaultUnitId)) {
            getLogger().warning("Единица по умолчанию '" + defaultUnitId + "' не найдена среди определенных/включенных единиц. Устанавливаю первую доступную.");
            if (!availableUnits.isEmpty()) {
                defaultUnitId = availableUnits.keySet().iterator().next(); 
            } else {
                defaultUnitId = "ms";
                getLogger().severe("Нет доступных единиц измерения. Переключение не будет работать.");
            }
        }
        if (!availableUnits.containsKey(defaultTopSpeedDisplayUnit)) {
            getLogger().warning("Единица по умолчанию для отображения топа '" + defaultTopSpeedDisplayUnit + "' не найдена среди определенных/включенных единиц. Устанавливаю первую доступную.");
            if (!availableUnits.isEmpty()) {
                defaultTopSpeedDisplayUnit = availableUnits.keySet().iterator().next(); 
            } else {
                defaultTopSpeedDisplayUnit = "ms";
            }
        }

        String enableStr = getConfig().getString("subcommands.enable", "on");
        cmdEnable = enableStr == null ? "on" : enableStr.toLowerCase();

        String disableStr = getConfig().getString("subcommands.disable", "off");
        cmdDisable = disableStr == null ? "off" : disableStr.toLowerCase();

        String unitStr = getConfig().getString("subcommands.toggle_unit", "unit");
        cmdUnit = unitStr == null ? "unit" : unitStr.toLowerCase();

        String reloadStr = getConfig().getString("subcommands.reload", "reload");
        cmdReload = reloadStr == null ? "reload" : reloadStr.toLowerCase();

        String helpStr = getConfig().getString("subcommands.help", "help");
        cmdHelp = helpStr == null ? "help" : helpStr.toLowerCase();

        String startRecordSpeedStr = getConfig().getString("subcommands.startrecordspeed", "startrecordspeed");
        cmdStartRecordSpeed = startRecordSpeedStr == null ? "startrecordspeed" : startRecordSpeedStr.toLowerCase();

        String topSpeedStr = getConfig().getString("subcommands.topspeed", "topspeed");
        cmdTopSpeed = topSpeedStr == null ? "topspeed" : topSpeedStr.toLowerCase();

        String topToggleUnitStr = getConfig().getString("subcommands.toptoggleunit", "toptoggleunit");
        cmdTopToggleUnit = topToggleUnitStr == null ? "toptoggleunit" : topToggleUnitStr.toLowerCase();
    }

    @SuppressWarnings("LoggerStringConcat")
    private void loadMessages() {
        String fileName = "lang/messages_" + currentLanguage + ".yml";
        InputStream stream = getResource(fileName);
        if (stream == null) {
            getLogger().warning("Языковой файл " + fileName + " не найден! Использую английский по умолчанию (messages_en.yml).");
            stream = getResource("lang/messages_en.yml");
            if (stream == null) {
                getLogger().severe("Английский языковой файл (messages_en.yml) также не найден! Сообщения будут отсутствовать.");
                messagesConfig = new YamlConfiguration();
                return;
            }
        }
        messagesConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
    }

    private String getMessage(String path, Map<String, String> placeholders) {
        String message = messagesConfig.getString("messages." + path, path);
        
        String prefix = messagesConfig.getString("command_prefix", "SpeedHUD");
        
        if (message == null) { 
            message = path;
        }
        
        message = message.replace("%prefix%", prefix);

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void startSpeedTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String name = player.getName();
                    UUID uuid = player.getUniqueId();
                    Location current = player.getLocation();
                    Location last = lastLocations.get(uuid);

                    double speed = 0.0;

                    if (last != null && current != null) {
                        World lastWorld = last.getWorld();
                        World currentWorld = current.getWorld();
                        if (lastWorld != null && currentWorld != null && currentWorld.equals(lastWorld)) {
                            double distance = current.distance(last);
                            speed = distance * 5; 
                        }
                    }

                    List<Double> history = recentSpeeds.computeIfAbsent(uuid, k -> new ArrayList<>());
                    history.add(speed);
                    if (history.size() > MAX_HISTORY) history.remove(0);

                    double avgSpeed = history.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

                    if (current != null) {
                        lastLocations.put(uuid, current.clone());
                    }

                    if (recordingPlayers.contains(name)) {
                        double prev = currentMaxSpeeds.getOrDefault(name, 0.0);
                        currentMaxSpeeds.put(name, Math.max(prev, avgSpeed));
                    }

                    if (!enabledPlayers.contains(player)) continue;

                    String currentUnitId = playerSelectedUnits.getOrDefault(player, defaultUnitId);
                    SpeedUnit selectedUnit = availableUnits.getOrDefault(currentUnitId, availableUnits.get(defaultUnitId));

                    double displaySpeed = avgSpeed * selectedUnit.multiplier;
                    String unitDisplayName = getMessage(selectedUnit.displayNameKey, null);

                    ChatColor color;
                    if (displaySpeed < selectedUnit.greenThreshold) {
                        color = ChatColor.GREEN;
                    } else if (displaySpeed < selectedUnit.yellowThreshold) {
                        color = ChatColor.YELLOW;
                    } else {
                        color = ChatColor.RED;
                    }

                    String message = getMessage("hud_display", null) + color + DF.format(displaySpeed) + " " + unitDisplayName;
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
                }
            }
        }.runTaskTimer(this, 0L, 4L);
    }

    @Override
    @SuppressWarnings("LoggerStringConcat")
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender == null || (!(sender instanceof Player) && !(sender instanceof ConsoleCommandSender))) {
            if (sender != null) {
                sender.sendMessage(getMessage("no_console_player_command", null));
            }
            return true;
        }

        Map<String, String> helpPlaceholders = new HashMap<>();
        helpPlaceholders.put("label", label);
        helpPlaceholders.put("enable", cmdEnable);
        helpPlaceholders.put("disable", cmdDisable);
        helpPlaceholders.put("unit", cmdUnit);
        helpPlaceholders.put("reload", cmdReload);
        helpPlaceholders.put("help", cmdHelp);
        helpPlaceholders.put("startrecordspeed", cmdStartRecordSpeed);
        helpPlaceholders.put("topspeed", cmdTopSpeed);
        helpPlaceholders.put("toptoggleunit", cmdTopToggleUnit);


        if (args.length == 0 || args[0].equalsIgnoreCase(cmdHelp)) {
            sender.sendMessage(getMessage("help_header", helpPlaceholders));
            sender.sendMessage(getMessage("help_enable", helpPlaceholders));
            sender.sendMessage(getMessage("help_disable", helpPlaceholders));
            sender.sendMessage(getMessage("help_unit", helpPlaceholders));
            sender.sendMessage(getMessage("help_reload", helpPlaceholders));
            sender.sendMessage(getMessage("help_help", helpPlaceholders));
            sender.sendMessage(getMessage("help_startrecordspeed", helpPlaceholders));
            sender.sendMessage(getMessage("help_topspeed", helpPlaceholders));
            sender.sendMessage(getMessage("help_toptoggleunit", helpPlaceholders));
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals(cmdStartRecordSpeed)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getMessage("start_record_player_only", null));
                return true;
            }
            if (!sender.hasPermission("speedhud.startrecordspeed")) {
                 sender.sendMessage(getMessage("no_permission", null));
                 return true;
            }

            Player p = (Player) sender;
            String name = p.getName();

            if (recordingPlayers.contains(name)) {
                sender.sendMessage(getMessage("start_record_already_recording", null));
                return true;
            }

            recordingPlayers.add(name);
            currentMaxSpeeds.put(name, 0.0);
            sender.sendMessage(getMessage("start_record_started", null));

            new BukkitRunnable() {
                @Override
                public void run() {
                    recordingPlayers.remove(name);
                    double recordedSpeed = currentMaxSpeeds.getOrDefault(name, 0.0);
                    String unitIdToSave = playerSelectedUnits.getOrDefault(p, defaultUnitId); 
                    SpeedUnit selectedUnit = availableUnits.getOrDefault(unitIdToSave, availableUnits.get(defaultUnitId));

                    topspeedConfig.set(name + ".speed_ms", recordedSpeed); 
                    topspeedConfig.set(name + ".unit_id", unitIdToSave); 
                    try {
                        topspeedConfig.save(topspeedFile);
                    } catch (IOException e) {
                        getLogger().severe(getMessage("topspeed_file_save_failed", null));
                    }
                    
                    Map<String, String> finishPlaceholders = new HashMap<>();
                    finishPlaceholders.put("speed", String.format("%.2f", recordedSpeed * selectedUnit.multiplier));
                    finishPlaceholders.put("unit", getMessage(selectedUnit.displayNameKey, null));
                    sender.sendMessage(getMessage("start_record_finished", finishPlaceholders));
                }
            }.runTaskLater(this, 20L * 10);
            return true;
        }

        if (sub.equals(cmdTopSpeed)) {
            if (!sender.hasPermission("speedhud.topspeed")) {
                 sender.sendMessage(getMessage("no_permission", null));
                 return true;
            }

            if (topspeedConfig.getKeys(false).isEmpty()) {
                sender.sendMessage(getMessage("topspeed_no_data", null));
                return true;
            }

            class PlayerTopSpeedData {
                String playerName;
                double speedMs; 
                @SuppressWarnings("unused")
                String unitId; 

                public PlayerTopSpeedData(String playerName, double speedMs, String unitId) {
                    this.playerName = playerName;
                    this.speedMs = speedMs;
                    this.unitId = unitId;
                }
            }

            List<PlayerTopSpeedData> allSpeeds = new ArrayList<>();
            for (String playerName : topspeedConfig.getKeys(false)) {
                if (topspeedConfig.contains(playerName + ".speed_ms")) { 
                    double speedMs = topspeedConfig.getDouble(playerName + ".speed_ms");
                    String unitId = topspeedConfig.getString(playerName + ".unit_id", defaultUnitId);
                    allSpeeds.add(new PlayerTopSpeedData(playerName, speedMs, unitId));
                }
            }

            allSpeeds.sort(Comparator.comparingDouble((PlayerTopSpeedData data) -> data.speedMs).reversed());

            sender.sendMessage(getMessage("topspeed_header", null));
            int i = 1;
            
            String displayTopUnitId = defaultTopSpeedDisplayUnit;
            if (sender instanceof Player player) {
                displayTopUnitId = playerSelectedTopUnits.getOrDefault(player, defaultTopSpeedDisplayUnit);
            }
            SpeedUnit displayUnit = availableUnits.getOrDefault(displayTopUnitId, availableUnits.get(defaultTopSpeedDisplayUnit));
            
            if (displayUnit == null) {
                getLogger().warning("Единица измерения для отображения топа ('" + displayTopUnitId + "') не найдена или недоступна. Использую 'м/с'.");
                displayUnit = new SpeedUnit("ms", 1.0, "unit_ms", 0.0, 0.0); 
            }

            for (PlayerTopSpeedData entry : allSpeeds) {
                Map<String, String> entryPlaceholders = new HashMap<>();
                entryPlaceholders.put("rank", String.valueOf(i++));
                entryPlaceholders.put("player", entry.playerName);
                entryPlaceholders.put("speed", String.format("%.2f", entry.speedMs * displayUnit.multiplier)); 
                entryPlaceholders.put("unit", getMessage(displayUnit.displayNameKey, null));
                sender.sendMessage(getMessage("topspeed_entry", entryPlaceholders));
            }
            return true;
        }

        if (sub.equals(cmdTopToggleUnit)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getMessage("no_console_player_command", null));
                return true;
            }
            if (!sender.hasPermission("speedhud.toptoggleunit")) {
                 sender.sendMessage(getMessage("no_permission", null));
                 return true;
            }

            Player p = (Player) sender;
            String currentTopUnitId = playerSelectedTopUnits.getOrDefault(p, defaultTopSpeedDisplayUnit);

            List<String> unitIds = new ArrayList<>(availableUnits.keySet());
            if (unitIds.isEmpty()) {
                sender.sendMessage(getMessage("topspeed_no_available_units", null));
                return true;
            }

            int currentIndex = unitIds.indexOf(currentTopUnitId);
            if (currentIndex == -1) {
                currentIndex = 0; 
            } else {
                currentIndex = (currentIndex + 1) % unitIds.size();
            }
            
            String nextUnitId = unitIds.get(currentIndex);

            playerSelectedTopUnits.put(p, nextUnitId);
            SpeedUnit nextUnit = availableUnits.get(nextUnitId);
            
            Map<String, String> unitPlaceholders = new HashMap<>();
            unitPlaceholders.put("unit", getMessage(nextUnit.displayNameKey, null));
            sender.sendMessage(getMessage("topspeed_unit_changed", unitPlaceholders));
            return true;
        }

        if (sub.equals(cmdEnable)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getMessage("no_console_player_command", null));
                return true;
            }
            Player p = (Player) sender;
            if (!p.hasPermission("speedhud.toggle")) {
                sender.sendMessage(getMessage("no_permission", null));
                return true;
            }
            enabledPlayers.add(p);
            if (!availableUnits.containsKey(playerSelectedUnits.getOrDefault(p, defaultUnitId))) {
                playerSelectedUnits.put(p, defaultUnitId);
            } else {
                playerSelectedUnits.putIfAbsent(p, defaultUnitId); 
            }
            if (!availableUnits.containsKey(playerSelectedTopUnits.getOrDefault(p, defaultTopSpeedDisplayUnit))) {
                playerSelectedTopUnits.put(p, defaultTopSpeedDisplayUnit);
            } else {
                playerSelectedTopUnits.putIfAbsent(p, defaultTopSpeedDisplayUnit); 
            }

            sender.sendMessage(getMessage("hud_enabled", null));
            return true;
        }

        if (sub.equals(cmdDisable)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getMessage("no_console_player_command", null));
                return true;
            }
            Player p = (Player) sender;
            if (!p.hasPermission("speedhud.toggle")) {
                sender.sendMessage(getMessage("no_permission", null));
                return true;
            }
            enabledPlayers.remove(p);
            sender.sendMessage(getMessage("hud_disabled", null));
            return true;
        }

        if (sub.equals(cmdUnit)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(getMessage("no_console_player_command", null));
                return true;
            }
            Player p = (Player) sender;
            String currentUnitId = playerSelectedUnits.getOrDefault(p, defaultUnitId);

            List<String> unitIds = new ArrayList<>(availableUnits.keySet());
            if (unitIds.isEmpty()) {
                sender.sendMessage(getMessage("no_available_units", null));
                return true;
            }

            int currentIndex = unitIds.indexOf(currentUnitId);
            if (currentIndex == -1) {
                currentIndex = 0; 
            } else {
                currentIndex = (currentIndex + 1) % unitIds.size();
            }
            
            String nextUnitId = unitIds.get(currentIndex);

            playerSelectedUnits.put(p, nextUnitId);
            SpeedUnit nextUnit = availableUnits.get(nextUnitId);
            
            Map<String, String> unitPlaceholders = new HashMap<>();
            unitPlaceholders.put("unit", getMessage(nextUnit.displayNameKey, null));
            sender.sendMessage(getMessage("unit_changed", unitPlaceholders));
            return true;
        }

        if (sub.equals(cmdReload)) {
            if (!sender.hasPermission("speedhud.reload")) {
                sender.sendMessage(getMessage("no_permission", null));
                return true;
            }
            sender.sendMessage(getMessage("plugin_reloading", null));
            loadConfigValues();
            loadMessages();
            sender.sendMessage(getMessage("config_updated", null));
            return true;
        }

        sender.sendMessage(getMessage("unknown_subcommand", helpPlaceholders));
        return true;
    }

    public Map<UUID, Location> getLastLocations() {
        return lastLocations;
    }
}