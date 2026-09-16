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

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

#include "src/tools/cmd_runner/platform.h"

extern char** environ;

namespace cmd_runner {

namespace {

std::string ErrnoString() { return std::string(strerror(errno)); }

void SetCloexec(int fd) {
  int flags = fcntl(fd, F_GETFD);
  if (flags >= 0) {
    fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
  }
}

bool EnsureParentDirectory(const std::string& path, std::string* error) {
  std::filesystem::path parent = std::filesystem::path(path).parent_path();
  if (parent.empty()) {
    return true;
  }
  std::error_code ec;
  std::filesystem::create_directories(parent, ec);
  if (ec) {
    *error = "cannot create directory " + parent.string() + ": " + ec.message();
    return false;
  }
  return true;
}

}  // namespace

Fd StdinFd() { return STDIN_FILENO; }
Fd StdoutFd() { return STDOUT_FILENO; }
Fd StderrFd() { return STDERR_FILENO; }

int64_t FdRead(Fd fd, void* buf, size_t size) {
  while (true) {
    ssize_t n = read(fd, buf, size);
    if (n < 0 && errno == EINTR) {
      continue;
    }
    return n;
  }
}

bool FdWriteAll(Fd fd, const void* buf, size_t size) {
  const char* p = static_cast<const char*>(buf);
  while (size > 0) {
    ssize_t n = write(fd, p, size);
    if (n < 0) {
      if (errno == EINTR) {
        continue;
      }
      // The reader went away, e.g. `head` stopped consuming input. Treat this
      // as success, like a shell would.
      return errno == EPIPE;
    }
    p += n;
    size -= n;
  }
  return true;
}

void FdClose(Fd fd) {
  if (fd >= 0) {
    close(fd);
  }
}

bool MakePipe(Fd* read_end, Fd* write_end, std::string* error) {
  int fds[2];
  if (pipe(fds) != 0) {
    *error = "cannot create pipe: " + ErrnoString();
    return false;
  }
  SetCloexec(fds[0]);
  SetCloexec(fds[1]);
  *read_end = fds[0];
  *write_end = fds[1];
  return true;
}

Fd OpenForRead(const std::string& path, std::string* error) {
  int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    *error = "cannot open " + path + " for reading: " + ErrnoString();
    return kInvalidFd;
  }
  return fd;
}

Fd OpenForWrite(const std::string& path, bool append, std::string* error) {
  if (!EnsureParentDirectory(path, error)) {
    return kInvalidFd;
  }
  int flags = O_WRONLY | O_CREAT | O_CLOEXEC | (append ? O_APPEND : O_TRUNC);
  int fd = open(path.c_str(), flags, 0666);
  if (fd < 0) {
    *error = "cannot open " + path + " for writing: " + ErrnoString();
    return kInvalidFd;
  }
  return fd;
}

bool SpawnProcess(const SpawnOptions& options, ProcessHandle* handle,
                  std::string* error) {
  // Build argv and envp before forking: the child must only call
  // async-signal-safe functions.
  std::string program = options.program;
  if (!options.search_path && !options.cwd.empty()) {
    program = AbsolutePath(program);
  }
  std::vector<std::string> arg_storage;
  arg_storage.reserve(options.args.size() + 1);
  arg_storage.push_back(program);
  for (const std::string& arg : options.args) {
    arg_storage.push_back(arg);
  }
  std::vector<char*> argv;
  for (std::string& arg : arg_storage) {
    argv.push_back(&arg[0]);
  }
  argv.push_back(nullptr);

  std::vector<std::string> env_storage;
  for (char** e = environ; e != nullptr && *e != nullptr; ++e) {
    std::string entry(*e);
    bool overridden = false;
    for (const auto& kv : options.env) {
      if (entry.size() > kv.first.size() &&
          entry.compare(0, kv.first.size(), kv.first) == 0 &&
          entry[kv.first.size()] == '=') {
        overridden = true;
        break;
      }
    }
    if (!overridden) {
      env_storage.push_back(entry);
    }
  }
  for (const auto& kv : options.env) {
    env_storage.push_back(kv.first + "=" + kv.second);
  }
  std::vector<char*> envp;
  for (std::string& entry : env_storage) {
    envp.push_back(&entry[0]);
  }
  envp.push_back(nullptr);

  // A CLOEXEC pipe through which the child reports exec failures.
  int err_pipe[2];
  if (pipe(err_pipe) != 0) {
    *error = "cannot create pipe: " + ErrnoString();
    return false;
  }
  SetCloexec(err_pipe[0]);
  SetCloexec(err_pipe[1]);

  pid_t pid = fork();
  if (pid < 0) {
    *error = "fork failed: " + ErrnoString();
    close(err_pipe[0]);
    close(err_pipe[1]);
    return false;
  }
  if (pid == 0) {
    // Child. Restore the default SIGPIPE disposition, which the runner itself
    // ignores in order to detect closed pipes via EPIPE.
    signal(SIGPIPE, SIG_DFL);
    if (options.stdin_fd != kInvalidFd) {
      dup2(options.stdin_fd, STDIN_FILENO);
    }
    if (options.stdout_fd != kInvalidFd) {
      dup2(options.stdout_fd, STDOUT_FILENO);
    }
    if (options.stderr_fd != kInvalidFd) {
      dup2(options.stderr_fd, STDERR_FILENO);
    }
    if (!options.cwd.empty() && chdir(options.cwd.c_str()) != 0) {
      int err = errno;
      char tag = 'c';
      (void)!write(err_pipe[1], &tag, 1);
      (void)!write(err_pipe[1], &err, sizeof(err));
      _exit(127);
    }
    environ = envp.data();
    if (options.search_path) {
      execvp(argv[0], argv.data());
    } else {
      execv(argv[0], argv.data());
    }
    int err = errno;
    char tag = 'e';
    (void)!write(err_pipe[1], &tag, 1);
    (void)!write(err_pipe[1], &err, sizeof(err));
    _exit(127);
  }

  close(err_pipe[1]);
  char tag = 0;
  int child_errno = 0;
  ssize_t n = read(err_pipe[0], &tag, 1);
  if (n == 1) {
    (void)!read(err_pipe[0], &child_errno, sizeof(child_errno));
  }
  close(err_pipe[0]);
  if (n == 1) {
    int status;
    waitpid(pid, &status, 0);
    if (tag == 'c') {
      *error = "cannot change directory to " + options.cwd + ": " +
               strerror(child_errno);
    } else {
      *error = "cannot execute " + program + ": " + strerror(child_errno);
    }
    return false;
  }
  handle->pid = pid;
  return true;
}

void PlatformInit() { signal(SIGPIPE, SIG_IGN); }

bool ExitedDueToBrokenPipe(int code) { return code == 128 + SIGPIPE; }

int WaitProcess(const ProcessHandle& handle) {
  int status = 0;
  while (waitpid(handle.pid, &status, 0) < 0) {
    if (errno != EINTR) {
      return 127;
    }
  }
  if (WIFEXITED(status)) {
    return WEXITSTATUS(status);
  }
  if (WIFSIGNALED(status)) {
    return 128 + WTERMSIG(status);
  }
  return 127;
}

std::string NativePath(const std::string& path) { return path; }

std::string AbsolutePath(const std::string& path) {
  std::error_code ec;
  std::filesystem::path abs = std::filesystem::absolute(path, ec);
  if (ec) {
    return path;
  }
  return abs.lexically_normal().string();
}

std::filesystem::path ToFsPath(const std::string& path) {
  return std::filesystem::path(path);
}

std::string FromFsPath(const std::filesystem::path& path) {
  return path.string();
}

bool ChangeMode(const std::string& path, const std::string& mode,
                std::string* error) {
  struct stat st;
  if (stat(path.c_str(), &st) != 0) {
    *error = "cannot stat " + path + ": " + ErrnoString();
    return false;
  }
  mode_t bits = st.st_mode & 07777;
  if (!mode.empty() && (mode[0] == '+' || mode[0] == '-' || mode[0] == '=')) {
    mode_t mask = 0;
    for (size_t i = 1; i < mode.size(); ++i) {
      switch (mode[i]) {
        case 'r':
          mask |= 0444;
          break;
        case 'w':
          mask |= 0222;
          break;
        case 'x':
          mask |= 0111;
          break;
        default:
          *error = "invalid mode: " + mode;
          return false;
      }
    }
    if (mode[0] == '+') {
      bits |= mask;
    } else if (mode[0] == '-') {
      bits &= ~mask;
    } else {
      bits = mask;
    }
  } else {
    char* end = nullptr;
    long parsed = strtol(mode.c_str(), &end, 8);
    if (mode.empty() || *end != '\0' || parsed < 0 || parsed > 07777) {
      *error = "invalid mode: " + mode;
      return false;
    }
    bits = static_cast<mode_t>(parsed);
  }
  if (chmod(path.c_str(), bits) != 0) {
    *error = "cannot chmod " + path + ": " + ErrnoString();
    return false;
  }
  return true;
}

}  // namespace cmd_runner
