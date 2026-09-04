package com.magmaguy.magmacore.ai;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record MindSnapshot(
        UUID logicalOwner,
        Optional<UUID> bodyEntityId,
        String programIdentifier,
        long programRevision,
        long generation,
        Set<String> activeBehaviors,
        MindPersistentState persistentState,
        boolean paused,
        boolean closed) {

    public MindSnapshot {
        bodyEntityId = bodyEntityId == null ? Optional.empty() : bodyEntityId;
        activeBehaviors = Collections.unmodifiableSet(new LinkedHashSet<>(activeBehaviors));
    }
}
