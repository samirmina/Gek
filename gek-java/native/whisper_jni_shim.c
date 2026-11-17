#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif
#include "whisper.h"

// Simple shim for JNA: hides complex struct layout.
// Returns 0 on success.

typedef struct shim_result {
    char* text;
    char* lang;
    int n_segments;
} shim_result;

__attribute__((visibility("default")))
struct whisper_context* gek_whisper_init(const char* model_path) {
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    return whisper_init_from_file_with_params(model_path, cparams);
}

__attribute__((visibility("default")))
int gek_whisper_transcribe(struct whisper_context* ctx, const float* samples, int n_samples, const char* lang_hint, shim_result* out) {
    if (!ctx || !samples || n_samples <= 0 || !out) return -1;
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.no_timestamps = true;
    wparams.single_segment = false;
    wparams.translate = false;
#ifdef _WIN32
    SYSTEM_INFO sysinfo; GetSystemInfo(&sysinfo);
    int ncpu = (int)sysinfo.dwNumberOfProcessors;
#else
    int ncpu = (int)sysconf(_SC_NPROCESSORS_ONLN);
#endif
    if (ncpu < 1) ncpu = 4;
    if (ncpu > 8) ncpu = 8;
    wparams.n_threads = ncpu;
    wparams.detect_language = false;
    if (lang_hint && lang_hint[0] != '\0' && strcmp(lang_hint, "auto") != 0 && strcmp(lang_hint, "detected") != 0) {
        wparams.language = lang_hint;
    } else {
        wparams.language = "auto";
    }
    int rc = whisper_full(ctx, wparams, samples, n_samples);
    if (rc != 0) return rc;
    int n = whisper_full_n_segments(ctx);
    // concat segments
    size_t cap = 256;
    char* buf = (char*)malloc(cap);
    if (!buf) return -2;
    buf[0] = '\0';
    size_t len = 0;
    for (int i = 0; i < n; ++i) {
        const char* txt = whisper_full_get_segment_text(ctx, i);
        if (!txt) continue;
        // trim leading spaces in segment
        while (*txt == ' ' || *txt == '\t' || *txt == '\n' || *txt == '\r') txt++;
        size_t tlen = strlen(txt);
        if (tlen == 0) continue;
        // trim trailing whitespace
        while (tlen > 0 && (txt[tlen-1]==' '||txt[tlen-1]=='\t'||txt[tlen-1]=='\n'||txt[tlen-1]=='\r')) tlen--;
        if (tlen == 0) continue;
        size_t need = len + (len>0?1:0) + tlen + 1;
        if (need > cap) {
            while (need > cap) cap *= 2;
            char* nb = (char*)realloc(buf, cap);
            if (!nb) { free(buf); return -2; }
            buf = nb;
        }
        if (len > 0) buf[len++] = ' ';
        memcpy(buf + len, txt, tlen);
        len += tlen;
        buf[len] = '\0';
    }
    // trim overall
    while (len > 0 && (buf[len-1]==' '||buf[len-1]=='\n'||buf[len-1]=='\r'||buf[len-1]=='\t')) buf[--len]='\0';
    size_t start = 0;
    while (buf[start]==' '||buf[start]=='\t'||buf[start]=='\n'||buf[start]=='\r') start++;
    if (start > 0) memmove(buf, buf+start, len-start+1);
    out->text = buf;
    int lid = whisper_full_lang_id(ctx);
    const char* ls = whisper_lang_str(lid);
    if (ls) {
        out->lang = strdup(ls);
    } else {
        out->lang = strdup("");
    }
    out->n_segments = n;
    return 0;
}

__attribute__((visibility("default")))
void gek_whisper_free_result(shim_result* r) {
    if (!r) return;
    if (r->text) { free(r->text); r->text = NULL; }
    if (r->lang) { free(r->lang); r->lang = NULL; }
    r->n_segments = 0;
}

__attribute__((visibility("default")))
void gek_whisper_free(struct whisper_context* ctx) {
    if (ctx) whisper_free(ctx);
}

__attribute__((visibility("default")))
const char* gek_whisper_version(void) { return "shim1"; }
