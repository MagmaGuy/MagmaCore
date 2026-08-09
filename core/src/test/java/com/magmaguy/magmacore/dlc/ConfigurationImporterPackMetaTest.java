package com.magmaguy.magmacore.dlc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * pack.meta is a plain text file authored by the DLC pipeline and, in support
 * cases, by hand. Text files normally end with a newline, and Windows editors
 * add CRLF and sometimes a UTF-8 BOM. None of that changes which plugin the
 * package declares, so none of it may change the resolved platform.
 *
 * <p>When it did, the package was rejected with "does not declare a supported
 * plugin platform", retained for retry, and silently never imported.
 */
class ConfigurationImporterPackMetaTest {

    @Test
    void resolvesPlatformWhenPackMetaEndsWithANewline() {
        assertEquals(ConfigurationImporter.PluginPlatform.WORLDCANNON,
                ConfigurationImporter.getPluginPlatform("CannonRTP\n"),
                "a trailing newline is normal in a text file and must not reject the package");
    }

    @Test
    void resolvesPlatformWhenPackMetaUsesWindowsLineEndings() {
        assertEquals(ConfigurationImporter.PluginPlatform.ELITEMOBS,
                ConfigurationImporter.getPluginPlatform("EliteMobs\r\n"));
    }

    @Test
    void resolvesPlatformWhenPackMetaCarriesAByteOrderMark() {
        assertEquals(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES,
                ConfigurationImporter.getPluginPlatform("﻿BetterStructures"));
    }

    @Test
    void resolvesPlatformWhenPackMetaIsPaddedWithWhitespace() {
        assertEquals(ConfigurationImporter.PluginPlatform.FREEMINECRAFTMODELS,
                ConfigurationImporter.getPluginPlatform("  FreeMinecraftModels \t "));
    }

    @Test
    void stillResolvesTheExactToken() {
        assertEquals(ConfigurationImporter.PluginPlatform.WORLDCANNON,
                ConfigurationImporter.getPluginPlatform("CannonRTP"));
        assertEquals(ConfigurationImporter.PluginPlatform.ETERNALTD,
                ConfigurationImporter.getPluginPlatform("EternalTD"));
    }

    @Test
    void stillRejectsAnUnknownOrEmptyPlatform() {
        assertEquals(ConfigurationImporter.PluginPlatform.NONE,
                ConfigurationImporter.getPluginPlatform("SomeOtherPlugin"));
        assertEquals(ConfigurationImporter.PluginPlatform.NONE,
                ConfigurationImporter.getPluginPlatform("   "));
        assertEquals(ConfigurationImporter.PluginPlatform.NONE,
                ConfigurationImporter.getPluginPlatform(null));
    }
}
