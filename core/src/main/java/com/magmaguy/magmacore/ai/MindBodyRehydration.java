package com.magmaguy.magmacore.ai;

import java.util.Objects;
import java.util.UUID;

/** Data recovered from a vanilla carrier before its logical mind session is reopened. */
public record MindBodyRehydration(
        MobBody body,
        MindBodyProfile profile,
        UUID logicalOwner,
        String programIdentifier,
        long programRevision,
        MindPersistentState persistentState) {

    public MindBodyRehydration {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(logicalOwner, "logicalOwner");
        Objects.requireNonNull(programIdentifier, "programIdentifier");
        Objects.requireNonNull(persistentState, "persistentState");
        if (programIdentifier.isBlank()) {
            throw new IllegalArgumentException("programIdentifier cannot be blank");
        }
        if (programRevision < 1L) {
            throw new IllegalArgumentException("programRevision must be positive");
        }
        if (!body.profile().equals(profile)) {
            throw new IllegalArgumentException("Rehydrated body profile does not match its marker");
        }
    }
}
