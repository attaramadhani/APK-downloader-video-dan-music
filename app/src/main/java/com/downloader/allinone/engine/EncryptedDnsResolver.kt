package com.downloader.allinone.engine

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object EncryptedDnsResolver : Dns {

    // Cache dengan masa berlaku: IP CDN sering berganti, cache permanen bisa membuat unduhan gagal
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val cacheTime = ConcurrentHashMap<String, Long>()

    // Direct HTTP client for DNS over HTTPS queries using raw IP
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    private fun isSafePublicIp(address: InetAddress): Boolean {
        // Blokir loopback (127.0.0.1, ::1)
        if (address.isLoopbackAddress) return false
        // Blokir link local (169.254.x.x)
        if (address.isLinkLocalAddress) return false
        // Blokir private subnet internal (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
        if (address.isSiteLocalAddress) return false
        // Blokir any local (0.0.0.0)
        if (address.isAnyLocalAddress) return false
        return true
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Return direct IP if hostname is already an IP address
        if (hostname.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$".toRegex())) {
            val addr = InetAddress.getByName(hostname)
            if (!isSafePublicIp(addr)) {
                throw UnknownHostException("Akses ke IP internal $hostname diblokir untuk keamanan.")
            }
            return listOf(addr)
        }

        // Return from memory cache if available
        val cachedAt = cacheTime[hostname] ?: 0L
        if (System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            cache[hostname]?.let { return it }
        }

        // 1. Prioritaskan System DNS bawaan HP / VPN yang sedang aktif
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname).filter { isSafePublicIp(it) }
            if (systemAddresses.isNotEmpty()) {
                cache[hostname] = systemAddresses; cacheTime[hostname] = System.currentTimeMillis()
                return systemAddresses
            }
        } catch (_: Exception) {}

        // 2. Query Cloudflare DNS over HTTPS (1.1.1.1) fallback
        val dohAddresses = queryCloudflareDoH(hostname).filter { isSafePublicIp(it) }
        if (dohAddresses.isNotEmpty()) {
            cache[hostname] = dohAddresses; cacheTime[hostname] = System.currentTimeMillis()
            return dohAddresses
        }

        // 3. Query Google DNS over HTTPS (8.8.8.8) fallback
        val googleAddresses = queryGoogleDoH(hostname).filter { isSafePublicIp(it) }
        if (googleAddresses.isNotEmpty()) {
            cache[hostname] = googleAddresses; cacheTime[hostname] = System.currentTimeMillis()
            return googleAddresses
        }

        throw UnknownHostException("Tidak dapat menemukan alamat IP publik untuk $hostname")
    }

    private fun queryCloudflareDoH(hostname: String): List<InetAddress> {
        return try {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                parseDnsJsonResponse(bodyStr)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryGoogleDoH(hostname: String): List<InetAddress> {
        return try {
            val request = Request.Builder()
                .url("https://8.8.8.8/resolve?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                parseDnsJsonResponse(bodyStr)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDnsJsonResponse(jsonStr: String): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val json = JSONObject(jsonStr)
            val answerArray = json.optJSONArray("Answer") ?: return emptyList()
            for (i in 0 until answerArray.length()) {
                val item = answerArray.getJSONObject(i)
                val type = item.optInt("type", 0)
                // Type 1 is A record (IPv4)
                if (type == 1) {
                    val ip = item.optString("data", "")
                    if (ip.isNotBlank() && ip.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$".toRegex())) {
                        addresses.add(InetAddress.getByName(ip))
                    }
                }
            }
        } catch (_: Exception) {}
        return addresses
    }
}
