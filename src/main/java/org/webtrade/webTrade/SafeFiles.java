package org.webtrade.webTrade;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Запись файлов данных «целиком или никак».
 * <p>
 * Счета, контейнеры и склад — это деньги игроков. Если сервер упадёт или у диска кончится место
 * посреди обычной записи, файл останется обрезанным, и при следующем старте данные пропадут.
 * Поэтому пишем во временный файл рядом и подменяем им настоящий одним движением.
 */
public final class SafeFiles {

    private SafeFiles() {
    }

    public static void save(YamlConfiguration yaml, File file) throws IOException {
        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(file.getName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
