fun leakAccessToken(accessToken: String) {
    val emit: (Any?) -> Unit = ::println
    emit(accessToken)
}
