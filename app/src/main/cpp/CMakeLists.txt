cmake_minimum_required(VERSION 3.22.1)
project("llmceiling" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is vendored unmodified (git submodule) at <repo>/third_party/llama.cpp
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Same choices as upstream examples/llama.android/lib/src/main/cpp/CMakeLists.txt
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(llmceiling SHARED engine.cpp llm_jni.cpp)
target_include_directories(llmceiling PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(llmceiling llama ggml android log)
