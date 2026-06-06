package com.writhdeck.app

data class SchemeColors(
    val bg: String, val fg: String,
    val bgBar: String, val fgBar: String, val bgSel: String,
    val heading: String, val comment: String, val markup: String,
    val bgAlt: String, val fgAlt: String,
    val bgBarAlt: String, val fgBarAlt: String, val bgSelAlt: String,
    val headingAlt: String, val commentAlt: String, val markupAlt: String
)

val BUILTIN_SCHEMES: Map<String, SchemeColors> = mapOf(
    "default" to SchemeColors(
        "#1a1a1a","#e8e8e8","#2a2a2a","#aaaaaa","#3a5a8a",
        "#c8a060","#606060","#6aa9d4",
        "#fdf6e3","#657b83","#eee8d5","#93a1a1","#e6ddb9",
        "#b58900","#aaaaaa","#2a7090"
    ),
    "solarized" to SchemeColors(
        "#002b36","#839496","#073642","#586e75","#004555",
        "#b58900","#586e75","#268bd2",
        "#fdf6e3","#657b83","#eee8d5","#93a1a1","#e6ddb9",
        "#b58900","#93a1a1","#268bd2"
    ),
    "gruvbox" to SchemeColors(
        "#282828","#ebdbb2","#1d2021","#a89984","#504945",
        "#fabd2f","#928374","#83a598",
        "#fbf1c7","#3c3836","#ebdbb2","#7c6f64","#d5c4a1",
        "#b57614","#a89984","#076678"
    ),
    "everforest" to SchemeColors(
        "#2b3339","#d3c6aa","#1e2326","#a7c080","#3a464c",
        "#a7c080","#7a8478","#7fbbb3",
        "#fdf6e3","#5c6a72","#efead4","#8da101","#e6e2cc",
        "#8da101","#a6b0a0","#3a94c5"
    ),
    "nord" to SchemeColors(
        "#2e3440","#d8dee9","#3b4252","#81a1c1","#434c5e",
        "#88c0d0","#4c566a","#8fbec0",
        "#eceff4","#2e3440","#e5e9f0","#5e81ac","#d8dee9",
        "#5e81ac","#4c566a","#5e81ac"
    ),
    "alt01" to SchemeColors(
        "#1a1214","#e8dcc8","#241820","#9e8878","#521828",
        "#e63060","#6e5858","#c24868",
        "#fffde9","#363c42","#eee8d5","#93a1a1","#f0e7c1",
        "#c8064a","#aaaaaa","#7e1c3e"
    ),
    "alt02" to SchemeColors(
        "#2a2520","#d4c4b0","#2a2520","#c4b4a0","#4a4035",
        "#e8a87c","#6a5a50","#c49070",
        "#f5f0eb","#3a2a20","#e8e0d8","#3a2a20","#e0d4c8",
        "#a65d2b","#a89080","#8b5a3c"
    ),
    "retro" to SchemeColors(
        "#0a0a0a","#33ff33","#111111","#22bb22","#004400",
        "#aaffaa","#1a661a","#00ffcc",
        "#ffffff","#000000","#e0e0e0","#333333","#d0d0d0",
        "#000000","#999999","#333333"
    )
)
