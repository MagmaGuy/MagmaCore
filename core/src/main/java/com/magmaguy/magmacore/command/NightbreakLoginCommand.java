package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakCatalogMenu;
import com.magmaguy.magmacore.nightbreak.NightbreakServerLink;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;

/**
 * Connects this server to a Nightbreak account.
 * Usage: /nightbreaklogin [token|confirm]
 * <p>
 * Without a token it prints a link to approve this server on nightbreak.io
 * (see {@link NightbreakServerLink}). With a token it saves that token. With
 * {@code confirm} it applies a linked token that was held back because the
 * server already had a working one. The token lands in the shared MagmaCore
 * config folder that every MagmaGuy plugin reads.
 */
public class NightbreakLoginCommand extends AdvancedCommand {
    private final JavaPlugin plugin;

    public NightbreakLoginCommand(JavaPlugin plugin) {
        super(new ArrayList<>());
        setUsage("/nightbreaklogin [token|confirm]");
        setDescription("Link this server to your Nightbreak account");
        setSenderType(SenderType.ANY);
        setPermission("nightbreak.login");
        this.plugin = plugin;

        // Optional: without it, the command starts a browser link instead
        addOptionalArgument("token", new ListStringCommandArgument("<token>"));
    }

    @Override
    public void execute(CommandData commandData) {
        if (!commandData.getCommandSender().hasPermission("nightbreak.login")) {
            Logger.sendMessage(commandData.getCommandSender(), "&cYou don't have permission to use this command.");
            return;
        }

        String[] args = commandData.getArgs();

        if (args.length < 1 || args[0] == null || args[0].isBlank()) {
            NightbreakServerLink.start(plugin, commandData.getCommandSender());
            return;
        }
        if (args[0].equalsIgnoreCase("confirm")) {
            NightbreakServerLink.confirm(plugin, commandData.getCommandSender());
            return;
        }

        String token = args[0].trim();

        // Warn if token doesn't look like a Nightbreak token (they typically start with nbk_)
        if (!token.startsWith("nbk_")) {
            Logger.sendMessage(commandData.getCommandSender(), "&eWarning: Token doesn't appear to be an account token (should start with 'nbk_').");
            Logger.sendMessage(commandData.getCommandSender(), "&eProceeding anyway...");
        }

        // Register the token
        NightbreakAccount account = NightbreakAccount.registerToken(plugin, token);

        if (account != null) {
            NightbreakCatalogMenu.sendLoginSuccess(commandData.getCommandSender(), plugin);
        } else {
            Logger.sendMessage(commandData.getCommandSender(), "&cFailed to save account token. Check console for errors.");
        }
    }
}
