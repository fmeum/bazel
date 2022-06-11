package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.RunfilesLibraryUsersProvider.RepositoryNameAndMapping;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Type.LabelClass;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

public final class RunfilesLibraryUsersCollector {

  private RunfilesLibraryUsersCollector() {}

  @Nullable
  public static RunfilesLibraryUsersProvider collectUsers(RuleContext ruleContext) {
    NestedSetBuilder<RepositoryNameAndMapping> users = NestedSetBuilder.compileOrder();
    for (TransitiveInfoCollection dep : getAllNonToolPrerequisites(ruleContext)) {
      RunfilesLibraryUsersProvider usersProvider = dep.getProvider(RunfilesLibraryUsersProvider.class);
      if (usersProvider != null) {
        users.addTransitive(usersProvider.getUsers());
      }
      if (dep.get(RunfilesLibraryInfo.PROVIDER.getKey()) != null) {
        users.add(new RepositoryNameAndMapping(ruleContext.getRepository(),
            ruleContext.getRule().getPackage().getRepositoryMapping()));
      }
    }
    if (users.isEmpty()) {
      return null;
    }
    return new RunfilesLibraryUsersProvider(users.build());
  }

  private static Iterable<TransitiveInfoCollection> getAllNonToolPrerequisites(
      RuleContext ruleContext) {
    List<TransitiveInfoCollection> prerequisites = new ArrayList<>();
    for (Attribute attribute : ruleContext.getRule().getAttributes()) {
      if (attribute.isToolDependency()) {
        continue;
      }
      prerequisites.addAll(attributeDependencyPrerequisites(attribute, ruleContext));
    }
    return prerequisites;
  }

  private static List<? extends TransitiveInfoCollection> attributeDependencyPrerequisites(
      Attribute attribute, RuleContext ruleContext) {
    if (attribute.getType().getLabelClass() == LabelClass.DEPENDENCY) {
      return ruleContext.getPrerequisites(attribute.getName());
    }
    return ImmutableList.of();
  }
}
