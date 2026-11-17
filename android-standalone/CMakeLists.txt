cmake_minimum_required(VERSION 3.22)
project(agentllama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(agentllama SHARED jni/agent_llama.cpp)
target_include_directories(agentllama PRIVATE llama.cpp/include llama.cpp/ggml/include)
target_link_libraries(agentllama PRIVATE llama android log)
