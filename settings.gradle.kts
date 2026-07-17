rootProject.name = "platform"

include("consensus")
// arc42 block layout (platform#2, mirrors netctl#548): each block under src/ owns its code and
// tests with the language level inside (src/consensus/src/java, tests level-on-top at
// src/consensus/test/unit/java). The Gradle module dir maps onto the block's Java language level;
// coordinates info.zwyssig.platform:consensus stay unchanged (consumer contract).
project(":consensus").projectDir = file("src/consensus/src/java")
