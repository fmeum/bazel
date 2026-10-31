// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.regex;

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

/** A utility class for regex-related operations. */
public final class RegexUtil {
  // If a regex pattern matches this pattern, it matches like a literal string after unescaping
  // dots. Both `\` and `.` are disallowed unless they appear as `\.`.
  private static final Pattern LITERAL_PATTERN_WITH_DOT_UNESCAPED =
      Pattern.compile("(?:[^\\[\\](){}^$|*+?.\\\\]|\\\\\\.)*");

  // If a regex matches this pattern, it matches on the suffix of a string. We need to rule out
  // possessive and reluctant quantifiers, which are the only possible characters after the ".*"
  // prefix that make it unsafe to drop.
  private static final Pattern EXTRACT_SUFFIX_MATCH =
      Pattern.compile("(?:\\^|\\\\A)*\\.\\*(?<suffix>[^?+].*)?");

  // Matches inline flags that may enable comments mode, in which "#" starts a comment that extends
  // to the end of the line and would thus swallow a "\\z" appended to the pattern.
  private static final Pattern MAY_ENABLE_COMMENTS = Pattern.compile("\\(\\?[a-zA-Z-]*x");

  // Matches the ".*" prefix (with optional anchors) of a regex. See EXTRACT_SUFFIX_MATCH for why
  // reluctant and possessive quantifiers are ruled out.
  private static final Pattern LEADING_DOT_STAR = Pattern.compile("(?:\\^|\\\\A)*\\.\\*(?![?+])");

  // The maximum number of alternatives a regex is split into by splitIntoAlternatives. Each
  // alternative is matched separately, so too many of them would be slower than matching the
  // original regex.
  private static final int MAX_ALTERNATIVES = 16;

  /**
   * Returns a {@link Predicate} that matches the input string (in internal encoding according to
   * {@link com.google.devtools.build.lib.util.StringEncoding}) against the regex pattern with the
   * same semantics as {@link Matcher#matches()}, but more optimized and as if the pattern was
   * compiled with {@link Pattern#DOTALL}.
   */
  public static Predicate<String> asOptimizedMatchingPredicate(Pattern regexPattern) {
    String pattern = regexPattern.pattern();
    if (pattern.contains("\\Q")) {
      // Unmatched \Q...\E sequences make it so that appending "\z" may not force a match to the
      // end and also complicate splitting the pattern into alternatives.
      return s -> regexPattern.matcher(internalToUnicode(s)).matches();
    }
    if (pattern.contains("|")) {
      // Top-level alternations make it so that appending "\z" may not force a match to the end.
      // While we could wrap the pattern in "(?:" + pattern + ")\z", that would no longer be
      // amenable to the regex engine's optimization pass exploited below - it only applies when
      // the first node is a literal, not a group. Instead, split the pattern into alternatives
      // without top-level alternations and optimize each of them individually.
      List<String> alternatives = splitIntoAlternatives(pattern);
      if (alternatives == null) {
        return s -> regexPattern.matcher(internalToUnicode(s)).matches();
      }
      if (alternatives.size() > 1 || !alternatives.getFirst().equals(pattern)) {
        var predicates = new ArrayList<Predicate<String>>(alternatives.size());
        for (String alternative : alternatives) {
          Pattern alternativePattern;
          try {
            alternativePattern = Pattern.compile(alternative, Pattern.DOTALL);
          } catch (PatternSyntaxException e) {
            // Not expected, but be conservative.
            return s -> regexPattern.matcher(internalToUnicode(s)).matches();
          }
          predicates.add(
              asOptimizedMatchingPredicateWithoutTopLevelAlternation(alternativePattern));
        }
        return anyOf(predicates);
      }
    }
    return asOptimizedMatchingPredicateWithoutTopLevelAlternation(regexPattern);
  }

  private static Predicate<String> anyOf(List<Predicate<String>> predicates) {
    if (predicates.size() == 1) {
      return predicates.getFirst();
    }
    @SuppressWarnings("unchecked")
    Predicate<String>[] predicatesArray = predicates.toArray(new Predicate[0]);
    return s -> {
      for (Predicate<String> predicate : predicatesArray) {
        if (predicate.test(s)) {
          return true;
        }
      }
      return false;
    };
  }

  private static Predicate<String> asOptimizedMatchingPredicateWithoutTopLevelAlternation(
      Pattern regexPattern) {
    String pattern = regexPattern.pattern();
    // If the pattern matches a literal string, optimize to an equals call.
    if (LITERAL_PATTERN_WITH_DOT_UNESCAPED.matcher(pattern).matches()) {
      return unicodeToInternal(pattern.replace("\\.", "."))::equals;
    }
    // Recognize a pattern that starts with ".*" and drop it so that the engine sees the next part
    // of the pattern as the beginning and potentially optimizes the literal search.
    // We don't apply the same transformation to the end of the pattern since it is harder to
    // determine if a ".*" suffix is safe to drop due to backslash escapes.
    Matcher suffixMatch = EXTRACT_SUFFIX_MATCH.matcher(pattern);
    if (!suffixMatch.matches()) {
      return s -> regexPattern.matcher(internalToUnicode(s)).matches();
    }
    String suffixPattern = suffixMatch.group("suffix");
    // A null suffixPattern implies the regex is equivalent to ".*", which matches any input string.
    if (suffixPattern == null) {
      return s -> true;
    }
    // If the pattern matches a literal suffix, optimize to a string suffix
    // match, which is by far the fastest way to match.
    if (LITERAL_PATTERN_WITH_DOT_UNESCAPED.matcher(suffixPattern).matches()) {
      String literalSuffix = unicodeToInternal(suffixPattern.replace("\\.", "."));
      return s -> s.endsWith(literalSuffix);
    }
    // Turn the "match" pattern into an equivalent "find" pattern, since these are the only ones
    // that benefit from the Boyer-Moore optimization in the Java regex engine.
    // https://github.com/openjdk/jdk/blob/50dced88ff1aed23bb4c8fe9e4a08e6cc200b897/src/java.base/share/classes/java/util/regex/Pattern.java#L1959-L1969
    if (MAY_ENABLE_COMMENTS.matcher(suffixPattern).find()) {
      return s -> regexPattern.matcher(internalToUnicode(s)).matches();
    }
    Pattern compiled = Pattern.compile(suffixPattern + "\\z", Pattern.DOTALL);
    return s -> compiled.matcher(internalToUnicode(s)).find();
  }

  /**
   * Splits a regex into alternatives such that a string fully matches the regex if and only if it
   * fully matches one of the alternatives, none of which contains a top-level alternation.
   *
   * <p>In addition, groups that directly follow a leading ".*" are distributed over their
   * alternatives, so that e.g. {@code .*(foo|bar)/baz} is split into {@code .*foo/baz} and {@code
   * .*bar/baz}. Each of these alternatives starts with a literal after the ".*" and can thus be
   * matched with a fast literal search.
   *
   * <p>Returns null if the regex uses constructs that make this unsafe (backreferences, octal
   * escapes, quotations or inline flags, which can apply across alternatives), can't be parsed or
   * would result in more than {@link #MAX_ALTERNATIVES} alternatives.
   */
  // Visible for testing.
  @Nullable
  static List<String> splitIntoAlternatives(String regex) {
    List<String> topLevelAlternatives = splitAtTopLevel(regex);
    if (topLevelAlternatives == null) {
      return null;
    }
    var alternatives = new ArrayList<String>();
    for (String alternative : topLevelAlternatives) {
      if (!distributeLeadingGroups(alternative, alternatives)) {
        return null;
      }
    }
    return alternatives;
  }

  /**
   * If the given regex without top-level alternations starts with ".*" followed by a group without
   * a quantifier, adds the alternatives obtained by distributing the ".*" and the rest of the regex
   * over the alternatives in the group to {@code out}, recursively. Otherwise, adds the regex
   * itself.
   *
   * @return false if the maximum number of alternatives has been exceeded
   */
  private static boolean distributeLeadingGroups(String regex, List<String> out) {
    Matcher leadingDotStar = LEADING_DOT_STAR.matcher(regex);
    int groupStart = leadingDotStar.lookingAt() ? leadingDotStar.end() : -1;
    int bodyStart;
    if (regex.startsWith("(?:", groupStart)) {
      bodyStart = groupStart + 3;
    } else if (regex.startsWith("(", groupStart) && !regex.startsWith("(?", groupStart)) {
      bodyStart = groupStart + 1;
    } else {
      return add(regex, out);
    }
    int groupEnd = findGroupEnd(regex, groupStart);
    if (groupEnd < 0 || startsWithQuantifier(regex, groupEnd + 1)) {
      return add(regex, out);
    }
    List<String> groupAlternatives = splitAtTopLevel(regex.substring(bodyStart, groupEnd));
    if (groupAlternatives == null
        || groupAlternatives.stream()
            .anyMatch(alternative -> startsWithQuantifier(alternative, 0))) {
      return add(regex, out);
    }
    String prefix = regex.substring(0, groupStart);
    String suffix = regex.substring(groupEnd + 1);
    for (String groupAlternative : groupAlternatives) {
      if (!distributeLeadingGroups(prefix + groupAlternative + suffix, out)) {
        return false;
      }
    }
    return true;
  }

  private static boolean add(String regex, List<String> out) {
    out.add(regex);
    return out.size() <= MAX_ALTERNATIVES;
  }

  private static boolean startsWithQuantifier(String regex, int index) {
    if (index >= regex.length()) {
      return false;
    }
    char c = regex.charAt(index);
    return c == '?' || c == '*' || c == '+' || c == '{';
  }

  /**
   * Splits a regex at top-level '|' characters, or returns null if it contains unbalanced
   * parentheses or constructs that are not supported by {@link #splitIntoAlternatives}.
   */
  @Nullable
  private static List<String> splitAtTopLevel(String regex) {
    var alternatives = new ArrayList<String>();
    int depth = 0;
    int alternativeStart = 0;
    int i = 0;
    while (i < regex.length()) {
      char c = regex.charAt(i);
      if (c == '|' && depth == 0) {
        alternatives.add(regex.substring(alternativeStart, i));
        alternativeStart = i + 1;
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        if (depth == 0) {
          return null;
        }
        depth--;
      }
      i = nextTokenIndex(regex, i);
      if (i < 0) {
        return null;
      }
    }
    if (depth != 0) {
      return null;
    }
    alternatives.add(regex.substring(alternativeStart));
    return alternatives;
  }

  /**
   * Returns the index of the ')' matching the '(' at the given index, or -1 if there is none or the
   * regex contains unsupported constructs.
   */
  private static int findGroupEnd(String regex, int groupStart) {
    int depth = 0;
    int i = groupStart;
    while (i < regex.length()) {
      char c = regex.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
      i = nextTokenIndex(regex, i);
      if (i < 0) {
        return -1;
      }
    }
    return -1;
  }

  /**
   * Returns the index of the first character after the token starting at the given index, skipping
   * over escape sequences and character classes as a whole. Returns -1 if the token is not
   * supported by {@link #splitIntoAlternatives}.
   */
  private static int nextTokenIndex(String regex, int i) {
    return switch (regex.charAt(i)) {
      case '\\' -> escapeEnd(regex, i);
      case '[' -> {
        int classEnd = characterClassEnd(regex, i);
        yield classEnd < 0 ? -1 : classEnd + 1;
      }
      case '(' -> {
        if (!regex.startsWith("(?", i)) {
          yield i + 1;
        }
        // Allow non-capturing groups, lookarounds, atomic groups and named groups, but not inline
        // flags, which may apply across alternatives.
        if (regex.startsWith("(?:", i)
            || regex.startsWith("(?=", i)
            || regex.startsWith("(?!", i)
            || regex.startsWith("(?>", i)
            || regex.startsWith("(?<=", i)
            || regex.startsWith("(?<!", i)) {
          yield i + 1;
        }
        if (regex.startsWith("(?<", i)
            && i + 3 < regex.length()
            && Character.isLetter(regex.charAt(i + 3))) {
          yield i + 1;
        }
        yield -1;
      }
      default -> i + 1;
    };
  }

  /**
   * Returns the index of the first character after the escape sequence starting at the given index,
   * or -1 if the escape sequence is not supported by {@link #splitIntoAlternatives}.
   */
  private static int escapeEnd(String regex, int i) {
    if (i + 1 >= regex.length()) {
      return -1;
    }
    char c = regex.charAt(i + 1);
    if ((c >= '0' && c <= '9') || c == 'k' || c == 'Q') {
      // Backreferences, octal escapes of variable length and quotations.
      return -1;
    }
    if (c == 'c') {
      // A control character escape consumes the next character, whatever it is.
      return i + 3 <= regex.length() ? i + 3 : -1;
    }
    // All other escape sequences are either two characters long or continue with characters that
    // are not special to splitIntoAlternatives (e.g. \x{...}, \p{...}).
    return i + 2;
  }

  /**
   * Returns the index of the ']' that closes the character class starting at the given index, or -1
   * if there is none or the class contains unsupported constructs.
   */
  private static int characterClassEnd(String regex, int classStart) {
    int i = classStart + 1;
    if (i < regex.length() && regex.charAt(i) == '^') {
      i++;
    }
    // A ']' directly after the opening '[' or '[^' is a literal.
    if (i < regex.length() && regex.charAt(i) == ']') {
      i++;
    }
    while (i < regex.length()) {
      char c = regex.charAt(i);
      if (c == ']') {
        return i;
      }
      if (c == '\\') {
        i = escapeEnd(regex, i);
      } else if (c == '[') {
        int nestedEnd = characterClassEnd(regex, i);
        i = nestedEnd < 0 ? -1 : nestedEnd + 1;
      } else {
        i++;
      }
      if (i < 0) {
        return -1;
      }
    }
    return -1;
  }

  private RegexUtil() {}
}
