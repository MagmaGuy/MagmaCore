package com.magmaguy.magmacore.nightbreak;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NightbreakAccountLiveAccessTest {
    @Test
    void configuredTokenCanReachAuthoritativeDlcAccessEndpoint()
            throws Exception {
        String tokenFile =
                System.getenv("MC_ACCEPTANCE_NIGHTBREAK_TOKEN_FILE");
        assumeTrue(
                tokenFile != null && !tokenFile.isBlank(),
                "Set MC_ACCEPTANCE_NIGHTBREAK_TOKEN_FILE for this opt-in live probe.");

        String token = YamlConfiguration
                .loadConfiguration(new File(tokenFile))
                .getString("token");
        assertNotNull(token, "The configured token file has no token.");

        Constructor<NightbreakAccount> constructor =
                NightbreakAccount.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        NightbreakAccount account = constructor.newInstance(token.trim());

        NightbreakAccount.AccessInfo access =
                account.checkAccess("bone-monastery");

        assertNotNull(access, "The live access endpoint returned no result.");
        assertTrue(
                access.hasAccess,
                () -> "Expected access for bone-monastery, got " + access);
    }
}
