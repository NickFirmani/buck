/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/** A provide for the {@link Preprocessor} and {@link Compiler} C/C++ drivers. */
public abstract class CxxToolProvider<T> {

  private static final Logger LOG = Logger.get(CxxToolProvider.class);
  private static final Pattern CLANG_VERSION_PATTERN =
      Pattern.compile(
          "\\s*("
              + // Ignore leading whitespace.
              "clang version [.0-9]*(\\s*\\(.*\\))?"
              + // Format used by opensource Clang.
              "|"
              + "Apple LLVM version [.0-9]*\\s*\\(clang-[.0-9]*\\)"
              + // Format used by Apple's clang.
              ")\\s*"); // Ignore trailing whitespace.

  private final ToolProvider toolProvider;
  private final Supplier<Type> type;
  private final boolean useUnixFileSeparator;

  private final LoadingCache<BuildRuleResolver, T> cache =
      CacheBuilder.newBuilder()
          .weakKeys()
          .build(
              new CacheLoader<BuildRuleResolver, T>() {
                @Override
                public T load(@Nonnull BuildRuleResolver resolver) {
                  return build(type.get(), toolProvider.resolve(resolver));
                }
              });

  private CxxToolProvider(
      ToolProvider toolProvider, Supplier<Type> type, boolean useUnixFileSeparator) {
    this.toolProvider = toolProvider;
    this.type = type;
    this.useUnixFileSeparator = useUnixFileSeparator;
  }

  private CxxToolProvider(ToolProvider toolProvider, Supplier<Type> type) {
    this(toolProvider, type, false);
  }

  /**
   * Build using a {@link ToolProvider} and a required type. It also allows to specify to use Unix
   * path separators for the NDK compiler.
   */
  public CxxToolProvider(ToolProvider toolProvider, Type type, boolean useUnixFileSeparator) {
    this(toolProvider, Suppliers.ofInstance(type), useUnixFileSeparator);
  }

  /** Build using a {@link ToolProvider} and a required type. */
  public CxxToolProvider(ToolProvider toolProvider, Type type) {
    this(toolProvider, Suppliers.ofInstance(type));
  }

  /**
   * Build using a {@link Path} and an optional type. If the type is absent, the tool will be
   * executed to infer it. It also allows to specify to use Unix path separators for the NDK
   * compiler.
   */
  public CxxToolProvider(
      Supplier<PathSourcePath> path, Optional<Type> type, boolean useUnixFileSeparator) {
    this(
        new ConstantToolProvider(new HashedFileTool(path)),
        type.map(Suppliers::ofInstance)
            .orElseGet(() -> MoreSuppliers.memoize(() -> getTypeFromPath(path.get()))::get),
        useUnixFileSeparator);
  }

  /**
   * Build using a {@link Path} and an optional type. If the type is absent, the tool will be
   * executed to infer it.
   */
  public CxxToolProvider(Supplier<PathSourcePath> path, Optional<Type> type) {
    this(
        new ConstantToolProvider(new HashedFileTool(path)),
        type.map(Suppliers::ofInstance)
            .orElseGet(() -> MoreSuppliers.memoize(() -> getTypeFromPath(path.get()))::get),
        false);
  }

  private static Type getTypeFromPath(PathSourcePath path) {
    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addCommand(path.getFilesystem().resolve(path.getRelativePath()).toString())
            .addCommand("--version")
            .build();
    ProcessExecutor.Result result;
    try {
      ProcessExecutor processExecutor = new DefaultProcessExecutor(Console.createNullConsole());
      result =
          processExecutor.launchAndExecute(
              params,
              EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }

    if (result.getExitCode() != 0) {
      String commandString = params.getCommand().toString();
      String message = result.getMessageForUnexpectedResult(commandString);
      LOG.error(message);
      throw new RuntimeException(message);
    }

    String stdout = result.getStdout().orElse("");
    Iterable<String> lines = Splitter.on(CharMatcher.anyOf("\r\n")).split(stdout);
    LOG.debug("Output of %s: %s", params.getCommand(), lines);
    return getTypeFromVersionOutput(lines);
  }

  @VisibleForTesting
  protected static Type getTypeFromVersionOutput(Iterable<String> lines) {
    for (String line : lines) {
      Matcher matcher = CLANG_VERSION_PATTERN.matcher(line);
      if (matcher.matches()) {
        return Type.CLANG;
      }
    }
    return Type.GCC;
  }

  protected abstract T build(Type type, Tool tool);

  public T resolve(BuildRuleResolver resolver) {
    return cache.getUnchecked(resolver);
  }

  public Iterable<BuildTarget> getParseTimeDeps() {
    return toolProvider.getParseTimeDeps();
  }

  public enum Type {
    CLANG,
    CLANG_CL,
    CLANG_WINDOWS,
    GCC,
    WINDOWS,
    WINDOWS_ML64
  }

  /** @returns whether the specific CxxTool is accepting paths with Unix path separator only. */
  public boolean getUseUnixPathSeparator() {
    return useUnixFileSeparator;
  }
}
