"""
MicRelay Modern Desktop GUI (PC Receiver)
Built with CustomTkinter for a sleek, dark-mode native Windows interface.
Features:
- Smart IP Detection & Recommendation Banner (No more IP guessing!)
- 1-Click Copy IP & Dynamic Scannable QR Code
- Real-time WASAPI Audio Output to VB-Cable or Speakers
- Live VU Meter with Peak Hold and Overload Warning
- Built-in Studio Noise Gate (removes PC fans, AC hum, room hiss)
- Stream Telemetry & Connection Health Monitor
"""

import sys
import socket
import threading
import sounddevice as sd
import customtkinter as ctk
import qrcode
import numpy as np
from PIL import Image
from typing import Optional, List, Dict
from audio_sink import AudioSink
from udp_listener import UdpListener

def get_network_ips() -> List[str]:
    """Detects and returns all valid local IPv4 addresses, prioritizing the active LAN gateway IP."""
    ips = []
    # Method 1: Connect UDP socket to detect primary gateway route interface
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        gw_ip = s.getsockname()[0]
        s.close()
        if gw_ip and not gw_ip.startswith("127."):
            ips.append(gw_ip)
    except Exception:
        pass

    # Method 2: Enumerate all interface IPs
    try:
        host = socket.gethostname()
        for ip in socket.gethostbyname_ex(host)[2]:
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass

    if not ips:
        ips.append("127.0.0.1")
    return ips


class MicRelayApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("MicRelay — Studio PC Audio Receiver")
        self.geometry("680x910")
        self.resizable(False, False)
        ctk.set_appearance_mode("Dark")
        ctk.set_default_color_theme("blue")

        self.audio_sink = AudioSink()
        self.listener = UdpListener(port=45454, audio_sink=self.audio_sink)
        self.listener.on_stats = self._on_stats_received
        self.listener.on_device_connected = self._on_device_connected

        self.devices: List[Dict] = []
        self.local_ips = get_network_ips()
        self.primary_ip = self.local_ips[0] if self.local_ips else "127.0.0.1"

        self.qr_window: Optional[ctk.CTkToplevel] = None

        self._build_ui()
        self._populate_audio_devices()

    def _build_ui(self):
        # 1. Header Frame
        header = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        header.pack(fill="x", padx=20, pady=(16, 8))

        title_row = ctk.CTkFrame(header, fg_color="transparent")
        title_row.pack(fill="x", padx=16, pady=(14, 2))

        title = ctk.CTkLabel(
            title_row, 
            text="🎙️ MicRelay Studio Receiver", 
            font=ctk.CTkFont(size=20, weight="bold"),
            text_color="#f4f4f5"
        )
        title.pack(side="left")

        ver_badge = ctk.CTkLabel(
            title_row,
            text="v1.2.0 • 48kHz High-Def",
            font=ctk.CTkFont(size=11),
            text_color="#10b981",
            fg_color="#064e3b",
            corner_radius=6,
            padx=8,
            pady=2
        )
        ver_badge.pack(side="right")

        subtitle = ctk.CTkLabel(
            header,
            text="Ultra-low latency studio microphone relay into OBS, Discord, and Windows",
            font=ctk.CTkFont(size=12),
            text_color="#a1a1aa"
        )
        subtitle.pack(anchor="w", padx=16, pady=(0, 12))

        # 2. Smart IP & Auto-Connect Banner Card
        ip_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#0f172a", border_width=1, border_color="#1e3a8a")
        ip_frame.pack(fill="x", padx=20, pady=6)

        ip_top = ctk.CTkFrame(ip_frame, fg_color="transparent")
        ip_top.pack(fill="x", padx=16, pady=(12, 4))

        ip_title = ctk.CTkLabel(
            ip_top,
            text="📡 Connect Phone To This PC (Same Wi-Fi):",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#93c5fd"
        )
        ip_title.pack(side="left")

        ip_content = ctk.CTkFrame(ip_frame, fg_color="transparent")
        ip_content.pack(fill="x", padx=16, pady=(0, 12))

        # Primary IP badge
        self.ip_badge = ctk.CTkLabel(
            ip_content,
            text=f"🟢 Wi-Fi IP:  {self.primary_ip}",
            font=ctk.CTkFont(family="Consolas", size=15, weight="bold"),
            text_color="#f8fafc",
            fg_color="#1e293b",
            corner_radius=8,
            padx=14,
            pady=6
        )
        self.ip_badge.pack(side="left", padx=(0, 8))

        copy_ip_btn = ctk.CTkButton(
            ip_content,
            text="📋 Copy IP",
            width=90,
            height=32,
            fg_color="#2563eb",
            hover_color="#1d4ed8",
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._copy_ip
        )
        copy_ip_btn.pack(side="left", padx=4)

        qr_btn = ctk.CTkButton(
            ip_content,
            text="📷 Scan QR Code",
            width=120,
            height=32,
            fg_color="#475569",
            hover_color="#334155",
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._show_qr_code
        )
        qr_btn.pack(side="left", padx=4)

        # 3. Audio Output Device Section
        device_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        device_frame.pack(fill="x", padx=20, pady=6)

        device_lbl = ctk.CTkLabel(device_frame, text="Audio Output Device (Virtual Cable / Speakers):", font=ctk.CTkFont(size=13, weight="bold"))
        device_lbl.pack(anchor="w", padx=16, pady=(12, 4))

        self.device_combo = ctk.CTkComboBox(device_frame, values=["Scanning..."], width=600, command=self._on_device_changed)
        self.device_combo.pack(padx=16, pady=(0, 12))

        # 4. Connection & Port Section
        conn_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        conn_frame.pack(fill="x", padx=20, pady=6)

        port_row = ctk.CTkFrame(conn_frame, fg_color="transparent")
        port_row.pack(fill="x", padx=16, pady=(12, 6))

        port_lbl = ctk.CTkLabel(port_row, text="UDP/TCP Port:", font=ctk.CTkFont(size=13))
        port_lbl.pack(side="left")

        self.port_entry = ctk.CTkEntry(port_row, width=90)
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

        self.status_badge = ctk.CTkLabel(
            conn_frame,
            text="● Status: Idle (Not listening)",
            text_color="#9ca3af",
            font=ctk.CTkFont(size=12, weight="bold")
        )
        self.status_badge.pack(anchor="w", padx=16, pady=(0, 12))

        # 5. Studio Audio & Volume Controls
        dsp_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        dsp_frame.pack(fill="x", padx=20, pady=6)

        # 5a. Microphone Volume Boost (Preamp Gain)
        gain_top = ctk.CTkFrame(dsp_frame, fg_color="transparent")
        gain_top.pack(fill="x", padx=16, pady=(10, 2))

        gain_title = ctk.CTkLabel(
            gain_top,
            text="🔊 Microphone Volume Boost (Preamp Gain):",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#f8fafc"
        )
        gain_title.pack(side="left")

        gain_slider_row = ctk.CTkFrame(dsp_frame, fg_color="transparent")
        gain_slider_row.pack(fill="x", padx=16, pady=(0, 8))

        gain_desc_label = ctk.CTkLabel(gain_slider_row, text="Boost Level:", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        gain_desc_label.pack(side="left")

        self.gain_slider = ctk.CTkSlider(
            gain_slider_row,
            from_=1.0,
            to=8.0,
            number_of_steps=35,
            width=360,
            command=self._on_volume_gain_slider
        )
        self.gain_slider.set(2.0)
        self.gain_slider.pack(side="left", padx=12)

        self.gain_val_lbl = ctk.CTkLabel(
            gain_slider_row,
            text="2.0x (+6.0 dB)",
            font=ctk.CTkFont(family="Consolas", size=12, weight="bold"),
            text_color="#10b981"
        )
        self.gain_val_lbl.pack(side="left")

        # 5b. Minimal Noise Cancellation (Downward Expander) - ON by default
        dsp_top = ctk.CTkFrame(dsp_frame, fg_color="transparent")
        dsp_top.pack(fill="x", padx=16, pady=(6, 2))

        dsp_title = ctk.CTkLabel(
            dsp_top, 
            text="🎙️ Minimal Noise Cancellation (Cleans Fan & Room Hiss):", 
            font=ctk.CTkFont(size=13, weight="bold")
        )
        dsp_title.pack(side="left")

        self.noise_gate_switch = ctk.CTkSwitch(
            dsp_top, 
            text="Enabled", 
            onvalue=True, 
            offvalue=False,
            font=ctk.CTkFont(size=12, weight="bold"),
            command=self._on_noise_gate_toggle
        )
        self.noise_gate_switch.select()  # Enabled by default for clean WO Mic style speech
        self.noise_gate_switch.pack(side="right")

        slider_row = ctk.CTkFrame(dsp_frame, fg_color="transparent")
        slider_row.pack(fill="x", padx=16, pady=(0, 10))

        thresh_label = ctk.CTkLabel(slider_row, text="Expander Sensitivity:", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        thresh_label.pack(side="left")

        self.gate_slider = ctk.CTkSlider(
            slider_row,
            from_=-55.0,
            to=-30.0,
            number_of_steps=25,
            width=360,
            command=self._on_noise_gate_slider
        )
        self.gate_slider.set(-46.0)
        self.gate_slider.pack(side="left", padx=12)

        self.gate_val_lbl = ctk.CTkLabel(
            slider_row, 
            text="-46.0 dB", 
            font=ctk.CTkFont(family="Consolas", size=12, weight="bold"),
            text_color="#38bdf8"
        )
        self.gate_val_lbl.pack(side="left")

        # 6. Live VU Meter Frame
        vu_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        vu_frame.pack(fill="x", padx=20, pady=6)

        vu_lbl = ctk.CTkLabel(vu_frame, text="Live Microphone Level (VU Meter):", font=ctk.CTkFont(size=13, weight="bold"))
        vu_lbl.pack(anchor="w", padx=16, pady=(10, 4))

        self.vu_bar = ctk.CTkProgressBar(vu_frame, width=600, height=16)
        self.vu_bar.set(0.0)
        self.vu_bar.configure(progress_color="#10b981")
        self.vu_bar.pack(padx=16, pady=(0, 4))

        self.vu_text = ctk.CTkLabel(vu_frame, text="-60.0 dB", font=ctk.CTkFont(size=11), text_color="#71717a")
        self.vu_text.pack(anchor="e", padx=16, pady=(0, 8))

        # 7. Stream Telemetry Grid
        tele_frame = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        tele_frame.pack(fill="x", padx=20, pady=6)

        tele_title = ctk.CTkLabel(tele_frame, text="Stream Telemetry & Health:", font=ctk.CTkFont(size=13, weight="bold"))
        tele_title.pack(anchor="w", padx=16, pady=(10, 6))

        grid = ctk.CTkFrame(tele_frame, fg_color="transparent")
        grid.pack(fill="x", padx=16, pady=(0, 10))

        self.stat_sender = ctk.CTkLabel(grid, text="Sender: Disconnected", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_sender.grid(row=0, column=0, sticky="w", pady=2)

        self.stat_loss = ctk.CTkLabel(grid, text="Packet Loss: 0.0%", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_loss.grid(row=0, column=1, sticky="w", padx=40, pady=2)

        self.stat_buffer = ctk.CTkLabel(grid, text="Jitter Buffer: 0 frames", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_buffer.grid(row=1, column=0, sticky="w", pady=2)

        self.stat_pkts = ctk.CTkLabel(grid, text="Packets Rcvd: 0", font=ctk.CTkFont(size=12), text_color="#a1a1aa")
        self.stat_pkts.grid(row=1, column=1, sticky="w", padx=40, pady=2)

        # 8. Quick Tools Footer
        footer = ctk.CTkFrame(self, corner_radius=12, fg_color="#18181b")
        footer.pack(fill="x", padx=20, pady=6)

        btn_row = ctk.CTkFrame(footer, fg_color="transparent")
        btn_row.pack(fill="x", padx=12, pady=10)

        adb_btn = ctk.CTkButton(
            btn_row,
            text="⚡ Copy USB Forward",
            fg_color="#3b82f6",
            hover_color="#2563eb",
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._copy_adb_command
        )
        adb_btn.pack(side="left", fill="x", expand=True, padx=(0, 4))

        vbcable_btn = ctk.CTkButton(
            btn_row,
            text="🎙️ Install Virtual Mic",
            fg_color="#8b5cf6",
            hover_color="#7c3aed",
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._install_vbcable
        )
        vbcable_btn.pack(side="left", fill="x", expand=True, padx=4)

        firewall_btn = ctk.CTkButton(
            btn_row,
            text="🛡️ Open Firewall",
            fg_color="#f59e0b",
            hover_color="#d97706",
            font=ctk.CTkFont(size=11, weight="bold"),
            command=self._open_firewall
        )
        firewall_btn.pack(side="left", fill="x", expand=True, padx=(4, 0))

    def _copy_ip(self):
        self.clipboard_clear()
        self.clipboard_append(self.primary_ip)
        self.status_badge.configure(text=f"● Copied {self.primary_ip} to clipboard! Paste it on your phone.", text_color="#38bdf8")

    def _show_qr_code(self):
        """Displays a high-contrast QR code modal window for instant mobile connection."""
        if self.qr_window is not None and self.qr_window.winfo_exists():
            self.qr_window.lift()
            return

        port = self.port_entry.get().strip() or "45454"
        qr_payload = f"micrelay://{self.primary_ip}:{port}"

        qr = qrcode.QRCode(version=1, box_size=8, border=2)
        qr.add_data(qr_payload)
        qr.make(fit=True)
        img = qr.make_image(fill_color="white", back_color="#09090b").convert("RGB")

        self.qr_window = ctk.CTkToplevel(self)
        self.qr_window.title("MicRelay — Mobile Instant Connect QR")
        self.qr_window.geometry("380x460")
        self.qr_window.resizable(False, False)
        self.qr_window.attributes("-topmost", True)

        ctk_img = ctk.CTkImage(light_image=img, dark_image=img, size=(220, 220))

        lbl = ctk.CTkLabel(
            self.qr_window,
            text="📷 Scan with Phone Camera",
            font=ctk.CTkFont(size=18, weight="bold")
        )
        lbl.pack(pady=(20, 4))

        sub = ctk.CTkLabel(
            self.qr_window,
            text=f"PC: {socket.gethostname()} ({self.primary_ip}:{port})",
            font=ctk.CTkFont(size=12),
            text_color="#94a3b8"
        )
        sub.pack(pady=(0, 14))

        qr_label = ctk.CTkLabel(self.qr_window, image=ctk_img, text="")
        qr_label.pack(pady=4)

        info = ctk.CTkLabel(
            self.qr_window,
            text="Point your phone camera or use 'Auto-Detect'\nin the MicRelay Android app to connect instantly!",
            font=ctk.CTkFont(size=11),
            text_color="#a1a1aa",
            justify="center"
        )
        info.pack(pady=(14, 10))

    def _on_volume_gain_slider(self, val):
        gain = float(val)
        db = 20.0 * np.log10(gain) if gain > 0 else 0.0
        self.gain_val_lbl.configure(text=f"{gain:.1f}x ({db:+.1f} dB)")
        self.audio_sink.set_volume_gain(gain)

    def _on_noise_gate_toggle(self):
        enabled = bool(self.noise_gate_switch.get())
        thresh = float(self.gate_slider.get())
        self.audio_sink.set_noise_cancellation(enabled, thresh)

    def _on_noise_gate_slider(self, val):
        thresh = float(val)
        self.gate_val_lbl.configure(text=f"{thresh:.1f} dB")
        enabled = bool(self.noise_gate_switch.get())
        self.audio_sink.set_noise_cancellation(enabled, thresh)

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
            
            target_api = wasapi_index
            for idx, dev in enumerate(devs):
                if dev['max_output_channels'] > 0:
                    if target_api is not None and dev['hostapi'] != target_api:
                        continue
                    
                    name = dev['name'].strip()
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
                self.status_badge.configure(text=f"● Status: Listening on {self.primary_ip}:{port}", text_color="#10b981")
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
            self.status_badge.configure(text=f"● Status: Listening on {self.primary_ip}:{self.listener.port}", text_color="#3b82f6")
            
        self.stat_sender.configure(text=f"Sender: {stats.get('sender', 'None')}")
        self.stat_loss.configure(text=f"Packet Loss: {stats.get('loss_rate_pct', 0.0):.1f}%")
        self.stat_buffer.configure(text=f"Jitter Buffer: {stats.get('buffered_frames', 0)} frames")
        self.stat_pkts.configure(text=f"Packets Rcvd: {stats.get('packets_received', 0)}")
        
        # Update VU Meter
        db = stats.get("vu_db", -60.0)
        norm = max(0.0, min(1.0, (db + 60.0) / 60.0))
        self.vu_bar.set(norm)
        if norm > 0.85:
            self.vu_bar.configure(progress_color="#ef4444")
        elif norm > 0.65:
            self.vu_bar.configure(progress_color="#f59e0b")
        else:
            self.vu_bar.configure(progress_color="#10b981")
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
