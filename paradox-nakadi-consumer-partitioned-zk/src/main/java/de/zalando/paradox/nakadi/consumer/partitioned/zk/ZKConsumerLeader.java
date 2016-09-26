package de.zalando.paradox.nakadi.consumer.partitioned.zk;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.CancelLeadershipException;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.curator.framework.state.ConnectionState;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import de.zalando.paradox.nakadi.consumer.core.utils.ThrowableUtils;

abstract class ZKConsumerLeader<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZKConsumerLeader.class);

    private final ZKMember member;

    private final ZKHolder zkHolder;

    private final String consumerName;

    ZKConsumerLeader(final ZKHolder zkHolder, final String consumerName, final ZKMember member) {
        this.zkHolder = requireNonNull(zkHolder, "zkHolder must not be null");
        this.consumerName = requireNonNull(consumerName, "consumerName must not be null");
        this.member = requireNonNull(member, "member must not be null");
    }

    interface LeadershipChangedListener<T> {
        void takeLeadership(final T t, final ZKMember member);

        void relinquishLeadership(final T t, final ZKMember member);
    }

    public abstract String getLeaderSelectorPath(final T t);

    public abstract String getLeaderInfoPath(final T t);

    private ConcurrentMap<T, LeaderControl> leaderControls = new ConcurrentHashMap<>();

    private static class LeaderControl {
        private final LeaderSelector selector;
        private final CountDownLatch stop = new CountDownLatch(1);
        private volatile boolean leader;

        LeaderControl(final LeaderSelector selector) {
            this.selector = selector;
        }

        static LeaderControl of(final LeaderSelector selector) {
            return new LeaderControl(selector);
        }

        void takeLeadership() throws InterruptedException {
            this.leader = true;
            this.stop.await();
        }

        void relinquishLeadership() {
            this.leader = false;
            this.stop.countDown();
        }

        boolean isLeader() {
            return leader;
        }

        private Collection<Participant> getParticipants() {
            try {
                return selector.getParticipants();
            } catch (Exception e) {
                ThrowableUtils.throwException(e);
                return null;
            }
        }
    }

    void initGroupLeadership(final T t, final LeadershipChangedListener<T> delegate) throws Exception {

        if (leaderControls.containsKey(t)) {
            return;
        }

        final LeaderSelector selector = new LeaderSelector(zkHolder.getCurator(), getLeaderSelectorPath(t),
                new LeaderSelectorListener() {
                    @Override
                    public void takeLeadership(final CuratorFramework client) throws Exception {
                        LOGGER.info("Member [{}] took leadership for [{}]", member.getMemberId(), t);
                        try {
                            final LeaderControl leaderControl = leaderControls.get(t);
                            Preconditions.checkState(leaderControl != null && !leaderControl.isLeader(),
                                "Leader control for [%s] in incorrect state", t);

                            // mark in zookeeper / information only
                            setLeaderInfo(getLeaderInfoPath(t), member.getMemberId());

                            delegate.takeLeadership(t, member);
                            leaderControl.takeLeadership();
                        } finally {
                            try {
                                LOGGER.info("Member [{}] relinquished leadership for [{}]", member.getMemberId(), t);
                                leaderControls.remove(t);
                            } finally {
                                delegate.relinquishLeadership(t, member);
                            }
                        }
                    }

                    @Override
                    public void stateChanged(final CuratorFramework client, final ConnectionState connectionState) {
                        final LeaderControl leaderControl = leaderControls.get(t);
                        LOGGER.info("Member [{}] connection state [{}] changed", member.getMemberId(), t);

                        if (null != leaderControl && leaderControl.isLeader()) {
                            switch (connectionState) {

                                case LOST :
                                case SUSPENDED :
                                    leaderControl.relinquishLeadership();
                                    throw new CancelLeadershipException("State " + connectionState + ":" + t);

                                default :
                                    break;
                            }
                        }
                    }

                });
        selector.setId(member.getMemberId());

        if (null == leaderControls.putIfAbsent(t, LeaderControl.of(selector))) {
            LOGGER.info("Init member [{}] leadership for [{}]", member.getMemberId(), t);

            // restart selector every time the leadership is lost
            try {
                selector.start();
            } catch (final Throwable throwable) {
                leaderControls.remove(t);
                ThrowableUtils.throwException(throwable);
            }
        }
    }

    public Map<String, Boolean> getParticipants(final T t) {
        return Optional.ofNullable(leaderControls.get(t)).map((leaderControl) ->
                               leaderControl.getParticipants().stream().collect(
                                   Collectors.toMap(Participant::getId, Participant::isLeader, (u, v) -> u))).orElseGet(
                           Collections::emptyMap);
    }

    public void closeGroupLeadership(final T t) {
        final LeaderControl leaderControl = leaderControls.remove(t);
        if (null != leaderControl) {
            LOGGER.info("Close member [{}] leadership for [{}]", member.getMemberId(), t);
            leaderControl.relinquishLeadership();
        }
    }

    public void close() {
        LOGGER.info("Closing for member [{}]", member.getMemberId());

        leaderControls.values().forEach(LeaderControl::relinquishLeadership);

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            ThrowableUtils.throwException(e);
        }

        leaderControls.values().forEach(leaderControl -> {
            try {
                leaderControl.selector.close();
            } catch (Exception e) {
                LOGGER.warn("Unexpected error while closing leader selector [{}]", e);
            }
        });
        leaderControls.clear();
    }

    public String getConsumerName() {
        return consumerName;
    }

    private void setLeaderInfo(final String path, final String memberId) throws Exception {
        final CuratorFramework curator = zkHolder.getCurator();
        try {
            curator.setData().forPath(path, memberId.getBytes("UTF-8"));
        } catch (KeeperException.NoNodeException e) {
            LOGGER.info("Set failed, no leader info node [{}]. Create new node", path);
            curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
            curator.setData().forPath(path, memberId.getBytes("UTF-8"));
        }
    }
}
