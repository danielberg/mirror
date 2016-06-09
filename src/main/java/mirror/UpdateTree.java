package mirror;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jooq.lambda.Seq;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

/**
 * A tree of file+directory metadata ({@link Update}s).
 *
 * Given comparing remote/local data is our main task, we store
 * both remote+local metadata within the same tree instance,
 * e.g. each node contains both it's respective remote+local Updates.
 *
 * All of the {@link Update}s within the UpdateTree should contain
 * metadata only, and as the tree is solely for tracking/diffing
 * the state of the remote vs. local directories.
 *
 * This class is not thread safe as it's assumed to be fed Updates
 * from a dedicated queue/thread, e.g. in {@link SyncLogic}.
 */
public class UpdateTree {

  private final Node root;
  private final PathRules extraIncludes;
  private final PathRules extraExcludes;

  public static UpdateTree newRoot() {
    // IntegrationTest currently depends on these values
    PathRules extraExcludes = new PathRules();
    extraExcludes.setRules(
      "tmp",
      "temp",
      "target",
      "build",
      "bin",
      "*___jb_bak___", // IntelliJ safe write files
      "*___jb_old___",
      ".*");
    PathRules extraIncludes = new PathRules();
    extraIncludes.setRules(
      "src/mainGeneratedRest",
      "src/mainGeneratedDataTemplate",
      "testGeneratedRest",
      "testGeneratedDataTemplate",
      "build/*/classes/mainGeneratedInternalUrns/",
      "build/*/resources/mainGeneratedInternalUrns/",
      "src_managed",
      "*-SNAPSHOT.jar",
      "*.iml",
      "*.ipr",
      "*.iws",
      ".classpath",
      ".project",
      ".gitignore");
    return new UpdateTree(extraExcludes, extraIncludes);
  }

  public static UpdateTree newRoot(PathRules extraExcludes, PathRules extraIncludes) {
    return new UpdateTree(extraExcludes, extraIncludes);
  }

  private UpdateTree(PathRules extraExcludes, PathRules extraIncludes) {
    this.extraExcludes = extraExcludes;
    this.extraIncludes = extraIncludes;
    this.root = new Node(null, "");
    this.root.setLocal(Update.newBuilder().setPath("").setDirectory(true).build());
    this.root.setRemote(Update.newBuilder().setPath("").setDirectory(true).build());
  }

  /**
   * Adds {@code update} to our tree of nodes. 
   *
   * We assume the updates come in a certain order, e.g. foo/bar.txt should have
   * it's directory foo added first.
   */
  public void addLocal(Update local) {
    addUpdate(local, true);
  }

  public void addRemote(Update remote) {
    addUpdate(remote, false);
  }

  private void addUpdate(Update update, boolean local) {
    if (update.getPath().startsWith("/") || update.getPath().endsWith("/")) {
      throw new IllegalArgumentException("Update path should not start or end with slash: " + update.getPath());
    }
    Node node = find(update.getPath());
    if (local) {
      node.setLocal(update);
    } else {
      node.setRemote(update);
    }
  }

  /** Invokes {@link visitor} at each node in the tree, including the root. */
  public void visit(Consumer<Node> visitor) {
    visit(root, n -> {
      visitor.accept(n);
      return true;
    });
  }

  /**
   * Invokes {@link visitor} at each dirty node in the tree, including the root.
   *
   * After this method completes, all nodes are reset to clean. 
   */
  public void visitDirty(Consumer<Node> visitor) {
    visit(root, n -> {
      if (n.isDirty) {
        visitor.accept(n);
        n.isDirty = false;
      }
      boolean cont = n.hasDirtyDecendent;
      n.hasDirtyDecendent = false;
      return cont;
    });
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    visit(node -> sb.append(node.getPath() //
      + " local="
      + node.local.getModTime()
      + " remote="
      + node.remote.getModTime()).append("\n"));
    return sb.toString();
  }

  @VisibleForTesting
  Node find(String path) {
    if ("".equals(path)) {
      return root;
    }
    // breaks up "foo/bar/zaz.txt", into [foo, bar, zaz.txt]
    List<Path> parts = Lists.newArrayList(Paths.get(path));
    // find parent directory
    Node current = root;
    for (Path part : parts) {
      current = current.getChild(part.getFileName().toString());
    }
    return current;
  }

  @VisibleForTesting
  List<Node> getChildren() {
    return root.children;
  }

  public enum NodeType {
    File, Directory, Symlink
  };

  /** Either a directory or file within the tree. */
  public class Node {
    private final Node parent;
    private final String path;
    private final String name;
    private final List<Node> children = new ArrayList<>();
    // should contain .gitignore + svn:ignore + custom excludes/includes
    private final PathRules ignoreRules = new PathRules();
    private boolean hasDirtyDecendent;
    private boolean isDirty;
    private Update local;
    private Update remote;
    private Boolean shouldIgnore;

    private Node(Node parent, String path) {
      this.parent = parent;
      this.path = path;
      this.name = Paths.get(path).getFileName().toString();
    }

    boolean isSameType() {
      return getType(local) == getType(remote);
    }

    private NodeType getType(Update u) {
      return u == null ? null : isDirectory(u) ? NodeType.Directory : isSymlink(u) ? NodeType.Symlink : NodeType.File;
    }

    Update getRemote() {
      return remote;
    }

    void setRemote(Update remote) {
      if (!path.equals(remote.getPath())) {
        throw new IllegalStateException("Path is not correct: " + path + " vs. " + remote.getPath());
      }
      this.remote = remote;
      updateParentIgnoreRulesIfNeeded();
      markDirty();
    }

    Update getLocal() {
      return local;
    }

    void setLocal(Update local) {
      if (!path.equals(local.getPath())) {
        throw new IllegalStateException("Path is not correct: " + path + " vs. " + local.getPath());
      }
      // The best we can do for guessing the mod time of deletions
      // is to take the old, known mod time and just tick 1
      if (local != null && this.local != null && local.getDelete() && local.getModTime() == 0L) {
        int tick = this.local.getDelete() ? 0 : 1;
        local = Update.newBuilder(local).setModTime(this.local.getModTime() + tick).build();
      }
      this.local = local;
      // If we're no longer a directory, or we got deleted, clear our children
      if (!isDirectory(local) || local.getDelete()) {
        children.clear();
      }
      updateParentIgnoreRulesIfNeeded();
      markDirty();
    }

    boolean isRemoteNewer() {
      return remote != null && (local == null || local.getModTime() < remote.getModTime());
    }

    boolean isLocalNewer() {
      return local != null && (remote == null || local.getModTime() > remote.getModTime());
    }

    String getName() {
      return name;
    }

    String getPath() {
      return path;
    }

    /** @return the node for {@code name}, and will create it if necessary */
    Node getChild(String name) {
      for (Node child : children) {
        if (child.getName().equals(name)) {
          return child;
        }
      }
      Node child = new Node(this, (root == this) ? name : getPath() + "/" + name);
      children.add(child);
      return child;
    }

    List<Node> getChildren() {
      return children;
    }

    void clearData() {
      remote = Update.newBuilder(remote).setData(ByteString.EMPTY).build();
    }

    boolean isFile(Update u) {
      return !isDirectory(u) && !isSymlink(u);
    }

    boolean isDirectory() {
      return local != null ? isDirectory(local) : remote != null ? isDirectory(remote) : false;
    }

    boolean isDirectory(Update u) {
      return u.getDirectory();
    }

    boolean isSymlink(Update u) {
      return !u.getSymlink().isEmpty();
    }

    /** @param p should be a relative path, e.g. a/b/c.txt. */
    boolean shouldIgnore() {
      if (shouldIgnore != null) {
        return shouldIgnore;
      }
      // we use arrays so that our forEach closure can calc all three in one iteration
      // (which avoids having to re-calc the relative path three times)
      boolean gitIgnored[] = { false };
      boolean extraIncluded[] = { false };
      boolean extraExcluded[] = { false };
      parents().forEach(node -> {
        // if our path is dir1/dir2/foo.txt, strip off dir1/ for dir1's .gitignore, so we pass dir2/foo.txt
        String relative = path.substring(node.path.length());
        gitIgnored[0] |= node.ignoreRules.shouldIgnore(relative, isDirectory());
        // besides parent .gitignores, also use our extra includes/excludes on each level of the path
        extraIncluded[0] |= extraIncludes.shouldIgnore(relative, isDirectory());
        extraExcluded[0] |= extraExcludes.shouldIgnore(relative, isDirectory());
      });
      shouldIgnore = (gitIgnored[0] || extraExcluded[0]) && !extraIncluded[0];
      return shouldIgnore;
    }

    void updateParentIgnoreRulesIfNeeded() {
      if (!".gitignore".equals(name)) {
        return;
      }
      if (isLocalNewer()) {
        parent.setIgnoreRules(local.getIgnoreString());
      } else if (isRemoteNewer()) {
        parent.setIgnoreRules(remote.getIgnoreString());
      }
    }

    void markDirty() {
      isDirty = true;
      parents().forEach(n -> n.hasDirtyDecendent = true);
    }

    void setIgnoreRules(String ignoreData) {
      ignoreRules.setRules(ignoreData);
      visit(this, n -> {
        n.shouldIgnore = null;
        return true;
      });
    }

    @Override
    public String toString() {
      return name;
    }

    private Seq<Node> parents() {
      return Seq.iterate(parent, t -> t.parent).limitUntil(Objects::isNull);
    }
  }

  /** Visits nodes in the tree, in breadth-first order, continuing if {@visitor} returns true. */
  private static void visit(Node start, Predicate<Node> visitor) {
    Queue<Node> queue = new LinkedBlockingQueue<Node>();
    queue.add(start);
    while (!queue.isEmpty()) {
      Node node = queue.remove();
      boolean cont = visitor.test(node);
      if (cont) {
        queue.addAll(node.children);
      }
    }
  }

}
