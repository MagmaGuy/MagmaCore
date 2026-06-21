package com.magmaguy.easyminecraftgoals.customentity;

public final class BedrockCustomEntityBridgeRegistry {
    private static volatile BedrockCustomEntityBridge bridge = BedrockCustomEntityBridge.NO_OP;

    private BedrockCustomEntityBridgeRegistry() {
    }

    public static BedrockCustomEntityBridge bridge() {
        return bridge;
    }

    public static void register(BedrockCustomEntityBridge bridge) {
        BedrockCustomEntityBridgeRegistry.bridge =
                bridge == null ? BedrockCustomEntityBridge.NO_OP : bridge;
    }

    public static void unregister(BedrockCustomEntityBridge bridge) {
        if (BedrockCustomEntityBridgeRegistry.bridge == bridge) {
            BedrockCustomEntityBridgeRegistry.bridge = BedrockCustomEntityBridge.NO_OP;
        }
    }

    public static boolean isAvailable() {
        return bridge.isAvailable();
    }
}
