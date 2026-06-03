package com.magmaguy.magmacore.util.minimessage;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.KeybindComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feasibility tests for the from-scratch MiniMessage → BungeeCord component parser.
 * Each test pins one slice of the format so we can see exactly how far the spike gets.
 */
class MiniMessageParserTest {

    private static String plainText(BaseComponent[] c) {
        StringBuilder sb = new StringBuilder();
        for (BaseComponent b : c) sb.append(b.toPlainText());
        return sb.toString();
    }

    @Test
    void plainTextPassesThrough() {
        BaseComponent[] c = MiniMessageParser.parse("Inferno Lord");
        assertEquals("Inferno Lord", plainText(c));
        assertNull(c[0].getColorRaw(), "plain text carries no colour");
    }

    @Test
    void namedColour() {
        BaseComponent[] c = MiniMessageParser.parse("<red>hi");
        assertEquals(ChatColor.RED, c[0].getColorRaw());
        assertEquals("hi", plainText(c));
    }

    @Test
    void hexColour() {
        BaseComponent[] c = MiniMessageParser.parse("<#ff8800>hi");
        assertEquals(new Color(0xFF, 0x88, 0x00), c[0].getColor().getColor());
    }

    @Test
    void verboseColourAndDecorationNesting() {
        // bold should persist across a nested colour change; colour should override.
        BaseComponent[] c = MiniMessageParser.parse("<bold><red>a<blue>b");
        assertEquals("ab", plainText(c));
        assertTrue(c[0].isBold());
        assertEquals(ChatColor.RED, c[0].getColorRaw());
        assertTrue(c[1].isBold(), "bold persists into nested colour");
        assertEquals(ChatColor.BLUE, c[1].getColorRaw());
    }

    @Test
    void closingTagEndsScope() {
        BaseComponent[] c = MiniMessageParser.parse("<bold>a</bold>b");
        assertEquals("a", c[0].toPlainText());
        assertTrue(c[0].isBold());
        assertEquals("b", c[1].toPlainText());
        assertNull(c[1].isBoldRaw(), "bold cleared after closing tag");
    }

    @Test
    void decorationNegation() {
        BaseComponent[] c = MiniMessageParser.parse("<bold>a<!bold>b");
        assertTrue(c[0].isBold());
        assertEquals(Boolean.FALSE, c[1].isBoldRaw());
    }

    @Test
    void gradientColoursEachCodePoint() {
        BaseComponent[] c = MiniMessageParser.parse("<gradient:#ff0000:#0000ff>ab</gradient>");
        assertEquals(2, c.length, "one component per code point");
        assertEquals(new Color(0xFF, 0, 0), c[0].getColor().getColor(), "first char = first stop");
        assertEquals(new Color(0, 0, 0xFF), c[1].getColor().getColor(), "last char = last stop");
    }

    @Test
    void rainbowColoursText() {
        BaseComponent[] c = MiniMessageParser.parse("<rainbow>abcd</rainbow>");
        assertEquals(4, c.length);
        // adjacent code points get distinct hues
        assertTrue(c[0].getColor() != c[1].getColor());
    }

    @Test
    void hoverEventAttaches() {
        BaseComponent[] c = MiniMessageParser.parse("<hover:show_text:'<yellow>tip'>x");
        assertNotNull(c[0].getHoverEvent(), "hover event present");
        assertEquals("x", plainText(c));
    }

    @Test
    void clickEventAttaches() {
        BaseComponent[] c = MiniMessageParser.parse("<click:run_command:'/etd hub'>Hub");
        assertNotNull(c[0].getClickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, c[0].getClickEvent().getAction());
        assertEquals("/etd hub", c[0].getClickEvent().getValue());
    }

    @Test
    void keybindAndTranslatableComponents() {
        assertInstanceOf(KeybindComponent.class, MiniMessageParser.parse("<key:key.jump>")[0]);
        assertInstanceOf(TranslatableComponent.class, MiniMessageParser.parse("<lang:block.minecraft.stone>")[0]);
    }

    @Test
    void newlineProducesLineBreak() {
        assertEquals("a\nb", plainText(MiniMessageParser.parse("a<newline>b")));
    }

    @Test
    void resetClearsFormatting() {
        BaseComponent[] c = MiniMessageParser.parse("<red><bold>a<reset>b");
        assertEquals(ChatColor.RED, c[0].getColorRaw());
        assertNull(c[1].getColorRaw(), "reset cleared colour");
        assertNull(c[1].isBoldRaw(), "reset cleared bold");
    }

    // ── the migration-compat cases that decide whether existing content survives ──

    @Test
    void literalAngleBracketsLeftVerbatim() {
        // role names like "<Quest Giver>" must NOT be eaten as a tag (space = not a tag name)
        assertEquals("<Quest Giver>", plainText(MiniMessageParser.parse("<Quest Giver>")));
    }

    @Test
    void unknownTagLeftVerbatim() {
        assertEquals("<notatag>x", plainText(MiniMessageParser.parse("<notatag>x")));
    }

    @Test
    void escapedTagIsLiteral() {
        assertEquals("<red>", plainText(MiniMessageParser.parse("\\<red>")));
    }

    // ── the legacy-string adapter used for boss / item name sinks ──

    @Test
    void toLegacyEmitsSectionCodes() {
        assertEquals("§chi", MiniMessageParser.toLegacy("<red>hi"));
    }

    @Test
    void toLegacyHandlesGradientAsHex() {
        // gradient downconverts to per-char §x hex sequences; just assert it renders the text
        String legacy = MiniMessageParser.toLegacy("<gradient:#ff0000:#0000ff>Boss</gradient>");
        assertTrue(legacy.contains("§x"), "hex colour encoded as legacy §x sequence");
        assertTrue(legacy.replaceAll("§.", "").equals("Boss"), "stripped of codes, text is intact");
    }

    // ── legacy & code back-compatibility (existing configs must keep rendering) ──

    @Test
    void legacyAmpersandColour() {
        BaseComponent[] c = MiniMessageParser.parse("&cInferno Lord");
        assertEquals(ChatColor.RED, c[0].getColorRaw());
        assertEquals("Inferno Lord", plainText(c));
    }

    @Test
    void legacyColourResetsDecorations() {
        // vanilla semantics: a colour code clears formatting (&l then &c → red, not bold)
        BaseComponent[] c = MiniMessageParser.parse("&lBold &cPlainRed");
        assertTrue(c[0].isBold());
        assertEquals(ChatColor.RED, c[c.length - 1].getColorRaw());
        assertNull(c[c.length - 1].isBoldRaw(), "colour code cleared the bold");
    }

    @Test
    void legacyHexCode() {
        BaseComponent[] c = MiniMessageParser.parse("&#ff8800hi");
        assertEquals(new Color(0xFF, 0x88, 0x00), c[0].getColor().getColor());
    }

    @Test
    void legacyAndMiniMessageMix() {
        BaseComponent[] c = MiniMessageParser.parse("&7<gradient:#ff0000:#0000ff>AB</gradient>");
        assertEquals(2, c.length, "gradient still applies when preceded by a legacy code");
        assertEquals(new Color(0xFF, 0, 0), c[0].getColor().getColor());
    }

    // ── new tags ──

    @Test
    void shortGradientAlias() {
        // the historical MagmaCore <g:...> alias must still resolve to a gradient
        BaseComponent[] c = MiniMessageParser.parse("<g:#ff0000:#0000ff>ab</g>");
        assertEquals(2, c.length);
        assertEquals(new Color(0xFF, 0, 0), c[0].getColor().getColor());
    }

    @Test
    void gradientHonoursNestedColourOverride() {
        // AB gradient, CD forced green (emitted as one span), EF back to gradient.
        BaseComponent[] c = MiniMessageParser.parse("<gradient:#ff0000:#0000ff>AB<green>CD</green>EF</gradient>");
        assertEquals("ABCDEF", plainText(c));
        assertEquals(5, c.length, "gradient chars are per-codepoint; the green span is one component");
        assertEquals("CD", c[2].toPlainText());
        assertEquals(ChatColor.GREEN, c[2].getColorRaw(), "nested colour overrides the gradient");
        // alignment preserved across the override: first char = first stop, last char = last stop
        assertEquals(new Color(0xFF, 0, 0), c[0].getColor().getColor());
        assertEquals(new Color(0, 0, 0xFF), c[4].getColor().getColor());
    }

    @Test
    void showItemHover() {
        BaseComponent[] c = MiniMessageParser.parse("<hover:show_item:'minecraft:diamond':3>loot");
        assertNotNull(c[0].getHoverEvent());
        assertEquals(HoverEventActions.SHOW_ITEM, c[0].getHoverEvent().getAction());
    }

    @Test
    void prideRendersAsGradient() {
        BaseComponent[] c = MiniMessageParser.parse("<pride>abcdef</pride>");
        assertEquals(6, c.length);
    }

    @Test
    void inertTagsAreConsumedNotPrinted() {
        // server-rendered / too-new tags must not leak as literal text
        assertEquals("hp: ", plainText(MiniMessageParser.parse("hp: <score:player:health>")));
        assertEquals("x", plainText(MiniMessageParser.parse("<shadow:#ff0000>x</shadow>")));
    }

    // ── integration: ChatColorConverter is the universal chokepoint ──

    @Test
    void chatColorConverterRoutesThroughMiniMessage() {
        // gradient via the legacy facade everything in the ecosystem already calls
        String legacy = com.magmaguy.magmacore.util.ChatColorConverter.convert("<gradient:#ff0000:#0000ff>Hi</gradient>");
        assertTrue(legacy.contains("§x"));
        assertEquals("Hi", legacy.replaceAll("§.", ""));
        // and plain legacy input still works
        assertEquals("§cred", com.magmaguy.magmacore.util.ChatColorConverter.convert("&cred"));
    }

    /** Small indirection so the test reads cleanly regardless of BungeeCord's enum import path. */
    private static final class HoverEventActions {
        static final net.md_5.bungee.api.chat.HoverEvent.Action SHOW_ITEM =
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_ITEM;
    }
}
