"""
MicRelay PC Receiver - Main Entrypoint
Supports both GUI and Headless CLI modes.
"""

import sys
import argparse
import time
from audio_sink import AudioSink
from udp_listener import UdpListener

def run_headless(port: int, device_idx: int):
    print(f"Starting MicRelay Receiver in headless mode on port {port}...")
    audio_sink = AudioSink(device_index=device_idx if device_idx >= 0 else None)
    audio_sink.start()
    
    listener = UdpListener(port=port, audio_sink=audio_sink)
    def stats_cb(s):
        print(f"\r[Recv] Pkts: {s.get('packets_received')} | Loss: {s.get('loss_rate_pct'):.1f}% | Buffer: {s.get('buffered_frames')} | VU: {s.get('vu_db'):.1f} dB    ", end="", flush=True)
    listener.on_stats = stats_cb
    listener.start()
    
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping...")
        listener.stop()
        audio_sink.stop()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MicRelay PC Receiver")
    parser.add_argument("--cli", action="store_true", help="Run in headless CLI mode instead of GUI")
    parser.add_argument("--port", type=int, default=45454, help="UDP listening port")
    parser.add_argument("--device", type=int, default=-1, help="Output audio device index")
    args = parser.parse_args()

    if args.cli:
        run_headless(args.port, args.device)
    else:
        from app_gui import MicRelayApp
        app = MicRelayApp()
        app.protocol("WM_DELETE_WINDOW", app.on_closing)
        app.mainloop()
