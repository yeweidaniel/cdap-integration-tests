/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.security;

import co.cask.cdap.api.flow.flowlet.StreamEvent;
import co.cask.cdap.client.ApplicationClient;
import co.cask.cdap.client.DatasetClient;
import co.cask.cdap.client.NamespaceClient;
import co.cask.cdap.client.StreamClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.common.utils.Tasks;
import co.cask.cdap.proto.DatasetSpecificationSummary;
import co.cask.cdap.proto.NamespaceMeta;
import co.cask.cdap.proto.StreamDetail;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.KerberosPrincipalId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.StreamId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Privilege;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Basic test base for authorization, this test base contains tests without impersonation
 *
 * We create a namespace for most of the test cases since we want to make sure the privilege for each user is clean.
 */
public class BasicAuthorizationTest extends AuthorizationTestBase {

  /**
   * Test the basic grant operations. User should be able to list once he has the privilege on the namespace.
   */
  @Test
  public void testNamespacePrivileges() throws Exception {
    // set up clients that will be used in the test
    ClientConfig adminConfig = getClientConfig(fetchAccessToken(ADMIN_USER, ADMIN_USER));
    RESTClient adminClient = new RESTClient(adminConfig);
    adminClient.addListener(createRestClientListener());
    ClientConfig aliceConfig = getClientConfig(fetchAccessToken(ALICE, ALICE + PASSWORD_SUFFIX));
    RESTClient aliceClient = new RESTClient(aliceConfig);
    aliceClient.addListener(createRestClientListener());
    ClientConfig bobConfig = getClientConfig(fetchAccessToken(BOB, BOB + PASSWORD_SUFFIX));
    RESTClient bobClient = new RESTClient(bobConfig);
    bobClient.addListener(createRestClientListener());
    ClientConfig eveConfig = getClientConfig(fetchAccessToken(EVE, EVE + PASSWORD_SUFFIX));
    RESTClient eveClient = new RESTClient(eveConfig);
    eveClient.addListener(createRestClientListener());

    NamespaceId namespaceId = testNamespace.getNamespaceId();
    StreamId streamId = namespaceId.stream("testNamespacePrivileges");

    // pre-grant all required privileges
    // admin user only has privilges to the namespace
    authorizationTestClient.grant(ADMIN_USER, namespaceId, Action.ADMIN);
    String principal = testNamespace.getConfig().getPrincipal();
    if (principal != null) {
      authorizationTestClient.grant(ADMIN_USER, new KerberosPrincipalId(principal), Action.ADMIN);
    }
    // alice has access to all entities
    authorizationTestClient.grant(ALICE, namespaceId, Action.ADMIN);
    authorizationTestClient.grant(ALICE, streamId, Action.ADMIN);
    // eve only has access to the stream
    authorizationTestClient.grant(EVE, streamId, Action.ADMIN);
    authorizationTestClient.waitForAuthzCacheTimeout();

    // bob can't create namespace without having ADMIN privilege on the namespace
    try {
      createAndRegisterNamespace(testNamespace, bobConfig, bobClient);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      Assert.assertTrue(ex.getMessage().toLowerCase().contains(NOT_VISIBLE_MSG.toLowerCase()));
    }

    // ADMIN_USER can create namespace with ADMIN privilege on the namespace
    createAndRegisterNamespace(testNamespace, adminConfig, adminClient);

    ApplicationClient applicationClient = new ApplicationClient(bobConfig, bobClient);
    try {
      // list should fail initially since BOB does not have privilege on the namespace
      applicationClient.list(namespaceId);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      Assert.assertTrue(ex.getMessage().toLowerCase().contains(NOT_VISIBLE_MSG.toLowerCase()));
    }

    StreamClient aliceStreamClient = new StreamClient(aliceConfig, aliceClient);
    aliceStreamClient.create(streamId);

    // cdapitn shouldn't be able to list the stream since he doesn't have privilege on the stream
    StreamClient adminStreamClient = new StreamClient(adminConfig, adminClient);
    List<StreamDetail> streams = adminStreamClient.list(namespaceId);
    Assert.assertEquals(0, streams.size());

    // ADMIN cannot delete the namespace because he doesn't have privileges on the stream
    try {
      new NamespaceClient(adminConfig, adminClient).delete(namespaceId);
      Assert.fail();
    } catch (IOException ex) {
      // expected
       Assert.assertTrue(ex.getMessage().toLowerCase().contains(NO_PRIVILEGE_MESG.toLowerCase()));
    }

    // Alice will not able to delete the namespace since she does not have admin on the namespace,
    // even though she has admin on all the entities in the namespace
    try {
      new NamespaceClient(eveConfig, eveClient).delete(namespaceId);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // expected
    }

    // Alice will be able to delete the namespace
    new NamespaceClient(aliceConfig, aliceClient).delete(namespaceId);
  }

  /**
   * Test list privileges, enforce, and revoke privileges, this test needs cache invalidation inside
   */
  @Test
  public void testPrivilegesAndVisibility() throws Exception {
    // set up clients that will be used in the test
    ClientConfig adminConfig = getClientConfig(fetchAccessToken(ADMIN_USER, ADMIN_USER));
    RESTClient adminClient = new RESTClient(adminConfig);
    adminClient.addListener(createRestClientListener());
    ClientConfig aliceConfig = getClientConfig(fetchAccessToken(ALICE, ALICE + PASSWORD_SUFFIX));
    RESTClient aliceClient = new RESTClient(aliceConfig);
    aliceClient.addListener(createRestClientListener());
    ClientConfig bobConfig = getClientConfig(fetchAccessToken(BOB, BOB + PASSWORD_SUFFIX));
    RESTClient bobClient = new RESTClient(bobConfig);
    bobClient.addListener(createRestClientListener());
    ClientConfig eveConfig = getClientConfig(fetchAccessToken(EVE, EVE + PASSWORD_SUFFIX));
    RESTClient eveClient = new RESTClient(eveConfig);
    eveClient.addListener(createRestClientListener());

    // set up entities
    String namespacePrefix = "testPrivilegesAndVisibility";
    final NamespaceId namespaceId1 = new NamespaceId(namespacePrefix + 1);
    NamespaceId namespaceId2 = new NamespaceId(namespacePrefix + 2);

    DatasetId ds11 = namespaceId1.dataset("ds11");
    DatasetId ds12 = namespaceId1.dataset("ds12");
    DatasetId ds13 = namespaceId1.dataset("ds23");
    DatasetId ds21 = namespaceId2.dataset("ds21");
    Set<DatasetId> datasetSet = Sets.newHashSet(ds11, ds12, ds13, ds21);

    StreamId stream11 = namespaceId1.stream("stream11");
    StreamId stream12 = namespaceId1.stream("stream12");
    StreamId stream13 = namespaceId1.stream("stream13");
    StreamId stream21 = namespaceId2.stream("stream21");
    StreamId stream22 = namespaceId2.stream("stream22");
    final StreamId stream23 = namespaceId2.stream("stream23");
    Set<StreamId> streamSet = Sets.newHashSet(stream11, stream12, stream13, stream21, stream22, stream23);

    // Bob's privileges will be revoked in later test and granted to user Alice.
    Set<Privilege> bobPrivileges = new HashSet<>();

    // admin user will have admin on all these entities
    authorizationTestClient.grant(ADMIN_USER, namespaceId1, Action.ADMIN);
    authorizationTestClient.grant(ADMIN_USER, namespaceId2, Action.ADMIN);
    for (DatasetId datasetId : datasetSet) {
      authorizationTestClient.grant(ADMIN_USER, datasetId, Action.ADMIN);
    }
    for (StreamId streamId : streamSet) {
      authorizationTestClient.grant(ADMIN_USER, streamId, Action.ADMIN);
    }

    // Grant privileges on entities ending with 1 to alice
    authorizationTestClient.grant(ALICE, ds11, Action.EXECUTE);
    authorizationTestClient.grant(ALICE, ds21, Action.WRITE);
    authorizationTestClient.grant(ALICE, stream11, Action.READ);
    authorizationTestClient.grant(ALICE, stream21, Action.WRITE);

    // Grant privileges on entities ending with 3 to BOB
    authorizationTestClient.grant(BOB, ds13, Action.ADMIN);
    bobPrivileges.add(new Privilege(ds13, Action.ADMIN));
    authorizationTestClient.grant(BOB, stream13, Action.EXECUTE);
    bobPrivileges.add(new Privilege(stream13, Action.EXECUTE));
    authorizationTestClient.grant(BOB, stream23, Action.READ);
    bobPrivileges.add(new Privilege(stream23, Action.READ));
    authorizationTestClient.grant(BOB, stream23, Action.WRITE);
    bobPrivileges.add(new Privilege(stream23, Action.WRITE));

    // Grant privileges on entity stream22 to eve
    authorizationTestClient.grant(EVE, stream22, Action.EXECUTE);
    authorizationTestClient.waitForAuthzCacheTimeout();

    // create these entities
    NamespaceMeta nsMeta1 = new NamespaceMeta.Builder().setName(namespaceId1).build();
    NamespaceMeta nsMeta2 = new NamespaceMeta.Builder().setName(namespaceId2).build();
    createAndRegisterNamespace(nsMeta1, adminConfig, adminClient);
    createAndRegisterNamespace(nsMeta2, adminConfig, adminClient);
    DatasetClient dsAdminClient = new DatasetClient(adminConfig, adminClient);
    for (DatasetId datasetId : datasetSet) {
      dsAdminClient.create(datasetId, "table");
    }
    StreamClient streamAdminClient = new StreamClient(adminConfig, adminClient);
    for (StreamId streamId : streamSet) {
      streamAdminClient.create(streamId);
    }

    // test visibility
    // admin should see all entities
    Assert.assertEquals(Sets.newHashSet(nsMeta1, nsMeta2),
                        Sets.newHashSet(new NamespaceClient(adminConfig, adminClient).list()));
    Assert.assertEquals(Sets.newHashSet(ds11, ds12, ds13),
                        toDatasetId(namespaceId1, dsAdminClient.list(namespaceId1)));
    Assert.assertEquals(Sets.newHashSet(ds21), toDatasetId(namespaceId2, dsAdminClient.list(namespaceId2)));
    Assert.assertEquals(Sets.newHashSet(stream11, stream12, stream13),
                        toStreamId(namespaceId1, streamAdminClient.list(namespaceId1)));
    Assert.assertEquals(Sets.newHashSet(stream21, stream22, stream23),
                        toStreamId(namespaceId2, streamAdminClient.list(namespaceId2)));

    // test alice visibility should only see the namespace and the entities she has privileges on
    Assert.assertEquals(Sets.newHashSet(nsMeta1, nsMeta2),
                        Sets.newHashSet(new NamespaceClient(aliceConfig, aliceClient).list()));
    DatasetClient datasetAliceClient = new DatasetClient(aliceConfig, aliceClient);
    Assert.assertEquals(Sets.newHashSet(ds11),
                        toDatasetId(namespaceId1, datasetAliceClient.list(namespaceId1)));
    Assert.assertEquals(Sets.newHashSet(ds21),
                        toDatasetId(namespaceId2, datasetAliceClient.list(namespaceId2)));
    StreamClient streamAliceClient = new StreamClient(aliceConfig, aliceClient);
    Assert.assertEquals(Sets.newHashSet(stream11),
                        toStreamId(namespaceId1, streamAliceClient.list(namespaceId1)));
    Assert.assertEquals(Sets.newHashSet(stream21),
                        toStreamId(namespaceId2, streamAliceClient.list(namespaceId2)));

    // test bob visibility should only see the namespace and the entities he has privileges on
    Assert.assertEquals(Sets.newHashSet(nsMeta1, nsMeta2),
                        Sets.newHashSet(new NamespaceClient(bobConfig, bobClient).list()));
    DatasetClient datasetBobClient = new DatasetClient(bobConfig, bobClient);
    Assert.assertEquals(Sets.newHashSet(ds13),
                        toDatasetId(namespaceId1, datasetBobClient.list(namespaceId1)));
    Assert.assertEquals(Sets.newHashSet(),
                        toDatasetId(namespaceId2, datasetBobClient.list(namespaceId2)));
    final StreamClient streamBobClient = new StreamClient(bobConfig, bobClient);
    Assert.assertEquals(Sets.newHashSet(stream13),
                        toStreamId(namespaceId1, streamBobClient.list(namespaceId1)));
    Assert.assertEquals(Sets.newHashSet(stream23),
                        toStreamId(namespaceId2, streamBobClient.list(namespaceId2)));

    // test eve visibility should only see the namespace2 and the entities he has privileges on
    Assert.assertEquals(Sets.newHashSet(nsMeta2),
                        Sets.newHashSet(new NamespaceClient(eveConfig, eveClient).list()));
    DatasetClient datasetEveClient = new DatasetClient(eveConfig, eveClient);
    try {
      datasetEveClient.list(namespaceId1);
      Assert.fail();
    } catch (UnauthorizedException e) {
      Assert.assertTrue(e.getMessage().toLowerCase().contains(NOT_VISIBLE_MSG.toLowerCase()));
    }
    Assert.assertEquals(Sets.newHashSet(),
                        toDatasetId(namespaceId2, datasetEveClient.list(namespaceId2)));
    StreamClient streamEveClient = new StreamClient(eveConfig, eveClient);
    try {
      streamEveClient.list(namespaceId1);
      Assert.fail();
    } catch (UnauthorizedException e) {
      Assert.assertTrue(e.getMessage().toLowerCase().contains(NOT_VISIBLE_MSG.toLowerCase()));
    }
    Assert.assertEquals(Sets.newHashSet(stream22),
                        toStreamId(namespaceId2, streamEveClient.list(namespaceId2)));

    // test some auth enforce operations
    try {
      // update needs ADMIN but alice only has EXECUTE
      datasetAliceClient.update(ds11, Collections.<String, String>emptyMap());
      Assert.fail();
    } catch (UnauthorizedException e) {
      Assert.assertTrue(e.getMessage().contains(NO_PRIVILEGE_MESG));
    }
    verifyStreamReadWritePrivilege(streamAliceClient, stream11, Sets.newHashSet(Action.READ));
    verifyStreamReadWritePrivilege(streamAliceClient, stream21, Sets.newHashSet(Action.WRITE));

    // update should succeed for bob on ds13 since bob has ADMIN
    datasetBobClient.update(ds13, Collections.<String, String>emptyMap());
    verifyStreamReadWritePrivilege(streamBobClient, stream13, Collections.<Action>emptySet());
    verifyStreamReadWritePrivilege(streamBobClient, stream23, Sets.newHashSet(Action.READ, Action.WRITE));


    // revoke privileges from BOB and grant them to alice
    for (Privilege privilege : bobPrivileges) {
      authorizationTestClient.wildCardRevoke(BOB, privilege.getAuthorizable(), privilege.getAction());
      authorizationTestClient.wildCardGrant(ALICE, privilege.getAuthorizable(), privilege.getAction());
    }
    bobPrivileges.clear();
    authorizationTestClient.revoke(EVE, stream22, Action.EXECUTE);

    Tasks.waitFor(true, new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        try {
          verifyStreamReadWritePrivilege(streamBobClient, stream23, Collections.<Action>emptySet());
          return true;
        } catch (Throwable t) {
          return false;
        }
      }
    }, 2 * cacheTimeout + 5, TimeUnit.SECONDS, 500, TimeUnit.MILLISECONDS);

    try {
      datasetBobClient.update(ds13, Collections.<String, String>emptyMap());
      Assert.fail();
    } catch (UnauthorizedException e) {
      // expected
    }
    verifyStreamReadWritePrivilege(streamBobClient, stream13, Collections.<Action>emptySet());
    verifyStreamReadWritePrivilege(streamBobClient, stream23, Collections.<Action>emptySet());
  }

  /**
   * Test basic privileges for dataset.
   */
  @Test
  public void testDatasetPrivileges() throws Exception {
    ClientConfig adminConfig = getClientConfig(fetchAccessToken(ADMIN_USER, ADMIN_USER));
    RESTClient adminClient = new RESTClient(adminConfig);
    adminClient.addListener(createRestClientListener());

    DatasetId testDatasetinstance = testNamespace.getNamespaceId().dataset("testDatasetPrivileges");

    // pre-grant all required privileges
    // admin user can create ns and dataset
    authorizationTestClient.grant(ADMIN_USER, testNamespace.getNamespaceId(), Action.ADMIN);
    authorizationTestClient.grant(ADMIN_USER, testDatasetinstance, Action.ADMIN);
    String principal = testNamespace.getConfig().getPrincipal();
    if (principal != null) {
      authorizationTestClient.grant(ADMIN_USER, new KerberosPrincipalId(principal), Action.ADMIN);
    }
    // eve can read from the dataset
    authorizationTestClient.grant(EVE, testDatasetinstance, Action.READ);
    authorizationTestClient.waitForAuthzCacheTimeout();

    createAndRegisterNamespace(testNamespace, adminConfig, adminClient);
    DatasetClient datasetAdminClient = new DatasetClient(adminConfig, adminClient);

    // Create, truncate, update should all succeed
    datasetAdminClient.create(testDatasetinstance, "table");
    Assert.assertTrue(datasetAdminClient.exists(testDatasetinstance));
    Assert.assertEquals(1, datasetAdminClient.list(testDatasetinstance.getNamespaceId()).size());
    Assert.assertNotNull(datasetAdminClient.get(testDatasetinstance));

    datasetAdminClient.truncate(testDatasetinstance);
    datasetAdminClient.update(testDatasetinstance, new HashMap<String, String>());

    ClientConfig aliceConfig = getClientConfig(fetchAccessToken(ALICE, ALICE + PASSWORD_SUFFIX));
    RESTClient aliceClinet = new RESTClient(aliceConfig);
    aliceClinet.addListener(createRestClientListener());

    // alice can't see the dataset yet
    try {
      new DatasetClient(aliceConfig, aliceClinet).exists(testDatasetinstance);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // Expected
    }

    ClientConfig eveConfig = getClientConfig(fetchAccessToken(EVE, EVE + PASSWORD_SUFFIX));
    RESTClient eveClient = new RESTClient(eveConfig);
    eveClient.addListener(createRestClientListener());
    DatasetClient datasetClient = new DatasetClient(eveConfig, eveClient);

    // Listing the dataset should succeed
    Assert.assertEquals(true, datasetClient.exists(testDatasetinstance));
    Assert.assertEquals(1, datasetClient.list(testDatasetinstance.getNamespaceId()).size());
    Assert.assertNotNull(datasetClient.get(testDatasetinstance));

    // truncating the dataset should fail
    try {
      datasetClient.truncate(testDatasetinstance);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // Expected
    }

    // updating the dataset should fail
    try {
      datasetClient.update(testDatasetinstance, new HashMap<String, String>());
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // Expected
    }

    // deleting the dataset should fail
    try {
      datasetClient.delete(testDatasetinstance);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // Expected
    }

    // ADMIN_USER should be able to delete the dataset
    datasetAdminClient.delete(testDatasetinstance);
  }

  /**
   * Test stream privileges.
   */
  @Test
  public void testStreamPrivileges() throws Exception {
    ClientConfig adminConfig = getClientConfig(fetchAccessToken(ADMIN_USER, ADMIN_USER));
    RESTClient adminClient = new RESTClient(adminConfig);
    adminClient.addListener(createRestClientListener());
    StreamId streamId = testNamespace.getNamespaceId().stream("testStreamPrivileges");

    // pre-grant all required privileges
    // admin can create all entities
    authorizationTestClient.grant(ADMIN_USER, testNamespace.getNamespaceId(), Action.ADMIN);
    authorizationTestClient.grant(ADMIN_USER, streamId, Action.ADMIN);
    String principal = testNamespace.getConfig().getPrincipal();
    if (principal != null) {
      authorizationTestClient.grant(ADMIN_USER, new KerberosPrincipalId(principal), Action.ADMIN);
    }
    // Alice can write to the stream
    authorizationTestClient.grant(ALICE, streamId, Action.WRITE);
    // Bob can read the stream
    authorizationTestClient.grant(BOB, streamId, Action.READ);
    authorizationTestClient.waitForAuthzCacheTimeout();

    createAndRegisterNamespace(testNamespace, adminConfig, adminClient);

    // Create a stream with Admin
    StreamClient adminStreamClient = new StreamClient(adminConfig, adminClient);
    adminStreamClient.create(streamId);
    Assert.assertEquals(1, adminStreamClient.list(testNamespace.getNamespaceId()).size());
    Assert.assertNotNull(adminStreamClient.getConfig(streamId));

    adminStreamClient.truncate(streamId);

    // admin doesn't have WRITE privilege on the stream, so the following actions will fail
    try {
      adminStreamClient.sendEvent(streamId, "an event");
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // expected
    }

    try {
      adminStreamClient.getEvents(streamId, 0, Long.MAX_VALUE, Integer.MAX_VALUE, new ArrayList<StreamEvent>());
      Assert.fail();
    } catch (UnauthorizedException ex) {
       Assert.assertTrue(ex.getMessage().toLowerCase().contains(NO_PRIVILEGE_MESG.toLowerCase()));
    }

    ClientConfig bobConfig = getClientConfig(fetchAccessToken(BOB, BOB + PASSWORD_SUFFIX));
    RESTClient bobClient = new RESTClient(bobConfig);
    bobClient.addListener(createRestClientListener());
    StreamClient bobStreamClient = new StreamClient(bobConfig, bobClient);

    // Bob can read from but not write to the stream
    bobStreamClient.getEvents(streamId, 0, Long.MAX_VALUE, Integer.MAX_VALUE, new ArrayList<StreamEvent>());
    try {
      bobStreamClient.sendEvent(streamId, "an event");
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // expected
    }

    ClientConfig aliceConfig = getClientConfig(fetchAccessToken(ALICE, ALICE + PASSWORD_SUFFIX));
    RESTClient aliceClient = new RESTClient(bobConfig);
    bobClient.addListener(createRestClientListener());
    StreamClient aliceStreamClient = new StreamClient(aliceConfig, aliceClient);

    // Alice can write to but not read from the stream
    aliceStreamClient.sendEvent(streamId, "an event");
    try {
      aliceStreamClient.getEvents(streamId, 0, Long.MAX_VALUE, Integer.MAX_VALUE, new ArrayList<StreamEvent>());
      Assert.fail();
    } catch (UnauthorizedException ex) {
       Assert.assertTrue(ex.getMessage().toLowerCase().contains(NO_PRIVILEGE_MESG.toLowerCase()));
    }

    // neither Bob nor Alice can drop the stream
    try {
      bobStreamClient.delete(streamId);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // expected
    }
    try {
      aliceStreamClient.delete(streamId);
      Assert.fail();
    } catch (UnauthorizedException ex) {
      // expected
    }

    // only admin can drop the stream
    adminStreamClient.delete(streamId);
  }

  /**
   * Test delete namespace with two different clients, deletion should work for both clients
   */
  @Test
  public void testDeleteNamespaceWithDifferentClients() throws Exception {
    ClientConfig adminConfig = getClientConfig(fetchAccessToken(ADMIN_USER, ADMIN_USER));
    RESTClient adminClient = new RESTClient(adminConfig);
    adminClient.addListener(createRestClientListener());

    String namespacePrincipal = testNamespace.getConfig().getPrincipal();
    authorizationTestClient.grant(ALICE, testNamespace.getNamespaceId(), Action.ADMIN);
    authorizationTestClient.grant(EVE, testNamespace.getNamespaceId(), Action.ADMIN);
    if (namespacePrincipal != null) {
      authorizationTestClient.grant(ALICE, new KerberosPrincipalId(namespacePrincipal), Action.ADMIN);
      authorizationTestClient.grant(EVE, new KerberosPrincipalId(namespacePrincipal), Action.ADMIN);
    }
    authorizationTestClient.waitForAuthzCacheTimeout();

    createAndDeleteNamespace(testNamespace, ALICE);
    createAndDeleteNamespace(testNamespace, EVE);
  }

  private void createAndDeleteNamespace(NamespaceMeta namespaceMeta, String user) throws Exception {
    ClientConfig clientConfig = getClientConfig(fetchAccessToken(user, user + PASSWORD_SUFFIX));
    RESTClient client = new RESTClient(clientConfig);
    client.addListener(createRestClientListener());

    // create namespace with client
    createAndRegisterNamespace(namespaceMeta, clientConfig, client);

    // verify namespace exists
    NamespaceClient namespaceClient = new NamespaceClient(clientConfig, client);
    Assert.assertTrue(namespaceClient.exists(namespaceMeta.getNamespaceId()));

    // delete it and verify it is gone
    namespaceClient.delete(namespaceMeta.getNamespaceId());
    Assert.assertFalse(namespaceClient.exists(namespaceMeta.getNamespaceId()));
  }

  private Set<DatasetId> toDatasetId(final NamespaceId namespaceId, List<DatasetSpecificationSummary> list) {
    return Sets.newHashSet(Lists.transform(list, new Function<DatasetSpecificationSummary, DatasetId>() {
      @Override
      public DatasetId apply(DatasetSpecificationSummary input) {
        return namespaceId.dataset(input.getName());
      }
    }));
  }

  private Set<StreamId> toStreamId(final NamespaceId namespaceId, List<StreamDetail> list) {
    return Sets.newHashSet(Lists.transform(list, new Function<StreamDetail,
      StreamId>() {
      @Override
      public StreamId apply(StreamDetail input) {
        return namespaceId.stream(input.getName());
      }
    }));
  }
}