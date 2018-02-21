package com.jetbrains.jetpad.vclang.library;

import com.jetbrains.jetpad.vclang.error.ErrorReporter;
import com.jetbrains.jetpad.vclang.module.ModulePath;
import com.jetbrains.jetpad.vclang.module.error.ExceptionError;
import com.jetbrains.jetpad.vclang.source.FileBinarySource;
import com.jetbrains.jetpad.vclang.source.GZIPStreamBinarySource;
import com.jetbrains.jetpad.vclang.source.Source;
import com.jetbrains.jetpad.vclang.typechecking.TypecheckerState;
import com.jetbrains.jetpad.vclang.util.FileUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UnmodifiableFileSourceLibrary extends UnmodifiableSourceLibrary {
  private final Path myBasePath;

  /**
   * Creates a new {@code UnmodifiableFileSourceLibrary}
   *
   * @param name              the name of this library.
   * @param basePath          a path from which files will be taken.
   * @param typecheckerState  a typechecker state in which the result of loading of cached modules will be stored.
   */
  public UnmodifiableFileSourceLibrary(String name, Path basePath, TypecheckerState typecheckerState) {
    super(name, typecheckerState);
    myBasePath = basePath;
  }

  @Nullable
  @Override
  public Source getBinarySource(ModulePath modulePath) {
    return new GZIPStreamBinarySource(new FileBinarySource(myBasePath, modulePath));
  }

  @Nullable
  @Override
  protected LibraryHeader loadHeader(ErrorReporter errorReporter) {
    // TODO[library]: load header from a config file instead.
    List<ModulePath> modules = new ArrayList<>();
    try {
      Files.walkFileTree(myBasePath, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (file.getFileName().toString().endsWith(FileUtils.SERIALIZED_EXTENSION)) {
            ModulePath modulePath = FileUtils.modulePath(myBasePath.relativize(file), FileUtils.SERIALIZED_EXTENSION);
            if (modulePath != null) {
              modules.add(modulePath);
            }
          }
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      errorReporter.report(new ExceptionError(e, getName()));
      return null;
    }

    return new LibraryHeader(modules, Collections.emptyList());
  }
}
