package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.command.arguments.ICommandArgument;
import com.magmaguy.magmacore.command.arguments.LiteralCommandArgument;
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
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // If no args, either run a no-args command if it exists, or list valid commands
        if (args.length == 0) {
            for (AdvancedCommand command : commands) {
                if (command.getAliases().isEmpty()) {
                    command.execute(new CommandData(sender, args, command));
                    return true;
                }
            }
            commands.forEach(command -> sender.sendMessage(command.getUsage()));
            return true;
        }

        // Iterate over all commands looking for an exact match
        for (AdvancedCommand command : commands) {
            // Must match the primary alias and be enabled
            if (!command.isEnabled()) continue;
            if (!command.getAliases().contains(args[0])) continue;

            // Check if the number of provided arguments exactly matches what is expected.
            // (command.getArgumentsList().size() represents the extra arguments after the alias.)
            if (args.length != command.getArgumentsList().size() + 1) continue;

            boolean valid = true;
            for (int i = 0; i < command.getArgumentsList().size(); i++) {
                // Only verify literal arguments
                if (!command.getArgumentsList().get(i).isLiteral()) continue;

                // We use args[i+1] since args[0] is the base alias
                if (!((LiteralCommandArgument) command.getArgumentsList().get(i))
                        .getLiteral().equals(args[i + 1])) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;

            // Check sender type (e.g. command must be run by a player)
            if (command.getSenderType() == SenderType.PLAYER && !(sender instanceof Player)) {
                Logger.sendMessage(sender, "This command must be run as a player!");
                return false;
            }

            // Check permission
            if (!permissionCheck(sender, command)) {
                Logger.sendMessage(sender, "You do not have permission to run this command!");
                return false;
            }

            // Execute the command
            command.execute(new CommandData(sender, args, command));
            return true;
        }

        // If no matching subcommand was found, provide suggestions:
        List<AdvancedCommand> suggestions = new ArrayList<>();
        for (AdvancedCommand command : commands) {
            if (!command.isEnabled()) continue;
            for (String alias : command.getAliases()) {
                if (alias.toLowerCase().startsWith(args[0].toLowerCase())) {
                    suggestions.add(command);
                    break;
                }
            }
        }

        if (!suggestions.isEmpty()) {
            Logger.sendMessage(sender, "Unknown command! Did you mean one of the following?");
            for (AdvancedCommand suggestion : suggestions) {
                sender.sendMessage(" " + suggestion.getUsage());
            }
        } else {
            Logger.sendMessage(sender, "Unknown command!");
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        return tabCompleteRestOfArguments(sender, args);
    }

    private List<String> tabCompleteRestOfArguments(CommandSender sender, String[] args) {
        if (args[0] == null) return List.of();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (AdvancedCommand command : commands) {
                if (command.aliasStartMatches(args[0]))
                    completions.addAll(command.getAliases());
            }
            return completions;
        }

        for (AdvancedCommand command : commands) {
            if (!command.aliasMatches(args[0])) continue;
            if (!command.isEnabled() || !permissionCheck(sender, command)) continue;

            int currentArgumentIndex = args.length - 2;
            String currentArgument = args[args.length - 1];

            if (currentArgumentIndex >= command.getArgumentsList().size()) continue;

            boolean argumentsSoFarValid = true;
            // Validate all previous arguments (0 to currentArgumentIndex - 1)
            for (int i = 0; i < currentArgumentIndex; i++) {
                ICommandArgument argDef = command.getArgumentsList().get(i);
                if (!argDef.matchesInput(args[i + 1])) { // Check all argument types
                    argumentsSoFarValid = false;
                    break;
                }
            }
            if (!argumentsSoFarValid) continue;

            completions.addAll(command.getArgumentsList().get(currentArgumentIndex).getSuggestions(sender, currentArgument));
        }

        return completions;
    }

    private boolean permissionCheck(CommandSender commandSender, AdvancedCommand command) {
        return commandSender.hasPermission(command.getPermission()) ||
                command.getPermission().equalsIgnoreCase("") ||
                command.getPermission().equalsIgnoreCase(commandExtension + ".");
    }
}
