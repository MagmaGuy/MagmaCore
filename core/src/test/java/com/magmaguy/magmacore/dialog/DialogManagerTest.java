package com.magmaguy.magmacore.dialog;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogManagerTest {

    @Test
    void legacyFormattedDialogTextSerializesWithoutSectionSigns() {
        JsonObject dialog = new DialogManager.MultiActionDialogBuilder()
                .title("§fPlayer Status Menu")
                .addBody(DialogManager.PlainMessageBody.of("§5§lPlayer Stats:\n§2Money: §a100").width(300))
                .addAction(DialogManager.ActionButton.of("§f← Back", new DialogManager.RunCommandAction("/elitemobs")))
                .build();

        String json = dialog.toString();
        assertFalse(json.contains("§"), json);
        assertTrue(json.contains("\"color\":\"white\""), json);
        assertTrue(json.contains("\"color\":\"dark_purple\""), json);
    }

    @Test
    void legacyColorCodesAreStrippedFromDialogCommands() {
        JsonObject action = new DialogManager.RunCommandAction("§f/ag").toJson();
        assertEquals("/ag", action.get("command").getAsString());
    }

}
