/*
 * Created by IntelliJ IDEA.
 * User: Alexander.Kitaev
 * Date: 20.07.2006
 * Time: 19:09:29
 */
package org.jetbrains.idea.svn.status;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.actions.VcsContextFactory;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.HashMap;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.Map;

public class SvnDiffEditor implements ISVNEditor {
  private VirtualFile mySourceRoot;
  private SVNRepository mySource;
  private SVNRepository myTarget;
  private long myTargetRevision;
  private boolean myReverse;

  private Map<String, Change> myChanges;

  public SvnDiffEditor(@NotNull SVNRepository source, SVNRepository target, long targetRevision, boolean reverse) {
    mySource = source;
    myTarget = target;
    myTargetRevision = targetRevision;
    myChanges = new HashMap<String, Change>();
    myReverse = reverse;
  }

  public SvnDiffEditor(@NotNull final VirtualFile sourceRoot, final SVNRepository target, long targetRevision,
                       boolean reverse) {
    mySourceRoot = sourceRoot;
    myTarget = target;
    myTargetRevision = targetRevision;
    myChanges = new HashMap<String, Change>();
    myReverse = reverse;
  }

  public Map<String, Change> getChangesMap() {
    return myChanges;
  }

  private ContentRevision createBeforeRevision(final String path) {
    if (mySource != null) {
      return new DiffContentRevision(path, mySource, -1);
    }
    // 'path' includes the first component of the root local path
    File f = new File(mySourceRoot.getParent().getPath(), path);
    FilePath filePath = VcsContextFactory.SERVICE.getInstance().createFilePathOn(f);
    return CurrentContentRevision.create(filePath);
  }

  private DiffContentRevision createAfterRevision(final String path) {
    if (mySourceRoot != null) {
      File f = new File(mySourceRoot.getParent().getPath(), path);
      FilePath filePath = VcsContextFactory.SERVICE.getInstance().createFilePathOn(f);
      return new DiffContentRevision(path, myTarget, myTargetRevision, filePath);
    }
    return new DiffContentRevision(path, myTarget, myTargetRevision);
  }

  public void targetRevision(long revision) throws SVNException {
  }
  public void openRoot(long revision) throws SVNException {
  }

  public void deleteEntry(String path, long revision) throws SVNException {
    // deleted - null for target, existing for source.
    Change change = createChange(path, FileStatus.DELETED);
    myChanges.put(path, change);
  }

  public void absentDir(String path) throws SVNException {
  }
  public void absentFile(String path) throws SVNException {
  }
  public void addDir(String path, String copyFromPath, long copyFromRevision) throws SVNException {
    FileStatus status = FileStatus.ADDED;
    if (myChanges.containsKey(path) && myChanges.get(path).getFileStatus() == FileStatus.DELETED) {
      // replaced file
      myChanges.remove(path);
      status = FileStatus.MODIFIED;
    }
    Change change = createChange(path, status);
    myChanges.put(path, change);
  }

  private Change createChange(final String path, final FileStatus status) {
    final ContentRevision beforeRevision = createBeforeRevision(path);
    final DiffContentRevision afterRevision = createAfterRevision(path);
    if (myReverse) {
      if (status == FileStatus.ADDED) {
        return new Change(afterRevision, null);
      }
      if (status == FileStatus.DELETED) {
        return new Change(null, beforeRevision);
      }
      return new Change(afterRevision, beforeRevision, status);
    }
    return new Change(status == FileStatus.ADDED ? null : beforeRevision,
                      status == FileStatus.DELETED ? null : afterRevision,
                      status);
  }

  public void openDir(String path, long revision) throws SVNException {
  }
  public void changeDirProperty(String name, String value) throws SVNException {
  }
  public void closeDir() throws SVNException {
  }

  public void addFile(String path, String copyFromPath, long copyFromRevision) throws SVNException {
    FileStatus status = FileStatus.ADDED;
    if (myChanges.containsKey(path) && myChanges.get(path).getFileStatus() == FileStatus.DELETED) {
      // replaced file
      myChanges.remove(path);
      status = FileStatus.MODIFIED;
    }
    Change change = createChange(path, status);
    myChanges.put(path, change);
  }

  public void openFile(String path, long revision) throws SVNException {
    Change change = createChange(path, FileStatus.MODIFIED);
    myChanges.put(path, change);
  }

  public void changeFileProperty(String path, String name, String value) throws SVNException {
  }
  public void closeFile(String path, String textChecksum) throws SVNException {
  }

  public void abortEdit() throws SVNException {
  }

  public void applyTextDelta(String path, String baseChecksum) throws SVNException {
  }

  public OutputStream textDeltaChunk(String path, SVNDiffWindow diffWindow) throws SVNException {
    return null;
  }

  public void textDeltaEnd(String path) throws SVNException {
  }

  public SVNCommitInfo closeEdit() throws SVNException {
    return null;
  }

  private static class DiffContentRevision implements ContentRevision {
    private String myPath;
    private SVNRepository myRepository;
    private String myContents;
    private FilePath myFilePath;
    private long myRevision;

    public DiffContentRevision(String path, @NotNull SVNRepository repos, long revision) {
      this(path, repos, revision, VcsUtil.getFilePath(path));
    }

    public DiffContentRevision(final String path, final SVNRepository repository, final long revision, final FilePath filePath) {
      myPath = path;
      myRepository = repository;
      myFilePath = filePath;
      myRevision = revision;
    }

    @Nullable
    public String getContent() throws VcsException {
      if (myContents == null) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
        try {
          myRepository.getFile(myPath, -1, null, bos);
          myRepository.closeSession();
        } catch (SVNException e) {
          throw new VcsException(e);
        }
        myContents = new String(bos.toByteArray());
      }
      return myContents;
    }

    @NotNull
    public FilePath getFile() {
      return myFilePath;
    }

    @NotNull
    public VcsRevisionNumber getRevisionNumber() {
      return new VcsRevisionNumber.Long(myRevision);
    }
  }
}