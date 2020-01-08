package com.woocommerce.android.model

sealed class RequestResponse {
    object Success : RequestResponse()
    object Error : RequestResponse()
    object ProductIncorrectSku : RequestResponse()
}
