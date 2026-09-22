import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pc-receiver"))

import time
import socket
import numpy as np
from protocol import pack_packet, unpack_packet, PAYLOAD_TYPE_HANDSHAKE, PAYLOAD_TYPE_ACK, PAYLOAD_TYPE_PCM, HEADER_SIZE
from udp_listener import UdpListener

def test_tcp_handshake_and_audio():
    print("[TEST TCP] Starting UdpListener on port 45456...")
    connected_device = []
    def on_conn(addr):
        print(f"[TEST CALLBACK] TCP Device connected: {addr}")
        connected_device.append(addr)

    listener = UdpListener(port=45456, audio_sink=None)
    listener.on_device_connected = on_conn
    listener.start()
    time.sleep(0.1)

    client_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        # 1. Connect TCP
        client_sock.connect(("127.0.0.1", 45456))
        client_sock.settimeout(2.0)

        # 2. Test TCP Handshake Ping
        handshake_pkt = pack_packet(seq=1, timestamp=0, payload_type=PAYLOAD_TYPE_HANDSHAKE, data=b"")
        client_sock.sendall(handshake_pkt)

        reply = client_sock.recv(1024)
        reply_pkt = unpack_packet(reply)
        assert reply_pkt is not None, "Reply packet could not be unpacked"
        assert reply_pkt.payload_type == PAYLOAD_TYPE_ACK, f"Expected ACK, got {reply_pkt.payload_type}"
        print(f"[TEST SUCCESS] Received TCP ACK reply: {reply_pkt.data}")

        # 3. Test TCP Audio Packets
        fake_pcm = (np.sin(np.linspace(0, 2 * np.pi * 440 * 0.02, 960)) * 16000).astype(np.int16).tobytes()
        for i in range(2, 7):
            audio_pkt = pack_packet(seq=i, timestamp=i*960, payload_type=PAYLOAD_TYPE_PCM, data=fake_pcm)
            client_sock.sendall(audio_pkt)
            time.sleep(0.01)

        time.sleep(0.1)
        stats = listener.get_stats()
        print(f"[TEST STATS] Packets received: {stats['packets_received']}")

        assert stats["packets_received"] == 5, f"Expected 5 packets, got {stats['packets_received']}"
        assert len(connected_device) > 0, "on_device_connected was not invoked!"
        print(f"[TEST SUCCESS] TCP Device connected verified: {connected_device}")

    finally:
        client_sock.close()
        listener.stop()
        print("[TEST TCP] Test completed successfully.")

if __name__ == "__main__":
    test_tcp_handshake_and_audio()
