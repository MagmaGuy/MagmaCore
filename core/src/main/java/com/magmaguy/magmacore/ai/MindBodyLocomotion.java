package com.magmaguy.magmacore.ai;

/** Native movement model installed on a MagmaCore Mind body. */
public enum MindBodyLocomotion {
    GROUNDED,
    FLYING,
    AQUATIC,
    /** Native pathfinding and movement across both land and water. */
    AMPHIBIOUS,
    STATIONARY
}
