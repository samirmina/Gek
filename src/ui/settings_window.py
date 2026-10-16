import logging
import tkinter as tk
from tkinter import ttk
from typing import Optional, Callable

logger = logging.getLogger(__name__)


class SettingsWindow:
    def __init__(self, parent: tk.Tk, settings: dict, on_apply: Optional[Callable] = None):
        self.parent = parent
        self.settings = dict(settings)
        self.on_apply = on_apply
        self.window: Optional[tk.Toplevel] = None
        self._build_window()

    def _build_window(self):
        logger.info("SettingsWindow._build_window: building settings window")
        self.window = tk.Toplevel(self.parent)
        self.window.title("Settings")
        self.window.geometry("400x720")
        self.window.resizable(True, True)
        self.window.minsize(400, 520)
        self.window.protocol("WM_DELETE_WINDOW", self._on_close)

        main = ttk.Frame(self.window, padding=10)
        main.pack(fill=tk.BOTH, expand=True)

        ttk.Label(main, text="Preset:").pack(anchor="w")
        self.preset_var = tk.StringVar(value=self.settings.get("preset", "Balanced"))
        preset_combo = ttk.Combobox(main, textvariable=self.preset_var,
                                    values=["Performance", "Balanced", "Quality"],
                                    state="readonly")
        preset_combo.pack(fill=tk.X, pady=(0, 8))
        preset_combo.bind("<<ComboboxSelected>>", self._on_preset_selected)

        ttk.Label(main, text="Whisper Model:").pack(anchor="w")
        self.model_var = tk.StringVar(value=self.settings.get("whisper_model", "base"))
        model_combo = ttk.Combobox(main, textvariable=self.model_var,
                                    values=["tiny", "base", "small", "medium", "large-v3"],
                                    state="readonly")
        model_combo.pack(fill=tk.X, pady=(0, 8))

        ttk.Label(main, text="Device:").pack(anchor="w")
        self.device_var = tk.StringVar(value=self.settings.get("whisper_device", "cpu"))
        device_combo = ttk.Combobox(main, textvariable=self.device_var,
                                    values=["cpu", "cuda"],
                                    state="readonly")
        device_combo.pack(fill=tk.X, pady=(0, 8))

        vram_frame = ttk.Frame(main)
        vram_frame.pack(fill=tk.X, pady=(0, 8))
        self._vram_label = ttk.Label(vram_frame, text="")
        self._vram_label.pack(anchor="w")
        self._update_vram_label()

        ttk.Label(main, text="Loopback (System Audio):").pack(anchor="w")
        self.loopback_var = tk.StringVar(value=self.settings.get("audio_loopback", ""))
        self.loopback_combo = ttk.Combobox(main, textvariable=self.loopback_var,
                                            state="readonly")
        self.loopback_combo.pack(fill=tk.X, pady=(0, 8))

        ttk.Label(main, text="Microphone:").pack(anchor="w")
        self.mic_var = tk.StringVar(value=self.settings.get("audio_mic", ""))
        self.mic_combo = ttk.Combobox(main, textvariable=self.mic_var,
                                       state="readonly")
        self.mic_combo.pack(fill=tk.X, pady=(0, 8))

        self._populate_audio_devices()

        ttk.Label(main, text="TTS Speed:").pack(anchor="w")
        self.speed_var = tk.DoubleVar(value=self.settings.get("tts_speed", 1.0))
        speed_frame = ttk.Frame(main)
        speed_frame.pack(fill=tk.X, pady=(0, 8))
        self.speed_label = ttk.Label(speed_frame, text=f"{self.speed_var.get():.1f}x")
        self.speed_label.pack(side=tk.RIGHT)
        speed_scale = ttk.Scale(speed_frame, from_=0.5, to=2.0, variable=self.speed_var,
                                 command=self._on_speed_change)
        speed_scale.pack(side=tk.LEFT, fill=tk.X, expand=True)

        ttk.Label(main, text="HUD Opacity:").pack(anchor="w")
        self.opacity_var = tk.DoubleVar(value=self.settings.get("hud_opacity", 0.85))
        opacity_frame = ttk.Frame(main)
        opacity_frame.pack(fill=tk.X, pady=(0, 8))
        self.opacity_label = ttk.Label(opacity_frame, text=f"{self.opacity_var.get():.0%}")
        self.opacity_label.pack(side=tk.RIGHT)
        opacity_scale = ttk.Scale(opacity_frame, from_=0.3, to=1.0, variable=self.opacity_var,
                                   command=self._on_opacity_change)
        opacity_scale.pack(side=tk.LEFT, fill=tk.X, expand=True)

        toggles_frame = ttk.Frame(main)
        toggles_frame.pack(fill=tk.X, pady=(0, 8))
        self.stt_enabled_var = tk.BooleanVar(value=bool(self.settings.get("stt_enabled", True)))
        self.tts_enabled_var = tk.BooleanVar(value=bool(self.settings.get("tts_enabled", True)))
        self.auto_mute_var = tk.BooleanVar(value=bool(self.settings.get("auto_mute_during_tts", True)))
        ttk.Checkbutton(toggles_frame, text="Enable STT", variable=self.stt_enabled_var).pack(anchor="w", pady=2)
        ttk.Checkbutton(toggles_frame, text="Enable TTS", variable=self.tts_enabled_var).pack(anchor="w", pady=2)
        ttk.Checkbutton(toggles_frame, text="Auto-mute STT during TTS", variable=self.auto_mute_var).pack(anchor="w", pady=2)

        btn_frame = ttk.Frame(main)
        btn_frame.pack(fill=tk.X, pady=(12, 0))
        ttk.Button(btn_frame, text="Apply", command=self._apply).pack(side=tk.RIGHT, padx=(4, 0))
        ttk.Button(btn_frame, text="Cancel", command=self._on_close).pack(side=tk.RIGHT)

    def _on_speed_change(self, val):
        self.speed_label.config(text=f"{self.speed_var.get():.1f}x")

    def _on_opacity_change(self, val):
        self.opacity_label.config(text=f"{self.opacity_var.get():.0%}")

    def _populate_audio_devices(self):
        logger.debug("SettingsWindow._populate_audio_devices: enumerating audio devices")
        try:
            import soundcard
            all_mics = soundcard.all_microphones(include_loopback=True)
            loopbacks = [m for m in all_mics if m.isloopback]
            mics = [m for m in all_mics if not m.isloopback]

            self.loopback_combo.configure(values=[m.name for m in loopbacks])
            self.mic_combo.configure(values=[m.name for m in mics])

            if not self.loopback_var.get() and loopbacks:
                self.loopback_var.set(loopbacks[0].name)
            if not self.mic_var.get() and mics:
                self.mic_var.set(mics[0].name)
        except Exception as e:
            self.loopback_combo.configure(values=["[error loading devices]"])
            self.mic_combo.configure(values=["[error loading devices]"])

    def _on_preset_selected(self, event):
        preset_name = self.preset_var.get()
        from config import PRESETS
        preset = PRESETS.get(preset_name, {})
        if preset:
            self.model_var.set(preset.get("whisper_model", self.model_var.get()))
            self.device_var.set(preset.get("whisper_device", self.device_var.get()))

    def _apply(self):
        logger.info(f"SettingsWindow._apply: preset={self.preset_var.get()} model={self.model_var.get()} device={self.device_var.get()}")
        self.settings["preset"] = self.preset_var.get()
        self.settings["whisper_model"] = self.model_var.get()
        self.settings["whisper_device"] = self.device_var.get()
        self.settings["tts_speed"] = self.speed_var.get()
        self.settings["hud_opacity"] = self.opacity_var.get()
        self.settings["audio_loopback"] = self.loopback_var.get()
        self.settings["audio_mic"] = self.mic_var.get()
        self.settings["stt_enabled"] = bool(self.stt_enabled_var.get())
        self.settings["tts_enabled"] = bool(self.tts_enabled_var.get())
        self.settings["auto_mute_during_tts"] = bool(self.auto_mute_var.get())
        if self.on_apply:
            self.on_apply(self.settings)
        self._on_close()

    def _on_close(self):
        logger.debug("SettingsWindow._on_close: destroying window")
        if self.window:
            self.window.destroy()
            self.window = None

    def show(self):
        logger.debug(f"SettingsWindow.show: exists={self.window is not None}")
        if self.window:
            self._update_vram_label()
            self.window.lift()
            self.window.focus_force()
        else:
            self._build_window()

    def _update_vram_label(self):
        try:
            import torch
            if torch.cuda.is_available():
                total = torch.cuda.get_device_properties(0).total_memory / 1024**2
                free, _ = torch.cuda.mem_get_info()
                free_mb = free / 1024**2
                used_mb = total - free_mb
                text = f"GPU VRAM: {used_mb:.0f} / {total:.0f} MB used ({free_mb:.0f} MB free)"
            else:
                text = "GPU: Not available (CPU mode)"
        except Exception:
            text = "GPU: Unknown"
        if hasattr(self, '_vram_label') and self._vram_label:
            self._vram_label.configure(text=text)
