cmake_minimum_required(VERSION 3.22.1)
project(base_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(base_core SHARED
        timeline_engine.cpp
        jni_bridge.cpp)

find_library(log-lib log)
# Следующий этап (нативный рендер / декод): mediandk, android, EGL, GLESv3
target_link_libraries(base_core ${log-lib})
