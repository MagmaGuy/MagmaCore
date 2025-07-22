package com.magmaguy.magmacore.util;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

public class SpigotMessage {
    private SpigotMessage() {
    }

    public static TextComponent simpleMessage(String message) {
        return new TextComponent(ChatColorConverter.convert(message));
    }

    public static TextComponent hoverMessage(String message, String hoverMessage) {
        TextComponent textComponent = simpleMessage(message);
        textComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverMessage)));
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
