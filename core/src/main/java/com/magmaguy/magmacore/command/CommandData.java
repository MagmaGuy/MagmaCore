package com.magmaguy.magmacore.command;

import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandData {
    @Getter
    private final String[] args;
    @Getter
    private final CommandSender commandSender;
    private final AdvancedCommand advancedCommand;

    public CommandData(CommandSender commandSender, String[] args, AdvancedCommand advancedCommand) {
        this.commandSender = commandSender;
        this.args = args;
        this.advancedCommand = advancedCommand;
    }

    public Player getPlayerSender() {
        return (Player) commandSender;
    }

    public String getStringArgument(String key) {
        return advancedCommand.getStringArgument(key, commandSender, args);
    }

    public Integer getIntegerArgument(String key) {
        return advancedCommand.getIntegerArgument(key, commandSender, args);
    }

    public Double getDoubleArgument(String key) {
        return advancedCommand.getDoubleArgument(key, commandSender, args);
    }

    public String getStringSequenceArgument(String key) {
        return advancedCommand.getStringSequenceArgument(key, commandSender, args);
    }

}
