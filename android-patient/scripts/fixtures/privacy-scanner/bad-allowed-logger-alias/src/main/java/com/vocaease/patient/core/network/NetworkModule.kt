package com.vocaease.patient.core.network

import java.util.logging.Logger

private val logger = Logger.getLogger("VocaEaseNetwork")

fun accepted(diagnostic: SafeDiagnostic) {
    logger.info(diagnostic.toLogLine())
}

fun leak(accessToken: String) {
    val alias = logger
    alias.info(accessToken)
}
