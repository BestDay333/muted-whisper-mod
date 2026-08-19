package com.example.mutedwhisper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Environment(EnvType.CLIENT)
public class MutedWhisperClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("MutedWhisper");
    
    public static float maxDistance = 100.0f;
    public static String msgCommand = "msg";
    public static boolean isEnabled = false;
    public static boolean showNotification = true; // Уведомления включены по умолчанию
    public static final Set<String> blackList = new HashSet<>();

    private static boolean hasWelcomed = false;
    private static File configFile;

    public static class ConfigData {
        public boolean firstLaunchDone = false;
        public boolean showNotification = true; // Сохранение в конфиг
    }

    @Override
    public void onInitializeClient() {
        configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "muted-whisper-config.json");
        loadConfig();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            
            SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> onlinePlayersSuggestion = (context, builder) -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null) {
                    List<String> playerNames = client.world.getPlayers().stream()
                        .map(p -> p.getName().getString())
                        .toList();
                    return CommandSource.suggestMatching(playerNames, builder);
                }
                return builder.buildFuture();
            };

            SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> blackListSuggestion = (context, builder) -> {
                return CommandSource.suggestMatching(blackList, builder);
            };

            dispatcher.register(
                ClientCommandManager.literal("wl")
                    .then(ClientCommandManager.literal("on").executes(ctx -> {
                        isEnabled = true;
                        sendInfo("§aРежим шепота ВКЛЮЧЕН.");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        isEnabled = false;
                        sendInfo("§cРежим шепота ВЫКЛЮЧЕН.");
                        return 1;
                    }))
                    // Новая подкоманда notification
                    .then(ClientCommandManager.literal("notification")
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            showNotification = true;
                            saveCurrentConfig();
                            sendInfo("§aУведомления о шёпоте ВКЛЮЧЕНЫ.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            showNotification = false;
                            saveCurrentConfig();
                            sendInfo("§cУведомления о шёпоте ВЫКЛЮЧЕНЫ.");
                            return 1;
                        }))
                    )
                    .then(ClientCommandManager.literal("help").executes(ctx -> {
                        sendInfo("§e=== Справка MutedWhisper ===");
                        sendInfo("§6/wl on / off §7- Включить или выключить режим шепота");
                        sendInfo("§6/wl notification <on|off> §7- Включить/выключить уведомления");
                        sendInfo("§6/wl help §7- Показать эту справку");
                        sendInfo("§6/wl wnear §7- Показать игроков в радиусе шепота");
                        sendInfo("§6/wl cmd <msg|tell|w> §7- Изменить команду ЛС");
                        sendInfo("§6/wl bloc add <ник> §7- Добавить игрока в черный список");
                        sendInfo("§6/wl bloc remove <ник> §7- Удалить игрока из черного списка");
                        sendInfo("§6/wl bloc list §7- Показать черный список");
                        sendInfo("§7GitHub: §bhttps://github.com/BestDay333/muted-whisper-mod");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("wnear").executes(ctx -> {
                        showNearbyPlayers();
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("cmd")
                        .then(ClientCommandManager.literal("msg").executes(ctx -> {
                            msgCommand = "msg";
                            sendInfo("Команда для ЛС установлена: /msg");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("tell").executes(ctx -> {
                            msgCommand = "tell";
                            sendInfo("Команда для ЛС установлена: /tell");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("w").executes(ctx -> {
                            msgCommand = "w";
                            sendInfo("Команда для ЛС установлена: /w");
                            return 1;
                        }))
                    )
                    .then(ClientCommandManager.literal("bloc")
                        .then(ClientCommandManager.literal("add")
                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(onlinePlayersSuggestion)
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    blackList.add(name);
                                    sendInfo("§aИгрок " + name + " добавлен в черный список.");
                                    return 1;
                                })
                            )
                        )
                        .then(ClientCommandManager.literal("remove")
                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests(blackListSuggestion)
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    if (blackList.remove(name)) {
                                        sendInfo("§eИгрок " + name + " удален из черного списка.");
                                    } else {
                                        sendInfo("§cИгрок " + name + " не найден в черном списке.");
                                    }
                                    return 1;
                                })
                            )
                        )
                        .then(ClientCommandManager.literal("list").executes(ctx -> {
                            if (blackList.isEmpty()) {
                                sendInfo("§7Черный список пуст.");
                            } else {
                                sendInfo("§eЧерный список: §f" + String.join(", ", blackList));
                            }
                            return 1;
                        }))
                    )
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!hasWelcomed && client.player != null && client.world != null) {
                hasWelcomed = true;
                checkFirstLaunchAndGreet();
            }
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!isEnabled) {
                return true;
            }

            MinecraftClient.getInstance().execute(() -> sendWhisper(message));
            return false;
        });
    }

    private void checkFirstLaunchAndGreet() {
        ConfigData config = loadConfigData();
        if (!config.firstLaunchDone) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§6[MutedWhisper] §aВы используете наш мод! Весь функционал можете узнать по команде §e/wl help§a."), false);
                
                Text githubLink = Text.literal("§7Следите за развитием проекта на нашем GitHub: §b§nhttps://github.com/BestDay333/muted-whisper-mod");
                client.player.sendMessage(githubLink, false);
                
                client.player.sendMessage(Text.literal("§aПриятного использования!"), false);
            }

            config.firstLaunchDone = true;
            saveConfigData(config);
        }
    }

    private ConfigData loadConfigData() {
        if (!configFile.exists()) {
            return new ConfigData();
        }
        try (FileReader reader = new FileReader(configFile)) {
            Gson gson = new Gson();
            ConfigData data = gson.fromJson(reader, ConfigData.class);
            return data != null ? data : new ConfigData();
        } catch (IOException e) {
            return new ConfigData();
        }
    }

    private void saveConfigData(ConfigData data) {
        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(configFile)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Не удалось сохранить конфиг MutedWhisper", e);
        }
    }

    private void saveCurrentConfig() {
        ConfigData data = loadConfigData();
        data.showNotification = showNotification;
        saveConfigData(data);
    }

    private void loadConfig() {
        ConfigData data = loadConfigData();
        showNotification = data.showNotification;
    }

    private List<String> getNearbyPlayerNames() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return List.of();

        ClientPlayerEntity localPlayer = client.player;

        return client.world.getPlayers().stream()
            .filter(p -> p != null)
            .filter(p -> !p.equals(localPlayer))
            .filter(p -> localPlayer.distanceTo(p) <= maxDistance)
            .map(p -> p.getName().getString())
            .filter(name -> !blackList.contains(name))
            .distinct()
            .toList();
    }

    private void showNearbyPlayers() {
        List<String> nearby = getNearbyPlayerNames();
        if (nearby.isEmpty()) {
            sendInfo("§cВ радиусе " + (int) maxDistance + " блоков никого нет (или все в черном списке).");
        } else {
            sendInfo("§eИгроки в радиусе шепота (" + nearby.size() + "): §f" + String.join(", ", nearby));
        }
    }

    private void sendWhisper(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler == null) return;

        if (client.currentScreen != null) {
            client.setScreen(null);
        }

        List<String> nearbyPlayerNames = getNearbyPlayerNames();

        if (nearbyPlayerNames.isEmpty()) {
            sendInfo("§cРядом никого нет (или все в черном списке) в радиусе " + (int) maxDistance + " блоков.");
            return;
        }

        for (int i = 0; i < nearbyPlayerNames.size(); i++) {
            String targetName = nearbyPlayerNames.get(i);
            String fullCommand = msgCommand + " " + targetName + " " + message;
            
            long batchPause = (i / 5) * 1500L;
            long delay = batchPause + ((i % 5) * 100L);

            if (delay == 0) {
                executeCommand(client, fullCommand);
            } else {
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                    executeCommand(client, fullCommand);
                });
            }
        }

        // Проверяем, включены ли уведомления, перед отправкой текста игроку
        if (showNotification) {
            sendInfo("§aШепот отправлен (" + nearbyPlayerNames.size() + " игрокам): " + message);
        }
    }

    private void executeCommand(MinecraftClient client, String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendPacket(new CommandExecutionC2SPacket(command));
        }
    }

    private void sendInfo(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§7[MutedWhisper] " + text), false);
        }
    }
}
