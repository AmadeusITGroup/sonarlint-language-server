/*
 * SonarLint Language Server
 * Copyright (C) 2009-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.ls.file;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import org.sonar.api.batch.fs.InputFile;
import org.sonarsource.sonarlint.core.client.api.common.ClientFileWalker;
import org.sonarsource.sonarlint.core.client.api.common.analysis.ClientInputFile;
import org.sonarsource.sonarlint.ls.DefaultClientInputFile;
import org.sonarsource.sonarlint.ls.LocalCodeFile;
import org.sonarsource.sonarlint.ls.SonarLintExtendedLanguageClient;
import org.sonarsource.sonarlint.ls.settings.WorkspaceFolderSettings;

public class LanguageServerFileWalker implements ClientFileWalker {
  private final Path baseDir;
  private final WorkspaceFolderSettings settings;
  private final Optional<SonarLintExtendedLanguageClient.GetJavaConfigResponse> javaConfig;
  private final FileTypeClassifier fileTypeClassifier;

  public LanguageServerFileWalker(Path baseDir, WorkspaceFolderSettings settings, Optional<SonarLintExtendedLanguageClient.GetJavaConfigResponse> javaConfig, FileTypeClassifier fileTypeClassifier) {
    this.baseDir = baseDir;
    this.settings = settings;
    this.javaConfig = javaConfig;
    this.fileTypeClassifier = fileTypeClassifier;
  }

  @Override
  public void walk(String language, InputFile.Type type, Consumer<ClientInputFile> fileConsumer) {
    try {
      Files.walk(baseDir)
        .filter(filePath -> filePath.toString().endsWith("." + language))
        .filter(filePath -> typeMatches(filePath.toUri(), type))
        .map(filePath -> toClientInputFile(filePath, language))
        .forEach(fileConsumer);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private boolean typeMatches(URI uri, InputFile.Type type) {
    boolean isTestFile = fileTypeClassifier.isTest(settings, uri, javaConfig);
    return isTestType(type) == isTestFile;
  }

  private boolean isTestType(InputFile.Type type) {
    return type == InputFile.Type.TEST;
  }

  private ClientInputFile toClientInputFile(Path filePath, String language) {
    URI fileUri = filePath.toUri();
    return new DefaultClientInputFile(
      fileUri,
      baseDir.relativize(filePath).toString(),
      LocalCodeFile.from(fileUri).content(),
      fileTypeClassifier.isTest(settings, fileUri, javaConfig),
      language);
  }
}
