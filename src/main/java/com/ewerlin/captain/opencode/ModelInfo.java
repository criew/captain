package com.ewerlin.captain.opencode;

import java.util.List;

/** Verfügbares Modell: {@code id} = {@code provider/modell}, Anzeigename, Varianten. */
public record ModelInfo(String id, String name, List<String> variants) {

    public ModelInfo {
        variants = List.copyOf(variants);
    }
}
