package com.magmaguy.magmacore.ai;

@FunctionalInterface
public interface MindFailureListener {
    MindFailureListener IGNORE = failure -> {
    };

    void onFailure(MindFailure failure);
}
