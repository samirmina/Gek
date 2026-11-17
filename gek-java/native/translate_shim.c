#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

static PyThreadState* main_tstate = NULL;
static int py_inited = 0;

static char* dupstr(const char* s) {
    if (!s) return NULL;
    size_t n = strlen(s);
    char* o = (char*)malloc(n+1);
    if (!o) return NULL;
    memcpy(o,s,n+1);
    return o;
}

__attribute__((visibility("default")))
int gek_translate_init(const char* python_home, const char* venv_site_packages, char** err_out) {
    if (err_out) *err_out = NULL;
    if (py_inited) return 0;

    PyStatus st;
    PyConfig config;
    PyConfig_InitPythonConfig(&config);
    config.install_signal_handlers = 0;
    config.parse_argv = 0;
    (void)python_home;
    st = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(st)) { if(err_out)*err_out=dupstr("Py_Initialize failed"); return -1; }

    PyGILState_STATE g = PyGILState_Ensure();
    PyObject* sysPath = PySys_GetObject("path");
    if (sysPath && PyList_Check(sysPath)) {
        if (venv_site_packages && venv_site_packages[0]) {
            PyObject* p = PyUnicode_FromString(venv_site_packages);
            if (p) { PyList_Insert(sysPath, 0, p); Py_DECREF(p); }
        }
        const char* extra[] = {"/usr/lib/python314.zip","/usr/lib/python3.14","/usr/lib/python3.14/lib-dynload", NULL};
        for (int i=0; extra[i]; i++) {
            PyObject* p = PyUnicode_FromString(extra[i]);
            if (!p) continue;
            int found = 0;
            Py_ssize_t n = PyList_Size(sysPath);
            for (Py_ssize_t j=0;j<n;j++) {
                PyObject* it = PyList_GetItem(sysPath,j);
                if (it && PyUnicode_Check(it) && PyUnicode_Compare(it,p)==0) { found=1; break; }
            }
            if (!found) PyList_Append(sysPath,p);
            Py_DECREF(p);
        }
    }

    const char* code =
"import sys, os\n"
"try:\n"
"    import ctranslate2\n"
"    import sentencepiece as spm\n"
"except Exception as _e:\n"
"    _gek_import_err = str(_e)\n"
"else:\n"
"    _gek_import_err = None\n"
"_gek_cache = {}\n"
"\n"
"def _gek_decode_spm(sp, pieces):\n"
"    def clean(s):\n"
"        if s is None: return ''\n"
"        s = s.replace(chr(0x2581), ' ').replace(chr(0x2047), '')\n"
"        return ' '.join(s.split()).strip()\n"
"    for name in ('decode','DecodePieces','decode_pieces'):\n"
"        fn = getattr(sp,name,None)\n"
"        if fn is None: continue\n"
"        try:\n"
"            out = fn(pieces)\n"
"            if isinstance(out,(list,tuple)): out=''.join(out)\n"
"            if isinstance(out,str):\n"
"                c = clean(out)\n"
"                if c: return c\n"
"        except Exception:\n"
"            pass\n"
"    try:\n"
"        ids=[]\n"
"        for p in pieces:\n"
"            if hasattr(sp,'piece_to_id'): ids.append(sp.piece_to_id(p))\n"
"            elif hasattr(sp,'PieceToId'): ids.append(sp.PieceToId(p))\n"
"            else:\n"
"                ids=None; break\n"
"        if ids is not None:\n"
"            for name in ('decode','DecodeIds','decode_ids'):\n"
"                fn=getattr(sp,name,None)\n"
"                if fn is None: continue\n"
"                try:\n"
"                    out = fn(ids)\n"
"                    if isinstance(out,(list,tuple)): out=''.join(out)\n"
"                    if isinstance(out,str):\n"
"                        c = clean(out)\n"
"                        if c and c not in {'.',',','!','?',':',';'}: return c\n"
"                        if c and len(pieces)<=2: return c\n"
"                except Exception:\n"
"                    pass\n"
"    except Exception:\n"
"        pass\n"
"    return clean(''.join(pieces) if isinstance(pieces,(list,tuple)) else str(pieces))\n"
"\n"
"def _gek_get(pkg_path):\n"
"    if _gek_import_err is not None:\n"
"        raise RuntimeError('missing deps: '+_gek_import_err)\n"
"    ent = _gek_cache.get(pkg_path)\n"
"    if ent is not None: return ent\n"
"    from pathlib import Path\n"
"    pkg = Path(pkg_path)\n"
"    model_dir = pkg/'model'\n"
"    spm_path = pkg/'sentencepiece.model'\n"
"    if not spm_path.exists():\n"
"        for cand in ('source.spm','spm.model','bpe.model'):\n"
"            p = pkg/cand\n"
"            if p.exists(): spm_path=p; break\n"
"    if not model_dir.is_dir() or not spm_path.exists():\n"
"        raise RuntimeError('invalid argos package (need model/ and sentencepiece/source.spm tokenizer): '+str(pkg))\n"
"    try:\n"
"        sp = spm.SentencePieceProcessor(model_file=str(spm_path))\n"
"    except Exception as e1:\n"
"        try:\n"
"            sp = spm.SentencePieceProcessor(); sp.load(str(spm_path))\n"
"        except Exception as e2:\n"
"            raise RuntimeError('failed to load tokenizer '+spm_path.name+': '+str(e1)+'; '+str(e2))\n"
"    try:\n"
"        tr = ctranslate2.Translator(str(model_dir), device='cpu')\n"
"    except Exception as e:\n"
"        raise RuntimeError('failed to load ctranslate2 model in '+str(model_dir)+': '+str(e))\n"
"    ent = (tr, sp)\n"
"    _gek_cache[pkg_path]=ent\n"
"    return ent\n"
"\n"
"def _gek_translate(pkg_path, text):\n"
"    tr, sp = _gek_get(pkg_path)\n"
"    if text is None: text=''\n"
"    if str(text).strip()=='': return ''\n"
"    lines = str(text).splitlines(keepends=False)\n"
"    def one(line):\n"
"        if line.strip()=='': return ''\n"
"        try: tokens = sp.encode(line, out_type=str)\n"
"        except Exception: tokens = sp.EncodeAsPieces(line)\n"
"        if not isinstance(tokens,list): tokens=list(tokens)\n"
"        result = tr.translate_batch([tokens], beam_size=4, max_decoding_length=max(32, min(4096, len(tokens)*4+16)))\n"
"        if not result or not result[0].hypotheses: return ''\n"
"        return _gek_decode_spm(sp, result[0].hypotheses[0])\n"
"    if len(lines)<=1:\n"
"        return one(str(text))\n"
"    return chr(10).join(one(l) for l in lines)\n";

    int rc = PyRun_SimpleString(code);
    if (rc != 0) {
        if (PyErr_Occurred()) PyErr_Clear();
        if (err_out) *err_out = dupstr("failed to initialize translation runtime script");
        PyGILState_Release(g);
        main_tstate = PyEval_SaveThread();
        py_inited = 1;
        return -2;
    }
    PyGILState_Release(g);
    main_tstate = PyEval_SaveThread();
    py_inited = 1;
    return 0;
}

__attribute__((visibility("default")))
char* gek_translate(const char* package_path, const char* text, char** err_out) {
    if (err_out) *err_out = NULL;
    if (!py_inited) { if (err_out) *err_out = dupstr("translation runtime not initialized"); return NULL; }
    if (!package_path) { if (err_out) *err_out = dupstr("package_path is required"); return NULL; }
    PyGILState_STATE g = PyGILState_Ensure();
    PyObject* main = PyImport_AddModule("__main__");
    PyObject* globals = PyModule_GetDict(main);
    PyObject* func = PyDict_GetItemString(globals, "_gek_translate");
    if (!func || !PyCallable_Check(func)) {
        if (err_out) *err_out = dupstr("translation function not available");
        PyGILState_Release(g);
        return NULL;
    }
    PyObject* args = Py_BuildValue("(ss)", package_path, text ? text : "");
    if (!args) {
        if (PyErr_Occurred()) { PyObject *t,*v,*tb; PyErr_Fetch(&t,&v,&tb); PyErr_NormalizeException(&t,&v,&tb); PyObject* s=v?PyObject_Str(v):NULL; if(err_out)*err_out=dupstr(s?PyUnicode_AsUTF8(s):"arg build failed"); Py_XDECREF(s); Py_XDECREF(t); Py_XDECREF(v); Py_XDECREF(tb); PyErr_Clear(); }
        else if (err_out) *err_out = dupstr("failed to build args");
        PyGILState_Release(g);
        return NULL;
    }
    PyObject* res = PyObject_CallObject(func, args);
    Py_DECREF(args);
    if (!res) {
        if (PyErr_Occurred()) {
            PyObject *t,*v,*tb; PyErr_Fetch(&t,&v,&tb); PyErr_NormalizeException(&t,&v,&tb);
            PyObject* s = v ? PyObject_Str(v) : NULL;
            const char* msg = s ? PyUnicode_AsUTF8(s) : "translation failed";
            if (err_out) *err_out = dupstr(msg ? msg : "translation failed");
            Py_XDECREF(s); Py_XDECREF(t); Py_XDECREF(v); Py_XDECREF(tb);
            PyErr_Clear();
        } else if (err_out) *err_out = dupstr("translation failed");
        PyGILState_Release(g);
        return NULL;
    }
    char* out = NULL;
    if (PyUnicode_Check(res)) {
        const char* u = PyUnicode_AsUTF8(res);
        out = dupstr(u ? u : "");
    } else {
        PyObject* s = PyObject_Str(res);
        if (s) { const char* u = PyUnicode_AsUTF8(s); out = dupstr(u ? u : ""); Py_DECREF(s); }
        else out = dupstr("");
    }
    Py_DECREF(res);
    PyGILState_Release(g);
    return out;
}

__attribute__((visibility("default")))
void gek_translate_free_str(char* s) {
    if (s) free(s);
}

__attribute__((visibility("default")))
void gek_translate_shutdown(void) {
    if (!py_inited) return;
    PyGILState_Ensure();
    Py_FinalizeEx();
    py_inited = 0;
    main_tstate = NULL;
}
