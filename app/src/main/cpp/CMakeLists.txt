cmake_minimum_required(VERSION 3.22.1)
project(picofacetracking C)

add_library(sharedbuffer SHARED shared_buffer.c)
target_compile_options(sharedbuffer PRIVATE -Wall -Wextra -Werror)
