// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Thin platform abstraction used by cmd_runner. Everything that differs between
// POSIX and Windows (file descriptors, pipes, process creation) lives behind
// this interface so that the interpreter itself is platform independent.

#ifndef BAZEL_SRC_TOOLS_CMD_RUNNER_PLATFORM_H_
#define BAZEL_SRC_TOOLS_CMD_RUNNER_PLATFORM_H_

#include <cstdint>
#include <filesystem>
#include <string>
#include <utility>
#include <vector>

namespace cmd_runner {

#ifdef _WIN32
// A HANDLE, stored as an integer so that this header stays free of windows.h.
typedef intptr_t Fd;
constexpr Fd kInvalidFd = -1;
#else
typedef int Fd;
constexpr Fd kInvalidFd = -1;
#endif

// Performs process-wide setup; must be called once at startup.
void PlatformInit();

// Returns whether an exit code reported by WaitProcess means that the process
// was killed because it wrote to a pipe whose reader had gone away.
bool ExitedDueToBrokenPipe(int code);

// Returns the three standard streams of the runner process itself.
Fd StdinFd();
Fd StdoutFd();
Fd StderrFd();

// Reads up to `size` bytes. Returns the number of bytes read, 0 on EOF and -1
// on error.
int64_t FdRead(Fd fd, void* buf, size_t size);
// Writes all bytes. Returns false on error.
bool FdWriteAll(Fd fd, const void* buf, size_t size);
void FdClose(Fd fd);

// Creates a pipe. Neither end is inherited by child processes unless it is
// passed explicitly to SpawnProcess.
bool MakePipe(Fd* read_end, Fd* write_end, std::string* error);

// Opens a file for reading. Returns kInvalidFd on error and sets `error`.
Fd OpenForRead(const std::string& path, std::string* error);
// Opens a file for writing, truncating or appending. Parent directories are
// created on demand.
Fd OpenForWrite(const std::string& path, bool append, std::string* error);

struct ProcessHandle {
#ifdef _WIN32
  intptr_t process = 0;
#else
  int pid = -1;
#endif
};

struct SpawnOptions {
  // The program to run. If `search_path` is true it is looked up in PATH,
  // otherwise it is a path relative to the current working directory of the
  // runner (or absolute).
  std::string program;
  bool search_path = false;
  std::vector<std::string> args;  // Excluding argv[0].
  // Additional environment variables layered on top of the runner's own.
  std::vector<std::pair<std::string, std::string>> env;
  // Working directory, empty for the runner's own.
  std::string cwd;
  // Standard streams for the child. kInvalidFd means "inherit from runner".
  Fd stdin_fd = kInvalidFd;
  Fd stdout_fd = kInvalidFd;
  Fd stderr_fd = kInvalidFd;
};

// Starts a child process. Returns false and sets `error` on failure.
bool SpawnProcess(const SpawnOptions& options, ProcessHandle* handle,
                  std::string* error);
// Waits for the process and returns its exit code (128 + signal on POSIX if
// killed by a signal).
int WaitProcess(const ProcessHandle& handle);

// Converts a path from the script (which always uses forward slashes) to the
// native representation.
std::string NativePath(const std::string& path);
// Returns an absolute version of `path`, resolved against the runner's working
// directory.
std::string AbsolutePath(const std::string& path);

// Converts between UTF-8 strings and std::filesystem paths.
std::filesystem::path ToFsPath(const std::string& path);
std::string FromFsPath(const std::filesystem::path& path);

// Applies a chmod-style mode ("+x", "-w", "755", ...) to `path`.
bool ChangeMode(const std::string& path, const std::string& mode,
                std::string* error);

}  // namespace cmd_runner

#endif  // BAZEL_SRC_TOOLS_CMD_RUNNER_PLATFORM_H_
