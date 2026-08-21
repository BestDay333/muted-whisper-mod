package com.example.mutedwhisper;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Optional;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Text.literal("Настройки Muted Whisper"));

            // Красивая главная категория настроек
            ConfigCategory general = builder.getOrCreateCategory(Text.literal("Основные настройки"));
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            // 1. Команда для личных сообщений
            general.addEntry(entryBuilder.startStrField(
                            Text.literal("Команда для ЛС"),
                            MutedWhisperClient.msgCommand
                    )
                    .setDefaultValue("msg")
                    .setTooltip(Text.literal("Какая команда используется на сервере для ЛС (например: msg, tell, w)"))
                    .setSaveConsumer(newValue -> MutedWhisperClient.msgCommand = newValue)
                    .build());

            // 2. Префикс мода в чате
            general.addEntry(entryBuilder.startStrField(
                            Text.literal("Префикс в чате"),
                            MutedWhisperClient.chatPrefix
                    )
                    .setDefaultValue("§7[MutedWhisper] ")
                    .setTooltip(Text.literal("Текст префикса перед системными сообщениями мода"))
                    .setSaveConsumer(newValue -> MutedWhisperClient.chatPrefix = newValue)
                    .build());

            // 3. Акцентный цвет (выбор кода цвета Minecraft)
            general.addEntry(entryBuilder.startStrField(
                            Text.literal("Акцентный цвет (код, например §6)"),
                            MutedWhisperClient.accentColor
                    )
                    .setDefaultValue("§6")
                    .setTooltip(Text.literal("Цветовой код Minecraft для выделения заголовков и префикса"))
                    .setSaveConsumer(newValue -> MutedWhisperClient.accentColor = newValue)
                    .build());

            // 4. Уведомления в чате (по мелочи)
            general.addEntry(entryBuilder.startBooleanToggle(
                            Text.literal("Уведомления в чате"),
                            MutedWhisperClient.showNotification
                    )
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Писать ли в чат отчет об успешной отправке шепота"))
                    .setSaveConsumer(newValue -> MutedWhisperClient.showNotification = newValue)
                    .build());

            // Сохранение конфигурации
            builder.setSavingRunnable(() -> {
                MutedWhisperClient.saveConfigStatic();
            });

            return builder.build();
        };
    }

    // Добавление кнопок «Сайт» (Modrinth) и «Проблемы» (GitHub Issues) в Mod Menu
    @Override
    public java.util.Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
        return java.util.Map.of();
    }

    // Mod Menu автоматически подтягивает ссылки из fabric.mod.json, 
    // но если нужно явно прописать ссылки на внешние страницы в интерфейсе Mod Menu:
    // (Mod Menu берёт ссылки прямо из fabric.mod.json, убедись что они там указаны)
}