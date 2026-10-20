// Push-to-talk voice chat on the phone itself (see voice.cpp).
#pragma once
#include <string>

struct VoiceView {
  bool available = false;  // voice pack installed and audio up (/run/audio-ready)
  std::string phase;       // "", "listening", "transcribing", "thinking", "speaking", or a message
  std::string question;    // what Whisper heard
  std::string answer;      // the model's answer so far
  bool busy = false;
  bool sound = false;           // answers are spoken (volume-up toggles it)
  int turn = 0;                 // this question's place in the conversation, from 1
  int ctx_used = 0, ctx_max = 0;  // context tokens the conversation fills, of the server's window
  bool new_after_full = false;  // this question started a new conversation: the last one was full
};

void voice_init();
void voice_press();    // volume-down pressed: start listening, or stop a running answer
void voice_release();  // volume-down released: transcribe, ask the model, speak the answer
void voice_set_sound(bool on);  // off after boot: answers are only shown
VoiceView voice_view();
