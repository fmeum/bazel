// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter;
import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelListConverter;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingKey;
import com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsClass;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.List;
import javax.annotation.Nullable;

/** Command-line options for platform-related configuration. */
@OptionsClass
public abstract class PlatformOptions extends FragmentOptions {

  private static final ImmutableSet<String> DEFAULT_PLATFORM_NAMES =
      ImmutableSet.of("host", "host_platform", "target_platform", "default_host", "default_target");

  public static final String DEFAULT_HOST_PLATFORM = "@bazel_tools//tools:host_platform";

  public static boolean platformIsDefault(Label platform) {
    return DEFAULT_PLATFORM_NAMES.contains(platform.getName());
  }

  @Option(
      name = "host_platform",
      oldName = "experimental_host_platform",
      converter = HostPlatformConverter.class,
      defaultValue = DEFAULT_HOST_PLATFORM,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      help = "The label of a platform rule that describes the host system.")
  public abstract Label getHostPlatform();

  public abstract void setHostPlatform(Label value);

  @Option(
      name = "extra_execution_platforms",
      converter = CommaSeparatedOptionListConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          """
          The platforms that are available as execution platforms to run actions.
          Platforms can be specified by exact target, or as a target pattern.
          These platforms will be considered before those declared in the `WORKSPACE` file by
          `register_execution_platforms()`. This option may only be set once; later
          instances will override earlier flag settings.
          """)
  public abstract List<String> getExtraExecutionPlatforms();

  @Option(
      name = "platforms",
      oldName = "experimental_platforms",
      converter = LabelListConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      help =
          """
          The labels of the platform rules describing the target platforms for the current
          command. If more than one platform is specified, every top-level target is analyzed
          (and, for tests, run) once per target platform. Each individual configured target
          still observes a single-valued `--platforms`, so transitions that read or set this
          flag keep working unchanged. Top-level targets whose `target_compatible_with` is not
          satisfied by a given target platform are skipped for that platform.
          """)
  public abstract List<Label> getPlatforms();

  public abstract void setPlatforms(List<Label> value);

  @Option(
      name = "extra_toolchains",
      defaultValue = "null",
      converter = CommaSeparatedOptionListConverter.class,
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      allowMultiple = true,
      effectTags = {
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      help =
          """
          The toolchain rules to be considered during toolchain resolution.
          Toolchains can be specified by exact target, or as a target pattern.
          These toolchains will be considered before those declared in the `WORKSPACE` file
          by `register_toolchains()`.
          """)
  public abstract List<String> getExtraToolchains();

  public abstract void setExtraToolchains(List<String> value);

  @Option(
      name = "toolchain_resolution_debug",
      defaultValue = "-.*", // By default, exclude everything.
      converter = RegexFilter.RegexFilterConverter.class,
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Print debug information during toolchain resolution. The flag takes a regex, which is"
              + " checked against toolchain types and specific targets to see which to debug. "
              + "Multiple regexes may be  separated by commas, and then each regex is checked "
              + "separately. Note: The output of this flag is very complex and will likely only be "
              + "useful to experts in toolchain resolution.")
  public abstract RegexFilter getToolchainResolutionDebug();

  @Option(
      name = "incompatible_use_toolchain_resolution_for_java_rules",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = OptionEffectTag.UNKNOWN,
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help = "No-op. Kept here for backwards compatibility.")
  public abstract boolean getUseToolchainResolutionForJavaRules();

  @Option(
      name = "platform_mappings",
      converter = PlatformMappingKeyConverter.class,
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.TOOLCHAIN,
      effectTags = {
        OptionEffectTag.AFFECTS_OUTPUTS,
        OptionEffectTag.CHANGES_INPUTS,
        OptionEffectTag.LOADING_AND_ANALYSIS
      },
      metadataTags = {
        OptionMetadataTag.NON_CONFIGURABLE,
      },
      help =
          """
          The location of a mapping file that describes which platform to use if none is set or
          which flags to set when a platform already exists. Must be relative to the main
          workspace root. Defaults to `platform_mappings` (a file directly under the
          workspace root).
          """)
  public abstract PlatformMappingKey getPlatformMappingKey();

  /**
   * Deduplicate the given list, keeping the last copy of any duplicates.
   *
   * <p>Example: [a, b, a, c, b] -> [a, c, b]
   */
  private static ImmutableList<String> dedupeKeepingLast(ImmutableList<String> values) {
    // Check common cases.
    if (values.size() <= 1) {
      return values;
    }

    // Reverse the list and then deduplicate.
    ImmutableList<String> reversedResult =
        values.reverse().stream().distinct().collect(toImmutableList());

    // If there were no duplicates, return the exact same instance we got.
    if (reversedResult.size() == values.size()) {
      return values;
    }

    // Reverse the result to get back to the original order.
    return reversedResult.reverse();
  }

  @Override
  public PlatformOptions getNormalized() {
    PlatformOptions result = (PlatformOptions) clone();
    result.setExtraToolchains(
        dedupeKeepingLast(
            result.getExtraToolchains() == null
                ? ImmutableList.of()
                : ImmutableList.copyOf(result.getExtraToolchains())));
    // Multiple target platforms are only meaningful for the top-level configuration, which
    // BuildView splits into one single-platform configuration per entry. Deduplicating here keeps
    // that split (and the resulting output directories) free of redundant copies. Note that the
    // order is preserved: it decides which platform is the build's primary one.
    List<Label> platforms = result.getPlatforms();
    if (platforms != null && platforms.size() > 1) {
      ImmutableList<Label> dedupedPlatforms = ImmutableSet.copyOf(platforms).asList();
      if (dedupedPlatforms.size() != platforms.size()) {
        result.setPlatforms(dedupedPlatforms);
      }
    }
    return result;
  }

  /**
   * Returns the intended target platform value based on options defined in this fragment.
   *
   * <p>If multiple target platforms are set, this returns the first one. Configurations that reach
   * a configured target always hold exactly one target platform, so this is only ambiguous for the
   * top-level options before {@link #splitByTargetPlatform} has split them.
   */
  public Label computeTargetPlatform() {
    if (!getPlatforms().isEmpty()) {
      return Iterables.getFirst(getPlatforms(), null);
    } else {
      // Default to the host platform, whatever it is.
      return getHostPlatform();
    }
  }

  /**
   * Splits top-level {@link BuildOptions} into one instance per target platform listed in {@code
   * --platforms}.
   *
   * <p>Each returned instance has a single-valued {@code --platforms}, so every configured target
   * created from them sees exactly one target platform and transitions that read or set {@code
   * --platforms} keep working unchanged.
   *
   * <p>Returns the input unchanged (as a singleton list) if at most one target platform is set,
   * which is the overwhelmingly common case.
   */
  public static ImmutableList<BuildOptions> splitByTargetPlatform(BuildOptions buildOptions) {
    PlatformOptions platformOptions = buildOptions.get(PlatformOptions.class);
    if (platformOptions == null
        || platformOptions.getPlatforms() == null
        || platformOptions.getPlatforms().size() <= 1) {
      return ImmutableList.of(buildOptions);
    }
    ImmutableList.Builder<BuildOptions> result = ImmutableList.builder();
    for (Label platform : platformOptions.getPlatforms()) {
      PlatformOptions singlePlatform = (PlatformOptions) platformOptions.clone();
      singlePlatform.setPlatforms(ImmutableList.of(platform));
      result.add(buildOptions.toBuilder().addFragmentOptions(singlePlatform).build());
    }
    return result.build();
  }

  /**
   * Converter for {@code --host_platform} that returns the default host platform if the flag is set
   * to empty string.
   */
  private static final class HostPlatformConverter extends LabelConverter {
    @Override
    @Nullable
    public Label convert(String input, Object conversionContext) throws OptionsParsingException {
      if (input.isEmpty()) {
        return super.convert(DEFAULT_HOST_PLATFORM, conversionContext);
      }
      return super.convert(input, conversionContext);
    }
  }

  /**
   * Converter for {@code --platform_mappings} that creates a canonical {@link PlatformMappingKey}
   * for the build.
   */
  private static final class PlatformMappingKeyConverter implements Converter<PlatformMappingKey> {
    private final PathFragmentConverter pathConverter = new PathFragmentConverter();

    @Override
    public PlatformMappingKey convert(String input, @Nullable Object conversionContext)
        throws OptionsParsingException {
      if (input.isEmpty()) {
        return PlatformMappingKey.DEFAULT;
      }
      PathFragment path = pathConverter.convert(input);
      if (path.isAbsolute()) {
        throw new OptionsParsingException("Expected relative path but got '" + input + "'.");
      }
      return PlatformMappingKey.createExplicitlySet(path);
    }

    @Override
    public boolean starlarkConvertible() {
      return true;
    }

    @Override
    public String reverseForStarlark(Object converted) {
      var key = (PlatformMappingKey) converted;
      return key.equals(PlatformMappingKey.DEFAULT)
          ? ""
          : key.getWorkspaceRelativeMappingPath().getPathString();
    }

    @Override
    public String getTypeDescription() {
      return "a main workspace-relative path";
    }
  }

}
