package com.magmaguy.magmacore.util.minimessage;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.KeybindComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
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
 * representable. So the "interactive" half of MiniMessage (hover/click/etc.) is just a matter of
 * wiring the parser to the right component setters — not a reason to shade Adventure.
 *
 * <h2>Two outputs</h2>
 * <ul>
 *   <li>{@link #parse(String)} → rich {@code BaseComponent[]} for message/title sinks (full
 *       hover/click/font/keybind fidelity).</li>
 *   <li>{@link #toLegacy(String)} → a legacy {@code §}-coded string for name sinks
 *       ({@code setDisplayName}/{@code setCustomName}); colours/gradients/decorations survive,
 *       interactive events drop (names can't carry them anyway).</li>
 * </ul>
 *
 * <h2>Coverage (spike)</h2>
 * Implemented: named colours, {@code <#rrggbb>}, {@code <color:…>}, decorations + negation
 * ({@code <!bold>}), {@code <reset>}, {@code <gradient>}, {@code <rainbow>}, {@code <newline>},
 * {@code <hover:show_text>}, {@code <click:…>}, {@code <insert>/<insertion>}, {@code <font>},
 * {@code <key>}, {@code <lang>}. Lenient parsing: unrecognised or malformed {@code <…>} (e.g. a
 * literal role name like {@code <Quest Giver>}) is left verbatim, unclosed tags auto-close at end.
 *
 * <p>Not yet covered (recent/niche, BungeeCord-gap): {@code show_item} data-components,
 * {@code <nbt>}, {@code <score>}, {@code <selector>}, {@code <shadow>}, {@code <sprite>},
 * {@code <head>}, and per-segment colour overrides <em>inside</em> a gradient.
 */
public final class MiniMessageParser {

    private MiniMessageParser() {
    }

    // ─────────────────────────────────────────────────────────────── public API

    /** Parses MiniMessage into rich BungeeCord components (flat, each fully styled). */
    public static BaseComponent[] parse(String input) {
        if (input == null || input.isEmpty()) return new BaseComponent[]{new TextComponent("")};
        List<Token> tokens = tokenize(input);
        Node root = buildTree(tokens);
        List<BaseComponent> out = new ArrayList<>();
        render(root, new Style(), out);
        if (out.isEmpty()) out.add(new TextComponent(""));
        return out.toArray(new BaseComponent[0]);
    }

    /** Parses MiniMessage and serialises down to a legacy {@code §}-coded string (hex-aware). */
    public static String toLegacy(String input) {
        return BaseComponent.toLegacyText(parse(input));
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

    // decoration canonical name keyed by every alias
    private static final Map<String, String> DECORATIONS = Map.ofEntries(
            Map.entry("bold", "bold"), Map.entry("b", "bold"),
            Map.entry("italic", "italic"), Map.entry("i", "italic"), Map.entry("em", "italic"),
            Map.entry("underlined", "underlined"), Map.entry("u", "underlined"),
            Map.entry("strikethrough", "strikethrough"), Map.entry("st", "strikethrough"),
            Map.entry("obfuscated", "obfuscated"), Map.entry("obf", "obfuscated"));

    private static final Set<String> OTHER_TAGS = Set.of(
            "color", "colour", "c", "reset",
            "gradient", "rainbow", "transition", "pride",
            "newline", "br",
            "hover", "click", "insert", "insertion", "font",
            "key", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or");

    /** A bare {@code name} (no leading {@code !}, no args) is a tag we recognise. */
    private static boolean isKnownTagName(String name) {
        if (name.startsWith("!")) name = name.substring(1);
        if (NAMED_COLORS.containsKey(name)) return true;
        if (DECORATIONS.containsKey(name)) return true;
        if (OTHER_TAGS.contains(name)) return true;
        return isHex(name);
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
            if (c == '\\' && i + 1 < len) { // escape: \< and \\ become literals
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

    /** Index of the {@code >} closing the tag opened at {@code start} ({@code <}), or -1. */
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
                return -1; // nested unescaped < → outer isn't a tag
            } else if (c == '>') {
                return j;
            }
        }
        return -1;
    }

    /** Turns the raw inside of {@code <…>} into a token, or null if it isn't a recognised tag. */
    private static Token parseTag(String raw) {
        if (raw.isEmpty()) return null;
        boolean selfClose = false;
        if (raw.endsWith("/")) {
            selfClose = true;
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.startsWith("/")) { // closing tag
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

    /** Tag names are lowercase alnum / {@code _ - #}, optionally a leading {@code !}. No spaces. */
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

    /** Tags that never open a scope (no closing tag, no children). */
    private static boolean isVoid(String name) {
        return name.equals("newline") || name.equals("br") || name.equals("reset")
                || name.equals("key") || name.equals("lang") || name.equals("tr") || name.equals("translate")
                || name.equals("lang_or") || name.equals("tr_or") || name.equals("translate_or");
    }

    private static Node buildTree(List<Token> tokens) {
        ElementNode root = new ElementNode("root", List.of());
        java.util.Deque<ElementNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        for (Token t : tokens) {
            if (t instanceof TextTok tt) {
                stack.peek().children.add(new TextNode(tt.text()));
            } else if (t instanceof OpenTok ot) {
                if (ot.name().equals("reset")) {
                    // reset closes every open scope; following content resumes unstyled at root.
                    while (stack.size() > 1) stack.pop();
                    continue;
                }
                ElementNode el = new ElementNode(ot.name(), ot.args());
                stack.peek().children.add(el);
                if (!ot.selfClose() && !isVoid(ot.name())) stack.push(el);
            } else if (t instanceof CloseTok ct) {
                // reset closes everything; a normal close pops to the nearest matching open.
                if (stack.stream().anyMatch(e -> e.name.equals(ct.name()))) {
                    while (stack.size() > 1 && !stack.peek().name.equals(ct.name())) stack.pop();
                    if (stack.size() > 1) stack.pop();
                } else {
                    // stray close with no opener → literal text, MiniMessage-lenient
                    stack.peek().children.add(new TextNode("</" + ct.name() + ">"));
                }
            }
        }
        return root;
    }

    // ───────────────────────────────────────────────────────────────── render

    /** Resolved style baked onto every leaf, so we never lean on BungeeCord's extra-inheritance. */
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
            if (!tn.text().isEmpty()) {
                TextComponent c = new TextComponent(tn.text());
                style.applyTo(c);
                out.add(c);
            }
            return;
        }
        ElementNode el = (ElementNode) node;

        // Tags that produce their own component(s) rather than a style scope.
        switch (el.name) {
            case "newline", "br" -> {
                TextComponent nl = new TextComponent("\n");
                style.applyTo(nl);
                out.add(nl);
                return;
            }
            case "reset" -> {
                return; // handled at tree level (closes scopes); nothing to emit
            }
            case "gradient", "transition", "pride" -> {
                renderGradient(el, style, out);
                return;
            }
            case "rainbow" -> {
                renderRainbow(el, style, out);
                return;
            }
            case "key" -> {
                if (!el.args.isEmpty()) {
                    KeybindComponent k = new KeybindComponent(el.args.get(0));
                    style.applyTo(k);
                    out.add(k);
                }
                return;
            }
            case "lang", "tr", "translate", "lang_or", "tr_or", "translate_or" -> {
                if (!el.args.isEmpty()) {
                    TranslatableComponent tc = new TranslatableComponent(el.args.get(0));
                    style.applyTo(tc);
                    out.add(tc);
                }
                return;
            }
            case "root" -> {
                for (Node child : el.children) render(child, style, out);
                return;
            }
            default -> {
                // style-modifying tag: derive a child style and recurse into children
                Style childStyle = applyStyleTag(el, style);
                for (Node child : el.children) render(child, childStyle, out);
            }
        }
    }

    /** Returns a copy of {@code parent} with this colour/decoration/hover/click/etc. tag applied. */
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
            if (!el.args.isEmpty()) s.color = resolveColor(el.args.get(0));
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
                if (!el.args.isEmpty()) s.font = el.args.get(0);
            }
            case "insert", "insertion" -> {
                if (!el.args.isEmpty()) s.insertion = el.args.get(0);
            }
            case "hover" -> {
                // <hover:show_text:'...'> — only show_text in this spike
                if (el.args.size() >= 2 && el.args.get(0).equalsIgnoreCase("show_text"))
                    s.hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(parse(el.args.get(1))));
            }
            case "click" -> {
                if (el.args.size() >= 2) {
                    ClickEvent.Action action = clickAction(el.args.get(0));
                    if (action != null) s.click = new ClickEvent(action, el.args.get(1));
                }
            }
            default -> {
                // unrecognised here (shouldn't happen — tokenizer gates names)
            }
        }
        return s;
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

    // ──────────────────────────────────────────────────────── gradient / rainbow

    private static void renderGradient(ElementNode el, Style style, List<BaseComponent> out) {
        List<Color> colors = new ArrayList<>();
        for (String a : el.args) {
            if (isHex(a)) colors.add(hexToColor(a));
            else {
                ChatColor cc = resolveColor(a);
                if (cc != null) colors.add(cc.getColor());
            }
        }
        String text = flatten(el);
        if (colors.size() < 2 || text.isEmpty()) { // not enough stops → treat as plain styled text
            emitPlain(text, style, colors.isEmpty() ? null : colors.get(0), out);
            return;
        }
        int total = text.codePointCount(0, text.length());
        int idx = 0;
        for (int off = 0; off < text.length(); ) {
            int cp = text.codePointAt(off);
            off += Character.charCount(cp);
            float t = total <= 1 ? 0f : (float) idx / (total - 1);
            out.add(coloredCodePoint(cp, gradientColor(colors, t), style));
            idx++;
        }
    }

    private static void renderRainbow(ElementNode el, Style style, List<BaseComponent> out) {
        float phase = 0f;
        if (!el.args.isEmpty()) {
            String a = el.args.get(0).replace("!", "");
            try {
                phase = Float.parseFloat(a);
            } catch (NumberFormatException ignored) {
            }
        }
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

    private static BaseComponent coloredCodePoint(int cp, Color color, Style style) {
        TextComponent c = new TextComponent(new String(Character.toChars(cp)));
        Style s = style.copy();
        s.color = ChatColor.of(String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
        s.applyTo(c);
        return c;
    }

    private static void emitPlain(String text, Style style, Color color, List<BaseComponent> out) {
        if (text.isEmpty()) return;
        TextComponent c = new TextComponent(text);
        Style s = style.copy();
        if (color != null)
            s.color = ChatColor.of(String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
        s.applyTo(c);
        out.add(c);
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

    /** All visible text under a node, flattening nested tags ({@code <newline>} → {@code \n}). */
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
