package com.magmaguy.magmacore.dialog;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DialogOwnershipTest {
    private MockedStatic<Bukkit> bukkit;
    private ConsoleCommandSender console;
    private Player player;
    private final Object owner = new Object();

    @BeforeEach void open() {
        console = mock(ConsoleCommandSender.class);
        player = mock(Player.class);
        when(player.getName()).thenReturn("QuestTester");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
        bukkit.when(() -> Bukkit.dispatchCommand(eq(console), anyString())).thenReturn(true);
    }

    @AfterEach void close() {
        DialogManager.forgetDialogOwner(player, owner);
        bukkit.close();
    }

    private void show(Object dialogOwner) {
        DialogManager.sendDialog(player, new DialogManager.MultiActionDialogBuilder().title("Quest")
                .addAction(DialogManager.ActionButton.of("Quest", new DialogManager.RunCommandAction("/em"))), dialogOwner);
    }

    @Test void closesOwnedDialogThroughVanillaCommandOnlyOnce() {
        show(owner);
        DialogManager.clearOwnedDialog(player, owner);
        DialogManager.clearOwnedDialog(player, owner);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), times(1));
        verify(player, never()).clearDialog();
    }

    @Test void absentOrDifferentOwnerCannotClearADialog() {
        DialogManager.clearOwnedDialog(player, owner);
        DialogManager.clearOwnedDialog(player, null);
        show(owner);
        DialogManager.clearOwnedDialog(player, new Object());
        DialogManager.clearOwnedDialog(player, null);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), never());
        verify(player, never()).clearDialog();
    }

    @Test void unsuccessfulCloseRetainsOwnershipForAnotherAttempt() {
        show(owner);
        bukkit.when(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"))
                .thenReturn(false, true);
        DialogManager.clearOwnedDialog(player, owner);
        DialogManager.clearOwnedDialog(player, owner);
        DialogManager.clearOwnedDialog(player, owner);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), times(2));
    }

    @Test void thrownDispatchDoesNotForgetOwnership() {
        show(owner);
        bukkit.when(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"))
                .thenThrow(new IllegalStateException("dispatch failed")).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> DialogManager.clearOwnedDialog(player, owner));
        DialogManager.clearOwnedDialog(player, owner);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), times(2));
    }

    @Test void failedShowDoesNotClaimOwnership() {
        bukkit.when(() -> Bukkit.dispatchCommand(eq(console), anyString())).thenReturn(false);
        show(owner);
        DialogManager.clearOwnedDialog(player, owner);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), never());
        verify(player, never()).clearDialog();
    }

    @Test void unownedReplacementReleasesPreviousOwnership() {
        show(owner);
        show(null);
        DialogManager.clearOwnedDialog(player, owner);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), never());
    }

    @Test void forgottenDialogIsNotCleared() {
        show(owner);
        DialogManager.forgetDialogOwner(player, owner);
        DialogManager.clearOwnedDialog(player, owner);
        bukkit.verify(() -> Bukkit.dispatchCommand(console, "minecraft:dialog clear QuestTester"), never());
    }
}
