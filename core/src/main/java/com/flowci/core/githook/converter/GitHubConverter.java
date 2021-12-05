/*
 * Copyright 2019 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flowci.core.githook.converter;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowci.core.common.domain.GitSource;
import com.flowci.core.githook.domain.*;
import com.flowci.core.githook.domain.GitPrTrigger.Source;
import com.flowci.core.githook.domain.GitTrigger.GitEvent;
import com.flowci.exception.ArgumentException;
import com.flowci.util.ObjectsHelper;
import com.google.common.collect.ImmutableMap;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;

/**
 * @author yang
 */
@Log4j2
@Component("gitHubConverter")
public class GitHubConverter extends TriggerConverter {

    public static final String Header = "X-GitHub-Event";

    public static final String Ping = "ping";

    public static final String Push = "push";

    public static final String Tag = "create";

    public static final String PR = "pull_request";

    private final Map<String, Function<InputStream, GitTrigger>> mapping =
            ImmutableMap.<String, Function<InputStream, GitTrigger>>builder()
                    .put(Ping, new EventConverter<>("Ping", PingEvent.class))
                    .put(Push, new EventConverter<>("Push", PushEvent.class))
                    .put(Tag, new EventConverter<>("Tag", TagEvent.class))
                    .put(PR, new EventConverter<>("PR", PrEvent.class))
                    .build();

    @Override
    GitSource getGitSource() {
        return GitSource.GITHUB;
    }

    @Override
    Map<String, Function<InputStream, GitTrigger>> getMapping() {
        return mapping;
    }

    // ======================================================
    //      Objects for GitHub
    // ======================================================

    private static class PingEvent implements GitTriggerable {

        @JsonProperty("hook_id")
        public String hookId;

        public PingHook hook;

        @Override
        public GitPingTrigger toTrigger() {
            GitPingTrigger trigger = new GitPingTrigger();
            trigger.setSource(GitSource.GITHUB);
            trigger.setEvent(GitEvent.PING);
            trigger.setActive(hook.active);
            trigger.setEvents(hook.events);
            trigger.setCreatedAt(hook.createdAt);
            return trigger;
        }
    }

    private static class PingHook {

        public boolean active;

        public Set<String> events;

        @JsonProperty("created_at")
        public String createdAt;

    }

    private static class PushEvent implements GitTriggerable {

        public String ref;

        public List<Commit> commits;

        @JsonProperty("head_commit")
        public Commit headCommit;

        public Author pusher;

        @Override
        public GitPushTrigger toTrigger() {
            if (Objects.isNull(headCommit)) {
                throw new ArgumentException("No commits data on Github push or tag event");
            }

            GitPushTrigger trigger = new GitPushTrigger();
            trigger.setSource(GitSource.GITHUB);
            trigger.setEvent(GitEvent.PUSH);
            trigger.setMessage(headCommit.message);
            trigger.setSender(pusher.toGitUser());

            ObjectsHelper.ifNotNull(commits, val -> {
                trigger.setNumOfCommit(val.size());
                trigger.setCommits(new ArrayList<>(val.size()));
                for (var c : val) {
                    trigger.getCommits().add(c.toGitCommit());
                }
            });

            return trigger;
        }

    }

    private static class TagEvent implements GitTriggerable {

        public String ref;

        @JsonProperty("ref_type")
        public String refType;

        @JsonProperty("master_branch")
        public String branch;

        @JsonProperty("description")
        public String desc;

        public Author sender;

        @Override
        public GitTrigger toTrigger() {
            if (!Objects.equals(refType, "tag")) {
                throw new ArgumentException("Unsupported event from github tag: ref_type = {0}", refType);
            }

            var t = new GitTagTrigger();
            t.setEvent(GitEvent.TAG);
            t.setSource(GitSource.GITHUB);
            t.setRef(ref);
            t.setMessage(desc);
            t.setCommits(Collections.emptyList());
            t.setSender(sender.toGitUser());
            return t;
        }
    }

    private static class PrEvent implements GitTriggerable {

        public static final String PrOpen = "opened";

        public static final String PrClosed = "closed";

        public String action;

        public String number;

        @JsonProperty("pull_request")
        public PrBody prBody;

        @JsonProperty("sender")
        public PrSender prSender;

        @Override
        public GitPrTrigger toTrigger() {
            GitPrTrigger trigger = new GitPrTrigger();
            trigger.setEvent(getEvent());
            trigger.setSource(GitSource.GITHUB);

            trigger.setNumber(number);
            trigger.setBody(prBody.body);
            trigger.setTitle(prBody.title);
            trigger.setUrl(prBody.url);
            trigger.setTime(prBody.time);
            trigger.setNumOfCommits(prBody.numOfCommits);
            trigger.setNumOfFileChanges(prBody.numOfFileChanges);
            trigger.setMerged(prBody.merged);

            Source head = new Source();
            head.setCommit(prBody.head.sha);
            head.setRef(prBody.head.ref);
            head.setRepoName(prBody.head.repo.fullName);
            head.setRepoUrl(prBody.head.repo.url);
            trigger.setHead(head);

            Source base = new Source();
            base.setCommit(prBody.base.sha);
            base.setRef(prBody.base.ref);
            base.setRepoName(prBody.base.repo.fullName);
            base.setRepoUrl(prBody.base.repo.url);
            trigger.setBase(base);

            GitUser sender = new GitUser()
                    .setId(prSender.id)
                    .setUsername(prSender.username);
            trigger.setSender(sender);

            return trigger;
        }

        private GitEvent getEvent() {
            if (action.equals(PrOpen)) {
                return GitEvent.PR_OPENED;
            }

            if (action.equals(PrClosed) && prBody.merged) {
                return GitEvent.PR_MERGED;
            }

            throw new ArgumentException("Cannot handle action {0} from pull request", action);
        }
    }

    private static class PrBody {

        @JsonProperty("html_url")
        public String url;

        public String title;

        public String body;

        @JsonProperty("created_at")
        public String time;

        public PrSource head;

        public PrSource base;

        @JsonProperty("commits")
        public String numOfCommits;

        @JsonProperty("changed_files")
        public String numOfFileChanges;

        public Boolean merged;
    }

    private static class PrSource {

        public String ref;

        public String sha;

        public PrRepo repo;
    }

    private static class PrRepo {

        public String id;

        @JsonProperty("full_name")
        public String fullName;

        @JsonProperty("html_url")
        public String url;
    }

    private static class PrSender {

        public String id;

        @JsonProperty("login")
        public String username;
    }

    private static class Commit {

        public String id;

        public String message;

        public String timestamp;

        public String url;

        public Author author;

        public GitCommit toGitCommit() {
            return GitCommit.of(id, message, timestamp, url, author.toGitUser());
        }
    }

    private static class Author {

        @JsonAlias("login")
        public String name;

        public String email;

        public String username;

        @JsonProperty("avatar_url")
        public String avatarUrl;

        public GitUser toGitUser() {
            return new GitUser()
                    .setEmail(email)
                    .setName(name)
                    .setAvatarLink(avatarUrl)
                    .setUsername(username);
        }

    }
}
