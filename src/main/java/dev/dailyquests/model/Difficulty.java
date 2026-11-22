package dev.dailyquests.model;

public enum Difficulty {
    EASY("easy", "&a", "Fácil"),
    NORMAL("normal", "&e", "Normal"),
    HARD("hard", "&c", "Difícil");

    private final String key;
    private final String color;
    private final String label;

    Difficulty(String key, String color, String label) {
        this.key = key;
        this.color = color;
        this.label = label;
    }

    public String key() { return key; }
    public String color() { return color; }
    public String label() { return label; }
}
