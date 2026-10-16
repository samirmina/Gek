import logging
import tkinter as tk
from tkinter import ttk
from typing import Optional
import threading

logger = logging.getLogger(__name__)


class HistoryWindow:
    def __init__(self, parent: tk.Tk, database, tts_engine):
        self.parent = parent
        self.database = database
        self.tts_engine = tts_engine
        self.window: Optional[tk.Toplevel] = None
        self.search_var = tk.StringVar()
        self.search_var.trace_add("write", self._on_search)
        self._build_window()

    def _build_window(self):
        logger.info("HistoryWindow._build_window: building history window")
        self.window = tk.Toplevel(self.parent)
        self.window.title("History")
        self.window.geometry("520x420")
        self.window.configure(bg="#1a1a2e")
        self.window.protocol("WM_DELETE_WINDOW", self._on_close)

        search_frame = ttk.Frame(self.window)
        search_frame.pack(fill=tk.X, padx=6, pady=(6, 0))

        ttk.Label(search_frame, text="Search:").pack(side=tk.LEFT)
        self.search_entry = ttk.Entry(search_frame, textvariable=self.search_var)
        self.search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(4, 0))
        self.search_entry.focus_set()

        container = ttk.Frame(self.window)
        container.pack(fill=tk.BOTH, expand=True, padx=6, pady=6)

        self.canvas = tk.Canvas(container, highlightthickness=0, bg="#1a1a2e")
        scrollbar = ttk.Scrollbar(container, orient=tk.VERTICAL, command=self.canvas.yview)
        self.scroll_frame = ttk.Frame(self.canvas)

        self.scroll_frame.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas_window = self.canvas.create_window((0, 0), window=self.scroll_frame, anchor="nw")
        self.canvas.configure(yscrollcommand=scrollbar.set)
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(self.canvas_window, width=e.width))

        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        self.canvas.bind("<MouseWheel>", self._on_mousewheel)
        self.scroll_frame.bind("<MouseWheel>", self._on_mousewheel)

        self._load_entries("")

    def _on_mousewheel(self, event):
        self.canvas.yview_scroll(int(-1 * (event.delta / 120)), "units")

    def _on_search(self, *_):
        query = self.search_var.get().strip()
        self._load_entries(query)

    def _load_entries(self, query: str):
        logger.debug(f"HistoryWindow._load_entries: query={query!r}")
        for widget in self.scroll_frame.winfo_children():
            widget.destroy()

        try:
            if query:
                entries = self.database.search(query)
            else:
                entries = self.database.get_recent(limit=200)
        except Exception as e:
            logger.warning("History search failed: %s", e)
            entries = []

        if not entries:
            ttk.Label(self.scroll_frame, text="No entries found.").pack(pady=10)
            return

        for entry in entries:
            self._add_entry_row(entry)

    def _add_entry_row(self, entry):
        row = ttk.Frame(self.scroll_frame)
        row.pack(fill=tk.X, padx=2, pady=3)

        text_frame = ttk.Frame(row)
        text_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        original = entry.get("original_text", "")
        translated = entry.get("translated_text", "")
        src_lang = entry.get("original_lang", "")
        tgt_lang = entry.get("translated_lang", "")

        lang_label = f"[{src_lang} -> {tgt_lang}] " if src_lang and tgt_lang else ""
        ttk.Label(text_frame, text=f"{lang_label}{original}", wraplength=350, anchor="w", justify="left").pack(fill=tk.X)
        ttk.Label(text_frame, text=f"  -> {translated}", wraplength=350, anchor="w", justify="left", foreground="blue").pack(fill=tk.X)

        play_btn = ttk.Button(row, text="Play", width=5, command=lambda e=entry: self._play(e))
        play_btn.pack(side=tk.RIGHT, padx=(4, 0))

    def _play(self, entry):
        logger.debug(f"HistoryWindow._play: entry_id={entry.get('id')}")
        text = entry.get("translated_text", "")
        lang_code = entry.get("translated_lang", "")
        if text and self.tts_engine:
            threading.Thread(
                target=self.tts_engine.speak,
                args=(text, lang_code),
                daemon=True,
            ).start()

    def _on_close(self):
        logger.debug("HistoryWindow._on_close: destroying window")
        if self.window:
            self.window.destroy()
            self.window = None

    def show(self):
        logger.debug(f"HistoryWindow.show: exists={self.window is not None}")
        if self.window:
            self.window.lift()
            self.window.focus_force()
        else:
            self._build_window()
