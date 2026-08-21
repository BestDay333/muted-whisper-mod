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
    public static boolean showNotification = true;
    public static String chatPrefix = "§7[MutedWhisper] ";
    public static String accentColor = "§6"; // Золотой по умолчанию
    public static final Set<String> blackList = new HashSet<>();

    private static boolean hasWelcomed = false;
    public static File configFile;

    public static class ConfigData {
        public boolean firstLaunchDone = false;
        public boolean showNotification = true;
        public String chatPrefix = "§7[MutedWhisper] ";
        public String commandType = "msg";
        public String accentColor = "§6";
    }

    @Override
    public void onInitializeClient() {
        configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "muted-whisper-config.json");
        loadConfig();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> onlinePlayersSuggestion = (context, builder) -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null) {
                    List<String> names = client.world.getPlayers().stream()
                        .map(p -> p.getName().getString())
                        .toList();
                    return CommandSource.suggestMatching(names, builder);
                }
                return builder.buildFuture();
            };

            SuggestionProvider<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> blackListSuggestion =
                (context, builder) -> CommandSource.suggestMatching(blackList, builder);

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
                    .then(ClientCommandManager.literal("notification")
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            showNotification = true;
                            saveConfigStatic();
                            sendInfo("§aУведомления ВКЛЮЧЕНЫ.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            showNotification = false;
                            saveConfigStatic();
                            sendInfo("§cУведомления ВЫКЛЮЧЕНЫ.");
                            return 1;
                        }))
                    )
                    .then(ClientCommandManager.literal("help").executes(ctx -> {
                        sendInfo("§e=== Справка MutedWhisper ===");
                        sendInfo("§6/wl on / off §7- Включить/выключить режим шепота");
                        sendInfo("§6/wl notification <on|off> §7- Уведомления");
                        sendInfo("§6/wl wnear §7- Игроки в радиусе шепота");
                        sendInfo("§6/wl cmd <msg|tell|w> §7- Изменить команду ЛС");
                        sendInfo("§6/wl bloc add <ник> §7- Добавить в черный список");
                        sendInfo("§6/wl bloc remove <ник> §7- Удалить из черного списка");
                        sendInfo("§6/wl bloc list §7- Показать черный список");
                        sendInfo("§7Modrinth: §bhttps://modrinth.com/mod/mutedwhisper");
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
                            saveConfigStatic();
                            sendInfo("Команда для ЛС: /msg");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("tell").executes(ctx -> {
                            msgCommand = "tell";
                            saveConfigStatic();
                            sendInfo("Команда для ЛС: /tell");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("w").executes(ctx -> {
                            msgCommand = "w";
                            saveConfigStatic();
                            sendInfo("Команда для ЛС: /w");
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
                                    sendInfo("§aИгрок §f" + name + " §aдобавлен в черный список.");
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
                                        sendInfo("§eИгрок §f" + name + " §eудален из черного списка.");
                                    } else {
                                        sendInfo("§cИгрок §f" + name + " §cне найден в черном списке.");
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
            if (!isEnabled) return true;
            MinecraftClient.getInstance().execute(() -> sendRadiusWhisper(message));
            return false;
        });
    }

    private void checkFirstLaunchAndGreet() {
        ConfigData config = loadConfigData();
        if (!config.firstLaunchDone) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§6[MutedWhisper] §aМод установлен! Справка: §e/wl help"), false);
                client.player.sendMessage(Text.literal("§7Modrinth: §bhttps://modrinth.com/mod/mutedwhisper"), false);
            }
            config.firstLaunchDone = true;
            saveConfigData(config);
        }
    }

    public static ConfigData loadConfigData() {
        if (configFile == null || !configFile.exists()) return new ConfigData();
        try (FileReader reader = new FileReader(configFile)) {
            ConfigData data = new Gson().fromJson(reader, ConfigData.class);
            return data != null ? data : new ConfigData();
        } catch (IOException e) {
            return new ConfigData();
        }
    }

    public static void saveConfigData(ConfigData data) {
        if (configFile == null) return;
        try {
            if (!configFile.getParentFile().exists()) configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Не удалось сохранить конфиг", e);
        }
    }

    public static void saveConfigStatic() {
        ConfigData data = loadConfigData();
        data.showNotification = showNotification;
        data.chatPrefix = chatPrefix;
        data.commandType = msgCommand;
        data.accentColor = accentColor;
        saveConfigData(data);
    }

    private static void loadConfig() {
        ConfigData data = loadConfigData();
        showNotification = data.showNotification;
        chatPrefix = data.chatPrefix != null ? data.chatPrefix : "§7[MutedWhisper] ";
        msgCommand = data.commandType != null ? data.commandType : "msg";
        accentColor = data.accentColor != null ? data.accentColor : "§6";
    }

    private static List<String> getNearbyPlayerNames() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return List.of();
        ClientPlayerEntity local = client.player;
        return client.world.getPlayers().stream()
            .filter(p -> p != null && !p.equals(local))
            .filter(p -> local.distanceTo(p) <= maxDistance)
            .map(p -> p.getName().getString())
            .filter(name -> !blackList.contains(name))
            .distinct()
            .toList();
    }

    private static void showNearbyPlayers() {
        List<String> nearby = getNearbyPlayerNames();
        if (nearby.isEmpty()) {
            sendInfo("§cВ радиусе " + (int) maxDistance + " блоков никого нет.");
        } else {
            sendInfo("§eИгроки в радиусе (" + nearby.size() + "): §f" + String.join(", ", nearby));
        }
    }

    private static void sendRadiusWhisper(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        List<String> targets = getNearbyPlayerNames();
        if (targets.isEmpty()) {
            sendInfo("§cРядом никого нет в радиусе " + (int) maxDistance + " блоков.");
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            String fullCommand = msgCommand + " " + targets.get(i) + " " + message;
            long delay = (i / 5) * 1500L + (i % 5) * 100L;
            if (delay == 0) {
                executeCommand(client, fullCommand);
            } else {
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                    .execute(() -> executeCommand(client, fullCommand));
            }
        }
        if (showNotification) {
            sendInfo("§aШепот отправлен (" + targets.size() + " игрокам): " + message);
        }
    }

    private static void executeCommand(MinecraftClient client, String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendPacket(new CommandExecutionC2SPacket(command));
        }
    }

    public static void sendInfo(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(accentColor + "[MutedWhisper] §r" + text), false);
        }
    }
}