cmake_minimum_required(VERSION 3.22.1)
project(hymt2_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 预编译 llama.cpp 引擎库（f8b355a9e），提交在 jniLibs/arm64-v8a
set(JNI_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/arm64-v8a)

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${JNI_LIB_DIR}/libllama.so)

# ggml_threadpool_new/free 在 libggml-cpu.so，params_default 在 libggml-base.so——hymt2 直接调用需显式链接
add_library(ggmlbase SHARED IMPORTED)
set_target_properties(ggmlbase PROPERTIES IMPORTED_LOCATION ${JNI_LIB_DIR}/libggml-base.so)
add_library(ggmlcpu SHARED IMPORTED)
set_target_properties(ggmlcpu PROPERTIES IMPORTED_LOCATION ${JNI_LIB_DIR}/libggml-cpu.so)

add_library(hymt2 SHARED hymt2_bridge.cpp)
target_include_directories(hymt2 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(hymt2 PRIVATE llama ggmlbase ggmlcpu android log)
