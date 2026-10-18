package org.webtrade.webTrade.market;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Заявка игрока на ресурсы. Оплачивается сразу при создании. */
public final class Container {

    public enum Status {
        /** Набирается: спрос активен. */
        ACTIVE,
        /** Отменён владельцем, несобранное возвращено деньгами. */
        CANCELLED,
        /** Всё запрошенное собрано и выдано. */
        COMPLETED
    }

    /** Строка контейнера по одному ресурсу. */
    public static final class Line {
        private final int requested;
        private int collected;
        private int withdrawn;

        public Line(int requested, int collected, int withdrawn) {
            this.requested = requested;
            this.collected = collected;
            this.withdrawn = withdrawn;
        }

        public int requested() {
            return requested;
        }

        public int collected() {
            return collected;
        }

        public int withdrawn() {
            return withdrawn;
        }

        /** Сколько ещё не собрано (это и есть спрос). */
        public int outstanding() {
            return Math.max(0, requested - collected);
        }

        /** Сколько собрано, но ещё не выдано игроку. */
        public int readyToWithdraw() {
            return Math.max(0, collected - withdrawn);
        }

        public void addCollected(int amount) {
            this.collected += amount;
        }

        public void addWithdrawn(int amount) {
            this.withdrawn += amount;
        }
    }

    /** Сколько символов влезает в название, чтобы не разъезжались списки. */
    public static final int MAX_NAME = 32;

    private final int id;
    private final UUID owner;
    private final long createdAt;
    private final LinkedHashMap<Material, Line> lines;
    private final String name;
    private volatile Status status;

    public Container(int id, UUID owner, String name, long createdAt, Status status,
                     LinkedHashMap<Material, Line> lines) {
        this.id = id;
        this.owner = owner;
        this.name = name == null ? "" : name;
        this.createdAt = createdAt;
        this.status = status;
        this.lines = lines;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Как контейнер называть в интерфейсе: имя, если задано, иначе просто номер. */
    public String label() {
        return name.isBlank() ? "Контейнер #" + id : "#" + id + " · " + name;
    }

    public UUID owner() {
        return owner;
    }

    public long createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Map<Material, Line> lines() {
        return Collections.unmodifiableMap(lines);
    }

    public Line line(Material material) {
        return lines.get(material);
    }

    /** Полностью ли набран контейнер. */
    public boolean isFilled() {
        return lines.values().stream().allMatch(line -> line.outstanding() == 0);
    }

    /** Всё ли собранное уже выдано владельцу. */
    public boolean isFullyWithdrawn() {
        return lines.values().stream().allMatch(line -> line.withdrawn() >= line.requested());
    }
}
