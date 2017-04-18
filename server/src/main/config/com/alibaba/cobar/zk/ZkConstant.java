package com.alibaba.cobar.zk;

public class ZkConstant {
	// 节点目录名
	public static final String BASE_NODE = "/" + System.getProperty("zk.base.node", "cobar") + "/"
			+ System.getProperty("cluster.name");// 跟节点

}
