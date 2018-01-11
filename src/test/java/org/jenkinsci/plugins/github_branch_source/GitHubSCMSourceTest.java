/*
 * The MIT License
 *
 * Copyright (c) 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import com.cloudbees.jenkins.GitHubRepositoryNameContributor;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.SingleRootFileSource;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.SecurityRealm;
import hudson.util.ListBoxModel;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import jenkins.branch.BranchSource;
import jenkins.branch.MultiBranchProject;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMHeadOrigin;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class GitHubSCMSourceTest {
    /**
     * All tests in this class only use Jenkins for the extensions
     */
    @ClassRule
    public static JenkinsRule r = new JenkinsRule();

    public static WireMockRuleFactory factory = new WireMockRuleFactory();

    @Rule
    public WireMockRule githubRaw = factory.getRule(WireMockConfiguration.options()
            .dynamicPort()
            .usingFilesUnderClasspath("raw")
    );
    @Rule
    public WireMockRule githubApi = factory.getRule(WireMockConfiguration.options()
            .dynamicPort()
            .usingFilesUnderClasspath("api")
            .extensions(
                    new ResponseTransformer() {
                        @Override
                        public Response transform(Request request, Response response, FileSource files,
                                                  Parameters parameters) {
                            if ("application/json"
                                    .equals(response.getHeaders().getContentTypeHeader().mimeTypePart())) {
                                return Response.Builder.like(response)
                                        .but()
                                        .body(response.getBodyAsString()
                                                .replace("https://api.github.com/",
                                                        "http://localhost:" + githubApi.port() + "/")
                                                .replace("https://raw.githubusercontent.com/",
                                                        "http://localhost:" + githubRaw.port() + "/")
                                        )
                                        .build();
                            }
                            return response;
                        }

                        @Override
                        public String getName() {
                            return "url-rewrite";
                        }

                    })
    );
    private GitHubSCMSource source;

    @Before
    public void prepareMockGitHub() throws Exception {
        new File("src/test/resources/api/mappings").mkdirs();
        new File("src/test/resources/api/__files").mkdirs();
        new File("src/test/resources/raw/mappings").mkdirs();
        new File("src/test/resources/raw/__files").mkdirs();
        githubApi.enableRecordMappings(new SingleRootFileSource("src/test/resources/api/mappings"),
                new SingleRootFileSource("src/test/resources/api/__files"));
        githubRaw.enableRecordMappings(new SingleRootFileSource("src/test/resources/raw/mappings"),
                new SingleRootFileSource("src/test/resources/raw/__files"));
        githubApi.stubFor(
                get(urlMatching(".*")).atPriority(10).willReturn(aResponse().proxiedFrom("https://api.github.com/")));
        githubRaw.stubFor(get(urlMatching(".*")).atPriority(10)
                .willReturn(aResponse().proxiedFrom("https://raw.githubusercontent.com/")));
        source = new GitHubSCMSource(null, "http://localhost:" + githubApi.port(), GitHubSCMSource.DescriptorImpl.SAME, null, "cloudbeers", "yolo");
    }

    @Test
    @Issue("JENKINS-48035")
    public void testGitHubRepositoryNameContributor() throws IOException {
        WorkflowMultiBranchProject job = r.createProject(WorkflowMultiBranchProject.class);
        job.setSourcesList(Arrays.asList(new BranchSource(source)));
        Collection<GitHubRepositoryName> names = GitHubRepositoryNameContributor.parseAssociatedNames(job);
        assertThat(names, contains(allOf(
                hasProperty("userName", equalTo("cloudbeers")),
                hasProperty("repositoryName", equalTo("yolo"))
        )));
        //And specifically...
        names = new ArrayList<>();
        ExtensionList.lookup(GitHubRepositoryNameContributor.class).get(GitHubSCMSourceRepositoryNameContributor.class).parseAssociatedNames(job, names);
        assertThat(names, contains(allOf(
                hasProperty("userName", equalTo("cloudbeers")),
                hasProperty("repositoryName", equalTo("yolo"))
        )));
    }

    @Test
    @Issue("JENKINS-48035")
    public void testGitHubRepositoryNameContributor_When_not_GitHub() throws IOException {
        WorkflowMultiBranchProject job = r.createProject(WorkflowMultiBranchProject.class);
        job.setSourcesList(Arrays.asList(new BranchSource(new GitSCMSource("file://tmp/something"))));
        Collection<GitHubRepositoryName> names = GitHubRepositoryNameContributor.parseAssociatedNames(job);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
        //And specifically...
        names = new ArrayList<>();
        ExtensionList.lookup(GitHubRepositoryNameContributor.class).get(GitHubSCMSourceRepositoryNameContributor.class).parseAssociatedNames(job, names);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
    }

    @Test
    @Issue("JENKINS-48035")
    public void testGitHubRepositoryNameContributor_When_not_MultiBranch() throws IOException {
        FreeStyleProject job = r.createProject(FreeStyleProject.class);
        Collection<GitHubRepositoryName> names = GitHubRepositoryNameContributor.parseAssociatedNames((Item) job);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
        //And specifically...
        names = new ArrayList<>();
        ExtensionList.lookup(GitHubRepositoryNameContributor.class).get(GitHubSCMSourceRepositoryNameContributor.class).parseAssociatedNames((Item) job, names);
        assertThat(names, Matchers.<GitHubRepositoryName>empty());
    }

    @Test
    public void fetchSmokes() throws Exception {
        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }
        assertThat(byName.keySet(), containsInAnyOrder("PR-master", "master", "stephenc-patch-1"));
        assertThat(byName.get("PR-master"), instanceOf(PullRequestSCMHead.class));
        assertThat(revByName.get("PR-master"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-master")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1"
        )));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchAltConfig() throws Exception {
        source.setBuildForkPRMerge(false);
        source.setBuildForkPRHead(true);
        source.setBuildOriginPRMerge(false);
        source.setBuildOriginPRHead(false);
        SCMHeadObserver.Collector collector = SCMHeadObserver.collect();
        source.fetch(new SCMSourceCriteria() {
            @Override
            public boolean isHead(@NonNull Probe probe, @NonNull TaskListener listener) throws IOException {
                return probe.stat("README.md").getType() == SCMFile.Type.REGULAR_FILE;
            }
        }, collector, null, null);
        Map<String,SCMHead> byName = new HashMap<>();
        Map<String,SCMRevision> revByName = new HashMap<>();
        for (Map.Entry<SCMHead, SCMRevision> h: collector.result().entrySet())  {
            byName.put(h.getKey().getName(), h.getKey());
            revByName.put(h.getKey().getName(), h.getValue());
        }
        assertThat(byName.keySet(), containsInAnyOrder("PR-master", "master", "stephenc-patch-1"));
        assertThat(byName.get("PR-master"), instanceOf(PullRequestSCMHead.class));
        assertThat(revByName.get("PR-master"), is((SCMRevision) new PullRequestSCMRevision((PullRequestSCMHead)(byName.get("PR-master")),
                "8f1314fc3c8284d8c6d5886d473db98f2126071c",
                "c0e024f89969b976da165eecaa71e09dc60c3da1"
        )));

        assertThat(byName.get("master"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("master"),
                hasProperty("hash", is("8f1314fc3c8284d8c6d5886d473db98f2126071c")
                ));
        assertThat(byName.get("stephenc-patch-1"), instanceOf(BranchSCMHead.class));
        assertThat(revByName.get("stephenc-patch-1"),
                hasProperty("hash", is("095e69602bb95a278505e937e41d505ac3cdd263")
                ));
    }

    @Test
    public void fetchActions() throws Exception {
        assertThat(source.fetchActions(null, null), Matchers.<Action>containsInAnyOrder(
                Matchers.<Action>is(
                        new ObjectMetadataAction(null, "You only live once", "http://yolo.example.com")
                ),
                Matchers.<Action>is(
                        new GitHubDefaultBranch("cloudbeers", "yolo", "master")
                ),
                instanceOf(GitHubRepoMetadataAction.class),
                Matchers.<Action>is(new GitHubLink("icon-github-repo", "https://github.com/cloudbeers/yolo"))));
    }

    @Test
    public void getTrustedRevisionReturnsRevisionIfRepoOwnerAndPullRequestBranchOwnerAreSameWithDifferentCase() throws Exception {
        source.setBuildOriginPRHead(true);
        PullRequestSCMRevision revision = createRevision("CloudBeers");
        assertThat(source.getTrustedRevision(revision, new LogTaskListener(Logger.getAnonymousLogger(), Level.INFO)), sameInstance((SCMRevision) revision));
    }

    private PullRequestSCMRevision createRevision(String sourceOwner) {
        PullRequestSCMHead head = new PullRequestSCMHead("", sourceOwner, "yolo", "", 0, new BranchSCMHead("non-null"),
                SCMHeadOrigin.DEFAULT, ChangeRequestCheckoutStrategy.HEAD);
        return new PullRequestSCMRevision(head, "non-null", null);
    }

    @Test
    public void doFillCredentials() throws Exception {
        final GitHubSCMSource.DescriptorImpl d =
                r.jenkins.getDescriptorByType(GitHubSCMSource.DescriptorImpl.class);
        final WorkflowMultiBranchProject dummy = r.jenkins.add(new WorkflowMultiBranchProject(r.jenkins, "dummy"), "dummy");
        SecurityRealm realm = r.jenkins.getSecurityRealm();
        AuthorizationStrategy strategy = r.jenkins.getAuthorizationStrategy();
        try {
            r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
            MockAuthorizationStrategy mockStrategy = new MockAuthorizationStrategy();
            mockStrategy.grant(Jenkins.ADMINISTER).onRoot().to("admin");
            mockStrategy.grant(Item.CONFIGURE).onItems(dummy).to("bob");
            mockStrategy.grant(Item.EXTENDED_READ).onItems(dummy).to("jim");
            r.jenkins.setAuthorizationStrategy(mockStrategy);
            ACL.impersonate(User.get("admin").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting only the provided value so that form config unchanged", rsp, hasSize(1));
                    assertThat("Expecting only the provided value so that form config unchanged", rsp.get(0).value,
                            Matchers.is("does-not-exist"));
                    rsp = d.doFillCredentialsIdItems(null, "", "does-not-exist");
                    assertThat("Expecting just the empty entry", rsp, hasSize(1));
                    assertThat("Expecting just the empty entry", rsp.get(0).value, Matchers.is(""));
                }
            });
            ACL.impersonate(User.get("bob").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting just the empty entry", rsp, hasSize(1));
                    assertThat("Expecting just the empty entry", rsp.get(0).value, Matchers.is(""));
                    rsp = d.doFillCredentialsIdItems(null, "", "does-not-exist");
                    assertThat("Expecting only the provided value so that form config unchanged", rsp, hasSize(1));
                    assertThat("Expecting only the provided value so that form config unchanged", rsp.get(0).value,
                            Matchers.is("does-not-exist"));
                }
            });
            ACL.impersonate(User.get("jim").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting just the empty entry", rsp, hasSize(1));
                    assertThat("Expecting just the empty entry", rsp.get(0).value, Matchers.is(""));
                }
            });
            ACL.impersonate(User.get("sue").impersonate(), new Runnable() {
                @Override
                public void run() {
                    ListBoxModel rsp = d.doFillCredentialsIdItems(dummy, "", "does-not-exist");
                    assertThat("Expecting only the provided value so that form config unchanged", rsp, hasSize(1));
                    assertThat("Expecting only the provided value so that form config unchanged", rsp.get(0).value,
                            Matchers.is("does-not-exist"));
                }
            });
        } finally {
            r.jenkins.setSecurityRealm(realm);
            r.jenkins.setAuthorizationStrategy(strategy);
            r.jenkins.remove(dummy);
        }
    }

}
