package com.guideglasses.ai.navigation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 眼鏡連著手機熱點時，手機在區網上的位址。
 *
 * 眼鏡沒有 SIM 卡，出門一定是連手機熱點上網 —— 這時 Wi-Fi 的預設閘道就是手機。
 * 熱點的網段 Android 11 起會隨機化（不一定是 192.168.43.1），所以每次都從目前的
 * 路由表問，不寫死。
 *
 * 眼鏡接的是一般路由器（在家、在學校）時，閘道是路由器而不是手機：直連會連不上，
 * [PhoneCompanionLocationProvider] 會退回經由後端取得位置。
 */
class PhoneHotspotAddress(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** 目前 Wi-Fi 的預設閘道（IPv4），不是 Wi-Fi 或查不到時回 null。 */
    fun gatewayHost(): String? {
        val manager = connectivity ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        return manager.getLinkProperties(network)
            ?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
    }
}
