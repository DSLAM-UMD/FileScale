package org.apache.hadoop.hdfs.server.namenode;

import static java.util.concurrent.TimeUnit.*;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.util.Set;
import java.util.concurrent.*;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class INodeKeyedObjects {
  private static IndexedCache<CompositeKey, INode> cache;

  private static Set<Long> concurrentHashSet;

  private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  static final Logger LOG = LoggerFactory.getLogger(INodeKeyedObjects.class);

  INodeKeyedObjects() {}

  public static Set<Long> getBackupSet() {
    if (concurrentHashSet == null) {
      ConcurrentHashMap<Long, Integer> map = new ConcurrentHashMap<>();
      concurrentHashSet = map.newKeySet();
    }
    return concurrentHashSet;
  }

  public static void BackupSetToDB() {
    final int num = 1000;

    final Runnable updateToDB =
        new Runnable() {
          public void run() {
            if (concurrentHashSet.size() >= num) {
              int i = 0;
              for (Long id : concurrentHashSet) {
                INode inode = INodeKeyedObjects.getCache().getIfPresent(Long.class, id);
                if (inode.isDirectory()) {
                  inode.asDirectory().updateINodeDirectory();
                } else {
                  inode.asFile().updateINodeFile();
                }
                if (++i >= num) break;
              }
            }
          }
        };

    final ScheduledFuture<?> updateHandle = scheduler.schedule(updateToDB, 5, SECONDS);

    scheduler.schedule(
        new Runnable() {
          public void run() {
            updateHandle.cancel(true);
          }
        },
        60 * 60,
        SECONDS);
  }

  // --------------------------------------------------------
  // caffeine cache

  public static IndexedCache<CompositeKey, INode> getCache() {
    if (cache == null) {
      concurrentHashSet = ConcurrentHashMap.newKeySet();
      // https://github.com/ben-manes/caffeine/wiki/Removal
      Caffeine<Object, Object> cfein =
          Caffeine.newBuilder()
              .removalListener(
                  (Object keys, Object value, RemovalCause cause) -> {
                    if (cause == RemovalCause.EXPLICIT
                        || cause == RemovalCause.COLLECTED
                        || cause == RemovalCause.EXPIRED
                        || cause == RemovalCause.SIZE) {
                      if (LOG.isInfoEnabled()) {
                        LOG.info("Cache Evicted: INode = " + ((CompositeKey) keys).getK1());
                      }
                      // stored procedure: update inode in db
                      INode inode = (INode) value;
                      if (inode.isDirectory()) {
                        inode.asDirectory().updateINodeDirectory();
                      } else {
                        inode.asFile().updateINodeFile();
                      }
                    }
                  })
              .maximumSize(100_000);
      cache =
          new IndexedCache.Builder<CompositeKey, INode>()
              .withIndex(Long.class, ck -> ck.getK1())
              .withIndex(Pair.class, ck -> ck.getK3())
              .buildFromCaffeine(cfein);
    }
    return cache;
  }
}
