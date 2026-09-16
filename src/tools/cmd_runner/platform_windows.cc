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

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <algorithm>
#include <filesystem>
#include <string>
#include <vector>

#include "src/tools/cmd_runner/platform.h"

namespace cmd_runner {

namespace {

HANDLE ToHandle(Fd fd) { return reinterpret_cast<HANDLE>(fd); }
Fd FromHandle(HANDLE h) { return reinterpret_cast<Fd>(h); }

std::wstring ToWide(const std::string& s) {
  if (s.empty()) {
    return std::wstring();
  }
  int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                              nullptr, 0);
  std::wstring result(n, L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                      &result[0], n);
  return result;
}

std::string FromWide(const std::wstring& s) {
  if (s.empty()) {
    return std::string();
  }
  int n = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                              nullptr, 0, nullptr, nullptr);
  std::string result(n, '\0');
  WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                      &result[0], n, nullptr, nullptr);
  return result;
}

std::string LastErrorString() {
  DWORD err = GetLastError();
  LPWSTR buf = nullptr;
  DWORD n = FormatMessageW(
      FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
          FORMAT_MESSAGE_IGNORE_INSERTS,
      nullptr, err, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
      reinterpret_cast<LPWSTR>(&buf), 0, nullptr);
  std::string result;
  if (n > 0 && buf != nullptr) {
    result = FromWide(std::wstring(buf, n));
    LocalFree(buf);
    while (!result.empty() &&
           (result.back() == '\n' || result.back() == '\r' ||
            result.back() == ' ')) {
      result.pop_back();
    }
  } else {
    result = "error " + std::to_string(err);
  }
  return result;
}

// Returns an absolute, backslash-separated path suitable for Win32 calls,
// including the \\?\ prefix for long paths.
std::wstring Win32Path(const std::string& path) {
  std::string abs = AbsolutePath(path);
  std::wstring wide = ToWide(abs);
  if (wide.size() >= MAX_PATH - 12 && wide.compare(0, 4, L"\\\\?\\") != 0 &&
      wide.compare(0, 2, L"\\\\") != 0) {
    wide = L"\\\\?\\" + wide;
  }
  return wide;
}

bool EnsureParentDirectory(const std::string& path, std::string* error) {
  std::filesystem::path parent = ToFsPath(path).parent_path();
  if (parent.empty()) {
    return true;
  }
  std::error_code ec;
  std::filesystem::create_directories(parent, ec);
  if (ec) {
    *error = "cannot create directory " + FromFsPath(parent) + ": " +
             ec.message();
    return false;
  }
  return true;
}

// Quotes a single argument following the rules of the Microsoft C runtime's
// command line parser.
std::wstring QuoteArg(const std::wstring& arg) {
  if (!arg.empty() && arg.find_first_of(L" \t\n\v\"") == std::wstring::npos) {
    return arg;
  }
  std::wstring result = L"\"";
  for (auto it = arg.begin();; ++it) {
    size_t backslashes = 0;
    while (it != arg.end() && *it == L'\\') {
      ++it;
      ++backslashes;
    }
    if (it == arg.end()) {
      result.append(backslashes * 2, L'\\');
      break;
    } else if (*it == L'"') {
      result.append(backslashes * 2 + 1, L'\\');
      result.push_back(*it);
    } else {
      result.append(backslashes, L'\\');
      result.push_back(*it);
    }
  }
  result.push_back(L'"');
  return result;
}

std::wstring BuildEnvironmentBlock(
    const std::vector<std::pair<std::string, std::string>>& overrides) {
  std::vector<std::pair<std::wstring, std::wstring>> entries;
  LPWCH block = GetEnvironmentStringsW();
  if (block != nullptr) {
    for (LPWCH p = block; *p != L'\0';) {
      std::wstring entry(p);
      p += entry.size() + 1;
      // Entries starting with '=' are special drive-relative directories.
      size_t eq = entry.find(L'=', entry.empty() || entry[0] == L'=' ? 1 : 0);
      if (eq == std::wstring::npos) {
        continue;
      }
      entries.emplace_back(entry.substr(0, eq), entry.substr(eq + 1));
    }
    FreeEnvironmentStringsW(block);
  }
  for (const auto& kv : overrides) {
    std::wstring key = ToWide(kv.first);
    std::wstring value = ToWide(kv.second);
    bool replaced = false;
    for (auto& entry : entries) {
      if (_wcsicmp(entry.first.c_str(), key.c_str()) == 0) {
        entry.second = value;
        replaced = true;
        break;
      }
    }
    if (!replaced) {
      entries.emplace_back(key, value);
    }
  }
  std::sort(entries.begin(), entries.end(),
            [](const std::pair<std::wstring, std::wstring>& a,
               const std::pair<std::wstring, std::wstring>& b) {
              return _wcsicmp(a.first.c_str(), b.first.c_str()) < 0;
            });
  std::wstring result;
  for (const auto& entry : entries) {
    result += entry.first;
    result += L'=';
    result += entry.second;
    result += L'\0';
  }
  result += L'\0';
  return result;
}

// Duplicates `fd` (or the runner's own standard handle if `fd` is invalid) as
// an inheritable handle.
HANDLE InheritableCopy(Fd fd, DWORD std_handle) {
  HANDLE source = fd == kInvalidFd ? GetStdHandle(std_handle) : ToHandle(fd);
  if (source == nullptr || source == INVALID_HANDLE_VALUE) {
    return INVALID_HANDLE_VALUE;
  }
  HANDLE result = INVALID_HANDLE_VALUE;
  if (!DuplicateHandle(GetCurrentProcess(), source, GetCurrentProcess(),
                       &result, 0, TRUE, DUPLICATE_SAME_ACCESS)) {
    return INVALID_HANDLE_VALUE;
  }
  return result;
}

}  // namespace

Fd StdinFd() { return FromHandle(GetStdHandle(STD_INPUT_HANDLE)); }
Fd StdoutFd() { return FromHandle(GetStdHandle(STD_OUTPUT_HANDLE)); }
Fd StderrFd() { return FromHandle(GetStdHandle(STD_ERROR_HANDLE)); }

int64_t FdRead(Fd fd, void* buf, size_t size) {
  HANDLE h = ToHandle(fd);
  if (h == nullptr || h == INVALID_HANDLE_VALUE) {
    return 0;
  }
  DWORD read = 0;
  if (!ReadFile(h, buf, static_cast<DWORD>(std::min<size_t>(size, 1 << 30)),
                &read, nullptr)) {
    DWORD err = GetLastError();
    if (err == ERROR_BROKEN_PIPE || err == ERROR_HANDLE_EOF) {
      return 0;
    }
    return -1;
  }
  return read;
}

bool FdWriteAll(Fd fd, const void* buf, size_t size) {
  HANDLE h = ToHandle(fd);
  const char* p = static_cast<const char*>(buf);
  while (size > 0) {
    DWORD written = 0;
    if (!WriteFile(h, p, static_cast<DWORD>(std::min<size_t>(size, 1 << 30)),
                   &written, nullptr)) {
      DWORD err = GetLastError();
      // The reader went away, e.g. `head` stopped consuming input.
      return err == ERROR_BROKEN_PIPE || err == ERROR_NO_DATA;
    }
    p += written;
    size -= written;
  }
  return true;
}

void FdClose(Fd fd) {
  if (fd != kInvalidFd) {
    CloseHandle(ToHandle(fd));
  }
}

bool MakePipe(Fd* read_end, Fd* write_end, std::string* error) {
  SECURITY_ATTRIBUTES sa;
  sa.nLength = sizeof(sa);
  sa.lpSecurityDescriptor = nullptr;
  sa.bInheritHandle = FALSE;
  HANDLE r = nullptr;
  HANDLE w = nullptr;
  if (!CreatePipe(&r, &w, &sa, 0)) {
    *error = "cannot create pipe: " + LastErrorString();
    return false;
  }
  *read_end = FromHandle(r);
  *write_end = FromHandle(w);
  return true;
}

Fd OpenForRead(const std::string& path, std::string* error) {
  HANDLE h = CreateFileW(Win32Path(path).c_str(), GENERIC_READ,
                         FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    *error = "cannot open " + path + " for reading: " + LastErrorString();
    return kInvalidFd;
  }
  return FromHandle(h);
}

Fd OpenForWrite(const std::string& path, bool append, std::string* error) {
  if (!EnsureParentDirectory(path, error)) {
    return kInvalidFd;
  }
  HANDLE h = CreateFileW(Win32Path(path).c_str(),
                         append ? FILE_APPEND_DATA : GENERIC_WRITE,
                         FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         nullptr, append ? OPEN_ALWAYS : CREATE_ALWAYS,
                         FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    *error = "cannot open " + path + " for writing: " + LastErrorString();
    return kInvalidFd;
  }
  return FromHandle(h);
}

bool SpawnProcess(const SpawnOptions& options, ProcessHandle* handle,
                  std::string* error) {
  std::wstring application;
  std::wstring command_line;
  if (options.search_path) {
    command_line = QuoteArg(ToWide(options.program));
  } else {
    std::string program = AbsolutePath(options.program);
    std::error_code ec;
    if (!std::filesystem::exists(ToFsPath(program), ec) &&
        std::filesystem::exists(ToFsPath(program + ".exe"), ec)) {
      program += ".exe";
    }
    application = Win32Path(program);
    command_line = QuoteArg(application);
  }
  for (const std::string& arg : options.args) {
    command_line += L' ';
    command_line += QuoteArg(ToWide(arg));
  }

  HANDLE std_handles[3] = {InheritableCopy(options.stdin_fd, STD_INPUT_HANDLE),
                           InheritableCopy(options.stdout_fd, STD_OUTPUT_HANDLE),
                           InheritableCopy(options.stderr_fd, STD_ERROR_HANDLE)};
  std::vector<HANDLE> inherited;
  for (HANDLE h : std_handles) {
    if (h != INVALID_HANDLE_VALUE) {
      inherited.push_back(h);
    }
  }

  SIZE_T attr_size = 0;
  InitializeProcThreadAttributeList(nullptr, 1, 0, &attr_size);
  std::vector<char> attr_buffer(attr_size);
  LPPROC_THREAD_ATTRIBUTE_LIST attrs =
      reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attr_buffer.data());
  bool ok = InitializeProcThreadAttributeList(attrs, 1, 0, &attr_size) != 0;
  if (ok && !inherited.empty()) {
    ok = UpdateProcThreadAttribute(attrs, 0,
                                   PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                   inherited.data(),
                                   inherited.size() * sizeof(HANDLE), nullptr,
                                   nullptr) != 0;
  }
  if (!ok) {
    *error = "cannot set up process attributes: " + LastErrorString();
    for (HANDLE h : inherited) {
      CloseHandle(h);
    }
    return false;
  }

  STARTUPINFOEXW si;
  ZeroMemory(&si, sizeof(si));
  si.StartupInfo.cb = sizeof(si);
  si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
  si.StartupInfo.hStdInput = std_handles[0];
  si.StartupInfo.hStdOutput = std_handles[1];
  si.StartupInfo.hStdError = std_handles[2];
  si.lpAttributeList = attrs;

  std::wstring env_block = BuildEnvironmentBlock(options.env);
  std::wstring cwd = options.cwd.empty() ? std::wstring() : Win32Path(options.cwd);

  PROCESS_INFORMATION pi;
  ZeroMemory(&pi, sizeof(pi));
  // CreateProcessW may modify the command line buffer.
  std::vector<wchar_t> command_line_buffer(command_line.begin(),
                                           command_line.end());
  command_line_buffer.push_back(L'\0');
  BOOL created = CreateProcessW(
      application.empty() ? nullptr : application.c_str(),
      command_line_buffer.data(), nullptr, nullptr, /* bInheritHandles= */ TRUE,
      CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT,
      const_cast<wchar_t*>(env_block.c_str()),
      cwd.empty() ? nullptr : cwd.c_str(), &si.StartupInfo, &pi);
  std::string create_error = created ? "" : LastErrorString();
  DeleteProcThreadAttributeList(attrs);
  for (HANDLE h : inherited) {
    CloseHandle(h);
  }
  if (!created) {
    *error = "cannot execute " + options.program + ": " + create_error;
    return false;
  }
  CloseHandle(pi.hThread);
  handle->process = reinterpret_cast<intptr_t>(pi.hProcess);
  return true;
}

void PlatformInit() {}

bool ExitedDueToBrokenPipe(int) { return false; }

int WaitProcess(const ProcessHandle& handle) {
  HANDLE h = reinterpret_cast<HANDLE>(handle.process);
  WaitForSingleObject(h, INFINITE);
  DWORD code = 127;
  GetExitCodeProcess(h, &code);
  CloseHandle(h);
  return static_cast<int>(code);
}

std::string NativePath(const std::string& path) {
  std::string result = path;
  std::replace(result.begin(), result.end(), '/', '\\');
  return result;
}

std::string AbsolutePath(const std::string& path) {
  std::error_code ec;
  std::filesystem::path abs = std::filesystem::absolute(ToFsPath(path), ec);
  if (ec) {
    return NativePath(path);
  }
  return FromFsPath(abs.lexically_normal().make_preferred());
}

std::filesystem::path ToFsPath(const std::string& path) {
  return std::filesystem::path(ToWide(path));
}

std::string FromFsPath(const std::filesystem::path& path) {
  return FromWide(path.wstring());
}

bool ChangeMode(const std::string& path, const std::string& mode,
                std::string* error) {
  // Windows only knows a read-only bit; every other mode change is a no-op.
  std::wstring wide = Win32Path(path);
  DWORD attrs = GetFileAttributesW(wide.c_str());
  if (attrs == INVALID_FILE_ATTRIBUTES) {
    *error = "cannot stat " + path + ": " + LastErrorString();
    return false;
  }
  bool has_w = mode.find('w') != std::string::npos;
  if (mode.empty()) {
    *error = "invalid mode: " + mode;
    return false;
  }
  DWORD new_attrs = attrs;
  if (mode[0] == '-' && has_w) {
    new_attrs |= FILE_ATTRIBUTE_READONLY;
  } else if ((mode[0] == '+' && has_w) ||
             (mode[0] >= '0' && mode[0] <= '7' && ((mode[0] - '0') & 2) != 0)) {
    new_attrs &= ~FILE_ATTRIBUTE_READONLY;
  }
  if (new_attrs != attrs && !SetFileAttributesW(wide.c_str(), new_attrs)) {
    *error = "cannot chmod " + path + ": " + LastErrorString();
    return false;
  }
  return true;
}

}  // namespace cmd_runner
