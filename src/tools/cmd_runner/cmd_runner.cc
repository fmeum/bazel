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

// cmd_runner executes scripts produced by Bazel's `cmd` Starlark module (see
// ctx.actions.run_script). It is a deliberately small, dependency-free
// interpreter for a pipeline language: it knows how to run external programs
// with pipes and redirections and provides a set of portable builtins (cat,
// grep, replace, ...) so that actions do not need a shell or coreutils on the
// execution platform.
//
// The script is read from a params file (`cmd_runner @file`) with one token per
// line. Each line consists of a keyword, optionally followed by a space and a
// payload in which `\\`, `\n` and `\r` are escaped. The grammar is:
//
//   script      := "cmd_runner 1" element* "end"
//   element     := "cmd" body "end" | "pipe" ("cmd" body "end")+ "end"
//   body        := (program | "arg" S | "path" S | "sub" element | "env" K=V |
//                   "cwd" S | "stdin" S | "stdin_text" S | "stdout" S |
//                   "stdout_append" S | "stderr" S | "stderr_append" S |
//                   "stderr_to_stdout")*
//   program     := "tool" S | "prog" S | "builtin" S
//
// `tool` runs an executable at a path relative to the working directory,
// `prog` looks a program up in PATH and `builtin` names one of the builtins
// implemented below. `sub` embeds the standard output of another element as an
// argument (command substitution). `path` arguments are file paths that are
// made absolute when the command runs in a different working directory.

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <regex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "src/tools/cmd_runner/platform.h"

namespace cmd_runner {
namespace {

namespace fs = std::filesystem;

// ---------------------------------------------------------------------------
// Script representation and parsing.
// ---------------------------------------------------------------------------

struct Element;

struct Arg {
  std::string value;
  bool is_path = false;
  std::unique_ptr<Element> substitution;
};

struct Command {
  enum class Kind { kTool, kProgram, kBuiltin };
  Kind kind = Kind::kProgram;
  std::string program;
  std::vector<Arg> args;
  std::vector<std::pair<std::string, std::string>> env;
  std::string cwd;
  std::string stdin_path;
  bool has_stdin_text = false;
  std::string stdin_text;
  std::string stdout_path;
  bool stdout_append = false;
  std::string stderr_path;
  bool stderr_append = false;
  bool stderr_to_stdout = false;

  std::string Describe() const {
    std::string result = program;
    for (size_t i = 0; i < args.size() && i < 3; ++i) {
      result += " ";
      result += args[i].substitution ? "$(...)" : args[i].value;
    }
    if (args.size() > 3) {
      result += " ...";
    }
    return result;
  }
};

// A single command or a pipeline of commands.
struct Element {
  std::vector<Command> commands;
};

struct Script {
  std::vector<Element> steps;
};

struct Line {
  std::string keyword;
  std::string payload;
};

std::string Unescape(const std::string& s) {
  std::string result;
  result.reserve(s.size());
  for (size_t i = 0; i < s.size(); ++i) {
    if (s[i] == '\\' && i + 1 < s.size()) {
      ++i;
      switch (s[i]) {
        case 'n':
          result.push_back('\n');
          break;
        case 'r':
          result.push_back('\r');
          break;
        default:
          result.push_back(s[i]);
          break;
      }
    } else {
      result.push_back(s[i]);
    }
  }
  return result;
}

class Parser {
 public:
  explicit Parser(std::vector<Line> lines) : lines_(std::move(lines)) {}

  bool Parse(Script* script, std::string* error) {
    error_ = error;
    if (!Expect("cmd_runner") || lines_[pos_ - 1].payload != "1") {
      *error = "unsupported script format (expected 'cmd_runner 1')";
      return false;
    }
    while (!AtEnd() && Peek() != "end") {
      Element element;
      if (!ParseElement(&element)) {
        return false;
      }
      script->steps.push_back(std::move(element));
    }
    return Expect("end");
  }

 private:
  bool AtEnd() const { return pos_ >= lines_.size(); }
  const std::string& Peek() const { return lines_[pos_].keyword; }

  bool Expect(const std::string& keyword) {
    if (AtEnd()) {
      *error_ = "unexpected end of script, expected '" + keyword + "'";
      return false;
    }
    if (Peek() != keyword) {
      *error_ = "line " + std::to_string(pos_ + 1) + ": expected '" + keyword +
                "' but got '" + Peek() + "'";
      return false;
    }
    ++pos_;
    return true;
  }

  bool ParseElement(Element* element) {
    if (AtEnd()) {
      *error_ = "unexpected end of script";
      return false;
    }
    if (Peek() == "cmd") {
      Command command;
      if (!ParseCommand(&command)) {
        return false;
      }
      element->commands.push_back(std::move(command));
      return true;
    }
    if (!Expect("pipe")) {
      return false;
    }
    while (!AtEnd() && Peek() == "cmd") {
      Command command;
      if (!ParseCommand(&command)) {
        return false;
      }
      element->commands.push_back(std::move(command));
    }
    if (element->commands.empty()) {
      *error_ = "line " + std::to_string(pos_ + 1) + ": empty pipeline";
      return false;
    }
    return Expect("end");
  }

  bool ParseCommand(Command* command) {
    if (!Expect("cmd")) {
      return false;
    }
    bool has_program = false;
    while (!AtEnd() && Peek() != "end") {
      const Line& line = lines_[pos_];
      const std::string& kw = line.keyword;
      const std::string payload = Unescape(line.payload);
      ++pos_;
      if (kw == "tool" || kw == "prog" || kw == "builtin") {
        command->kind = kw == "tool"      ? Command::Kind::kTool
                        : kw == "prog"    ? Command::Kind::kProgram
                                          : Command::Kind::kBuiltin;
        command->program = payload;
        has_program = true;
      } else if (kw == "arg" || kw == "path") {
        Arg arg;
        arg.value = payload;
        arg.is_path = kw == "path";
        command->args.push_back(std::move(arg));
      } else if (kw == "sub") {
        Arg arg;
        arg.substitution = std::make_unique<Element>();
        if (!ParseElement(arg.substitution.get())) {
          return false;
        }
        command->args.push_back(std::move(arg));
      } else if (kw == "env") {
        size_t eq = payload.find('=');
        if (eq == std::string::npos) {
          *error_ = "line " + std::to_string(pos_) + ": malformed env entry";
          return false;
        }
        command->env.emplace_back(payload.substr(0, eq), payload.substr(eq + 1));
      } else if (kw == "cwd") {
        command->cwd = payload;
      } else if (kw == "stdin") {
        command->stdin_path = payload;
      } else if (kw == "stdin_text") {
        command->has_stdin_text = true;
        command->stdin_text = payload;
      } else if (kw == "stdout" || kw == "stdout_append") {
        command->stdout_path = payload;
        command->stdout_append = kw == "stdout_append";
      } else if (kw == "stderr" || kw == "stderr_append") {
        command->stderr_path = payload;
        command->stderr_append = kw == "stderr_append";
      } else if (kw == "stderr_to_stdout") {
        command->stderr_to_stdout = true;
      } else {
        *error_ = "line " + std::to_string(pos_) + ": unknown keyword '" + kw + "'";
        return false;
      }
    }
    if (!has_program) {
      *error_ = "line " + std::to_string(pos_ + 1) + ": command without program";
      return false;
    }
    return Expect("end");
  }

  std::vector<Line> lines_;
  size_t pos_ = 0;
  std::string* error_ = nullptr;
};

// ---------------------------------------------------------------------------
// Buffered I/O on top of raw file descriptors.
// ---------------------------------------------------------------------------

class Reader {
 public:
  explicit Reader(Fd fd) : fd_(fd) {}

  // Reads a line without its trailing newline. Returns false at EOF.
  bool ReadLine(std::string* line) {
    line->clear();
    while (true) {
      for (size_t i = pos_; i < len_; ++i) {
        if (buf_[i] == '\n') {
          line->append(buf_ + pos_, i - pos_);
          pos_ = i + 1;
          return true;
        }
      }
      line->append(buf_ + pos_, len_ - pos_);
      pos_ = len_ = 0;
      if (!Fill()) {
        return !line->empty();
      }
    }
  }

  bool ReadAll(std::string* out) {
    out->assign(buf_ + pos_, len_ - pos_);
    pos_ = len_ = 0;
    while (Fill()) {
      out->append(buf_, len_);
      pos_ = len_ = 0;
    }
    return !failed_;
  }

  // Copies everything to `write`. Returns false on read error.
  bool CopyTo(const std::function<bool(const char*, size_t)>& write) {
    if (len_ > pos_ && !write(buf_ + pos_, len_ - pos_)) {
      return false;
    }
    pos_ = len_ = 0;
    while (Fill()) {
      if (!write(buf_, len_)) {
        return false;
      }
      pos_ = len_ = 0;
    }
    return !failed_;
  }

  bool failed() const { return failed_; }

 private:
  bool Fill() {
    int64_t n = FdRead(fd_, buf_, sizeof(buf_));
    if (n < 0) {
      failed_ = true;
      return false;
    }
    if (n == 0) {
      return false;
    }
    pos_ = 0;
    len_ = static_cast<size_t>(n);
    return true;
  }

  Fd fd_;
  char buf_[1 << 16];
  size_t pos_ = 0;
  size_t len_ = 0;
  bool failed_ = false;
};

class Writer {
 public:
  explicit Writer(Fd fd) : fd_(fd) {}
  ~Writer() { Flush(); }

  bool Write(const std::string& s) { return Write(s.data(), s.size()); }

  bool Write(const char* data, size_t size) {
    if (buf_.size() + size > kLimit) {
      if (!Flush()) {
        return false;
      }
      if (size > kLimit) {
        return ok_ = FdWriteAll(fd_, data, size);
      }
    }
    buf_.append(data, size);
    return ok_;
  }

  bool WriteLine(const std::string& s) { return Write(s) && Write("\n", 1); }

  bool Flush() {
    if (!buf_.empty()) {
      ok_ = ok_ && FdWriteAll(fd_, buf_.data(), buf_.size());
      buf_.clear();
    }
    return ok_;
  }

  bool ok() const { return ok_; }

 private:
  static constexpr size_t kLimit = 1 << 16;
  Fd fd_;
  std::string buf_;
  bool ok_ = true;
};

// ---------------------------------------------------------------------------
// Builtins.
// ---------------------------------------------------------------------------

struct BuiltinContext {
  Fd in;
  Fd out;
  Fd err;
  std::vector<std::string> options;  // Leading "--name" or "--name=value".
  std::vector<std::string> args;     // Everything after "--".

  bool HasOption(const std::string& name) const {
    for (const std::string& option : options) {
      if (option == "--" + name) {
        return true;
      }
    }
    return false;
  }

  std::string OptionValue(const std::string& name, const std::string& def) const {
    std::string prefix = "--" + name + "=";
    for (const std::string& option : options) {
      if (option.compare(0, prefix.size(), prefix) == 0) {
        return option.substr(prefix.size());
      }
    }
    return def;
  }

  int Fail(const std::string& message) const {
    std::string line = "cmd_runner: " + message + "\n";
    FdWriteAll(err, line.data(), line.size());
    return 1;
  }
};

// Calls `fn` for every input, which is either the given files or stdin.
// `fn` receives a Reader and the input's name.
int ForEachInput(const BuiltinContext& ctx,
                 const std::function<int(Reader&, const std::string&)>& fn) {
  if (ctx.args.empty()) {
    Reader reader(ctx.in);
    return fn(reader, "-");
  }
  for (const std::string& file : ctx.args) {
    std::string error;
    Fd fd = OpenForRead(file, &error);
    if (fd == kInvalidFd) {
      return ctx.Fail(error);
    }
    Reader reader(fd);
    int result = fn(reader, file);
    FdClose(fd);
    if (result != 0) {
      return result;
    }
  }
  return 0;
}

int BuiltinEcho(const BuiltinContext& ctx) {
  std::string sep = ctx.OptionValue("sep", " ");
  std::string end = ctx.OptionValue("end", "\n");
  Writer writer(ctx.out);
  for (size_t i = 0; i < ctx.args.size(); ++i) {
    if (i > 0) {
      writer.Write(sep);
    }
    writer.Write(ctx.args[i]);
  }
  writer.Write(end);
  return writer.Flush() ? 0 : ctx.Fail("echo: write error");
}

int BuiltinCat(const BuiltinContext& ctx) {
  Writer writer(ctx.out);
  int result = ForEachInput(ctx, [&](Reader& reader, const std::string& name) {
    if (!reader.CopyTo([&](const char* data, size_t size) {
          return writer.Write(data, size);
        })) {
      return ctx.Fail("cat: error while copying " + name);
    }
    return 0;
  });
  if (result != 0) {
    return result;
  }
  return writer.Flush() ? 0 : ctx.Fail("cat: write error");
}

bool CompileRegex(const std::string& pattern, bool ignore_case,
                  std::regex* regex, std::string* error) {
  try {
    auto flags = std::regex::ECMAScript | std::regex::optimize;
    if (ignore_case) {
      flags |= std::regex::icase;
    }
    *regex = std::regex(pattern, flags);
    return true;
  } catch (const std::regex_error& e) {
    *error = "invalid regular expression '" + pattern + "': " + e.what();
    return false;
  }
}

std::string ToLower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  return s;
}

int BuiltinGrep(const BuiltinContext& ctx_in) {
  BuiltinContext ctx = ctx_in;
  if (ctx.args.empty()) {
    return ctx.Fail("grep: missing pattern");
  }
  std::string pattern = ctx.args.front();
  ctx.args.erase(ctx.args.begin());
  bool fixed = ctx.HasOption("fixed");
  bool ignore_case = ctx.HasOption("ignore-case");
  bool invert = ctx.HasOption("invert");
  bool count = ctx.HasOption("count");
  bool line_number = ctx.HasOption("line-number");
  bool with_filename = ctx.args.size() > 1;

  std::regex regex;
  std::string lower_pattern = ignore_case ? ToLower(pattern) : pattern;
  if (!fixed) {
    std::string error;
    if (!CompileRegex(pattern, ignore_case, &regex, &error)) {
      return ctx.Fail("grep: " + error);
    }
  }
  auto matches = [&](const std::string& line) {
    bool matched;
    if (fixed) {
      matched = ignore_case ? ToLower(line).find(lower_pattern) != std::string::npos
                            : line.find(pattern) != std::string::npos;
    } else {
      matched = std::regex_search(line, regex);
    }
    return matched != invert;
  };

  Writer writer(ctx.out);
  int result = ForEachInput(ctx, [&](Reader& reader, const std::string& name) {
    std::string line;
    size_t number = 0;
    size_t matched = 0;
    while (reader.ReadLine(&line)) {
      ++number;
      if (!matches(line)) {
        continue;
      }
      ++matched;
      if (count) {
        continue;
      }
      std::string prefix;
      if (with_filename) {
        prefix += name + ":";
      }
      if (line_number) {
        prefix += std::to_string(number) + ":";
      }
      writer.Write(prefix);
      writer.WriteLine(line);
    }
    if (count) {
      writer.WriteLine((with_filename ? name + ":" : "") + std::to_string(matched));
    }
    return reader.failed() ? ctx.Fail("grep: error while reading " + name) : 0;
  });
  if (result != 0) {
    return result;
  }
  return writer.Flush() ? 0 : ctx.Fail("grep: write error");
}

std::string ReplaceAll(const std::string& text, const std::string& from,
                       const std::string& to, bool ignore_case) {
  if (from.empty()) {
    return text;
  }
  std::string haystack = ignore_case ? ToLower(text) : text;
  std::string needle = ignore_case ? ToLower(from) : from;
  std::string result;
  size_t pos = 0;
  while (true) {
    size_t found = haystack.find(needle, pos);
    if (found == std::string::npos) {
      result.append(text, pos, std::string::npos);
      break;
    }
    result.append(text, pos, found - pos);
    result.append(to);
    pos = found + needle.size();
  }
  return result;
}

int BuiltinReplace(const BuiltinContext& ctx_in) {
  BuiltinContext ctx = ctx_in;
  if (ctx.args.size() < 2) {
    return ctx.Fail("replace: expected old and new text");
  }
  std::string from = ctx.args[0];
  std::string to = ctx.args[1];
  ctx.args.erase(ctx.args.begin(), ctx.args.begin() + 2);
  bool use_regex = ctx.HasOption("regex");
  bool ignore_case = ctx.HasOption("ignore-case");
  std::regex regex;
  if (use_regex) {
    std::string error;
    if (!CompileRegex(from, ignore_case, &regex, &error)) {
      return ctx.Fail("replace: " + error);
    }
  }
  Writer writer(ctx.out);
  int result = ForEachInput(ctx, [&](Reader& reader, const std::string& name) {
    std::string content;
    if (!reader.ReadAll(&content)) {
      return ctx.Fail("replace: error while reading " + name);
    }
    std::string replaced;
    if (use_regex) {
      try {
        replaced = std::regex_replace(content, regex, to);
      } catch (const std::regex_error& e) {
        return ctx.Fail(std::string("replace: ") + e.what());
      }
    } else {
      replaced = ReplaceAll(content, from, to, ignore_case);
    }
    writer.Write(replaced);
    return 0;
  });
  if (result != 0) {
    return result;
  }
  return writer.Flush() ? 0 : ctx.Fail("replace: write error");
}

bool ParseCount(const BuiltinContext& ctx, const std::string& name, size_t* out) {
  std::string value = ctx.OptionValue(name, "10");
  char* end = nullptr;
  long long parsed = strtoll(value.c_str(), &end, 10);
  if (value.empty() || *end != '\0' || parsed < 0) {
    return false;
  }
  *out = static_cast<size_t>(parsed);
  return true;
}

int BuiltinHead(const BuiltinContext& ctx) {
  size_t n;
  if (!ParseCount(ctx, "lines", &n)) {
    return ctx.Fail("head: invalid line count");
  }
  Writer writer(ctx.out);
  int result = ForEachInput(ctx, [&](Reader& reader, const std::string&) {
    std::string line;
    size_t printed = 0;
    while (printed < n && reader.ReadLine(&line)) {
      writer.WriteLine(line);
      ++printed;
    }
    return 0;
  });
  if (result != 0) {
    return result;
  }
  return writer.Flush() ? 0 : ctx.Fail("head: write error");
}

int BuiltinTail(const BuiltinContext& ctx) {
  size_t n;
  if (!ParseCount(ctx, "lines", &n)) {
    return ctx.Fail("tail: invalid line count");
  }
  Writer writer(ctx.out);
  int result = ForEachInput(ctx, [&](Reader& reader, const std::string&) {
    std::deque<std::string> last;
    std::string line;
    while (reader.ReadLine(&line)) {
      last.push_back(line);
      if (last.size() > n) {
        last.pop_front();
      }
    }
    for (const std::string& l : last) {
      writer.WriteLine(l);
    }
    return 0;
  });
  if (result != 0) {
    return result;
  }
  return writer.Flush() ? 0 : ctx.Fail("tail: write error");
}

bool ReadAllLines(const BuiltinContext& ctx, std::vector<std::string>* lines) {
  return ForEachInput(ctx, [&](Reader& reader, const std::string& name) {
           std::string line;
           while (reader.ReadLine(&line)) {
             lines->push_back(line);
           }
           return reader.failed() ? ctx.Fail("error while reading " + name) : 0;
         }) == 0;
}

int BuiltinSort(const BuiltinContext& ctx) {
  std::vector<std::string> lines;
  if (!ReadAllLines(ctx, &lines)) {
    return 1;
  }
  bool numeric = ctx.HasOption("numeric");
  bool reverse = ctx.HasOption("reverse");
  auto less = [&](const std::string& a, const std::string& b) {
    if (numeric) {
      double da = strtod(a.c_str(), nullptr);
      double db = strtod(b.c_str(), nullptr);
      if (da != db) {
        return da < db;
      }
    }
    return a < b;
  };
  std::stable_sort(lines.begin(), lines.end(), less);
  if (ctx.HasOption("unique")) {
    lines.erase(std::unique(lines.begin(), lines.end()), lines.end());
  }
  if (reverse) {
    std::reverse(lines.begin(), lines.end());
  }
  Writer writer(ctx.out);
  for (const std::string& line : lines) {
    writer.WriteLine(line);
  }
  return writer.Flush() ? 0 : ctx.Fail("sort: write error");
}

int BuiltinUniq(const BuiltinContext& ctx) {
  std::vector<std::string> lines;
  if (!ReadAllLines(ctx, &lines)) {
    return 1;
  }
  bool count = ctx.HasOption("count");
  Writer writer(ctx.out);
  size_t i = 0;
  while (i < lines.size()) {
    size_t j = i;
    while (j < lines.size() && lines[j] == lines[i]) {
      ++j;
    }
    if (count) {
      char buf[32];
      snprintf(buf, sizeof(buf), "%7zu ", j - i);
      writer.Write(buf, strlen(buf));
    }
    writer.WriteLine(lines[i]);
    i = j;
  }
  return writer.Flush() ? 0 : ctx.Fail("uniq: write error");
}

int BuiltinWc(const BuiltinContext& ctx) {
  bool lines = ctx.HasOption("lines");
  bool words = ctx.HasOption("words");
  bool bytes = ctx.HasOption("bytes");
  if (!lines && !words && !bytes) {
    lines = words = bytes = true;
  }
  Writer writer(ctx.out);
  size_t total_lines = 0, total_words = 0, total_bytes = 0;
  bool multiple = ctx.args.size() > 1;
  auto emit = [&](size_t l, size_t w, size_t b, const std::string& name) {
    std::string out;
    if (lines) {
      out += std::to_string(l);
    }
    if (words) {
      out += (out.empty() ? "" : " ") + std::to_string(w);
    }
    if (bytes) {
      out += (out.empty() ? "" : " ") + std::to_string(b);
    }
    if (!name.empty()) {
      out += " " + name;
    }
    writer.WriteLine(out);
  };
  int result = ForEachInput(ctx, [&](Reader& reader, const std::string& name) {
    size_t l = 0, w = 0, b = 0;
    bool in_word = false;
    reader.CopyTo([&](const char* data, size_t size) {
      b += size;
      for (size_t i = 0; i < size; ++i) {
        unsigned char c = data[i];
        if (c == '\n') {
          ++l;
        }
        if (std::isspace(c)) {
          in_word = false;
        } else if (!in_word) {
          in_word = true;
          ++w;
        }
      }
      return true;
    });
    if (reader.failed()) {
      return ctx.Fail("wc: error while reading " + name);
    }
    total_lines += l;
    total_words += w;
    total_bytes += b;
    emit(l, w, b, ctx.args.empty() ? "" : name);
    return 0;
  });
  if (result != 0) {
    return result;
  }
  if (multiple) {
    emit(total_lines, total_words, total_bytes, "total");
  }
  return writer.Flush() ? 0 : ctx.Fail("wc: write error");
}

bool CopyPath(const fs::path& from, const fs::path& to, std::error_code* ec) {
  fs::create_directories(to.parent_path(), *ec);
  if (*ec) {
    return false;
  }
  if (fs::is_directory(from)) {
    fs::copy(from, to,
             fs::copy_options::recursive | fs::copy_options::overwrite_existing,
             *ec);
  } else {
    fs::copy_file(from, to, fs::copy_options::overwrite_existing, *ec);
  }
  return !*ec;
}

int BuiltinCp(const BuiltinContext& ctx) {
  if (ctx.args.size() < 2) {
    return ctx.Fail("cp: expected at least a source and a destination");
  }
  fs::path dest = ToFsPath(ctx.args.back());
  std::error_code ec;
  bool into_dir = ctx.args.size() > 2 || fs::is_directory(dest, ec);
  for (size_t i = 0; i + 1 < ctx.args.size(); ++i) {
    fs::path from = ToFsPath(ctx.args[i]);
    fs::path to = into_dir ? dest / from.filename() : dest;
    if (!fs::exists(from, ec)) {
      return ctx.Fail("cp: " + ctx.args[i] + " does not exist");
    }
    if (!CopyPath(from, to, &ec)) {
      return ctx.Fail("cp: cannot copy " + ctx.args[i] + " to " +
                      FromFsPath(to) + ": " + ec.message());
    }
  }
  return 0;
}

int BuiltinMv(const BuiltinContext& ctx) {
  if (ctx.args.size() != 2) {
    return ctx.Fail("mv: expected a source and a destination");
  }
  fs::path from = ToFsPath(ctx.args[0]);
  fs::path to = ToFsPath(ctx.args[1]);
  std::error_code ec;
  if (fs::is_directory(to, ec)) {
    to /= from.filename();
  }
  fs::create_directories(to.parent_path(), ec);
  fs::rename(from, to, ec);
  if (ec) {
    // Fall back to copy and delete, e.g. when crossing file systems.
    ec.clear();
    if (!CopyPath(from, to, &ec)) {
      return ctx.Fail("mv: cannot move " + ctx.args[0] + " to " +
                      ctx.args[1] + ": " + ec.message());
    }
    fs::remove_all(from, ec);
  }
  return 0;
}

int BuiltinMkdir(const BuiltinContext& ctx) {
  for (const std::string& dir : ctx.args) {
    std::error_code ec;
    fs::create_directories(ToFsPath(dir), ec);
    if (ec) {
      return ctx.Fail("mkdir: cannot create " + dir + ": " + ec.message());
    }
  }
  return 0;
}

int BuiltinRm(const BuiltinContext& ctx) {
  for (const std::string& path : ctx.args) {
    std::error_code ec;
    fs::remove_all(ToFsPath(path), ec);
    if (ec) {
      return ctx.Fail("rm: cannot remove " + path + ": " + ec.message());
    }
  }
  return 0;
}

int BuiltinTouch(const BuiltinContext& ctx) {
  for (const std::string& path : ctx.args) {
    std::string error;
    Fd fd = OpenForWrite(path, /* append= */ true, &error);
    if (fd == kInvalidFd) {
      return ctx.Fail("touch: " + error);
    }
    FdClose(fd);
    std::error_code ec;
    fs::last_write_time(ToFsPath(path), fs::file_time_type::clock::now(), ec);
  }
  return 0;
}

int BuiltinChmod(const BuiltinContext& ctx) {
  if (ctx.args.size() < 2) {
    return ctx.Fail("chmod: expected a mode and at least one file");
  }
  for (size_t i = 1; i < ctx.args.size(); ++i) {
    std::string error;
    if (!ChangeMode(ctx.args[i], ctx.args[0], &error)) {
      return ctx.Fail("chmod: " + error);
    }
  }
  return 0;
}

int BuiltinTee(const BuiltinContext& ctx) {
  bool append = ctx.HasOption("append");
  std::vector<Fd> fds;
  for (const std::string& path : ctx.args) {
    std::string error;
    Fd fd = OpenForWrite(path, append, &error);
    if (fd == kInvalidFd) {
      for (Fd f : fds) {
        FdClose(f);
      }
      return ctx.Fail("tee: " + error);
    }
    fds.push_back(fd);
  }
  Reader reader(ctx.in);
  bool ok = reader.CopyTo([&](const char* data, size_t size) {
    bool result = FdWriteAll(ctx.out, data, size);
    for (Fd fd : fds) {
      result = FdWriteAll(fd, data, size) && result;
    }
    return result;
  });
  for (Fd fd : fds) {
    FdClose(fd);
  }
  return ok ? 0 : ctx.Fail("tee: I/O error");
}

int BuiltinLs(const BuiltinContext& ctx) {
  bool recursive = ctx.HasOption("recursive");
  std::vector<std::string> roots = ctx.args;
  if (roots.empty()) {
    roots.push_back(".");
  }
  Writer writer(ctx.out);
  for (const std::string& root : roots) {
    std::error_code ec;
    fs::path root_path = ToFsPath(root);
    if (!fs::is_directory(root_path, ec)) {
      if (fs::exists(root_path, ec)) {
        writer.WriteLine(root);
        continue;
      }
      return ctx.Fail("ls: " + root + " does not exist");
    }
    std::vector<std::string> entries;
    if (recursive) {
      for (fs::recursive_directory_iterator it(root_path, ec), end;
           !ec && it != end; it.increment(ec)) {
        if (!it->is_directory(ec)) {
          entries.push_back(
              FromFsPath(it->path().lexically_relative(root_path).generic_string()));
        }
      }
    } else {
      for (fs::directory_iterator it(root_path, ec), end; !ec && it != end;
           it.increment(ec)) {
        std::string name = FromFsPath(it->path().filename());
        if (it->is_directory(ec)) {
          name += "/";
        }
        entries.push_back(name);
      }
    }
    if (ec) {
      return ctx.Fail("ls: cannot list " + root + ": " + ec.message());
    }
    std::sort(entries.begin(), entries.end());
    for (const std::string& entry : entries) {
      writer.WriteLine(entry);
    }
  }
  return writer.Flush() ? 0 : ctx.Fail("ls: write error");
}

int BuiltinFail(const BuiltinContext& ctx) {
  std::string message;
  for (size_t i = 0; i < ctx.args.size(); ++i) {
    if (i > 0) {
      message += " ";
    }
    message += ctx.args[i];
  }
  message += "\n";
  FdWriteAll(ctx.err, message.data(), message.size());
  return 1;
}

int RunBuiltin(const std::string& name, const std::vector<std::string>& args,
               Fd in, Fd out, Fd err) {
  BuiltinContext ctx;
  ctx.in = in;
  ctx.out = out;
  ctx.err = err;
  size_t i = 0;
  for (; i < args.size(); ++i) {
    if (args[i] == "--") {
      ++i;
      break;
    }
    if (args[i].compare(0, 2, "--") != 0) {
      break;
    }
    ctx.options.push_back(args[i]);
  }
  ctx.args.assign(args.begin() + i, args.end());

  static const struct {
    const char* name;
    int (*fn)(const BuiltinContext&);
  } kBuiltins[] = {
      {"echo", BuiltinEcho},   {"cat", BuiltinCat},     {"grep", BuiltinGrep},
      {"replace", BuiltinReplace}, {"head", BuiltinHead}, {"tail", BuiltinTail},
      {"sort", BuiltinSort},   {"uniq", BuiltinUniq},   {"wc", BuiltinWc},
      {"cp", BuiltinCp},       {"mv", BuiltinMv},       {"mkdir", BuiltinMkdir},
      {"rm", BuiltinRm},       {"touch", BuiltinTouch}, {"chmod", BuiltinChmod},
      {"tee", BuiltinTee},     {"ls", BuiltinLs},       {"fail", BuiltinFail},
  };
  for (const auto& builtin : kBuiltins) {
    if (name == builtin.name) {
      return builtin.fn(ctx);
    }
  }
  return ctx.Fail("unknown builtin '" + name + "'");
}

// ---------------------------------------------------------------------------
// Execution.
// ---------------------------------------------------------------------------

// A running stage of a pipeline: either a child process or a thread running a
// builtin.
struct Stage {
  std::string description;
  bool is_process = false;
  ProcessHandle process;
  std::unique_ptr<std::thread> thread;
  std::shared_ptr<int> thread_result;
};

class Executor {
 public:
  int RunScript(const Script& script) {
    for (const Element& element : script.steps) {
      int code = RunElement(element, kInvalidFd, kInvalidFd);
      if (code != 0) {
        return code;
      }
    }
    return 0;
  }

 private:
  // Runs an element to completion. Takes ownership of `in` and `out` (if they
  // are valid), which are the default standard streams of the first and last
  // command respectively.
  int RunElement(const Element& element, Fd in, Fd out) {
    std::vector<Stage> stages;
    int start_code = StartElement(element, in, out, &stages);
    int code = WaitStages(&stages);
    return start_code != 0 ? start_code : code;
  }

  int StartElement(const Element& element, Fd in, Fd out,
                   std::vector<Stage>* stages) {
    size_t n = element.commands.size();
    Fd next_in = in;
    for (size_t i = 0; i < n; ++i) {
      const Command& command = element.commands[i];
      Fd stage_in = next_in;
      Fd stage_out;
      if (i + 1 < n) {
        std::string error;
        if (!MakePipe(&next_in, &stage_out, &error)) {
          FdClose(stage_in);
          if (i + 1 < n) {
            FdClose(out);
          }
          return Error(error);
        }
      } else {
        stage_out = out;
      }
      Stage stage;
      std::string error;
      if (!StartStage(command, stage_in, stage_out, &stage, &error)) {
        // The remaining pipe ends must be closed so that earlier stages see
        // EOF or SIGPIPE instead of hanging.
        if (i + 1 < n) {
          FdClose(next_in);
          FdClose(out);
        }
        return Error(error);
      }
      stages->push_back(std::move(stage));
    }
    return 0;
  }

  int WaitStages(std::vector<Stage>* stages) {
    int code = 0;
    for (size_t i = 0; i < stages->size(); ++i) {
      Stage& stage = (*stages)[i];
      int stage_code;
      if (stage.is_process) {
        stage_code = WaitProcess(stage.process);
        // A producer that is killed because a later stage stopped reading (as
        // in `cat file | head`) is not an error.
        if (i + 1 < stages->size() && ExitedDueToBrokenPipe(stage_code)) {
          stage_code = 0;
        }
      } else {
        stage.thread->join();
        stage_code = *stage.thread_result;
      }
      if (stage_code != 0) {
        if (stage.is_process || stage_code != 1) {
          Error("'" + stage.description + "' failed with exit code " +
                std::to_string(stage_code));
        }
        code = stage_code;
      }
    }
    return code;
  }

  // Starts a single command. Takes ownership of `in` and `out`.
  bool StartStage(const Command& command, Fd in, Fd out, Stage* stage,
                  std::string* error) {
    std::vector<Fd> owned;
    if (in != kInvalidFd) {
      owned.push_back(in);
    }
    if (out != kInvalidFd) {
      owned.push_back(out);
    }
    Fd err = kInvalidFd;
    auto cleanup = [&]() {
      for (Fd fd : owned) {
        FdClose(fd);
      }
    };

    // Evaluate arguments, including command substitutions, first.
    std::vector<std::string> args;
    for (const Arg& arg : command.args) {
      if (arg.substitution) {
        std::string value;
        if (!Capture(*arg.substitution, &value, error)) {
          cleanup();
          return false;
        }
        args.push_back(value);
      } else if (arg.is_path && !command.cwd.empty() &&
                 command.kind != Command::Kind::kBuiltin) {
        args.push_back(AbsolutePath(arg.value));
      } else {
        args.push_back(arg.value);
      }
    }

    // Set up redirections. Explicit redirections of a command take precedence
    // over the streams it inherits from the pipeline.
    if (!command.stdin_path.empty() || command.has_stdin_text) {
      if (in != kInvalidFd) {
        *error = "'" + command.Describe() +
                 "': stdin is both piped and redirected";
        cleanup();
        return false;
      }
      if (command.has_stdin_text) {
        Fd r, w;
        if (!MakePipe(&r, &w, error)) {
          cleanup();
          return false;
        }
        // The text is written from a helper thread so that large inputs do
        // not block before the consumer starts reading.
        std::string text = command.stdin_text;
        feeders_.emplace_back([w, text]() {
          FdWriteAll(w, text.data(), text.size());
          FdClose(w);
        });
        in = r;
      } else {
        in = OpenForRead(command.stdin_path, error);
        if (in == kInvalidFd) {
          cleanup();
          return false;
        }
      }
      owned.push_back(in);
    }
    if (!command.stdout_path.empty()) {
      if (out != kInvalidFd) {
        *error = "'" + command.Describe() +
                 "': stdout is both piped and redirected";
        cleanup();
        return false;
      }
      out = OpenForWrite(command.stdout_path, command.stdout_append, error);
      if (out == kInvalidFd) {
        cleanup();
        return false;
      }
      owned.push_back(out);
    }
    if (!command.stderr_path.empty()) {
      err = OpenForWrite(command.stderr_path, command.stderr_append, error);
      if (err == kInvalidFd) {
        cleanup();
        return false;
      }
      owned.push_back(err);
    } else if (command.stderr_to_stdout) {
      err = out;
    }

    stage->description = command.Describe();
    if (command.kind == Command::Kind::kBuiltin) {
      stage->is_process = false;
      stage->thread_result = std::make_shared<int>(0);
      std::string name = command.program;
      Fd actual_in = in == kInvalidFd ? StdinFd() : in;
      Fd actual_out = out == kInvalidFd ? StdoutFd() : out;
      Fd actual_err = err == kInvalidFd ? StderrFd() : err;
      std::shared_ptr<int> result = stage->thread_result;
      stage->thread = std::make_unique<std::thread>(
          [name, args, actual_in, actual_out, actual_err, owned, result]() {
            *result = RunBuiltin(name, args, actual_in, actual_out, actual_err);
            for (Fd fd : owned) {
              FdClose(fd);
            }
          });
      return true;
    }

    SpawnOptions options;
    options.program = command.kind == Command::Kind::kTool
                          ? NativePath(command.program)
                          : command.program;
    options.search_path = command.kind == Command::Kind::kProgram;
    options.args = std::move(args);
    options.env = command.env;
    options.cwd = command.cwd;
    options.stdin_fd = in;
    options.stdout_fd = out;
    options.stderr_fd = err;
    stage->is_process = true;
    bool ok = SpawnProcess(options, &stage->process, error);
    cleanup();
    return ok;
  }

  // Runs `element` and captures its standard output, with trailing newlines
  // removed, like $(...) in a shell.
  bool Capture(const Element& element, std::string* value, std::string* error) {
    Fd r, w;
    if (!MakePipe(&r, &w, error)) {
      return false;
    }
    std::string captured;
    std::thread reader([r, &captured]() {
      Reader reader(r);
      reader.ReadAll(&captured);
      FdClose(r);
    });
    int code = RunElement(element, kInvalidFd, w);
    reader.join();
    if (code != 0) {
      *error = "command substitution failed with exit code " +
               std::to_string(code);
      return false;
    }
    while (!captured.empty() &&
           (captured.back() == '\n' || captured.back() == '\r')) {
      captured.pop_back();
    }
    *value = captured;
    return true;
  }

  int Error(const std::string& message) {
    std::string line = "cmd_runner: " + message + "\n";
    FdWriteAll(StderrFd(), line.data(), line.size());
    return 1;
  }

 public:
  ~Executor() {
    for (std::thread& t : feeders_) {
      t.join();
    }
  }

 private:
  std::vector<std::thread> feeders_;
};

bool ReadLines(const std::string& path, std::vector<Line>* lines,
               std::string* error) {
  Fd fd = OpenForRead(path, error);
  if (fd == kInvalidFd) {
    return false;
  }
  Reader reader(fd);
  std::string content;
  bool ok = reader.ReadAll(&content);
  FdClose(fd);
  if (!ok) {
    *error = "cannot read " + path;
    return false;
  }
  size_t start = 0;
  while (start < content.size()) {
    size_t end = content.find('\n', start);
    if (end == std::string::npos) {
      end = content.size();
    }
    std::string raw = content.substr(start, end - start);
    if (!raw.empty() && raw.back() == '\r') {
      raw.pop_back();
    }
    start = end + 1;
    if (raw.empty()) {
      continue;
    }
    Line line;
    size_t space = raw.find(' ');
    if (space == std::string::npos) {
      line.keyword = raw;
    } else {
      line.keyword = raw.substr(0, space);
      line.payload = raw.substr(space + 1);
    }
    lines->push_back(std::move(line));
  }
  return true;
}

int Main(int argc, char** argv) {
  if (argc != 2) {
    fprintf(stderr, "Usage: cmd_runner @<script file>\n");
    return 2;
  }
  std::string path = argv[1];
  if (!path.empty() && path[0] == '@') {
    path = path.substr(1);
  }
  std::vector<Line> lines;
  std::string error;
  if (!ReadLines(path, &lines, &error)) {
    fprintf(stderr, "cmd_runner: %s\n", error.c_str());
    return 2;
  }
  Script script;
  if (!Parser(std::move(lines)).Parse(&script, &error)) {
    fprintf(stderr, "cmd_runner: invalid script %s: %s\n", path.c_str(),
            error.c_str());
    return 2;
  }
  PlatformInit();
  Executor executor;
  return executor.RunScript(script);
}

}  // namespace
}  // namespace cmd_runner

int main(int argc, char** argv) { return cmd_runner::Main(argc, argv); }
