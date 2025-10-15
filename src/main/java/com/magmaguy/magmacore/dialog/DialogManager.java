package com.magmaguy.magmacore.dialog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Provides a fluent builder API for constructing Minecraft dialog JSON definitions
 * as described in the official dialog specification for Java Edition.  The
 * builders in this file mirror every field and option documented on the
 * Minecraft Wiki, allowing plugins or mods to assemble complex dialog
 * definitions without manually crafting JSON.  Once built, the resulting
 * {@link JsonObject} can be serialized and passed to
 * {@code player.performCommand("dialog show @s " + jsonString)} to display
 * the dialog.  See the Minecraft Wiki for a full description of each field
 * and default value:contentReference[oaicite:0]{index=0}.
 */
public class DialogManager {
    private DialogManager() {
    }

    public static JsonObject serializeItemComponents(ItemStack itemStack) {
        try {
            JsonObject components = new JsonObject();

            if (!itemStack.hasItemMeta()) {
                return components;
            }

            org.bukkit.inventory.meta.ItemMeta meta = itemStack.getItemMeta();

            // Custom name
            if (meta.hasDisplayName()) {
                JsonObject customName = new JsonObject();
                customName.addProperty("text", meta.getDisplayName());
                components.add("minecraft:custom_name", customName);
            }

            // Lore
            if (meta.hasLore() && meta.getLore() != null) {
                JsonArray loreArray = new JsonArray();
                for (String loreLine : meta.getLore()) {
                    JsonObject loreComponent = new JsonObject();
                    loreComponent.addProperty("text", loreLine);
                    loreArray.add(loreComponent);
                }
                components.add("minecraft:lore", loreArray);
            }

            // Skip enchantments - they'll show in the vanilla tooltip anyway
            // The enchantments component format is complex and varies by version

            // Custom Model Data
            if (meta.hasCustomModelData()) {
                components.addProperty("minecraft:custom_model_data", meta.getCustomModelData());
            }

            // Unbreakable
            if (meta.isUnbreakable()) {
                JsonObject unbreakable = new JsonObject();
                unbreakable.addProperty("show_in_tooltip", false);
                components.add("minecraft:unbreakable", unbreakable);
            }

            return components;

        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to serialize item components: " + e.getMessage());
            e.printStackTrace();
            return new JsonObject();
        }
    }

    /**
     * Enum representing the after_action options.  These control what happens
     * after a click or submit action.  See the wiki for details:contentReference[oaicite:23]{index=23}.
     */
    public enum AfterAction {
        CLOSE("close"),
        NONE("none"),
        WAIT_FOR_RESPONSE("wait_for_response");

        private final String value;

        AfterAction(String value) {
            this.value = value;
        }
    }

    /**
     * Represents a body element within a dialog.  Implementations correspond
     * to the different body element types supported by the format such as
     * plain messages and items:contentReference[oaicite:24]{index=24}:contentReference[oaicite:25]{index=25}.
     */
    public interface BodyElement {
        JsonObject toJson();
    }

    /**
     * Represents an input control in a dialog.  Input controls allow the
     * client to collect information from the player and send it back as a
     * template substitution or NBT tag:contentReference[oaicite:34]{index=34}.
     */
    public interface InputControl {
        JsonObject toJson();
    }

    /**
     * Base interface for click actions.  Concrete actions correspond to the
     * various static and dynamic action types enumerated in the spec:contentReference[oaicite:61]{index=61}.
     */
    public interface Action {
        JsonObject toJson();
    }

    /**
     * Base builder for all dialog types.  Common fields shared by every dialog
     * are exposed here.  The type‑specific builders extend this class and
     * override the {@link #build()} method to insert additional keys.
     *
     * @param <T> the concrete builder type used for fluent chaining
     */
    public abstract static class DialogBuilder<T extends DialogBuilder<T>> {
        protected final String type;
        protected final List<BodyElement> bodyElements = new ArrayList<>();
        protected final List<InputControl> inputControls = new ArrayList<>();
        protected JsonElement title;
        protected JsonElement externalTitle;
        protected Boolean canCloseWithEscape;
        protected Boolean pause;
        protected AfterAction afterAction;

        protected DialogBuilder(String type) {
            this.type = type;
        }

        /**
         * Sets the title of the dialog.  The title is always visible and uses
         * the text component format:contentReference[oaicite:1]{index=1}.
         *
         * @param title text component for the title
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T title(JsonElement title) {
            this.title = title;
            return (T) this;
        }

        /**
         * Convenience overload for plain string titles.  The provided string
         * will be wrapped in a simple text component.
         *
         * @param text plain text title
         * @return this builder
         */
        public T title(String text) {
            return title(TextComponent.of(text));
        }

        /**
         * Sets an external title used when the dialog appears in the pause
         * menu or quick actions list.  If omitted, the main title is reused
         * :contentReference[oaicite:2]{index=2}.
         *
         * @param externalTitle text component for the external title
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T externalTitle(JsonElement externalTitle) {
            this.externalTitle = externalTitle;
            return (T) this;
        }

        /**
         * Convenience overload for plain string external titles.
         *
         * @param text plain text external title
         * @return this builder
         */
        public T externalTitle(String text) {
            return externalTitle(TextComponent.of(text));
        }

        /**
         * Adds a body element to the dialog.  Body elements appear between
         * the title and the action/input controls:contentReference[oaicite:3]{index=3}.
         *
         * @param element the body element
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T addBody(BodyElement element) {
            this.bodyElements.add(element);
            return (T) this;
        }

        /**
         * Adds an input control to the dialog.  Inputs allow players to
         * provide data (text, boolean, option, or number range) and are
         * available on all dialog types:contentReference[oaicite:4]{index=4}.
         *
         * @param control the input control
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T addInput(InputControl control) {
            this.inputControls.add(control);
            return (T) this;
        }

        /**
         * Controls whether the Escape key closes the dialog.  Defaults to true
         * :contentReference[oaicite:5]{index=5}.
         *
         * @param canCloseWithEscape true to allow closing with Esc
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T canCloseWithEscape(boolean canCloseWithEscape) {
            this.canCloseWithEscape = canCloseWithEscape;
            return (T) this;
        }

        /**
         * Controls whether the dialog pauses the game in singleplayer.  Defaults
         * to true:contentReference[oaicite:6]{index=6}.
         *
         * @param pause true to pause the game when the dialog is open
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T pause(boolean pause) {
            this.pause = pause;
            return (T) this;
        }

        /**
         * Sets the after_action behaviour.  Determines how the dialog behaves
         * after actions or inputs are submitted, e.g. close the dialog, do
         * nothing, or show a waiting screen:contentReference[oaicite:7]{index=7}.
         *
         * @param afterAction the after action enum
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        public T afterAction(AfterAction afterAction) {
            this.afterAction = afterAction;
            return (T) this;
        }

        /**
         * Builds the common portion of a dialog JSON object.  Subclasses should
         * call this method and then insert their own type‑specific properties.
         *
         * @return partially built JsonObject representing the dialog
         */
        protected JsonObject buildBase() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", type);
            if (title != null) {
                obj.add("title", title);
            }
            if (externalTitle != null) {
                obj.add("external_title", externalTitle);
            }
            if (!bodyElements.isEmpty()) {
                if (bodyElements.size() == 1) {
                    obj.add("body", bodyElements.get(0).toJson());
                } else {
                    JsonArray arr = new JsonArray();
                    for (BodyElement element : bodyElements) {
                        arr.add(element.toJson());
                    }
                    obj.add("body", arr);
                }
            }
            if (!inputControls.isEmpty()) {
                JsonArray inputsArray = new JsonArray();
                for (InputControl control : inputControls) {
                    inputsArray.add(control.toJson());
                }
                obj.add("inputs", inputsArray);
            }
            if (canCloseWithEscape != null) {
                obj.addProperty("can_close_with_escape", canCloseWithEscape);
            }
            if (pause != null) {
                obj.addProperty("pause", pause);
            }
            if (afterAction != null) {
                obj.addProperty("after_action", afterAction.value);
            }
            return obj;
        }

        /**
         * Builds the final JsonObject representing the dialog.  Concrete
         * subclasses override this to add type‑specific fields before
         * returning the object.
         *
         * @return fully built JsonObject
         */
        public abstract JsonObject build();

        /**
         * Serializes the built dialog to a JSON string.  Users can call
         * {@link #build()} if they need the JsonObject itself.
         *
         * @return JSON string representation
         */
        public String toJson() {
            return build().toString();
        }
    }

    /**
     * Builder for notice dialogs.  Notice dialogs display a single action
     * button in the footer; by default this is a generic OK button:contentReference[oaicite:8]{index=8}.
     */
    public static class NoticeDialogBuilder extends DialogBuilder<NoticeDialogBuilder> {
        private ActionButton action;

        public NoticeDialogBuilder() {
            super("minecraft:notice");
        }

        /**
         * Sets the action button for the notice.  If omitted, the dialog will
         * show a default OK button with no action.:contentReference[oaicite:9]{index=9}.
         *
         * @param action the action button to display
         * @return this builder
         */
        public NoticeDialogBuilder action(ActionButton action) {
            this.action = action;
            return this;
        }

        @Override
        public JsonObject build() {
            JsonObject obj = buildBase();
            if (action != null) {
                obj.add("action", action.toJson());
            }
            return obj;
        }
    }

    /**
     * Builder for confirmation dialogs.  Confirmation dialogs display two
     * buttons—yes and no—allowing players to choose between two actions
     * :contentReference[oaicite:10]{index=10}.
     */
    public static class ConfirmationDialogBuilder extends DialogBuilder<ConfirmationDialogBuilder> {
        private ActionButton yesButton;
        private ActionButton noButton;

        public ConfirmationDialogBuilder() {
            super("minecraft:confirmation");
        }

        /**
         * Sets the button for the positive (yes) outcome.
         *
         * @param button the yes button
         * @return this builder
         */
        public ConfirmationDialogBuilder yes(ActionButton button) {
            this.yesButton = button;
            return this;
        }

        /**
         * Sets the button for the negative (no) outcome.
         *
         * @param button the no button
         * @return this builder
         */
        public ConfirmationDialogBuilder no(ActionButton button) {
            this.noButton = button;
            return this;
        }

        @Override
        public JsonObject build() {
            JsonObject obj = buildBase();
            if (yesButton != null) {
                obj.add("yes", yesButton.toJson());
            }
            if (noButton != null) {
                obj.add("no", noButton.toJson());
            }
            return obj;
        }
    }

    /**
     * Builder for multi‑action dialogs.  Multi‑action dialogs display a scrollable
     * list of buttons.  Each button has a label, optional tooltip, width and
     * an action.  You can configure the number of columns and optionally add an
     * exit button which also handles the Escape key:contentReference[oaicite:11]{index=11}.
     */
    public static class MultiActionDialogBuilder extends DialogBuilder<MultiActionDialogBuilder> {
        private final List<ActionButton> actions = new ArrayList<>();
        private Integer columns;
        private ActionButton exitAction;

        public MultiActionDialogBuilder() {
            super("minecraft:multi_action");
        }

        /**
         * Adds an action button to the list.  At least one action is required.
         *
         * @param button the action button to add
         * @return this builder
         */
        public MultiActionDialogBuilder addAction(ActionButton button) {
            Objects.requireNonNull(button, "button");
            this.actions.add(button);
            return this;
        }

        /**
         * Sets the number of columns to arrange the action buttons in.  Defaults
         * to 2 if unspecified:contentReference[oaicite:12]{index=12}.
         *
         * @param columns number of columns (must be positive)
         * @return this builder
         */
        public MultiActionDialogBuilder columns(int columns) {
            if (columns < 1) throw new IllegalArgumentException("columns must be >= 1");
            this.columns = columns;
            return this;
        }

        /**
         * Sets an optional exit action.  When present, a button will appear in
         * the footer which both closes the dialog and performs the supplied
         * action:contentReference[oaicite:13]{index=13}.
         *
         * @param button the exit action button
         * @return this builder
         */
        public MultiActionDialogBuilder exitAction(ActionButton button) {
            this.exitAction = button;
            return this;
        }

        @Override
        public JsonObject build() {
            if (actions.isEmpty()) {
                throw new IllegalStateException("multi_action dialog requires at least one action");
            }
            JsonObject obj = buildBase();
            JsonArray arr = new JsonArray();
            for (ActionButton btn : actions) {
                arr.add(btn.toJson());
            }
            obj.add("actions", arr);
            if (columns != null) {
                obj.addProperty("columns", columns);
            }
            if (exitAction != null) {
                obj.add("exit_action", exitAction.toJson());
            }
            return obj;
        }
    }

    /**
     * Builder for server links dialogs.  These dialogs list all server links
     * defined via the server_links registry.  The only configurable options are
     * the exit action, number of columns, and button width:contentReference[oaicite:14]{index=14}.
     */
    public static class ServerLinksDialogBuilder extends DialogBuilder<ServerLinksDialogBuilder> {
        private ActionButton exitAction;
        private Integer columns;
        private Integer buttonWidth;

        public ServerLinksDialogBuilder() {
            super("minecraft:server_links");
        }

        /**
         * Sets an optional exit action.  When present, a button will appear in
         * the footer which both closes the dialog and performs the supplied
         * action:contentReference[oaicite:15]{index=15}.
         *
         * @param button the exit action button
         * @return this builder
         */
        public ServerLinksDialogBuilder exitAction(ActionButton button) {
            this.exitAction = button;
            return this;
        }

        /**
         * Sets the number of columns to arrange the server link buttons in.  Defaults
         * to 2 if unspecified:contentReference[oaicite:16]{index=16}.
         *
         * @param columns number of columns (must be positive)
         * @return this builder
         */
        public ServerLinksDialogBuilder columns(int columns) {
            if (columns < 1) throw new IllegalArgumentException("columns must be >= 1");
            this.columns = columns;
            return this;
        }

        /**
         * Sets the width of each server link button.  Defaults to 150 if
         * unspecified:contentReference[oaicite:17]{index=17}.
         *
         * @param width button width (1–1024)
         * @return this builder
         */
        public ServerLinksDialogBuilder buttonWidth(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.buttonWidth = width;
            return this;
        }

        @Override
        public JsonObject build() {
            JsonObject obj = buildBase();
            if (exitAction != null) {
                obj.add("exit_action", exitAction.toJson());
            }
            if (columns != null) {
                obj.addProperty("columns", columns);
            }
            if (buttonWidth != null) {
                obj.addProperty("button_width", buttonWidth);
            }
            return obj;
        }
    }

    /**
     * Builder for dialog list dialogs.  These dialogs present buttons that
     * navigate to other dialogs.  Each entry can be a namespaced identifier
     * referencing a dialog in a data pack or an inline definition:contentReference[oaicite:18]{index=18}.
     */
    public static class DialogListDialogBuilder extends DialogBuilder<DialogListDialogBuilder> {
        private final List<DialogReference> dialogs = new ArrayList<>();
        private ActionButton exitAction;
        private Integer columns;
        private Integer buttonWidth;

        public DialogListDialogBuilder() {
            super("minecraft:dialog_list");
        }

        /**
         * Adds a dialog reference to the list.  The reference can be either an
         * identifier (e.g. "custom:my_dialog") or a new dialog definition built
         * inline.
         *
         * @param ref dialog reference
         * @return this builder
         */
        public DialogListDialogBuilder addDialog(DialogReference ref) {
            Objects.requireNonNull(ref, "dialog reference");
            this.dialogs.add(ref);
            return this;
        }

        /**
         * Sets an optional exit action.  When present, a button will appear in
         * the footer which both closes the dialog and performs the supplied
         * action:contentReference[oaicite:19]{index=19}.
         *
         * @param button exit button
         * @return this builder
         */
        public DialogListDialogBuilder exitAction(ActionButton button) {
            this.exitAction = button;
            return this;
        }

        /**
         * Sets the number of columns to arrange the dialog entries in.  Defaults
         * to 2 if unspecified:contentReference[oaicite:20]{index=20}.
         *
         * @param columns number of columns (must be positive)
         * @return this builder
         */
        public DialogListDialogBuilder columns(int columns) {
            if (columns < 1) throw new IllegalArgumentException("columns must be >= 1");
            this.columns = columns;
            return this;
        }

        /**
         * Sets the width of each dialog button.  Defaults to 150 if unspecified
         * :contentReference[oaicite:21]{index=21}.
         *
         * @param width button width (1–1024)
         * @return this builder
         */
        public DialogListDialogBuilder buttonWidth(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.buttonWidth = width;
            return this;
        }

        @Override
        public JsonObject build() {
            JsonObject obj = buildBase();
            if (!dialogs.isEmpty()) {
                if (dialogs.size() == 1) {
                    obj.add("dialogs", dialogs.get(0).toJsonElement());
                } else {
                    JsonArray arr = new JsonArray();
                    for (DialogReference ref : dialogs) {
                        arr.add(ref.toJsonElement());
                    }
                    obj.add("dialogs", arr);
                }
            }
            if (exitAction != null) {
                obj.add("exit_action", exitAction.toJson());
            }
            if (columns != null) {
                obj.addProperty("columns", columns);
            }
            if (buttonWidth != null) {
                obj.addProperty("button_width", buttonWidth);
            }
            return obj;
        }
    }

    /**
     * Represents a reference to another dialog.  A reference can be either a
     * namespaced identifier pointing to a dialog resource in a data pack or an
     * inline definition built with a {@link DialogBuilder}.  Dialog lists may
     * contain any number of such references:contentReference[oaicite:22]{index=22}.
     */
    public static class DialogReference {
        private final String id;
        private final DialogBuilder<?> inline;

        private DialogReference(String id, DialogBuilder<?> inline) {
            this.id = id;
            this.inline = inline;
        }

        /**
         * Creates a reference to a dialog by identifier.
         *
         * @param id namespaced ID (e.g. "custom:my_dialog")
         * @return a new dialog reference
         */
        public static DialogReference id(String id) {
            return new DialogReference(Objects.requireNonNull(id, "id"), null);
        }

        /**
         * Creates a reference to an inline dialog definition.
         *
         * @param builder a dialog builder defining the nested dialog
         * @return a new dialog reference
         */
        public static DialogReference inline(DialogBuilder<?> builder) {
            return new DialogReference(null, Objects.requireNonNull(builder, "builder"));
        }

        /**
         * Converts this reference into a JSON element.  Identifiers become
         * simple strings; inline definitions become full objects.
         *
         * @return JsonElement representing the reference
         */
        private JsonElement toJsonElement() {
            if (id != null) {
                return new JsonPrimitive(id);
            }
            return inline.build();
        }
    }

    /**
     * A plain message body element.  Displays a multiline block of text.
     */
    public static class PlainMessageBody implements BodyElement {
        private final JsonElement contents;
        private Integer width;

        private PlainMessageBody(JsonElement contents) {
            this.contents = contents;
        }

        public static PlainMessageBody of(JsonElement contents) {
            return new PlainMessageBody(contents);
        }

        public static PlainMessageBody of(String text) {
            return new PlainMessageBody(TextComponent.of(text));
        }

        /**
         * Sets the maximum width of the message.  Defaults to 200:contentReference[oaicite:26]{index=26}.
         *
         * @param width width between 1 and 1024
         * @return this builder
         */
        public PlainMessageBody width(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.width = width;
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "minecraft:plain_message");
            obj.add("contents", contents);
            if (width != null) {
                obj.addProperty("width", width);
            }
            return obj;
        }
    }

    /**
     * An item body element.  Displays an item stack and an optional description
     * with configurable width/height and tooltip settings:contentReference[oaicite:27]{index=27}.
     */
    public static class ItemBody implements BodyElement {
        private final String itemId;
        private final int count;
        private JsonObject components;
        private JsonElement description;
        private Boolean showDecoration;
        private Boolean showTooltip;
        private Integer width;
        private Integer height;

        private ItemBody(String itemId, int count) {
            this.itemId = Objects.requireNonNull(itemId, "itemId");
            this.count = count;
        }

        /**
         * Creates an item body element with the specified item id and count.
         *
         * @param itemId item identifier
         * @param count  item count
         * @return new item body
         */
        public static ItemBody of(String itemId, int count) {
            return new ItemBody(itemId, count);
        }

        /**
         * Sets optional item component information (e.g. components on an item
         * stack).  See the item components wiki page for possible keys:contentReference[oaicite:28]{index=28}.
         *
         * @param components JsonObject representing additional item data
         * @return this builder
         */
        public ItemBody components(JsonObject components) {
            this.components = components;
            return this;
        }

        /**
         * Sets an optional description text to appear next to the item.  Does
         * not support nbt, score or selector components:contentReference[oaicite:29]{index=29}.
         *
         * @param description text component for the description
         * @return this builder
         */
        public ItemBody description(JsonElement description) {
            this.description = description;
            return this;
        }

        /**
         * Convenience overload for plain string descriptions.
         *
         * @param text plain text description
         * @return this builder
         */
        public ItemBody description(String text) {
            return description(TextComponent.of(text));
        }

        /**
         * Controls whether the count and durability bar are rendered over the
         * item icon.  Defaults to true:contentReference[oaicite:30]{index=30}.
         *
         * @param show true to render decoration
         * @return this builder
         */
        public ItemBody showDecoration(boolean show) {
            this.showDecoration = show;
            return this;
        }

        /**
         * Controls whether the vanilla item tooltip appears when hovering over
         * the item.  Defaults to true:contentReference[oaicite:31]{index=31}.
         *
         * @param show true to show tooltip
         * @return this builder
         */
        public ItemBody showTooltip(boolean show) {
            this.showTooltip = show;
            return this;
        }

        /**
         * Sets the horizontal size of the element in pixels.  Defaults to 16:contentReference[oaicite:32]{index=32}.
         *
         * @param width width between 1 and 256
         * @return this builder
         */
        public ItemBody width(int width) {
            if (width < 1 || width > 256) throw new IllegalArgumentException("width must be between 1 and 256");
            this.width = width;
            return this;
        }

        /**
         * Sets the vertical size of the element in pixels.  Defaults to 16:contentReference[oaicite:33]{index=33}.
         *
         * @param height height between 1 and 256
         * @return this builder
         */
        public ItemBody height(int height) {
            if (height < 1 || height > 256) throw new IllegalArgumentException("height must be between 1 and 256");
            this.height = height;
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "minecraft:item");

            // item payload
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("id", itemId);
            if (count > 0) itemObj.addProperty("count", count);
            if (components != null) itemObj.add("components", components);
            obj.add("item", itemObj);

            // description object: { "contents": <component>, "width": <int> }
            if (description != null) {
                JsonObject desc = new JsonObject();
                desc.add("contents", description);
                obj.add("description", desc);
            }

            if (showDecoration != null) obj.addProperty("show_decoration", showDecoration);
            if (showTooltip != null) obj.addProperty("show_tooltip", showTooltip);
            if (width != null) obj.addProperty("width", width);
            if (height != null) obj.addProperty("height", height);
            return obj;
        }
    }

    /**
     * A single‑line or multiline text input control:contentReference[oaicite:35]{index=35}.
     */
    public static class TextInput implements InputControl {
        private final String key;
        private final JsonElement label;
        private Integer width;
        private Boolean labelVisible;
        private String initial;
        private Integer maxLength;
        private Integer maxLines;
        private Integer height;

        private TextInput(String key, JsonElement label) {
            this.key = Objects.requireNonNull(key, "key");
            this.label = Objects.requireNonNull(label, "label");
        }

        /**
         * Creates a new text input with the specified key and label.  The key
         * identifies the input in template substitutions and NBT output
         * :contentReference[oaicite:36]{index=36}.
         *
         * @param key   unique key for the input
         * @param label text component displayed to the left of the input
         * @return new text input
         */
        public static TextInput of(String key, JsonElement label) {
            return new TextInput(key, label);
        }

        /**
         * Convenience overload for plain string labels.
         *
         * @param key   unique key
         * @param label plain text label
         * @return new text input
         */
        public static TextInput of(String key, String label) {
            return new TextInput(key, TextComponent.of(label));
        }

        /**
         * Sets the width of the input box.  Defaults to 200:contentReference[oaicite:37]{index=37}.
         *
         * @param width width between 1 and 1024
         * @return this builder
         */
        public TextInput width(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.width = width;
            return this;
        }

        /**
         * Controls whether the label is visible.  Defaults to true:contentReference[oaicite:38]{index=38}.
         *
         * @param visible true to show the label
         * @return this builder
         */
        public TextInput labelVisible(boolean visible) {
            this.labelVisible = visible;
            return this;
        }

        /**
         * Sets an initial value for the input.  If omitted, the field is
         * initially empty:contentReference[oaicite:39]{index=39}.
         *
         * @param initial initial text
         * @return this builder
         */
        public TextInput initial(String initial) {
            this.initial = initial;
            return this;
        }

        /**
         * Sets the maximum length of the input.  Defaults to 32 characters
         * :contentReference[oaicite:40]{index=40}.
         *
         * @param maxLength maximum character count
         * @return this builder
         */
        public TextInput maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        /**
         * Configures the input for multiple lines.  If maxLines is specified,
         * users cannot exceed the given number of lines; height controls the
         * pixel height of the text box:contentReference[oaicite:41]{index=41}.
         *
         * @param maxLines maximum number of lines (optional)
         * @param height   pixel height of the input box
         * @return this builder
         */
        public TextInput multiline(Integer maxLines, Integer height) {
            this.maxLines = maxLines;
            this.height = height;
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "minecraft:text");
            obj.addProperty("key", key);
            obj.add("label", label);
            if (width != null) {
                obj.addProperty("width", width);
            }
            if (labelVisible != null) {
                obj.addProperty("label_visible", labelVisible);
            }
            if (initial != null) {
                obj.addProperty("initial", initial);
            }
            if (maxLength != null) {
                obj.addProperty("max_length", maxLength);
            }
            if (maxLines != null || height != null) {
                JsonObject multi = new JsonObject();
                if (maxLines != null) multi.addProperty("max_lines", maxLines);
                if (height != null) multi.addProperty("height", height);
                obj.add("multiline", multi);
            }
            return obj;
        }
    }

    /**
     * A boolean checkbox input control:contentReference[oaicite:42]{index=42}.
     */
    public static class BooleanInput implements InputControl {
        private final String key;
        private final JsonElement label;
        private Boolean initial;
        private String onTrue;
        private String onFalse;

        private BooleanInput(String key, JsonElement label) {
            this.key = Objects.requireNonNull(key, "key");
            this.label = Objects.requireNonNull(label, "label");
        }

        public static BooleanInput of(String key, JsonElement label) {
            return new BooleanInput(key, label);
        }

        public static BooleanInput of(String key, String label) {
            return new BooleanInput(key, TextComponent.of(label));
        }

        /**
         * Sets the initial checked state.  Defaults to false:contentReference[oaicite:43]{index=43}.
         *
         * @param initial initial value
         * @return this builder
         */
        public BooleanInput initial(boolean initial) {
            this.initial = initial;
            return this;
        }

        /**
         * Sets the string value sent when true.  Defaults to "true":contentReference[oaicite:44]{index=44}.
         *
         * @param value string when checked
         * @return this builder
         */
        public BooleanInput onTrue(String value) {
            this.onTrue = value;
            return this;
        }

        /**
         * Sets the string value sent when false.  Defaults to "false":contentReference[oaicite:45]{index=45}.
         *
         * @param value string when unchecked
         * @return this builder
         */
        public BooleanInput onFalse(String value) {
            this.onFalse = value;
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "minecraft:boolean");
            obj.addProperty("key", key);
            obj.add("label", label);
            if (initial != null) {
                obj.addProperty("initial", initial);
            }
            if (onTrue != null) {
                obj.addProperty("on_true", onTrue);
            }
            if (onFalse != null) {
                obj.addProperty("on_false", onFalse);
            }
            return obj;
        }
    }

    /**
     * A single option selector input control:contentReference[oaicite:46]{index=46}.
     */
    public static class SingleOptionInput implements InputControl {
        private final String key;
        private final JsonElement label;
        private final List<Option> options = new ArrayList<>();
        private Boolean labelVisible;
        private Integer width;

        private SingleOptionInput(String key, JsonElement label) {
            this.key = Objects.requireNonNull(key, "key");
            this.label = Objects.requireNonNull(label, "label");
        }

        public static SingleOptionInput of(String key, JsonElement label) {
            return new SingleOptionInput(key, label);
        }

        public static SingleOptionInput of(String key, String label) {
            return new SingleOptionInput(key, TextComponent.of(label));
        }

        /**
         * Controls whether the label is visible.  Defaults to true:contentReference[oaicite:47]{index=47}.
         *
         * @param visible true to show the label
         * @return this builder
         */
        public SingleOptionInput labelVisible(boolean visible) {
            this.labelVisible = visible;
            return this;
        }

        /**
         * Sets the width of the input box.  Defaults to 200:contentReference[oaicite:48]{index=48}.
         *
         * @param width width between 1 and 1024
         * @return this builder
         */
        public SingleOptionInput width(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.width = width;
            return this;
        }

        /**
         * Adds an option to the selection.  At least one option is required.
         *
         * @param id      value returned on submit
         * @param display text component for the option display
         * @param initial whether this option is selected by default
         * @return this builder
         */
        public SingleOptionInput addOption(String id, JsonElement display, boolean initial) {
            options.add(new Option(id, display, initial));
            return this;
        }

        /**
         * Convenience overload using plain string for the display.
         *
         * @param id      value returned on submit
         * @param display plain text label
         * @param initial whether this option is selected by default
         * @return this builder
         */
        public SingleOptionInput addOption(String id, String display, boolean initial) {
            options.add(new Option(id, TextComponent.of(display), initial));
            return this;
        }

        @Override
        public JsonObject toJson() {
            if (options.isEmpty()) {
                throw new IllegalStateException("single_option input requires at least one option");
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "minecraft:single_option");
            obj.addProperty("key", key);
            obj.add("label", label);
            if (labelVisible != null) {
                obj.addProperty("label_visible", labelVisible);
            }
            if (width != null) {
                obj.addProperty("width", width);
            }
            JsonArray opts = new JsonArray();
            for (Option opt : options) {
                opts.add(opt.toJson());
            }
            obj.add("options", opts);
            return obj;
        }

        /**
         * Represents a single option within a single option input control.
         */
        private static class Option {
            private final String id;
            private final JsonElement display;
            private final boolean initial;

            Option(String id, JsonElement display, boolean initial) {
                this.id = id;
                this.display = display;
                this.initial = initial;
            }

            JsonObject toJson() {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", id);
                obj.add("display", display);
                if (initial) {
                    obj.addProperty("initial", true);
                }
                return obj;
            }
        }
    }

    /**
     * A number range slider input control:contentReference[oaicite:49]{index=49}.
     */
    public static class NumberRangeInput implements InputControl {
        private final String key;
        private final JsonElement label;
        private String labelFormat;
        private Integer width;
        private Float start;
        private Float end;
        private Float step;
        private Float initial;

        private NumberRangeInput(String key, JsonElement label) {
            this.key = Objects.requireNonNull(key, "key");
            this.label = Objects.requireNonNull(label, "label");
        }

        public static NumberRangeInput of(String key, JsonElement label) {
            return new NumberRangeInput(key, label);
        }

        public static NumberRangeInput of(String key, String label) {
            return new NumberRangeInput(key, TextComponent.of(label));
        }

        /**
         * Sets a translation key used to build the label when the value changes.
         * Defaults to "options.generic_value":contentReference[oaicite:50]{index=50}.
         *
         * @param format translation key
         * @return this builder
         */
        public NumberRangeInput labelFormat(String format) {
            this.labelFormat = format;
            return this;
        }

        /**
         * Sets the width of the slider.  Defaults to 200:contentReference[oaicite:51]{index=51}.
         *
         * @param width width between 1 and 1024
         * @return this builder
         */
        public NumberRangeInput width(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.width = width;
            return this;
        }

        /**
         * Sets the start of the range (minimum value):contentReference[oaicite:52]{index=52}.
         *
         * @param start minimum value
         * @return this builder
         */
        public NumberRangeInput start(float start) {
            this.start = start;
            return this;
        }

        /**
         * Sets the end of the range (maximum value):contentReference[oaicite:53]{index=53}.
         *
         * @param end maximum value
         * @return this builder
         */
        public NumberRangeInput end(float end) {
            this.end = end;
            return this;
        }

        /**
         * Sets the step size.  If omitted, any value between start and end is
         * allowed:contentReference[oaicite:54]{index=54}.
         *
         * @param step step size
         * @return this builder
         */
        public NumberRangeInput step(float step) {
            this.step = step;
            return this;
        }

        /**
         * Sets the initial value of the slider.  Defaults to the middle of the
         * range:contentReference[oaicite:55]{index=55}.
         *
         * @param initial initial value
         * @return this builder
         */
        public NumberRangeInput initial(float initial) {
            this.initial = initial;
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "minecraft:number_range");
            obj.addProperty("key", key);
            obj.add("label", label);
            if (labelFormat != null) {
                obj.addProperty("label_format", labelFormat);
            }
            if (width != null) {
                obj.addProperty("width", width);
            }
            if (start != null) {
                obj.addProperty("start", start);
            }
            if (end != null) {
                obj.addProperty("end", end);
            }
            if (step != null) {
                obj.addProperty("step", step);
            }
            if (initial != null) {
                obj.addProperty("initial", initial);
            }
            return obj;
        }
    }

    /**
     * Represents a clickable button within a dialog.  Buttons consist of a
     * label, optional tooltip, width and an action.  See the dialog format
     * specification for default values:contentReference[oaicite:56]{index=56}:contentReference[oaicite:57]{index=57}.
     */
    public static class ActionButton {
        private final JsonElement label;
        private final Action action;
        private JsonElement tooltip;
        private Integer width;

        private ActionButton(JsonElement label, Action action) {
            this.label = Objects.requireNonNull(label, "label");
            this.action = Objects.requireNonNull(action, "action");
        }

        /**
         * Creates a button with the specified label and action.
         *
         * @param label  text component for the button label
         * @param action action to perform when clicked
         * @return new action button
         */
        public static ActionButton of(JsonElement label, Action action) {
            return new ActionButton(label, action);
        }

        /**
         * Convenience overload using a plain string label.
         *
         * @param label  plain text label
         * @param action action to perform
         * @return new action button
         */
        public static ActionButton of(String label, Action action) {
            return new ActionButton(TextComponent.of(label), action);
        }

        /**
         * Sets an optional tooltip shown when the button is highlighted:contentReference[oaicite:58]{index=58}.
         *
         * @param tooltip text component for the tooltip
         * @return this builder
         */
        public ActionButton tooltip(JsonElement tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        /**
         * Convenience overload using a plain string tooltip.
         *
         * @param text plain text tooltip
         * @return this builder
         */
        public ActionButton tooltip(String text) {
            return tooltip(TextComponent.of(text));
        }

        /**
         * Sets the button width in pixels.  Defaults to 150:contentReference[oaicite:59]{index=59}:contentReference[oaicite:60]{index=60}.
         *
         * @param width width between 1 and 1024
         * @return this builder
         */
        public ActionButton width(int width) {
            if (width < 1 || width > 1024) throw new IllegalArgumentException("width must be between 1 and 1024");
            this.width = width;
            return this;
        }

        /**
         * Converts this button into a JSON object.
         *
         * @return JsonObject representing the button
         */
        private JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.add("label", label);
            if (tooltip != null) {
                obj.add("tooltip", tooltip);
            }
            if (width != null) {
                obj.addProperty("width", width);
            }
            obj.add("action", action.toJson());
            return obj;
        }
    }

    /**
     * Action opening a URL in the player's web browser:contentReference[oaicite:62]{index=62}.
     */
    public static class OpenUrlAction implements Action {
        private final String url;

        public OpenUrlAction(String url) {
            this.url = Objects.requireNonNull(url, "url");
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "open_url");
            obj.addProperty("url", url);
            return obj;
        }
    }

    /**
     * Action running a command on behalf of the player:contentReference[oaicite:63]{index=63}.
     */
    public static class RunCommandAction implements Action {
        private final String command;

        public RunCommandAction(String command) {
            this.command = Objects.requireNonNull(command, "command");
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "run_command");
            obj.addProperty("command", command);
            return obj;
        }
    }

    /**
     * Action suggesting a command into the chat box:contentReference[oaicite:64]{index=64}.
     */
    public static class SuggestCommandAction implements Action {
        private final String command;

        public SuggestCommandAction(String command) {
            this.command = Objects.requireNonNull(command, "command");
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "suggest_command");
            obj.addProperty("command", command);
            return obj;
        }
    }

    /**
     * Action changing the page in a written book:contentReference[oaicite:65]{index=65}.
     */
    public static class ChangePageAction implements Action {
        private final int page;

        public ChangePageAction(int page) {
            this.page = page;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "change_page");
            obj.addProperty("page", page);
            return obj;
        }
    }

    /**
     * Action copying a value to the clipboard:contentReference[oaicite:66]{index=66}.
     */
    public static class CopyToClipboardAction implements Action {
        private final String value;

        public CopyToClipboardAction(String value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "copy_to_clipboard");
            obj.addProperty("value", value);
            return obj;
        }
    }

    /**
     * Action showing another dialog.  The dialog can be specified either by
     * identifier or inline definition:contentReference[oaicite:67]{index=67}.
     */
    public static class ShowDialogAction implements Action {
        private final DialogReference dialog;

        public ShowDialogAction(DialogReference dialog) {
            this.dialog = Objects.requireNonNull(dialog, "dialog");
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "show_dialog");
            obj.add("dialog", dialog.toJsonElement());
            return obj;
        }
    }

    /**
     * Action sending a custom event to the server:contentReference[oaicite:68]{index=68}.
     */
    public static class CustomAction implements Action {
        private final String id;
        private String payload;

        public CustomAction(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public CustomAction payload(String payload) {
            this.payload = payload;
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "custom");
            obj.addProperty("id", id);
            if (payload != null) {
                obj.addProperty("payload", payload);
            }
            return obj;
        }
    }

    /**
     * Dynamic action that builds a run_command event using a macro template:contentReference[oaicite:69]{index=69}.
     */
    public static class DynamicRunCommandAction implements Action {
        private final String template;

        public DynamicRunCommandAction(String template) {
            this.template = Objects.requireNonNull(template, "template");
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "dynamic/run_command");
            obj.addProperty("template", template);
            return obj;
        }
    }

    /**
     * Dynamic custom action that sends all input values back to the server with
     * optional additional fields:contentReference[oaicite:70]{index=70}.
     */
    public static class DynamicCustomAction implements Action {
        private final String id;
        private final Map<String, JsonElement> additions = new HashMap<>();

        public DynamicCustomAction(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        /**
         * Adds a static field to the payload sent with the custom event.  The
         * key must not collide with any input keys.
         *
         * @param key   field name
         * @param value JSON element value
         * @return this builder
         */
        public DynamicCustomAction add(String key, JsonElement value) {
            additions.put(key, value);
            return this;
        }

        /**
         * Convenience overload for plain string values.
         *
         * @param key   field name
         * @param value plain text value
         * @return this builder
         */
        public DynamicCustomAction add(String key, String value) {
            additions.put(key, new JsonPrimitive(value));
            return this;
        }

        @Override
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "dynamic/custom");
            obj.addProperty("id", id);
            if (!additions.isEmpty()) {
                JsonObject addObj = new JsonObject();
                for (Map.Entry<String, JsonElement> entry : additions.entrySet()) {
                    addObj.add(entry.getKey(), entry.getValue());
                }
                obj.add("additions", addObj);
            }
            return obj;
        }
    }

    /**
     * Utility class for building simple text components.  Most dialog fields
     * require text components rather than plain strings.  This class provides
     * convenient factory methods for constructing common text structures.
     */
    public static final class TextComponent {
        private TextComponent() {
        }

        /**
         * Creates a plain text component with the given string.
         *
         * @param text plain text
         * @return JsonElement representing the text component
         */
        public static JsonElement of(String text) {
            JsonObject obj = new JsonObject();
            obj.addProperty("text", text);
            return obj;
        }
    }
}
