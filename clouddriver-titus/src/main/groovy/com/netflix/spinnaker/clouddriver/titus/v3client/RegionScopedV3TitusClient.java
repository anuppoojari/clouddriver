/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.titus.v3client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.netflix.frigga.Names;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.clouddriver.titus.client.TitusClient;
import com.netflix.spinnaker.clouddriver.titus.client.TitusClientObjectMapper;
import com.netflix.spinnaker.clouddriver.titus.client.TitusJobCustomizer;
import com.netflix.spinnaker.clouddriver.titus.client.TitusRegion;
import com.netflix.spinnaker.clouddriver.titus.client.model.*;
import com.netflix.spinnaker.clouddriver.titus.client.model.HealthStatus;
import com.netflix.spinnaker.clouddriver.titus.client.model.Job;
import com.netflix.spinnaker.clouddriver.titus.client.model.Task;
import com.netflix.titus.grpc.protogen.*;
import groovy.util.logging.Log;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Log
public class RegionScopedV3TitusClient implements TitusClient {

  /**
   * Default connect timeout in milliseconds
   */
  private static final long DEFAULT_CONNECT_TIMEOUT = 60000;

  /**
   * Default read timeout in milliseconds
   */
  private static final long DEFAULT_READ_TIMEOUT = 20000;

  /**
   * An instance of {@link TitusRegion} that this RegionScopedV3TitusClient will use
   */
  private final TitusRegion titusRegion;

  private final Registry registry;

  private final List<TitusJobCustomizer> titusJobCustomizers;

  private final String environment;

  private final ObjectMapper objectMapper;

  private final JobManagementServiceGrpc.JobManagementServiceBlockingStub grpcBlockingStub;


  public RegionScopedV3TitusClient(TitusRegion titusRegion, Registry registry, List<TitusJobCustomizer> titusJobCustomizers, String environment, String eurekaName, GrpcChannelFactory grpcChannelFactory) {
    this(titusRegion, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, TitusClientObjectMapper.configure(), registry, titusJobCustomizers, environment, eurekaName, grpcChannelFactory);
  }

  public RegionScopedV3TitusClient(TitusRegion titusRegion,
                                   long connectTimeoutMillis,
                                   long readTimeoutMillis,
                                   ObjectMapper objectMapper,
                                   Registry registry,
                                   List<TitusJobCustomizer> titusJobCustomizers,
                                   String environment,
                                   String eurekaName,
                                   GrpcChannelFactory channelFactory
  ) {
    this.titusRegion = titusRegion;
    this.registry = registry;
    this.titusJobCustomizers = titusJobCustomizers;
    this.environment = environment;
    this.objectMapper = objectMapper;

    String titusHost = "";
    try {
      URL titusUrl = new URL(titusRegion.getEndpoint());
      titusHost = titusUrl.getHost();
    } catch (Exception e) {

    }
    this.grpcBlockingStub = JobManagementServiceGrpc.newBlockingStub(channelFactory.build(titusRegion, environment, eurekaName, DEFAULT_CONNECT_TIMEOUT, registry));
  }

  // APIs
  // ------------------------------------------------------------------------------------------

  @Override
  public Job getJob(String jobId) {
    return new Job(grpcBlockingStub.findJob(JobId.newBuilder().setId(jobId).build()), getTasks(Arrays.asList(jobId), true).get(jobId));
  }

  @Override
  public Job findJobByName(String jobName) {
    JobQuery.Builder jobQuery = JobQuery.newBuilder()
      .putFilteringCriteria("jobType", "SERVICE")
      .putFilteringCriteria("attributes", "source:spinnaker,name:" + jobName)
      .putFilteringCriteria("attributes.op", "and");
    List<Job> results = getJobs(jobQuery);
    return results.isEmpty() ? null : results.get(0);
  }

  @Override
  public List<Job> findJobsByApplication(String application) {
    JobQuery.Builder jobQuery = JobQuery.newBuilder().putFilteringCriteria("appName", application);
    return getJobs(jobQuery);
  }

  @Override
  public String submitJob(SubmitJobRequest submitJobRequest) {
    JobDescription jobDescription = submitJobRequest.getJobDescription();
    if (jobDescription.getType() == null) {
      jobDescription.setType("service");
    }
    if (jobDescription.getUser() == null) {
      jobDescription.setUser("spinnaker@netflix.com");
    } else if (!jobDescription.getUser().contains("@")) {
      jobDescription.setUser(jobDescription.getUser() + "@netflix.com");
    }
    if (jobDescription.getJobGroupSequence() == null && jobDescription.getType().equals("service")) {
      try {
        int sequence = Names.parseName(jobDescription.getName()).getSequence();
        jobDescription.setJobGroupSequence(String.format("v%03d", sequence));
      } catch (Exception e) {
        // fail silently if we can't get a job group sequence
      }
    }
    jobDescription.getLabels().put("name", jobDescription.getName());
    jobDescription.getLabels().put("source", "spinnaker");
    jobDescription.getLabels().put("spinnakerAccount", submitJobRequest.getCredentials());
    for (TitusJobCustomizer customizer : titusJobCustomizers) {
      customizer.customize(jobDescription);
    }
    return grpcBlockingStub.createJob(jobDescription.getGrpcJobDescriptor()).getId();
  }

  @Override
  public Task getTask(String taskId) {
    // new Task(grpcBlockingStub.findTask(taskId));
    // return new Task(grpcBlockingStub.findTask(com.netflix.titus.grpc.protogen.TaskId.newBuilder().setId(taskId).build()));
    return null;
  }

  @Override
  public void resizeJob(ResizeJobRequest resizeJobRequest) {
    grpcBlockingStub.updateJobCapacity(JobCapacityUpdate.newBuilder()
      .setJobId(resizeJobRequest.getJobId())
      .setCapacity(Capacity.newBuilder()
        .setDesired(resizeJobRequest.getInstancesDesired())
        .setMax(resizeJobRequest.getInstancesMax())
        .setMin(resizeJobRequest.getInstancesMin())
      )
      .build()
    );
  }

  @Override
  public void activateJob(ActivateJobRequest activateJobRequest) {
    grpcBlockingStub.updateJobStatus(JobStatusUpdate.newBuilder().setId(activateJobRequest.getJobId()).setEnableStatus(activateJobRequest.getInService()).build());
  }

  @Override
  public void setAutoscaleEnabled(String jobId, boolean shouldEnable) {
    grpcBlockingStub.updateJobProcesses(
      JobProcessesUpdate.newBuilder()
        .setServiceJobProcesses(
          ServiceJobSpec.ServiceJobProcesses.newBuilder()
            .setDisableDecreaseDesired(shouldEnable)
            .setDisableIncreaseDesired(shouldEnable)
            .build()
        )
        .setJobId(jobId)
        .build()
    );
  }

  @Override
  public void terminateJob(TerminateJobRequest terminateJobRequest) {
    grpcBlockingStub.killJob(JobId.newBuilder().setId(terminateJobRequest.getJobId()).build());
  }

  @Override
  public void terminateTasksAndShrink(TerminateTasksAndShrinkJobRequest terminateTasksAndShrinkJob) {
    terminateTasksAndShrinkJob.getTaskIds().forEach(id ->
      grpcBlockingStub.killTask(TaskKillRequest.newBuilder().setTaskId(id).setShrink(terminateTasksAndShrinkJob.isShrink()).build())
    );
  }

  @Override
  public Map logsDownload(String taskId) {
    return null;
  }

  @Override
  public TitusHealth getHealth() {
    return new TitusHealth(HealthStatus.HEALTHY);
  }

  @Override
  public List<Job> getAllJobs() {
    JobQuery.Builder jobQuery = JobQuery.newBuilder()
      .putFilteringCriteria("jobType", "SERVICE")
      .putFilteringCriteria("attributes", "source:spinnaker");
    return getJobs(jobQuery);
  }

  private List<Job> getJobs(JobQuery.Builder jobQuery) {
    int currentPage = 0;
    int totalPages;
    List<Job> jobs = new ArrayList<>();
    List<com.netflix.titus.grpc.protogen.Job> grpcJobs = new ArrayList<>();
    do {
      jobQuery.setPage(Page.newBuilder().setPageNumber(currentPage).setPageSize(100));
      JobQuery criteria = jobQuery.build();
      JobQueryResult resultPage = grpcBlockingStub.findJobs(criteria);
      grpcJobs.addAll(resultPage.getItemsList());
      totalPages = resultPage.getPagination().getTotalPages();
      currentPage++;
    } while (totalPages > currentPage);
    List<String> jobIds = grpcJobs.stream().map(grpcJob -> grpcJob.getId()).collect(
      Collectors.toList()
    );
    Map<String, List<com.netflix.titus.grpc.protogen.Task>> tasks = getTasks(jobIds, false);
    return grpcJobs.stream().map(grpcJob -> new Job(grpcJob, tasks.get(grpcJob.getId()))).collect(Collectors.toList());
  }

  private Map<String, List<com.netflix.titus.grpc.protogen.Task>> getTasks(List<String> jobIds, boolean includeDoneJobs) {
    List<com.netflix.titus.grpc.protogen.Task> tasks = new ArrayList<>();
    TaskQueryResult taskResults;
    int currentTaskPage = 0;
    do {
      TaskQuery.Builder taskQueryBuilder = TaskQuery.newBuilder();
      taskQueryBuilder.setPage(Page.newBuilder().setPageNumber(currentTaskPage).setPageSize(100));
      taskQueryBuilder.putFilteringCriteria("jobIds", jobIds.stream().collect(Collectors.joining(",")));
      if (includeDoneJobs) {
        taskQueryBuilder.putFilteringCriteria("taskStates", "Accepted,Launched,StartInitiated,Started,KillInitiated,Finished");
      }
      taskResults = grpcBlockingStub.findTasks(
        taskQueryBuilder.build()
      );
      tasks.addAll(taskResults.getItemsList());
      currentTaskPage++;
    } while (taskResults.getPagination().getHasMore());
    return tasks.stream().collect(Collectors.groupingBy(task -> task.getJobId()));
  }

  @Override
  public Object getJobJson(String jobId) {
    return toJson(grpcBlockingStub.findJob(JobId.newBuilder().setId(jobId).build()));
  }

  @Override
  public Object getTaskJson(String taskId) {
    return toJson(grpcBlockingStub.findTask(TaskId.newBuilder().setId(taskId).build()));
  }

  private Object toJson(Message message) {
    Object job = null;
    try {
      job = objectMapper.readValue(JsonFormat.printer().print(message), Object.class);
    } catch (Exception e) {

    }
    return job;
  }

}
