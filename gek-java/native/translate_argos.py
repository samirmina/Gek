#!/usr/bin/env python3
import argparse
import sys
from pathlib import Path


def decode_spm(sp, pieces):
    def clean(s):
        if s is None:
            return ""
        s = s.replace("\u2581", " ").replace("\u2047", "")
        return " ".join(s.split()).strip()

    # Prefer decoding string pieces directly; ids decode is unreliable for some Argos/SentencePiece models.
    for name in ("decode", "DecodePieces", "decode_pieces"):
        fn = getattr(sp, name, None)
        if fn is None:
            continue
        try:
            out = fn(pieces)
            if isinstance(out, (list, tuple)):
                out = "".join(out)
            if isinstance(out, str):
                c = clean(out)
                if c:
                    return c
        except Exception:
            pass
    try:
        ids = []
        for p in pieces:
            if hasattr(sp, "piece_to_id"):
                ids.append(sp.piece_to_id(p))
            elif hasattr(sp, "PieceToId"):
                ids.append(sp.PieceToId(p))
            else:
                ids = None
                break
        if ids is not None:
            for name in ("decode", "DecodeIds", "decode_ids"):
                fn = getattr(sp, name, None)
                if fn is None:
                    continue
                try:
                    out = fn(ids)
                    if isinstance(out, (list, tuple)):
                        out = "".join(out)
                    if isinstance(out, str):
                        c = clean(out)
                        # guard against degenerate artifact-only decodes
                        if c and c not in {".", ",", "!", "?", ":", ";"}:
                            return c
                        if c and len(pieces) <= 2:
                            return c
                except Exception:
                    pass
    except Exception:
        pass
    return clean("".join(pieces))


def main():
    ap = argparse.ArgumentParser(description="Local Argos CTranslate2+SentencePiece translator")
    ap.add_argument("--package", required=True, help="Path to Argos package dir (contains model/ and sentencepiece model)")
    ap.add_argument("--text", help="Text to translate (UTF-8)")
    ap.add_argument("--stdin", action="store_true", help="Read text from stdin")
    ap.add_argument("--beam-size", type=int, default=4)
    ap.add_argument("--max-input-chars", type=int, default=12000, help="Trim very long input to avoid runaway memory/time")
    ap.add_argument("--device", default="cpu")
    args = ap.parse_args()

    pkg = Path(args.package)
    if not pkg.is_dir():
        print(f"package not found: {pkg}", file=sys.stderr)
        return 2
    model_dir = pkg / "model"
    spm_path = pkg / "sentencepiece.model"
    if not spm_path.exists():
        # fallback names sometimes used
        for cand in ("source.spm", "spm.model", "bpe.model"):
            p = pkg / cand
            if p.exists():
                spm_path = p
                break
    if not model_dir.is_dir() or not spm_path.exists():
        print(f"invalid argos package (need model/ and sentencepiece/source.spm tokenizer): {pkg}", file=sys.stderr)
        return 2

    if args.stdin:
        text = sys.stdin.read()
    else:
        text = args.text or ""
    if text is None:
        text = ""
    # preserve leading/trailing newlines only when meaningful; trim outer whitespace safely
    if text.strip() == "":
        print("")
        return 0
    if args.max_input_chars and args.max_input_chars > 0 and len(text) > args.max_input_chars:
        text = text[: args.max_input_chars]

    try:
        import ctranslate2  # type: ignore
        import sentencepiece as spm  # type: ignore
    except Exception as e:
        print(f"missing deps (need ctranslate2 and sentencepiece): {e}", file=sys.stderr)
        return 3

    try:
        sp = spm.SentencePieceProcessor(model_file=str(spm_path))
    except Exception as e1:
        try:
            sp = spm.SentencePieceProcessor()
            sp.load(str(spm_path))
        except Exception as e2:
            print(f"failed to load tokenizer '{spm_path.name}' (SentencePiece proto required; BPE text models unsupported): {e1}; {e2}", file=sys.stderr)
            return 4
    try:
        translator = ctranslate2.Translator(str(model_dir), device=args.device)
    except Exception as e:
        print(f"failed to load ctranslate2 model in {model_dir}: {e}", file=sys.stderr)
        return 5

    # For multi-line input, translate line-wise to keep structure stable for simple cases
    lines = text.splitlines(keepends=False)
    if len(lines) <= 1:
        try:
            tokens = sp.encode(text, out_type=str)
        except Exception:
            tokens = sp.EncodeAsPieces(text)
        if not isinstance(tokens, list):
            tokens = list(tokens)
        try:
            result = translator.translate_batch([tokens], beam_size=max(1, int(args.beam_size)), max_decoding_length=max(32, min(4096, len(tokens) * 4 + 16)))
        except Exception as e:
            print(f"translation failed: {e}", file=sys.stderr)
            return 6
        if not result or not result[0].hypotheses:
            print("")
            return 0
        hyp = result[0].hypotheses[0]
        out = decode_spm(sp, hyp)
        sys.stdout.write(out)
        return 0

    outputs = []
    for line in lines:
        if line.strip() == "":
            outputs.append("")
            continue
        try:
            tokens = sp.encode(line, out_type=str)
        except Exception:
            tokens = sp.EncodeAsPieces(line)
        if not isinstance(tokens, list):
            tokens = list(tokens)
        try:
            result = translator.translate_batch([tokens], beam_size=max(1, int(args.beam_size)), max_decoding_length=max(32, min(4096, len(tokens) * 4 + 16)))
        except Exception as e:
            print(f"translation failed: {e}", file=sys.stderr)
            return 6
        if not result or not result[0].hypotheses:
            outputs.append("")
        else:
            outputs.append(decode_spm(sp, result[0].hypotheses[0]))
    sys.stdout.write("\n".join(outputs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
