package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.nightbreak.NightbreakCatalogMenu;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;

/**
 * Command to register an account token.
 * Usage: /nightbreaklogin <token>
 * <p>
 * This command saves the token to a shared MagmaCore config folder
 * that is accessible by all MagmaGuy plugins.
 */
public class NightbreakLoginCommand extends AdvancedCommand {
    private final JavaPlugin plugin;

    public NightbreakLoginCommand(JavaPlugin plugin) {
        super(new ArrayList<>());
        setUsage("/nightbreaklogin <token>");
        setDescription("Register your account token");
        setSenderType(SenderType.ANY);
        setPermission("nightbreak.login");
        this.plugin = plugin;

        // Add token argument with a hint
        addArgument("token", new ListStringCommandArgument("<token>"));
    }

    @Override
    public void execute(CommandData commandData) {
        if (!commandData.getCommandSender().hasPermission("nightbreak.login")) {
            Logger.sendMessage(commandData.getCommandSender(), "&cYou don't have permission to use this command.");
            return;
        }

        String[] args = commandData.getArgs();

        // Basic token validation
        if (args.length < 1 || args[0] == null || args[0].isEmpty()) {
            Logger.sendMessage(commandData.getCommandSender(), "&cUsage: /nightbreaklogin <token>");
            Logger.sendMessage(commandData.getCommandSender(), "&7Get your token at: &9https://nightbreak.io/account");
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

            // Optionally verify the token works by checking access to a test endpoint
            // This is commented out to avoid unnecessary API calls on login
            // You could enable this for immediate feedback
            /*
            commandData.getCommandSender().sendMessage("Verifying token...");
            // Make a quick API call to verify
            */
        } else {
            Logger.sendMessage(commandData.getCommandSender(), "&cFailed to save account token. Check console for errors.");
        }
    }
}
