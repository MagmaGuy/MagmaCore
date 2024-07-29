package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.command.CommandSender;

import java.util.*;

public abstract class AdvancedCommand {
    @Getter
    private final List<String> aliases;
    @Getter
    private final boolean enabled = true;
    @Getter
    private String usage;
    @Getter
    private String description;
    @Getter
    private String permission = "";
    @Getter
    private final List argumentsList = new ArrayList();
    @Getter
    private final Map<String, Integer> argumentsMap = new HashMap<>();
    @Getter
    private SenderType senderType = SenderType.ANY;

    public AdvancedCommand(List<String> aliases) {
        this.aliases = aliases;
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

    protected void addLiteral(String literal) {
        argumentsList.add(literal);
    }

    protected void addArgument(String key, Object value) {
        argumentsMap.put(key, argumentsList.size());
        argumentsList.add(value);
    }

    protected void setUsage(String usage) {
        this.usage = usage;
    }

    public String getStringArgument(String key, CommandSender commandSender, String[] args) {
        try {
            return args[argumentsMap.get(key) + 1];
        } catch (Exception e) {
            Logger.sendMessage(commandSender, "Key " + key + " not found");
            return null;
        }
    }

    public Integer getIntegerArgument(String key, CommandSender commandSender, String[] args) {
        try {
            return Integer.parseInt(args[argumentsMap.get(key) + 1]);
        } catch (Exception e) {
            Logger.sendMessage(commandSender, "Key " + key + " not found");
            return null;
        }
    }

    public Double getDoubleArgument(String key, CommandSender commandSender, String[] args) {
        try {
            return Double.parseDouble(args[argumentsMap.get(key) + 1]);
        } catch (Exception e) {
            Logger.sendMessage(commandSender, "Key " + key + " not found");
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
        if (argumentsList.size() <= index) return Collections.emptyList();
        if (argumentsList.get(index) instanceof List list) {
            return list;
        } else if (!argumentsList.get(index).toString().isEmpty()) {
            return List.of(argumentsList.get(index).toString());
        } else return Collections.emptyList();
    }
}
