package com.example.camunda;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.DeploymentEvent;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.response.PublishMessageResponse;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.impl.oauth.OAuthCredentialsProvider;
import io.camunda.client.impl.oauth.OAuthCredentialsProviderBuilder;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Exercises Camunda Java Client 8.10 against a Camunda 8.9 self-managed stack.
 * Uses plain CamundaClient (Camunda Java Client) — no starter available for 8.10.
 */
@Component
public class CompatRunner implements CommandLineRunner {

  @Value("${camunda.client.zeebe.gateway-url:http://localhost:26500}")
  String gatewayUrl;

  @Value("${camunda.client.zeebe.rest-url:http://localhost:8080}")
  String restUrl;

  @Value("${camunda.client.auth.token-url:http://localhost:18080/auth/realms/camunda-platform/protocol/openid-connect/token}")
  String tokenUrl;

  @Value("${camunda.client.auth.client-id:zeebe}")
  String clientId;

  @Value("${camunda.client.auth.client-secret:secret}")
  String clientSecret;

  @Override
  public void run(String... args) throws Exception {
    OAuthCredentialsProvider creds =
        new OAuthCredentialsProviderBuilder()
            .audience("zeebe-api")
            .authorizationServerUrl(tokenUrl)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build();

    try (CamundaClient client =
        CamundaClient.newClientBuilder()
            .grpcAddress(URI.create(gatewayUrl))
            .restAddress(URI.create(restUrl))
            .credentialsProvider(creds)
            .build()) {

      log("Client built OK (8.10.0-alpha5-rc3)");

      // 1. Topology via gRPC gateway
      log("Topology: " + client.newTopologyRequest().send().join());

      // 2. Deploy a simple BPMN via REST
      DeploymentEvent deployment =
          client
              .newDeployResourceCommand()
              .addResourceFromClasspath("process.bpmn")
              .send()
              .join();
      log("Deployed: " + deployment.getKey() + " processes=" + deployment.getProcesses().size());

      // 3. Create process instance
      ProcessInstanceEvent instance =
          client
              .newCreateInstanceCommand()
              .bpmnProcessId("compat-process")
              .latestVersion()
              .variables(Map.of("orderId", 42, "createdVia", "client-8.10"))
              .send()
              .join();
      log("Instance started: " + instance.getProcessInstanceKey());

      // 4. Publish a message
      PublishMessageResponse msg =
          client
              .newPublishMessageCommand()
              .messageName("order-received")
              .correlationKey("42")
              .variables(Map.of("msgFrom", "810"))
              .send()
              .join();
      log("Message published: " + msg.getMessageKey());

      // 5. Search process instances (REST /v2/process-instances/search)
      var piSearch =
          client.newProcessInstanceSearchRequest().filter(f -> f.processInstanceKey(instance.getProcessInstanceKey())).send().join();
      log("PI search hits: " + piSearch.items().size() + " first=" + firstKey(piSearch.items()));

      // 6. Search user tasks (REST /v2/user-tasks/search)
      var tasks = client.newUserTaskSearchRequest().send().join();
      log("User task search hits: " + tasks.items().size());

      // 7. Job worker (gRPC)
      var jobWorker =
          client
              .newWorker()
              .jobType("compat-task")
              .handler(
                  (c, job) -> {
                    log("Job completed: " + job.getKey() + " vars=" + job.getVariables());
                    c.newCompleteCommand(job.getKey()).send().join();
                  })
              .open();
      Thread.sleep(3000);
      jobWorker.close();

      log("ALL STEPS COMPLETED");
    } catch (Exception e) {
      log("FAILURE: " + e);
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      log("ROOT CAUSE: " + cause);
      throw e;
    }
  }

  private long firstKey(java.util.List<ProcessInstance> items) {
    return items.isEmpty() ? -1 : items.get(0).getProcessInstanceKey();
  }

  private void log(String s) {
    System.out.println("[COMPAT] " + s);
  }
}
