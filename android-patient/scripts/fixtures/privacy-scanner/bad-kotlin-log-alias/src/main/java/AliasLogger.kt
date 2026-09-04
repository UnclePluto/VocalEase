package fixture

import android.util.Log as AuditLog

fun leak(accessToken: String) = AuditLog.e("auth", accessToken)
