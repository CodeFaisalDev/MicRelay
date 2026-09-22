"""
MicRelay Modern Desktop GUI (PC Receiver)
Built with CustomTkinter for a sleek, dark-mode native Windows interface.
"""

import sys
import threading
import sounddevice as sd
import customtkinter as ctk
from typing import Optional, List, Dict
from audio_sink import AudioSink
from udp_listener import UdpListener

class MicRelayApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("MicRelay — PC Audio Receiver")
        self.geometry("640x720")
        self.resizable(False, False)
        ctk.set_appearance_mode("Dark")
        ctk.set_default_color_theme("blue")

        self.audio_sink = AudioSink()
        self.listener = UdpListener(port=45454, audio_sink=self.audio_sink)
        self.listener.on_stats = self._on_stats_received
        self.listener.on_device_connected = self._on_device_connected

        self.devices: List[Dict] = []
        self._build_ui()
        self._populate_audio_devices()

    def _build_ui(self):
        # Header Frame
        header = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        header.pack(fill="x", padx=20, pady=(20, 10))

        title = ctk.CTkLabel(
            header, 
            text="🎙️ MicRelay Receiver", 
            font=ctk.CTkFont(size=22, weight="bold"),
            text_color="#f4f4f5"
        )
        title.pack(anchor="w", padx=20, pady=(15, 2))

        subtitle = ctk.CTkLabel(
            header,
            text="Ultra-low latency phone microphone relay into OBS & Windows",
            font=ctk.CTkFont(size=12),
            text_color="#a1a1aa"
        )
        subtitle.pack(anchor="w", padx=20, pady=(0, 15))

        # Audio Output Section
        device_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        device_frame.pack(fill="x", padx=20, pady=10)

        device_lbl = ctk.CTkLabel(device_frame, text="Audio Output Device (Virtual Cable / Speakers):", font=ctk.CTkFont(size=13, weight="bold"))
        device_lbl.pack(anchor="w", padx=20, pady=(15, 5))

        self.device_combo = ctk.CTkComboBox(device_frame, values=["Scanning..."], width=560, command=self._on_device_changed)
        self.device_combo.pack(padx=20, pady=(0, 15))

        # Connection & Port Section
        conn_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        conn_frame.pack(fill="x", padx=20, pady=10)

        port_row = ctk.CTkFrame(conn_frame, fg_color="transparent")
        port_row.pack(fill="x", padx=20, pady=(15, 10))

        port_lbl = ctk.CTkLabel(port_row, text="UDP Port:", font=ctk.CTkFont(size=13))
        port_lbl.pack(side="left")

        self.port_entry = ctk.CTkEntry(port_row, width=100)
        self.port_entry.insert(0, "45454")
        self.port_entry.pack(side="left", padx=10)

        self.toggle_btn = ctk.CTkButton(
            port_row,
            text="Start Listening",
            fg_color="#10b981",
            hover_color="#059669",
            font=ctk.CTkFont(size=13, weight="bold"),
            command=self._toggle_listener
        )
        self.toggle_btn.pack(side="right")

        # Status badge
        self.status_badge = ctk.CTkLabel(
            conn_frame,
            text="● Status: Idle (Not listening)",
            text_color="#9ca3af",
            font=ctk.CTkFont(size=12, weight="bold")
        )
        self.status_badge.pack(anchor="w", padx=20, pady=(0, 15))

        # Live VU Meter Frame
        vu_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        vu_frame.pack(fill="x", padx=20, pady=10)

        vu_lbl = ctk.CTkLabel(vu_frame, text="Live Microphone Level (VU Meter):", font=ctk.CTkFont(size=13, weight="bold"))
        vu_lbl.pack(anchor="w", padx=20, pady=(15, 5))

        self.vu_bar = ctk.CTkProgressBar(vu_frame, width=560, height=18)
        self.vu_bar.set(0.0)
        self.vu_bar.configure(progress_color="#10b981")
        self.vu_bar.pack(padx=20, pady=(0, 5))

        self.vu_text = ctk.CTkLabel(vu_frame, text="-60.0 dB", font=ctk.CTkFont(size=11), text_color="#71717a")
        self.vu_text.pack(anchor="e", padx=20, pady=(0, 15))

        # Stream Telemetry Grid
        tele_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        tele_frame.pack(fill="x", padx=20, pady=10)

        tele_title = ctk.CTkLabel(tele_frame, text="Stream Telemetry & Health:", font=ctk.CTkFont(size=13, weight="bold"))
        tele_title.pack(anchor="w", padx=20, pady=(15, 10))

        grid = ctk.CTkFrame(tele_frame, fg_color="transparent")
        grid.pack(fill="x", padx=20, pady=(0, 15))

        self.stat_sender = ctk.CTkLabel(grid, text="Sender: Disconnected", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_sender.grid(row=0, column=0, sticky="w", pady=2)

        self.stat_loss = ctk.CTkLabel(grid, text="Packet Loss: 0.0%", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_loss.grid(row=0, column=1, sticky="w", padx=40, pady=2)

        self.stat_buffer = ctk.CTkLabel(grid, text="Jitter Buffer: 0 frames", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_buffer.grid(row=1, column=0, sticky="w", pady=2)

        self.stat_pkts = ctk.CTkLabel(grid, text="Packets Rcvd: 0", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_pkts.grid(row=1, column=1, sticky="w", padx=40, pady=2)

        # USB / Virtual Cable / Quick Guide Footer
        footer = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        footer.pack(fill="x", padx=20, pady=10)

        btn_row = ctk.CTkFrame(footer, fg_color="transparent")
        btn_row.pack(fill="x", padx=15, pady=12)

        adb_btn = ctk.CTkButton(
            btn_row,
            text="⚡ Copy USB ADB Forward",
            fg_color="#3b82f6",
            hover_color="#2563eb",
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._copy_adb_command
        )
        adb_btn.pack(side="left", fill="x", expand=True, padx=(0, 6))

        vbcable_btn = ctk.CTkButton(
            btn_row,
            text="🎙️ Install Virtual Mic",
            fg_color="#8b5cf6",
            hover_color="#7c3aed",
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._install_vbcable
        )
        vbcable_btn.pack(side="left", fill="x", expand=True, padx=6)

        firewall_btn = ctk.CTkButton(
            btn_row,
            text="🛡️ Open Firewall Port",
            fg_color="#f59e0b",
            hover_color="#d97706",
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._open_firewall
        )
        firewall_btn.pack(side="left", fill="x", expand=True, padx=(6, 0))


    def _populate_audio_devices(self):
        try:
            hostapis = sd.query_hostapis()
            wasapi_index = None
            for i, h in enumerate(hostapis):
                if 'wasapi' in h['name'].lower():
                    wasapi_index = i
                    break

            devs = sd.query_devices()
            output_devices = []
            values = []
            seen_names = set()
            
            # Prioritize WASAPI, fallback to all devices if WASAPI is unavailable
            target_api = wasapi_index
            for idx, dev in enumerate(devs):
                if dev['max_output_channels'] > 0:
                    if target_api is not None and dev['hostapi'] != target_api:
                        continue
                    
                    name = dev['name'].strip()
                    # Filter out duplicate virtual sound mappers
                    if any(bad in name.lower() for bad in ['sound mapper', 'primary sound driver', 'default directsound']):
                        continue
                    if name in seen_names:
                        continue
                    seen_names.add(name)

                    output_devices.append({"index": idx, "name": name})
                    values.append(name)
                    
            self.devices = output_devices
            if values:
                self.device_combo.configure(values=values)
                # Auto-select VB-Cable or default speaker
                cable_idx = next((i for i, d in enumerate(output_devices) if "cable" in d['name'].lower()), 0)
                self.device_combo.set(values[cable_idx])
                self.audio_sink.device_index = output_devices[cable_idx]["index"]
            else:
                self.device_combo.configure(values=["No Output Devices Found"])
        except Exception as e:
            print(f"Error querying audio devices: {e}")

    def _on_device_changed(self, choice):
        for dev in self.devices:
            if dev["name"] == choice:
                self.audio_sink.set_device(dev["index"])
                break

    def _toggle_listener(self):
        if not self.listener.is_running:
            try:
                port = int(self.port_entry.get().strip())
                self.listener.port = port
                self.audio_sink.start()
                self.listener.start()
                self.toggle_btn.configure(text="Stop Listening", fg_color="#ef4444", hover_color="#dc2626")
                self.status_badge.configure(text="● Status: Listening on UDP " + str(port), text_color="#10b981")
            except Exception as e:
                self.status_badge.configure(text=f"● Error: {e}", text_color="#ef4444")
        else:
            self.listener.stop()
            self.audio_sink.stop()
            self.toggle_btn.configure(text="Start Listening", fg_color="#10b981", hover_color="#059669")
            self.status_badge.configure(text="● Status: Stopped", text_color="#9ca3af")
            self.vu_bar.set(0.0)
            self.vu_text.configure(text="-60.0 dB")

    def _on_device_connected(self, addr: str):
        self.after(0, lambda: self._show_connection_notification(addr))

    def _show_connection_notification(self, addr: str):
        self.status_badge.configure(text=f"🟢 Phone Connected! ({addr})", text_color="#10b981")
        print(f"\n[MicRelay] >>> Phone Connected from {addr} <<<")

    def _on_stats_received(self, stats: dict):
        self.after(0, lambda: self._update_ui_stats(stats))

    def _update_ui_stats(self, stats: dict):
        connected = stats.get("connected", False)
        if connected:
            self.status_badge.configure(text=f"🟢 Connected: {stats.get('sender')}", text_color="#10b981")
        elif self.listener.is_running:
            self.status_badge.configure(text=f"● Status: Listening on UDP/TCP {self.listener.port}", text_color="#3b82f6")
            
        self.stat_sender.configure(text=f"Sender: {stats.get('sender', 'None')}")
        self.stat_loss.configure(text=f"Packet Loss: {stats.get('loss_rate_pct', 0.0):.1f}%")
        self.stat_buffer.configure(text=f"Jitter Buffer: {stats.get('buffered_frames', 0)} frames")
        self.stat_pkts.configure(text=f"Packets Rcvd: {stats.get('packets_received', 0)}")
        
        # Update VU Meter
        db = stats.get("vu_db", -60.0)
        norm = max(0.0, min(1.0, (db + 60.0) / 60.0))
        self.vu_bar.set(norm)
        if norm > 0.85:
            self.vu_bar.configure(progress_color="#ef4444")  # Red peak
        elif norm > 0.65:
            self.vu_bar.configure(progress_color="#f59e0b")  # Yellow
        else:
            self.vu_bar.configure(progress_color="#10b981")  # Green
        self.vu_text.configure(text=f"{db:.1f} dB")

    def _copy_adb_command(self):
        cmd = "adb forward tcp:45454 tcp:45454"
        self.clipboard_clear()
        self.clipboard_append(cmd)
        self.status_badge.configure(text="● Copied ADB command to clipboard!", text_color="#38bdf8")

    def _install_vbcable(self):
        import subprocess
        import os
        setup_bat = os.path.join(os.path.dirname(__file__), "..", "install_virtual_mic_driver.bat")
        if os.path.exists(setup_bat):
            subprocess.Popen([setup_bat], shell=True)
            self.status_badge.configure(text="● Launched VB-Audio Cable Installer!", text_color="#a855f7")
        else:
            self.status_badge.configure(text="● Error: Installer script not found", text_color="#ef4444")

    def _open_firewall(self):
        import subprocess
        import os
        fw_bat = os.path.join(os.path.dirname(__file__), "..", "allow_firewall.bat")
        if os.path.exists(fw_bat):
            subprocess.Popen([fw_bat], shell=True)
            self.status_badge.configure(text="● Requested Firewall Open on Port 45454!", text_color="#f59e0b")
        else:
            self.status_badge.configure(text="● Error: Firewall script not found", text_color="#ef4444")

    def on_closing(self):
        self.listener.stop()
        self.audio_sink.stop()
        self.destroy()

if __name__ == "__main__":
    app = MicRelayApp()
    app.protocol("WM_DELETE_WINDOW", app.on_closing)
    app.mainloop()
