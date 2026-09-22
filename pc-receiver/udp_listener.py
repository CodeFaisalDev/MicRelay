"""
Dual UDP & TCP Socket Listener & Audio Pipeline Coordinator for MicRelay PC Receiver
Listens simultaneously on UDP (low-latency WiFi) and TCP (ADB USB port forwarding).
"""

import socket
import threading
import time
import numpy as np
from typing import Optional, Callable
from protocol import (
    unpack_packet, pack_packet,
    PAYLOAD_TYPE_PCM, PAYLOAD_TYPE_OPUS, PAYLOAD_TYPE_HEARTBEAT,
    PAYLOAD_TYPE_HANDSHAKE, PAYLOAD_TYPE_ACK, HEADER_SIZE
)
from jitter_buffer import JitterBuffer
from drift_compensator import ClockDriftCompensator
from audio_sink import AudioSink

class UdpListener:
    def __init__(self, port: int = 45454, audio_sink: Optional[AudioSink] = None):
        self.port = port
        self.audio_sink = audio_sink
        self.udp_sock: Optional[socket.socket] = None
        self.tcp_sock: Optional[socket.socket] = None
        self.is_running = False
        
        self.jitter_buffer = JitterBuffer(target_delay_ms=60.0)
        self.drift_compensator = ClockDriftCompensator()
        
        self.udp_thread: Optional[threading.Thread] = None
        self.tcp_thread: Optional[threading.Thread] = None
        self.playback_thread: Optional[threading.Thread] = None
        
        self.sender_address = None
        self.last_packet_time = 0.0
        self.packets_received = 0
        self.device_name = "Phone"
        
        # Statistics callback: cb(stats_dict)
        self.on_stats: Optional[Callable[[dict], None]] = None
        self.on_device_connected: Optional[Callable[[str], None]] = None

    def start(self):
        if self.is_running:
            return
            
        self.is_running = True
        
        # 1. Bind UDP Socket
        try:
            self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 256 * 1024)
            self.udp_sock.bind(("0.0.0.0", self.port))
            self.udp_sock.settimeout(0.5)
            self.udp_thread = threading.Thread(target=self._udp_receive_loop, daemon=True)
            self.udp_thread.start()
            print(f"[Network] UDP listening on 0.0.0.0:{self.port}")
        except Exception as e:
            print(f"[Network] Failed to bind UDP port {self.port}: {e}")

        # 2. Bind TCP Socket (for ADB USB forward and TCP streaming)
        try:
            self.tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.tcp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.tcp_sock.bind(("0.0.0.0", self.port))
            self.tcp_sock.listen(2)
            self.tcp_sock.settimeout(0.5)
            self.tcp_thread = threading.Thread(target=self._tcp_server_loop, daemon=True)
            self.tcp_thread.start()
            print(f"[Network] TCP listening on 0.0.0.0:{self.port}")
        except Exception as e:
            print(f"[Network] Failed to bind TCP port {self.port}: {e}")

        # 3. Audio Playback loop
        self.playback_thread = threading.Thread(target=self._playback_loop, daemon=True)
        self.playback_thread.start()

    def stop(self):
        self.is_running = False
        if self.udp_thread:
            self.udp_thread.join(timeout=0.5)
        if self.tcp_thread:
            self.tcp_thread.join(timeout=0.5)
        if self.playback_thread:
            self.playback_thread.join(timeout=0.5)
            
        if self.udp_sock:
            try:
                self.udp_sock.close()
            except Exception:
                pass
            self.udp_sock = None

        if self.tcp_sock:
            try:
                self.tcp_sock.close()
            except Exception:
                pass
            self.tcp_sock = None
        print("[Network] Stopped all listeners.")

    def _handle_packet(self, data: bytes, addr, sock_reply_fn=None):
        pkt = unpack_packet(data)
        if not pkt:
            return

        was_disconnected = (time.time() - self.last_packet_time > 4.0)
        self.sender_address = addr
        self.last_packet_time = time.time()

        if was_disconnected and self.on_device_connected:
            self.on_device_connected(f"{addr[0]}:{addr[1]}")

        # Handle Handshake Ping
        if pkt.payload_type == PAYLOAD_TYPE_HANDSHAKE:
            if sock_reply_fn:
                ack_packet = pack_packet(seq=pkt.seq, timestamp=pkt.timestamp, payload_type=PAYLOAD_TYPE_ACK, data=b"OK")
                sock_reply_fn(ack_packet)
            return

        if pkt.payload_type == PAYLOAD_TYPE_HEARTBEAT or pkt.payload_type == PAYLOAD_TYPE_ACK:
            return

        # Audio sample packet (direct routing into hardware-clocked AudioSink)
        if pkt.payload_type == PAYLOAD_TYPE_PCM:
            self.packets_received += 1
            if self.audio_sink and self.audio_sink.is_running:
                self.audio_sink.push_pcm_frame(pkt.data)

    def _udp_receive_loop(self):
        while self.is_running:
            try:
                data, addr = self.udp_sock.recvfrom(2048)
                self._handle_packet(
                    data, 
                    addr, 
                    sock_reply_fn=lambda ack: self.udp_sock.sendto(ack, addr)
                )
            except socket.timeout:
                continue
            except Exception as e:
                if self.is_running:
                    pass

    def _tcp_server_loop(self):
        while self.is_running:
            try:
                client_sock, client_addr = self.tcp_sock.accept()
                client_sock.settimeout(2.0)
                threading.Thread(target=self._tcp_client_handler, args=(client_sock, client_addr), daemon=True).start()
            except socket.timeout:
                continue
            except Exception:
                break

    def _tcp_client_handler(self, client_sock: socket.socket, client_addr):
        buffer = bytearray()
        try:
            while self.is_running:
                chunk = client_sock.recv(4096)
                if not chunk:
                    break
                buffer.extend(chunk)

                # Parse packets from stream
                while len(buffer) >= HEADER_SIZE:
                    length = int.from_bytes(buffer[16:18], byteorder='big')
                    total_len = HEADER_SIZE + length
                    if len(buffer) < total_len:
                        break  # Wait for complete packet

                    packet_data = bytes(buffer[:total_len])
                    del buffer[:total_len]

                    self._handle_packet(
                        packet_data,
                        client_addr,
                        sock_reply_fn=lambda ack: client_sock.sendall(ack)
                    )
        except Exception:
            pass
        finally:
            try:
                client_sock.close()
            except Exception:
                pass

    def get_stats(self) -> dict:
        is_conn = (time.time() - self.last_packet_time < 3.0) if self.last_packet_time > 0 else False
        buffered_frames = (len(self.audio_sink.buffer) // 480) if (self.audio_sink and self.audio_sink.is_running) else 0
        return {
            "buffered_frames": buffered_frames,
            "packets_received": self.packets_received,
            "packets_lost": 0,
            "loss_rate_pct": 0.0,
            "packets_late": 0,
            "connected": is_conn,
            "sender": f"{self.sender_address[0]}:{self.sender_address[1]}" if self.sender_address and is_conn else "Disconnected",
            "vu_db": self.audio_sink.current_peak_db if self.audio_sink and is_conn else -60.0
        }

    def _playback_loop(self):
        """Monitors telemetry and stream health at regular intervals."""
        while self.is_running:
            time.sleep(0.08)
            if self.on_stats:
                self.on_stats(self.get_stats())


