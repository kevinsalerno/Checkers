# Optional CMake build (the supported build is `make`). Same sources.
cmake_minimum_required(VERSION 3.14)
project(checkers_cpp_engine CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE
  src/chess_piece.cpp src/chess_move.cpp src/checkers_move.cpp
  src/checkers_engine.cpp src/chess_engine_moves.cpp src/chess_engine_rules.cpp
  src/game_controller.cpp src/audio.cpp src/headless.cpp src/net.cpp
  src/graphics_settings.cpp src/scene_builder.cpp src/font.cpp
  src/ui_overlay.cpp src/app_core.cpp src/app_screens.cpp src/app_game.cpp)

find_package(PkgConfig QUIET)
if(PkgConfig)
  pkg_check_modules(SDL2 QUIET sdl2)
endif()
if(NOT SDL2_FOUND)
  find_path(SDL2_INCLUDE_DIRS SDL.h PATH_SUFFIXES SDL2)
  find_library(SDL2_LIBRARIES SDL2)
  if(SDL2_INCLUDE_DIRS AND SDL2_LIBRARIES)
    set(SDL2_FOUND TRUE)
  endif()
endif()

if(SDL2_FOUND)
  add_definitions(-DHAVE_SDL2)
  add_executable(checkers src/main.cpp src/platform_sdl.cpp ${CORE})
  target_include_directories(checkers PRIVATE ${SDL2_INCLUDE_DIRS})
  target_link_libraries(checkers PRIVATE ${SDL2_LIBRARIES})
else()
  message(STATUS "SDL2 not found - building headless binary with GUI stub")
  add_executable(checkers src/main.cpp src/platform_stub.cpp ${CORE})
endif()

find_package(Threads REQUIRED)
target_link_libraries(checkers PRIVATE Threads::Threads)
if(WIN32)
  target_link_libraries(checkers PRIVATE ws2_32)
endif()

add_executable(run_tests
  tests/test_engines.cpp tests/test_simulation.cpp tests/test_tracer.cpp
  tests/test_net.cpp tests/test_main.cpp ${CORE})
target_link_libraries(run_tests PRIVATE Threads::Threads)
if(WIN32)
  target_link_libraries(run_tests PRIVATE ws2_32)
endif()
