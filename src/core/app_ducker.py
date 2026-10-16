"""Per-app audio ducking using Windows Core Audio API (pycaw)."""

import logging
import threading

logger = logging.getLogger(__name__)


class AppDucker:
    """Duck and restore individual app audio volumes."""

    def __init__(self, duck_level=0.2):
        self._duck_level = duck_level
        self._original_volumes = {}
        self._lock = threading.Lock()

    def _get_sessions(self):
        from pycaw.pycaw import AudioUtilities
        return AudioUtilities.GetAllSessions()

    def _find_session(self, process_name):
        name_lower = process_name.lower()
        if not name_lower.endswith(".exe"):
            name_lower += ".exe"
        for session in self._get_sessions():
            if session.Process and session.Process.name().lower() == name_lower:
                return session
        return None

    def _find_session_by_pid(self, pid):
        for session in self._get_sessions():
            try:
                if session.Process and session.Process.pid == pid:
                    return session
            except Exception:
                pass
        return None

    def duck(self, process_name=None, pid=None):
        logger.debug(f"AppDucker.duck: process={process_name!r} pid={pid}")
        with self._lock:
            try:
                session = None
                if pid is not None:
                    session = self._find_session_by_pid(pid)
                elif process_name is not None:
                    session = self._find_session(process_name)
                if not session:
                    logger.debug(f"AppDucker.duck: no session found for pid={pid} process={process_name!r}")
                    return False
                vol = session.SimpleAudioVolume
                current = vol.GetMasterVolume()
                key = process_name or str(pid)
                if current <= 0.001:
                    self._original_volumes[key] = 1.0
                else:
                    self._original_volumes[key] = current
                vol.SetMasterVolume(self._duck_level, None)
                logger.info(f"Ducked pid={pid} name={process_name!r}: {current:.2f} -> {self._duck_level:.2f}")
                return True
            except Exception as e:
                logger.error(f"Duck error for pid={pid} process={process_name!r}: {e}")
                return False

    def restore(self, process_name=None, pid=None):
        with self._lock:
            return self._restore_unlocked(process_name, pid)

    def _restore_unlocked(self, process_name=None, pid=None):
        logger.debug(f"AppDucker._restore_unlocked: process={process_name!r} pid={pid}")
        try:
            key = process_name or str(pid)
            original = self._original_volumes.pop(key, 1.0)
            session = None
            if pid is not None:
                session = self._find_session_by_pid(pid)
            elif process_name is not None:
                session = self._find_session(process_name)
            if not session:
                logger.debug(f"AppDucker._restore_unlocked: no session found for pid={pid} process={process_name!r}")
                return False
            session.SimpleAudioVolume.SetMasterVolume(original, None)
            logger.info(f"Restored pid={pid} name={process_name!r}: -> {original:.2f}")
            return True
        except Exception as e:
            logger.error(f"Restore error for pid={pid} process={process_name!r}: {e}")
            return False

    def restore_all(self):
        logger.debug("AppDucker.restore_all: restoring all ducked sessions")
        with self._lock:
            names = list(self._original_volumes.keys())
            self._original_volumes.clear()
        for key in names:
            try:
                pid = int(key)
            except ValueError:
                pid = None
            self._restore_unlocked(process_name=None if pid is not None else key, pid=pid)
