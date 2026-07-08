rootProject.name = "platform"

include("consensus")
// Java modules live under src/java so all Java is grouped together (mirrors netctl); map the dir.
project(":consensus").projectDir = file("src/java/consensus")
