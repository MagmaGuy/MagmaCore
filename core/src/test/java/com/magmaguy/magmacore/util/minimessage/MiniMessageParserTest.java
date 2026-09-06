package com.magmaguy.magmacore.util.minimessage;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.KeybindComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.api.chat.hover.content.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"<red>hi, hi", "&cInferno Lord, Inferno Lord"})
    void namedAndLegacyColour(String input, String text) {
        BaseComponent[] c = MiniMessageParser.parse(input);
        assertEquals(ChatColor.RED, c[0].getColorRaw());
        assertEquals(text, plainText(c));
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"<#ff8800>hi", "&#ff8800hi", "§x§f§f§8§8§0§0hi"})
    void hexColourForms(String input) {
        BaseComponent[] c = MiniMessageParser.parse(input);
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

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"<bold>a<!bold>b", "<bold>a<bold:false>b"})
    void decorationCanBeDisabled(String input) {
        BaseComponent[] c = MiniMessageParser.parse(input);
        assertTrue(c[0].isBold());
        assertEquals(Boolean.FALSE, c[1].isBoldRaw());
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"<gradient:#ff0000:#0000ff>ab</gradient>", "<g:#ff0000:#0000ff>ab</g>"})
    void gradientColoursEachCodePoint(String input) {
        BaseComponent[] c = MiniMessageParser.parse(input);
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

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource(value = {
            "<Quest Giver>|<Quest Giver>",
            "<notatag>x|<notatag>x",
            "\\<red>|<red>",
            "hp: <score:player:health>|'hp: '",
            "<shadow:#ff0000>x</shadow>|x",
            "<selector:@p>|''",
            "a<sprite:icon>b|ab"
    }, delimiter = '|')
    void literalTextSurvivesAndInertTagsAreConsumed(String input, String expected) {
        assertEquals(expected, plainText(MiniMessageParser.parse(input)));
    }

    // ── the legacy-string adapter used for boss / item name sinks ──

    @Test
    void toLegacyEmitsSectionCodes() {
        assertEquals("§chi", MiniMessageParser.toLegacy("<red>hi"));
    }

    @Test
    void toLegacyKeepsPlainStructuralStringsUntouched() {
        assertEquals("vampire_gargoyle.yml", MiniMessageParser.toLegacy("vampire_gargoyle.yml"));
        assertEquals("68,10,68", MiniMessageParser.toLegacy("68,10,68"));
        assertEquals("NORMAL", MiniMessageParser.toLegacy("NORMAL"));
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
    void legacyColourResetsDecorations() {
        // vanilla semantics: a colour code clears formatting (&l then &c → red, not bold)
        BaseComponent[] c = MiniMessageParser.parse("&lBold &cPlainRed");
        assertTrue(c[0].isBold());
        assertEquals(ChatColor.RED, c[c.length - 1].getColorRaw());
        assertNull(c[c.length - 1].isBoldRaw(), "colour code cleared the bold");
    }

    @Test
    void legacyAndMiniMessageMix() {
        BaseComponent[] c = MiniMessageParser.parse("&7<gradient:#ff0000:#0000ff>AB</gradient>");
        assertEquals(2, c.length, "gradient still applies when preceded by a legacy code");
        assertEquals(new Color(0xFF, 0, 0), c[0].getColor().getColor());
    }

    // ── new tags ──

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

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"<hover:show_item:'minecraft:diamond':3>loot", "<hover:show_item:minecraft:diamond:3>loot"})
    void itemHoverRetainsNamespacedIdentityAndCount(String input) {
        BaseComponent[] c = MiniMessageParser.parse(input);
        assertNotNull(c[0].getHoverEvent());
        assertEquals(HoverEvent.Action.SHOW_ITEM, c[0].getHoverEvent().getAction());
        Item item = (Item) c[0].getHoverEvent().getContents().get(0);
        assertEquals("minecraft:diamond", item.getId());
        assertEquals(3, item.getCount());
    }

    @Test
    void prideRendersAsGradient() {
        BaseComponent[] c = MiniMessageParser.parse("<pride>abcdef</pride>");
        assertEquals(6, c.length);
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
        // plain non-message config values must not be mutated by the universal facade
        assertEquals("vampire_gargoyle.yml", com.magmaguy.magmacore.util.ChatColorConverter.convert("vampire_gargoyle.yml"));
    }

    // ── hardening: every bug found in the pre-merge review pass ──

    @Test
    void langWithPlaceholderKeyDoesNotCrash() {
        // BungeeCord NPEs/IndexOutOfBounds on a placeholder key; the universal convert() path must survive.
        assertDoesNotThrow(() -> MiniMessageParser.toLegacy("<lang:commands.give.success>"));
        assertDoesNotThrow(() ->
                com.magmaguy.magmacore.util.ChatColorConverter.convert("<lang:death.attack.mob>"));
        // highest-value guard: only the offending component is dropped — surrounding text survives intact
        assertEquals("Reward:  done", com.magmaguy.magmacore.util.ChatColorConverter
                .convert("Reward: <lang:commands.give.success> done").replaceAll("§.", ""));
    }

    @Test
    void showEntityWithoutUuidDropsHover() {
        // MiniMessage requires the uuid as the 2nd arg; without it we deliberately emit no hover
        // (a clean drop, not a crash). Pinned so this stays a contract, not an accident.
        assertNull(MiniMessageParser.parse("<hover:show_entity:zombie:Bob>x")[0].getHoverEvent());
    }

    @Test
    void langCarriesArguments() {
        BaseComponent[] c = MiniMessageParser.parse("<lang:chat.type.text:Steve:hi>");
        assertInstanceOf(TranslatableComponent.class, c[0]);
        assertEquals(2, ((TranslatableComponent) c[0]).getWith().size());
    }

    @Test
    void namespacedFontSurvivesColonSplit() {
        assertEquals("minecraft:uniform", MiniMessageParser.parse("<font:minecraft:uniform>x")[0].getFontRaw());
    }

    @Test
    void unquotedUrlClickSurvivesColonSplit() {
        BaseComponent[] c = MiniMessageParser.parse("<click:open_url:https://magmaguy.com/x>link");
        assertEquals(ClickEvent.Action.OPEN_URL, c[0].getClickEvent().getAction());
        assertEquals("https://magmaguy.com/x", c[0].getClickEvent().getValue());
    }

    @Test
    void showEntityHover() {
        BaseComponent[] c = MiniMessageParser.parse(
                "<hover:show_entity:minecraft:pig:06e96388-0000-4000-8000-000000000001:Babe>x");
        assertNotNull(c[0].getHoverEvent());
        assertEquals(HoverEvent.Action.SHOW_ENTITY, c[0].getHoverEvent().getAction());
    }

    @Test
    void insertionTag() {
        assertEquals("hello", MiniMessageParser.parse("<insert:hello>x")[0].getInsertion());
    }

    @Test
    void resetInsideGradientDoesNotLeakCloseTag() {
        // the reported corruption: </gradient> must not appear as literal text
        String legacy = MiniMessageParser.toLegacy("<gradient:#ff0000:#0000ff>AB<reset>CD</gradient>");
        assertFalse(legacy.contains("gradient"), "no literal </gradient> leaked: " + legacy);
        assertEquals("ABCD", legacy.replaceAll("§.", ""));
    }

    @Test
    void nestedGradientHonoured() {
        // inner green→yellow gradient must apply to CD, not the outer red→blue stops
        BaseComponent[] c = MiniMessageParser.parse(
                "<gradient:#ff0000:#0000ff>AB<gradient:#00ff00:#ffff00>CD</gradient>EF</gradient>");
        assertEquals("ABCDEF", plainText(c));
        assertEquals(6, c.length);
        assertEquals(new Color(0x00, 0xFF, 0x00), c[2].getColor().getColor(), "C = inner gradient first stop");
    }

    @Test
    void transitionTag() {
        // phase 1 → the end colour (blue), applied to the whole span
        assertEquals(new Color(0, 0, 0xFF),
                MiniMessageParser.parse("<transition:#ff0000:#0000ff:1>abc")[0].getColor().getColor());
    }

    @Test
    void decorationAliases() {
        assertTrue(MiniMessageParser.parse("<b>x</b>")[0].isBold());
        assertTrue(MiniMessageParser.parse("<i>x</i>")[0].isItalic());
        assertEquals(Boolean.TRUE, MiniMessageParser.parse("<st>x</st>")[0].isStrikethroughRaw());
    }

    @Test
    void convertIsIdempotentOnSectionCodes() {
        String once = com.magmaguy.magmacore.util.ChatColorConverter.convert("&cred");
        String twice = com.magmaguy.magmacore.util.ChatColorConverter.convert(once);
        assertEquals(once, twice);
    }

    @Test
    void gradientHandlesSurrogatePairs() {
        // an emoji is one code point (two chars) — it must not be split across two coloured components
        BaseComponent[] c = MiniMessageParser.parse("<gradient:#ff0000:#0000ff>A😀B</gradient>");
        assertEquals(3, c.length, "emoji counts as a single coloured code point");
    }

    @Test
    void nullAndEmptyInput() {
        assertEquals("", MiniMessageParser.toLegacy(null));
        assertEquals("", plainText(MiniMessageParser.parse("")));
        assertEquals("", plainText(MiniMessageParser.parse(null)));
    }

}
