package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.command.arguments.ICommandArgument;
import com.magmaguy.magmacore.command.arguments.LiteralCommandArgument;
import com.magmaguy.magmacore.command.arguments.PlayerCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public abstract class AdvancedCommand {
    @Getter
    private final List<String> aliases;
    @Getter
    private final boolean enabled = true;
    @Getter
    private final List<ICommandArgument> argumentsList = new ArrayList<>();
    @Getter
    private final Map<String, Integer> argumentsMap = new HashMap<>();
    @Getter
    private String usage;
    @Getter
    private String description;
    @Getter
    private String permission = "";
    @Getter
    private SenderType senderType = SenderType.ANY;

    public AdvancedCommand(List<String> aliases) {
        this.aliases = aliases;
    }

    /**
     * Create a Bukkit Command that delegates to the given AdvancedCommand.
     *
     * @param advanced the AdvancedCommand implementation to wrap
     * @param name     the primary command name (e.g. "logify")
     * @param aliases  any aliases for the command
     * @return a Bukkit Command you can register with CommandMap
     */
    public static Command toBukkitCommand(JavaPlugin plugin,
                                          AdvancedCommand delegate,
                                          String name,
                                          List<String> aliases) {
        return new AdvancedBukkitCommand(plugin, delegate, name, aliases);
    }

    public boolean aliasMatches(String potentialAlias) {
        for (String alias : aliases)
            if (alias.equals(potentialAlias)) return true;
        return false;
    }

    public boolean aliasStartMatches(String potentialAliasStart) {
        for (String alias : aliases)
            if (alias.startsWith(potentialAliasStart)) return true;
        return false;
    }

    protected void setPermission(String permission) {
        this.permission = permission;
    }

    protected void setDescription(String description) {
        this.description = description;
    }

    protected void setSenderType(SenderType senderType) {
        this.senderType = senderType;
    }

    protected void addLiteral(String key) {
        addArgument(key, new LiteralCommandArgument(key));
    }

    protected void addPlayerArgument(String key) {
        addArgument(key, new PlayerCommandArgument());
    }

    // or if you want a generic “addArgument(ICommandArgument arg)”
    protected void addArgument(String key, ICommandArgument arg) {
        argumentsMap.put(key, argumentsList.size());
        argumentsList.add(arg);
    }

    /**
     * Register an argument the user may omit. Optional arguments must be
     * trailing — once you call this, every subsequent argument added to this
     * command should also be optional. The framework consumes optional args
     * left-to-right from the user input and reports {@code null} from
     * {@link #getStringArgument(String, CommandSender, String[])} (silently —
     * no "Key not found" feedback) when the user didn't provide a value.
     */
    protected void addOptionalArgument(String key, ICommandArgument arg) {
        addArgument(key, new OptionalArgumentDecorator(arg));
    }

    /**
     * Wraps an {@link ICommandArgument} so it reports {@code isOptional() == true}
     * without forcing every implementation to know about optional-ness. All
     * other behavior (tab-completion suggestions, validation, literals)
     * delegates to the wrapped argument unchanged.
     */
    private static final class OptionalArgumentDecorator implements ICommandArgument {
        private final ICommandArgument delegate;

        OptionalArgumentDecorator(ICommandArgument delegate) {
            this.delegate = delegate;
        }

        @Override
        public String hint() {
            return delegate.hint();
        }

        @Override
        public boolean matchesInput(String input) {
            return delegate.matchesInput(input);
        }

        @Override
        public List<String> literals() {
            return delegate.literals();
        }

        @Override
        public List<String> getSuggestions(CommandSender sender, String partialInput) {
            return delegate.getSuggestions(sender, partialInput);
        }

        @Override
        public boolean isLiteral() {
            return delegate.isLiteral();
        }

        @Override
        public boolean isOptional() {
            return true;
        }
    }

    protected void setUsage(String usage) {
        this.usage = usage;
    }

    public String getStringArgument(String key, CommandSender commandSender, String[] args) {
        Integer pos = argumentsMap.get(key);
        if (pos == null) {
            Logger.sendMessage(commandSender, "Key " + key + " not found");
            return null;
        }
        int idx = pos + 1;
        if (idx >= args.length) {
            // Silent for optional args the user chose to omit; logged for
            // required args that should always be present.
            if (!argumentsList.get(pos).isOptional()) {
                Logger.sendMessage(commandSender, "Key " + key + " not found");
            }
            return null;
        }
        return args[idx];
    }

    public Integer getIntegerArgument(String key, CommandSender commandSender, String[] args) {
        String raw = getStringArgument(key, commandSender, args);
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            Logger.sendMessage(commandSender, "Key " + key + " not a valid integer");
            return null;
        }
    }

    public Double getDoubleArgument(String key, CommandSender commandSender, String[] args) {
        String raw = getStringArgument(key, commandSender, args);
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            Logger.sendMessage(commandSender, "Key " + key + " not a valid number");
            return null;
        }
    }

    public String getStringSequenceArgument(String key, CommandSender commandSender, String[] args) {
        try {
            StringBuilder output = new StringBuilder();
            for (int i = argumentsMap.get(key) + 1; i < args.length; i++)
                output.append(args[i]).append(" ");
            return output.toString();
        } catch (Exception e) {
            Logger.sendMessage(commandSender, "Key " + key + " not found");
            return null;
        }
    }

    private boolean validateArgument(int index, CommandSender sender) {
        if (argumentsList.size() <= index) {
            Logger.sendMessage(sender, "Incorrect usage of this command!");
            Logger.sendMessage(sender, description);
            return false;
        }
        return true;
    }

    public abstract void execute(CommandData commandData);

    public List<String> onTabComplete(String[] args) {
        int index = args.length - 2;
        if (index < 0 || argumentsList.size() <= index) return Collections.emptyList();
        if (argumentsList.get(index) instanceof List list) {
            return list;
        } else if (!argumentsList.get(index).toString().isEmpty()) {
            return List.of(argumentsList.get(index).toString());
        } else return Collections.emptyList();
    }

    /**
     * Inner class that extends Bukkit's Command and hands all execution
     * & tab-complete calls back to your AdvancedCommand.
     */
    private static class AdvancedBukkitCommand extends Command {
        private final AdvancedCommand delegate;
        private final JavaPlugin plugin;

        AdvancedBukkitCommand(JavaPlugin plugin,
                              AdvancedCommand delegate,
                              String name,
                              List<String> aliases) {
            super(name,
                    delegate.getDescription(),    // pulled from your AdvancedCommand
                    delegate.getUsage(),          // likewise
                    aliases);
            this.delegate = delegate;
            this.plugin = plugin;

            if (!delegate.getPermission().isBlank()) {
                setPermission(delegate.getPermission());
                setPermissionMessage("§cYou lack permission: " + delegate.getPermission());
            }
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            // Delegate back into your AdvancedCommand
            delegate.execute(new CommandData(sender, args, delegate));
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender,
                                        String alias,
                                        String[] args)
                throws IllegalArgumentException {
            return delegate.onTabComplete(args);
        }
    }
}
