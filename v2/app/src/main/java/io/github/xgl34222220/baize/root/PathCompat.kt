package io.github.xgl34222220.baize.root

import java.nio.file.Path

internal inline fun <T> Path.useLines(block: (Sequence<String>) -> T): T = toFile().useLines(block = block)
