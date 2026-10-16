import logging
import time
import threading
import os

logger = logging.getLogger(__name__)


def enumerate_audio_sessions_pycaw():
    """
    Use Windows WASAPI (pycaw) to find processes with active audio sessions.
    Only returns processes that are actually producing audio (volume > 0).
    Excludes the current process to avoid detecting GEK itself.
    """
    try:
        import os
        from pycaw.pycaw import AudioUtilities

        current_pid = os.getpid()
        sessions = AudioUtilities.GetAllSessions()
        active = set()
        for session in sessions:
            proc = session.Process
            if proc is None:
                continue
            if proc.pid == current_pid:
                continue
            try:
                vol = session.SimpleAudioVolume
                if vol.GetMasterVolume() > 0:
                    name = proc.name()
                    if name:
                        active.add(name.lower())
            except Exception:
                pass
        return list(active)
    except Exception as e:
        logger.debug(f"pycaw session enumeration failed: {e}")
        return []


def enumerate_audio_processes_poll():
    """
    Fallback: find processes with known audio-related names via Get-Process.
    Used when pycaw is unavailable or fails.
    """
    audio_process_names = {
        "chrome.exe", "msedge.exe", "firefox.exe",
        "mpc-hc64.exe", "mpc-hc.exe", "vlc.exe",
        "spotify.exe", "discord.exe", "teams.exe",
        "zoom.exe", "slack.exe", "telegram.exe",
        "steam.exe", "obs.exe", "obs64.exe",
        "audacity.exe", "foobar2000.exe", "aimp.exe",
        "potplayer.exe", "potplayermini.exe",
        "wmplayer.exe", "winamp.exe",
    }
    try:
        import subprocess
        result = subprocess.run(
            ["powershell", "-Command",
             "Get-Process | Select-Object -Property Name | ForEach-Object { $_.Name }"],
            capture_output=True, text=True, timeout=3, creationflags=0x08000000,
        )
        if result.returncode == 0:
            names = [line.strip().lower() + ".exe" for line in result.stdout.strip().split("\n") if line.strip()]
            return [n for n in names if n in audio_process_names]
    except Exception as e:
        logger.debug(f"Poll enumeration failed: {e}")
    return []


def get_active_audio_processes():
    """
    Primary: WASAPI via pycaw for processes actually producing audio.
    Fallback: Get-Process name matching if pycaw fails.
    """
    procs = enumerate_audio_sessions_pycaw()
    if procs:
        return procs
    return enumerate_audio_processes_poll()


def _enumerate_audio_sessions_with_pids():
    try:
        from pycaw.pycaw import AudioUtilities
        current_pid = os.getpid()
        sessions = AudioUtilities.GetAllSessions()
        infos = []
        for session in sessions:
            proc = session.Process
            if proc is None:
                continue
            if proc.pid == current_pid:
                continue
            try:
                vol = session.SimpleAudioVolume
                if vol.GetMasterVolume() > 0:
                    name = proc.name()
                    if name:
                        infos.append({"name": name.lower(), "pid": proc.pid})
            except Exception:
                pass
        return infos
    except Exception as e:
        logger.debug(f"pycaw session enumeration with pids failed: {e}")
        return []


class AudioProcessDetector:
    """
    Polls for active audio processes.
    Returns process names that are actually playing audio.
    """
    def __init__(self, poll_interval=2.0, on_processes=None):
        self.poll_interval = poll_interval
        self.on_processes = on_processes
        self._running = False
        self._thread = None
        self._current_processes = []
        self._current_pids = []

    @property
    def current_processes(self):
        return list(self._current_processes)

    @property
    def current_pids(self):
        return list(self._current_pids)

    def start(self):
        logger.info("AudioProcessDetector.start: starting poll thread")
        self._running = True
        self._thread = threading.Thread(target=self._poll_loop, daemon=True, name="process-detector")
        self._thread.start()
        logger.info("Audio process detector started")

    def _poll_loop(self):
        logger.debug("AudioProcessDetector._poll_loop: started")
        while self._running:
            try:
                infos = _enumerate_audio_sessions_with_pids()
                procs = [info["name"] for info in infos]
                pids = [info["pid"] for info in infos]
                if set(procs) != set(self._current_processes):
                    logger.debug(f"AudioProcessDetector._poll_loop: processes changed {self._current_processes} -> {procs}")
                    self._current_processes = procs
                    self._current_pids = pids
                if self.on_processes and self._running:
                    try:
                        self.on_processes(procs)
                    except Exception as e:
                        logger.error(f"Process callback error: {e}")
            except Exception as e:
                logger.error(f"Process poll error: {e}")
            time.sleep(self.poll_interval)
        logger.debug("AudioProcessDetector._poll_loop: exited")

    def stop(self):
        logger.info("AudioProcessDetector.stop: stopping")
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)
            self._thread = None
        self._current_processes.clear()
        self._current_pids.clear()
        logger.info("Audio process detector stopped")

    def get_pid_for_source(self, source_type):
        if source_type != "system" or not self._current_pids:
            return None
        return self._current_pids[0]

    def get_label_for_source(self, source_type):
        logger.debug(f"AudioProcessDetector.get_label_for_source: source={source_type!r}")
        if source_type == "mic":
            return "mic"
        procs = self._current_processes
        if not procs:
            return "system"
        if len(procs) == 1:
            return procs[0].replace(".exe", "")
        return procs[0].replace(".exe", "") + f"+{len(procs)-1}"
