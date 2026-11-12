# Checkers & Chess Suite - C++ engine (cpp_engine).
# Zero mandatory dependencies: a C++17 compiler, make and pthreads build
# the headless simulation CLI and the full test suite. SDL2 is OPTIONAL:
# when found, the interactive GUI (window + audio) is enabled; otherwise
# a stub message binary is built and every headless path still works.
CXX ?= c++
STD = -std=c++17
WARN = -Wall -Wextra
OPT = -O2
PTHREAD = -pthread

SRC_CORE = src/chess_piece.cpp src/chess_move.cpp src/checkers_move.cpp \
	src/checkers_engine.cpp src/chess_engine_moves.cpp src/chess_engine_rules.cpp \
	src/game_controller.cpp src/audio.cpp src/headless.cpp src/net.cpp \
	src/graphics_settings.cpp src/scene_builder.cpp src/font.cpp \
	src/ui_overlay.cpp src/app_core.cpp src/app_screens.cpp src/app_game.cpp
SRC_TESTS = tests/test_engines.cpp tests/test_simulation.cpp tests/test_tracer.cpp \
	tests/test_net.cpp tests/test_main.cpp

BUILD = build
OBJ_CORE = $(addprefix $(BUILD)/,$(SRC_CORE:.cpp=.o))
OBJ_TESTS = $(addprefix $(BUILD)/,$(SRC_TESTS:.cpp=.o))
OBJ_MAIN = $(BUILD)/src/main.o
OBJ_SDL = $(BUILD)/src/platform_sdl.o
OBJ_STUB = $(BUILD)/src/platform_stub.o

# ---- SDL2 auto-detection -------------------------------------------------
HAVE_SDL2 := 0
ifneq ($(shell pkg-config --exists sdl2 2>/dev/null && echo yes),)
  HAVE_SDL2 := 1
  SDL_CFLAGS := $(shell pkg-config --cflags sdl2)
  SDL_LIBS := $(shell pkg-config --libs sdl2)
else ifneq ($(shell command -v sdl2-config 2>/dev/null),)
  HAVE_SDL2 := 1
  SDL_CFLAGS := $(shell sdl2-config --cflags)
  SDL_LIBS := $(shell sdl2-config --libs)
else ifneq ($(wildcard /opt/homebrew/include/SDL2/SDL.h),)
  HAVE_SDL2 := 1
  SDL_CFLAGS := -I/opt/homebrew/include/SDL2 -D_THREAD_SAFE
  SDL_LIBS := -L/opt/homebrew/lib -lSDL2
else ifneq ($(wildcard /usr/local/include/SDL2/SDL.h),)
  HAVE_SDL2 := 1
  SDL_CFLAGS := -I/usr/local/include/SDL2 -D_THREAD_SAFE
  SDL_LIBS := -L/usr/local/lib -lSDL2
else ifneq ($(wildcard /usr/include/SDL2/SDL.h),)
  HAVE_SDL2 := 1
  SDL_CFLAGS := -D_THREAD_SAFE
  SDL_LIBS := -lSDL2
endif

ifeq ($(HAVE_SDL2),1)
  PLATFORM_OBJ = $(OBJ_SDL)
  PLATFORM_FLAGS = -DHAVE_SDL2 $(SDL_CFLAGS)
  PLATFORM_LIBS = $(SDL_LIBS)
  SDL_STATUS = SDL2 GUI enabled
else
  PLATFORM_OBJ = $(OBJ_STUB)
  PLATFORM_FLAGS =
  PLATFORM_LIBS =
  SDL_STATUS = SDL2 not found - headless only (install SDL2 for the GUI)
endif

# ---- targets -------------------------------------------------------------
all: build test

build: checkers run_tests

checkers: $(OBJ_CORE) $(OBJ_MAIN) $(PLATFORM_OBJ)
	$(CXX) $(STD) $(OPT) -o $@ $(OBJ_CORE) $(OBJ_MAIN) $(PLATFORM_OBJ) $(PTHREAD) $(PLATFORM_LIBS)

run_tests: $(OBJ_CORE) $(OBJ_TESTS)
	$(CXX) $(STD) $(OPT) -o $@ $(OBJ_CORE) $(OBJ_TESTS) $(PTHREAD)

$(BUILD)/%.o: %.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(STD) $(WARN) $(OPT) $(PTHREAD) $(PLATFORM_FLAGS) -MMD -MP -c $< -o $@

$(BUILD)/src/platform_sdl.o: src/platform_sdl.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(STD) $(WARN) $(OPT) $(PTHREAD) $(PLATFORM_FLAGS) -MMD -MP -c $< -o $@

-include $(OBJ_CORE:.o=.d) $(OBJ_TESTS:.o=.d) $(OBJ_MAIN:.o=.d) $(PLATFORM_OBJ:.o=.d)

test: run_tests
	@echo "--- $(SDL_STATUS) ---"
	./run_tests
	@echo "--- headless CLI smoke checks ---"
	./checkers --simulate chess f3 e5 g4 Qh4#
	./checkers --simulate checkers b6-a5 c3-b4 a5xc3 b2xd4
	./checkers --file examples/fools_mate.txt

clean:
	rm -rf $(BUILD) checkers run_tests

sdl-status:
	@echo "$(SDL_STATUS)"

.PHONY: all build test clean sdl-status
