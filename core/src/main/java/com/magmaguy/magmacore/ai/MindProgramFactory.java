package com.magmaguy.magmacore.ai;

/**
 * Catalog-safe definition that creates one executable program per entity binding.
 */
@FunctionalInterface
public interface MindProgramFactory {
    MindProgram instantiate();
}
