package com.example.library.catalog.adapter.out.persistence;

/** Construction de motifs LIKE à partir d'un texte saisi : tous les caractères sont littéraux. */
final class LikePatterns {

    private LikePatterns() {
    }

    /** Échappe {@code \}, {@code %} et {@code _} (dans cet ordre), puis entoure de {@code %}. */
    static String containsPattern(String text) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
