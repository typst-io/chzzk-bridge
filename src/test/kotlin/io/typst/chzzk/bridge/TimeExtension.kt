package io.typst.chzzk.bridge

import java.time.Instant
import java.time.temporal.ChronoUnit

fun nowInstant(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)