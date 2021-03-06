package com.monitorjbl.plugins;

import com.atlassian.bitbucket.build.BuildStatusSetEvent;
import com.atlassian.bitbucket.commit.Commit;
import com.atlassian.bitbucket.event.pull.PullRequestParticipantStatusUpdatedEvent;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestMergeRequest;
import com.atlassian.bitbucket.pull.PullRequestSearchRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.pull.PullRequestState;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;
import com.atlassian.event.api.EventListener;
import com.monitorjbl.plugins.AsyncProcessor.TaskProcessor;
import com.monitorjbl.plugins.config.Config;
import com.monitorjbl.plugins.config.ConfigDao;

import java.io.Serializable;

public class PullRequestListener {
  private static final String PR_APPROVE_BUCKET = "AUTOMERGE_PR_APPROVAL";
  private static final String BUILD_APPROVE_BUCKET = "AUTOMERGE_BUILD_APPROVAL";
  public static final int MAX_COMMITS = 1048576;

  private final AsyncProcessor asyncProcessor;
  private final ConfigDao configDao;
  private final PullRequestService prService;
  private final SecurityService securityService;
  private final RegexUtils regexUtils;

  private final ApprovalTaskProcessor approvalTaskProcessor = new ApprovalTaskProcessor();
  private final BuildTaskProcessor buildTaskProcessor = new BuildTaskProcessor();

  public PullRequestListener(AsyncProcessor asyncProcessor, ConfigDao configDao, PullRequestService prService,
                             SecurityService securityService, RegexUtils regexUtils) {
    this.asyncProcessor = asyncProcessor;
    this.configDao = configDao;
    this.prService = prService;
    this.securityService = securityService;
    this.regexUtils = regexUtils;
  }

  @EventListener
  public void prApprovalListener(PullRequestParticipantStatusUpdatedEvent event) {
    asyncProcessor.dispatch(PR_APPROVE_BUCKET, new ApprovalTask(event.getPullRequest()), approvalTaskProcessor);
  }

  @EventListener
  public void buildStatusListener(BuildStatusSetEvent event) {
    asyncProcessor.dispatch(BUILD_APPROVE_BUCKET, new ApprovalTask(event.getCommitId()), buildTaskProcessor);
  }

  void automergePullRequest(PullRequest pr) {
    Repository repo = pr.getToRef().getRepository();
    Config config = configDao.getConfigForRepo(repo.getProject().getKey(), repo.getSlug());
    String toBranch = regexUtils.formatBranchName(pr.getToRef().getId());
    String fromBranch = regexUtils.formatBranchName(pr.getFromRef().getId());

    if((regexUtils.match(config.getAutomergePRs(), toBranch) || regexUtils.match(config.getAutomergePRsFrom(), fromBranch)) &&
        !regexUtils.match(config.getBlockedPRs(), toBranch) && prService.canMerge(repo.getId(), pr.getId()).canMerge()) {
      securityService.impersonating(pr.getAuthor().getUser(), "Performing automerge on behalf of " + pr.getAuthor().getUser().getSlug()).call(() -> {
        prService.merge(new PullRequestMergeRequest.Builder(pr).build());
        return null;
      });
    }
  }

  PullRequest findPRByCommitId(String commitId) {
    int start = 0;
    Page<PullRequest> requests = null;
    while(requests == null || requests.getSize() > 0) {
      requests = prService.search(new PullRequestSearchRequest.Builder()
          .state(PullRequestState.OPEN)
          .build(), new PageRequestImpl(start, 10));
      for(PullRequest pr : requests.getValues()) {
        Page<Commit> commits = prService.getCommits(pr.getToRef().getRepository().getId(), pr.getId(), new PageRequestImpl(0, MAX_COMMITS));
        for(Commit c : commits.getValues()) {
          if(c.getId().equals(commitId)) {
            return pr;
          }
        }
      }
      start += 10;
    }
    return null;
  }

  private class ApprovalTaskProcessor extends TaskProcessor<ApprovalTask> {
    @Override
    public void handleTask(ApprovalTask task) {
      securityService.withPermission(Permission.ADMIN, "Automerge check (PR approval)").call(() -> {
        automergePullRequest(prService.getById(task.repositoryId, task.pullRequestId));
        return null;
      });
    }
  }

  private class BuildTaskProcessor extends TaskProcessor<ApprovalTask> {
    @Override
    public void handleTask(ApprovalTask task) {
      securityService.withPermission(Permission.ADMIN, "Automerge check (PR approval)").call(() -> {
        PullRequest pr = findPRByCommitId(task.commitId);
        if(pr != null) {
          automergePullRequest(pr);
        }
        return null;
      });
    }
  }

  public static class ApprovalTask implements Serializable {
    public final Long pullRequestId;
    public final int repositoryId;
    public final String commitId;

    public ApprovalTask(String commitId) {
      this(null, -1, commitId);
    }

    public ApprovalTask(PullRequest pr) {
      this(pr.getId(), pr.getToRef().getRepository().getId(), null);
    }

    public ApprovalTask(Long pullRequestId, Integer repositoryId, String commitId) {
      this.pullRequestId = pullRequestId;
      this.repositoryId = repositoryId;
      this.commitId = commitId;
    }
  }
}
