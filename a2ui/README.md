# a2ui: ask the phone, the model draws the answer

Hold **VOLUME UP** and ask a question. Whisper turns it into text, a small
language model on the same phone writes the answer as a screen, and
[A2UI](https://a2ui.org) (Google's Agent-to-User Interface protocol, v0.9)
carries it to the toolkit, which draws it with Material 3 components. Under
the answer come 2-4 questions you might ask next, as buttons; pressing one
continues the conversation on the same subject, until **Home**. Nothing
leaves the device.

It runs on this project's phone image in place of `llm-ui`, and talks to
its llama.cpp server (any server with llama.cpp's OpenAI-style API will
do: `A2UI_LLM=host:port`, an IP address; default `127.0.0.1:8080`). It is an
[embedded-compose](https://github.com/kaiwalyakhasnis/embedded-compose) app
(Jetpack Compose's runtime and a small toolkit on minui, with its Material 3
module), drawn by the
[embedded-compose-a2ui](https://github.com/kaiwalyakhasnis/embedded-compose-a2ui)
library.

```
VOL+ held: tinycap ─▶ whisper-cli ─▶ question ─▶ LocalAgent ── /v1/chat/completions, JSON schema ──▶ llama.cpp
                                                     │  the reply as it streams: compact screen JSON
                                                     ▼
                                createSurface / updateComponents / updateDataModel (A2UI v0.9)
                                                     ▼
        A2uiClient (embedded-compose-a2ui) ── basic catalog as Material 3 (embedded-compose) ──▶ screen
                                                     │  a next question, or a control's value
                                                     ▼
                                   action ──▶ LocalAgent: the next question, with the values ──▶ next screen
```

## The pieces

- **[embedded-compose-a2ui](https://github.com/kaiwalyakhasnis/embedded-compose-a2ui)**
  (package `ui.a2ui`, a library of its own): the A2UI client. It takes v0.9 messages (`createSurface`, `updateComponents`,
  `updateDataModel`, `deleteSurface`), keeps each surface's components and
  data model as snapshot state, and renders the basic catalog with
  embedded-compose's `core/material`: `Text` (in Material's type scale),
  `Icon`, `Row`, `Column`, `List`, `Card`, `Divider`, `Button` (filled,
  tonal or text), `CheckBox`, `ChoicePicker` (filter chips), `Slider`,
  `Tabs`, `Modal`, `TextField` and `DateTimeInput` (shown, not edited: no
  keyboard), `Image`, `Video` and `AudioPlayer` (placeholders: nothing is
  downloaded). A surface's `theme.primaryColor` seeds its color scheme.
  Data binding (`{"path": ...}`, relative inside list templates), two-way
  binding for the controls, functions (`formatString`, `formatNumber`,
  `formatCurrency`, `pluralize`, `required`, `email`, `length`, `numeric`,
  `regex`, `and`, `or`, `not`), button `checks`, `action` events with their
  context, and `error` messages for what it can't take. Its own JSON parser
  keeps it small (the app is a 3.9 MB static binary).
- **`LocalAgent`**: the agent, on the device. It asks the model for a
  *compact screen*: a title, content (cards, text, rows with icons, and
  controls such as a choice or a slider when the user should pick
  something), and the next questions. It turns that into real A2UI as it
  streams: parts appear while the model is still writing. Small models keep
  nesting straight much better than A2UI's flat list of ids, and the compact
  form costs fewer tokens. It keeps the first question and the last few
  answers, so follow-ups stay on the subject.
- **The grammar**: every request carries a JSON schema, which llama.cpp
  turns into a grammar, so every model writes valid screens and always
  suggests next questions.
- **Voice** (`Voice.kt`): push-to-talk with nexus5x-local-llm's voice pack
  (`/tmp/voice`: tinycap, whisper-cli, Whisper tiny.en) and its mixer
  scripts. The tools run as their own processes, so the screen keeps
  drawing while Whisper works.
- **The link port** (8082): JSON lines both ways, for other programs.
  Send a question (`{"prompt": "..."}` or plain text), `home`, or A2UI
  messages of your own (a whole agent elsewhere can draw on this screen).
  Everything the screen shows and every action comes back out:

  ```sh
  nc 172.16.42.1 8082                                # watch the A2UI stream
  echo 'help me choose a baby cradle' | nc -N 172.16.42.1 8082
  ```

## Keys

The phone has no touch here (this project's kernel leaves it out): hold
**VOLUME UP** to talk, **VOLUME DOWN** reads through the answer a page at a
time and then moves between the buttons (after the last one, back to the
top), **POWER** presses.

## Building and running

From the top of this repo, with embedded-compose and embedded-compose-a2ui
checked out next to it (or `EMBEDDED_COMPOSE=` and `EC_A2UI=` in
`local.env`), the phone booted with a model and the voice pack loaded:

```sh
scripts/build-a2ui.sh          # APP_DIR=a2ui ./ec binary nexus5x, in embedded-compose
scripts/a2ui-phone.sh          # into the phone's RAM, in place of llm-ui
scripts/a2ui-phone.sh --back   # llm-ui again
```

The binary is one static executable (3.9 MB); it also needs Roboto in
`/res/fonts` and minui's font in `/res/images`, which `a2ui-phone.sh`
copies over. Its log is `/tmp/a2ui.log` on the phone.
