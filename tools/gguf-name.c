/* gguf-name model.gguf: print a short display name for a GGUF model, from its
 * own metadata (general.name, or general.basename + general.size_label when
 * the name is long), e.g. "Google Gemma 3 1b It", "Qwen2.5 Coder 0.5B Instruct".
 * llm-serve uses it for the server's model id and /tmp/model.name (the screen). */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { T_STRING = 8, T_ARRAY = 9 };
static const int type_size[13] = {1, 1, 2, 2, 4, 4, 4, 1, 0, 0, 8, 8, 8};

static FILE *f;

static int rd(void *p, size_t n) { return fread(p, 1, n, f) == n; }

/* Reads a GGUF string into out (truncated to cap-1), or skips it if out is NULL. */
static int rd_str(char *out, size_t cap)
{
	uint64_t n;
	if (!rd(&n, 8)) return 0;
	if (!out) return fseek(f, (long)n, SEEK_CUR) == 0;
	size_t take = n < cap - 1 ? n : cap - 1;
	if (!rd(out, take)) return 0;
	out[take] = 0;
	return fseek(f, (long)(n - take), SEEK_CUR) == 0;
}

static int skip(uint32_t t)
{
	if (t == T_STRING) return rd_str(NULL, 0);
	if (t == T_ARRAY) {
		uint32_t at;
		uint64_t n;
		if (!rd(&at, 4) || !rd(&n, 8)) return 0;
		if (at == T_STRING) {
			while (n--)
				if (!rd_str(NULL, 0)) return 0;
			return 1;
		}
		return at < 13 && type_size[at] && fseek(f, (long)(type_size[at] * n), SEEK_CUR) == 0;
	}
	return t < 13 && type_size[t] && fseek(f, type_size[t], SEEK_CUR) == 0;
}

int main(int argc, char **argv)
{
	char magic[4], key[256], name[128] = "", base[64] = "", size[32] = "";
	uint32_t ver;
	uint64_t ntensors, nkv;
	if (argc < 2 || !(f = fopen(argv[1], "rb"))) return 1;
	if (!rd(magic, 4) || memcmp(magic, "GGUF", 4) || !rd(&ver, 4) || ver < 2 ||
	    !rd(&ntensors, 8) || !rd(&nkv, 8))
		return 1;
	while (nkv--) {
		uint32_t t;
		if (!rd_str(key, sizeof(key)) || !rd(&t, 4)) break;
		char *dst = !strcmp(key, "general.name") ? name :
			    !strcmp(key, "general.basename") ? base :
			    !strcmp(key, "general.size_label") ? size : NULL;
		size_t cap = dst == name ? sizeof(name) : dst == base ? sizeof(base) : sizeof(size);
		if (dst && t == T_STRING) {
			if (!rd_str(dst, cap)) break;
		} else if (!skip(t)) {
			break;
		}
	}
	if (*base && (!*name || strlen(name) > 28))
		printf("%s%s%s\n", base, *size ? " " : "", size);
	else
		printf("%s\n", *name ? name : "model");
	return 0;
}
