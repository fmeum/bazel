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

import static com.google.common.truth.Truth.assertThat;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link RegexUtil}. */
@RunWith(TestParameterInjector.class)
public class RegexUtilTest {

  @Test
  public void optimizedMatchingPredicate(
      @TestParameter({
            "",
            ".",
            "a",
            "foo",
            "foofoo",
            "coverage.dat",
            "/coverage.dat",
            "/coverage.data",
            "/coverage1dat",
            "/coverage1data",
            "foo/coverage.dat",
            "foo/coverage.data",
            "foo/coverage1dat",
            "foo/coverage1data",
            "foo/test/a/coverage.dat",
            "foo/test/.*/coverage.dat",
            "]]\n",
            "()",
            "+",
            "|",
            "bar",
            "foo/bar",
            "Foo",
            "aa",
            "foo/test/x",
            "foo/coverage1dat/x",
            "foobar",
          })
          String haystack,
      @TestParameter({
            ".*",
            ".*?foo",
            ".*+foo",
            "^foo$",
            "coverage\\.dat",
            ".*/coverage.dat",
            ".*/coverage\\.dat",
            ".*/test/.*/coverage\\.dat",
            "$|",
            "^",
            ".]",
            ".*]",
            ".*^?^\\Q",
            "foo|/coverage.dat",
            ".*^|.*a",
            "\\Q.",
            ".*.",
            ".*\\\\",
            ".*()",
            ".*|",
            ".*^",
            ".*+",
            ".*(foo|bar)",
            ".*(foo|/coverage)\\.dat",
            ".*((a|fo)o|bar)(/.*)?",
            ".*(?:foo|)",
            ".*(|foo)",
            "^.*(foo|a)",
            "\\A.*(foo|^a)",
            ".*(foo|a)*",
            ".*(foo|a)+",
            ".*(foo|a){2}",
            ".*\\.(dat|data)",
            ".*(\\c|foo)",
            ".*(?i:foo|a)",
            "(?i)foo|A",
            ".*(a)\\1|foo",
            ".*[|(]foo",
            ".*(?:[]|]|foo)",
            "foo|.*/coverage(\\.dat|1dat)",
            ".*(test|foo/(test|coverage))/.*",
            ".*(?:{2}a|foo)",
            ".*(?=foo)(foo|bar)",
            ".*(?<=/)(foo|bar)",
            ".*(?<n>foo|bar)",
            ".*(?x)foo #comment",
            ".*(?ix)foo",
            ".*(?x:foo)#bar",
          })
          String needle) {
    Pattern originalPattern = Pattern.compile(needle, Pattern.DOTALL);
    Predicate<String> optimizedMatcher = RegexUtil.asOptimizedMatchingPredicate(originalPattern);
    assertThat(optimizedMatcher.test(haystack))
        .isEqualTo(originalPattern.matcher(haystack).matches());
  }

  @Test
  public void splitIntoAlternatives_distributesLeadingGroups() {
    assertThat(RegexUtil.splitIntoAlternatives(".*(foo|bar)/baz"))
        .containsExactly(".*foo/baz", ".*bar/baz")
        .inOrder();
    assertThat(RegexUtil.splitIntoAlternatives("^.*(?:foo|(ba|qu)r)(/.*)?"))
        .containsExactly("^.*foo(/.*)?", "^.*bar(/.*)?", "^.*qur(/.*)?")
        .inOrder();
    assertThat(RegexUtil.splitIntoAlternatives("foo|.*(a|b)|[|]"))
        .containsExactly("foo", ".*a", ".*b", "[|]")
        .inOrder();
  }

  @Test
  public void splitIntoAlternatives_regexFromIssue() {
    // https://github.com/bazelbuild/bazel/issues/32287
    assertThat(
            RegexUtil.splitIntoAlternatives(
                ".*(((synced_folder_failure|model_configuration_markers|personal_api_keys)"
                    + "_browser_test|run_admin_panel_e2e)/test.outputs(/.*)?"
                    + "|build/vulnerability/repository_report[.](grype[.]json|summary(/.*)?))"))
        .containsExactly(
            ".*synced_folder_failure_browser_test/test.outputs(/.*)?",
            ".*model_configuration_markers_browser_test/test.outputs(/.*)?",
            ".*personal_api_keys_browser_test/test.outputs(/.*)?",
            ".*run_admin_panel_e2e/test.outputs(/.*)?",
            ".*build/vulnerability/repository_report[.](grype[.]json|summary(/.*)?)")
        .inOrder();
  }

  @Test
  public void splitIntoAlternatives_keepsGroupsThatCantBeDistributed() {
    assertThat(RegexUtil.splitIntoAlternatives(".*(foo|bar)?")).containsExactly(".*(foo|bar)?");
    assertThat(RegexUtil.splitIntoAlternatives(".*(foo|bar){2}")).containsExactly(".*(foo|bar){2}");
    assertThat(RegexUtil.splitIntoAlternatives(".*?(foo|bar)")).containsExactly(".*?(foo|bar)");
    assertThat(RegexUtil.splitIntoAlternatives(".*(?=foo|bar)")).containsExactly(".*(?=foo|bar)");
    assertThat(RegexUtil.splitIntoAlternatives("a(foo|bar)")).containsExactly("a(foo|bar)");
  }

  @Test
  public void splitIntoAlternatives_unsupported_returnsNull(
      @TestParameter({
            "(?i)foo|bar",
            "foo|(?i:bar)",
            "(a)|\\1",
            "(?<n>a)|\\k<n>",
            "\\0101|a",
            "\\Qa|b\\E",
            "(foo|bar",
            "foo|bar)",
            "[foo|bar",
            ".*(a|b|c|d|e|f|g|h|i|j|k|l|m|n|o|p|q)",
          })
          String regex) {
    assertThat(RegexUtil.splitIntoAlternatives(regex)).isNull();
  }
}
