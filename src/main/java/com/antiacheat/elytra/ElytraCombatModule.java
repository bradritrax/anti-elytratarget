package com.antiacheat.elytra;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ElytraTarget ve Rocket Macro hilelerini engelleyen davranışsal paket analizi modülü.
 * 
 * Bu modül sadece oyuncu havada süzülürken (isGliding) çalışır ve şu iki tespit methodunu kullanır:
 * 1. SAS (Swap-Attack-Swap): Slot değişimi → Saldırı → Slot değişimi (80ms içinde)
 * 2. Anti-Rocket Combat: Roket kullanımı → Saldırı (50ms içinde)
 * 
 * Java 17 özellikleri: Record sınıfları, Pattern Matching for instanceof, Switch expressions
 * 
 * @author AntiCheat Developer
 * @version 1.0.0
 */
public class ElytraCombatModule {

    private final Plugin plugin;
    private final ProtocolManager protocolManager;

    // Oyuncu bazlı veri saklama - ConcurrentHashMap thread-safe'dir
    private final Map<UUID, PlayerCombatData> combatDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, ViolationData> violationMap = new ConcurrentHashMap<>();

    // Tespit eşikleri (milisaniye)
    private static final long SAS_THRESHOLD_MS = 80L;      // Swap-Attack-Swap eşiği
    private static final long ROCKET_ATTACK_THRESHOLD_MS = 50L;  // Roket-Saldırı eşiği
    
    // Violation limitleri
    private static final int MAX_VIOLATIONS = 5;           // Ban/kick öncesi maksimum ihlal
    private static final long VIOLATION_DECAY_MS = 30000L; // 30 saniyede bir violation azalma

    /**
     * Oyuncunun combat verilerini tutan record sınıfı (Java 17)
     */
    private record PlayerCombatData(
        long lastSlotChangeTime,      // Son slot değişim zamanı
        int lastSlot,                  // Son slot numarası
        long lastAttackTime,           // Son saldırı zamanı
        long lastRocketUseTime,        // Son roket kullanım zamanı
        long swapAttackStartTime,      // SAS zinciri başlangıç zamanı
        boolean pendingAttack,         // Saldırı bekleniyor mu (SAS için)
        boolean isSwapping             // Slot değişimi aktif mi
    ) {
        // Default constructor ile başlangıç değerleri
        PlayerCombatData() {
            this(0L, -1, 0L, 0L, 0L, false, false);
        }
    }

    /**
     * İhlal verilerini tutan record sınıfı
     */
    private record ViolationData(
        int count,                     // Toplam ihlal sayısı
        long lastViolationTime,        // Son ihlal zamanı
        String lastDetectionType       // Son tespit tipi
    ) {
        ViolationData() {
            this(0, 0L, "NONE");
        }
    }

    public ElytraCombatModule(Plugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    /**
     * Modülü başlatır ve tüm packet listener'ları kaydeder
     */
    public void enable() {
        registerHeldItemSlotListener();
        registerUseEntityListener();
        registerUseItemListener();
        startViolationDecayTask();
        
        Bukkit.getLogger().info("[ElytraCombat] Modül aktif edildi. SAS ve Rocket Combat koruması devrede.");
    }

    /**
     * Modülü devre dışı bırakır
     */
    public void disable() {
        combatDataMap.clear();
        violationMap.clear();
        Bukkit.getLogger().info("[ElytraCombat] Modül devre dışı bırakıldı.");
    }

    /**
     * Slot değişimi (HELD_ITEM_SLOT) paketini dinler
     * SAS zincirinin ilk ve son adımını tespit eder
     */
    private void registerHeldItemSlotListener() {
        protocolManager.addPacketListener(new PacketAdapter(
            plugin,
            ListenerPriority.HIGH,
            PacketType.Play.Client.HELD_ITEM_SLOT
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                
                // Sadece elytra ile süzülen oyuncuları kontrol et
                if (!player.isGliding()) {
                    return;
                }

                PacketContainer packet = event.getPacket();
                int newSlot = packet.getIntegers().read(0); // 0-8 arası slot numarası
                
                UUID playerId = player.getUniqueId();
                long currentTime = System.currentTimeMillis();
                
                combatDataMap.compute(playerId, (uuid, data) -> {
                    if (data == null) {
                        data = new PlayerCombatData();
                    }
                    
                    // SAS zinciri kontrolü
                    if (data.pendingAttack && data.isSwapping) {
                        // İkinci slot değişimi - SAS zincirini tamamla
                        long totalDuration = currentTime - data.swapAttackStartTime;
                        
                        if (totalDuration <= SAS_THRESHOLD_MS) {
                            // SAS tespit edildi!
                            handleSASDetection(player, totalDuration);
                        }
                        
                        // SAS zincirini sıfırla
                        return new PlayerCombatData(
                            currentTime,
                            newSlot,
                            data.lastAttackTime,
                            data.lastRocketUseTime,
                            0L,
                            false,
                            false
                        );
                    } else {
                        // İlk slot değişimi - SAS zincirini başlat
                        return new PlayerCombatData(
                            currentTime,
                            newSlot,
                            data.lastAttackTime,
                            data.lastRocketUseTime,
                            currentTime,
                            true,  // Saldırı bekleniyor
                            true   // Slot değişimi aktif
                        );
                    }
                });
            }
        });
    }

    /**
     * Saldırı (USE_ENTITY) paketini dinler
     * SAS zincirinin orta adımını ve Rocket-Attack çakışmasını kontrol eder
     */
    private void registerUseEntityListener() {
        protocolManager.addPacketListener(new PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST, // HIGHEST çünkü vuruşu iptal edebiliriz
            PacketType.Play.Client.USE_ENTITY
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                
                // Sadece elytra ile süzülen oyuncuları kontrol et
                if (!player.isGliding()) {
                    return;
                }

                PacketContainer packet = event.getPacket();
                
                // Sadece saldırı (ATTACK) aksiyonlarını kontrol et
                EnumWrappers.EntityUseAction action = packet.getEntityUseActions().read(0);
                
                // Java 17 Pattern Matching for instanceof kullanımı
                if (action != EnumWrappers.EntityUseAction.ATTACK) {
                    return;
                }

                UUID playerId = player.getUniqueId();
                long currentTime = System.currentTimeMillis();
                
                // Oyuncu verisini al veya oluştur
                PlayerCombatData data = combatDataMap.getOrDefault(playerId, new PlayerCombatData());
                
                // === KONTROL 1: SAS (Swap-Attack-Swap) ===
                if (data.pendingAttack && data.isSwapping) {
                    // SAS zincirinin orta adımı - saldırı geldi
                    // Şimdi ikinci slot değişimini bekleyeceğiz
                    combatDataMap.put(playerId, new PlayerCombatData(
                        data.lastSlotChangeTime,
                        data.lastSlot,
                        currentTime,
                        data.lastRocketUseTime,
                        data.swapAttackStartTime,
                        true,  // Hala saldırı bekleniyor (ikinci swap için)
                        true   // Slot değişimi hala aktif
                    ));
                    return; // SAS kontrolü için erken dön
                }
                
                // === KONTROL 2: Anti-Rocket Combat ===
                long timeSinceRocket = currentTime - data.lastRocketUseTime;
                
                if (timeSinceRocket <= ROCKET_ATTACK_THRESHOLD_MS && data.lastRocketUseTime > 0) {
                    // Roket kullanımından çok kısa süre sonra saldırı!
                    
                    // Elinde kılıç/balta var mı kontrol et
                    ItemStack mainHand = player.getInventory().getItemInMainHand();
                    Material handType = mainHand.getType();
                    
                    // Sadece silah türü eşyalar için kontrol et
                    if (isWeapon(handType)) {
                        // Vuruşu iptal et
                        event.setCancelled(true);
                        
                        handleRocketMacroDetection(player, timeSinceRocket);
                        
                        // Veriyi güncelle ama roket zamanını koru (ardışık tespitler için)
                        combatDataMap.put(playerId, new PlayerCombatData(
                            data.lastSlotChangeTime,
                            data.lastSlot,
                            currentTime,
                            data.lastRocketUseTime,
                            data.swapAttackStartTime,
                            false,
                            false
                        ));
                    }
                } else {
                    // Normal saldırı - veriyi güncelle
                    combatDataMap.put(playerId, new PlayerCombatData(
                        data.lastSlotChangeTime,
                        data.lastSlot,
                        currentTime,
                        data.lastRocketUseTime,
                        data.swapAttackStartTime,
                        false,
                        false
                    ));
                }
            }
        });
    }

    /**
     * Eşya kullanımı (USE_ITEM) paketini dinler
     * Roket kullanımını tespit eder
     */
    private void registerUseItemListener() {
        protocolManager.addPacketListener(new PacketAdapter(
            plugin,
            ListenerPriority.HIGH,
            PacketType.Play.Client.USE_ITEM  // 1.17.1'de havai fişek kullanımı USE_ITEM olarak gelir
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                
                // Sadece elytra ile süzülen oyuncuları kontrol et
                if (!player.isGliding()) {
                    return;
                }

                // Elindeki eşyayı kontrol et
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                
                // Roket kontrolü
                if (!isRocket(mainHand.getType())) {
                    return;
                }

                UUID playerId = player.getUniqueId();
                long currentTime = System.currentTimeMillis();
                
                // Roket kullanım zamanını kaydet
                combatDataMap.compute(playerId, (uuid, data) -> {
                    if (data == null) {
                        data = new PlayerCombatData();
                    }
                    
                    return new PlayerCombatData(
                        data.lastSlotChangeTime,
                        data.lastSlot,
                        data.lastAttackTime,
                        currentTime,  // Roket kullanım zamanını güncelle
                        data.swapAttackStartTime,
                        data.pendingAttack,
                        data.isSwapping
                    );
                });
            }
        });
    }

    /**
     * SAS (Swap-Attack-Swap) tespiti yapıldığında çağrılır
     */
    private void handleSASDetection(Player player, long duration) {
        UUID playerId = player.getUniqueId();
        
        // Violation sayısını artır
        ViolationData vData = violationMap.compute(playerId, (uuid, vd) -> {
            if (vd == null) {
                vd = new ViolationData();
            }
            return new ViolationData(
                vd.count + 1,
                System.currentTimeMillis(),
                "SAS"
            );
        });
        
        // Log
        Bukkit.getLogger().warning(String.format(
            "[ElytraCombat] SAS Tespiti! Oyuncu: %s | Süre: %dms | İhlal: %d/%d",
            player.getName(), duration, vData.count, MAX_VIOLATIONS
        ));
        
        // İhlal limitini aştı mı?
        if (vData.count >= MAX_VIOLATIONS) {
            punishPlayer(player, "ElytraTarget (SAS) - Otomatik slot değişimi tespit edildi");
        }
    }

    /**
     * Rocket Macro tespiti yapıldığında çağrılır
     */
    private void handleRocketMacroDetection(Player player, long timeDiff) {
        UUID playerId = player.getUniqueId();
        
        // Violation sayısını artır
        ViolationData vData = violationMap.compute(playerId, (uuid, vd) -> {
            if (vd == null) {
                vd = new ViolationData();
            }
            return new ViolationData(
                vd.count + 1,
                System.currentTimeMillis(),
                "ROCKET_MACRO"
            );
        });
        
        // Log
        Bukkit.getLogger().warning(String.format(
            "[ElytraCombat] Rocket Macro Tespiti! Oyuncu: %s | Fark: %dms | İhlal: %d/%d",
            player.getName(), timeDiff, vData.count, MAX_VIOLATIONS
        ));
        
        // İhlal limitini aştı mı?
        if (vData.count >= MAX_VIOLATIONS) {
            punishPlayer(player, "Rocket Macro - İnsanüstü roket-saldırı kombinasyonu");
        }
    }

    /**
     * İhlal limitini aşan oyuncuyu cezalandırır
     */
    private void punishPlayer(Player player, String reason) {
        // Ana thread'de çalıştır
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Burada ban/kick mantığınızı ekleyin
            // Örnek: player.kickPlayer("§c[AntiCheat] " + reason);
            
            Bukkit.getLogger().severe(String.format(
                "[ElytraCombat] CEZA: %s sebebiyle %s cezalandırıldı!",
                player.getName(), reason
            ));
            
            // Tüm sunucuya bildirim (opsiyonel)
            Bukkit.broadcastMessage(String.format(
                "§c[AntiCheat] §f%s §7adlı oyuncu §c%s §7sebebiyle cezalandırıldı.",
                player.getName(), reason
            ));
            
            // Violation'ı sıfırla (tekrar ceza alabilmesi için)
            violationMap.remove(player.getUniqueId());
        });
    }

    /**
     * Belirli aralıklarla violation sayılarını azaltan görev
     * Lag dostu olması için senkron çalışır
     */
    private void startViolationDecayTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                
                violationMap.entrySet().removeIf(entry -> {
                    ViolationData vd = entry.getValue();
                    
                    // 30 saniye geçtiyse violation azalt veya sil
                    if (currentTime - vd.lastViolationTime > VIOLATION_DECAY_MS) {
                        if (vd.count > 1) {
                            // Violation azalt ama silme
                            violationMap.put(entry.getKey(), new ViolationData(
                                vd.count - 1,
                                currentTime,
                                vd.lastDetectionType
                            ));
                            return false; // Silme
                        }
                        return true; // Sil
                    }
                    return false; // Silme
                });
            }
        }.runTaskTimer(plugin, 20L * 30, 20L * 30); // Her 30 saniyede bir
    }

    // ============ YARDIMCI METHODLAR ============

    /**
     * Verilen materyalin silah olup olmadığını kontrol eder
     */
    private boolean isWeapon(Material material) {
        return switch (material) {
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, GOLDEN_SWORD, 
                 DIAMOND_SWORD, NETHERITE_SWORD,
                 WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, 
                 DIAMOND_AXE, NETHERITE_AXE -> true;
            default -> false;
        };
    }

    /**
     * Verilen materyalin roket olup olmadığını kontrol eder
     */
    private boolean isRocket(Material material) {
        return material == Material.FIREWORK_ROCKET;
    }

    // ============ DEBUG/ADMIN KOMUTLARI ============

    /**
     * Belirli bir oyuncunun violation durumunu döndürür (Admin kontrolü için)
     */
    public String getPlayerViolationStatus(UUID playerId) {
        ViolationData vd = violationMap.get(playerId);
        PlayerCombatData data = combatDataMap.get(playerId);
        
        if (vd == null || vd.count == 0) {
            return "Temiz - İhlal yok";
        }
        
        return String.format(
            "İhlal: %d/%d | Son Tespit: %s | Son Aktivite: %dms önce",
            vd.count,
            MAX_VIOLATIONS,
            vd.lastDetectionType,
            System.currentTimeMillis() - vd.lastViolationTime
        );
    }

    /**
     * Oyuncunun violationlarını manuel olarak sıfırlar
     */
    public void resetPlayerViolations(UUID playerId) {
        violationMap.remove(playerId);
        combatDataMap.remove(playerId);
    }

    /**
     * Tüm violation verilerini sıfırlar
     */
    public void resetAllViolations() {
        violationMap.clear();
        combatDataMap.clear();
        Bukkit.getLogger().info("[ElytraCombat] Tüm violation verileri sıfırlandı.");
    }
}
