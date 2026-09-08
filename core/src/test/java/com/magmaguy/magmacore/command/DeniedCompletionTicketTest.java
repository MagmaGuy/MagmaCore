package com.magmaguy.magmacore.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** BetterStructures #61: the shared command manager must filter denied root aliases. */
class DeniedCompletionTicketTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
    void rootSuggestionsExcludeCommandsTheSenderCannotUse() {
        CommandManager manager = new CommandManager("betterstructures");
        manager.registerCommand(new AdvancedCommand(List.of("setup")) {
            { setPermission("betterstructures.setup"); }
            @Override public void execute(CommandData data) { throw new AssertionError("Completion must not execute commands"); }
        });
        assertEquals(List.of("setup"), manager.onTabComplete(sender(true), null, "bs", new String[]{""}));
        assertEquals(List.of(), manager.onTabComplete(sender(false), null, "bs", new String[]{""}),
                "A sender denied every permission must not receive the setup command as a suggestion");
    }
    private static CommandSender sender(boolean allowed) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return allowed;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }
}
