/*
 * SonarLint Language Server
 * Copyright (C) 2009-2025 SonarSource SA
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

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.sonarsource.sonarlint.ls.backend.BackendServiceFacade;
import org.sonarsource.sonarlint.ls.connected.ProjectBinding;
import org.sonarsource.sonarlint.ls.connected.ProjectBindingManager;
import org.sonarsource.sonarlint.ls.log.LanguageClientLogger;
import org.sonarsource.sonarlint.ls.util.CatchingRunnable;
import org.sonarsource.sonarlint.ls.util.Utils;

import static java.lang.String.format;
import static java.net.URI.create;

public class WorkspaceFoldersManager {
  private final Map<URI, WorkspaceFolderWrapper> folders = new ConcurrentHashMap<>();
  private final Map<String, Boolean> analysisReadiness = new ConcurrentHashMap<>();
  private final List<WorkspaceFolderLifecycleListener> listeners = new ArrayList<>();
  private ProjectBindingManager bindingManager;
  private final BackendServiceFacade backendServiceFacade;
  private final LanguageClientLogger logOutput;
  private final ExecutorService executor;

  public WorkspaceFoldersManager(BackendServiceFacade backendServiceFacade, LanguageClientLogger logOutput) {
    this(Executors.newCachedThreadPool(Utils.threadFactory("SonarLint folders manager", false)), backendServiceFacade, logOutput);
  }

  WorkspaceFoldersManager(ExecutorService executor, BackendServiceFacade backendServiceFacade, LanguageClientLogger logOutput) {
    this.executor = executor;
    this.backendServiceFacade = backendServiceFacade;
    this.logOutput = logOutput;
  }

  public void setBindingManager(ProjectBindingManager bindingManager) {
    this.bindingManager = bindingManager;
  }

  public void initialize(@Nullable List<WorkspaceFolder> workspaceFolders) {
    if (workspaceFolders != null) {
      workspaceFolders.forEach(wf -> {
        var uri = create(wf.getUri());
        addFolder(wf, uri);
      });
      executor.submit(new CatchingRunnable(() -> backendServiceFacade.getBackendService().addWorkspaceFolders(workspaceFolders, getBindingProvider()),
        t -> logOutput.errorWithStackTrace("Failed to initialize workspace folders.", t)));
    }
  }

  public void didChangeWorkspaceFolders(WorkspaceFoldersChangeEvent event) {
    logOutput.debug("Processing didChangeWorkspaceFolders event");
    for (var removed : event.getRemoved()) {
      var uri = create(removed.getUri());
      removeFolder(uri);
    }
    for (var added : event.getAdded()) {
      var uri = create(added.getUri());
      var addedWrapper = addFolder(added, uri);
      listeners.forEach(l -> l.added(addedWrapper));
    }
    executor.submit(new CatchingRunnable(() -> {
      backendServiceFacade.getBackendService().addWorkspaceFolders(event.getAdded(), getBindingProvider());
      event.getRemoved().forEach(removed -> removeFolderFromBackend(removed.getUri()));
    }, t -> logOutput.errorWithStackTrace("Failed to add workspace folder", t)));

  }

  @CheckForNull
  private WorkspaceFolderWrapper removeFolder(URI uri) {
    var removed = folders.remove(uri);
    if (removed == null) {
      logOutput.warn("Unregistered workspace folder was missing: " + uri);
      return null;
    }
    logOutput.debug(format("Folder %s removed", removed));
    listeners.forEach(l -> l.removed(removed));
    return removed;
  }

  private WorkspaceFolderWrapper addFolder(WorkspaceFolder added, URI uri) {
    var addedWrapper = new WorkspaceFolderWrapper(uri, added, logOutput);
    if (folders.put(uri, addedWrapper) != null) {
      logOutput.warn(format("Registered workspace folder %s was already added", addedWrapper));
    } else {
      logOutput.debug(format("Folder %s added", addedWrapper));
    }
    return addedWrapper;
  }

  private Function<WorkspaceFolder, Optional<ProjectBinding>> getBindingProvider() {
    return folder -> bindingManager.getBinding(create(folder.getUri()));
  }

  private void removeFolderFromBackend(String removedUri) {
    backendServiceFacade.getBackendService().removeWorkspaceFolder(removedUri);
  }

  public Optional<WorkspaceFolderWrapper> findFolderForFile(URI uri) {
    var folderUriCandidates = folders.keySet().stream()
      .filter(wfRoot -> isAncestor(wfRoot, uri))
      // Sort by path descending length to prefer the deepest one in case of multiple nested workspace folders
      .sorted(Comparator.<URI>comparingInt(wfRoot -> wfRoot.getPath().length()).reversed())
      .toList();
    if (folderUriCandidates.isEmpty()) {
      return Optional.empty();
    }
    if (folderUriCandidates.size() > 1) {
      logOutput.debug(format("Multiple candidates workspace folders to contains %s. Default to the deepest one.", uri));
    }
    return Optional.of(folders.get(folderUriCandidates.get(0)));
  }

  public Optional<WorkspaceFolderWrapper> getFolder(URI folderUri) {
    return Optional.ofNullable(folders.get(folderUri));
  }

  // Visible for testing
  static boolean isAncestor(URI folderUri, URI fileUri) {
    if (folderUri.isOpaque() || fileUri.isOpaque()) {
      throw new IllegalArgumentException("Only hierarchical URIs are supported");
    }
    if (!folderUri.getScheme().equalsIgnoreCase(fileUri.getScheme())) {
      return false;
    }
    if (!Objects.equals(folderUri.getHost(), fileUri.getHost())) {
      return false;
    }
    if (folderUri.getPort() != fileUri.getPort()) {
      return false;
    }
    if (Utils.uriHasFileScheme(folderUri)) {
      return Paths.get(fileUri).startsWith(Paths.get(folderUri));
    }
    // Assume "/" is the separator of "folders"
    var fileSegments = fileUri.getPath().split("/");
    var folderSegments = folderUri.getPath().split("/");
    return folderSegments.length <= fileSegments.length && Arrays.equals(folderSegments, Arrays.copyOfRange(fileSegments, 0, folderSegments.length));
  }

  public Collection<WorkspaceFolderWrapper> getAll() {
    return new ArrayList<>(folders.values());
  }

  public void addListener(WorkspaceFolderLifecycleListener listener) {
    listeners.add(listener);
  }

  public void removeListener(WorkspaceFolderLifecycleListener listener) {
    listeners.remove(listener);
  }

  public void shutdown() {
    Utils.shutdownAndAwait(executor, true);
  }

  public void updateAnalysisReadiness(Set<String> configurationScopeIds, boolean areReadyForAnalysis) {
    configurationScopeIds.forEach(s -> logOutput.debug(format("Analysis readiness changed for config scope `%s` to %b", s, areReadyForAnalysis)));
    configurationScopeIds.forEach(folderUri -> analysisReadiness.put(folderUri, areReadyForAnalysis));
  }

  public boolean isReadyForAnalysis(String folderUri) {
    return analysisReadiness.getOrDefault(folderUri, false);
  }

}
