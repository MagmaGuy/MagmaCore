package com.magmaguy.magmacore.match;

import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpaceTest extends MatchTestSupport {
    @Test
    void regionContainsOnlyItsBox() {
        MatchSpace space = MatchSpace.region(arena, new BoundingBox(0, 0, 0, 10, 100, 10));
        assertTrue(space.contains(at(arena, 5, 50, 5)));
        assertFalse(space.contains(at(arena, 15, 50, 5)));
        assertFalse(space.contains(at(overworld, 5, 50, 5)));
    }

    @Test
    void regionsGuardTheirEntryOnlyWhileStartingOrOngoing() {
        MatchSpace space = MatchSpace.region(arena, location -> true);
        assertFalse(space.guardsEntryDuring(MatchPhase.WAITING));
        assertTrue(space.guardsEntryDuring(MatchPhase.STARTING));
        assertTrue(space.guardsEntryDuring(MatchPhase.ONGOING));
        assertFalse(space.guardsEntryDuring(MatchPhase.ENDED));
    }

    @Test
    void temporaryWorldsContainEveryWorldAndCanGrow() {
        WorldMock second = server.addSimpleWorld("arena_2");
        TemporaryWorlds space = MatchSpace.temporaryWorlds(arena);
        assertFalse(space.contains(at(second, 0, 64, 0)));
        space.addWorld(second);
        assertTrue(space.contains(at(second, 0, 64, 0)));
        assertTrue(space.guardsEntryDuring(MatchPhase.WAITING));
    }

    // MockBukkit has no world container on disk, so TemporaryWorldManager's folder deletion
    // cannot run here. This checks the hand-off; the deletion itself is TemporaryWorldManager's.
    @Test
    void temporaryWorldsAreHandedToTheDeleterOnDestroy() {
        WorldMock doomed = server.addSimpleWorld("doomed");
        WorldMock added = server.addSimpleWorld("doomed_2");
        List<World> deleted = new ArrayList<>();
        TemporaryWorlds space = new TemporaryWorlds(List.of(doomed), deleted::add);
        TestMatch match = openMatch(settings -> settings.space(space));
        space.addWorld(added);
        match.destroy();
        assertEquals(List.of(doomed, added), deleted);
        assertTrue(space.worlds().isEmpty());
    }

    // A loaded world with anyone inside cannot be unloaded, so its folder would never be deleted.
    @Test
    void playersStillInsideTemporaryWorldsAreMovedOutBeforeDeletion() {
        WorldMock doomed = server.addSimpleWorld("doomed");
        List<String> occupantsAtDeletion = new ArrayList<>();
        TemporaryWorlds space = new TemporaryWorlds(List.of(doomed),
                world -> world.getPlayers().forEach(occupant -> occupantsAtDeletion.add(occupant.getName())));
        TestMatch match = openMatch(settings -> settings.space(space).exit(at(overworld, 7.5, 64, 7.5)));
        PlayerMock staff = player("Staff");
        staff.setLocation(at(doomed, 3, 64, 3));

        match.destroy();

        assertEquals(List.of(), occupantsAtDeletion);
        assertEquals(at(overworld, 7.5, 64, 7.5), staff.getLocation());
    }

    @Test
    void regionTeardownLeavesBystandersWhereTheyAre() {
        TestMatch match = openMatch(settings -> settings.exit(at(overworld, 7.5, 64, 7.5)));
        PlayerMock bystander = player("Bystander");
        bystander.setLocation(at(arena, 3, 64, 3));

        match.destroy();

        assertEquals(at(arena, 3, 64, 3), bystander.getLocation());
    }
}
