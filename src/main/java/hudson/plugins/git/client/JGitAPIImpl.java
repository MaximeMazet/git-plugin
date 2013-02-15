package hudson.plugins.git.client;

import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.eclipse.jgit.lib.Constants.HEAD;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitAPIImpl implements IGitAPI {

    private final IGitAPI delegate;
    private final File workspace;
    private final TaskListener listener;

    public JGitAPIImpl(String gitExe, File workspace,
                       TaskListener listener, EnvVars environment) {
        this(gitExe, workspace, listener, environment, null);
    }

    public JGitAPIImpl(String gitExe, File workspace,
                         TaskListener listener, EnvVars environment, String reference) {
        this(new CliGitAPIImpl(gitExe, workspace, listener, environment, reference),
             workspace, listener);
    }

    private JGitAPIImpl(IGitAPI delegate, File workspace, TaskListener listener) {
        this.delegate = delegate;
        this.workspace = workspace;
        this.listener = listener;
    }

    public IGitAPI subGit(String subdir) {
        return new JGitAPIImpl(delegate, new File(workspace, subdir), listener);
    }

    public void init() throws GitException {
        Git.init().setDirectory(workspace).call();
    }

    public void checkout(String commitish) throws GitException {
        checkout(commitish, null);
    }

    public void checkout(String commitish, String branch) throws GitException {
        try {
            Git git = Git.open(workspace);

            // First, checkout to detached HEAD, so we can delete the branch.
            checkoutDetachedHead(git.getRepository(), commitish);

            if (branch!=null) {
                // Second, check to see if the branch actually exists, and then delete it if it does.
                for (Branch b : getBranches()) {
                    if (b.getName().equals(branch)) {
                        deleteBranch(branch);
                    }
                }
                // Lastly, checkout the branch, creating it in the process, using commitish as the start point.
                git.checkout().setName(branch).setForce(true).setStartPoint(commitish).call();
            }
        } catch (IOException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + commitish, e);
        } catch (GitAPIException e) {
            throw new GitException("Could not checkout " + branch + " with start point " + commitish, e);
        }
    }

    public void add(String filePattern) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.add().addFilepattern(filePattern).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void commit(String message) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.commit().setMessage(message).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void branch(String name) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.branchCreate().setName(name).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void deleteBranch(String name) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.branchDelete().setBranchNames(name).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public Set<Branch> getBranches() throws GitException {
        try {
            Git git = Git.open(workspace);
            List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            Set<Branch> branches = new HashSet<Branch>(refs.size());
            for (Ref ref : refs) {
                branches.add(new Branch(ref));
            }
            return branches;
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public void tag(String name, String message) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.tag().setName(name).setMessage(message).call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
    }

    public void reset() throws GitException {
        reset(false);
    }

    public void reset(boolean hard) throws GitException {
        try {
            Git git = Git.open(workspace);
            git.reset()
                    .setMode(hard ? ResetCommand.ResetType.HARD : ResetCommand.ResetType.MIXED)
                    .call();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public void fetch(String remote, RefSpec refspec) throws GitException {

        delegate.fetch(remote, refspec);

        /**
         * not working, as demonstrated by hudson.plugins.git.GitSCMTest#testMultipleBranchBuild
         * JGit FecthProcess don't let us set RefUpdate.force=true
         * @see http://stackoverflow.com/questions/14876321/jgit-fetch-dont-update-tag
         *
        listener.getLogger().println(
                "Fetching upstream changes"
                        + (remote != null ? " from " + remote : ""));

        try {
            Git git = Git.open(workspace);
            FetchCommand fetch = git.fetch().setTagOpt(TagOpt.FETCH_TAGS);
            if (remote != null) fetch.setRemote(remote);
            if (refspec != null) fetch.setRefSpecs(refspec));
            fetch.call();
        } catch (IOException e) {
            throw new GitException(e);
        } catch (GitAPIException e) {
            throw new GitException(e);
        }
         */
    }

    // --- low-level JGit stuff

    private void checkoutDetachedHead(Repository repo, String commitish) throws IOException {
        Ref head = repo.getRef(HEAD);
        RevWalk revWalk = new RevWalk(repo);
        AnyObjectId headId = head.getObjectId();
        RevCommit headCommit = headId == null ? null : revWalk.parseCommit(headId);
        RevTree headTree = headCommit == null ? null : headCommit.getTree();

        ObjectId target = ObjectId.fromString(commitish);
        RevCommit newCommit = revWalk.parseCommit(target);

        DirCache dc = repo.lockDirCache();
        try {
            DirCacheCheckout dco = new DirCacheCheckout(repo, headTree, dc, newCommit.getTree());
            dco.setFailOnConflict(true);
            dco.checkout();
        } finally {
            dc.unlock();
        }
        RefUpdate refUpdate = repo.updateRef(HEAD, true);
        refUpdate.setForceUpdate(true);
        refUpdate.setRefLogMessage("checkout: moving to " + commitish, false);
        refUpdate.setNewObjectId(newCommit);
        refUpdate.forceUpdate();
    }

    // --- delegates

    public void addNote(String note, String namespace) throws GitException {
        delegate.addNote(note, namespace);
    }

    public void appendNote(String note, String namespace) throws GitException {
        delegate.appendNote(note, namespace);
    }

    public void changelog(String revFrom, String revTo, OutputStream fos) throws GitException {
        delegate.changelog(revFrom, revTo, fos);
    }

    public void clean() throws GitException {
        delegate.clean();
    }

    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException {
        delegate.clone(rc, useShallowClone);
    }

    public void deleteTag(String tagName) throws GitException {
        delegate.deleteTag(tagName);
    }

    public String getAllLogEntries(String branch) {
        return delegate.getAllLogEntries(branch);
    }

    public ObjectId getHeadRev(String remoteRepoUrl, String branch) throws GitException {
        return delegate.getHeadRev(remoteRepoUrl, branch);
    }

    public Set<Branch> getRemoteBranches() throws GitException, IOException {
        return delegate.getRemoteBranches();
    }

    public String getRemoteUrl(String name) throws GitException {
        return delegate.getRemoteUrl(name);
    }

        public Repository getRepository() throws IOException {
        try {
            Git git = Git.open(workspace);
            return git.getRepository();
        } catch (IOException e) {
            throw new GitException(e);
        }
    }

    public List<IndexEntry> getSubmodules(String treeIsh) throws GitException {
        return delegate.getSubmodules(treeIsh);
    }

    public Set<String> getTagNames(String tagPattern) throws GitException {
        return delegate.getTagNames(tagPattern);
    }

    public boolean hasGitModules() throws GitException {
        return delegate.hasGitModules();
    }

    public boolean hasGitRepo() throws GitException {
        return delegate.hasGitRepo();
    }

    public boolean isCommitInRepo(ObjectId commit) throws GitException {
        return delegate.isCommitInRepo(commit);
    }

    public void merge(String revSpec) throws GitException {
        delegate.merge(revSpec);
    }

    public ObjectId mergeBase(ObjectId sha1, ObjectId sha12) {
        return delegate.mergeBase(sha1, sha12);
    }

    public void prune(RemoteConfig repository) throws GitException {
        delegate.prune(repository);
    }

    public void push(RemoteConfig repository, String revspec) throws GitException {
        delegate.push(repository, revspec);
    }

    public List<ObjectId> revListAll() throws GitException {
        return delegate.revListAll();
    }

    public ObjectId revParse(String revName) throws GitException {
        return delegate.revParse(revName);
    }

    public void setRemoteUrl(String name, String url) throws GitException {
        delegate.setRemoteUrl(name, url);
    }

    public void setupSubmoduleUrls(Revision rev, TaskListener listener) throws GitException {
        delegate.setupSubmoduleUrls(rev, listener);
    }

    public List<String> showRevision(ObjectId r, ObjectId from) throws GitException {
        return delegate.showRevision(r, from);
    }

    public void submoduleClean(boolean recursive) throws GitException {
        delegate.submoduleClean(recursive);
    }

    public void submoduleUpdate(boolean recursive) throws GitException {
        delegate.submoduleUpdate(recursive);
    }

    public boolean tagExists(String tagName) throws GitException {
        return delegate.tagExists(tagName);
    }


}