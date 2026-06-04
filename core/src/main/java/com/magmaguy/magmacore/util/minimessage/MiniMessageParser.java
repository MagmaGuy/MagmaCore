package com.magmaguy.magmacore.util.minimessage;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.KeybindComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.api.chat.hover.content.Entity;
import net.md_5.bungee.api.chat.hover.content.Item;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A self-contained <a href="https://docs.papermc.io/adventure/minimessage/format/">MiniMessage</a>
 * parser that emits BungeeCord {@link BaseComponent}s — the component API already on the Spigot
 * classpath and already used by {@code Logger}/{@code SpigotMessage}. No Kyori Adventure dependency.
 *
 * <h2>Why BungeeCord components</h2>
 * BungeeCord's {@code net.md_5.bungee.api.chat} package is itself a full component tree: colours,
 * hex, decorations, hover, click, insertion, font, keybind and translatable components are all
 * representable, so the interactive half of MiniMessage is just a matter of wiring the parser to
 * the right component setters rather than a reason to shade Adventure.
 *
 * <h2>Outputs</h2>
 * <ul>
 *   <li>{@link #parse(String)} → rich {@code BaseComponent[]} for message/title sinks (full
 *       hover/click/font/keybind fidelity).</li>
 *   <li>{@link #toLegacy(String)} → a legacy {@code §}-coded string for name sinks
 *       ({@code setDisplayName}/{@code setCustomName}); colours/gradients/decorations survive,
 *       interactive events drop (names can't carry them anyway).</li>
 * </ul>
 *
 * <h2>Backwards compatibility</h2>
 * Both entry points first run {@link #applyLegacy(String)}, which rewrites legacy {@code &}/{@code §}
 * colour and format codes (including {@code &#rrggbb} and the BungeeCord {@code &x&r&r…} hex form)
 * into MiniMessage tags. A legacy colour code resets decorations, matching how the vanilla client
 * renders {@code §} strings, so existing config text keeps its exact appearance. The historical
 * MagmaCore tags ({@code <gradient>}, {@code <rainbow>}, {@code <#hex>}) and their short aliases
 * ({@code <g:…>}, {@code <r>}) are accepted too.
 *
 * <h2>Lenient parsing</h2>
 * Unrecognised or malformed {@code <…>} (e.g. a literal role name like {@code <Quest Giver>}),
 * stray closing tags and {@code \<} are left verbatim; unclosed tags auto-close at end of input.
 *
 * <h2>Coverage</h2>
 * Implemented: named colours, {@code <#rrggbb>}, {@code <color:…>}, decorations + negation, reset,
 * gradient (with per-segment nested-colour overrides), rainbow, transition, pride, newline,
 * hover (show_text / show_item / show_entity), click (all actions), insert/insertion, font, key,
 * lang/translatable (with arguments). Recognised but with their effect dropped (server-rendered or
 * too new for the BungeeCord component API), consumed rather than printed literally: {@code <nbt>},
 * {@code <score>}, {@code <selector>}, {@code <sprite>}, {@code <head>} emit nothing, while
 * {@code <shadow>} still renders its inner text (only the shadow colour is ignored).
 */
public final class MiniMessageParser {

    private MiniMessageParser() {
    }

    // ─────────────────────────────────────────────────────────────── public API

    /** Parses MiniMessage (and legacy {@code &} codes) into rich BungeeCord components. */
    public static BaseComponent[] parse(String input) {
        if (input == null || input.isEmpty()) return new BaseComponent[]{new TextComponent("")};
        List<Token> tokens = tokenize(applyLegacy(input));
        ElementNode root = buildTree(tokens);
        List<BaseComponent> out = new ArrayList<>();
        for (Node child : root.children) render(child, new Style(), out);
        if (out.isEmpty()) out.add(new TextComponent(""));
        return out.toArray(new BaseComponent[0]);
    }

    /** Parses MiniMessage and serialises down to a legacy {@code §}-coded string (hex-aware). */
    public static String toLegacy(String input) {
        if (input == null) return "";
        if (!containsFormatting(input)) return input;
        BaseComponent[] parsed = parse(input);
        try {
            return BaseComponent.toLegacyText(parsed);
        } catch (RuntimeException e) {
            // Server-side serialisation of a TranslatableComponent can throw when a vanilla key
            // carries format placeholders that the (caller-less) `with` list doesn't satisfy
            // (e.g. <lang:commands.give.success>). Because this is the universal name/scoreboard/title
            // path, it must never crash the caller — fall back to a per-component render that drops
            // only the offending component.
            StringBuilder sb = new StringBuilder();
            for (BaseComponent c : parsed) {
                try {
                    sb.append(BaseComponent.toLegacyText(c));
                } catch (RuntimeException ignored) {
                    // un-renderable server-side (translatable/score/etc.) — skip it
                }
            }
            return sb.toString();
        }
    }

    private static boolean containsFormatting(String input) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < input.length()) {
                char n = Character.toLowerCase(input.charAt(i + 1));
                if (n == '#' && i + 8 <= input.length() && isHex6(input, i + 2)) return true;
                if (readSpreadHex(input, i) != null) return true;
                if (legacyCodeToTag(n) != null) return true;
            } else if (c == '<') {
                int end = findTagEnd(input, i);
                if (end > 0 && parseTag(input.substring(i + 1, end)) != null) return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────── legacy code bridge

    /** Rewrites legacy {@code &}/{@code §} codes (incl. {@code &#rrggbb} and {@code &x…}) to tags. */
    static String applyLegacy(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0, len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < len) {
                char n = Character.toLowerCase(s.charAt(i + 1));
                // &#rrggbb
                if (n == '#' && i + 8 <= len && isHex6(s, i + 2)) {
                    out.append("<reset><#").append(s, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }
                // &x&r&r&g&g&b&b  (BungeeCord spread hex)
                String spread = readSpreadHex(s, i);
                if (spread != null) {
                    out.append("<reset><#").append(spread).append('>');
                    i += 14;
                    continue;
                }
                String tag = legacyCodeToTag(n);
                if (tag != null) {
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex6(String s, int from) {
        if (from + 6 > s.length()) return false;
        for (int i = from; i < from + 6; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /** Reads {@code &x&r&r&g&g&b&b} (or {@code §} variant) at {@code start}; returns 6 hex chars or null. */
    private static String readSpreadHex(String s, int start) {
        if (start + 14 > s.length()) return null;
        if (Character.toLowerCase(s.charAt(start + 1)) != 'x') return null;
        StringBuilder hex = new StringBuilder(6);
        for (int k = 0; k < 6; k++) {
            char marker = s.charAt(start + 2 + k * 2);
            char digit = s.charAt(start + 3 + k * 2);
            if (marker != '&' && marker != '§') return null;
            if (Character.digit(digit, 16) < 0) return null;
            hex.append(digit);
        }
        return hex.toString();
    }

    private static String legacyCodeToTag(char code) {
        // Colours reset decorations first, mirroring vanilla client rendering of § strings.
        return switch (code) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    // ───────────────────────────────────────────────────────── known tag tables

    private static final Map<String, ChatColor> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", ChatColor.BLACK),
            Map.entry("dark_blue", ChatColor.DARK_BLUE),
            Map.entry("dark_green", ChatColor.DARK_GREEN),
            Map.entry("dark_aqua", ChatColor.DARK_AQUA),
            Map.entry("dark_red", ChatColor.DARK_RED),
            Map.entry("dark_purple", ChatColor.DARK_PURPLE),
            Map.entry("gold", ChatColor.GOLD),
            Map.entry("gray", ChatColor.GRAY),
            Map.entry("dark_gray", ChatColor.DARK_GRAY),
            Map.entry("blue", ChatColor.BLUE),
            Map.entry("green", ChatColor.GREEN),
            Map.entry("aqua", ChatColor.AQUA),
            Map.entry("red", ChatColor.RED),
            Map.entry("light_purple", ChatColor.LIGHT_PURPLE),
            Map.entry("yellow", ChatColor.YELLOW),
            Map.entry("white", ChatColor.WHITE));

    private static final Map<String, String> DECORATIONS = Map.ofEntries(
            Map.entry("bold", "bold"), Map.entry("b", "bold"),
            Map.entry("italic", "italic"), Map.entry("i", "italic"), Map.entry("em", "italic"),
            Map.entry("underlined", "underlined"), Map.entry("u", "underlined"),
            Map.entry("strikethrough", "strikethrough"), Map.entry("st", "strikethrough"),
            Map.entry("obfuscated", "obfuscated"), Map.entry("obf", "obfuscated"));

    private static final Set<String> OTHER_TAGS = Set.of(
            "color", "colour", "c", "reset",
            "gradient", "g", "rainbow", "r", "transition", "pride",
            "newline", "br",
            "hover", "click", "insert", "insertion", "font",
            "key", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or",
            // recognised-but-inert (server-rendered / newer than the BungeeCord API)
            "nbt", "data", "score", "selector", "sel", "shadow", "sprite", "head");

    private static boolean isKnownTagName(String name) {
        if (name.startsWith("!")) name = name.substring(1);
        return NAMED_COLORS.containsKey(name) || DECORATIONS.containsKey(name)
                || OTHER_TAGS.contains(name) || isHex(name);
    }

    private static boolean isColorTag(String name) {
        return NAMED_COLORS.containsKey(name) || isHex(name)
                || name.equals("color") || name.equals("colour") || name.equals("c");
    }

    private static boolean isHex(String s) {
        if (s.length() != 7 || s.charAt(0) != '#') return false;
        for (int i = 1; i < 7; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    // ─────────────────────────────────────────────────────────────── tokenizer

    private sealed interface Token permits TextTok, OpenTok, CloseTok {
    }

    private record TextTok(String text) implements Token {
    }

    private record OpenTok(String name, List<String> args, boolean selfClose) implements Token {
    }

    private record CloseTok(String name) implements Token {
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0, len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = s.charAt(i + 1);
                if (next == '<' || next == '\\') {
                    text.append(next);
                    i += 2;
                    continue;
                }
            }
            if (c == '<') {
                int end = findTagEnd(s, i);
                if (end > 0) {
                    Token tag = parseTag(s.substring(i + 1, end));
                    if (tag != null) {
                        if (text.length() > 0) {
                            tokens.add(new TextTok(text.toString()));
                            text.setLength(0);
                        }
                        tokens.add(tag);
                        i = end + 1;
                        continue;
                    }
                }
            }
            text.append(c);
            i++;
        }
        if (text.length() > 0) tokens.add(new TextTok(text.toString()));
        return tokens;
    }

    private static int findTagEnd(String s, int start) {
        char quote = 0;
        for (int j = start + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quote != 0) {
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '<') {
                return -1;
            } else if (c == '>') {
                return j;
            }
        }
        return -1;
    }

    private static Token parseTag(String raw) {
        if (raw.isEmpty()) return null;
        boolean selfClose = false;
        if (raw.endsWith("/")) {
            selfClose = true;
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.startsWith("/")) {
            String name = raw.substring(1).toLowerCase(Locale.ROOT);
            if (!isValidName(name) || !isKnownTagName(name)) return null;
            return new CloseTok(name);
        }
        List<String> parts = splitTopLevel(raw, ':');
        if (parts.isEmpty()) return null;
        String name = parts.get(0).toLowerCase(Locale.ROOT);
        if (!isValidName(name) || !isKnownTagName(name)) return null;
        List<String> args = new ArrayList<>();
        for (int k = 1; k < parts.size(); k++) args.add(unquote(parts.get(k)));
        return new OpenTok(name, args, selfClose);
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty()) return false;
        int start = name.charAt(0) == '!' ? 1 : 0;
        if (start == name.length()) return false;
        for (int i = start; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '#')) return false;
        }
        return true;
    }

    private static List<String> splitTopLevel(String s, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < s.length()) {
                    cur.append(c).append(s.charAt(++i));
                    continue;
                }
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == delim) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"') && s.charAt(s.length() - 1) == s.charAt(0)) {
            String inner = s.substring(1, s.length() - 1);
            return inner.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    // ────────────────────────────────────────────────────────────── tree build

    private sealed interface Node permits TextNode, ElementNode {
    }

    private record TextNode(String text) implements Node {
    }

    private static final class ElementNode implements Node {
        final String name;
        final List<String> args;
        final List<Node> children = new ArrayList<>();

        ElementNode(String name, List<String> args) {
            this.name = name;
            this.args = args;
        }
    }

    /** Tags with no closing form. */
    private static boolean isVoid(String name) {
        return switch (name) {
            case "newline", "br", "key", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or",
                 "nbt", "data", "score", "selector", "sel", "sprite", "head" -> true;
            default -> false;
        };
    }

    private static ElementNode buildTree(List<Token> tokens) {
        ElementNode root = new ElementNode("root", List.of());
        java.util.Deque<ElementNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        for (Token t : tokens) {
            if (t instanceof TextTok tt) {
                stack.peek().children.add(new TextNode(tt.text()));
            } else if (t instanceof OpenTok ot) {
                if (ot.name().equals("reset")) {
                    while (stack.size() > 1) stack.pop();
                    continue;
                }
                ElementNode el = new ElementNode(ot.name(), ot.args());
                stack.peek().children.add(el);
                if (!ot.selfClose() && !isVoid(ot.name())) stack.push(el);
            } else if (t instanceof CloseTok ct) {
                if (stack.stream().anyMatch(e -> e.name.equals(ct.name()))) {
                    while (stack.size() > 1 && !stack.peek().name.equals(ct.name())) stack.pop();
                    if (stack.size() > 1) stack.pop();
                }
                // else: a known close tag with no matching open — e.g. the </gradient> after a
                // <reset> already popped the gradient — is dropped (MiniMessage semantics). Echoing
                // it as literal text would leak "</gradient>" into rendered output. (Unknown-name
                // closes never reach here: the tokenizer leaves them as literal text.)
            }
        }
        return root;
    }

    // ───────────────────────────────────────────────────────────────── render

    private static final class Style {
        ChatColor color;
        Boolean bold, italic, underlined, strikethrough, obfuscated;
        String font, insertion;
        HoverEvent hover;
        ClickEvent click;

        Style copy() {
            Style s = new Style();
            s.color = color;
            s.bold = bold;
            s.italic = italic;
            s.underlined = underlined;
            s.strikethrough = strikethrough;
            s.obfuscated = obfuscated;
            s.font = font;
            s.insertion = insertion;
            s.hover = hover;
            s.click = click;
            return s;
        }

        void applyTo(BaseComponent comp) {
            if (color != null) comp.setColor(color);
            if (bold != null) comp.setBold(bold);
            if (italic != null) comp.setItalic(italic);
            if (underlined != null) comp.setUnderlined(underlined);
            if (strikethrough != null) comp.setStrikethrough(strikethrough);
            if (obfuscated != null) comp.setObfuscated(obfuscated);
            if (font != null) comp.setFont(font);
            if (insertion != null) comp.setInsertion(insertion);
            if (hover != null) comp.setHoverEvent(hover);
            if (click != null) comp.setClickEvent(click);
        }
    }

    private static void render(Node node, Style style, List<BaseComponent> out) {
        if (node instanceof TextNode tn) {
            emit(tn.text(), style, out);
            return;
        }
        ElementNode el = (ElementNode) node;
        switch (el.name) {
            case "newline", "br" -> emit("\n", style, out);
            case "reset" -> { /* handled at tree level */ }
            case "gradient", "g" -> renderGradient(el, parseColors(el.args), style, out);
            case "pride" -> renderGradient(el, prideColors(el.args), style, out);
            case "transition" -> renderTransition(el, parseColors(el.args), style, out);
            case "rainbow", "r" -> renderRainbow(el, style, out);
            case "key" -> {
                if (!el.args.isEmpty()) emitComponent(new KeybindComponent(el.args.get(0)), style, out);
            }
            case "lang", "tr", "translate", "lang_or", "tr_or", "translate_or" -> {
                if (!el.args.isEmpty()) {
                    TranslatableComponent tc = new TranslatableComponent(el.args.get(0));
                    // `with` MUST be non-null: BungeeCord's TranslatableComponent.toPlainText()/convert()
                    // NPEs on a null `with` for any key that expects placeholders — which would crash the
                    // universal convert() path on a single such string in any config.
                    List<BaseComponent> with = new ArrayList<>();
                    for (int k = 1; k < el.args.size(); k++) with.add(new TextComponent(parse(el.args.get(k))));
                    tc.setWith(with);
                    emitComponent(tc, style, out);
                }
            }
            // recognised-but-inert: consume any children with the current style, ignore the tag itself
            case "nbt", "data", "score", "selector", "sel", "sprite", "head" -> {
            }
            case "shadow" -> {
                for (Node child : el.children) render(child, style, out);
            }
            default -> {
                Style childStyle = applyStyleTag(el, style);
                for (Node child : el.children) render(child, childStyle, out);
            }
        }
    }

    private static void emit(String text, Style style, List<BaseComponent> out) {
        if (text.isEmpty()) return;
        TextComponent c = new TextComponent(text);
        style.applyTo(c);
        out.add(c);
    }

    private static void emitComponent(BaseComponent c, Style style, List<BaseComponent> out) {
        style.applyTo(c);
        out.add(c);
    }

    private static Style applyStyleTag(ElementNode el, Style parent) {
        Style s = parent.copy();
        String name = el.name;

        if (NAMED_COLORS.containsKey(name)) {
            s.color = NAMED_COLORS.get(name);
            return s;
        }
        if (isHex(name)) {
            s.color = ChatColor.of(name);
            return s;
        }
        if (name.equals("color") || name.equals("colour") || name.equals("c")) {
            if (!el.args.isEmpty()) {
                ChatColor cc = resolveColor(el.args.get(0));
                if (cc != null) s.color = cc;
            }
            return s;
        }
        if (name.startsWith("!") && DECORATIONS.containsKey(name.substring(1))) {
            setDecoration(s, DECORATIONS.get(name.substring(1)), false);
            return s;
        }
        if (DECORATIONS.containsKey(name)) {
            boolean value = el.args.isEmpty() || !el.args.get(0).equalsIgnoreCase("false");
            setDecoration(s, DECORATIONS.get(name), value);
            return s;
        }
        switch (name) {
            case "font" -> {
                // values commonly contain a namespace colon (minecraft:uniform), which the
                // top-level split fragments — rejoin them.
                if (!el.args.isEmpty()) s.font = joinFrom(el.args, 0);
            }
            case "insert", "insertion" -> {
                if (!el.args.isEmpty()) s.insertion = joinFrom(el.args, 0);
            }
            case "hover" -> s.hover = parseHover(el.args);
            case "click" -> {
                if (el.args.size() >= 2) {
                    ClickEvent.Action action = clickAction(el.args.get(0));
                    // click values are frequently unquoted URLs (https://… → rejoin the colon split)
                    if (action != null) s.click = new ClickEvent(action, joinFrom(el.args, 1));
                }
            }
            default -> {
            }
        }
        return s;
    }

    private static HoverEvent parseHover(List<String> args) {
        if (args.size() < 2) return null;
        String action = args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "show_text" -> {
                return new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(parse(joinFrom(args, 1))));
            }
            case "show_item" -> {
                // show_item:<id>[:count]  — id is normally a namespaced key (minecraft:diamond), so
                // it survives the colon split only by rejoining; an optional trailing integer is count.
                List<String> rest = args.subList(1, args.size());
                int count = 1;
                int idEnd = rest.size();
                // A trailing all-digit segment is treated as the stack count. (A namespaced id whose
                // PATH is purely numeric would be mis-split, but no real Minecraft item id is all-digits.)
                if (rest.size() >= 2 && isInteger(rest.get(rest.size() - 1))) {
                    count = Integer.parseInt(rest.get(rest.size() - 1).trim());
                    idEnd = rest.size() - 1;
                }
                String id = String.join(":", rest.subList(0, idEnd));
                return new HoverEvent(HoverEvent.Action.SHOW_ITEM, new Item(id, count, null));
            }
            case "show_entity" -> {
                // show_entity:<type>:<uuid>[:name] — type may be namespaced; locate the uuid segment
                // to split type (before) from the optional name (after).
                List<String> rest = args.subList(1, args.size());
                int uuidIdx = -1;
                for (int k = 0; k < rest.size(); k++) {
                    if (isUuid(rest.get(k))) {
                        uuidIdx = k;
                        break;
                    }
                }
                if (uuidIdx < 0) return null;
                String type = String.join(":", rest.subList(0, uuidIdx));
                String id = rest.get(uuidIdx);
                BaseComponent name = uuidIdx + 1 < rest.size()
                        ? new TextComponent(parse(joinFrom(rest, uuidIdx + 1))) : null;
                return new HoverEvent(HoverEvent.Action.SHOW_ENTITY, new Entity(type, id, name));
            }
            default -> {
                return null;
            }
        }
    }

    private static String joinFrom(List<String> args, int from) {
        return from >= args.size() ? "" : String.join(":", args.subList(from, args.size()));
    }

    private static boolean isInteger(String s) {
        s = s.trim();
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static boolean isUuid(String s) {
        return s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static void setDecoration(Style s, String canonical, boolean v) {
        switch (canonical) {
            case "bold" -> s.bold = v;
            case "italic" -> s.italic = v;
            case "underlined" -> s.underlined = v;
            case "strikethrough" -> s.strikethrough = v;
            case "obfuscated" -> s.obfuscated = v;
            default -> {
            }
        }
    }

    private static ChatColor resolveColor(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        if (NAMED_COLORS.containsKey(t)) return NAMED_COLORS.get(t);
        if (isHex(t)) return ChatColor.of(t);
        return null;
    }

    private static ClickEvent.Action clickAction(String name) {
        try {
            return ClickEvent.Action.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────── gradient / rainbow

    /** Renders a gradient subtree, advancing one shared index per visible code point so that a
     *  nested explicit colour overrides the gradient for its span without breaking alignment. */
    private static void renderGradient(ElementNode el, List<Color> colors, Style style, List<BaseComponent> out) {
        if (colors.size() < 2) {
            for (Node child : el.children) render(child, style, out);
            return;
        }
        int total = Math.max(1, countCodePoints(el));
        renderGradientChildren(el, colors, total, new int[]{0}, style, out);
    }

    private static void renderGradientChildren(ElementNode el, List<Color> colors, int total, int[] idx,
                                               Style style, List<BaseComponent> out) {
        for (Node child : el.children) {
            if (child instanceof TextNode tn) {
                String text = tn.text();
                for (int off = 0; off < text.length(); ) {
                    int cp = text.codePointAt(off);
                    off += Character.charCount(cp);
                    float t = total <= 1 ? 0f : (float) idx[0] / (total - 1);
                    out.add(coloredCodePoint(cp, gradientColor(colors, t), style));
                    idx[0]++;
                }
            } else {
                ElementNode child2 = (ElementNode) child;
                if (child2.name.equals("newline") || child2.name.equals("br")) {
                    emit("\n", style, out);
                    idx[0]++;
                    continue;
                }
                if (producesOwnColor(child2.name)) {
                    // An explicit colour OR a nested gradient/rainbow/transition/pride wins for its
                    // whole span: render it via the normal path (so the inner colouring is honoured)
                    // and advance the shared index by its length so the outer gradient stays aligned.
                    render(child2, style, out);
                    idx[0] += countCodePoints(child2);
                } else {
                    // decoration-only (or other style) tag: keep flowing the gradient through it
                    renderGradientChildren(child2, colors, total, idx, applyStyleTag(child2, style), out);
                }
            }
        }
    }

    private static boolean producesOwnColor(String name) {
        return isColorTag(name)
                || name.equals("gradient") || name.equals("g")
                || name.equals("rainbow") || name.equals("r")
                || name.equals("transition") || name.equals("pride");
    }

    private static void renderTransition(ElementNode el, List<Color> colors, Style style, List<BaseComponent> out) {
        if (colors.isEmpty()) {
            for (Node child : el.children) render(child, style, out);
            return;
        }
        float phase = lastPhase(el.args);
        Color color = colors.size() == 1 ? colors.get(0) : gradientColor(colors, (phase + 1f) / 2f);
        Style s = style.copy();
        s.color = toChatColor(color);
        for (Node child : el.children) render(child, s, out);
    }

    private static void renderRainbow(ElementNode el, Style style, List<BaseComponent> out) {
        float phase = lastPhase(el.args);
        String text = flatten(el);
        int total = Math.max(1, text.codePointCount(0, text.length()));
        int idx = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            off += Character.charCount(cp);
            float hue = ((float) idx / total + phase) % 1.0f;
            if (hue < 0) hue += 1.0f;
            out.add(coloredCodePoint(cp, Color.getHSBColor(hue, 1.0f, 1.0f), style));
            idx++;
        }
    }

    private static float lastPhase(List<String> args) {
        if (args.isEmpty()) return 0f;
        try {
            return Float.parseFloat(args.get(args.size() - 1).replace("!", ""));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static BaseComponent coloredCodePoint(int cp, Color color, Style style) {
        TextComponent c = new TextComponent(new String(Character.toChars(cp)));
        Style s = style.copy();
        s.color = toChatColor(color);
        s.applyTo(c);
        return c;
    }

    private static ChatColor toChatColor(Color color) {
        return ChatColor.of(String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
    }

    private static Color gradientColor(List<Color> colors, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float scaled = t * (colors.size() - 1);
        int i1 = Math.min((int) scaled, colors.size() - 1);
        int i2 = Math.min(i1 + 1, colors.size() - 1);
        float local = scaled - i1;
        Color a = colors.get(i1), b = colors.get(i2);
        return new Color(
                Math.round(a.getRed() + local * (b.getRed() - a.getRed())),
                Math.round(a.getGreen() + local * (b.getGreen() - a.getGreen())),
                Math.round(a.getBlue() + local * (b.getBlue() - a.getBlue())));
    }

    /** Parses gradient/transition stops, ignoring a trailing numeric phase argument. */
    private static List<Color> parseColors(List<String> args) {
        List<Color> colors = new ArrayList<>();
        for (String a : args) {
            if (isHex(a)) {
                colors.add(hexToColor(a));
            } else {
                ChatColor cc = resolveColor(a);
                if (cc != null) colors.add(cc.getColor());
                // else: probably the phase number — skip
            }
        }
        return colors;
    }

    private static List<Color> prideColors(List<String> args) {
        String flag = args.isEmpty() ? "pride" : args.get(0).toLowerCase(Locale.ROOT);
        String[] hexes = switch (flag) {
            case "trans" -> new String[]{"#5BCEFA", "#F5A9B8", "#FFFFFF", "#F5A9B8", "#5BCEFA"};
            case "bi" -> new String[]{"#D60270", "#9B4F96", "#0038A8"};
            case "pan" -> new String[]{"#FF218C", "#FFD800", "#21B1FF"};
            case "lesbian" -> new String[]{"#D52D00", "#FF9A56", "#FFFFFF", "#D362A4", "#A30262"};
            case "nonbinary", "enby" -> new String[]{"#FCF434", "#FFFFFF", "#9C59D1", "#2C2C2C"};
            default -> new String[]{"#E40303", "#FF8C00", "#FFED00", "#008026", "#004DFF", "#750787"};
        };
        List<Color> colors = new ArrayList<>(hexes.length);
        for (String h : hexes) colors.add(hexToColor(h));
        return colors;
    }

    private static int countCodePoints(Node node) {
        if (node instanceof TextNode tn) return tn.text().codePointCount(0, tn.text().length());
        ElementNode el = (ElementNode) node;
        if (el.name.equals("newline") || el.name.equals("br")) return 1;
        int sum = 0;
        for (Node child : el.children) sum += countCodePoints(child);
        return sum;
    }

    private static String flatten(Node node) {
        StringBuilder sb = new StringBuilder();
        flatten(node, sb);
        return sb.toString();
    }

    private static void flatten(Node node, StringBuilder sb) {
        if (node instanceof TextNode tn) {
            sb.append(tn.text());
        } else if (node instanceof ElementNode el) {
            if (el.name.equals("newline") || el.name.equals("br")) {
                sb.append('\n');
                return;
            }
            for (Node child : el.children) flatten(child, sb);
        }
    }

    private static Color hexToColor(String hex) {
        return new Color(
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16));
    }
}
