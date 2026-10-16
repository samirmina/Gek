"""Main HUD overlay for the gek voice translation project."""

import customtkinter as ctk
import ctypes
from ctypes import wintypes
import tkinter as tk
import logging

from config import HUD_WIDTH as _HUD_WIDTH

logger = logging.getLogger(__name__)


class MONITORINFO(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("rcMonitor", wintypes.RECT),
        ("rcWork", wintypes.RECT),
        ("dwFlags", wintypes.DWORD),
    ]


user32 = ctypes.windll.user32

BASE_HEIGHT = 280
PANEL_TITLE_HEIGHT = 28
PANEL_LINE_HEIGHT = 24
PANEL_LINES = 10
PANEL_HEIGHT = PANEL_TITLE_HEIGHT + (PANEL_LINES * PANEL_LINE_HEIGHT) + 8
PANEL_PADDING = 4
SOURCE_BAR_HEIGHT = 28
TEXT_PANEL_HEIGHT = 226
PANEL_ROW_HEIGHT = PANEL_HEIGHT + PANEL_PADDING

LINE_COLORS = ["#e94560", "#3498db", "#2ecc71", "#f39c12", "#9b59b6", "#1abc9c"]
ACCENT_CHAR = "\u258e"
COLOR_FILTER_BAR_HEIGHT = 30


def get_monitor_from_point(x: int, y: int) -> dict:
    pt = wintypes.POINT(x, y)
    hmon = user32.MonitorFromPoint(pt, 2)
    mi = MONITORINFO()
    mi.cbSize = ctypes.sizeof(MONITORINFO)
    user32.GetMonitorInfoW(hmon, ctypes.byref(mi))
    return {
        "left": mi.rcMonitor.left,
        "top": mi.rcMonitor.top,
        "right": mi.rcMonitor.right,
        "bottom": mi.rcMonitor.bottom,
        "work_left": mi.rcWork.left,
        "work_top": mi.rcWork.top,
        "work_right": mi.rcWork.right,
        "work_bottom": mi.rcWork.bottom,
    }



class Hud(ctk.CTkToplevel):

    def __init__(self, master=None, languages=None, on_close=None):
        logger.info("Hud.__init__: begin")
        super().__init__(master)
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.configure(fg_color="#1a1a2e")
        self.resizable(True, True)
        self.minsize(420, 300)
        logger.debug("Hud.__init__: window created, overrideredirect=True, topmost=True")

        self._phrase_history = []
        self._phrase_id_counter = 0
        self._translations = {}
        self._whisper_language = None
        self._whisper_var = tk.StringVar(value="Detected")
        self._stt_ref = None
        self._on_close_callback = on_close
        self._dock_edge = "top"
        self._monitors = []
        self._languages = languages or []
        self._drag_data = {"x": 0, "y": 0}
        self._note_window = None
        self._monitor_enum_cb = None

        self._hear_langs = set()
        self._text_area = None
        self._text_panel_visible = True
        self._control_bar = None
        self._active_color_filter = None  # None = show all, 0-5 = show only that color
        self._filter_btns = []
        self._rainbow_btn = None
        self._language_panel_visible = False

        self._lang_display_to_code = {}
        self._lang_code_to_display = {}
        if languages:
            if isinstance(languages[0], (list, tuple)):
                for code, name in languages:
                    self._lang_display_to_code[name] = code
                    self._lang_code_to_display[code] = name
                self._lang_names = ["Detected"] + [name for _, name in languages]
                self._lang_codes = ["auto"] + [code for code, _ in languages]
            else:
                from config import SUPPORTED_LANGUAGES
                for code in languages:
                    name = SUPPORTED_LANGUAGES.get(code, code)
                    self._lang_display_to_code[name] = code
                    self._lang_code_to_display[code] = name
                self._lang_names = ["Detected"] + [SUPPORTED_LANGUAGES.get(c, c) for c in languages]
                self._lang_codes = ["auto"] + list(languages)
        else:
            self._lang_names = ["Detected"]
            self._lang_codes = ["auto"]

        self._hear_vars = {}
        self._hear_checks = {}
        self._hear_frame = None

        self._resize_data = {"x": 0, "y": 0, "w": 0, "h": 0}

        self._build_ui()
        self.after(200, self._init_positioning)
        logger.info("Hud.__init__: UI built, positioning scheduled")

    def _init_positioning(self):
        logger.debug("Hud._init_positioning: starting")
        try:
            self._detect_monitors()
            self._dock_to_edge()
            logger.debug("Hud._init_positioning: complete")
        except Exception as e:
            logger.error(f"HUD positioning error: {e}")

    def _detect_monitors(self):
        logger.debug("Hud._detect_monitors: enumerating monitors")
        self._monitors.clear()
        MONITORENUMPROC = ctypes.WINFUNCTYPE(
            ctypes.c_bool, wintypes.HMONITOR, wintypes.HDC,
            wintypes.LPARAM, wintypes.WPARAM
        )

        def _cb(hmon, _dc, _lp, _wp):
            mi = MONITORINFO()
            mi.cbSize = ctypes.sizeof(MONITORINFO)
            user32.GetMonitorInfoW(hmon, ctypes.byref(mi))
            self._monitors.append({
                "left": mi.rcMonitor.left,
                "top": mi.rcMonitor.top,
                "right": mi.rcMonitor.right,
                "bottom": mi.rcMonitor.bottom,
                "work_top": mi.rcWork.top,
                "work_bottom": mi.rcWork.bottom,
            })
            return True

        self._monitor_enum_cb = MONITORENUMPROC(_cb)
        try:
            user32.EnumDisplayMonitors(None, None, self._monitor_enum_cb, 0)
        except Exception as e:
            logger.debug("EnumDisplayMonitors fallback: %s", e)
            sw = user32.GetSystemMetrics(0)
            sh = user32.GetSystemMetrics(1)
            self._monitors.append({
                "left": 0, "top": 0, "right": sw,
                "bottom": sh, "work_top": 0, "work_bottom": sh,
            })

    def _dock_to_edge(self):
        logger.debug(f"Hud._dock_to_edge: edge={self._dock_edge}")
        try:
            x = self.winfo_pointerx()
            y = self.winfo_pointery()
            if x == 0 and y == 0:
                x, y = 100, 100
            mon = get_monitor_from_point(x, y)
        except Exception as e:
            logger.debug("Monitor detection fallback: %s", e)
            mon = {"left": 0, "top": 0, "right": 1920, "bottom": 1080,
                   "work_top": 0, "work_bottom": 1040}

        w = max(420, self.winfo_width() or _HUD_WIDTH)
        h = self._calc_height()
        edge = self._dock_edge

        if edge == "top":
            px = mon["left"] + (mon["right"] - mon["left"] - w) // 2
            py = mon["work_top"]
        elif edge == "bottom":
            px = mon["left"] + (mon["right"] - mon["left"] - w) // 2
            py = mon["work_bottom"] - h
        elif edge == "left":
            px = mon["left"]
            py = mon["work_top"]
        elif edge == "right":
            px = mon["right"] - w
            py = mon["work_top"]
        else:
            px = mon["left"] + 100
            py = mon["work_top"] + 100

        logger.debug(f"Hud._dock_to_edge: geometry={w}x{h}+{px}+{py}")
        self.geometry(f"{w}x{h}+{px}+{py}")

    def _calc_height(self):
        h = BASE_HEIGHT + COLOR_FILTER_BAR_HEIGHT
        if self._language_panel_visible:
            h += 140
        if not self._text_panel_visible:
            h -= TEXT_PANEL_HEIGHT
        logger.debug(f"Hud._calc_height: result={h}")
        return h

    def _recalc_height(self):
        h = self._calc_height()
        w = max(420, self.winfo_width() or _HUD_WIDTH)
        logger.debug(f"Hud._recalc_height: setting geometry={w}x{h}")
        self.geometry(f"{w}x{h}")

    def _build_ui(self):
        logger.info("Hud._build_ui: building UI")
        self._build_control_bar()
        self._build_source_bar()
        self._build_language_panel()
        self._build_color_filter_bar()
        self._build_text_area()
        self._enable_global_drag()
        self._add_resize_grip()
        logger.info("Hud._build_ui: UI build complete")

    def _build_control_bar(self):
        logger.debug("Hud._build_control_bar: building control bar")
        bar = ctk.CTkFrame(self, fg_color="#16213e", height=30, corner_radius=0)
        bar.pack(fill="x", side="top")
        bar.pack_propagate(False)
        self._control_bar = bar

        grip = ctk.CTkLabel(bar, text="\u2630", width=20, text_color="#a0a0b0", cursor="fleur")
        grip.pack(side="left", padx=(6, 2))
        grip.bind("<ButtonPress-1>", self._start_drag)
        grip.bind("<B1-Motion>", self._on_drag)

        self._src_toggle_btn = ctk.CTkButton(
            bar, text="Lang", width=28, height=22,
            fg_color="transparent", hover_color="#0f3460",
            text_color="#a0a0b0", font=("Consolas", 11),
            command=self._toggle_language_panel,
        )
        self._src_toggle_btn.pack(side="left", padx=(0, 2))

        self._status_dot = ctk.CTkLabel(bar, text="\u25cf", width=14, text_color="#2ecc71")
        self._status_dot.pack(side="left", padx=(0, 4))

        self._live_label = ctk.CTkLabel(
            bar, text="LIVE", text_color="#2ecc71",
            font=("Consolas", 11, "bold")
        )
        self._live_label.pack(side="left", padx=(0, 8))

        self._status_label = ctk.CTkLabel(
            bar, text="Ready", text_color="#a0a0b0",
            font=("Segoe UI", 10)
        )
        self._status_label.pack(side="left", padx=(0, 8))

        btn_style = self._btn_secondary()

        ctk.CTkButton(bar, text="Text", width=35,
                      command=self._toggle_text_panel, **btn_style).pack(side="right", padx=2)
        ctk.CTkButton(bar, text="Notes", width=50,
                      command=self._open_notes, **btn_style).pack(side="right", padx=2)
        ctk.CTkButton(bar, text="Clear", width=55,
                      command=self.clear_history, **btn_style).pack(side="right", padx=2)
        ctk.CTkButton(bar, text="Dock", width=45,
                      command=self._cycle_dock, **btn_style).pack(side="right", padx=2)

        ctk.CTkButton(bar, text="X", width=28, fg_color="#e74c3c",
                      hover_color="#c0392b",
                      command=self._on_close_hud).pack(side="right", padx=2)

    def _btn_secondary(self):
        return {
            "fg_color": "#0f3460",
            "hover_color": "#16213e",
            "text_color": "#a0a0b0",
            "corner_radius": 4,
            "height": 24,
            "font": ("Segoe UI", 10),
        }

    def _build_source_bar(self):
        logger.debug("Hud._build_source_bar: building source bar")
        bar = ctk.CTkFrame(self, fg_color="#16213e", height=SOURCE_BAR_HEIGHT, corner_radius=0)
        bar.pack(fill="x", side="top")
        bar.pack_propagate(False)
        self._source_bar = bar

        self._hear_frame = None

        ctk.CTkLabel(bar, text="STT:", text_color="#a0a0b0", font=("Segoe UI", 9)).pack(side="left", padx=(8, 2))
        self._whisper_combo = ctk.CTkComboBox(
            bar,
            width=120,
            values=[n for n in self._lang_names if n is not None],
            variable=self._whisper_var,
            state="readonly",
            command=self._on_whisper_lang_change,
        )
        self._whisper_combo.pack(side="left", padx=(0, 8))
        self._whisper_combo.set("Detected")

    def _build_language_panel(self):
        self._language_panel = ctk.CTkFrame(self, fg_color="#16213e")

        if hasattr(ctk, "CTkScrollableFrame"):
            self._hear_frame = ctk.CTkScrollableFrame(
                self._language_panel,
                width=max(280, self.winfo_width() or 420),
                height=140,
                corner_radius=0,
                fg_color="#16213e",
            )
        else:
            self._hear_frame = ctk.CTkFrame(self._language_panel, fg_color="#16213e")
        self._hear_frame.pack(fill="both", expand=True, padx=4, pady=4)

        self._hear_vars = {}
        self._hear_checks = {}
        for code, name in zip(self._lang_codes, self._lang_names):
            if code == "auto":
                continue
            var = tk.BooleanVar(value=False)
            self._hear_vars[code] = var
            chk = ctk.CTkCheckBox(
                self._hear_frame, text=name or "", variable=var,
                fg_color="#0f3460", hover_color="#16213e",
                text_color="#a0a0b0", font=("Segoe UI", 9),
                command=lambda c=code: self._on_hear_toggled(c),
            )
            chk.pack(anchor="w", padx=8, pady=2)
            self._hear_checks[code] = chk
        self._language_panel.pack_forget()

    def _toggle_language_panel(self):
        if not hasattr(self, "_language_panel") or self._language_panel is None:
            self._build_language_panel()
        assert self._language_panel is not None
        if self._language_panel_visible:
            self._language_panel.pack_forget()
            self._language_panel_visible = False
            self._src_toggle_btn.configure(text="Lang")
        else:
            self._language_panel.pack(fill="x", side="top", after=self._source_bar)
            self._language_panel_visible = True
            self._src_toggle_btn.configure(text="Lang ✓")
        self._recalc_height()

    def _on_hear_toggled(self, lang_code):
        is_checked = self._hear_vars.get(lang_code, tk.BooleanVar()).get()
        logger.info(f"Hud._on_hear_toggled: lang={lang_code!r} checked={is_checked}")
        if is_checked:
            self._hear_langs.add(lang_code)
        else:
            self._hear_langs.discard(lang_code)
        logger.debug(f"Hud._on_hear_toggled: hear_langs={sorted(self._hear_langs)}")

    def get_hear_languages(self):
        try:
            return [code for code, var in self._hear_vars.items() if var.get()]
        except Exception as e:
            logger.debug("get_hear_languages failed: %s", e)
            return []

    def _enable_global_drag(self):
        for widget in [self._control_bar, self._source_bar, self._filter_bar, self._text_area]:
            widget.bind("<ButtonPress-1>", self._start_drag, add="+")
            widget.bind("<B1-Motion>", self._on_drag, add="+")

    def _add_resize_grip(self):
        grip = ctk.CTkFrame(self, fg_color="#16213e", width=16, height=16, corner_radius=0)
        grip.place(relx=1.0, rely=1.0, anchor="se")
        grip.bind("<ButtonPress-1>", self._start_resize)
        grip.bind("<B1-Motion>", self._on_resize)
        grip.lift()
        self._resize_grip = grip

    def _build_color_filter_bar(self):
        bar = ctk.CTkFrame(self, fg_color="#16213e", height=COLOR_FILTER_BAR_HEIGHT, corner_radius=0)
        bar.pack(fill="x", side="top")
        bar.pack_propagate(False)
        self._filter_bar = bar

        self._rainbow_btn = ctk.CTkButton(
            bar, text="\U0001F308", width=28, height=22,
            fg_color="#0f3460", hover_color="#16213e",
            text_color="#ffffff", font=("Segoe UI", 12),
            command=self._show_all_colors,
        )
        self._rainbow_btn.pack(side="left", padx=(6, 4))

        self._filter_btns = []
        for i, color in enumerate(LINE_COLORS):
            btn = ctk.CTkButton(
                bar, text="", width=28, height=22,
                fg_color=color, hover_color=color,
                text_color="#ffffff", font=("Consolas", 11, "bold"),
                command=lambda ci=i: self._toggle_color_filter(ci),
            )
            btn.pack(side="left", padx=2)
            self._filter_btns.append(btn)

    def _toggle_color_filter(self, color_idx):
        logger.debug(f"Hud._toggle_color_filter: color_idx={color_idx}")
        if self._active_color_filter == color_idx:
            self._active_color_filter = None
        else:
            self._active_color_filter = color_idx
        self._update_filter_buttons()
        self._render_main_text()

    def _show_all_colors(self):
        logger.debug("Hud._show_all_colors: clearing filter")
        self._active_color_filter = None
        self._update_filter_buttons()
        self._render_main_text()

    def _update_filter_buttons(self):
        assert self._rainbow_btn is not None
        if self._active_color_filter is None:
            self._rainbow_btn.configure(fg_color="#e94560", text_color="#ffffff")
        else:
            self._rainbow_btn.configure(fg_color="#0f3460", text_color="#a0a0b0")
        for i, btn in enumerate(self._filter_btns):
            if self._active_color_filter == i:
                btn.configure(text="\u2713", text_color="#ffffff",
                              border_width=2, border_color="#ffffff")
            else:
                btn.configure(text="", text_color="#ffffff",
                              border_width=0)

    def _build_text_area(self):
        logger.debug("Hud._build_text_area: building text area")
        self._text_area = ctk.CTkFrame(self, fg_color="transparent")
        self._text_area.pack(fill="x", padx=8, pady=(4, 2))

        self._textbox = ctk.CTkTextbox(
            self._text_area, height=222, wrap="word", fg_color="#0f3460",
            text_color="#eaeaea", font=("Segoe UI", 15),
            state="normal", corner_radius=6,
        )
        self._textbox.pack(fill="x")
        self._configure_accent_tags(self._textbox)
        self._textbox.bind("<Key>", lambda e: "break")
        self._textbox.bind("<<Copy>>", self._on_copy_main_text, add="+")
        self._textbox._textbox.bind("<Control-c>", self._on_copy_main_text, add="+")
        self._textbox._textbox.bind("<Control-C>", self._on_copy_main_text, add="+")

    def _configure_accent_tags(self, textbox):
        tw = textbox._textbox
        for i, color in enumerate(LINE_COLORS):
            tw.tag_config(f"accent_{i}", foreground=color)

    def _on_copy_main_text(self, event=None):
        tw = self._textbox._textbox
        try:
            sel_start = tw.index("sel.first")
            sel_end = tw.index("sel.last")
        except Exception:
            return "break"
        result = ""
        pos = sel_start
        while True:
            ranges = tw.tag_nextrange(self._SOURCE_TAG, pos, sel_end)
            if not ranges:
                result += tw.get(pos, sel_end)
                break
            start, end = ranges
            result += tw.get(pos, start)
            pos = end
            if pos >= sel_end:
                break
        if result:
            self.clipboard_clear()
            self.clipboard_append(result)
        return "break"

    def _start_drag(self, event):
        self._drag_data["x"] = event.x_root - self.winfo_x()
        self._drag_data["y"] = event.y_root - self.winfo_y()

    def _on_drag(self, event):
        nx = event.x_root - self._drag_data["x"]
        ny = event.y_root - self._drag_data["y"]
        self.geometry(f"+{nx}+{ny}")

    def _start_resize(self, event):
        self._resize_data = {
            "x": event.x_root,
            "y": event.y_root,
            "w": self.winfo_width(),
            "h": self.winfo_height(),
        }

    def _on_resize(self, event):
        dx = event.x_root - self._resize_data["x"]
        dy = event.y_root - self._resize_data["y"]
        new_w = max(300, self._resize_data["w"] + dx)
        new_h = max(200, self._resize_data["h"] + dy)
        self.geometry(f"{new_w}x{new_h}")

    def _cycle_dock(self):
        logger.debug(f"Hud._cycle_dock: current edge={self._dock_edge}")
        edges = ["top", "right", "bottom", "left"]
        try:
            idx = edges.index(self._dock_edge)
        except ValueError:
            idx = 0
        self._dock_edge = edges[(idx + 1) % 4]
        logger.info(f"Hud._cycle_dock: new edge={self._dock_edge}")
        self._dock_to_edge()

    def _open_notes(self):
        from ui.note_window import NoteWindow
        logger.debug("Hud._open_notes: opening notes window")
        try:
            if self._note_window is None or not self._note_window.winfo_exists():
                self._note_window = NoteWindow(self)
            self._note_window.lift()
        except Exception as e:
            logger.warning("Failed to open notes window: %s", e)

    RTL_LANGS = {"ar"}
    _RTL_START = "\u202b"
    _RTL_END = "\u202c"
    _SOURCE_TAG = "source_meta"

    def _rtl_wrap(self, text, lang=""):
        if not text or lang not in self.RTL_LANGS:
            return text
        return f"{self._RTL_START}{text}{self._RTL_END}"

    def _on_close_hud(self):
        logger.info("Hud._on_close_hud: closing HUD")
        if self._on_close_callback:
            try:
                self._on_close_callback()
            except Exception as e:
                logger.debug(f"HUD close callback error: {e}")
        try:
            if self._note_window:
                self._note_window.destroy()
        except Exception as e:
            logger.debug("Note window cleanup: %s", e)
        self.destroy()

    def _toggle_text_panel(self):
        assert self._text_area is not None
        logger.debug(f"Hud._toggle_text_panel: was_visible={self._text_panel_visible}")
        if self._text_panel_visible:
            self._text_area.pack_forget()
            self._src_toggle_btn.configure(text="\u25B6")
        else:
            self._text_area.pack(fill="x", padx=8, pady=(4, 2), after=self._source_bar)
            self._src_toggle_btn.configure(text="\u25BC")
        self._text_panel_visible = not self._text_panel_visible
        self._recalc_height()
        logger.debug(f"Hud._toggle_text_panel: now_visible={self._text_panel_visible}")

    def update_text(self, text, source="", speaker="", detected_lang="", phrase_id=None, confidence=0.0):
        logger.debug(f"Hud.update_text: source={source!r} lang={detected_lang!r} conf={confidence:.2f} text={text[:60]!r}")
        def _do():
            try:
                if not self.winfo_exists():
                    logger.debug("Hud.update_text: window gone, dropping")
                    return
                if phrase_id is not None:
                    pid = phrase_id
                    self._phrase_id_counter = max(self._phrase_id_counter, pid)
                else:
                    self._phrase_id_counter += 1
                    pid = self._phrase_id_counter
                if self._hear_langs and detected_lang not in self._hear_langs:
                    logger.debug(f"Hud.update_text: lang {detected_lang!r} not in hear_langs {sorted(self._hear_langs)}, filtering")
                    return
                self._phrase_history.append((pid, text, source, speaker, detected_lang))
                if len(self._phrase_history) > PANEL_LINES:
                    self._phrase_history = self._phrase_history[-PANEL_LINES:]
                self._render_main_text()
                logger.debug(f"Hud.update_text: rendered phrase_id={pid}")
            except Exception as e:
                logger.debug(f"update_text error: {e}")
        self.after(0, _do)

    def update_translation(self, translations, phrase_id=None):
        def _do():
            try:
                if not self.winfo_exists():
                    return
                self._translations.update(translations)
            except Exception as e:
                logger.debug(f"update_translation error: {e}")
        self.after(0, _do)

    def next_phrase_id(self):
        self._phrase_id_counter += 1
        return self._phrase_id_counter

    def _render_main_text(self):
        self._textbox.configure(state="normal")
        self._textbox.delete("1.0", "end")
        tw = self._textbox._textbox
        for idx, (pid, phrase_text, phrase_source, phrase_speaker, phrase_lang) in enumerate(self._phrase_history):
            color_idx = pid % len(LINE_COLORS)
            if self._active_color_filter is not None and color_idx != self._active_color_filter:
                continue
            tag = f"accent_{color_idx}"
            tw.insert("end", ACCENT_CHAR, (tag,))
            if phrase_speaker:
                tw.insert("end", f" [{phrase_source} / {phrase_speaker}] ", (self._SOURCE_TAG,))
            elif phrase_source:
                tw.insert("end", f" [{phrase_source}] ", (self._SOURCE_TAG,))
            else:
                tw.insert("end", " ")
            display_phrase = self._rtl_wrap(phrase_text, phrase_lang)
            tw.insert("end", f"{display_phrase}\n")
        self._textbox.see("end")

    def update_status(self, text, color="#2ecc71"):
        logger.debug(f"Hud.update_status: text={text!r} color={color!r}")
        def _do():
            try:
                if not self.winfo_exists():
                    return
                if self._status_label:
                    self._status_label.configure(text=text, text_color=color)
            except Exception as e:
                logger.debug(f"update_status error: {e}")
        self.after(0, _do)

    def clear_history(self):
        logger.info("Hud.clear_history: clearing all text")
        def _do():
            try:
                if not self.winfo_exists():
                    return
                self._phrase_id_counter = 0
                self._phrase_history.clear()
                self._translations.clear()
                self._active_color_filter = None
                self._update_filter_buttons()
                self._textbox.configure(state="normal")
                self._textbox.delete("1.0", "end")
                logger.info("Hud.clear_history: main text cleared")
            except Exception as e:
                logger.debug(f"Clear history after destroy: {e}")
        self.after(0, _do)

    def get_whisper_language(self):
        try:
            if hasattr(self, '_whisper_var') and self._whisper_var:
                val = self._whisper_var.get()
                raw = self._lang_display_to_code.get(val, val)
                return None if raw == "auto" else raw
        except Exception as e:
            logger.debug("get_whisper_language failed: %s", e)
        return None

    def _on_whisper_lang_change(self, value):
        raw = self._lang_display_to_code.get(value, value)
        code = None if raw == "auto" else raw
        self._whisper_language = code
        if self._stt_ref is not None:
            try:
                self._stt_ref.set_language(code)
            except Exception as e:
                logger.debug("STT language set failed: %s", e)
