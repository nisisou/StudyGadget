package com.example.studycompanion

import com.example.studycompanion.core.NetworkUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun hotspotConfigDoesNotHardcodeAPhoneAddress() {
        assertEquals("auto 8765 abc123\n",NetworkUtils.pairingLine("auto",8765,"abc123"))
        assertEquals("192.168.158.1 9000 abc123\n",NetworkUtils.pairingLine("192.168.158.1",9000,"abc123"))
        assertEquals("10.42.0.1 8765 abc123\n",NetworkUtils.pairingLine("10.42.0.1",8765,"abc123"))
    }

    @Test
    fun pairingRejectsUnusableAddressesAndBrokenConfigLines() {
        for(host in listOf("", "0.0.0.0", "127.0.0.1", "255.255.255.255", "192.168.999.1", "192.168.01.1", "phone.local", "::1")) {
            assertNull(NetworkUtils.pairingLine(host,8765,"token"))
        }
        assertNull(NetworkUtils.pairingLine("auto",0,"token"))
        assertNull(NetworkUtils.pairingLine("auto",65536,"token"))
        assertNull(NetworkUtils.pairingLine("auto",8765,""))
        assertNull(NetworkUtils.pairingLine("auto",8765,"token\nsecond-line"))
    }

    @Test
    fun cellularAndVpnAreNotSuggestedAsLanPeers() {
        for(name in listOf("rmnet_data0","v4-rmnet_data0","ccmni0","tun0","ipsec0"))assertTrue(NetworkUtils.isCellularOrVpn(name))
        for(name in listOf("wlan0","wlan1","ap0","swlan0","eth0"))assertFalse(NetworkUtils.isCellularOrVpn(name))
        for(name in listOf("ap0","swlan0","wlan1","bridge0"))assertTrue(NetworkUtils.isHotspotInterface(name))
    }

    @Test
    fun testPrivateIp() {
        assertTrue(NetworkUtils.isPrivateIp("127.0.0.1"))
        assertTrue(NetworkUtils.isPrivateIp("192.168.1.50"))
        assertTrue(NetworkUtils.isPrivateIp("10.0.2.15"))
        assertTrue(NetworkUtils.isPrivateIp("172.16.0.1"))
        assertTrue(NetworkUtils.isPrivateIp("169.254.10.20"))

        assertFalse(NetworkUtils.isPrivateIp("8.8.8.8"))
        assertFalse(NetworkUtils.isPrivateIp("1.1.1.1"))
        assertFalse(NetworkUtils.isPrivateIp("142.250.196.142"))
    }
}
