package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.util.minimessage.MiniMessageParser;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

public class SpigotMessage {
    private SpigotMessage() {
    }

    /**
     * Full MiniMessage → rich BungeeCord components (hover/click/font/gradients/legacy &amp; codes).
     * Prefer this over {@link #simpleMessage(String)} when the source may contain interactive tags.
     */
    public static BaseComponent[] fromMiniMessage(String message) {
        return MiniMessageParser.parse(message);
    }

    public static TextComponent simpleMessage(String message) {
        // Parse MiniMessage straight to components so hover/click/font in the source survive
        // (the legacy-string round-trip used previously dropped them).
        TextComponent wrapper = new TextComponent();
        for (BaseComponent component : MiniMessageParser.parse(message))
            wrapper.addExtra(component);
        return wrapper;
    }

    public static TextComponent hoverMessage(String message, String hoverMessage) {
        TextComponent textComponent = simpleMessage(message);
        textComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(hoverMessage))));
        return textComponent;
    }

    public static TextComponent commandHoverMessage(String message, String hoverMessage, String commandString) {
        TextComponent textComponent = hoverMessage(message, ChatColorConverter.convert(hoverMessage));
        if (!commandString.isEmpty())
            textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, commandString));
        return textComponent;
    }

    public static TextComponent hoverLinkMessage(String message, String hoverMessage, String link) {
        TextComponent textComponent = hoverMessage(message, hoverMessage);
        textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
        return textComponent;
    }
}
