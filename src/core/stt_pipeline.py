import gc
import os
import queue
import re
import threading
import time
import logging

import numpy as np
import soundcard
from faster_whisper import WhisperModel

logger = logging.getLogger(__name__)


class STTPipeline:
    def __init__(
        self,
        model_size="small",
        device="cpu",
        compute_type="int8",
        language=None,
        sample_rate=16000,
        chunk_duration: float = 1,
        models_dir=None,
        on_transcription=None,
        on_status=None,
        silence_threshold=0.01,
        silence_duration_ms=250,
        min_chunk_duration=0.5,
        max_chunk_duration=5.0,
        carryover_duration=0.2,
        diarizer=None,
        idle_timeout=10,
        transcribe_timeout_factor=5.0,
        beam_size=1,
        no_speech_threshold=0.6,
        log_prob_threshold=-1.0,
        compression_ratio_threshold=2.4,
        condition_on_previous_text=False,
        loopback_device=None,
        microphone_device=None,
    ):
        self.model_size = model_size
        self.device = device
        self.compute_type = compute_type
        self.language = language
        self.sample_rate = sample_rate
        self.chunk_duration = chunk_duration
        self.models_dir = models_dir
        self.on_transcription = on_transcription
        self.on_status = on_status
        self.silence_threshold = silence_threshold
        self.silence_duration_ms = silence_duration_ms
        self.min_chunk_duration = min_chunk_duration
        self.max_chunk_duration = max_chunk_duration
        self.carryover_duration = carryover_duration
        self.diarizer = diarizer
        self.idle_timeout = idle_timeout
        self.transcribe_timeout_factor = transcribe_timeout_factor
        self.beam_size = beam_size
        self.no_speech_threshold = no_speech_threshold
        self.log_prob_threshold = log_prob_threshold
        self.compression_ratio_threshold = compression_ratio_threshold
        self.condition_on_previous_text = condition_on_previous_text
        self.loopback_device = loopback_device
        self.microphone_device = microphone_device

        from config import WHISPER_BEAM_SIZE_MAP
        self._beam_size_map = WHISPER_BEAM_SIZE_MAP

        self._model = None
        self._queue = queue.Queue(maxsize=30)
        self._running = False
        self._capture_threads = []
        self._stt_thread = None
        self._model_lock = threading.Lock()
        self._loopback = None
        self._microphone = None
        self._transcribe_queue = queue.Queue(maxsize=4)
        self._transcribe_workers = []
        self._num_workers = 1
        self._pause_reasons = set()
        self._state_lock = threading.RLock()
        self._buffer_reset_event = threading.Event()

    def _find_loopback(self):
        all_mics = soundcard.all_microphones(include_loopback=True)
        if self.loopback_device:
            for m in all_mics:
                if m.isloopback and m.name == self.loopback_device:
                    return m
        loopbacks = [m for m in all_mics if m.isloopback]
        for lb in loopbacks:
            name_lower = lb.name.lower()
            if "speaker" in name_lower or "headset" in name_lower:
                return lb
        return loopbacks[0] if loopbacks else None

    def _find_microphone(self):
        all_mics = soundcard.all_microphones(include_loopback=True)
        if self.microphone_device:
            for m in all_mics:
                if not m.isloopback and m.name == self.microphone_device:
                    return m
        mics = [m for m in all_mics if not m.isloopback]
        for mic in mics:
            name_lower = mic.name.lower()
            if "mic" in name_lower:
                return mic
        return mics[0] if mics else None

    def _to_mono(self, audio):
        if isinstance(audio, np.ndarray):
            if audio.ndim == 2 and audio.shape[1] > 1:
                audio = audio.mean(axis=1)
            elif audio.ndim == 2 and audio.shape[1] == 1:
                audio = audio.flatten()
        return audio.astype(np.float32)

    def _capture_loop(self, source, label):
        chunk_size = int(self.sample_rate * self.chunk_duration)
        logger.info(f"STTPipeline._capture_loop[{label}]: starting source={source.name} chunk_size={chunk_size}")
        try:
            with source.recorder(
                samplerate=self.sample_rate,
                blocksize=chunk_size,
            ) as recorder:
                logger.info(f"STTPipeline._capture_loop[{label}]: recorder opened")
                while self._running:
                    if self.is_input_paused:
                        time.sleep(0.02)
                        continue
                    try:
                        audio = recorder.record(numframes=chunk_size)
                        mono_audio = self._to_mono(audio)
                        if self.is_input_paused:
                            continue
                        try:
                            self._queue.put_nowait((label, mono_audio))
                        except queue.Full:
                            try:
                                self._queue.get_nowait()
                            except queue.Empty:
                                pass
                            try:
                                self._queue.put_nowait((label, mono_audio))
                            except queue.Full:
                                pass
                    except Exception as e:
                        logger.error(f"Capture error [{label}]: {e}")
                        break
        except Exception as e:
            logger.error(f"Capture thread failed [{label}]: {e}")
        logger.info(f"STTPipeline._capture_loop[{label}]: exited")

    def _get_device(self):
        try:
            import torch
            if self.device == "cuda" and torch.cuda.is_available():
                return "cuda"
        except ImportError:
            pass
        return "cpu"

    def load_model(self):
        logger.debug(f"STTPipeline.load_model: acquiring lock model={self.model_size}")
        with self._model_lock:
            if self._model is not None:
                logger.debug(f"STTPipeline.load_model: model already loaded, skipping")
                return
            if self.on_status:
                self.on_status(f"Loading model {self.model_size}...")
            try:
                snapshot_path = None
                if self.models_dir:
                    cache_dir = os.path.join(self.models_dir, f"models--Systran--faster-whisper-{self.model_size}", "snapshots")
                    if os.path.isdir(cache_dir):
                        for entry in os.listdir(cache_dir):
                            candidate = os.path.join(cache_dir, entry)
                            if os.path.isdir(candidate):
                                model_bin = os.path.join(candidate, "model.bin")
                                if os.path.isfile(model_bin):
                                    snapshot_path = candidate
                                    logger.info(f"STTPipeline.load_model: found snapshot at {snapshot_path}")
                                    break
                                else:
                                    logger.warning(f"Incomplete model cache at {candidate}, missing model.bin")

                device = self._get_device()
                effective_beam = self.beam_size
                if self.model_size in self._beam_size_map:
                    effective_beam = self._beam_size_map[self.model_size]

                if device == "cuda":
                    try:
                        import torch
                        total = torch.cuda.get_device_properties(0).total_memory / 1024**2
                        reserved = torch.cuda.memory_reserved(0) / 1024**2
                        free = total - reserved
                        if free < 2048:
                            logger.warning(f"Low VRAM: {free:.0f} MB free, model load may fail or cause OOM")
                    except Exception:
                        pass

                if snapshot_path:
                    kwargs = {
                        "model_size_or_path": snapshot_path,
                        "device": device,
                        "compute_type": self.compute_type,
                    }
                else:
                    kwargs = {
                        "model_size_or_path": self.model_size,
                        "device": device,
                        "compute_type": self.compute_type,
                    }
                    if self.models_dir:
                        kwargs["download_root"] = self.models_dir
                logger.info(f"STTPipeline.load_model: loading WhisperModel kwargs={kwargs}")
                self._model = WhisperModel(**kwargs)
                if self.on_status:
                    self.on_status(f"Model loaded ({device}, beam={effective_beam})")
                logger.info(f"Whisper {self.model_size} loaded on {device}, beam_size={effective_beam}, compute={self.compute_type}")
            except Exception as e:
                logger.error(f"Model load failed: {e}")
                if self.on_status:
                    self.on_status(f"Model load failed: {e}")
                raise

    def unload_model(self):
        logger.debug("STTPipeline.unload_model: acquiring lock")
        with self._model_lock:
            if self._model is None:
                logger.debug("STTPipeline.unload_model: model already None, skipping")
                return
            self._model = None
            logger.info("STTPipeline.unload_model: model set to None")
        if self._get_device() == "cuda":
            try:
                import torch
                torch.cuda.empty_cache()
                logger.debug("STTPipeline.unload_model: CUDA cache emptied")
            except Exception as e:
                logger.debug("CUDA cleanup failed: %s", e)
        gc.collect()
        if self.on_status:
            self.on_status("Model unloaded (VRAM freed)")
        logger.info("Whisper model unloaded, VRAM freed")

    def feed_audio(self, label, audio):
        logger.debug(f"STTPipeline.feed_audio[{label}]: running={self._running} queue_size={self._queue.qsize()}")
        if not self._running or self.is_input_paused:
            logger.debug(f"STTPipeline.feed_audio[{label}]: not accepting audio, dropping")
            return
        try:
            self._queue.put_nowait((label, audio))
        except queue.Full:
            try:
                self._queue.get_nowait()
            except queue.Empty:
                pass
            try:
                self._queue.put_nowait((label, audio))
            except queue.Full:
                logger.warning(f"STTPipeline.feed_audio[{label}]: queue full after drop")

    @property
    def is_input_paused(self):
        with self._state_lock:
            return bool(self._pause_reasons)

    @property
    def is_listening(self):
        return bool(self._running and not self.is_input_paused)

    def pause_input(self, reason="manual"):
        if not reason:
            reason = "manual"
        with self._state_lock:
            was_paused = bool(self._pause_reasons)
            self._pause_reasons.add(reason)
            if was_paused:
                return
            self._buffer_reset_event.set()
            for q in (self._queue, self._transcribe_queue):
                while True:
                    try:
                        q.get_nowait()
                    except queue.Empty:
                        break

    def resume_input(self, reason="manual"):
        if not reason:
            reason = "manual"
        with self._state_lock:
            self._pause_reasons.discard(reason)

    def set_language(self, language):
        if language in (None, "", "auto", "Auto-detect", "Detected"):
            self.language = None
        else:
            self.language = language

    def start(self):
        logger.info(f"STTPipeline.start: begin model={self.model_size} device={self.device}")
        self._loopback = self._find_loopback()
        self._microphone = self._find_microphone()

        missing = []
        if not self._loopback:
            missing.append("loopback (system audio)")
        if not self._microphone:
            missing.append("microphone")
        if missing:
            raise RuntimeError(f"Audio devices not found: {', '.join(missing)}")

        logger.info(f"STTPipeline.start: loopback={self._loopback.name}")
        logger.info(f"STTPipeline.start: mic={self._microphone.name}")

        self._running = True

        t_loopback = threading.Thread(
            target=self._capture_loop,
            args=(self._loopback, "system"),
            daemon=True,
            name="capture-system",
        )
        t_mic = threading.Thread(
            target=self._capture_loop,
            args=(self._microphone, "mic"),
            daemon=True,
            name="capture-mic",
        )
        t_stt = threading.Thread(
            target=self._stt_loop,
            daemon=True,
            name="stt-engine",
        )

        self._capture_threads = [t_loopback, t_mic]
        self._stt_thread = t_stt
        logger.info("STTPipeline.start: starting capture-system thread")
        t_loopback.start()
        logger.info("STTPipeline.start: starting capture-mic thread")
        t_mic.start()
        logger.info("STTPipeline.start: starting stt-engine thread")
        t_stt.start()
        logger.info("STTPipeline.start: all threads started")

        self._transcribe_workers = []
        for i in range(self._num_workers):
            t = threading.Thread(
                target=self._transcribe_worker,
                daemon=True,
                name=f"stt-worker-{i+1}",
            )
            logger.info(f"STTPipeline.start: starting {t.name}")
            t.start()
            self._transcribe_workers.append(t)
        logger.info(f"STTPipeline.start: complete, workers={len(self._transcribe_workers)}")

    def _rms(self, audio):
        if audio is None or len(audio) == 0:
            return 0.0
        with np.errstate(invalid='ignore', divide='ignore'):
            return float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))

    def _is_silence(self, audio):
        rms = self._rms(audio)
        return rms < self.silence_threshold

    def _stt_loop(self):
        logger.info("STTPipeline._stt_loop: starting")
        try:
            self.load_model()
        except Exception as e:
            logger.error(f"STT thread aborting: {e}")
            return

        buffers = {"system": [], "mic": []}
        buffer_durations = {"system": 0.0, "mic": 0.0}
        silent_counts = {"system": 0, "mic": 0}
        has_speech = {"system": False, "mic": False}
        carryover = {"system": [], "mic": []}
        carryover_durations = {"system": 0.0, "mic": 0.0}
        last_transcribe_time = time.monotonic()
        logger.info("STTPipeline._stt_loop: model loaded, entering main loop")

        chunk_sec = self.chunk_duration
        carryover_chunks_needed = max(1, int(self.carryover_duration / chunk_sec))
        logger.debug(f"STTPipeline._stt_loop: carryover_chunks_needed={carryover_chunks_needed}")
        while self._running:
            if self._buffer_reset_event.is_set():
                buffers = {"system": [], "mic": []}
                buffer_durations = {"system": 0.0, "mic": 0.0}
                silent_counts = {"system": 0, "mic": 0}
                has_speech = {"system": False, "mic": False}
                carryover = {"system": [], "mic": []}
                carryover_durations = {"system": 0.0, "mic": 0.0}
                self._buffer_reset_event.clear()
            try:
                label, audio = self._queue.get(timeout=0.1)
            except queue.Empty:
                if self.is_input_paused:
                    continue
                with self._model_lock:
                    has_model = self._model is not None
                if (
                    has_model
                    and self.idle_timeout > 0
                    and (time.monotonic() - last_transcribe_time) > self.idle_timeout
                ):
                    logger.info("STTPipeline._stt_loop: idle timeout reached, unloading model")
                    self.unload_model()
                continue

            if label not in buffers:
                logger.warning(f"STTPipeline._stt_loop: unknown audio source: {label}, ignoring")
                continue

            with self._model_lock:
                needs_model = self._model is None
            if needs_model:
                try:
                    logger.info(f"STTPipeline._stt_loop: model is None, reloading for {label}")
                    self.load_model()
                except Exception as e:
                    logger.error(f"Model reload failed: {e}")
                    continue

            chunk_sec = len(audio) / self.sample_rate
            is_silent = self._is_silence(audio)
            rms = self._rms(audio)
            logger.debug(f"STTPipeline._stt_loop[{label}]: chunk={chunk_sec:.3f}s silent={is_silent} rms={rms:.6f} threshold={self.silence_threshold:.6f} buffer_dur={buffer_durations[label]:.3f}s")

            if not is_silent:
                has_speech[label] = True
                silent_counts[label] = 0
            elif has_speech[label]:
                silent_counts[label] += 1

            buffers[label].append(audio)
            buffer_durations[label] += chunk_sec

            silence_chunks_needed = max(1, int(self.silence_duration_ms / (chunk_sec * 1000)))

            silence_triggered = has_speech[label] and silent_counts[label] >= silence_chunks_needed
            min_reached = buffer_durations[label] >= self.min_chunk_duration
            max_reached = buffer_durations[label] >= self.max_chunk_duration

            if (silence_triggered and min_reached) or buffer_durations[label] >= self.max_chunk_duration:
                if buffers[label]:
                    transcribe_chunks = buffers[label]
                    if self.is_input_paused:
                        buffers[label] = []
                        buffer_durations[label] = 0.0
                        silent_counts[label] = 0
                        has_speech[label] = False
                        continue
                    try:
                        self._transcribe_queue.put_nowait((transcribe_chunks, label))
                        logger.info(f"STTPipeline._stt_loop[{label}]: queued {len(transcribe_chunks)} chunks, buffer_dur={buffer_durations[label]:.3f}s")
                    except queue.Full:
                        try:
                            self._transcribe_queue.get_nowait()
                        except queue.Empty:
                            pass
                        try:
                            self._transcribe_queue.put_nowait((transcribe_chunks, label))
                            logger.info(f"STTPipeline._stt_loop[{label}]: queued after drop, {len(transcribe_chunks)} chunks")
                        except queue.Full:
                            logger.warning(f"STTPipeline._stt_loop[{label}]: queue still full, dropping chunk")
                    last_transcribe_time = time.monotonic()

                    if len(transcribe_chunks) > carryover_chunks_needed:
                        carryover[label] = transcribe_chunks[-carryover_chunks_needed:]
                        carryover_durations[label] = sum(
                            len(c) / self.sample_rate for c in carryover[label]
                        )
                    else:
                        carryover[label] = []
                        carryover_durations[label] = 0.0

                    buffers[label] = []
                    buffer_durations[label] = 0.0
                    silent_counts[label] = 0
                    has_speech[label] = False
            else:
                if carryover[label] and not buffers[label]:
                    buffers[label] = list(carryover[label])
                    buffer_durations[label] = carryover_durations[label]
                    has_speech[label] = True
                    carryover[label] = []
                    carryover_durations[label] = 0.0

    def _transcribe_worker(self):
        logger.info(f"STTPipeline._transcribe_worker[{threading.current_thread().name}]: starting")
        while self._running:
            try:
                chunks, label = self._transcribe_queue.get(timeout=0.1)
                logger.debug(f"STTPipeline._transcribe_worker[{threading.current_thread().name}]: got {len(chunks)} chunks for {label}")
                if self.is_input_paused:
                    continue
                self._do_transcribe(chunks, label)
            except queue.Empty:
                continue
            except Exception as e:
                logger.error(f"Worker error: {e}")
        logger.info(f"STTPipeline._transcribe_worker[{threading.current_thread().name}]: exited")

    def _do_transcribe(self, chunks, source):
        if self.is_input_paused:
            return
        with self._model_lock:
            model = self._model
        if model is None:
            logger.warning(f"STTPipeline._do_transcribe[{source}]: model is None, aborting")
            return
        audio = np.concatenate(chunks).flatten().astype(np.float32)
        if len(audio) == 0:
            logger.warning(f"STTPipeline._do_transcribe[{source}]: empty audio, aborting")
            return
        if audio.max() == 0 and audio.min() == 0:
            logger.warning(f"STTPipeline._do_transcribe[{source}]: zero audio, aborting")
            return
        if np.max(np.abs(audio)) < self.silence_threshold:
            logger.debug(f"STTPipeline._do_transcribe[{source}]: below silence threshold, aborting")
            return

        if self.is_input_paused:
            return

        audio_duration = len(audio) / self.sample_rate
        t0 = time.monotonic()
        logger.debug(f"STTPipeline._do_transcribe[{source}]: starting transcription audio_dur={audio_duration:.3f}s")

        segments, info = model.transcribe(
            audio,
            language=self.language,
            beam_size=self.beam_size,
            condition_on_previous_text=self.condition_on_previous_text,
            compression_ratio_threshold=self.compression_ratio_threshold,
            no_speech_threshold=self.no_speech_threshold,
            log_prob_threshold=self.log_prob_threshold,
        )
        texts = []
        for segment in segments:
            text = segment.text.strip()
            if text:
                texts.append(text)

        elapsed = time.monotonic() - t0
        if (
            self.transcribe_timeout_factor > 0
            and audio_duration > 0
            and elapsed > audio_duration * self.transcribe_timeout_factor
        ):
            logger.warning(
                f"Transcription too slow ({elapsed:.1f}s for {audio_duration:.1f}s audio), reloading model"
            )
            self.unload_model()
            return

        if not texts:
            logger.debug(f"STTPipeline._do_transcribe[{source}]: no texts produced")
            return

        full_text = " ".join(texts)
        detected_lang = info.language
        prob = getattr(info, "language_probability", 0.0)
        try:
            prob_str = f"{prob:.2f}"
        except Exception:
            prob_str = str(prob)
        logger.info(f"STTPipeline._do_transcribe[{source}]: detected={detected_lang} prob={prob_str} text={full_text[:80]!r}")

        if detected_lang == "es" and self.language is None:
            try:
                segments_en, _ = model.transcribe(
                    audio,
                    language="es",
                    task="translate",
                    beam_size=self.beam_size,
                    condition_on_previous_text=self.condition_on_previous_text,
                    compression_ratio_threshold=self.compression_ratio_threshold,
                    no_speech_threshold=self.no_speech_threshold,
                    log_prob_threshold=self.log_prob_threshold,
                )
                en_texts = []
                for seg in segments_en:
                    t = seg.text.strip()
                    if t:
                        en_texts.append(t)
                if en_texts:
                    full_text = " ".join(en_texts)
                    detected_lang = "en"
                    logger.info(f"Whisper translated Spanish -> English")
            except Exception as e:
                logger.warning(f"Whisper translate failed, using transcription: {e}")

        if detected_lang == "ar" and info.language_probability > 0.7 and self.language is None:
            try:
                segments_ar, _ = model.transcribe(
                    audio,
                    language="ar",
                    beam_size=self.beam_size,
                    condition_on_previous_text=self.condition_on_previous_text,
                    compression_ratio_threshold=self.compression_ratio_threshold,
                    no_speech_threshold=self.no_speech_threshold,
                    log_prob_threshold=self.log_prob_threshold,
                )
                ar_texts = []
                for seg in segments_ar:
                    t = seg.text.strip()
                    if t:
                        ar_texts.append(t)
                if ar_texts:
                    full_text = " ".join(ar_texts)
                    logger.info(f"Whisper re-transcribed Arabic with explicit language")
            except Exception as e:
                logger.warning(f"Whisper Arabic re-transcription failed, using original: {e}")

        if self._is_hallucination(full_text):
            logger.debug(f"Discarded hallucination: {full_text[:60]}...")
            return

        speaker = ""
        if self.diarizer and self.diarizer.is_available():
            try:
                diar_segments = self.diarizer.diarize(audio, self.sample_rate)
                if diar_segments:
                    speaker_durations = {}
                    for seg in diar_segments:
                        sp = seg.get("speaker", "")
                        if sp:
                            dur = seg.get("end", 0) - seg.get("start", 0)
                            speaker_durations[sp] = speaker_durations.get(sp, 0) + dur
                    if speaker_durations:
                        speaker = max(speaker_durations, key=speaker_durations.get)
                        logger.debug(f"STTPipeline._do_transcribe[{source}]: speaker={speaker}")
            except Exception as e:
                logger.debug(f"Diarization error in STT: {e}")

        if self.on_transcription:
            try:
                logger.debug(f"STTPipeline._do_transcribe[{source}]: invoking on_transcription callback")
                self.on_transcription(source, full_text, speaker, detected_lang, info.language_probability)
                logger.debug(f"STTPipeline._do_transcribe[{source}]: callback returned")
            except Exception as e:
                logger.error(f"Transcription callback error: {e}")

    _HALLUCINATION_PHRASES = (
        "thanks for watching",
        "thank you for watching",
        "please subscribe",
        "subscribe to my channel",
        "like and subscribe",
        "don't forget to subscribe",
        "see you next time",
        "see you in the next video",
        "see you soon",
        "bye bye",
        "if you liked this video",
        "please like and subscribe",
        "and i'll see you",
        "and i will see you",
        "subscrib",
        "my channel",
        "our channel",
        "next video",
        "follow me on",
        "translated by",
        "translation by",
        "subtitles by",
        "for watching",
        "we'll be right back",
        "we'll see you next week",
        "اشتركوا في القناة",
        "اشتراك في القناة",
        "لا تنسوا الاشتراك",
        "شكرا للمشاهدة",
        "شكرا على المشاهدة",
        "اشتراك",
        "abonneer op het kanaal",
        "bedankt voor het kijken",
        "abonneer",
        "abonnieren sie den kanal",
        "abonniere den kanal",
        "danke fürs zuschauen",
        "danke für das zuschauen",
        "abonnieren",
        "abonnez-vous à la chaîne",
        "abonnez-vous a la chaine",
        "merci d'avoir regardé",
        "merci d'avoir regarde",
        "abonnez-vous",
        "suscríbete al canal",
        "suscribete al canal",
        "gracias por ver",
        "suscríbete",
        "suscribete",
        "チャンネル登録",
        "ご視聴ありがとうございました",
        "チャンネル登録をお願いします",
        "채널 구독",
        "구독과 좋아요",
        "시청해 주셔서 감사합니다",
        "구독해 주세요",
        "请订阅频道",
        "感谢观看",
        "感谢收看",
        "字幕",
        "subskrybuj kanał",
        "subskrybuj kanal",
        "dzięki za oglądanie",
        "dzieki za ogladanie",
        "subskrybuj",
    )

    _HALLUCINATION_PATTERNS = (
        re.compile(r'(\w{1,6}\.){4,}'),
        re.compile(r'(.)\1{5,}'),
        re.compile(r'(.{1,2})\1{5,}'),
    )

    def _is_hallucination(self, text):
        if len(text) < 2:
            return True
        lower = text.lower().strip()
        for phrase in self._HALLUCINATION_PHRASES:
            if phrase in lower:
                return True
        punct_count = sum(1 for c in text if c in ".,!?;:…。，！？、")
        if punct_count > len(text) * 0.3 and len(text) < 30:
            return True
        for pattern in self._HALLUCINATION_PATTERNS:
            if pattern.search(lower):
                return True
        words = lower.split()
        for i in range(len(words) - 3):
            if words[i] == words[i+1] == words[i+2] == words[i+3]:
                return True
        for n in range(2, min(5, max(2, len(words) // 3 + 1))):
            if len(words) < n * 2:
                continue
            phrase_counts = {}
            for i in range(len(words) - n + 1):
                phrase = " ".join(words[i:i+n])
                phrase_counts[phrase] = phrase_counts.get(phrase, 0) + 1
            for phrase, count in phrase_counts.items():
                if count >= 3:
                    return True
        return False

    def stop(self):
        logger.info("STTPipeline.stop: beginning shutdown")
        self._running = False
        with self._state_lock:
            self._pause_reasons.clear()
        self._buffer_reset_event.set()
        for q in (self._queue, self._transcribe_queue):
            while True:
                try:
                    q.get_nowait()
                except queue.Empty:
                    break
        capture_threads = getattr(self, "_capture_threads", [])
        for t in capture_threads:
            if t.is_alive():
                logger.debug(f"STTPipeline.stop: joining {t.name}")
                t.join(timeout=1)
        if hasattr(self, "_capture_threads"):
            self._capture_threads.clear()
        stt_thread = getattr(self, "_stt_thread", None)
        if stt_thread and stt_thread.is_alive() and stt_thread is not threading.current_thread():
            logger.debug(f"STTPipeline.stop: joining {stt_thread.name}")
            stt_thread.join(timeout=1)
            self._stt_thread = None
        for t in getattr(self, "_transcribe_workers", []):
            if t.is_alive():
                logger.debug(f"STTPipeline.stop: joining {t.name}")
                t.join(timeout=1)
        if hasattr(self, "_transcribe_workers"):
            self._transcribe_workers.clear()
        self.unload_model()
        logger.info("STTPipeline.stop: shutdown complete")
