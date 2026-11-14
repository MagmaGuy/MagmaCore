package com.magmaguy.magmacore.ai;

@FunctionalInterface
public interface MindFailureListener {
    void onFailure(MindFailure failure);
}
