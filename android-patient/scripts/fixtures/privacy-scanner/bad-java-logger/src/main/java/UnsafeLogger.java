package fixture;

import java.util.logging.Logger;

final class UnsafeLogger {
    void leak(String password) {
        Logger.getLogger("fixture").warning(password);
    }
}
