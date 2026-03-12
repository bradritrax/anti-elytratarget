package com.antiacheat.elytra;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * ElytraAntiCheat Ana Plugin Sinifi
 * 
 * Minecraft 1.17.1 sunuculari icin ElytraTarget ve Rocket Macro korumasi.
 * ProtocolLib kullanarak davranissal paket analizi yapar.
 * 
 * @author AntiCheat Developer
 * @version 1.0.0
 */
public class ElytraAntiCheat extends JavaPlugin {

    private static ElytraAntiCheat instance;
    private ElytraCombatModule combatModule;
    private ConfigManager configManager;

    @Override
    public void onEnable() {
        instance = this;
        
        // Config dosyasini olustur/yukle
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        
        // ProtocolLib kontrolu
        if (!checkProtocolLib()) {
            getLogger().severe("[ElytraAntiCheat] ProtocolLib bulunamadi! Plugin devre disi birakiliyor...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Combat modulunu baslat
        this.combatModule = new ElytraCombatModule(this);
        this.combatModule.enable();
        
        getLogger().info("=================================");
        getLogger().info("ElytraAntiCheat aktif edildi!");
        getLogger().info("Versiyon: " + getDescription().getVersion());
        getLogger().info("SAS Korumasi: Aktif");
        getLogger().info("Rocket Macro Korumasi: Aktif");
        getLogger().info("=================================");
    }

    @Override
    public void onDisable() {
        if (combatModule != null) {
            combatModule.disable();
        }
        
        getLogger().info("ElytraAntiCheat devre disi birakildi.");
    }

    /**
     * ProtocolLib'in yuklu olup olmadigini kontrol eder
     */
    private boolean checkProtocolLib() {
        return getServer().getPluginManager().getPlugin("ProtocolLib") != null;
    }

    /**
     * Singleton instance getter
     */
    public static ElytraAntiCheat getInstance() {
        return instance;
    }

    /**
     * Combat modulune erisim
     */
    public ElytraCombatModule getCombatModule() {
        return combatModule;
    }

    /**
     * Config yoneticisine erisim
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    // ==================== KOMUT ISLEYICI ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("elytraac") && 
            !command.getName().equalsIgnoreCase("eac")) {
            return false;
        }

        // Yetki kontrolu
        if (!sender.hasPermission("elytraac.admin")) {
            sender.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkiniz yok!");
            return true;
        }

        // Arguman kontrolu
        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "status" -> handleStatusCommand(sender, args);
            case "reset" -> handleResetCommand(sender, args);
            case "resetall" -> handleResetAllCommand(sender);
            case "reload" -> handleReloadCommand(sender);
            case "help" -> sendHelpMessage(sender);
            default -> {
                sender.sendMessage(ChatColor.RED + "Bilinmeyen komut: " + subCommand);
                sendHelpMessage(sender);
            }
        }

        return true;
    }

    /**
     * /elytraac status <oyuncu> - Oyuncu durumunu gosterir
     */
    private void handleStatusCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Kullanim: /elytraac status <oyuncu>");
            return;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Oyuncu bulunamadi: " + playerName);
            return;
        }

        UUID playerId = target.getUniqueId();
        String status = combatModule.getPlayerViolationStatus(playerId);

        sender.sendMessage(ChatColor.GOLD + "========== " + target.getName() + " Durumu ==========");
        sender.sendMessage(ChatColor.YELLOW + "UUID: " + ChatColor.WHITE + playerId);
        sender.sendMessage(ChatColor.YELLOW + "Elytra: " + ChatColor.WHITE + (target.isGliding() ? "Evet" : "Hayir"));
        sender.sendMessage(ChatColor.YELLOW + "Tespit Durumu: " + ChatColor.WHITE + status);
        sender.sendMessage(ChatColor.GOLD + "========================================");
    }

    /**
     * /elytraac reset <oyuncu> - Oyuncu ihlallerini sifirlar
     */
    private void handleResetCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Kullanim: /elytraac reset <oyuncu>");
            return;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Oyuncu bulunamadi: " + playerName);
            return;
        }

        combatModule.resetPlayerViolations(target.getUniqueId());
        sender.sendMessage(ChatColor.GREEN + target.getName() + " adli oyuncunun tum ihlalleri sifirlandi.");
        
        // Oyuncuyu da bilgilendir
        if (target != sender) {
            target.sendMessage(ChatColor.GREEN + "AntiCheat ihlalleriniz bir yetkili tarafindan sifirlandi.");
        }
    }

    /**
     * /elytraac resetall - Tum ihlalleri sifirlar
     */
    private void handleResetAllCommand(CommandSender sender) {
        combatModule.resetAllViolations();
        sender.sendMessage(ChatColor.GREEN + "Tum oyuncularin ihlalleri sifirlandi.");
        
        // Broadcast yap
        Bukkit.broadcastMessage(ChatColor.YELLOW + "[AntiCheat] Tum ihlal verileri sifirlandi.");
    }

    /**
     * /elytraac reload - Yapilandirmayi yeniden yukler
     */
    private void handleReloadCommand(CommandSender sender) {
        reloadConfig();
        configManager.reload();
        sender.sendMessage(ChatColor.GREEN + "ElytraAntiCheat yapilandirmasi yeniden yuklendi.");
        
        getLogger().info(sender.getName() + " tarafindan yapilandirma yeniden yuklendi.");
    }

    /**
     * Yardim mesajini gosterir
     */
    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "========== ElytraAntiCheat Komutlari ==========");
        sender.sendMessage(ChatColor.YELLOW + "/elytraac status <oyuncu>" + ChatColor.GRAY + " - Oyuncu durumunu goster");
        sender.sendMessage(ChatColor.YELLOW + "/elytraac reset <oyuncu>" + ChatColor.GRAY + " - Oyuncu ihlallerini sifirla");
        sender.sendMessage(ChatColor.YELLOW + "/elytraac resetall" + ChatColor.GRAY + " - Tum ihlalleri sifirla");
        sender.sendMessage(ChatColor.YELLOW + "/elytraac reload" + ChatColor.GRAY + " - Yapilandirmayi yeniden yukle");
        sender.sendMessage(ChatColor.YELLOW + "/elytraac help" + ChatColor.GRAY + " - Bu mesaji goster");
        sender.sendMessage(ChatColor.GOLD + "==============================================");
    }
}
