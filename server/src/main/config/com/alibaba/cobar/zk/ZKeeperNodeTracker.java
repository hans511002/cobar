package com.alibaba.cobar.zk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkDataListener;
import org.I0Itec.zkclient.ZkClient;

public abstract class ZKeeperNodeTracker {

	protected String path;
	protected boolean listenerChild = true;// 是否监听数据
	private IZkChildListener zkChildListener;
	protected boolean listenerData = true;// 是否监听数据
	private IZkDataListener zkDataListener;
	protected boolean started = false;// 是否已经启动监听
	protected boolean isFirstInit = true;
	ZkClient zkClient;// zk客户端

	Map<String, ZKeeperNodeTracker> subPaths = new HashMap<String, ZKeeperNodeTracker>();

	/**
	 * Constructs a new ZK node tracker.
	 * 
	 * @param node
	 * @param zkClient
	 * @param daemonMaster
	 */
	public ZKeeperNodeTracker(String node) {
		if (node.startsWith("/")) {
			this.path = node;
		} else {
			this.path = ZkConstant.BASE_NODE + "/" + node;
		}
	}

	public boolean isFirstInited() {
		return !isFirstInit;
	}

	/**
	 * 确定哪些需要监听
	 * 
	 * @param child
	 *            是否监听子节点变更
	 * @param data
	 *            是否监听数据
	 */
	public void enableListener(boolean child, boolean data) {
		listenerChild = child;
		listenerData = data;
	}

	/**
	 * Starts the tracking of the node in ZooKeeper.
	 */
	public synchronized void startListener() {
		if (!started) {
			if (listenerChild) {
				initZkChildListener();
				zkClient.subscribeChildChanges(path, zkChildListener);
			}
			if (listenerData) {
				initZkDataListener();
				zkClient.subscribeDataChanges(path, zkDataListener);
			}
			started = true;
		}
	}

	public synchronized void stopListener() {
		if (listenerChild) {
			zkClient.unsubscribeChildChanges(path, zkChildListener);
		}
		if (listenerData) {
			zkClient.unsubscribeDataChanges(path, zkDataListener);
		}
		started = false;
	}

	public static class ZkChildListener implements IZkChildListener {
		ZKeeperNodeTracker tracker;
		List<String> strings = null;

		public ZkChildListener(ZKeeperNodeTracker zKeeperNodeTracker) {
			this.tracker = zKeeperNodeTracker;
		}

		@Override
		public void handleChildChange(String s, List<String> strings) throws Exception {
			if (!tracker.path.equals(s)) {
				return;
			}
			tracker.nodeChildrenChanged(strings);
		}
	}

	public static class ZkDataListener implements IZkDataListener {
		ZKeeperNodeTracker tracker;

		public ZkDataListener(ZKeeperNodeTracker zKeeperNodeTracker) {
			this.tracker = zKeeperNodeTracker;
		}

		@Override
		public void handleDataChange(String s, Object o) throws Exception {
			if (tracker.path.equals(s)) {
				tracker.nodeDataChanged(o);
			} else if (s.startsWith(tracker.path + "/")) {// 子节点数据变更
				String node = s.substring(tracker.path.length() + 1);
				tracker.subNodeDataChanged(node, o);
			}
		}

		@Override
		public void handleDataDeleted(String s) throws Exception {

			if (tracker.path.equals(s)) {
				tracker.nodeDeleted();
			} else if (s.startsWith(tracker.path + "/")) {// 子节点数据变更
				String node = s.substring(tracker.path.length() + 1);
				if (node.indexOf("/") == -1) {
					tracker.subNodeDeleted(node);
				}
			}
		}
	}

	void initZkChildListener() {
		if (zkChildListener != null) {
			return;
		}
		zkChildListener = new ZkChildListener(this);
	}

	void initZkDataListener() {
		if (zkDataListener != null) {
			return;
		}
		zkDataListener = new ZkDataListener(this);
	}

	public synchronized void startSubListener(String subNode) {
		if (!subPaths.containsKey(subNode)) {
			subPaths.put(subNode, this);
			if (zkDataListener == null) {
				initZkDataListener();
			}
			zkClient.subscribeDataChanges(path + "/" + subNode, zkDataListener);
		}
	}

	public synchronized void stopSubListener(String subNode) {
		if (subPaths.containsKey(subNode) && zkDataListener != null) {
			subPaths.remove(subNode);
			zkClient.unsubscribeDataChanges(path + "/" + subNode, zkDataListener);
		}
	}

	public abstract void start();

	public void stop() {
		for (String pathNode : subPaths.keySet()) {
			stopSubListener(pathNode);
		}
		stopListener();
		subPaths.clear();
		started = false;
	}

	public boolean isStarted() {
		return started;
	}

	// 子节点变更
	public void nodeChildrenChanged(List<String> childs) {
	}

	// 节点数据变更
	public void subNodeDataChanged(String node, Object data) {
	}

	public void subNodeDeleted(String node) {

	}

	// 节点数据变更
	public void nodeDataChanged(Object data) {
	}

	// 节点数据删除
	public void nodeDeleted() {
	}

}
