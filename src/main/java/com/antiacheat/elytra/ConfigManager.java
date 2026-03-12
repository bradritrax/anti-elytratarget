package com.antiacheat.elytra;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Yapilandirma yoneticisi
 * config.yml dosyasindan degerleri okur ve cache'ler
 */
public class ConfigManager {

    private final ElytraAntiCheat plugin;
    private FileConfiguration config;

    // Cache'lenmis degerler
    private long sasThresholdMs;
    private int sasMaxViolations;
    private long rocketThresholdMs;
    private int rocketMaxViolations;
    private long violationDecayMs;
    private boolean debugMode;
    private String punishmentCommand;
    private boolean broadcastPunishment;

    public ConfigManager(ElytraAntiCheat plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        loadValues();
    }

    /**
     * Tum degerleri config'den yukler
     */
    public void loadValues() {
        // SAS Ayarlari
        this.sasThresholdMs = config.getLong("sas.threshold-ms", 80L);
        this.sasMaxViolations = config.getInt("sas.max-violations", 5);

        // Rocket Ayarlari
        this.rocketThresholdMs = config.getLong("rocket.threshold-ms", 50L);
        this.rocketMaxViolations = config.getInt("rocket.max-violations", 5);

        // Genel Ayarlar
        this.violationDecayMs = config.getLong("general.violation-decay-seconds", 30L) * 1000L;
        this.debugMode = config.getBoolean("general.debug-mode", false);
        this.punishmentCommand = config.getString("general.punishment-command", "kick {player} &c[AntiCheat] {reason}");
        this.broadcastPunishment = config.getBoolean("general.broadcast-punishment", true);

        plugin.getLogger().info("[Config] Degerler yuklendi.");
    }

    /**
     * Yapilandirmayi yeniden yukler
     */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadValues();
    }

    // ==================== GETTER'LAR ====================

    public long getSasThresholdMs() {
        return sasThresholdMs;
    }

    public int getSasMaxViolations() {
        return sasMaxViolations;
    }

    public long getRocketThresholdMs() {
        return rocketThresholdMs;
    }

    public int getRocketMaxViolations() {
        return rocketMaxViolations;
    }

    public long getViolationDecayMs() {
        return violationDecayMs;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public String getPunishmentCommand() {
        return punishmentCommand;
    }

    public boolean isBroadcastPunishment() {
        return broadcastPunishment;
    }
}
