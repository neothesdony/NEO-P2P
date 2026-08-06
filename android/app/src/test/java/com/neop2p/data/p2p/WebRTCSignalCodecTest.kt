package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebRTCSignalCodecTest {

    @Test
    fun `round trips offer sdp`() {
        val sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val encoded = WebRTCSignalCodec.encodeOffer(sdp)
        val decoded = WebRTCSignalCodec.decodeOffer(encoded)
        assertEquals(sdp, decoded)
    }

    @Test
    fun `round trips answer sdp`() {
        val sdp = "v=0\r\no=- 456 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val encoded = WebRTCSignalCodec.encodeAnswer(sdp)
        val decoded = WebRTCSignalCodec.decodeAnswer(encoded)
        assertEquals(sdp, decoded)
    }

    @Test
    fun `round trips ice candidate`() {
        val candidate = "candidate:1 1 UDP 2122260223 192.168.1.5 54321 typ host"
        val encoded = WebRTCSignalCodec.encodeIceCandidate(candidate)
        val decoded = WebRTCSignalCodec.decodeIceCandidate(encoded)
        assertEquals(candidate, decoded)
    }

    @Test
    fun `round trips ice candidate with sdp mid and mline index`() {
        val candidate = "candidate:2 1 TCP 2105458943 10.0.0.7 9 typ host tcptype active"
        val encoded = WebRTCSignalCodec.encodeIceCandidate(candidate, sdpMid = "0", sdpMLineIndex = 0)
        val decoded = WebRTCSignalCodec.decodeIceCandidate(encoded)
        assertEquals(candidate, decoded)
    }

    @Test
    fun `decode rejects empty input`() {
        assertNull(WebRTCSignalCodec.decodeOffer(ByteArray(0)))
        assertNull(WebRTCSignalCodec.decodeAnswer(ByteArray(0)))
        assertNull(WebRTCSignalCodec.decodeIceCandidate(ByteArray(0)))
    }

    @Test
    fun `decode rejects truncated input`() {
        val encoded = WebRTCSignalCodec.encodeOffer("v=0\r\ns=-\r\n")
        assertNull(WebRTCSignalCodec.decodeOffer(encoded.copyOfRange(0, encoded.size - 3)))
    }

    @Test
    fun `decode rejects wrong message type`() {
        val offer = WebRTCSignalCodec.encodeOffer("v=0\r\ns=-\r\n")
        assertNull(WebRTCSignalCodec.decodeAnswer(offer))
        assertNull(WebRTCSignalCodec.decodeIceCandidate(offer))
    }
}
