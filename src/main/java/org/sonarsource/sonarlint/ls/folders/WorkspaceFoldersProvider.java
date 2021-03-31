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
package org.sonarsource.sonarlint.ls.folders;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.sonarsource.sonarlint.core.client.api.common.ModuleInfo;
import org.sonarsource.sonarlint.core.client.api.common.ModulesProvider;

public class WorkspaceFoldersProvider implements ModulesProvider<String> {

  public static String key(WorkspaceFolder folder) {
    return folder.getName();
  }

  private final WorkspaceFoldersManager workspaceFoldersManager;

  public WorkspaceFoldersProvider(WorkspaceFoldersManager workspaceFoldersManager) {
    this.workspaceFoldersManager = workspaceFoldersManager;
  }

  @Override
  public List<ModuleInfo<String>> getModules() {
    return workspaceFoldersManager.getAll().stream()
      .map(folder -> new ModuleInfo<>(key(folder.getLspFolder()), folder.getUri(), null))
      .collect(Collectors.toList());
  }
}
