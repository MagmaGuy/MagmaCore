package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.initialization.PluginInitializationContext;

public interface NightbreakPluginHooks {
    void asyncInitialization(PluginInitializationContext initializationContext);

    void syncInitialization(PluginInitializationContext initializationContext);

    default void onInitializationSuccess() {
    }

    default void onInitializationFailure(Throwable throwable) {
    }
}
