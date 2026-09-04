package fixture

import java.util.logging.Logger

private val logger = Logger.getLogger("fixture")
fun leak() = logger.warning("credential")
