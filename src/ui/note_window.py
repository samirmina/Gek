"""Simple note-taking window with crash guard."""

import logging
import os
import tempfile

import customtkinter as ctk
from pathlib import Path

logger = logging.getLogger(__name__)

NOTES_FILE = Path(__file__).resolve().parent.parent / "resources" / "notes.txt"


class NoteWindow(ctk.CTkToplevel):

    def __init__(self, master=None):
        super().__init__(master)
        self.title("Notes")
        self.geometry("400x350")
        self.configure(fg_color="#1a1a2e")
        self.protocol("WM_DELETE_WINDOW", self._on_close)

        self._text_area = ctk.CTkTextbox(
            self, fg_color="#0f3460", text_color="#eaeaea",
            font=("Consolas", 12), wrap="word", corner_radius=6,
        )
        self._text_area.pack(fill="both", expand=True, padx=8, pady=(8, 4))

        btn_frame = ctk.CTkFrame(self, fg_color="transparent")
        btn_frame.pack(fill="x", padx=8, pady=(0, 8))

        ctk.CTkButton(
            btn_frame, text="Save", width=80,
            fg_color="#2ecc71", hover_color="#27ae60",
            command=self._save,
        ).pack(side="left", padx=4)

        ctk.CTkButton(
            btn_frame, text="Clear", width=80,
            fg_color="#e74c3c", hover_color="#c0392b",
            command=self._clear,
        ).pack(side="left", padx=4)

        self._load_existing()

    def _load_existing(self):
        logger.debug("NoteWindow._load_existing: loading notes file")
        try:
            if NOTES_FILE.exists():
                content = NOTES_FILE.read_text(encoding="utf-8")
                self._text_area.insert("1.0", content)
        except Exception as e:
            logger.debug(f"Notes load error: {e}")

    def insert_text(self, text):
        try:
            self._text_area.insert("end", text + "\n")
        except Exception as e:
            logger.debug(f"Notes insert error: {e}")

    def _save(self):
        logger.debug("NoteWindow._save: saving notes")
        try:
            content = self._text_area.get("1.0", "end-1c")
            fd, tmp_path = tempfile.mkstemp(dir=str(NOTES_FILE.parent), suffix=".tmp")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    f.write(content)
                os.replace(tmp_path, str(NOTES_FILE))
                logger.debug("NoteWindow._save: saved successfully")
            except Exception as write_err:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass
                logger.debug("Notes write failed: %s", write_err)
                raise
        except Exception as e:
            logger.error(f"Notes save error: {e}")

    def _clear(self):
        logger.debug("NoteWindow._clear: clearing notes")
        try:
            self._text_area.delete("1.0", "end")
        except Exception as e:
            logger.debug(f"Notes clear error: {e}")

    def _on_close(self):
        logger.debug("NoteWindow._on_close: auto-saving and closing")
        try:
            self._save()
        except Exception as e:
            logger.debug(f"Notes auto-save error: {e}")
        self.destroy()
