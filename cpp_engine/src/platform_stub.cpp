// Fallback GUI entry point when SDL2 is not available at build time.
// The headless simulation, renderer and tests still build and run;
// only the interactive window needs SDL2.
#include "platform_gui.hpp"

#ifndef HAVE_SDL2

#include <iostream>

int runGui() {
    std::cout << "The interactive GUI needs SDL2, which was not found at build time.\n"
                 "Install SDL2 (e.g. `brew install sdl2`) and rebuild, or use the\n"
                 "headless modes, which need no display:\n"
                 "  ./checkers --simulate chess e4 e5 Nf3\n"
                 "  ./checkers --file moves.txt\n"
                 "  ./checkers --render out.ppm chess\n"
                 "  ./run_tests\n";
    return 1;
}

int runGuiSmoke(int) {
    std::cout << "GUI smoke test needs SDL2 (see runGui message above).\n";
    return 1;
}

#endif
