// GUI entry point implemented by the platform layer. With SDL2
// available this opens a window and runs the App event loop;
// otherwise it prints a message and returns 1.
#pragma once

int runGui();
// Runs the SDL2 window for `seconds` (processing events and presenting
// frames) then exits 0. Used for automated smoke-testing of the full
// platform path; without SDL2 it prints the same message as runGui().
int runGuiSmoke(int seconds);
