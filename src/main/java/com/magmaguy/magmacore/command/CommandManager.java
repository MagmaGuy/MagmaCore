package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class CommandManager implements CommandExecutor, TabCompleter {
    @Getter
    private static final HashSet<CommandManager> commandManagers = new HashSet<>();
    public final List<AdvancedCommand> commands = new ArrayList<>();
    private final String commandExtension;

    public CommandManager(JavaPlugin javaPlugin, String commandExtension) {
        javaPlugin.getCommand(commandExtension).setExecutor(this);
        this.commandExtension = commandExtension;
        commandManagers.add(this);
    }

    public static void shutdown() {
        commandManagers.forEach(CommandManager::clearAllCommands);
        commandManagers.clear();
    }

    public void clearAllCommands() {
        commands.clear();
    }

    public void registerCommand(AdvancedCommand command) {
        commands.add(command);
    }

    public void unregisterCommand(Command command) {
        commands.remove(command);
    }

    @Override
    public boolean onCommand(CommandSender commandSender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            for (AdvancedCommand command : commands) {
                if (command.getAliases().isEmpty()) {
                    command.execute(new CommandData(commandSender, args, command));
                    return true;
                }
            }
            Logger.sendMessage(commandSender, "Valid commands:");
            commands.forEach(command -> commandSender.sendMessage(command.getUsage()));
            return true;
        }

        for (AdvancedCommand command : commands) {
            // We don't want to execute other commands or ones that are disabled
            if (!(command.getAliases().contains(args[0]) && command.isEnabled())) continue;

            if (command.getArgumentsList().size() != args.length - 1) continue;

            boolean sameMandatoryArguments = true;
            for (int i = command.getArgumentsList().size() - 1; i >= 0; i--) {
                if (command.getArgumentsList().get(i) instanceof String string)
                    if (!args[i + 1].equalsIgnoreCase(string)) {
                        sameMandatoryArguments = false;
                        break;
                    }
            }
            if (!sameMandatoryArguments) continue;

            if (command.getSenderType() == SenderType.PLAYER && !(commandSender instanceof Player)) {
                // Must be a player
                Logger.sendMessage(commandSender, "This command must be run as a player!");
                return false;
            }

            if (!permissionCheck(commandSender, command)) {
                // No permissions
                Logger.sendMessage(commandSender, "You do not have the permission to run this command!");
                return false;
            }

            command.execute(new CommandData(commandSender, args, command));
            return true;
        }
        // Unknown command message
        Logger.sendMessage(commandSender, "Unknown command!");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command cmd, String label, String[] args) {
        // Handle the tab completion if it's a sub-command.
        if (args.length == 1) return tabCompleteFirstArgument(commandSender, args);
        return tabCompleteRestOfArguments(commandSender, args);
    }

    private List<String> tabCompleteFirstArgument(CommandSender commandSender, String[] args) {
        List<String> result = new ArrayList<>();
        for (AdvancedCommand command : commands) {
            for (String alias : command.getAliases()) {
                if (alias.toLowerCase().startsWith(args[0].toLowerCase()) &&
                        (command.isEnabled() && permissionCheck(commandSender, command))) {
                    result.add(alias);
                }
            }
        }
        return result;
    }

    private List<String> tabCompleteRestOfArguments(CommandSender commandSender, String[] args) {
        List<String> output = new ArrayList<>();
        // Let the sub-command handle the tab completion
        for (AdvancedCommand command : commands) {
            if (command.getAliases().contains(args[0]) &&
                    command.getArgumentsList().size() >= args.length - 1 &&
                    command.isEnabled() &&
                    permissionCheck(commandSender, command)) {
                boolean sameMandatoryArguments = true;
                for (int i = 1; i < args.length; i++) {
                    if (command.getArgumentsList().get(i - 1) instanceof String string &&
                            !args[i].equalsIgnoreCase(string) &&
                            i != args.length - 1) {
                        sameMandatoryArguments = false;
                        break;
                    }
                }
                if (!sameMandatoryArguments) continue;
                output.addAll(command.onTabComplete(args));
            }
        }
        return output;
    }

    private boolean permissionCheck(CommandSender commandSender, AdvancedCommand command) {
        return commandSender.hasPermission(command.getPermission()) ||
                command.getPermission().equalsIgnoreCase("") ||
                command.getPermission().equalsIgnoreCase(commandExtension + ".");
    }
}
