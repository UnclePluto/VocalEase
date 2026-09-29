import kotlin.io.println as emit

fun leakAccessToken(accessToken: String) {
    emit(accessToken)
}
