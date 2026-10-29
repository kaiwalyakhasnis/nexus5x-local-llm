# Models and speed

Measured on the phone, answers of 200-400 tokens, 6 threads (all cores):

| Model (Ollama name) | File | Generation | Context | Notes |
|---|---|---|---|---|
| Qwen2.5 Coder 0.5B (`qwen2.5-coder:0.5b`) | 398 MB | ~9.5 tok/s, ~8 sustained | 32K | good for short code snippets |
| Qwen2.5 0.5B (`qwen2.5:0.5b`) | 398 MB | 10.3 tok/s (llama-bench) | 32K | general chat |
| Google Gemma 3 1B (`gemma3:1b`) | 806 MB | ~5.3 tok/s, prompt 11 tok/s | 32K | best quality that fits; the voice demo |

Models up to about **1.25 GB** fit (the app greys out bigger ones):
`gemma3:270m`, `qwen3:0.6b`, `qwen2.5-coder:1.5b`, `llama3.2:1b` in Q4_K_M,
`smollm2:360m` in Q8_0... Gemma 3n and Gemma 4 (3.5 GB and up) don't.

**Context window**: the server gets the context length the model was
trained with, up to **32K tokens** (`CTX_MAX` in
[`rootfs/etc/llm.conf`](../rootfs/etc/llm.conf); `CTX=` overrides it). 32K is
about 24,000 words. Gemma 3 1B fits it easily: its sliding-window layers
keep the cache small (about 250 MB at 32K). A long input is slow, though:
the phone reads about 7-11 tokens a second, so this project's README of the time (~4,000 tokens)
took 9.5 minutes to read before Gemma summarized it, and answers slow to
about 3 tok/s with that much in the context. Follow-up questions about the
same text are fast: llama.cpp keeps what it has read. Models whose cache
grows with every layer need much more RAM for the same context: at 32K the
cache alone would be ~3.7 GB for Qwen3 0.6B and ~1 GB for Llama 3.2 1B,
too much next to the model; set a smaller `CTX` for them (8192 fits).

**Heat**: under full load the A57 cores reach their 100 °C limit within a
second or two, and the stock thermal driver holds them at 768 MHz (instead of
1824) until they cool; the A53s stay at full speed. The numbers above are
with this protection on, and it stays on: the Nexus 5X is known for
heat-related failures. A bigger model doesn't make it hotter, only slower.
