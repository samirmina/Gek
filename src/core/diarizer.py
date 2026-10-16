import logging
import os
import warnings

from config import MODELS_DIR

warnings.filterwarnings("ignore", message=".*torchcodec.*")
warnings.filterwarnings("ignore", message=".*triton.*")
warnings.filterwarnings("ignore", category=UserWarning, module="pyannote")

import numpy as np

logger = logging.getLogger(__name__)

# Diarization is optional — degraded mode without it


class Diarizer:
    def __init__(
        self,
        sample_rate=16000,
        max_speakers=4,
        model_path=None,
        on_status=None,
    ):
        self.sample_rate = sample_rate
        self.max_speakers = max_speakers
        self.model_path = model_path or str(MODELS_DIR / "pyannote" / "speaker-diarization-community-1")
        self.on_status = on_status

        self._pipeline = None
        self._loaded = False

    def load_model(self):
        logger.info("Diarizer.load_model: begin")
        if self._loaded:
            logger.debug("Diarizer.load_model: already loaded, skipping")
            return
        if self.on_status:
            self.on_status("Loading diarization model...")
        try:
            from pyannote.audio import Pipeline
            self._pipeline = Pipeline.from_pretrained(
                self.model_path,
            )
            device = self._get_device()
            if device is not None:
                try:
                    self._pipeline.to(device)
                except Exception as e:
                    logger.warning(f"Diarizer CUDA init failed, falling back to CPU: {e}")
            self._loaded = True
            if self.on_status:
                self.on_status("Diarization model loaded")
            logger.info("Diarization model loaded from local path")
        except Exception as e:
            logger.warning(f"Diarization model load failed: {e}")
            if self.on_status:
                self.on_status(f"Diarization unavailable: {e}")
            self._loaded = False

    def _get_device(self):
        try:
            import torch
            if torch.cuda.is_available():
                return torch.device("cuda")
        except ImportError:
            pass
        return None

    def diarize(self, audio, sample_rate=None):
        logger.debug(f"Diarizer.diarize: available={self._loaded} audio_len={len(audio)}")
        if not self._loaded or self._pipeline is None:
            logger.debug("Diarizer.diarize: not available, returning []")
            return []

        sr = sample_rate or self.sample_rate

        try:
            import torch

            audio_np = np.array(audio, dtype=np.float32)
            if audio_np.ndim > 1:
                audio_np = audio_np.mean(axis=1)

            if audio_np.size == 0 or np.isnan(audio_np).all():
                return []

            if len(audio_np) < sr * 0.5:
                return []

            waveform = torch.from_numpy(audio_np).unsqueeze(0)

            diarization = self._pipeline(
                {"waveform": waveform, "sample_rate": sr},
                num_speakers=None,
                min_speakers=1,
                max_speakers=min(self.max_speakers, 4),
            )

            segments = []
            for turn, _, speaker in diarization.speaker_diarization.itertracks(yield_label=True):
                segments.append({
                    "start": turn.start,
                    "end": turn.end,
                    "speaker": speaker,
                })

            logger.debug(f"Diarizer.diarize: got {len(segments)} segments")
            return self._merge_speakers(segments)

        except Exception as e:
            logger.warning(f"Diarization failed: {e}")
            return []

    def _merge_speakers(self, segments):
        logger.debug(f"Diarizer._merge_speakers: input={len(segments)} segments")
        if not segments:
            return []

        merged = []
        for seg in segments:
            if not merged:
                merged.append(dict(seg))
                continue
            last = merged[-1]
            if (
                seg["speaker"] == last["speaker"]
                and seg["start"] - last["end"] < 0.3
            ):
                last["end"] = seg["end"]
            else:
                merged.append(dict(seg))
        logger.debug(f"Diarizer._merge_speakers: output={len(merged)} segments")
        return merged

    def get_speaker_label(self, segments, time_sec):
        logger.debug(f"Diarizer.get_speaker_label: segments={len(segments)} time={time_sec:.3f}")
        if not segments:
            return None
        for seg in segments:
            if seg["start"] <= time_sec <= seg["end"]:
                return seg["speaker"]
        return segments[0]["speaker"] if segments else None

    def is_available(self):
        logger.debug(f"Diarizer.is_available: loaded={self._loaded}")
        return self._loaded

    def unload(self):
        logger.info("Diarizer.unload: unloading")
        self._pipeline = None
        self._loaded = False
        if self.on_status:
            self.on_status("Diarization unloaded")
