package com.magmaguy.magmacore.scripting;

import java.util.List;

record LuaMindProgramMetadata(
        String identifier,
        long revision,
        List<String> modules) {

    LuaMindProgramMetadata {
        modules = List.copyOf(modules);
    }
}
