/** Copyright (c) 2019 Aiven, Helsinki, Finland. https://aiven.io/
 */

package io.aiven.kafka.auth;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import kafka.network.RequestChannel.Session;
import kafka.security.auth.Acl;
import kafka.security.auth.Authorizer;
import kafka.security.auth.Operation;
import kafka.security.auth.Resource;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AivenAclAuthorizer implements Authorizer {
  private static final Logger logger = LoggerFactory.getLogger(AivenAclAuthorizer.class);
  private String configFileLocation;
  private ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
  private long lastUpdateCheckTimestamp = 0;
  private long lastModifiedTimestamp = 0;
  private List<AivenAclEntry> aclEntries;
  private Map<String, Boolean> verdictCache;

  public AivenAclAuthorizer() {
  }

  /** Read config file and populate ACL entries, if the config file has changed.
   * This function assumes appropriate synchronization by caller. */
  private void checkAndUpdateConfig() {
    File configFile = new File(configFileLocation);
    long configFileLastModified = configFile.lastModified();

    if (configFileLastModified != lastModifiedTimestamp) {
      logger.info("Reloading ACL configuration {}", configFileLocation);
      List<AivenAclEntry> newAclEntries = new ArrayList<AivenAclEntry>();
      JSONParser parser = new JSONParser();
      try {
        Object obj = parser.parse(new FileReader(configFile));
        JSONArray root = (JSONArray) obj;
        Iterator<JSONObject> iter = root.iterator();
        while (iter.hasNext()) {
          JSONObject node = iter.next();
          newAclEntries.add(
              new AivenAclEntry(
                  (String)node.get("principal_type"),
                  (String)node.get("principal"),
                  (String)node.get("operation"),
                  (String)node.get("resource"),
                  (String)node.get("resource_pattern")
              )
          );
        }

        // initialize cache for non-trivial ACLs
        if (newAclEntries.size() > 10) {
          if (verdictCache != null) {
            verdictCache.clear();
          } else {
            verdictCache = new ConcurrentHashMap<String, Boolean>();
          }
        } else {
          verdictCache = null;
        }

        aclEntries = newAclEntries;
      } catch (IOException | ParseException ex) {
        logger.error("Failed to read configuration file", ex);
      }
    }
    lastModifiedTimestamp = configFileLastModified;
  }

  /** Reload ACLs from disk under a write lock. */
  public boolean reloadAcls() {
    lock.writeLock().lock();
    long previousUpdate = lastModifiedTimestamp;
    try {
      checkAndUpdateConfig();
      lastUpdateCheckTimestamp = System.nanoTime() / 1000000;
      return (previousUpdate != lastModifiedTimestamp);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void configure(java.util.Map<String, ?> configs) {
    configFileLocation = (String) configs.get("aiven.acl.authorizer.configuration");
    checkAndUpdateConfig();
  }

  @Override
  public void close() {
  }

  @Override
  public boolean authorize(Session session, Operation operationObj, Resource resourceObj) {
    KafkaPrincipal principal = session.principal();
    if (principal == null) {
      principal = KafkaPrincipal.ANONYMOUS;
    }

    String principalName = principal.getName();
    String principalType = principal.getPrincipalType();
    String operation = operationObj.name();
    String resource = resourceObj.resourceType() + ":" + resourceObj.name();

    return checkAcl(principalType, principalName, operation, resource);
  }

  /** Authorize a single request. */
  public boolean checkAcl(
          String principalType,
          String principalName,
          String operation,
          String resource) {
    long now = System.nanoTime() / 1000000; // nanoTime is monotonic, convert to milliseconds
    boolean verdict = false;
    String cacheKey = null;

    // only ever try to cache user matches
    if (principalType.equals(KafkaPrincipal.USER_TYPE)) {
      cacheKey = resource + "|" + operation + "|" + principalName + "|" + principalType;
    }

    // we loop here until we can evaluate the access with fresh configuration
    while (true) {
      // First, check if we have a fresh config, and if so, evaluate access request
      lock.readLock().lock();
      try {
        if (lastUpdateCheckTimestamp + 10000 > now) {
          if (cacheKey != null && verdictCache != null) {
            Boolean cachedVerdict = verdictCache.get(cacheKey);
            if (cachedVerdict != null) {
              verdict = cachedVerdict.booleanValue();
              if (verdict == true) {
                logger.debug("[ALLOW] Auth request {} on {} by {} {} (cached)",
                        operation, resource, principalType, principalName);
              } else {
                logger.info("[DENY] Auth request {} on {} by {} {} (cached)",
                        operation, resource, principalType, principalName);
              }
              return verdict;
            }
          }

          Iterator<AivenAclEntry> iter = aclEntries.iterator();
          while (verdict == false && iter.hasNext()) {
            AivenAclEntry aclEntry = iter.next();
            if (aclEntry.check(principalType, principalName, operation, resource)) {
              verdict = true;
            }
          }
          if (verdict == true) {
            logger.debug("[ALLOW] Auth request {} on {} by {} {}",
                    operation, resource, principalType, principalName);
          } else {
            logger.info("[DENY] Auth request {} on {} by {} {}",
                    operation, resource, principalType, principalName);
          }
          if (cacheKey != null && verdictCache != null) {
            verdictCache.put(cacheKey, new Boolean(verdict));
          }
          return verdict;
        }
      } finally {
        lock.readLock().unlock();
      }

      // We may need to update the config
      lock.writeLock().lock();
      try {
        // Recheck the timer, as an another thread may have updated config
        // while we waited for the lock.
        if (lastUpdateCheckTimestamp + 10000 <= now) {
          lastUpdateCheckTimestamp = now;
          checkAndUpdateConfig();
        }
      } finally {
        lock.writeLock().unlock();
      }
    }
  }

  @Override
  public scala.collection.immutable.Set<Acl> getAcls(Resource resource) {
    logger.error("getAcls(Resource) is not implemented");
    return new scala.collection.immutable.HashSet<Acl>();
  }

  @Override
  public scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>>
      getAcls(KafkaPrincipal principal) {
    logger.error("getAcls(KafkaPrincipal) is not implemented");
    return new scala.collection.immutable.HashMap<Resource, scala.collection.immutable.Set<Acl>>();
  }

  @Override
  public scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>> getAcls() {
    logger.error("getAcls() is not implemented");
    return new scala.collection.immutable.HashMap<Resource, scala.collection.immutable.Set<Acl>>();
  }

  @Override
  public boolean removeAcls(scala.collection.immutable.Set<Acl> acls, Resource resource) {
    logger.error("removeAcls(Set<Acl>, Resource) is not implemented");
    return false;
  }

  @Override
  public boolean removeAcls(Resource resource) {
    logger.error("removeAcls(Resource) is not implemented");
    return false;
  }

  @Override
  public void addAcls(scala.collection.immutable.Set<Acl> acls, Resource resource) {
    logger.error("addAcls(Set<Acl>, Resource) is not implemented");
  }
}
