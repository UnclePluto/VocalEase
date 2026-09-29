package fixture

import androidx.work.Data

fun leak(password: String): Data {
    val indirectPayload = password
    return Data.Builder().putString("payload", indirectPayload).build()
}
