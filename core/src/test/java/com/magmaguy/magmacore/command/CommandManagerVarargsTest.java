package com.magmaguy.magmacore.command;

import com.magmaguy.magmacore.command.arguments.ICommandArgument;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandManagerVarargsTest {

    @Test
    void boundedAndVarargsOverloadsAreSelectedByArity() {
        TestCommand bounded = TestCommand.boundedElite();
        TestCommand withPowers = TestCommand.eliteWithPowers();
        List<AdvancedCommand> commands = List.of(bounded, withPowers);

        assertSame(bounded, CommandManager.findMatchingCommand(
                commands, new String[]{"spawn", "elite", "ZOMBIE", "10"}));
        assertSame(withPowers, CommandManager.findMatchingCommand(
                commands, new String[]{"spawn", "elite", "ZOMBIE", "10", "fire.yml"}));
        assertSame(withPowers, CommandManager.findMatchingCommand(
                commands,
                new String[]{"spawn", "elite", "ZOMBIE", "10", "fire.yml", "shield.yml"}));
        assertNull(CommandManager.findMatchingCommand(
                List.of(withPowers), new String[]{"spawn", "elite", "ZOMBIE", "10"}));
    }

    @Test
    void managerDispatchesAllVarargsTokensToTheSequenceAccessor() {
        CommandManager manager = new CommandManager("elitemobs");
        TestCommand withPowers = TestCommand.eliteWithPowers();
        manager.registerCommand(withPowers);

        boolean handled = manager.onCommand(
                permissiveSender(),
                null,
                "elitemobs",
                new String[]{
                        "spawn", "elite", "ZOMBIE", "10", "fire.yml", "shield.yml"
                });

        assertTrue(handled);
        assertEquals(1, withPowers.executions);
        assertEquals("fire.yml shield.yml", withPowers.receivedSequence);
    }

    @Test
    void boundedCommandRejectsExcessInputAndRemainsAvailableAsHelp() {
        TestCommand bounded = TestCommand.boundedElite();
        String[] excessInput = {"spawn", "elite", "ZOMBIE", "10", "unexpected"};

        assertNull(CommandManager.findMatchingCommand(List.of(bounded), excessInput));
        assertEquals(List.of(bounded),
                CommandManager.findAliasSuggestions(List.of(bounded), "spawn"));
    }

    @Test
    void literalsStillDisambiguateCommandsSharingAnAlias() {
        TestCommand withPowers = TestCommand.eliteWithPowers();
        TestCommand boss = TestCommand.boundedBoss();

        assertSame(boss, CommandManager.findMatchingCommand(
                List.of(withPowers, boss),
                new String[]{"spawn", "boss", "test_boss.yml"}));
    }

    @Test
    void tabCompletionRepeatsTheFinalDefinitionForVarargsInput() {
        CommandManager manager = new CommandManager("elitemobs");
        manager.registerCommand(TestCommand.eliteWithPowers());

        List<String> completions = manager.onTabComplete(
                permissiveSender(),
                null,
                "elitemobs",
                new String[]{"spawn", "elite", "ZOMBIE", "10", "fire.yml", "shi"});

        assertEquals(List.of("shield.yml"), completions);
    }

    @Test
    void optionalVarargsAcceptZeroOrManyValues() {
        TestCommand command = TestCommand.optionalAnnouncement();

        assertSame(command, CommandManager.findMatchingCommand(
                List.of(command), new String[]{"announce"}));
        assertSame(command, CommandManager.findMatchingCommand(
                List.of(command), new String[]{"announce", "hello", "world"}));
    }

    @Test
    void noArgumentMayBeDeclaredAfterVarargs() {
        IllegalStateException exception =
                assertThrows(IllegalStateException.class, TestCommand::invalidTrailingArgument);

        assertTrue(exception.getMessage().contains("final argument"));
    }

    @Test
    void requiredArgumentMayNotFollowAnOptionalArgument() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                TestCommand::invalidRequiredAfterOptional);

        assertTrue(exception.getMessage().contains("cannot follow"));
    }

    @Test
    void commandAliasesMatchRegardlessOfCase() {
        TestCommand command = new TestCommand(List.of("selectFloor"));

        assertTrue(command.aliasMatches("selectfloor"));
        assertTrue(command.aliasMatches("SELECTFLOOR"));
        assertSame(command, CommandManager.findMatchingCommand(
                List.of(command), new String[]{"SeLeCtFlOoR"}));
    }

    @Test
    void commandAliasPrefixesMatchRegardlessOfCase() {
        TestCommand command = new TestCommand(List.of("selectFloor"));

        assertTrue(command.aliasStartMatches("selectf"));
        assertTrue(command.aliasStartMatches("SELECTF"));
        assertEquals(List.of(command),
                CommandManager.findAliasSuggestions(List.of(command), "SeLeCtF"));
    }

    private static CommandSender permissiveSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return true;
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == byte.class) return (byte) 0;
                    if (returnType == short.class) return (short) 0;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == float.class) return 0F;
                    if (returnType == double.class) return 0D;
                    if (returnType == char.class) return '\0';
                    return null;
                });
    }

    private static final class TestCommand extends AdvancedCommand {
        private int executions;
        private String receivedSequence;
        private String sequenceKey;

        private TestCommand(List<String> aliases) {
            super(aliases);
        }

        static TestCommand boundedElite() {
            TestCommand command = new TestCommand(List.of("spawn"));
            command.addLiteral("elite");
            command.addArgument("entityType", accepting("<entity>"));
            command.addArgument("level", accepting("<level>"));
            command.setUsage("/em spawn elite <entity> <level>");
            return command;
        }

        static TestCommand eliteWithPowers() {
            TestCommand command = new TestCommand(List.of("spawn"));
            command.addLiteral("elite");
            command.addArgument("entityType", accepting("<entity>"));
            command.addArgument("level", accepting("<level>"));
            command.addVarargsArgument(
                    "powers",
                    new ListStringCommandArgument(
                            List.of("fire.yml", "shield.yml"),
                            "<powers>"));
            command.sequenceKey = "powers";
            command.setUsage("/em spawn elite <entity> <level> <powers...>");
            return command;
        }

        static TestCommand boundedBoss() {
            TestCommand command = new TestCommand(List.of("spawn"));
            command.addLiteral("boss");
            command.addArgument("filename", accepting("<filename>"));
            command.setUsage("/em spawn boss <filename>");
            return command;
        }

        static TestCommand optionalAnnouncement() {
            TestCommand command = new TestCommand(List.of("announce"));
            command.addOptionalVarargsArgument("message", accepting("<message>"));
            command.setUsage("/announce [message...]");
            return command;
        }

        static TestCommand invalidTrailingArgument() {
            TestCommand command = eliteWithPowers();
            command.addArgument("after", accepting("<invalid>"));
            return command;
        }

        static TestCommand invalidRequiredAfterOptional() {
            TestCommand command = new TestCommand(List.of("invalid"));
            command.addOptionalArgument("optional", accepting("[optional]"));
            command.addArgument("required", accepting("<required>"));
            return command;
        }

        private static ICommandArgument accepting(String hint) {
            return new ICommandArgument() {
                @Override
                public String hint() {
                    return hint;
                }

                @Override
                public boolean matchesInput(String input) {
                    return true;
                }

                @Override
                public List<String> literals() {
                    return List.of();
                }

                @Override
                public List<String> getSuggestions(CommandSender sender, String partialInput) {
                    return List.of();
                }

                @Override
                public boolean isLiteral() {
                    return false;
                }
            };
        }

        @Override
        public void execute(CommandData commandData) {
            executions++;
            if (sequenceKey != null) {
                receivedSequence = commandData.getStringSequenceArgument(sequenceKey).trim();
            }
        }
    }
}
