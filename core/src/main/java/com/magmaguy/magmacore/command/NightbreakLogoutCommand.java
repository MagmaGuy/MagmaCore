package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakLogoutMessages;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Removes the shared Nightbreak account token from this server.
 */
public class NightbreakLogoutCommand extends AdvancedCommand {
    private final JavaPlugin plugin;
    private final Supplier<NightbreakLogoutMessages> messagesSupplier;

    public NightbreakLogoutCommand(JavaPlugin plugin) {
        this(plugin, NightbreakLogoutMessages::defaults);
    }

    public NightbreakLogoutCommand(JavaPlugin plugin,
                                   Supplier<NightbreakLogoutMessages> messagesSupplier) {
        super(new ArrayList<>());
        setUsage("/nightbreaklogout");
        this.messagesSupplier = messagesSupplier == null
                ? NightbreakLogoutMessages::defaults
                : messagesSupplier;
        setDescription(messages().commandDescription());
        setSenderType(SenderType.ANY);
        setPermission("nightbreak.login");
        this.plugin = plugin;
    }

    /** Keeps command help in sync when a consumer loads translated config after onLoad. */
    @Override
    public String getDescription() {
        return messages().commandDescription();
    }

    @Override
    public String getPermissionMessage() {
        return messages().permissionDenied();
    }

    @Override
    public void execute(CommandData commandData) {
        NightbreakLogoutMessages messages = messages();
        if (!commandData.getCommandSender().hasPermission("nightbreak.login")) {
            Logger.sendMessage(commandData.getCommandSender(),
                    messages.permissionDenied());
            return;
        }
        if (NightbreakAccount.clearToken(plugin)) {
            Logger.sendSimpleMessage(commandData.getCommandSender(),
                    messages.success());
            Logger.sendSimpleMessage(commandData.getCommandSender(),
                    messages.reconnect());
        } else {
            Logger.sendSimpleMessage(commandData.getCommandSender(),
                    messages.failure());
        }
    }

    private NightbreakLogoutMessages messages() {
        try {
            NightbreakLogoutMessages messages = messagesSupplier.get();
            return messages == null ? NightbreakLogoutMessages.defaults() : messages;
        } catch (RuntimeException ignored) {
            return NightbreakLogoutMessages.defaults();
        }
    }
}
